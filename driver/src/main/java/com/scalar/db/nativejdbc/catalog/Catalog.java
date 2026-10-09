package com.scalar.db.nativejdbc.catalog;

import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.engine.Engine.QualifiedName;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

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
 *       table in another schema. The answer is the stored spelling, which is also the spelling
 *       ScalarDB keeps for the tables it created or imported.
 *   <li><b>ScalarDB's catalog</b>, loaded whole on a connection of this class's own and refreshed
 *       after {@code ttl} or on {@link #invalidate}, then classifies the stored name: a {@code
 *       virtual_tables} row named by it is a created decoupled table (INNER join) or the virtual
 *       table of an imported one, which a statement must not name; a row whose left source it is
 *       marks an imported decoupled table (LEFT_OUTER) or the data table of a created one, which a
 *       statement must not name either; a row whose right source it is marks a transaction metadata
 *       table, refused as well; a {@code metadata} entry carrying the transaction columns is a
 *       classic-layout table; anything else is plain.
 * </ol>
 *
 * <p>Nothing runs on the user's connection: the catalog's queries would otherwise fix the snapshot
 * of the user's transaction before the statement the design requires to come first, and a failure
 * would abort that transaction. The session schemas are read from the user's connection by the
 * caller, outside a transaction ({@link Engine#sessionSchemas}), and passed in.
 */
public final class Catalog {

  /** ScalarDB's default system namespace, where its catalog tables live. */
  public static final String DEFAULT_METADATA_SCHEMA = "scalardb";

  /** How long a loaded catalog and the names resolved against it are trusted. */
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

  private static final int RESOLUTION_CACHE_SIZE = 4096;

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
     * Opens a connection.
     *
     * @return the connection, in autocommit mode
     * @throws SQLException if the database cannot be reached
     */
    Connection open() throws SQLException;
  }

  private final Engine engine;
  private final String metadataSchema;
  private final ConnectionSupplier connections;
  private final Duration ttl;
  private final Clock clock;

  private final Object lock = new Object();
  private @Nullable Snapshot snapshot; // guarded by lock
  private @Nullable Connection connection; // guarded by lock; reused across loads and resolutions

  /** Resolved names, keyed by session schemas and the name as resolved; guarded by lock. */
  private final Map<String, Optional<QualifiedName>> resolutions =
      new LinkedHashMap<String, Optional<QualifiedName>>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<QualifiedName>> eldest) {
          return size() > RESOLUTION_CACHE_SIZE;
        }
      };

  /**
   * Creates a catalog for one database, trusting what it loads for {@link #DEFAULT_TTL}.
   *
   * @param engine the database engine
   * @param metadataSchema the schema that holds {@code virtual_tables} and {@code metadata},
   *     {@value #DEFAULT_METADATA_SCHEMA} unless ScalarDB is configured otherwise
   * @param connections how to open a connection of the catalog's own
   */
  public Catalog(Engine engine, String metadataSchema, ConnectionSupplier connections) {
    this(engine, metadataSchema, connections, DEFAULT_TTL, Clock.systemUTC());
  }

  /**
   * Creates a catalog for one database.
   *
   * @param engine the database engine
   * @param metadataSchema the schema that holds ScalarDB's catalog tables
   * @param connections how to open a connection of the catalog's own
   * @param ttl how long a loaded catalog is trusted before it is read again
   * @param clock the clock that measures {@code ttl}
   */
  public Catalog(
      Engine engine,
      String metadataSchema,
      ConnectionSupplier connections,
      Duration ttl,
      Clock clock) {
    if (!IDENTIFIER.matcher(metadataSchema).matches()) {
      throw new IllegalArgumentException("Not a plain identifier: " + metadataSchema);
    }
    this.engine = engine;
    this.metadataSchema = metadataSchema;
    this.connections = connections;
    this.ttl = ttl;
    this.clock = clock;
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
   * @throws SQLException if a catalog cannot be read, or if the name denotes a table a user
   *     statement must not use: the data table, the transaction metadata table or the virtual table
   *     of a decoupled layout (SQLSTATE 42000)
   */
  public TableInfo lookup(List<String> sessionSchemas, @Nullable String rawSchema, String rawTable)
      throws SQLException {
    String schema = rawSchema == null ? null : engine.resolveIdentifier(rawSchema);
    String table = engine.resolveIdentifier(rawTable);
    Snapshot current = snapshot();
    if (!current.present) {
      return plain(schema, sessionSchemas, table); // no ScalarDB catalog: every table is plain
    }
    QualifiedName name = resolve(sessionSchemas, schema, table);
    if (name == null) {
      return plain(schema, sessionSchemas, table); // the engine knows no such table
    }
    return current.classify(name);
  }

  /** Forgets everything loaded and resolved, so the next lookup reads both catalogs again. */
  public void invalidate() {
    synchronized (lock) {
      snapshot = null;
      resolutions.clear();
    }
  }

  private static TableInfo plain(
      @Nullable String schema, List<String> sessionSchemas, String table) {
    String ns = schema != null ? schema : sessionSchemas.isEmpty() ? "" : sessionSchemas.get(0);
    return new TableInfo(
        ns, table, Layout.PLAIN, table, null, null, List.of(), List.of(), List.of());
  }

  private Snapshot snapshot() throws SQLException {
    synchronized (lock) {
      Snapshot s = snapshot;
      Instant now = clock.instant();
      if (s != null && s.loadedAt.plus(ttl).isAfter(now)) {
        return s;
      }
      Snapshot loaded = withConnection(c -> load(c, now));
      snapshot = loaded;
      resolutions.clear();
      return loaded;
    }
  }

  private @Nullable QualifiedName resolve(
      List<String> sessionSchemas, @Nullable String schema, String table) throws SQLException {
    String key = String.join("\u0000", sessionSchemas) + "\u0001" + schema + "\u0001" + table;
    synchronized (lock) {
      Optional<QualifiedName> cached = resolutions.get(key);
      if (cached != null) {
        return cached.orElse(null);
      }
      Optional<QualifiedName> resolved =
          withConnection(
              c -> Optional.ofNullable(engine.resolve(c, sessionSchemas, schema, table)));
      resolutions.put(key, resolved);
      return resolved.orElse(null);
    }
  }

  private interface Query<T> {
    T run(Connection c) throws SQLException;
  }

  /** Runs on the catalog's own connection, opened on demand and dropped on any failure. */
  private <T> T withConnection(Query<T> query) throws SQLException {
    Connection c = connection;
    if (c == null) {
      c = connections.open();
      connection = c;
    }
    try {
      return query.run(c);
    } catch (SQLException e) {
      connection = null;
      try {
        c.close();
      } catch (SQLException ignored) {
        // the first exception is the one to report
      }
      throw e;
    }
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
      // a ScalarDB too old for virtual tables: only classic layouts can exist
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
      present = false; // no ScalarDB catalog in this database
    }
    return new Snapshot(present, virtualTables, metadata, now);
  }

  /** One row of {@code virtual_tables}. */
  static final class VirtualTable {
    final String name;
    final String left;
    final String right;
    final boolean inner;

    VirtualTable(String name, String left, String right, String joinType) {
      this.name = name;
      this.left = left;
      this.right = right;
      this.inner = joinType.toUpperCase(Locale.ROOT).equals("INNER");
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
    final Instant loadedAt;
    private final Map<String, VirtualTable> byName = new HashMap<>();
    private final Map<String, VirtualTable> byLeft = new HashMap<>();
    private final Map<String, VirtualTable> byRight = new HashMap<>();
    private final Map<String, List<MetadataColumn>> metadata;

    Snapshot(
        boolean present,
        List<VirtualTable> virtualTables,
        Map<String, List<MetadataColumn>> metadata,
        Instant loadedAt) {
      this.present = present;
      this.loadedAt = loadedAt;
      this.metadata = metadata;
      for (VirtualTable v : virtualTables) {
        byName.put(v.name, v);
        byLeft.put(v.left, v);
        byRight.put(v.right, v);
      }
    }

    TableInfo classify(QualifiedName q) throws SQLException {
      String full = q.schema() + "." + q.table();
      VirtualTable v = byName.get(full);
      if (v != null) {
        if (!v.inner) {
          throw new SQLException(
              full + " is ScalarDB's virtual table of an imported table; name the table itself",
              "42000");
        }
        // ScalarDB registers metadata for the source tables, not for the virtual table: the keys
        // and the user's columns are the data table's entry
        Columns columns = columns(v.left);
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
      v = byLeft.get(full);
      if (v != null) {
        if (v.inner) {
          throw new SQLException(
              full + " is the data table of a ScalarDB table; name the ScalarDB table instead",
              "42000");
        }
        Columns columns = columns(full); // the user's name is the data table
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
      if (byRight.containsKey(full)) {
        throw new SQLException(
            full
                + " is the transaction metadata table of a ScalarDB table; name the ScalarDB table"
                + " instead",
            "42000");
      }
      Columns columns = columns(full);
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

    private Columns columns(String fullTableName) {
      Columns out = new Columns();
      Set<String> names = new LinkedHashSet<>();
      for (MetadataColumn col : metadata.getOrDefault(fullTableName, List.of())) {
        names.add(col.name);
        if (col.keyType != null) {
          String k = col.keyType.toUpperCase(Locale.ROOT);
          if (k.equals("PARTITION")) {
            out.partitionKey.add(col.name);
          } else if (k.equals("CLUSTERING")) {
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
