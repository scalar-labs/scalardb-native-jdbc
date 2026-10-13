package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.ScalarDbTables;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.Outcome;
import com.scalar.db.nativejdbc.sql.SqlAst;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Decides, for one statement on one connection, what the vendor driver receives (design section
 * 4.1). A statement on plain tables only is forwarded exactly as written; one the parser cannot
 * interpret is refused with the reason; a read of ScalarDB tables at READ COMMITTED runs as the
 * split read, or with each such table renamed to its read view {@code rc_T} where the split does
 * not apply (4.2); any other statement that names a ScalarDB table is refused until its path
 * exists.
 */
final class Router {

  /**
   * What to do with a statement, what running it does to the session and the catalog, and the state
   * it was decided under: a prepared statement is routed again before it runs if that state moved.
   */
  static final class Route {
    final String source;
    final String sql;
    final boolean changesSession;
    final boolean changesNames;
    final boolean passthrough;
    final int isolation;
    final long sessionGeneration;
    final long catalogGeneration;
    final Instant trustedUntil;

    /**
     * For each placeholder of {@link #sql}, the application's placeholder whose value it takes, or
     * null when they are the same: a split read repeats the statement's placeholders.
     */
    final @Nullable List<Integer> parameterSources;

    /** The number of placeholders the application binds. */
    final int parameters;

    /**
     * When the route depends on in-flight counts, the {@link System#nanoTime} after which they are
     * read again; 0 when it does not.
     */
    final long recheckAt;

    /**
     * Whether this route differs from the one it replaces only in how a committed copy excludes its
     * rows in flight, which the counts decide: either text returns the same rows.
     */
    final boolean countsOnly;

    /** The positions, among the statement's split references, of those checked row by row. */
    final List<Integer> perRow;

    private Route(
        String source,
        String sql,
        boolean changesSession,
        boolean changesNames,
        boolean passthrough,
        int isolation,
        long sessionGeneration,
        long catalogGeneration,
        Instant trustedUntil,
        @Nullable List<Integer> parameterSources,
        int parameters,
        long recheckAt,
        boolean countsOnly,
        List<Integer> perRow) {
      this.perRow = List.copyOf(perRow);
      this.recheckAt = recheckAt;
      this.countsOnly = countsOnly;
      this.trustedUntil = trustedUntil;
      this.parameterSources = parameterSources;
      this.parameters = parameters;
      this.isolation = isolation;
      this.source = source;
      this.sql = sql;
      this.changesSession = changesSession;
      this.changesNames = changesNames;
      this.passthrough = passthrough;
      this.sessionGeneration = sessionGeneration;
      this.catalogGeneration = catalogGeneration;
    }
  }

  /**
   * Above this many rows in flight, MySQL and MariaDB exclude a table's rows in flight row by row
   * where the statement reaches the table through an index; the DISTINCT set of the in-flight keys
   * that they build otherwise grows with the count (design 4.2, todo 103).
   */
  static final int PER_ROW_ABOVE = 500;

  /** How long an in-flight count is used before it is read again. */
  private static final long COUNT_TTL_NANOS = 1_000_000_000L;

  private final Engine engine;
  private final Catalog catalog;
  private final Session session;
  // per metadata table: the in-flight count, capped above PER_ROW_ABOVE, and when it was read
  private final Map<String, long[]> inflight = new HashMap<>();
  // while set, read() takes these per-row choices instead of reading counts
  private @Nullable List<Integer> forcedPerRow;
  private boolean passthrough;
  private int isolation = Connection.TRANSACTION_READ_COMMITTED; // set at connect

  Router(Engine engine, Catalog catalog, Session session) {
    this.engine = engine;
    this.catalog = catalog;
    this.session = session;
  }

  void setPassthrough(boolean on) {
    passthrough = on;
  }

  boolean passthrough() {
    return passthrough;
  }

  /** After the connection's isolation level was set: a read of a ScalarDB table depends on it. */
  void setIsolation(int level) {
    isolation = level;
  }

  /**
   * Routes a statement.
   *
   * @param vendor the vendor connection, for the session state and the autocommit mode
   * @param sql the statement as the application wrote it
   * @return the route, whose statement the vendor driver may run
   * @throws SQLException 0AN01 for a statement the driver does not handle, 42000 for a name a
   *     statement must not use, or a catalog failure
   */
  Route route(Connection vendor, String sql) throws SQLException {
    if (passthrough) {
      if (!vendor.getAutoCommit()) {
        throw Errors.unsupported("the passthrough setting applies in autocommit mode only");
      }
      // anything may run here, so assume it changed the session and the names
      return route(sql, sql, true, true, true, null, 0);
    }
    Outcome<SqlAst> parsed =
        SqlAst.analyze(sql, engine, session.doubleQuotesQuoteIdentifiers(vendor));
    if (!parsed.isPresent()) {
      throw Errors.unsupported(parsed.reason());
    }
    SqlAst ast = parsed.get();
    SqlAst.Kind kind = ast.kind();
    String database = session.database();
    String target = ast.useTarget();
    if (database != null && target != null && !database.equals(target)) {
      throw Errors.unsupported(
          "the connection stays in database "
              + database
              + ", where it was opened; open a connection to "
              + target
              + " instead");
    }
    List<String> schemas = session.schemas(vendor);
    IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb = new IdentityHashMap<>();
    for (SqlAst.TableRef ref : ast.tableRefs()) {
      TableInfo table = catalog.lookup(schemas, ref.schema(), ref.name());
      if (!table.isSetUp()) {
        throw Errors.notSetUp(table.namespace() + "." + table.table());
      }
      if (table.isScalarDb()) {
        scalarDb.put(ref, table);
      }
    }
    if (!scalarDb.isEmpty()) {
      return read(vendor, sql, ast, scalarDb);
    }
    boolean changesSession = kind != SqlAst.Kind.SELECT && kind != SqlAst.Kind.DML;
    boolean changesNames = kind == SqlAst.Kind.DDL || kind == SqlAst.Kind.TRUNCATE;
    return route(sql, sql, changesSession, changesNames, false, null, ast.parameterCount());
  }

  /**
   * A read at READ COMMITTED: the split read, whose copies keep every join and filter on the base
   * tables' indexes; where it does not apply, every ScalarDB table renamed to its read view, which
   * shows each row's committed image (design sections 3 and 4.2).
   */
  private Route read(
      Connection vendor,
      String sql,
      SqlAst ast,
      IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb)
      throws SQLException {
    Set<String> names = new TreeSet<>();
    for (TableInfo t : scalarDb.values()) {
      names.add(t.namespace() + "." + t.table());
    }
    String tables = " (" + String.join(", ", names) + ")";
    if (ast.kind() != SqlAst.Kind.SELECT) {
      throw Errors.unsupported(
          "only reads of ScalarDB tables are supported so far; writes and other statements come"
              + " later"
              + tables);
    }
    if (isolation != Connection.TRANSACTION_READ_COMMITTED) {
      throw Errors.unsupported(
          "reads of ScalarDB tables run at READ COMMITTED only so far; set that level on the"
              + " connection"
              + tables);
    }
    if (ast.hasLockingOrIntoClause()) {
      throw Errors.unsupported(
          "a locking read (FOR UPDATE, FOR SHARE) or SELECT ... INTO of a ScalarDB table is not"
              + " supported yet"
              + tables);
    }
    IdentityHashMap<SqlAst.TableRef, String> views = new IdentityHashMap<>();
    for (Map.Entry<SqlAst.TableRef, TableInfo> e : scalarDb.entrySet()) {
      TableInfo t = e.getValue();
      if (t.layout() == Layout.CLASSIC) {
        throw Errors.unsupported(
            "tables in ScalarDB's classic layout are not supported yet" + tables);
      }
      if (e.getKey().hasHintOrLink()) {
        // a hint such as SQL Server's NOLOCK would reach the base tables through the view
        throw Errors.unsupported(
            "a table hint, sample clause, PIVOT or database link on a ScalarDB table is not"
                + " supported"
                + tables);
      }
      views.put(e.getKey(), engine.quote(t.namespace()) + "." + engine.quote(t.readView(engine)));
    }
    Set<SqlAst.TableRef> perRow = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Integer> chosen = new ArrayList<>();
    long recheckAt = 0;
    if (engine == Engine.MYSQL || engine == Engine.MARIADB) {
      List<SqlAst.TableRef> refs = ast.splittableRefs();
      for (int i = 0; i < refs.size(); i++) {
        SqlAst.TableRef r = refs.get(i);
        TableInfo t = scalarDb.get(r);
        if (t == null || !ast.reachedThrough(r, leadingColumns(t))) {
          continue;
        }
        boolean many;
        if (forcedPerRow != null) {
          many = forcedPerRow.contains(i);
        } else {
          long[] count = inflightCount(vendor, t);
          long due = count[1] + COUNT_TTL_NANOS;
          recheckAt = recheckAt == 0 || due - recheckAt < 0 ? due : recheckAt;
          many = count[0] > PER_ROW_ABOVE;
        }
        if (many) {
          perRow.add(r);
          chosen.add(i);
        }
      }
    }
    SqlAst.SplitText split = SplitRead.emit(engine, ast, scalarDb, views, perRow);
    if (split != null) {
      return route(
          sql,
          split.sql(),
          false,
          false,
          false,
          split.parameterSources(),
          ast.parameterCount(),
          recheckAt,
          chosen);
    }
    String rewritten = ast.joinTokens(views::get);
    if (rewritten == null) {
      throw Errors.unsupported("the statement cannot be rewritten onto the read views" + tables);
    }
    return route(sql, rewritten, false, false, false, null, ast.parameterCount());
  }

  private Route route(
      String source,
      String sql,
      boolean changesSession,
      boolean changesNames,
      boolean passthrough,
      @Nullable List<Integer> parameterSources,
      int parameters) {
    return route(
        source,
        sql,
        changesSession,
        changesNames,
        passthrough,
        parameterSources,
        parameters,
        0,
        List.of());
  }

  private Route route(
      String source,
      String sql,
      boolean changesSession,
      boolean changesNames,
      boolean passthrough,
      @Nullable List<Integer> parameterSources,
      int parameters,
      long recheckAt,
      List<Integer> perRow) {
    return new Route(
        source,
        sql,
        changesSession,
        changesNames,
        passthrough,
        isolation,
        session.generation(),
        catalog.generation(),
        catalog.trustedUntil(),
        parameterSources,
        parameters,
        recheckAt,
        false,
        perRow);
  }

  /** The lower-cased columns that lead an index of the table: its key's first, and the indexed. */
  private static Set<String> leadingColumns(TableInfo t) {
    Set<String> out = new HashSet<>();
    out.add(t.keyColumns().get(0).toLowerCase(Locale.ROOT));
    for (String c : t.indexedColumns()) {
      out.add(c.toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /**
   * The table's rows in flight, counted through the in-flight index up to {@link #PER_ROW_ABOVE} +
   * 1, and when they were counted; read again once a count is older than {@link #COUNT_TTL_NANOS}.
   * The count only chooses a plan, so one that is a second old costs time, never rows.
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "names from ScalarDB's catalog, quoted by the engine's rules")
  private long[] inflightCount(Connection vendor, TableInfo t) throws SQLException {
    String meta =
        engine.quote(t.namespace())
            + "."
            + engine.quote(Objects.requireNonNull(t.metadataTable(), "metadata table"));
    long now = System.nanoTime();
    long[] cached = inflight.get(meta);
    if (cached != null && now - cached[1] < COUNT_TTL_NANOS) {
      return cached;
    }
    long count;
    try (Statement st = vendor.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM (SELECT 1 FROM "
                    + meta
                    + " WHERE "
                    + engine.quote("tx_state")
                    + " IN (1, 2) LIMIT "
                    + (PER_ROW_ABOVE + 1)
                    + ") x")) {
      rs.next();
      count = rs.getLong(1);
    }
    long[] fresh = {count, now};
    inflight.put(meta, fresh);
    return fresh;
  }

  /**
   * Classifies a table by its stored names, for {@link ScalarDbTables}: the lookup a statement
   * naming it, quoted, would make.
   *
   * @param vendor the vendor connection, for the session's schemas
   * @param schema the stored schema name
   * @param table the stored table name
   * @return how the driver treats the table
   * @throws SQLException if a catalog cannot be read
   */
  ScalarDbTables.Kind kind(Connection vendor, String schema, String table) throws SQLException {
    TableInfo info;
    try {
      info = catalog.lookup(session.schemas(vendor), engine.quote(schema), engine.quote(table));
    } catch (SQLSyntaxErrorException e) {
      if ("42000".equals(e.getSQLState())) {
        return ScalarDbTables.Kind.REFUSED;
      }
      throw e;
    }
    if (!info.isScalarDb()) {
      return ScalarDbTables.Kind.PLAIN;
    }
    return info.isSetUp() ? ScalarDbTables.Kind.SCALARDB : ScalarDbTables.Kind.NOT_SET_UP;
  }

  /**
   * The route a prepared statement runs with now: its own if nothing it was decided under has
   * moved, otherwise the statement routed again, which refuses it if it now names a ScalarDB table
   * or the passthrough setting no longer applies.
   *
   * @param vendor the vendor connection
   * @param prepared the route decided when the statement was prepared
   * @return the route to run with
   * @throws SQLException as {@link #route}
   */
  Route current(Connection vendor, Route prepared) throws SQLException {
    boolean unchanged =
        prepared.sessionGeneration == session.generation()
            && prepared.catalogGeneration == catalog.generation()
            && !catalog.hasPassed(prepared.trustedUntil)
            && prepared.passthrough == passthrough
            && prepared.isolation == isolation
            && (!prepared.passthrough || vendor.getAutoCommit());
    if (unchanged && (prepared.recheckAt == 0 || System.nanoTime() - prepared.recheckAt < 0)) {
      return prepared;
    }
    Route now = route(vendor, prepared.source);
    if (now.sql.equals(prepared.sql) || now.perRow.equals(prepared.perRow)) {
      return now;
    }
    // the per-row choices moved: is that all that moved?
    Route same;
    forcedPerRow = prepared.perRow;
    try {
      same = route(vendor, prepared.source);
    } finally {
      forcedPerRow = null;
    }
    if (!same.sql.equals(prepared.sql)) {
      return now;
    }
    return new Route(
        now.source,
        now.sql,
        now.changesSession,
        now.changesNames,
        now.passthrough,
        now.isolation,
        now.sessionGeneration,
        now.catalogGeneration,
        now.trustedUntil,
        now.parameterSources,
        now.parameters,
        now.recheckAt,
        true,
        now.perRow);
  }

  /**
   * After a routed statement ran, or failed, since it may have taken effect before failing: re-read
   * the session and forget resolved names, as it requires. Never throws.
   */
  void ran(Connection vendor, Route route) {
    if (route.changesSession) {
      session.changed(vendor);
    }
    if (route.changesNames) {
      catalog.invalidateResolutions();
    }
  }
}
