package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.ScalarDbTables;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.Outcome;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.StatementShape;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.select.ForMode;
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
  // A connection may be used from several threads: every entry point holds the router's lock, so
  // the route cache and the per-connection state stay consistent (todo 125). A ReentrantLock, not
  // synchronized, which would pin a virtual thread's carrier through the round trips it waits on.

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

    /** The kind of statement, or null in passthrough mode. */
    final SqlAst.@Nullable Kind kind;

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
     * rows in flight, which the counts decide, or in whether it reads its rows' versions: either
     * text returns the same rows.
     */
    final boolean countsOnly;

    /** The positions, among the statement's split references, of those checked row by row. */
    final List<Integer> perRow;

    /**
     * On PostgreSQL, whether a split read keeps the session's parallel workers: its plain
     * statement's own plan is parallel, a long one.
     */
    final boolean parallel;

    /**
     * Whether PostgreSQL may run the statement with parallel workers, which it never does for a
     * write or a session statement: only such a statement needs the session's own workers setting.
     */
    final boolean plansParallel;

    /** A write of a ScalarDB table, which the driver runs itself, or null. */
    final Writes.@Nullable Plan write;

    /** A read whose rows carry their versions in hidden trailing columns, or null. */
    final @Nullable Anchored anchored;

    /**
     * For a read that anchors its rows where they are recorded, whether it was decided where they
     * are: in a transaction on a connection that is not read-only; null for any other statement.
     */
    final @Nullable Boolean anchoring;

    /**
     * At SNAPSHOT and SERIALIZABLE, the checks for rows in flight a read runs first, once per table
     * and transaction: a row aborts it (design 4.2); empty otherwise.
     */
    final List<String> checks;

    private Route(
        String source,
        String sql,
        boolean changesSession,
        boolean changesNames,
        boolean passthrough,
        SqlAst.@Nullable Kind kind,
        int isolation,
        long sessionGeneration,
        long catalogGeneration,
        Instant trustedUntil,
        @Nullable List<Integer> parameterSources,
        int parameters,
        long recheckAt,
        boolean countsOnly,
        List<Integer> perRow,
        boolean parallel,
        boolean plansParallel,
        Writes.@Nullable Plan write,
        @Nullable Anchored anchored,
        @Nullable Boolean anchoring,
        List<String> checks) {
      this.anchoring = anchoring;
      this.checks = List.copyOf(checks);
      this.parallel = parallel;
      this.plansParallel = plansParallel;
      this.write = write;
      this.anchored = anchored;
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
      this.kind = kind;
      this.sessionGeneration = sessionGeneration;
      this.catalogGeneration = catalogGeneration;
    }

    /**
     * This route, its rows carrying their versions as {@code a} describes, or none, decided with
     * {@code anchoring} as {@link #anchoring} says.
     */
    Route anchored(@Nullable Anchored a, @Nullable Boolean anchoring) {
      return copy(sql, parameterSources, perRow, countsOnly, parallel, a, anchoring, checks);
    }

    /** This route, with the checks a read at SNAPSHOT or SERIALIZABLE runs first. */
    Route checked(List<String> checks) {
      return copy(sql, parameterSources, perRow, countsOnly, parallel, anchored, anchoring, checks);
    }

    /** This route with another statement text and what goes with it. */
    Route with(
        String sql,
        @Nullable List<Integer> parameterSources,
        List<Integer> perRow,
        boolean countsOnly,
        boolean parallel) {
      return copy(sql, parameterSources, perRow, countsOnly, parallel, anchored, anchoring, checks);
    }

    private Route copy(
        String sql,
        @Nullable List<Integer> parameterSources,
        List<Integer> perRow,
        boolean countsOnly,
        boolean parallel,
        @Nullable Anchored anchored,
        @Nullable Boolean anchoring,
        List<String> checks) {
      return new Route(
          source,
          sql,
          changesSession,
          changesNames,
          passthrough,
          kind,
          isolation,
          sessionGeneration,
          catalogGeneration,
          trustedUntil,
          parameterSources,
          parameters,
          recheckAt,
          countsOnly,
          perRow,
          parallel,
          plansParallel,
          write,
          anchored,
          anchoring,
          checks);
    }
  }

  /**
   * A read of ScalarDB tables at READ COMMITTED that returns, after the application's columns, for
   * each table it returns rows of, the row's key and version: the anchors of the transaction's
   * later writes (design 4.2, 4.3).
   */
  static final class Anchored {
    /** The tables, as {@code Writes} names them, in the order of their columns. */
    final List<String> tables;

    /** The number of key columns of each. */
    final List<Integer> keys;

    /**
     * Whether each table's columns end with the row's {@code tx_state}, at SNAPSHOT and
     * SERIALIZABLE: a row in flight aborts the read (design 4.2).
     */
    final boolean states;

    private final int hidden;

    Anchored(List<String> tables, List<Integer> keys, boolean states) {
      this.tables = tables;
      this.keys = keys;
      this.states = states;
      this.hidden = keys.stream().mapToInt(k -> k + width()).sum();
    }

    /** The columns each table appends after its key: the version, and the state with states. */
    int width() {
      return states ? 3 : 2;
    }

    /** Columns the driver appended that are hidden only, not recorded: {@code hidden} of them. */
    Anchored(String table, int keys, int hidden) {
      this.tables = List.of(table);
      this.keys = List.of(keys);
      this.states = false;
      this.hidden = hidden;
    }

    /**
     * The hidden trailing columns: per table its key's, then {@code tx_id} and {@code tx_version},
     * then {@code tx_state} with {@link #states}.
     */
    int hidden() {
      return hidden;
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
  private final ReentrantLock lock = new ReentrantLock();
  // per metadata table: the in-flight count, capped above PER_ROW_ABOVE_IN_RANGES, when it was
  // read, and which per-row choices were last made: 1 by equality, 2 in ranges
  private final Map<String, long[]> inflight = new HashMap<>();
  // per data table: the lower-cased first column of its primary key, in the database's order
  private final Map<String, String> keyLead = new HashMap<>();
  // PostgreSQL's max_parallel_workers_per_gather: the session's own value (-1 until read), and
  // whether the driver's 0 is in effect (null when not known, after a rollback or a SET of it),
  // and whether a statement in the open transaction set it, which the transaction's end may undo
  private long sessionWorkers = -1;
  private @Nullable Boolean workersOff;
  private boolean workersSetInTransaction;
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
  // false while nativeSQL decides a route: nothing runs on the connection (todo 127)
  private boolean probes = true;
  private int isolation = Connection.TRANSACTION_READ_COMMITTED; // set at connect
  // the checks for rows in flight the open transaction passed, in its snapshot
  private final Set<String> passed = new HashSet<>();

  Router(Engine engine, Catalog catalog, Session session) {
    this.engine = engine;
    this.catalog = catalog;
    this.session = session;
  }

  void setPassthrough(boolean on) {
    lock.lock();
    try {
      passthrough = on;
    } finally {
      lock.unlock();
    }
  }

  boolean passthrough() {
    lock.lock();
    try {
      return passthrough;
    } finally {
      lock.unlock();
    }
  }

  /** After the connection's isolation level was set: a read of a ScalarDB table depends on it. */
  void setIsolation(int level) {
    lock.lock();
    try {
      isolation = level;
    } finally {
      lock.unlock();
    }
  }

  /**
   * The statement as the database would receive it, for {@link Connection#nativeSQL}: decided from
   * the counts and plans known so far, without reading them on the connection (todo 127), and not
   * kept; only a session that changed since it was read is read again. A statement the driver
   * forwards as written, or refuses, or cannot route now, is translated by the vendor.
   *
   * @param vendor the vendor connection
   * @param sql the statement
   * @return the statement as sent
   * @throws SQLException if the vendor cannot translate it
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the application's own statement, which nativeSQL translates and never runs")
  String nativeSql(Connection vendor, String sql) throws SQLException {
    lock.lock();
    try {
      Route r = routes.get(key(vendor, sql));
      probes = false;
      try {
        // the cached route only while what it was decided under holds: another level reads
        // another text
        r = r == null ? decide(vendor, sql, null, null) : current(vendor, r);
      } catch (SQLException e) {
        r = null;
      } finally {
        probes = true;
      }
      return r == null || r.sql.equals(sql) ? vendor.nativeSQL(sql) : r.sql;
    } finally {
      lock.unlock();
    }
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
    lock.lock();
    try {
      String key = key(vendor, sql);
      Route cached = routes.get(key);
      Route r = cached == null ? decide(vendor, sql, null, null) : current(vendor, cached);
      routes.put(key, r);
      return r;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Routes a statement from its text, as {@link #route}; with {@code forcedPerRow}, the per-row
   * choices of a split read are those instead of the counts'.
   */
  private Route decide(
      Connection vendor,
      String sql,
      @Nullable List<Integer> forcedPerRow,
      @Nullable Boolean forcedAnchoring)
      throws SQLException {
    if (passthrough) {
      if (!vendor.getAutoCommit()) {
        throw Errors.unsupported("the passthrough setting applies in autocommit mode only");
      }
      // anything may run here, so assume it changed the session and the names
      return route(sql, sql, null, true, true, true, null, 0, 0, List.of(), true);
    }
    // parsed first as the session was last read: transaction control runs nothing before it, since
    // it may end a transaction that a failed statement aborted (todo 122)
    boolean quotes = session.doubleQuotesQuoteIdentifiersAsLastRead();
    Outcome<SqlAst> parsed = SqlAst.analyze(sql, engine, quotes);
    if (!(parsed.isPresent() && controlsTransaction(parsed.get().kind()))
        && session.doubleQuotesQuoteIdentifiers(vendor) != quotes) {
      parsed = SqlAst.analyze(sql, engine, !quotes);
    }
    if (!parsed.isPresent()) {
      throw Errors.unsupported(parsed.reason());
    }
    SqlAst ast = parsed.get();
    SqlAst.Kind kind = ast.kind();
    if (controlsTransaction(kind)) {
      return route(sql, sql, kind, false, false, false, null, 0, 0, List.of(), false);
    }
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
    if (!scalarDb.isEmpty() && ast.hintsOnOthers(scalarDb.keySet())) {
      // blanked out of the text the driver rewrites (4.7)
      throw Errors.unsupported(
          "a SQL Server table hint other than NOLOCK or INDEX on a table ScalarDB does not manage"
              + " is not supported in a statement that reads a ScalarDB table");
    }
    for (SqlAst.TableRef ref : scalarDb.keySet()) {
      if (!StatementShape.LOCK_HINTS.containsAll(ast.hints(ref))) {
        // READUNCOMMITTED, among them, would read a prepare's after image (4.7)
        throw Errors.unsupported(
            "only the table hints "
                + String.join(", ", new TreeSet<>(StatementShape.LOCK_HINTS))
                + " are supported on a ScalarDB table ("
                + ref.name()
                + ")");
      }
    }
    if (!scalarDb.isEmpty() && kind == SqlAst.Kind.DML) {
      return write(vendor, sql, ast, scalarDb);
    }
    if (!scalarDb.isEmpty()) {
      return read(
          vendor,
          sql,
          ast,
          scalarDb,
          forcedPerRow,
          forcedAnchoring != null ? forcedAnchoring : anchoring(vendor));
    }
    String routed = sql;
    if ((kind == SqlAst.Kind.SELECT || (kind == SqlAst.Kind.DML && engine == Engine.SQLSERVER))
        && lockingSerializable()) {
      // other tables are locked as they are read too, as the engine's SERIALIZABLE would lock them
      // (todo 229); a write's own reads take next-key locks at REPEATABLE READ on MySQL and
      // MariaDB, but read the snapshot unlocked at SQL Server's SNAPSHOT: there its target and
      // every table it reads are held too, its predicate's range with them (todo 268)
      routed = lockingEveryRead(ast, sql);
      if (routed == null) {
        throw Errors.unsupported(LOCKS_EVERY_SELECT);
      }
    }
    boolean changesSession = kind != SqlAst.Kind.SELECT && kind != SqlAst.Kind.DML;
    boolean changesNames = kind == SqlAst.Kind.DDL || kind == SqlAst.Kind.TRUNCATE;
    boolean plansParallel = kind != SqlAst.Kind.DML && kind != SqlAst.Kind.SESSION;
    return route(
        sql,
        routed,
        kind,
        changesSession,
        changesNames,
        false,
        null,
        ast.parameterCount(),
        0,
        List.of(),
        plansParallel);
  }

  /** A write of a ScalarDB table: planned now, run by the driver (design 4.3, milestone 4a). */
  private Route write(
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
    if (!levelSupported()) {
      throw Errors.unsupported(LEVELS + tables);
    }
    Outcome<StatementShape> shape = StatementShape.classify(ast);
    if (!shape.isPresent()) {
      throw Errors.unsupported(
          "a write of a ScalarDB table must be one INSERT ... VALUES, UPDATE or DELETE of that"
              + " table alone: "
              + shape.reason()
              + tables);
    }
    TableInfo t = Objects.requireNonNull(scalarDb.get(shape.get().table()));
    if (t.layout() == Layout.CLASSIC) {
      throw Errors.unsupported(
          "tables in ScalarDB's classic layout are not supported yet" + tables);
    }
    Writes.Plan plan =
        Writes.plan(engine, shape.get(), t, tables, snapshot(), lockingSerializable(), vendor);
    return new Route(
        sql,
        sql,
        false,
        false,
        false,
        SqlAst.Kind.DML,
        isolation,
        session.generation(),
        catalog.generation(),
        catalog.trustedUntil(),
        null,
        ast.parameterCount(),
        0,
        false,
        List.of(),
        false,
        false,
        plan,
        null,
        null,
        List.of());
  }

  /**
   * The table a locking read locks (design 4.2), or a plain read at SERIALIZABLE on MySQL and
   * MariaDB: the one ScalarDB table of a read row by row, on an engine where writes run. Null for
   * any other read: a join, a subquery, a grouped or DISTINCT select, an aggregate in the select
   * list or ORDER BY.
   */
  private SqlAst.@Nullable TableRef lockable(
      SqlAst ast, IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb) {
    Optional<StatementShape> shape = StatementShape.of(ast);
    if (scalarDb.size() != 1
        || shape.isEmpty()
        || shape.get().kind() != StatementShape.Kind.SELECT
        || !ast.rowPerRow()
        || (shape.get().lockMode() == null && !lockingSerializable())) {
      return null;
    }
    SqlAst.TableRef ref = shape.get().table();
    if (capturedByMeta(ref)) {
      return null;
    }
    return scalarDb.containsKey(ref) ? ref : null;
  }

  /**
   * The tables whose rows a read anchors (design 4.2, READ COMMITTED), where writes run: in a read
   * row by row, each table the result has a row of, joined in the top-level FROM clause, a LEFT
   * JOIN's nullable side included. A table in a subquery returns no row, and a grouped or DISTINCT
   * select, or an aggregate in the select list or ORDER BY, returns none of a table's own: none
   * anchored.
   */
  private List<SqlAst.TableRef> anchorable(SqlAst ast, SqlAst.Split split) {
    if (!ast.rowPerRow()) {
      return List.of();
    }
    List<SqlAst.TableRef> out = new ArrayList<>();
    for (Map.Entry<SqlAst.TableRef, SqlAst.Place> e : split.places().entrySet()) {
      if (split.joined(e.getKey())) {
        if (capturedByMeta(e.getKey())) {
          return List.of();
        }
        out.add(e.getKey());
      }
    }
    return out;
  }

  /**
   * Whether a read's anchors would be recorded: in a transaction, on a connection that is not
   * read-only (todo 146); a read routed elsewhere reads no versions.
   */
  private static boolean anchoring(Connection vendor) throws SQLException {
    return !vendor.getAutoCommit() && !vendor.isReadOnly();
  }

  /**
   * The route cache's key: the text, per anchoring mode, so that a text read both in and out of
   * transactions keeps a route for each.
   */
  private static String key(Connection vendor, String sql) throws SQLException {
    return anchoring(vendor) ? sql : "\0" + sql;
  }

  /** Whether the version lookups' own alias would capture the row's key (todo 144). */
  private boolean capturedByMeta(SqlAst.TableRef ref) {
    return engine.columnKey(engine.resolveIdentifier(exposed(ref))).equals(META_ALIAS);
  }

  static String exposed(SqlAst.TableRef ref) {
    return ref.hasAlias() ? Objects.requireNonNull(ref.alias()) : ref.name();
  }

  /**
   * What each copy of an anchored read appends to its select list: the key, and the version of the
   * row it returns, read by key for those rows only; a committed copy's {@code tx_id}, an in-flight
   * copy's before image's (design 4.2).
   */
  private List<String> versions(
      SqlAst.TableRef ref, Writes.Names n, SqlAst.Piece piece, boolean states) {
    String x = exposed(ref);
    String m = engine.quote(META_ALIAS);
    List<String> out = new ArrayList<>();
    for (String k : n.keyColumns) {
      out.add(n.key(x, k));
    }
    String prefix = piece == SqlAst.Piece.COMMITTED ? "" : "before_";
    List<String> read = new ArrayList<>(List.of(prefix + "tx_id", prefix + "tx_version"));
    if (states) {
      read.add("tx_state"); // the row's own, also where the piece reads the before image
    }
    for (String c : read) {
      out.add(
          "(SELECT "
              + m
              + "."
              + engine.quote(c)
              + " FROM "
              + n.meta
              + " "
              + m
              + " WHERE "
              + n.keyJoin(m, x)
              + ")");
    }
    return out;
  }

  /**
   * A user's locking read of one ScalarDB table (design 4.2): its committed copy alone, the
   * statement as written over it, locking clause included, which locks the data table's rows; and
   * the same predicate over the rows in flight, which the driver checks first.
   */
  private Route lockingRead(
      String sql,
      SqlAst ast,
      IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb,
      SqlAst.TableRef ref,
      String tables)
      throws SQLException {
    TableInfo t = Objects.requireNonNull(scalarDb.get(ref));
    if (t.layout() == Layout.CLASSIC) {
      throw Errors.unsupported(
          "tables in ScalarDB's classic layout are not supported yet" + tables);
    }
    if (ref.hasHintOrLink()) {
      throw Errors.unsupported(
          "a table hint, sample clause, PIVOT or database link on a ScalarDB table is not"
              + " supported"
              + tables);
    }
    StatementShape shape = StatementShape.of(ast).orElseThrow();
    // a read at SERIALIZABLE without a clause of its own locks as FOR SHARE does
    boolean plain = shape.lockMode() == null;
    ForMode mode = plain ? ForMode.SHARE : shape.lockMode();
    if (mode == ForMode.KEY_SHARE) {
      // a ScalarDB prepare writes the row with a non-key UPDATE, which KEY SHARE does not block
      throw Errors.unsupported(
          "FOR KEY SHARE does not hold off ScalarDB's writes; use FOR SHARE" + tables);
    }
    for (SqlAst.ColumnRef c : ast.columnRefs()) {
      SqlAst.TableRef q = c.qualifier();
      if (q != null && q.schema() != null) {
        throw Errors.unsupported(
            "a column qualified by a schema in a locking read of a ScalarDB table" + tables);
      }
    }
    Writes.Names n = new Writes.Names(engine, t);
    // MySQL and MariaDB lock no row a derived table reads for a clause outside it: the statement is
    // parsed again without its clause, which goes into the committed copy instead, and the copy is
    // merged into the statement, the user's predicate with it, so that only the rows it matches
    // are locked (todos 189, 190)
    SqlAst read = ast;
    SqlAst.TableRef readRef = ref;
    IdentityHashMap<SqlAst.TableRef, TableInfo> readDb = scalarDb;
    String inner = null;
    String relock = null;
    if (engine == Engine.SQLSERVER) {
      if (ast.source().contains(LOCK_MARK)) {
        throw Errors.unsupported(
            "the locking read cannot be rewritten onto the committed rows" + tables);
      }
      if (ast.hints(ref).isEmpty() && !plain) {
        throw Errors.unsupported(
            "SQL Server locks with table hints, not FOR UPDATE or FOR SHARE: write WITH (UPDLOCK)"
                + " or WITH (HOLDLOCK) after the table"
                + tables);
      }
      // the hints, blanked out of the text, go onto the committed copy's data table, which the
      // optimizer folds into the statement, the user's predicate with it (4.7); at SERIALIZABLE
      // with a range lock, which holds off a ScalarDB insert into the predicate's range
      List<String> hints = new ArrayList<>(ast.hints(ref));
      if (lockingSerializable() && !hints.contains("HOLDLOCK") && !hints.contains("SERIALIZABLE")) {
        hints.add("HOLDLOCK");
      }
      inner = "WITH (" + String.join(", ", hints) + ")";
      // a lock read through a covering index there locks that index's keys only, an update lock
      // too: the rows are locked again through the primary key as their versions are read
      relock =
          (mode == ForMode.UPDATE ? "UPDLOCK" : "REPEATABLEREAD")
              + ", ROWLOCK"
              + (shape.noWait() || shape.skipLocked() ? ", NOWAIT" : "");
    } else if (engine == Engine.ORACLE) {
      // the clause stays at the end, as on PostgreSQL: Oracle takes it on the outer query only,
      // and locks there the rows of the committed copy's data table that the predicate matches
      if (mode != ForMode.UPDATE) {
        throw Errors.unsupported(
            "only FOR UPDATE locks a ScalarDB table on Oracle, which has no FOR SHARE" + tables);
      }
    } else if (engine != Engine.POSTGRESQL) {
      boolean update = mode == ForMode.UPDATE;
      if (!update && mode != ForMode.SHARE) {
        throw Errors.unsupported(
            "only FOR UPDATE and FOR SHARE lock a ScalarDB table on this engine" + tables);
      }
      String share = engine == Engine.MARIADB ? "LOCK IN SHARE MODE" : "FOR SHARE";
      String wait = (shape.noWait() ? " NOWAIT" : "") + (shape.skipLocked() ? " SKIP LOCKED" : "");
      inner = (update ? "FOR UPDATE" : share) + wait;
      // a shared lock read through a covering index locks the index entry only: the rows are
      // locked again by key as the versions are read under the lock, not their metadata rows
      // ponytail: NOWAIT there, under SKIP LOCKED too, fails rather than skips a row locked in
      // between
      // at REPEATABLE READ a plain read sees the snapshot, so FOR UPDATE's versions are read under
      // a lock too, and the metadata rows with them, which MySQL's OF d would read in the snapshot
      relock =
          update && !snapshot()
              ? null
              : (update ? "FOR UPDATE" : share)
                  + (engine == Engine.MYSQL && !snapshot() ? " OF d" : "")
                  + (wait.isEmpty() ? "" : " NOWAIT");
      int clause = plain ? -1 : ast.afterLast("FOR") - "FOR".length();
      SqlAst bare =
          ast.source().contains(LOCK_MARK)
              ? null
              : plain ? ast : clause < 0 ? null : ast.without(clause, ast.end());
      if (bare == null || bare.hasLockingOrIntoClause() || bare.tableRefs().size() != 1) {
        throw Errors.unsupported(
            "the locking read cannot be rewritten onto the committed rows" + tables);
      }
      read = bare;
      readRef = bare.tableRefs().get(0);
      readDb = new IdentityHashMap<>();
      readDb.put(readRef, t);
    }
    SqlAst.TableRef anchoredRef = readRef;
    SqlAst.SplitText text =
        SplitRead.emit(
            engine,
            read,
            read.split(r -> r == anchoredRef ? t.keyColumns() : null),
            readDb,
            new IdentityHashMap<>(),
            Set.of(),
            List.of(readRef),
            (r, piece) -> versions(anchoredRef, n, piece, false),
            true,
            false,
            inner == null ? null : LOCK_MARK);
    Expression where = shape.where();
    int[] span = where == null ? null : ast.span(where);
    List<Integer> inWhere = span == null ? List.of() : ast.placeholdersIn(span[0], span[1]);
    if (text == null || (where != null && span == null) || inWhere == null) {
      throw Errors.unsupported(
          "the locking read cannot be rewritten onto the committed rows" + tables);
    }
    String lockSql = text.sql();
    if (inner != null) {
      lockSql = lockSql.replace(LOCK_MARK, inner);
      if (engine == Engine.MARIADB) {
        lockSql = "SET STATEMENT optimizer_switch='derived_merge=on' FOR " + lockSql;
      } else if (engine == Engine.MYSQL) {
        // ponytail: ahead of any hint comment of the user's, which MySQL then ignores
        // the outer SELECT of the text as emitted, which re-spaces the user's (todo 202); the
        // quoting setting does not move a token
        SqlAst emitted = SqlAst.parse(lockSql, engine, true).orElse(null);
        int select = emitted == null ? -1 : emitted.afterFirst("SELECT");
        if (select < 0) {
          throw Errors.unsupported(
              "the locking read cannot be rewritten onto the committed rows" + tables);
        }
        lockSql =
            lockSql.substring(0, select)
                + String.format(" /*+ MERGE(%s) */", exposed(readRef))
                + lockSql.substring(select);
      }
    }
    Writes.Step inFlight =
        shape.skipLocked()
            ? null // a row in flight is a locked row, skipped
            : inFlightStep(t, ref, ast, span, inWhere);
    // at SNAPSHOT and SERIALIZABLE a row in flight aborts the transaction (section 6), NOWAIT or
    // not
    Writes.Plan plan =
        new Writes.LockPlan(
            n,
            new Writes.Step(lockSql, text.parameterSources()),
            inFlight,
            shape.noWait() && !snapshot(),
            relock,
            // the comment the clause replaces is left as it is
            inner != null && snapshot()
                ? new Writes.Step(text.sql(), text.parameterSources())
                : null);
    return new Route(
        sql,
        sql,
        false,
        false,
        false,
        SqlAst.Kind.SELECT,
        isolation,
        session.generation(),
        catalog.generation(),
        catalog.trustedUntil(),
        null,
        ast.parameterCount(),
        0,
        false,
        List.of(),
        false,
        false,
        plan,
        null,
        null,
        List.of());
  }

  /**
   * The check a locking read makes for rows in flight its predicate matches: by their before
   * images, and at SNAPSHOT and SERIALIZABLE by their after images too, since a row in flight
   * aborts there.
   */
  private Writes.Step inFlightStep(
      TableInfo t, SqlAst.TableRef ref, SqlAst ast, int @Nullable [] span, List<Integer> inWhere) {
    String where = span == null ? "" : " WHERE " + ast.source().substring(span[0], span[1]);
    String sql =
        "SELECT 1 FROM "
            + SplitRead.inFlight(engine, t, t.dataColumns())
            + " "
            + exposed(ref)
            + where;
    List<Integer> binds = new ArrayList<>(inWhere);
    if (snapshot()) {
      sql +=
          " UNION ALL SELECT 1 FROM "
              + SplitRead.afterImages(engine, t, t.dataColumns())
              + " "
              + exposed(ref)
              + where;
      binds.addAll(inWhere);
    }
    return new Writes.Step(Writes.anyRow(engine, sql), binds);
  }

  /**
   * Where the committed copy's locking clause goes, put in after the split read is emitted: the
   * parser that checks it reads no {@code LOCK IN SHARE MODE NOWAIT} in a derived table.
   */
  private static final String LOCK_MARK = "/* scalardb_native_lock */";

  /** The version lookups' alias for the metadata table, which an anchored read must not use. */
  private static final String META_ALIAS = "scalardb_native_meta";

  /**
   * A read at READ COMMITTED: the split read, whose copies keep every join and filter on the base
   * tables' indexes; where it does not apply, every ScalarDB table renamed to its read view, which
   * shows each row's committed image (design sections 3 and 4.2).
   */
  private Route read(
      Connection vendor,
      String sql,
      SqlAst ast,
      IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb,
      @Nullable List<Integer> forcedPerRow,
      boolean anchoring)
      throws SQLException {
    Set<String> names = new TreeSet<>();
    for (TableInfo t : scalarDb.values()) {
      names.add(t.namespace() + "." + t.table());
    }
    String tables = " (" + String.join(", ", names) + ")";
    boolean snapshot = snapshot();
    if (ast.kind() != SqlAst.Kind.SELECT) {
      throw Errors.unsupported(
          "only reads of ScalarDB tables are supported so far; writes and other statements come"
              + " later"
              + tables);
    }
    if (!levelSupported()) {
      throw Errors.unsupported(LEVELS + tables);
    }
    if (lockingSerializable() && !ast.hasLockingOrIntoClause()) {
      // every read locks what it reads (design 4.2, SERIALIZABLE): a table read row by row is
      // locked row by row, as FOR SHARE locks it; anything else takes the coarse path
      SqlAst.TableRef one = ast.tableRefs().size() == 1 ? lockable(ast, scalarDb) : null;
      return one != null
          ? lockingRead(sql, ast, scalarDb, one, tables)
          : coarseRead(sql, ast, scalarDb, tables);
    }
    if (ast.hasLockingOrIntoClause()) {
      SqlAst.TableRef locked = lockable(ast, scalarDb);
      if (locked == null) {
        throw Errors.unsupported(
            "a locking read (FOR UPDATE, FOR SHARE) of a ScalarDB table must read that table alone"
                + " row by row, on PostgreSQL, MySQL or MariaDB; SELECT ... INTO is not supported"
                + tables);
      }
      return lockingRead(sql, ast, scalarDb, locked, tables);
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
    SqlAst.Split split =
        ast.split(
            r -> {
              TableInfo t = scalarDb.get(r);
              return t == null ? null : t.keyColumns();
            });
    Set<SqlAst.TableRef> perRow = Collections.newSetFromMap(new IdentityHashMap<>());
    List<Integer> chosen = new ArrayList<>();
    long recheckAt = 0;
    if (engine == Engine.MYSQL || engine == Engine.MARIADB) {
      List<SqlAst.TableRef> refs = new ArrayList<>(split.places().keySet());
      IdentityHashMap<SqlAst.TableRef, Set<String>> leading = new IdentityHashMap<>();
      for (SqlAst.TableRef r : refs) {
        TableInfo t = scalarDb.get(r);
        if (t != null) {
          leading.put(r, leadingColumns(vendor, t));
        }
      }
      Set<SqlAst.TableRef> byEquality = ast.reachedByIndex(split, leading, false);
      Set<SqlAst.TableRef> reached = ast.reachedByIndex(split, leading, true);
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
    List<SqlAst.TableRef> anchorable = anchorable(ast, split);
    // at SNAPSHOT and SERIALIZABLE a row in flight aborts the read: where every ScalarDB table
    // returns rows, each row carries its state, read as it is rather than without the rows in
    // flight; elsewhere each table is checked whole first (design 4.2). A row in flight the
    // result does not return changes it where an outer join leaves a row unmatched, or an offset
    // skips rows: checked whole too
    boolean states =
        snapshot
            && scalarDb.keySet().stream().allMatch(r -> anchorable.stream().anyMatch(x -> x == r))
            && !split.places().containsValue(SqlAst.Place.NULLABLE)
            && !ast.skipsRows();
    // the versions cost lookups per row, read only where they are recorded
    List<SqlAst.TableRef> anchored = anchoring || states ? anchorable : List.of();
    IdentityHashMap<SqlAst.TableRef, Writes.Names> anchorNames = new IdentityHashMap<>();
    List<String> anchorTables = new ArrayList<>();
    List<Integer> keys = new ArrayList<>();
    for (SqlAst.TableRef r : anchored) {
      Writes.Names n = new Writes.Names(engine, Objects.requireNonNull(scalarDb.get(r)));
      anchorNames.put(r, n);
      anchorTables.add(n.table);
      keys.add(n.keyColumns.size());
    }
    SqlAst.SplitText text =
        SplitRead.emit(
            engine,
            ast,
            split,
            scalarDb,
            views,
            perRow,
            anchored,
            (r, piece) -> versions(r, Objects.requireNonNull(anchorNames.get(r)), piece, states),
            false,
            states,
            null);
    if (text != null) {
      Route r =
          route(
              sql,
              text.sql(),
              SqlAst.Kind.SELECT,
              false,
              false,
              false,
              text.parameterSources(),
              ast.parameterCount(),
              recheckAt,
              chosen,
              true);
      if (!anchorable.isEmpty()) {
        r =
            r.anchored(
                anchoring || states ? new Anchored(anchorTables, keys, states) : null, anchoring);
      }
      if (snapshot) {
        // with states, run only for a scrollable result, whose moves pass over rows unchecked
        r = r.checked(checks(scalarDb));
      }
      return engine == Engine.POSTGRESQL && plainPlanIsParallel(vendor, sql, ast, scalarDb)
          ? r.with(r.sql, r.parameterSources, r.perRow, false, true)
          : r;
    }
    String rewritten = ast.joinTokens(views::get);
    if (rewritten == null) {
      throw Errors.unsupported("the statement cannot be rewritten onto the read views" + tables);
    }
    Route r =
        route(
            sql,
            rewritten,
            SqlAst.Kind.SELECT,
            false,
            false,
            false,
            null,
            ast.parameterCount(),
            0,
            List.of(),
            true);
    return snapshot ? r.checked(checks(scalarDb)) : r;
  }

  /** Per ScalarDB table of a read, the check for a row in flight anywhere in it (design 4.2). */
  private List<String> checks(IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb) {
    Set<String> out = new TreeSet<>();
    for (TableInfo t : scalarDb.values()) {
      out.add(
          Writes.anyRow(
              engine,
              "SELECT 1 FROM "
                  + SplitRead.meta(engine, t)
                  + " WHERE "
                  + engine.quote("tx_state")
                  + " IN (1, 2)"));
    }
    return List.copyOf(out);
  }

  /**
   * A read at SERIALIZABLE on MySQL or MariaDB that is not one table row by row: each ScalarDB
   * table's in-flight index read under a shared lock, whose gap lock holds off every ScalarDB
   * prepare of the table until commit, then the statement on the data tables, which then hold the
   * committed rows only, under {@code LOCK IN SHARE MODE} (design 4.2, the coarse path). A table
   * the statement reaches in a subquery or a derived table would be read unlocked, from the
   * snapshot: refused.
   */
  private Route coarseRead(
      String sql, SqlAst ast, IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb, String tables)
      throws SQLException {
    SqlAst.Split split =
        ast.split(
            r -> {
              TableInfo t = scalarDb.get(r);
              return t == null ? null : t.keyColumns();
            });
    IdentityHashMap<SqlAst.TableRef, String> data = new IdentityHashMap<>();
    for (Map.Entry<SqlAst.TableRef, TableInfo> e : scalarDb.entrySet()) {
      TableInfo t = e.getValue();
      SqlAst.Place place = split.places().get(e.getKey());
      if (!ast.singleSelect() || place == null) {
        throw Errors.unsupported(
            "at SERIALIZABLE on this engine a read that is not of one table row by row reaches"
                + " its ScalarDB tables in the FROM clause of a single SELECT or in an IN or EXISTS"
                + " subquery of its WHERE clause, never in a derived table or a set operation"
                + tables);
      }
      if (t.layout() == Layout.CLASSIC) {
        throw Errors.unsupported(
            "tables in ScalarDB's classic layout are not supported yet" + tables);
      }
      if (e.getKey().hasHintOrLink()) {
        throw Errors.unsupported(
            "a table hint, sample clause, PIVOT or database link on a ScalarDB table is not"
                + " supported"
                + tables);
      }
      data.put(e.getKey(), engine.quote(t.namespace()) + "." + engine.quote(t.dataTable()));
    }
    String rewritten = ast.joinTokens(data::get);
    SqlAst again =
        rewritten == null
            ? null
            : SqlAst.parse(rewritten, engine, session.doubleQuotesQuoteIdentifiersAsLastRead())
                .orElse(null);
    // every SELECT locked, a subquery's too: none reads in the snapshot (todo 229)
    String locked = again == null ? null : lockingEveryRead(again, rewritten);
    if (locked == null) {
      throw Errors.unsupported("the statement cannot be rewritten onto the data tables" + tables);
    }
    Route r =
        route(
            sql,
            locked,
            SqlAst.Kind.SELECT,
            false,
            false,
            false,
            null,
            ast.parameterCount(),
            0,
            List.of(),
            true);
    List<String> checks = new ArrayList<>();
    if (engine == Engine.SQLSERVER) {
      // the in-flight index read under a range lock, which holds off every prepare of the table
      for (TableInfo t : byName(scalarDb).values()) {
        checks.add(
            Writes.anyRow(
                engine,
                "SELECT 1 FROM "
                    + SplitRead.meta(engine, t)
                    + " WITH (HOLDLOCK, INDEX("
                    + engine.quote(t.inflightIndex(engine))
                    + ")) WHERE "
                    + engine.quote("tx_state")
                    + " IN (1, 2)"));
      }
    } else {
      for (String check : checks(scalarDb)) {
        checks.add(check + " LOCK IN SHARE MODE");
      }
    }
    return r.checked(checks);
  }

  /** Each ScalarDB table of a read once, in the order of their names, which locks follow. */
  private static Map<String, TableInfo> byName(IdentityHashMap<SqlAst.TableRef, TableInfo> refs) {
    Map<String, TableInfo> out = new TreeMap<>();
    for (TableInfo t : refs.values()) {
      out.put(t.namespace() + "." + t.table(), t);
    }
    return out;
  }

  /**
   * {@code text} with every read under a shared lock, as the engine's SERIALIZABLE would take it
   * (todo 229): {@code LOCK IN SHARE MODE} after every SELECT on MySQL and MariaDB, {@code
   * HOLDLOCK} on every table on SQL Server; null when one cannot be placed.
   */
  private @Nullable String lockingEveryRead(SqlAst ast, String text) {
    return engine == Engine.SQLSERVER
        ? ast.holdingEveryTable(text)
        : ast.lockingEverySelect(text, " LOCK IN SHARE MODE");
  }

  private static final String LOCKS_EVERY_SELECT =
      "at SERIALIZABLE on MySQL, MariaDB and SQL Server every read is under a lock, which a"
          + " statement with a # comment, a SELECT joining information_schema to other tables, a"
          + " locking clause whose OF list leaves a table out, a table with SQL Server's NOLOCK"
          + " or INDEX hint, or a call of a SQL Server function of your own, whose reads no lock"
          + " hint reaches, cannot be given; use -- or /* */ comments, read information_schema on"
          + " its own, name every table in OF, and leave those hints and functions out";

  private static final String LEVELS =
      "statements on ScalarDB tables run at READ COMMITTED, REPEATABLE READ and SERIALIZABLE on"
          + " PostgreSQL, MySQL, MariaDB and SQL Server, at READ COMMITTED and REPEATABLE READ on"
          + " Oracle; set one of those levels on the connection";

  /** Whether statements on ScalarDB tables run at the connection's level on this engine yet. */
  private boolean levelSupported() {
    lock.lock();
    try {
      return isolation == Connection.TRANSACTION_READ_COMMITTED
          || isolation == Connection.TRANSACTION_REPEATABLE_READ
          || (isolation == Connection.TRANSACTION_SERIALIZABLE
              && (engine == Engine.POSTGRESQL || locksAtSerializable()));
    } finally {
      lock.unlock();
    }
  }

  /** The level statements on ScalarDB tables run at. */
  int isolation() {
    lock.lock();
    try {
      return isolation;
    } finally {
      lock.unlock();
    }
  }

  /**
   * The session's level on SQL Server, read from the server, since the vendor driver reports the
   * level it last set itself (todo 264): its lock-based REPEATABLE READ, at which the driver does
   * not run, as {@code TRANSACTION_NONE}, which refuses every statement on a ScalarDB table.
   */
  private static int sqlServerLevel(Connection vendor) throws SQLException {
    try (Statement st = vendor.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT transaction_isolation_level FROM sys.dm_exec_sessions"
                    + " WHERE session_id = @@SPID")) {
      switch (rs.next() ? rs.getInt(1) : 0) {
        case 1:
          return Connection.TRANSACTION_READ_UNCOMMITTED;
        case 2:
          return Connection.TRANSACTION_READ_COMMITTED;
        case 4:
          return Connection.TRANSACTION_SERIALIZABLE;
        case 5:
          return Connection.TRANSACTION_REPEATABLE_READ;
        default:
          return Connection.TRANSACTION_NONE;
      }
    }
  }

  /** SQL Server's SNAPSHOT level, the vendor driver's {@code TRANSACTION_SNAPSHOT}. */
  static final int SQLSERVER_SNAPSHOT = 4096;

  /**
   * The vendor connection's level for the driver's {@code level} (design 5): SNAPSHOT is SQL
   * Server's SNAPSHOT level, its REPEATABLE READ being lock-based; SERIALIZABLE on MySQL, MariaDB
   * and SQL Server is the snapshot level with the driver's locking re-reads.
   */
  int vendorLevel(int level) {
    if (level == Connection.TRANSACTION_SERIALIZABLE && locksAtSerializable()) {
      level = Connection.TRANSACTION_REPEATABLE_READ;
    }
    if (level == Connection.TRANSACTION_REPEATABLE_READ && engine == Engine.SQLSERVER) {
      return SQLSERVER_SNAPSHOT;
    }
    if (level == Connection.TRANSACTION_REPEATABLE_READ && engine == Engine.ORACLE) {
      return Connection.TRANSACTION_SERIALIZABLE; // Oracle's snapshot isolation
    }
    return level;
  }

  /**
   * The level an Oracle session runs at for the driver's: REPEATABLE READ is Oracle's SERIALIZABLE
   * in a transaction, and READ COMMITTED in autocommit, where each statement is one: at
   * SERIALIZABLE ojdbc commits after a write only, so a read in autocommit would open a transaction
   * that later reads go on in, at its snapshot, and SET TRANSACTION fails in (ORA-01453); one
   * statement's snapshot is READ COMMITTED's own.
   *
   * @param autocommit whether the connection is in autocommit
   * @return the JDBC level to set on the vendor connection
   */
  int oracleSessionLevel(boolean autocommit) {
    return autocommit ? Connection.TRANSACTION_READ_COMMITTED : vendorLevel(isolation());
  }

  /** The engine this router routes for. */
  Engine engine() {
    return engine;
  }

  /** The driver's level for a vendor connection's: SQL Server's SNAPSHOT is REPEATABLE READ. */
  static int driverLevel(int vendorLevel) {
    return vendorLevel == SQLSERVER_SNAPSHOT ? Connection.TRANSACTION_REPEATABLE_READ : vendorLevel;
  }

  /**
   * Whether SERIALIZABLE runs at the engine's snapshot level with the driver's locking re-reads, on
   * MySQL, MariaDB and SQL Server (design 4.2), rather than at the engine's own SERIALIZABLE.
   */
  boolean locksAtSerializable() {
    return engine == Engine.MYSQL || engine == Engine.MARIADB || engine == Engine.SQLSERVER;
  }

  /** Whether the connection runs SERIALIZABLE by the driver's locking re-reads. */
  private boolean lockingSerializable() {
    lock.lock();
    try {
      return isolation == Connection.TRANSACTION_SERIALIZABLE && locksAtSerializable();
    } finally {
      lock.unlock();
    }
  }

  /** Whether the connection runs at SNAPSHOT or SERIALIZABLE, which abort on a row in flight. */
  boolean snapshot() {
    lock.lock();
    try {
      return isolation == Connection.TRANSACTION_REPEATABLE_READ
          || isolation == Connection.TRANSACTION_SERIALIZABLE;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Runs a read's checks for rows in flight, those its transaction has not passed yet: in one
   * snapshot a table without rows in flight stays so (design 4.2).
   *
   * @param vendor the vendor connection, in a transaction
   * @param route the read
   * @param timeout the statement's query timeout in seconds, 0 for none
   * @throws SQLException {@code 40001} when a table has a row in flight
   */
  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC", justification = "the driver's own statements")
  void check(Connection vendor, Route route, int timeout) throws SQLException {
    for (String check : route.checks) {
      lock.lock();
      try {
        if (passed.contains(check)) {
          continue;
        }
      } finally {
        lock.unlock();
      }
      try (Statement st = vendor.createStatement()) {
        st.setQueryTimeout(timeout);
        try (ResultSet rs = st.executeQuery(check)) {
          if (rs.next()) {
            throw new Writes.Aborted(
                "a table the read reaches has a row a ScalarDB transaction is writing");
          }
        }
      }
      lock.lock();
      try {
        passed.add(check);
      } finally {
        lock.unlock();
      }
    }
  }

  private static boolean controlsTransaction(SqlAst.Kind kind) {
    return kind == SqlAst.Kind.COMMIT
        || kind == SqlAst.Kind.ROLLBACK
        || kind == SqlAst.Kind.ROLLBACK_TO_SAVEPOINT
        || kind == SqlAst.Kind.SAVEPOINT;
  }

  private Route route(
      String source,
      String sql,
      SqlAst.@Nullable Kind kind,
      boolean changesSession,
      boolean changesNames,
      boolean passthrough,
      @Nullable List<Integer> parameterSources,
      int parameters,
      long recheckAt,
      List<Integer> perRow,
      boolean plansParallel) {
    return new Route(
        source,
        sql,
        changesSession,
        changesNames,
        passthrough,
        kind,
        isolation,
        session.generation(),
        catalog.generation(),
        catalog.trustedUntil(),
        parameterSources,
        parameters,
        recheckAt,
        false,
        perRow,
        false,
        plansParallel,
        null,
        null,
        null,
        List.of());
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
    if (known != null || !probes) {
      return Boolean.TRUE.equals(known);
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
    if (!probes) {
      return t.keyColumns().get(0).toLowerCase(Locale.ROOT);
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
    if (!probes) {
      return cached == null ? new long[] {0, now, 0} : cached.clone();
    }
    long count;
    // the count only chooses a plan: when it cannot be read, as with a streaming result open on
    // the connection (todo 106), the last one, or none, will do
    try (Statement st = vendor.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COUNT(*) FROM (SELECT "
                    + (engine == Engine.SQLSERVER ? "TOP " + (PER_ROW_ABOVE_IN_RANGES + 1) : "")
                    + " 1 AS one FROM "
                    + meta
                    + " WHERE "
                    + engine.quote("tx_state")
                    + " IN (1, 2)"
                    + (engine == Engine.SQLSERVER ? "" : " LIMIT " + (PER_ROW_ABOVE_IN_RANGES + 1))
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
   */
  void beforeRun(Connection vendor, List<Route> routes) {
    lock.lock();
    try {
      if (engine != Engine.POSTGRESQL || routes.isEmpty()) {
        return;
      }
      boolean off = false;
      boolean on = false;
      for (Route r : routes) {
        if (r.parameterSources != null && !r.parallel) {
          off = true; // a short split read
        } else {
          on |= r.plansParallel;
        }
      }
      if (!off && !on) {
        return; // writes and session statements: the setting does not matter (todo 107)
      }
      try {
        switchWorkers(vendor, off);
      } catch (SQLException e) {
        // only the speed depends on it; the statement itself reports an aborted transaction
        workersOff = null;
      }
    } finally {
      lock.unlock();
    }
  }

  private void switchWorkers(Connection vendor, boolean off) throws SQLException {
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
      // a 0 with a known session value is the driver's own: only a statement that sets it, which
      // forgets the value, or a transaction's end after one, changes the session's
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

  /**
   * Whether a statement may set the workers: it names the setting, as {@code SET} and {@code
   * set_config} do, or resets every setting.
   */
  private static boolean setsWorkers(String sql) {
    String lower = sql.toLowerCase(Locale.ROOT);
    return lower.contains("max_parallel_workers_per_gather") || RESETS_ALL.matcher(lower).find();
  }

  private static final Pattern SETS_LEVEL =
      Pattern.compile("isolation|set_config|\\b(reset|discard)\\b");

  private static final Pattern SETS_SNAPSHOT =
      Pattern.compile("\\bisolation\\s+level\\s+snapshot\\b");

  private static final Pattern SETS_SESSION_REPEATABLE =
      Pattern.compile("\\bsession\\s+transaction\\s+isolation\\s+level\\s+repeatable\\b");

  private static final Map<Integer, String> LEVEL_NAMES =
      Map.of(
          Connection.TRANSACTION_READ_UNCOMMITTED, "READ UNCOMMITTED",
          Connection.TRANSACTION_READ_COMMITTED, "READ COMMITTED",
          Connection.TRANSACTION_REPEATABLE_READ, "REPEATABLE READ",
          Connection.TRANSACTION_SERIALIZABLE, "SERIALIZABLE");

  private static final Pattern RESETS_ALL = Pattern.compile("\\b(reset|discard)\\s+all\\b");

  /** After a rollback, which undoes a SET made in the transaction. */
  void rolledBack() {
    lock.lock();
    try {
      workersOff = null;
    } finally {
      lock.unlock();
    }
  }

  /** At commit or rollback: a SET of the workers in the transaction may have been undone. */
  void transactionEnded() {
    lock.lock();
    try {
      passed.clear();
      if (workersSetInTransaction) {
        sessionWorkers = -1;
        workersOff = null;
        workersSetInTransaction = false;
      }
    } finally {
      lock.unlock();
    }
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
    // the kept text's hidden columns, not the new route's (todo 144)
    return now.with(kept.sql, kept.parameterSources, kept.perRow, false, kept.parallel)
        .anchored(kept.anchored, kept.anchoring);
  }

  /**
   * Classifies a table by its stored names, for {@link ScalarDbTables}: the lookup a statement
   * naming it, quoted, would make.
   *
   * @param vendor the vendor connection, for the session's schemas
   * @param schema the stored schema name, or null for the table the session's schemas find
   * @param table the stored table name
   * @return how the driver treats the table
   * @throws SQLException if a catalog cannot be read
   */
  ScalarDbTables.Kind kind(Connection vendor, @Nullable String schema, String table)
      throws SQLException {
    TableInfo info;
    try {
      info =
          catalog.lookup(
              session.schemas(vendor),
              schema == null ? null : engine.quote(schema),
              engine.quote(table));
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
    lock.lock();
    try {
      boolean unchanged =
          prepared.sessionGeneration == session.generation()
              && prepared.catalogGeneration == catalog.generation()
              && !catalog.hasPassed(prepared.trustedUntil)
              && prepared.passthrough == passthrough
              && prepared.isolation == isolation
              && (!prepared.passthrough || vendor.getAutoCommit())
              && (prepared.anchoring == null || prepared.anchoring == anchoring(vendor));
      if (unchanged && (prepared.recheckAt == 0 || System.nanoTime() - prepared.recheckAt < 0)) {
        return prepared;
      }
      Route now = decide(vendor, prepared.source, null, null);
      if (now.sql.equals(prepared.sql)
          || (now.perRow.equals(prepared.perRow)
              && Objects.equals(now.anchoring, prepared.anchoring))) {
        return now;
      }
      // the per-row choices or the anchors moved: is that all that moved? It is when only the
      // counts were due
      if (!unchanged
          && !decide(vendor, prepared.source, prepared.perRow, prepared.anchoring)
              .sql
              .equals(prepared.sql)) {
        return now;
      }
      return now.with(now.sql, now.parameterSources, now.perRow, true, now.parallel);
    } finally {
      lock.unlock();
    }
  }

  /**
   * After a routed statement ran, or failed, since it may have taken effect before failing: re-read
   * the session and forget resolved names, as it requires. Never throws.
   */
  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC", justification = "a level name of the driver's")
  void ran(Connection vendor, Route route) {
    lock.lock();
    try {
      if (route.kind == SqlAst.Kind.COMMIT) {
        session.transactionEnded();
        transactionEnded();
      } else if (route.kind == SqlAst.Kind.ROLLBACK) {
        rolledBack();
        session.transactionEnded();
        transactionEnded();
      } else if (route.kind == SqlAst.Kind.ROLLBACK_TO_SAVEPOINT) {
        rolledBack();
        session.rolledBackToSavepoint();
      }
      if (route.changesSession) {
        if (engine == Engine.POSTGRESQL && setsWorkers(route.source)) {
          sessionWorkers = -1;
          workersOff = null;
          try {
            workersSetInTransaction |= !vendor.getAutoCommit();
          } catch (SQLException e) {
            workersSetInTransaction = true; // unknown: the transaction's end, if any, forgets too
          }
        }
        session.changed(vendor);
      }
      if (route.changesNames) {
        catalog.invalidateResolutions();
      }
      String text = route.source.toLowerCase(Locale.ROOT);
      if (route.passthrough && SETS_LEVEL.matcher(text).find()) {
        // passthrough may set the level, or reset it, in SQL: follow the session (todos 184, 222)
        try {
          int level =
              engine == Engine.SQLSERVER
                  ? sqlServerLevel(vendor)
                  : driverLevel(vendor.getTransactionIsolation());
          if (engine == Engine.ORACLE) {
            // ojdbc reports the level it last set, and Oracle shows no session's own: the level
            // set in SQL is the connection's again, which the driver's reads are written for
            try (Statement s = vendor.createStatement()) {
              // passthrough runs in autocommit, whose level is READ COMMITTED
              s.execute("ALTER SESSION SET ISOLATION_LEVEL = READ COMMITTED");
            }
          } else if (engine == Engine.SQLSERVER) {
            // SNAPSHOT, what SERIALIZABLE runs at, is followed only where the statement names it;
            // SQL Server's own SERIALIZABLE is the driver's
            if (level != Connection.TRANSACTION_REPEATABLE_READ
                || isolation != Connection.TRANSACTION_SERIALIZABLE
                || SETS_SNAPSHOT.matcher(text).find()) {
              isolation = level;
            }
            if (isolation != Connection.TRANSACTION_NONE) {
              // the vendor driver's own idea of the level, which it never reads back, and the
              // session at the level the driver runs it at
              vendor.setTransactionIsolation(vendorLevel(isolation));
            }
          } else if (!locksAtSerializable()) {
            isolation = level;
          } else {
            // the level is read, not the text, which a comment or a SELECT may name it in; what
            // SERIALIZABLE runs at, REPEATABLE READ, is followed only where the statement set it
            // for the session, not globally or for the next transaction (todos 232, 244, 249)
            int expected =
                isolation == Connection.TRANSACTION_SERIALIZABLE
                    ? Connection.TRANSACTION_REPEATABLE_READ
                    : isolation;
            if (level == Connection.TRANSACTION_SERIALIZABLE) {
              isolation = level;
              level = Connection.TRANSACTION_REPEATABLE_READ;
            } else if (level != expected || SETS_SESSION_REPEATABLE.matcher(text).find()) {
              isolation = level;
            }
            // set it again: that clears a SET TRANSACTION for the next transaction alone, which
            // the session's level does not show, in autocommit as passthrough runs
            try (Statement s = vendor.createStatement()) {
              s.execute("SET SESSION TRANSACTION ISOLATION LEVEL " + LEVEL_NAMES.get(level));
            }
          }
        } catch (SQLException e) {
          // the connection failed; its next statement reports it
        }
      }
    } finally {
      lock.unlock();
    }
  }
}
