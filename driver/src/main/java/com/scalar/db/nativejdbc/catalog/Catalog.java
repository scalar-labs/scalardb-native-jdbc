package com.scalar.db.nativejdbc.catalog;

import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.engine.Engine.QualifiedName;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells, for a table name as a statement writes it, what the driver knows about the table: a {@link
 * TableInfo} built from ScalarDB's two catalog tables, {@code virtual_tables} and {@code metadata}
 * in ScalarDB's system schema.
 *
 * <p>Two catalogs are consulted, in this order, and never guessed at:
 *
 * <ol>
 *   <li><b>The database's own catalog</b> says which stored table or view the name denotes, given
 *       the session's schemas ({@link Engine#resolve}). This is what makes the lookup correct where
 *       the engines differ: Oracle folds unquoted names to upper case and PostgreSQL to lower case,
 *       SQL Server compares without regard to case, a plain table earlier in a PostgreSQL {@code
 *       search_path} shadows a ScalarDB table later in it, and an Oracle synonym may stand for a
 *       table in another schema. The answer is the stored spelling.
 *   <li><b>ScalarDB's catalog</b>, loaded whole on a connection of this class's own and refreshed
 *       after {@code ttl} or on {@link #invalidate}, then classifies the stored name: a {@code
 *       virtual_tables} row named by it is a created decoupled table (INNER join) or the virtual
 *       table of an imported one, which a statement must not name; a row whose left source it is
 *       marks an imported decoupled table (LEFT_OUTER) or the data table of a created one, which a
 *       statement must not name either; a row whose right source it is marks a transaction metadata
 *       table, refused as well; a {@code metadata} entry carrying the transaction columns is a
 *       classic-layout table; anything else is plain. ScalarDB records the spelling the operator
 *       typed, which on an engine that compares table names without regard to case may differ from
 *       the stored one, so where the database compares that way ({@link
 *       Engine#tableNamesCaseInsensitive}) so does this classification.
 * </ol>
 *
 * <p>A database without ScalarDB's catalog is an error unless the catalog was told to allow it: a
 * silent "every table is plain" is also what a mistyped schema name, or a user without rights on
 * the catalog tables, would produce, and on Oracle "does not exist" is the answer to both.
 *
 * <p>Nothing runs on the user's connection: the catalog's queries would otherwise fix the snapshot
 * of the user's transaction before the statement the design requires to come first, and a failure
 * would abort that transaction. The session schemas are read from the user's connection by the
 * caller, outside a transaction ({@link Engine#sessionSchemas}), and passed in. No lock is held
 * across a round trip: a stale catalog is served while one thread reloads it, and a name is
 * resolved on the catalog connection outside the cache's lock.
 */
public final class Catalog implements AutoCloseable {

  /** ScalarDB's default system namespace, where its catalog tables live. */
  public static final String DEFAULT_METADATA_SCHEMA = "scalardb";

  /** How long a loaded catalog and the names resolved against it are trusted. */
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

  private static final Logger LOG = LoggerFactory.getLogger(Catalog.class);

  private static final int RESOLUTION_CACHE_SIZE = 4096;

  /** How long one round trip on the catalog's own connection may take. */
  private static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(30);

  /** Runs the vendor drivers' network-timeout aborts; daemon threads, so a JVM can exit. */
  private static final Executor TIMEOUTS =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "scalardb-native-jdbc-catalog-timeout");
            t.setDaemon(true);
            return t;
          });

  private static final Set<String> TRANSACTION_COLUMNS =
      Set.of(
          "tx_id",
          "tx_state",
          "tx_version",
          "tx_prepared_at",
          "tx_committed_at",
          "before_tx_id",
          "before_tx_state",
          "before_tx_version",
          "before_tx_prepared_at",
          "before_tx_committed_at");

  /** What a schema name given to this class may look like: a plain SQL identifier. */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** Opens a connection to the database with the application's credentials. */
  @FunctionalInterface
  public interface ConnectionSupplier {
    /**
     * Opens a connection. The catalog puts it in autocommit mode itself.
     *
     * @return the connection
     * @throws SQLException if the database cannot be reached
     */
    Connection open() throws SQLException;
  }

  private final Engine engine;
  private final String metadataSchema;
  private final ConnectionSupplier connections;
  private final Duration ttl;
  private final Clock clock;
  private final boolean allowAbsent;

  private volatile @Nullable Snapshot snapshot;
  private final ReentrantLock loadLock = new ReentrantLock();

  private final ReentrantLock connectionLock = new ReentrantLock();
  private @Nullable Connection connection; // guarded by connectionLock
  private volatile boolean closed;

  /**
   * A resolved name with the instant it was resolved at, and the classification of that name
   * against one snapshot, kept so that a hit costs a map lookup rather than a rebuilt {@link
   * TableInfo}.
   */
  private static final class Resolution {
    final @Nullable QualifiedName name;
    final Instant resolvedAt;
    volatile @Nullable Classified classified;

    Resolution(@Nullable QualifiedName name, Instant resolvedAt) {
      this.name = name;
      this.resolvedAt = resolvedAt;
    }
  }

  /** What a snapshot said about a name: a table, or a refusal to rethrow. */
  private static final class Classified {
    final Snapshot snapshot;
    final @Nullable TableInfo info;
    final @Nullable SQLException refusal;

    Classified(Snapshot snapshot, @Nullable TableInfo info, @Nullable SQLException refusal) {
      this.snapshot = snapshot;
      this.info = info;
      this.refusal = refusal;
    }

    TableInfo get() throws SQLException {
      if (info != null) {
        return info;
      }
      // a fresh exception each time, of the same kind, so the stack trace is the caller's
      SQLException r = Objects.requireNonNull(refusal);
      if (r instanceof SQLSyntaxErrorException) {
        throw new SQLSyntaxErrorException(r.getMessage(), r.getSQLState(), r.getErrorCode());
      }
      throw new SQLNonTransientException(r.getMessage(), r.getSQLState(), r.getErrorCode());
    }
  }

  private final Map<String, String> folds =
      new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
          return size() > RESOLUTION_CACHE_SIZE;
        }
      };

  private final Map<String, Resolution> resolutions =
      new LinkedHashMap<String, Resolution>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Resolution> eldest) {
          return size() > RESOLUTION_CACHE_SIZE;
        }
      };

  /**
   * Creates a catalog for one database that requires ScalarDB's catalog to exist and trusts what it
   * loads for {@link #DEFAULT_TTL}.
   *
   * @param engine the database engine
   * @param metadataSchema the schema that holds {@code virtual_tables} and {@code metadata},
   *     {@value #DEFAULT_METADATA_SCHEMA} unless ScalarDB is configured otherwise
   * @param connections how to open a connection of the catalog's own
   */
  public Catalog(Engine engine, String metadataSchema, ConnectionSupplier connections) {
    this(engine, metadataSchema, connections, DEFAULT_TTL, Clock.systemUTC(), false);
  }

  /**
   * Creates a catalog for one database.
   *
   * @param engine the database engine
   * @param metadataSchema the schema that holds ScalarDB's catalog tables
   * @param connections how to open a connection of the catalog's own
   * @param ttl how long a loaded catalog is trusted before it is read again
   * @param clock the clock that measures {@code ttl}
   * @param allowAbsent whether a database whose {@code metadataSchema} does not exist is accepted,
   *     with every table plain, rather than refused
   */
  public Catalog(
      Engine engine,
      String metadataSchema,
      ConnectionSupplier connections,
      Duration ttl,
      Clock clock,
      boolean allowAbsent) {
    if (!IDENTIFIER.matcher(metadataSchema).matches()) {
      throw new IllegalArgumentException("Not a plain identifier: " + metadataSchema);
    }
    this.engine = engine;
    this.metadataSchema = metadataSchema;
    this.connections = connections;
    this.ttl = ttl;
    this.clock = clock;
    this.allowAbsent = allowAbsent;
  }

  /**
   * Returns the engine this catalog speaks to.
   *
   * @return the engine
   */
  public Engine engine() {
    return engine;
  }

  /**
   * Looks a table name up as a statement writes it.
   *
   * @param sessionSchemas the schemas of the user's session, from {@link Engine#sessionSchemas}
   * @param rawSchema the schema as written, quotes included, or {@code null} when unqualified
   * @param rawTable the table as written, quotes included
   * @return what the driver knows about the table the engine resolves the name to; a name the
   *     engine knows no table or view for is plain
   * @throws SQLException if a catalog cannot be read (SQLSTATE 08004 when ScalarDB's catalog is
   *     absent and not allowed to be, 42501 when its tables exist but cannot be seen), or if the
   *     name denotes a table a user statement must not use: the data table, the transaction
   *     metadata table or the virtual table of a decoupled layout (SQLSTATE 42000)
   */
  public TableInfo lookup(List<String> sessionSchemas, @Nullable String rawSchema, String rawTable)
      throws SQLException {
    String schema = rawSchema == null ? null : fold(rawSchema);
    String table = fold(rawTable);
    Snapshot current = snapshot();
    if (!current.present) {
      return plain(schema, sessionSchemas, table); // no ScalarDB catalog, by permission
    }
    Resolution resolution = resolve(sessionSchemas, schema, table);
    QualifiedName name = resolution.name;
    if (name == null) {
      return plain(schema, sessionSchemas, table); // the engine knows no such table
    }
    Classified c = resolution.classified;
    if (c == null || c.snapshot != current) {
      try {
        c = new Classified(current, current.classify(name), null);
      } catch (SQLException refusal) {
        c = new Classified(current, null, refusal);
      }
      resolution.classified = c;
    }
    return c.get();
  }

  /**
   * Resolves an identifier as written; an unquoted Oracle name beyond ASCII is folded by Oracle
   * itself, once, since its upper-casing of those characters is the database's to decide.
   */
  private String fold(String raw) throws SQLException {
    if (!engine.needsServerFold(raw)) {
      return engine.resolveIdentifier(raw);
    }
    synchronized (folds) {
      String cached = folds.get(raw);
      if (cached != null) {
        return cached;
      }
    }
    String folded = withConnection(c -> engine.serverFold(c, raw));
    synchronized (folds) {
      folds.put(raw, folded);
    }
    return folded;
  }

  /**
   * Loads ScalarDB's catalog now unless a fresh one is loaded, so that a database without it, or a
   * user who cannot read it, is refused when the connection opens rather than at its first
   * statement.
   *
   * @throws SQLException as {@link #lookup} would for the catalog itself (08004, 42501)
   */
  public void verify() throws SQLException {
    snapshot();
  }

  /** Forgets everything loaded and resolved, so the next lookup reads both catalogs again. */
  public void invalidate() {
    snapshot = null;
    synchronized (resolutions) {
      resolutions.clear();
    }
  }

  /** Closes the catalog's own connection; a later lookup opens a new one. */
  @Override
  public void close() {
    closed = true;
    connectionLock.lock();
    try {
      dropConnection();
    } finally {
      connectionLock.unlock();
    }
  }

  private static TableInfo plain(
      @Nullable String schema, List<String> sessionSchemas, String table) {
    String ns = schema != null ? schema : sessionSchemas.isEmpty() ? "" : sessionSchemas.get(0);
    return new TableInfo(
        ns, table, Layout.PLAIN, table, null, null, List.of(), List.of(), List.of());
  }

  /**
   * The current snapshot: the loaded one while it is fresh; a stale one while another thread
   * reloads; a fresh load when there is none or this thread is the one to reload.
   */
  private Snapshot snapshot() throws SQLException {
    Snapshot s = snapshot;
    Instant now = clock.instant();
    if (s != null && s.expiresAt.isAfter(now)) {
      return s;
    }
    if (s != null) {
      if (!loadLock.tryLock()) {
        return s; // stale, and someone else is reloading: serve the stale one
      }
    } else {
      loadLock.lock(); // nothing to serve yet: wait for the first load
    }
    try {
      Snapshot again = snapshot;
      if (again != null && again.expiresAt.isAfter(now)) {
        return again;
      }
      Snapshot loaded = withConnection(c -> load(c, now));
      snapshot = loaded;
      return loaded;
    } finally {
      loadLock.unlock();
    }
  }

  private Resolution resolve(List<String> sessionSchemas, @Nullable String schema, String table)
      throws SQLException {
    String key =
        String.join("\u0000", sessionSchemas)
            + "\u0001"
            + (schema == null ? "" : "\u0002" + schema)
            + "\u0001"
            + table;
    Instant now = clock.instant();
    Resolution cached;
    synchronized (resolutions) {
      cached = resolutions.get(key);
    }
    if (cached != null && cached.resolvedAt.plus(ttl).isAfter(now)) {
      return cached;
    }
    // Stale or missing. A stale answer is served while the catalog connection is busy (a reload or
    // another resolution), so a hit never waits on a round trip; only a missing one does.
    if (cached != null) {
      if (!connectionLock.tryLock()) {
        return cached;
      }
    } else {
      connectionLock.lock();
    }
    try {
      Optional<QualifiedName> resolved =
          withConnection(
              c -> Optional.ofNullable(engine.resolve(c, sessionSchemas, schema, table)));
      Resolution fresh = new Resolution(resolved.orElse(null), now);
      synchronized (resolutions) {
        resolutions.put(key, fresh);
      }
      return fresh;
    } finally {
      connectionLock.unlock();
    }
  }

  private interface Query<T> {
    T run(Connection c) throws SQLException;
  }

  /**
   * Runs on the catalog's own connection, opened on demand, serialised with the other users of it
   * (the lock is reentrant, so a caller that already holds it may call this) and never with the
   * caches. A connection that fails is dropped; if it had been used before, one fresh connection is
   * tried once, so a server-side idle reap costs no statement.
   */
  private <T> T withConnection(Query<T> query) throws SQLException {
    if (closed) {
      throw new SQLNonTransientConnectionException("The catalog is closed", "08003");
    }
    connectionLock.lock();
    try {
      boolean reused = connection != null;
      Connection c = connection();
      try {
        return query.run(c);
      } catch (SQLException e) {
        dropConnection();
        if (!reused || !isConnectionFailure(e)) {
          throw e;
        }
      }
      return query.run(connection());
    } catch (SQLException e) {
      dropConnection();
      throw e;
    } finally {
      connectionLock.unlock();
    }
  }

  private Connection connection() throws SQLException {
    Connection c = connection;
    if (c == null) {
      c = connections.open();
      c.setAutoCommit(true);
      try {
        // a catalog query that hangs would otherwise hold every reload and miss of this catalog
        c.setNetworkTimeout(TIMEOUTS, (int) NETWORK_TIMEOUT.toMillis());
      } catch (SQLFeatureNotSupportedException | AbstractMethodError e) {
        // the vendor driver has no network timeout; its own socket settings apply
      }
      connection = c;
    }
    return c;
  }

  private void dropConnection() {
    Connection c = connection;
    connection = null;
    if (c != null) {
      try {
        c.close();
      } catch (SQLException ignored) {
        // the connection is being discarded
      }
    }
  }

  private static boolean isConnectionFailure(SQLException e) {
    String state = e.getSQLState();
    return state != null && state.startsWith("08");
  }

  private String quoted(String table) {
    return engine.quote(metadataSchema) + "." + engine.quote(table);
  }

  @SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
      justification =
          "the only text concatenated into the two queries is the metadata schema name, validated"
              + " against IDENTIFIER, and ScalarDB's own column names, all quoted with the engine's"
              + " quote; nothing comes from a statement")
  private Snapshot load(Connection c, Instant now) throws SQLException {
    List<VirtualTable> virtualTables = new ArrayList<>();
    Map<String, List<MetadataColumn>> metadata = new HashMap<>();
    boolean present = true;
    try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT "
                    + engine.quote("full_table_name")
                    + ", "
                    + engine.quote("left_source_table_full_table_name")
                    + ", "
                    + engine.quote("right_source_table_full_table_name")
                    + ", "
                    + engine.quote("join_type")
                    + " FROM "
                    + quoted("virtual_tables"));
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        virtualTables.add(
            new VirtualTable(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
      }
    } catch (SQLException e) {
      if (!engine.isMissingTable(e)) {
        throw e;
      }
      LOG.warn(
          "{}.virtual_tables is not visible: only classic-layout ScalarDB tables can be"
              + " recognised ({})",
          metadataSchema,
          e.getMessage());
    }
    try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT "
                    + engine.quote("full_table_name")
                    + ", "
                    + engine.quote("column_name")
                    + ", "
                    + engine.quote("key_type")
                    + " FROM "
                    + quoted("metadata")
                    + " ORDER BY "
                    + engine.quote("full_table_name")
                    + ", "
                    + engine.quote("ordinal_position"));
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        metadata
            .computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
            .add(new MetadataColumn(rs.getString(2), rs.getString(3)));
      }
    } catch (SQLException e) {
      if (!engine.isMissingTable(e)) {
        throw e;
      }
      present = false;
      absent(c, e);
    }
    boolean caseInsensitive = present && engine.tableNamesCaseInsensitive(c);
    return new Snapshot(present, caseInsensitive, virtualTables, metadata, now.plus(ttl));
  }

  /** The metadata table is not visible: tell "no ScalarDB here" from "not allowed to look". */
  private void absent(Connection c, SQLException cause) throws SQLException {
    if (engine.schemaExists(c, metadataSchema)) {
      throw new SQLSyntaxErrorException(
          "ScalarDB's catalog table "
              + metadataSchema
              + ".metadata cannot be seen although the schema exists: either ScalarDB has not"
              + " created it yet, or this user lacks SELECT on "
              + metadataSchema
              + ".metadata and "
              + metadataSchema
              + ".virtual_tables ("
              + cause.getMessage()
              + ")",
          "42501",
          cause);
    }
    if (!allowAbsent) {
      throw new SQLNonTransientConnectionException(
          "No ScalarDB catalog: schema "
              + metadataSchema
              + " does not exist in this database. Set the metadata schema property if ScalarDB"
              + " uses another one, or allow a database without ScalarDB explicitly ("
              + cause.getMessage()
              + ")",
          "08004",
          cause);
    }
    LOG.warn(
        "No ScalarDB catalog in schema {}: every table is treated as plain, as allowed",
        metadataSchema);
  }

  /** One row of {@code virtual_tables}. */
  static final class VirtualTable {
    final String name;
    final String left;
    final String right;
    final boolean inner;

    VirtualTable(String name, String left, String right, @Nullable String joinType) {
      this.name = name;
      this.left = left;
      this.right = right;
      // ScalarDB writes INNER or LEFT_OUTER; anything else, NULL included, is treated as the outer
      // join of an imported table, whose data table is then what a statement names
      this.inner = joinType != null && joinType.strip().toUpperCase(Locale.ROOT).equals("INNER");
    }
  }

  /** One row of {@code metadata}. */
  static final class MetadataColumn {
    final String name;
    final @Nullable String keyType;

    MetadataColumn(String name, @Nullable String keyType) {
      this.name = name;
      this.keyType = keyType;
    }
  }

  /** ScalarDB's catalog as loaded at one instant. */
  static final class Snapshot {
    final boolean present;
    final boolean caseInsensitive;
    final Instant expiresAt;
    private final Map<String, VirtualTable> byName = new HashMap<>();
    private final Map<String, VirtualTable> byLeft = new HashMap<>();
    private final Map<String, VirtualTable> byRight = new HashMap<>();
    private final Map<String, List<MetadataColumn>> metadata = new HashMap<>();

    /**
     * @param caseInsensitive whether the database compares table names without regard to case, in
     *     which case the names ScalarDB recorded are matched that way too
     */
    Snapshot(
        boolean present,
        boolean caseInsensitive,
        List<VirtualTable> virtualTables,
        Map<String, List<MetadataColumn>> metadata,
        Instant expiresAt) {
      this.present = present;
      this.caseInsensitive = caseInsensitive;
      this.expiresAt = expiresAt;
      for (VirtualTable v : virtualTables) {
        byName.put(key(v.name), v);
        byLeft.put(key(v.left), v);
        byRight.put(key(v.right), v);
      }
      for (Map.Entry<String, List<MetadataColumn>> e : metadata.entrySet()) {
        this.metadata.put(key(e.getKey()), e.getValue());
      }
    }

    private String key(String fullTableName) {
      return caseInsensitive ? fullTableName.toLowerCase(Locale.ROOT) : fullTableName;
    }

    TableInfo classify(QualifiedName q) throws SQLException {
      String full = q.schema() + "." + q.table();
      if (q.isSynonym()) {
        throw new SQLSyntaxErrorException(
            full + " is a synonym; name the table it stands for instead", "42000");
      }
      String k = key(full);
      VirtualTable v = byName.get(k);
      if (v != null) {
        if (!v.inner) {
          throw new SQLSyntaxErrorException(
              full
                  + " is ScalarDB's virtual table of an imported table; name the table itself, "
                  + v.left,
              "42000");
        }
        // ScalarDB registers metadata for the source tables, not for the virtual table: the keys
        // and the user's columns are the data table's entry
        Columns columns = keyed(v.left, full);
        return new TableInfo(
            q.schema(),
            q.table(),
            Layout.DECOUPLED_CREATED,
            unqualified(v.left),
            unqualified(v.right),
            q.table(),
            columns.partitionKey,
            columns.clusteringKey,
            columns.dataColumns);
      }
      v = byLeft.get(k);
      if (v != null) {
        if (v.inner) {
          throw new SQLSyntaxErrorException(
              full
                  + " is the data table of a ScalarDB table; name the ScalarDB table instead, "
                  + v.name,
              "42000");
        }
        Columns columns = keyed(full, full); // the user's name is the data table
        return new TableInfo(
            q.schema(),
            q.table(),
            Layout.DECOUPLED_IMPORTED,
            q.table(),
            unqualified(v.right),
            unqualified(v.name),
            columns.partitionKey,
            columns.clusteringKey,
            columns.dataColumns);
      }
      v = byRight.get(k);
      if (v != null) {
        throw new SQLSyntaxErrorException(
            full
                + " is the transaction metadata table of a ScalarDB table; name the ScalarDB table"
                + " instead, "
                + (v.inner ? v.name : v.left),
            "42000");
      }
      Columns columns = columns(full);
      if (columns.transactional && columns.partitionKey.isEmpty()) {
        keyed(full, full); // throws: a ScalarDB table without a key
      }
      if (columns.transactional) {
        return new TableInfo(
            q.schema(),
            q.table(),
            Layout.CLASSIC,
            q.table(),
            null,
            null,
            columns.partitionKey,
            columns.clusteringKey,
            columns.dataColumns);
      }
      return new TableInfo(
          q.schema(),
          q.table(),
          Layout.PLAIN,
          q.table(),
          null,
          null,
          List.of(),
          List.of(),
          List.of());
    }

    /**
     * The columns of a ScalarDB table, which must have a key: an entry without one would make every
     * predicate look like no key equality at best, and the write path's per-key steps impossible.
     */
    private Columns keyed(String dataTable, String full) throws SQLException {
      Columns c = columns(dataTable);
      if (c.partitionKey.isEmpty()) {
        throw new SQLNonTransientException(
            "ScalarDB's catalog has no key for "
                + dataTable
                + ", the data table of "
                + full
                + "; its metadata entry is missing or incomplete",
            "XX000");
      }
      return c;
    }

    private Columns columns(String fullTableName) {
      Columns out = new Columns();
      Set<String> names = new LinkedHashSet<>();
      for (MetadataColumn col : metadata.getOrDefault(key(fullTableName), List.of())) {
        names.add(col.name);
        if (col.keyType != null) {
          String kt = col.keyType.toUpperCase(Locale.ROOT);
          if (kt.equals("PARTITION")) {
            out.partitionKey.add(col.name);
          } else if (kt.equals("CLUSTERING")) {
            out.clusteringKey.add(col.name);
          }
        }
      }
      out.transactional = names.containsAll(TRANSACTION_COLUMNS);
      for (String name : names) {
        if (TRANSACTION_COLUMNS.contains(name)) {
          continue;
        }
        if (out.transactional
            && name.startsWith("before_")
            && names.contains(name.substring("before_".length()))) {
          continue; // the before image of a data column
        }
        out.dataColumns.add(name);
      }
      return out;
    }
  }

  /** The columns of one entry of ScalarDB's {@code metadata} table. */
  private static final class Columns {
    final List<String> partitionKey = new ArrayList<>();
    final List<String> clusteringKey = new ArrayList<>();
    final List<String> dataColumns = new ArrayList<>();
    boolean transactional;
  }

  private static String unqualified(String fullTableName) {
    int dot = fullTableName.indexOf('.');
    return dot < 0 ? fullTableName : fullTableName.substring(dot + 1);
  }
}
