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
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    /**
     * On PostgreSQL, whether a split read keeps the session's parallel workers: its plain
     * statement's own plan is parallel, a long one.
     */
    final boolean parallel;

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
        List<Integer> perRow,
        boolean parallel) {
      this.parallel = parallel;
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

  /**
   * The same for a table reached only through a range, which may read thousands of its rows: row by
   * row, each costs a lookup, so the set stays cheaper up to a few thousand in flight (todo 112).
   */
  static final int PER_ROW_ABOVE_IN_RANGES = 5000;

  /** How long an in-flight count is used before it is read again. */
  private static final long COUNT_TTL_NANOS = 1_000_000_000L;

  private final Engine engine;
  private final Catalog catalog;
  private final Session session;
  // per metadata table: the in-flight count, capped above PER_ROW_ABOVE_IN_RANGES, when it was
  // read, and which per-row choices were last made: 1 by equality, 2 in ranges
  private final Map<String, long[]> inflight = new HashMap<>();
  // per data table: the lower-cased first column of its primary key, in the database's order
  private final Map<String, String> keyLead = new HashMap<>();
  // while set, read() takes these per-row choices instead of reading counts
  private @Nullable List<Integer> forcedPerRow;
  // PostgreSQL's max_parallel_workers_per_gather: the session's own value (-1 until read), and
  // whether the driver's 0 is in effect (null when not known, after a rollback or a SET)
  private long sessionWorkers = -1;
  private @Nullable Boolean workersOff;
  // per statement text: whether the plain statement's plan is parallel; bounded, least recently
  // used first out
  private final Map<String, Boolean> parallelPlain =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > 256;
        }
      };
  // per statement text: its last route, reused while current() finds it still holds; bounded,
  // least recently used first out
  // ponytail: per connection, so each pooled connection parses a text once; share across
  // connections, keyed with the session state, if warming every connection shows in profiles
  private final Map<String, Route> routes =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Route> eldest) {
          return size() > 256;
        }
      };
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
   * Routes a statement: the route last decided for the same text, if nothing it was decided under
   * has moved ({@link #current}), so that a text is parsed once, not per execution (todo 008).
   *
   * @param vendor the vendor connection, for the session state and the autocommit mode
   * @param sql the statement as the application wrote it
   * @return the route, whose statement the vendor driver may run
   * @throws SQLException 0AN01 for a statement the driver does not handle, 42000 for a name a
   *     statement must not use, or a catalog failure
   */
  Route route(Connection vendor, String sql) throws SQLException {
    Route cached = routes.get(sql);
    Route r = cached == null ? decide(vendor, sql) : current(vendor, cached);
    routes.put(sql, r);
    return r;
  }

  /** Routes a statement from its text, as {@link #route}. */
  private Route decide(Connection vendor, String sql) throws SQLException {
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
      IdentityHashMap<SqlAst.TableRef, Set<String>> leading = new IdentityHashMap<>();
      for (SqlAst.TableRef r : refs) {
        TableInfo t = scalarDb.get(r);
        if (t != null) {
          leading.put(r, leadingColumns(vendor, t));
        }
      }
      Set<SqlAst.TableRef> byEquality = ast.reachedByIndex(leading, false);
      Set<SqlAst.TableRef> reached = ast.reachedByIndex(leading, true);
      for (int i = 0; i < refs.size(); i++) {
        SqlAst.TableRef r = refs.get(i);
        TableInfo t = scalarDb.get(r);
        if (t == null || !reached.contains(r)) {
          continue;
        }
        boolean many;
        if (forcedPerRow != null) {
          many = forcedPerRow.contains(i);
        } else {
          long[] count = inflightCount(vendor, t);
          long due = count[1] + COUNT_TTL_NANOS;
          recheckAt = recheckAt == 0 || due - recheckAt < 0 ? due : recheckAt;
          long bit = byEquality.contains(r) ? 1 : 2;
          long above = bit == 1 ? PER_ROW_ABOVE : PER_ROW_ABOVE_IN_RANGES;
          // back to the set only well below the threshold, so that a count near it does not flip
          // the statement's form, and prepare it again, every second
          many = count[0] > ((count[2] & bit) != 0 ? above / 2 : above);
          count[2] = many ? count[2] | bit : count[2] & ~bit;
        }
        if (many) {
          perRow.add(r);
          chosen.add(i);
        }
      }
    }
    SqlAst.SplitText split = SplitRead.emit(engine, ast, scalarDb, views, perRow);
    if (split != null) {
      Route r =
          route(
              sql,
              split.sql(),
              false,
              false,
              false,
              split.parameterSources(),
              ast.parameterCount(),
              recheckAt,
              chosen);
      return engine == Engine.POSTGRESQL && plainPlanIsParallel(vendor, sql, ast, scalarDb)
          ? new Route(
              r.source,
              r.sql,
              r.changesSession,
              r.changesNames,
              r.passthrough,
              r.isolation,
              r.sessionGeneration,
              r.catalogGeneration,
              r.trustedUntil,
              r.parameterSources,
              r.parameters,
              r.recheckAt,
              r.countsOnly,
              r.perRow,
              true)
          : r;
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
        perRow,
        false);
  }

  /**
   * Whether PostgreSQL plans the statement itself, over the data tables, as parallel: a long read,
   * whose split read then keeps the session's workers (design 4.2). Asked once per statement text
   * with {@code EXPLAIN (GENERIC_PLAN)}, which plans without the values; before PostgreSQL 16, or
   * when the plan cannot be made, the answer is no.
   */
  private boolean plainPlanIsParallel(
      Connection vendor,
      String sql,
      SqlAst ast,
      IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb)
      throws SQLException {
    Boolean known = parallelPlain.get(sql);
    if (known != null) {
      return known;
    }
    boolean parallel = false;
    if (vendor.getMetaData().getDatabaseMajorVersion() >= 16) {
      String plain =
          ast.joinTokensNumbered(
              ref -> {
                TableInfo t = scalarDb.get(ref);
                return t == null
                    ? null
                    : engine.quote(t.namespace()) + "." + engine.quote(t.dataTable());
              });
      if (plain != null) {
        parallel = explainsParallel(vendor, plain);
      }
    }
    parallelPlain.put(sql, parallel);
    return parallel;
  }

  /**
   * Runs the EXPLAIN; any failure, such as a parameter whose type a generic plan cannot infer (todo
   * 106), means no. Inside a transaction it runs under a savepoint, since a failed statement would
   * abort the application's transaction.
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the application's own statement, its tables renamed to quoted data tables")
  private static boolean explainsParallel(Connection vendor, String plain) {
    Savepoint sp = null;
    try {
      if (!vendor.getAutoCommit()) {
        sp = vendor.setSavepoint();
      }
      boolean parallel;
      try (Statement st = vendor.createStatement();
          ResultSet rs = st.executeQuery("EXPLAIN (GENERIC_PLAN, FORMAT JSON) " + plain)) {
        parallel = rs.next() && rs.getString(1).contains("\"Node Type\": \"Gather");
      }
      if (sp != null) {
        vendor.releaseSavepoint(sp);
      }
      return parallel;
    } catch (SQLException e) {
      if (sp != null) {
        try {
          vendor.rollback(sp);
        } catch (SQLException ignored) {
          // the application's next statement reports the connection's state
        }
      }
      return false;
    }
  }

  /** The lower-cased columns that lead an index of the table: its key's first, and the indexed. */
  private Set<String> leadingColumns(Connection vendor, TableInfo t) {
    Set<String> out = new HashSet<>();
    out.add(keyLead(vendor, t));
    for (String c : t.indexedColumns()) {
      out.add(c.toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /**
   * The first column of the table's primary key as the database orders it. ScalarDB records an
   * imported table's key in the order of {@code getPrimaryKeys}, which is by column name, so its
   * catalog can name a column that leads no index; the catalog's first serves when the database
   * cannot answer.
   */
  private String keyLead(Connection vendor, TableInfo t) {
    String table = t.namespace() + "." + t.dataTable();
    String lead = keyLead.get(table);
    if (lead != null) {
      return lead;
    }
    try (ResultSet rs =
        vendor.getMetaData().getPrimaryKeys(t.namespace(), t.namespace(), t.dataTable())) {
      while (rs.next()) {
        if (rs.getInt("KEY_SEQ") == 1) {
          lead = rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
        }
      }
    } catch (SQLException e) {
      // as with the count, a streaming result open on the connection: ask again next time
      return t.keyColumns().get(0).toLowerCase(Locale.ROOT);
    }
    if (lead == null) {
      lead = t.keyColumns().get(0).toLowerCase(Locale.ROOT);
    }
    keyLead.put(table, lead);
    return lead;
  }

  /**
   * The table's rows in flight, counted through the in-flight index up to {@link
   * #PER_ROW_ABOVE_IN_RANGES} + 1, and when they were counted; read again once a count is older
   * than {@link #COUNT_TTL_NANOS}. The count only chooses a plan, so one that is a second old costs
   * time, never rows.
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
    // the count only chooses a plan: when it cannot be read, as with a streaming result open on
    // the connection (todo 106), the last one, or none, will do
    try (Statement st = vendor.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM (SELECT 1 FROM "
                    + meta
                    + " WHERE "
                    + engine.quote("tx_state")
                    + " IN (1, 2) LIMIT "
                    + (PER_ROW_ABOVE_IN_RANGES + 1)
                    + ") x")) {
      rs.next();
      count = rs.getLong(1);
    } catch (SQLException e) {
      count = cached == null ? 0 : cached[0];
    }
    long[] fresh = {count, now, cached == null ? 0 : cached[2]};
    inflight.put(meta, fresh);
    return fresh;
  }

  /**
   * Before routed statements run, on PostgreSQL: a split read runs with {@code
   * max_parallel_workers_per_gather = 0}, since its estimated cost makes PostgreSQL start workers
   * (about 10 ms) for statements that take 1 ms, unless its plain statement's own plan is parallel;
   * every other statement runs with the session's own value. The setting changes only when the next
   * statement needs the other one (design 4.2).
   *
   * @param vendor the vendor connection
   * @param routes the routes about to run
   * @throws SQLException if the setting cannot be read or set
   */
  void beforeRun(Connection vendor, List<Route> routes) throws SQLException {
    if (engine != Engine.POSTGRESQL || routes.isEmpty()) {
      return;
    }
    boolean off = false;
    for (Route r : routes) {
      off |= r.parameterSources != null && !r.parallel; // a short split read
    }
    if (workersOff == null) {
      long shown;
      try (Statement st = vendor.createStatement();
          ResultSet rs = st.executeQuery("SHOW max_parallel_workers_per_gather")) {
        rs.next();
        shown = Long.parseLong(rs.getString(1));
      }
      if (shown != 0 || sessionWorkers <= 0) {
        sessionWorkers = shown;
      }
      // a 0 with a known session value is the driver's own, which a rollback kept
      workersOff = shown == 0;
    }
    if (off && !workersOff) {
      setWorkers(vendor, 0);
      workersOff = true;
    } else if (!off && workersOff && sessionWorkers > 0) {
      setWorkers(vendor, sessionWorkers);
      workersOff = false;
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "a number the driver read from the session, or 0")
  private static void setWorkers(Connection vendor, long n) throws SQLException {
    try (Statement st = vendor.createStatement()) {
      st.execute("SET max_parallel_workers_per_gather = " + n);
    }
  }

  /** After a rollback, which undoes a SET made in the transaction. */
  void rolledBack() {
    workersOff = null;
  }

  /**
   * The route a prepared statement keeps when it cannot be prepared again for a route that differs
   * only in its committed checks: its own text, checked again when the new route is.
   *
   * @param kept the route the statement was prepared with
   * @param now the route it would take now
   * @return the kept text with the new route's recheck time
   */
  static Route keepText(Route kept, Route now) {
    return new Route(
        kept.source,
        kept.sql,
        kept.changesSession,
        kept.changesNames,
        kept.passthrough,
        now.isolation,
        now.sessionGeneration,
        now.catalogGeneration,
        now.trustedUntil,
        kept.parameterSources,
        kept.parameters,
        now.recheckAt,
        false,
        kept.perRow,
        kept.parallel);
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
    Route now = decide(vendor, prepared.source);
    if (now.sql.equals(prepared.sql) || now.perRow.equals(prepared.perRow)) {
      return now;
    }
    // the per-row choices moved: is that all that moved?
    Route same;
    forcedPerRow = prepared.perRow;
    try {
      same = decide(vendor, prepared.source);
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
        now.perRow,
        now.parallel);
  }

  /**
   * After a routed statement ran, or failed, since it may have taken effect before failing: re-read
   * the session and forget resolved names, as it requires. Never throws.
   */
  void ran(Connection vendor, Route route) {
    if (route.changesSession) {
      sessionWorkers = -1; // the statement may have set it
      workersOff = null;
      session.changed(vendor);
    }
    if (route.changesNames) {
      catalog.invalidateResolutions();
    }
  }
}
