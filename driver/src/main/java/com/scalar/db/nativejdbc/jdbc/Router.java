package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.ScalarDbTables;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.Outcome;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.SqlWords;
import com.scalar.db.nativejdbc.sql.StatementShape;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.ForMode;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
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

    /**
     * For a read of one ScalarDB table, the check for rows in flight its predicate matches by
     * either image, run when its table's check finds a row: none found, the read goes on (design
     * 4.2); null otherwise.
     */
    final Writes.@Nullable Step fallback;

    /** The returned columns that are BOOLEAN columns of ScalarDB tables, or null (todo 304). */
    final @Nullable Booleans booleans;

    /**
     * At SNAPSHOT and SERIALIZABLE on PostgreSQL, the statement as written over the data tables,
     * which a transaction that found every table of {@link #clearedBy} clear runs instead of {@link
     * #sql} (design 4.2, todo 329); null otherwise.
     */
    final @Nullable String plainSql;

    /** The coarse checks of the ScalarDB tables {@link #plainSql} reads, each to have passed. */
    final List<String> clearedBy;

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
        List<String> checks,
        Writes.@Nullable Step fallback,
        @Nullable Booleans booleans,
        @Nullable String plainSql,
        List<String> clearedBy) {
      this.plainSql = plainSql;
      this.clearedBy = List.copyOf(clearedBy);
      this.booleans = booleans;
      this.anchoring = anchoring;
      this.checks = List.copyOf(checks);
      this.fallback = fallback;
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
      return copy(
          sql,
          parameterSources,
          perRow,
          countsOnly,
          parallel,
          a,
          anchoring,
          checks,
          fallback,
          booleans);
    }

    /**
     * This route, with the checks a read at SNAPSHOT or SERIALIZABLE runs first and the predicate's
     * check that may clear a row they find.
     */
    Route checked(List<String> checks, Writes.@Nullable Step fallback) {
      return copy(
          sql,
          parameterSources,
          perRow,
          countsOnly,
          parallel,
          anchored,
          anchoring,
          checks,
          fallback,
          booleans);
    }

    /** This route with another statement text and what goes with it. */
    Route with(
        String sql,
        @Nullable List<Integer> parameterSources,
        List<Integer> perRow,
        boolean countsOnly,
        boolean parallel) {
      return copy(
          sql,
          parameterSources,
          perRow,
          countsOnly,
          parallel,
          anchored,
          anchoring,
          checks,
          fallback,
          booleans);
    }

    /** This route, its returned BOOLEAN columns as {@code b} says. */
    Route booleans(@Nullable Booleans b) {
      return copy(
          sql,
          parameterSources,
          perRow,
          countsOnly,
          parallel,
          anchored,
          anchoring,
          checks,
          fallback,
          b);
    }

    /** This route, with the statement a transaction runs once it found the tables clear. */
    Route plain(@Nullable String plainSql, List<String> clearedBy) {
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
          checks,
          fallback,
          booleans,
          plainSql,
          clearedBy);
    }

    private Route copy(
        String sql,
        @Nullable List<Integer> parameterSources,
        List<Integer> perRow,
        boolean countsOnly,
        boolean parallel,
        @Nullable Anchored anchored,
        @Nullable Boolean anchoring,
        List<String> checks,
        Writes.@Nullable Step fallback,
        @Nullable Booleans booleans) {
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
          checks,
          fallback,
          booleans,
          plainSql,
          clearedBy);
    }
  }

  /**
   * The returned columns, by position, that are BOOLEAN columns of ScalarDB tables on MySQL and
   * MariaDB, where a UNION ALL, as a split read and a read view are, widens {@code TINYINT(1)} to
   * {@code TINYINT}, which the vendor then reports as a number (todo 304); and a read of one such
   * column as it is, whose metadata the vendor reports for them.
   */
  static final class Booleans {
    final List<Integer> columns;
    final String probe;

    Booleans(List<Integer> columns, String probe) {
      this.columns = List.copyOf(columns);
      this.probe = probe;
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

    /**
     * Whether each table's columns carry the row's version, the anchor recorded; not at SNAPSHOT
     * and SERIALIZABLE on PostgreSQL, where only the states are read (todo 329).
     */
    final boolean versions;

    /**
     * Per table, the coarse check of {@link #inFlightCheck} its state column stands for: a state of
     * 0 says the check would find no row in the transaction's snapshot (todo 329).
     */
    final List<String> checks;

    private final int hidden;

    Anchored(
        List<String> tables,
        List<Integer> keys,
        boolean states,
        boolean versions,
        List<String> checks) {
      this.tables = tables;
      this.keys = keys;
      this.states = states;
      this.versions = versions;
      this.checks = List.copyOf(checks);
      this.hidden = keys.stream().mapToInt(k -> k + width()).sum();
    }

    /** The columns each table appends after its key: the version, and the state with states. */
    int width() {
      return (versions ? 2 : 0) + (states ? 1 : 0);
    }

    /** Columns the driver appended that are hidden only, not recorded: {@code hidden} of them. */
    Anchored(String table, int keys, int hidden) {
      this.tables = List.of(table);
      this.keys = List.of(keys);
      this.states = false;
      this.versions = false;
      this.checks = List.of("");
      this.hidden = hidden;
    }

    /**
     * The hidden trailing columns: per table its key's, then {@code tx_id} and {@code tx_version}
     * with {@link #versions}, then {@code tx_state} with {@link #states}.
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

  /** ScalarDB's default namespace for its coordinator table. */
  static final String COORDINATOR_NAMESPACE = "coordinator";

  private final Engine engine;
  private final Catalog catalog;
  private final Session session;

  /**
   * ScalarDB's namespace for its coordinator table, which a statement sent as written must not
   * name.
   */
  private final String coordinatorNamespace;

  private final ReentrantLock lock = new ReentrantLock();
  // per metadata table: the in-flight count, capped above PER_ROW_ABOVE_IN_RANGES, when it was
  // read, and which per-row choices were last made: 1 by equality, 2 in ranges
  private final Map<String, long[]> inflight = new HashMap<>();
  // per data table: the lower-cased first column of its primary key, in the database's order
  private final Map<String, String> keyLead = new HashMap<>();
  // PostgreSQL's max_parallel_workers_per_gather: the session's own value (-1 until read); whether
  // the driver's 0 is in effect (null when not known, after a SET of it or a failed switch); the
  // same as the open transaction began, which its rollback restores; whether the driver set it in
  // the open transaction, which a rollback to a savepoint may have undone; and whether a statement
  // in the open transaction set it, which the transaction's end may undo
  private long sessionWorkers = -1;
  private @Nullable Boolean workersOff;
  private @Nullable Boolean workersOffAtStart;
  private boolean workersSwitchedInTransaction;
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
  // the application's Connection.setReadOnly, which SQL Server's driver ignores (todo 324)
  private boolean readOnly;
  // the checks for rows in flight the open transaction passed, in its snapshot
  private final Set<String> passed = new HashSet<>();
  // the checks that found a row in flight in the open transaction's snapshot, which keeps it:
  // not run again, the predicate's check run at once
  private final Set<String> found = new HashSet<>();
  // scalardbnative.predicateChecks: a read of one table checks its predicate's rows in flight
  // before it aborts on its table's
  private final boolean predicateChecks;

  Router(Engine engine, Catalog catalog, Session session) {
    this(engine, catalog, session, false, COORDINATOR_NAMESPACE);
  }

  Router(
      Engine engine,
      Catalog catalog,
      Session session,
      boolean predicateChecks,
      String coordinatorNamespace) {
    this.predicateChecks = predicateChecks;
    this.engine = engine;
    this.catalog = catalog;
    this.session = session;
    this.coordinatorNamespace = coordinatorNamespace;
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

  /** After the application's {@code setReadOnly}, which the driver tracks itself (todo 324). */
  void setReadOnly(boolean on) {
    lock.lock();
    try {
      readOnly = on;
    } finally {
      lock.unlock();
    }
  }

  /** Whether the application set the connection read-only. */
  boolean readOnly() {
    lock.lock();
    try {
      return readOnly;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Whether the connection runs read-only transactions at SERIALIZABLE without the locking
   * re-reads, as at SNAPSHOT (design 5, todo 324): on MySQL, MariaDB and SQL Server, where the
   * writers' locks and ScalarDB's validation give them one serial order, of which a snapshot that
   * refuses rows in flight is a cut. Such a transaction runs reads alone.
   */
  boolean readOnlySerializable() {
    lock.lock();
    try {
      return readOnly && isolation == Connection.TRANSACTION_SERIALIZABLE && locksAtSerializable();
    } finally {
      lock.unlock();
    }
  }

  /**
   * The level a route is decided at: the connection's, but REPEATABLE READ, the driver's SNAPSHOT,
   * for a read-only one at SERIALIZABLE that runs as SNAPSHOT does ({@link #readOnlySerializable}),
   * so that a statement prepared before {@code setReadOnly} is routed again.
   */
  private int routedLevel() {
    return readOnlySerializable() ? Connection.TRANSACTION_REPEATABLE_READ : isolation();
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
   * The names a statement sent as written must not contain, in passthrough (todo 323) or unparsed
   * (todo 314): every ScalarDB table's, its physical tables' among them, ScalarDB's catalog schema,
   * its coordinator namespace (todo 332), and on Oracle the synonyms a parsed statement may not
   * use.
   */
  private List<String> protectedNames() throws SQLException {
    List<String> names = new ArrayList<>();
    names.add(catalog.metadataSchema());
    names.add(coordinatorNamespace);
    for (TableInfo t : catalog.scalarDbTables()) {
      names.add(t.table());
      names.add(t.dataTable());
      if (t.metadataTable() != null) {
        names.add(t.metadataTable());
      }
      if (t.virtualTable() != null) {
        names.add(t.virtualTable());
      }
    }
    names.addAll(catalog.guardedSynonyms(List.copyOf(names)));
    return names;
  }

  /** The namespaces of ScalarDB's tables, which DDL on a schema or database reaches them by. */
  private Set<String> namespaces() throws SQLException {
    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (TableInfo t : catalog.scalarDbTables()) {
      names.add(t.namespace());
    }
    return names;
  }

  /** The setup's read views and in-flight indexes, which a ScalarDB table is read through. */
  private Set<String> setupNames() throws SQLException {
    Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (TableInfo t : catalog.scalarDbTables()) {
      names.add(engine.readViewName(t.table()));
      names.add(engine.inflightIndexName(t.table()));
    }
    return names;
  }

  /**
   * What DDL that names no ScalarDB table still reaches one by (todo 331): its namespace, as {@code
   * DROP SCHEMA} or {@code DROP DATABASE} names it, the setup's read view or in-flight index, or
   * {@code DROP OWNED}; for DDL the parser reads without its targets ({@code ALTER SCHEMA}, {@code
   * ALTER INDEX}), any of these or a protected name among its words.
   *
   * @return what it reaches, or null
   */
  private @Nullable String reached(String sql, SqlAst ast) throws SQLException {
    if (ast.kind() == SqlAst.Kind.OTHER) {
      if (!SqlWords.startsDdl(sql, engine)) {
        return null;
      }
      List<String> names = protectedNames();
      names.addAll(namespaces());
      names.addAll(setupNames());
      return SqlWords.named(sql, names);
    }
    if (SqlWords.dropsOwned(sql, engine)) {
      return "what a role owns";
    }
    Set<String> namespaces = namespaces();
    Set<String> setup = setupNames();
    for (SqlAst.TableRef ref : ast.tableRefs()) {
      if ((ref.schema() == null && namespaces.contains(ref.name())) || setup.contains(ref.name())) {
        return ref.schema() == null ? ref.name() : ref.schema() + "." + ref.name();
      }
    }
    return null;
  }

  /**
   * Routes a statement the parser could not read (todo 314): sent as written when its words name no
   * ScalarDB table and it is a query, DML or DDL ({@link SqlWords#whyNotAsWritten}); refused where
   * every read must be locked, which needs the parsed statement.
   */
  private Route unparsed(String sql, String reason) throws SQLException {
    if (readOnlySerializable()) {
      // unparsed, it may write (todo 324)
      throw new SQLException(READ_ONLY, "25006");
    }
    if (lockingSerializable()) {
      throw Errors.unsupported(
          reason
              + "; at SERIALIZABLE on MySQL, MariaDB and SQL Server the driver locks every read,"
              + " which it cannot do in a statement it cannot parse");
    }
    List<String> names = protectedNames();
    names.addAll(namespaces());
    names.addAll(setupNames());
    String why = SqlWords.whyNotAsWritten(sql, engine, names);
    if (why != null) {
      throw Errors.unsupported(reason + "; it is not sent as written since " + why);
    }
    // DDL may change what names resolve to, and the session's settings, as parsed DDL does
    boolean ddl = SqlWords.startsDdl(sql, engine);
    return route(sql, sql, null, ddl, ddl, false, null, 0, 0, List.of(), false);
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
      if (readOnlySerializable()) {
        // unparsed, it may write (todo 324)
        throw new SQLException(READ_ONLY, "25006");
      }
      String named = SqlWords.named(sql, protectedNames());
      if (named != null) {
        // sent as written, it would bypass ScalarDB's protocol on the table (todo 323)
        throw Errors.unsupported(
            "passthrough does not run a statement that names a ScalarDB table or ScalarDB's own"
                + " schema ("
                + named
                + "); run it without passthrough, or on a connection of the database's own driver"
                + " with ScalarDB's transactions on the table stopped");
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
      if (parsed.isUnreadable()) {
        return unparsed(sql, parsed.reason());
      }
      throw Errors.unsupported(parsed.reason());
    }
    SqlAst ast = parsed.get();
    SqlAst.Kind kind = ast.kind();
    if (controlsTransaction(kind)) {
      return route(sql, sql, kind, false, false, false, null, 0, 0, List.of(), false);
    }
    if (readOnlySerializable()
        && (!(kind == SqlAst.Kind.SELECT || kind == SqlAst.Kind.SESSION)
            || (engine == Engine.SQLSERVER && ast.hasLockingOrIntoClause()))) {
      // its reads take no locks: a write beside them would be snapshot isolation's, which
      // SERIALIZABLE must not give (design 5, todo 324); SQL Server's SELECT ... INTO writes a
      // table
      throw new SQLException(READ_ONLY, "25006");
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
          "a SQL Server table hint the parser cannot read (any but WITH (NOLOCK) or WITH (INDEX"
              + " ...)) on a table ScalarDB does not manage is not supported in a statement that"
              + " reads a ScalarDB table");
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
              forcedAnchoring != null ? forcedAnchoring : anchoring(vendor))
          .booleans(booleans(ast, scalarDb));
    }
    if (kind == SqlAst.Kind.DDL || kind == SqlAst.Kind.TRUNCATE || kind == SqlAst.Kind.OTHER) {
      String reached = reached(sql, ast);
      if (reached != null) {
        throw Errors.unsupported(SCHEMA_CHANGE + " (" + reached + ")");
      }
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

  /** The returned BOOLEAN columns of a read of ScalarDB tables, on MySQL and MariaDB (todo 304). */
  private @Nullable Booleans booleans(
      SqlAst ast, IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb) {
    if (engine != Engine.MYSQL && engine != Engine.MARIADB) {
      return null;
    }
    List<SqlAst.@Nullable ColumnSource> sources =
        ast.selectSources(
            r -> {
              TableInfo t = scalarDb.get(r);
              return t == null ? null : t.dataColumns();
            });
    List<Integer> columns = new ArrayList<>();
    String probe = null;
    for (int i = 0; sources != null && i < sources.size(); i++) {
      SqlAst.ColumnSource c = sources.get(i);
      TableInfo t = c == null ? null : scalarDb.get(c.table());
      if (c != null && t != null && t.booleanColumns().contains(c.column())) {
        columns.add(i + 1);
        probe =
            "SELECT "
                + engine.quote(c.column())
                + " FROM "
                + engine.quote(t.namespace())
                + "."
                + engine.quote(t.dataTable())
                + " WHERE 1 = 0";
      }
    }
    return probe == null ? null : new Booleans(columns, probe);
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
    // a column qualified by the schema and the table, as Spring Data JDBC writes it, qualified by
    // the table alone, which the rewrite keeps naming the table (milestone 9c)
    String text = engine == Engine.SQLSERVER ? sql : ast.source();
    String unqualified = ast.withoutSchemaQualifiers(shape.get().table(), text);
    if (unqualified != null && !unqualified.equals(text)) {
      SqlAst again =
          SqlAst.parse(unqualified, engine, session.doubleQuotesQuoteIdentifiersAsLastRead())
              .orElse(null);
      Outcome<StatementShape> reshaped = again == null ? null : StatementShape.classify(again);
      if (reshaped != null && reshaped.isPresent()) {
        shape = reshaped;
      }
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
        routedLevel(),
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
        List.of(),
        null,
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
  private boolean anchoring(Connection vendor) throws SQLException {
    return !vendor.getAutoCommit() && !readOnly();
  }

  /**
   * Whether reads take no versions (todo 329): at SNAPSHOT and SERIALIZABLE on PostgreSQL every
   * statement of the transaction reads its one snapshot, so a later write's own read of its keys
   * (4.3) finds the version a read would have recorded, and a version that moved after the snapshot
   * fails the write with PostgreSQL's serialization failure. The same on Oracle, whose SERIALIZABLE
   * is snapshot isolation and fails a write to a row changed since the snapshot with ORA-08177
   * (design 4.2 "On Oracle both hold", todo 334). Elsewhere an UPDATE reads the newest committed
   * version, which the recorded anchor has to catch.
   */
  private boolean versionless() {
    return (engine == Engine.POSTGRESQL || engine == Engine.ORACLE) && snapshot();
  }

  /**
   * The route cache's key: the text, per anchoring mode, so that a text read both in and out of
   * transactions keeps a route for each.
   */
  private String key(Connection vendor, String sql) throws SQLException {
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
   * copy's before image's (design 4.2). Without {@code versioned} only the state, with {@code
   * states}: 1 for a row whose key is in flight, NULL otherwise, a committed row (todo 329).
   */
  private List<String> versions(
      SqlAst.TableRef ref, Writes.Names n, SqlAst.Piece piece, boolean states, boolean versioned) {
    return versions(ref, n, piece, states, versioned, false);
  }

  /**
   * As {@link #versions(SqlAst.TableRef, Writes.Names, SqlAst.Piece, boolean, boolean)}; with
   * {@code own}, the versions are the copy's own columns, which it carries in place of a lookup per
   * row ({@link SplitRead#OWN}, todo 329), the state among them with {@code states}.
   */
  private List<String> versions(
      SqlAst.TableRef ref,
      Writes.Names n,
      SqlAst.Piece piece,
      boolean states,
      boolean versioned,
      boolean own) {
    String x = exposed(ref);
    String m = engine.quote(META_ALIAS);
    List<String> out = new ArrayList<>();
    if (own && versioned) {
      for (String k : n.keyColumns) {
        out.add(n.key(x, k));
      }
      for (int i = 0; i < (states ? 3 : 2); i++) {
        out.add(x + "." + engine.quote(SplitRead.OWN + i));
      }
      return out;
    }
    if (!versioned) {
      if (states) {
        // 0 when the table has no row in flight at all, which one probe of the in-flight index
        // decides once per statement, and which later statements of the transaction build on
        // (passed); else one hashed set of the keys in flight, read once per statement from
        // that index rather than a lookup per row, 1 for a row in it
        // ponytail: past work_mem the set is scanned per row; the rows in flight are few
        List<String> keys = new ArrayList<>();
        List<String> inFlight = new ArrayList<>();
        for (String k : n.keyColumns) {
          keys.add(n.key(x, k));
          inFlight.add(m + "." + engine.quote(k));
        }
        String held = m + "." + engine.quote("tx_state") + " IN (1, 2)";
        out.add(
            "CASE WHEN NOT EXISTS (SELECT "
                + n.inflightHint(m)
                + "1 FROM "
                + n.meta
                + " "
                + m
                + " WHERE "
                + held
                + ") THEN 0 WHEN ("
                + String.join(", ", keys)
                + ") IN (SELECT "
                + n.inflightHint(m)
                + String.join(", ", inFlight)
                + " FROM "
                + n.meta
                + " "
                + m
                + " WHERE "
                + held
                + ") THEN 1 END");
      }
      return out;
    }
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
    // columns qualified by the schema and the table, as Spring Data JDBC writes them, qualified by
    // the table alone, as for a write (milestone 9c)
    // on SQL Server the application's text, whose hints the source has blanked out
    String original = engine == Engine.SQLSERVER ? sql : ast.source();
    String unqualified = ast.withoutSchemaQualifiers(ref, original);
    if (unqualified != null && !unqualified.equals(original)) {
      SqlAst again =
          SqlAst.parse(unqualified, engine, session.doubleQuotesQuoteIdentifiersAsLastRead())
              .orElse(null);
      List<SqlAst.TableRef> before = ast.tableRefs();
      if (again != null && again.tableRefs().size() == before.size()) {
        IdentityHashMap<SqlAst.TableRef, TableInfo> remapped = new IdentityHashMap<>();
        SqlAst.TableRef target = null;
        for (int i = 0; i < before.size(); i++) {
          TableInfo info = scalarDb.get(before.get(i));
          if (info != null) {
            remapped.put(again.tableRefs().get(i), info);
          }
          if (before.get(i) == ref) {
            target = again.tableRefs().get(i);
          }
        }
        if (target != null) {
          return lockingRead(sql, again, remapped, target, tables);
        }
      }
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
    if (ast.source().contains(LOCK_MARK)) {
      // the driver's own mark, which the user's text must not bring
      throw Errors.unsupported(
          "the locking read cannot be rewritten onto the committed rows" + tables);
    }
    if (engine == Engine.SQLSERVER) {
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
        hints.remove("REPEATABLEREAD"); // which conflicts with HOLDLOCK, holding less (todo 270)
        hints.add("HOLDLOCK");
      }
      inner = "WITH (" + String.join(", ", hints) + ")";
      // a lock read through a covering index there locks that index's keys only, an update lock
      // too: the rows are locked again through the primary key as their versions are read
      relock =
          (mode == ForMode.UPDATE ? "UPDLOCK, ROWLOCK" : Writes.shareLock(engine))
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
      String share = Writes.shareLock(engine);
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
      SqlAst bare = plain ? ast : clause < 0 ? null : ast.without(clause, ast.end());
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
    Expression where = shape.where();
    int[] span = where == null ? null : ast.span(where);
    List<Integer> inWhere = span == null ? List.of() : ast.placeholdersIn(span[0], span[1]);
    // at SNAPSHOT and SERIALIZABLE on PostgreSQL, and at SNAPSHOT on Oracle, the statement as
    // written, on the data table: the rows it locks are the committed rows where the table has
    // none in flight (todo 329, todo 334); not for an inline table whose rows it reads whole,
    // which would show the metadata columns. Marked, so that its text differs from the
    // application's own prepared statement of the same text: pgjdbc's statement cache holds one
    // entry per text, and two statements borrowing it in turn would drop each other's
    // server-side plan at every call
    List<Integer> all = ast.placeholdersIn(0, ast.source().length());
    String plainSql =
        (engine == Engine.POSTGRESQL || engine == Engine.ORACLE)
                && snapshot()
                && all != null
                && (!n.inline || ast.columnsReadFrom(readRef) != null)
            ? Writes.onDataTable(engine, ast, t, true, ast.source(), -1)
            : null;
    if (plainSql != null) {
      plainSql = "/* scalardb_native_lock */ " + plainSql;
    }
    // on Oracle at SNAPSHOT, the same statement with the write's gate in its WHERE clause, for a
    // table the transaction has not found clear yet (design 4.3 "The locking read on Oracle",
    // todo 334); SKIP LOCKED keeps the steps and their versions
    String gatedSql =
        engine == Engine.ORACLE && plainSql != null && !shape.skipLocked()
            ? Writes.gatedLock(engine, ast, t, n, span)
            : null;
    if (gatedSql != null) {
      gatedSql = "/* scalardb_native_lock */ " + gatedSql;
    }
    boolean oracleSnapshot = gatedSql != null;
    // the lock sent with its in-flight check in one round trip reads no versions (LockPlan), nor
    // does the Oracle form at SNAPSHOT; the steps on Oracle keep theirs
    boolean versioned =
        !versionless() || shape.skipLocked() || (engine == Engine.ORACLE && !oracleSnapshot);
    SqlAst.Split readSplit = read.split(r -> r == anchoredRef ? t.keyColumns() : null);
    SqlAst.SplitText text =
        SplitRead.emit(
            engine,
            read,
            readSplit,
            readDb,
            new IdentityHashMap<>(),
            Set.of(),
            List.of(readRef),
            (r, piece) -> versions(anchoredRef, n, piece, false, versioned),
            true,
            false,
            inner == null ? null : LOCK_MARK);
    // at READ COMMITTED on PostgreSQL, a table in the inline layout, the statement as written
    // over the rows as they are, each with its key, version and state as columns of the row: the
    // lock returns each row's newest version, the anchor the level wants, and a row in flight
    // shows its state; the images of a row differ in no column the statement matches or orders
    // by, so no row in flight is matched by its before image alone (design 4.2, todo 329)
    // on Oracle too, in both layouts, the decoupled one's metadata row joined, whose restart after
    // a lock wait runs the join again at the new point in time (design 4.3 "The locking read on
    // Oracle", todo 334, its test 6)
    boolean fastRc =
        (engine == Engine.POSTGRESQL || engine == Engine.ORACLE)
            && !snapshot()
            && !shape.skipLocked()
            && !shape.grouped()
            && shape.wherePure()
            && shape.orderByPure()
            && keysOnly(t, shape.whereColumns())
            && keysOnly(t, shape.orderByColumns());
    SqlAst.SplitText fastText = null;
    if (fastRc && (n.inline || engine == Engine.ORACLE)) {
      fastText =
          SplitRead.emit(
              engine,
              read,
              readSplit,
              readDb,
              new IdentityHashMap<>(),
              Set.of(),
              List.of(readRef),
              (r, piece) -> versions(anchoredRef, n, piece, true, true, true),
              true,
              true,
              null,
              Set.of(readRef),
              true);
    }
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
    // the same at READ COMMITTED on a decoupled table, whose state is in its metadata row, which
    // the lock's re-read of a data row does not reach: the lock, then the keys, versions and
    // states of the rows the predicate matches from the data table joined to its metadata table,
    // read in a snapshot taken after the lock, two statements in one round trip (design 4.3,
    // todo 329); the lock's own rows go to the application, the versions to the anchors
    Writes.Step fastRcStep = null;
    if (fastText != null) {
      fastRcStep = new Writes.Step(fastText.sql(), fastText.parameterSources());
    } else if (fastRc && !n.inline) {
      String x = exposed(readRef);
      // the predicate over the data table alone, in a derived table, as the user's columns are
      // unqualified and the metadata table has the key's too
      String versions =
          "SELECT "
              + n.readColumns("d")
              + ", m."
              + n.txId
              + ", m."
              + n.txVersion
              + ", m."
              + n.txState
              + ", (m."
              + engine.quote("before_tx_id")
              + " IS NOT NULL OR m."
              + engine.quote("before_tx_version")
              + " IS NOT NULL) FROM (SELECT "
              + n.readColumns(x)
              + " FROM "
              + n.data
              + " "
              + x
              + (span == null ? "" : " WHERE " + ast.source().substring(span[0], span[1]))
              + ") d LEFT JOIN "
              + n.meta
              + " m ON "
              + n.keyJoin("m", "d");
      List<Integer> binds = new ArrayList<>(text.parameterSources());
      binds.addAll(inWhere);
      fastRcStep = new Writes.Step(lockSql + "; " + versions, binds);
    }
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
                : null,
            engine == Engine.POSTGRESQL && snapshot(),
            plainSql == null || all == null ? null : new Writes.Step(plainSql, all),
            fastRcStep,
            gatedSql == null || all == null ? null : new Writes.Step(gatedSql, all),
            gatedSql == null || plainSql == null || all == null
                ? null
                : new Writes.Step(plainSql, all));
    return new Route(
        sql,
        sql,
        false,
        false,
        false,
        SqlAst.Kind.SELECT,
        routedLevel(),
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
        List.of(),
        null,
        null,
        null,
        List.of());
  }

  /** Whether every one of {@code columns} is a key column of {@code t}. */
  private boolean keysOnly(TableInfo t, List<String> columns) {
    Set<String> keys = new HashSet<>();
    for (String k : t.keyColumns()) {
      keys.add(engine.columnKey(k));
    }
    for (String c : columns) {
      if (!keys.contains(engine.columnKey(c))) {
        return false;
      }
    }
    return true;
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
    if (ast.kind() == SqlAst.Kind.DDL || ast.kind() == SqlAst.Kind.TRUNCATE) {
      // run raw, even in passthrough, it would leave ScalarDB's metadata and the setup behind
      throw Errors.unsupported(SCHEMA_CHANGE + tables);
    }
    if (ast.kind() != SqlAst.Kind.SELECT) {
      throw Errors.unsupported(OTHER_STATEMENT + tables);
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
    } else if (engine == Engine.SQLSERVER) {
      // a table with rows in flight seeks its in-flight index row by row (SplitRead's SEEK); with
      // none, the plan is SQL Server's own, which a large read needs
      List<SqlAst.TableRef> refs = new ArrayList<>(split.places().keySet());
      for (int i = 0; i < refs.size(); i++) {
        TableInfo t = scalarDb.get(refs.get(i));
        if (t == null) {
          continue;
        }
        boolean seek;
        if (forcedPerRow != null) {
          seek = forcedPerRow.contains(i);
        } else {
          long[] count = inflightCount(vendor, t);
          long due = count[1] + COUNT_TTL_NANOS;
          recheckAt = recheckAt == 0 || due - recheckAt < 0 ? due : recheckAt;
          seek = count[0] > 0;
        }
        if (seek) {
          perRow.add(refs.get(i));
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
    // the versions cost lookups per row, read only where they are recorded; on PostgreSQL each
    // copy carries them as columns of its own instead (todo 329)
    boolean versioned = !versionless();
    List<SqlAst.TableRef> anchored = (anchoring && versioned) || states ? anchorable : List.of();
    Set<SqlAst.TableRef> own = Collections.newSetFromMap(new IdentityHashMap<>());
    if ((engine == Engine.POSTGRESQL || engine == Engine.ORACLE) && versioned) {
      own.addAll(anchored); // on Oracle, design 4.2 "The read side on Oracle" (todo 334)
    }
    IdentityHashMap<SqlAst.TableRef, Writes.Names> anchorNames = new IdentityHashMap<>();
    List<String> anchorTables = new ArrayList<>();
    List<Integer> keys = new ArrayList<>();
    List<String> anchorChecks = new ArrayList<>();
    for (SqlAst.TableRef r : anchored) {
      Writes.Names n = new Writes.Names(engine, Objects.requireNonNull(scalarDb.get(r)));
      anchorNames.put(r, n);
      anchorTables.add(n.table);
      keys.add(versioned ? n.keyColumns.size() : 0); // without versions, no key either
      anchorChecks.add(Writes.inFlightCheck(n));
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
            (r, piece) ->
                versions(
                    r,
                    Objects.requireNonNull(anchorNames.get(r)),
                    piece,
                    states,
                    versioned,
                    !own.isEmpty()),
            false,
            states,
            null,
            own,
            false);
    if (text == null && !own.isEmpty()) {
      // a * the copies cannot spell out: the versions looked up per row
      text =
          SplitRead.emit(
              engine,
              ast,
              split,
              scalarDb,
              views,
              perRow,
              anchored,
              (r, piece) ->
                  versions(r, Objects.requireNonNull(anchorNames.get(r)), piece, states, versioned),
              false,
              states,
              null);
    }
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
        // the mode is kept without versions too: it is what lets the statement be prepared again
        // for another level, whose text may read them (Proxies.preparedWith)
        r =
            r.anchored(
                anchored.isEmpty()
                    ? null
                    : new Anchored(anchorTables, keys, states, versioned, anchorChecks),
                anchoring);
      }
      if (snapshot) {
        // with states, run only for a scrollable result, whose moves pass over rows unchecked
        r = r.checked(checks(scalarDb), fallback(ast, scalarDb));
      }
      r =
          engine == Engine.POSTGRESQL && plainPlanIsParallel(vendor, sql, ast, scalarDb)
              ? r.with(r.sql, r.parameterSources, r.perRow, false, true)
              : r;
      return withPlain(r, ast, scalarDb);
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
    return snapshot
        ? withPlain(r.checked(checks(scalarDb), fallback(ast, scalarDb)), ast, scalarDb)
        : r;
  }

  /**
   * With {@code scalardbnative.predicateChecks}, for a read of one ScalarDB table alone with a pure
   * predicate on its own columns, the check for rows in flight that predicate matches by either
   * image (design 4.2): a row in flight neither image brings into the statement's rows changes
   * nothing, whichever is true. Null otherwise.
   */
  private Writes.@Nullable Step fallback(
      SqlAst ast, IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb) {
    if (!predicateChecks) {
      return null;
    }
    if (scalarDb.size() > 1) {
      return pairs(ast, scalarDb);
    }
    if (scalarDb.size() != 1) {
      return null;
    }
    StatementShape shape = StatementShape.of(ast).orElse(null);
    if (shape == null
        || shape.kind() != StatementShape.Kind.SELECT
        || shape.where() == null
        || !shape.wherePure()) {
      return null;
    }
    SqlAst.TableRef ref = shape.table();
    TableInfo t = scalarDb.get(ref);
    if (t == null) {
      return null;
    }
    // the table's own columns only: a pseudo-column, as Oracle's SYSDATE, reads as one
    Set<String> columns = new HashSet<>();
    for (String c : t.dataColumns()) {
      columns.add(engine.columnKey(c));
    }
    for (String c : shape.whereColumns()) {
      if (!columns.contains(engine.columnKey(c))) {
        return null;
      }
    }
    for (SqlAst.ColumnRef c : ast.columnRefs()) {
      SqlAst.TableRef q = c.qualifier();
      if (q != null && q.schema() != null) {
        return null; // the check names the table by its alias alone
      }
    }
    int[] span = ast.span(Objects.requireNonNull(shape.where()));
    List<Integer> inWhere = span == null ? null : ast.placeholdersIn(span[0], span[1]);
    return span == null || inWhere == null ? null : inFlightStep(t, ref, ast, span, inWhere);
  }

  /**
   * The most ScalarDB tables a join's check takes: 2^n - 1 + n statements for n tables, 10 for
   * three.
   */
  private static final int PAIRS_TABLES = 3;

  /**
   * Stage 2 of the predicate's check (design 4.2), the in-flight pairs: for a single SELECT whose
   * ScalarDB tables, two or three and no other table, are joined by inner joins in its FROM clause
   * or semi-joined by an IN or EXISTS conjunct of its WHERE clause, with pure ON and WHERE clauses,
   * the statement's FROM and WHERE clauses over every combination of pieces with a row in flight:
   * the before images on a non-empty subset of the tables and the data table on the rest; and the
   * after images on one table and the data table on the rest. The data table holds the after images
   * too, so these cover every combination; a row in flight that no combination brings into the
   * statement's rows changes nothing, whichever image is true. An outer join or an anti-join, whose
   * rows a row's absence changes, keeps the table-wide check. Not on MySQL, which plans the
   * statements under the row limit as a full scan. On Oracle and SQL Server the metadata tables are
   * read through the in-flight index: Oracle 19c otherwise drives a pair from the other table's
   * data table, SQL Server, planning from statistics taken with few rows in flight, scans. Null
   * otherwise.
   */
  private Writes.@Nullable Step pairs(
      SqlAst ast, IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb) {
    if (engine == Engine.MYSQL
        || scalarDb.size() > PAIRS_TABLES
        || ast.tableRefs().size() != scalarDb.size()
        || !ast.singleSelect()) {
      return null; // a WITH clause stands before the FROM clause the check is cut from
    }
    PlainSelect select = (PlainSelect) ast.statement();
    Expression where = select.getWhere();
    if (where == null || !StatementShape.pure(where, true)) {
      return null;
    }
    if (select.getJoins() != null) {
      for (Join j : select.getJoins()) {
        // an outer join changes its rows through a row's absence, which no inner pair shows
        if (j.isLeft()
            || j.isRight()
            || j.isFull()
            || j.isOuter()
            || j.isNatural()
            || j.isApply()
            || (j.getUsingColumns() != null && !j.getUsingColumns().isEmpty())) {
          return null;
        }
        for (Expression on : j.getOnExpressions()) {
          if (!StatementShape.pure(on, false)) {
            return null;
          }
        }
      }
    }
    SqlAst.Split split =
        ast.split(
            r -> {
              TableInfo t = scalarDb.get(r);
              return t == null ? null : t.keyColumns();
            });
    Set<String> columns = new HashSet<>();
    for (Map.Entry<SqlAst.TableRef, TableInfo> e : scalarDb.entrySet()) {
      SqlAst.Place place = split.places().get(e.getKey());
      if ((place != SqlAst.Place.INNER && place != SqlAst.Place.SEMI_JOINED)
          || e.getKey().hasHintOrLink()) {
        return null;
      }
      for (String c : e.getValue().dataColumns()) {
        columns.add(engine.columnKey(c));
      }
    }
    // the FROM and WHERE clauses alone: what the statement groups, limits or returns is made of
    // their rows, and a HAVING or OFFSET over the pieces would hide one
    int[] whereSpan = ast.span(where);
    int fromAt = 0;
    for (SelectItem<?> item : select.getSelectItems()) {
      if (item.getExpression() instanceof AllColumns) {
        continue; // a star, a table's star included, has no span and holds no FROM
      }
      int[] s = ast.span(item.getExpression());
      fromAt = s == null ? Integer.MAX_VALUE : Math.max(fromAt, s[1]);
    }
    if (whereSpan == null || fromAt == Integer.MAX_VALUE) {
      return null;
    }
    int from = ast.startOfFirstAfter("FROM", fromAt);
    List<Integer> inClauses = from < 0 ? null : ast.placeholdersIn(from, whereSpan[1]);
    SqlAst cut =
        inClauses == null
            ? null
            : ast.reparse("SELECT 1 " + ast.source().substring(from, whereSpan[1]));
    if (cut == null || cut.tableRefs().size() != scalarDb.size()) {
      return null;
    }
    // the cut's references in the order of the text, as the statement's are
    List<SqlAst.TableRef> refs = inTextOrder(ast, new ArrayList<>(scalarDb.keySet()));
    List<SqlAst.TableRef> cutRefs = inTextOrder(cut, new ArrayList<>(cut.tableRefs()));
    if (refs == null || cutRefs == null) {
      return null;
    }
    // the tables' own columns only, a pseudo-column reads as one; a schema in a qualifier would
    // name the data table, which the pieces replace under its alias; a piece is a derived table,
    // which needs an alias, the reference's own or its name where a qualifier names it
    Set<String> qualifying = new HashSet<>();
    for (SqlAst.ColumnRef c : cut.columnRefs()) {
      SqlAst.TableRef q = c.qualifier();
      if (c.isAllColumnsOfTable()
          || (q != null && q.schema() != null)
          || !columns.contains(engine.columnKey(engine.resolveIdentifier(c.name())))) {
        return null;
      }
      if (q != null) {
        qualifying.add(engine.nameKey(engine.resolveIdentifier(q.name())));
      }
    }
    for (SqlAst.TableRef r : cutRefs) {
      if (!r.hasAlias()
          && !qualifying.contains(engine.nameKey(engine.resolveIdentifier(r.name())))) {
        return null;
      }
    }
    int n = refs.size();
    List<String> arms = new ArrayList<>();
    List<Integer> binds = new ArrayList<>();
    // the before images on a non-empty subset, bit i for table i, then the after images on one
    List<char[]> assignments = new ArrayList<>();
    for (int mask = 1; mask < (1 << n); mask++) {
      char[] a = new char[n];
      for (int i = 0; i < n; i++) {
        a[i] = (mask & (1 << i)) != 0 ? 'B' : 'D';
      }
      assignments.add(a);
    }
    for (int i = 0; i < n; i++) {
      char[] a = new char[n];
      Arrays.fill(a, 'D');
      a[i] = 'A';
      assignments.add(a);
    }
    for (char[] a : assignments) {
      IdentityHashMap<SqlAst.TableRef, String> pieces = new IdentityHashMap<>();
      for (int i = 0; i < n; i++) {
        TableInfo t = Objects.requireNonNull(scalarDb.get(refs.get(i)));
        pieces.put(
            cutRefs.get(i),
            a[i] == 'B'
                ? SplitRead.inFlight(engine, t, t.dataColumns(), true)
                : a[i] == 'A'
                    ? SplitRead.afterImages(engine, t, t.dataColumns(), true)
                    : engine.quote(t.namespace()) + "." + engine.quote(t.dataTable()));
      }
      String arm = cut.joinTokens(pieces::get);
      if (arm == null) {
        return null;
      }
      arms.add(arm);
      binds.addAll(inClauses);
    }
    return new Writes.Step(Writes.anyRow(engine, String.join(" UNION ALL ", arms)), binds);
  }

  /** References sorted by where their names stand, or null when one cannot be placed. */
  private static @Nullable List<SqlAst.TableRef> inTextOrder(
      SqlAst ast, List<SqlAst.TableRef> refs) {
    Map<SqlAst.TableRef, Integer> at = new IdentityHashMap<>();
    for (SqlAst.TableRef r : refs) {
      int[] s = ast.nameSpan(r);
      if (s == null) {
        return null;
      }
      at.put(r, s[0]);
    }
    refs.sort((x, y) -> Integer.compare(at.get(x), at.get(y)));
    return refs;
  }

  /** Per ScalarDB table of a read, the check for a row in flight anywhere in it (design 4.2). */
  private List<String> checks(IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb) {
    return checks(scalarDb, false);
  }

  /**
   * Each ScalarDB table's check once, in the order of their names, which locks follow; when {@code
   * locked}, the in-flight index read under a shared lock, a gap or range lock that holds off every
   * prepare of the table.
   */
  private List<String> checks(
      IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb, boolean locked) {
    Set<String> out = new TreeSet<>();
    for (TableInfo t : scalarDb.values()) {
      boolean hint = locked && engine == Engine.SQLSERVER;
      out.add(
          locked
              ? Writes.anyRow(
                      engine,
                      "SELECT 1 FROM "
                          + SplitRead.meta(engine, t)
                          + (hint
                              ? " WITH (HOLDLOCK, INDEX("
                                  + engine.quote(t.inflightIndex(engine))
                                  + "))"
                              : "")
                          + " WHERE "
                          + engine.quote("tx_state")
                          + " IN (1, 2)")
                  // a database's forced parameterization would make the literal a parameter,
                  // which the filtered index then does not match (Msg 8622); RECOMPILE keeps it
                  // (todo 271)
                  + (hint ? " OPTION (RECOMPILE)" : " LOCK IN SHARE MODE")
              : inFlightCheck(t));
    }
    return List.copyOf(out);
  }

  /**
   * The coarse check of a table for rows in flight, one probe of the in-flight index of its
   * metadata table: a row means the transaction's snapshot holds a row in flight of the table. Its
   * text is the key under which a transaction remembers that it found none (design 4.2, todo 329).
   */
  String inFlightCheck(TableInfo t) {
    return Writes.inFlightCheck(new Writes.Names(engine, t));
  }

  /**
   * Whether the open transaction found, in its snapshot, no row in flight of the table whose coarse
   * check {@code check} is (todo 329): every statement of the transaction reads that snapshot at
   * SNAPSHOT and SERIALIZABLE, so none can meet one, and the table's reads, locking reads and
   * writes run as the vendor's, the writes with their stamp. Only in a transaction: the callers
   * check that, as the snapshot of an autocommit statement is its own.
   */
  boolean passed(String check) {
    lock.lock();
    try {
      return passed.contains(check);
    } finally {
      lock.unlock();
    }
  }

  /** Records that the open transaction found no row in flight by {@code check}. */
  void pass(String check) {
    lock.lock();
    try {
      passed.add(check);
    } finally {
      lock.unlock();
    }
  }

  /**
   * A read at SERIALIZABLE on MySQL, MariaDB or SQL Server that is not one table row by row: each
   * ScalarDB table's in-flight index read under a shared lock, whose gap or range lock holds off
   * every ScalarDB prepare of the table until commit, then the statement on the data tables, which
   * then hold the committed rows only, under {@code LOCK IN SHARE MODE}, or on SQL Server with
   * {@code WITH (HOLDLOCK)} on every table (design 4.2, the coarse path). A table the statement
   * reaches in a subquery or a derived table would be read unlocked, from the snapshot: refused.
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
    return r.checked(checks(scalarDb, true), null);
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
          + " locking clause whose OF list leaves a table out, a table with SQL Server's INDEX hint"
          + " or an isolation hint that holds no lock (NOLOCK, READPAST, READCOMMITTED, SNAPSHOT), or a call of a SQL Server function of your own, whose reads no lock"
          + " hint reaches, cannot be given; use -- or /* */ comments, read information_schema on"
          + " its own, name every table in OF, and leave those hints and functions out";

  private static final String SCHEMA_CHANGE =
      "DDL and TRUNCATE on a ScalarDB table are not supported, in passthrough mode neither; change"
          + " or truncate it through ScalarDB's admin, then run scalardb-native-tools setup again"
          + " and refresh the catalog";

  private static final String OTHER_STATEMENT =
      "a ScalarDB table is supported in SELECT and in INSERT, UPDATE and DELETE of that one table"
          + " only, not in this statement";

  private static final String READ_ONLY =
      "a read-only transaction at SERIALIZABLE runs reads only: its reads take no locks (design 5);"
          + " set the connection read-write for this statement";

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
   * The level the vendor session runs at for the driver's {@code level}: {@link #vendorLevel}, but
   * READ COMMITTED on Oracle in autocommit, where each statement is one: at SERIALIZABLE ojdbc
   * commits after a write only, so a read in autocommit would open a transaction that later reads
   * go on in, at its snapshot, and SET TRANSACTION fails in (ORA-01453); one statement's snapshot
   * is READ COMMITTED's own.
   *
   * @param level the driver's level
   * @param autocommit whether the connection is in autocommit
   * @return the JDBC level to set on the vendor connection
   */
  int sessionLevel(int level, boolean autocommit) {
    return engine == Engine.ORACLE && autocommit
        ? Connection.TRANSACTION_READ_COMMITTED
        : vendorLevel(level);
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
      return isolation == Connection.TRANSACTION_SERIALIZABLE && locksAtSerializable() && !readOnly;
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

  /** Binds the application's value of one of its placeholders to a driver statement. */
  @FunctionalInterface
  interface Binder {
    void bind(PreparedStatement ps, int position, int placeholder) throws SQLException;
  }

  /**
   * Runs a read's checks for rows in flight, those its transaction has not passed yet: in one
   * snapshot a table without rows in flight stays so (design 4.2). A table with one is cleared, for
   * this read only, by the route's {@link Route#fallback} finding none its predicate matches.
   *
   * @param vendor the vendor connection, in a transaction
   * @param route the read
   * @param timeout the statement's query timeout in seconds, 0 for none
   * @param binder the application's values for the fallback, or null when they cannot be read twice
   *     (a stream): no fallback then
   * @throws SQLException {@code 40001} when a table has a row in flight
   */
  @SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
      justification = "the driver's own statements, built from the catalog's quoted names")
  void check(Connection vendor, Route route, int timeout, @Nullable Binder binder)
      throws SQLException {
    // the checks still to run, in one round trip on PostgreSQL and Oracle as one statement (todo
    // 329, todo 334)
    List<String> pending = new ArrayList<>();
    lock.lock();
    try {
      for (String check : route.checks) {
        if (!passed.contains(check) && !found.contains(check)) {
          pending.add(check);
        }
      }
    } finally {
      lock.unlock();
    }
    Map<String, Boolean> results = new HashMap<>();
    boolean oracle = engine == Engine.ORACLE;
    if ((engine == Engine.POSTGRESQL || oracle) && pending.size() > 1) {
      List<String> columns = new ArrayList<>();
      for (String check : pending) {
        columns.add(
            oracle
                ? "CASE WHEN EXISTS (" + check + ") THEN 1 ELSE 0 END"
                : "EXISTS (" + check + ")");
      }
      try (PreparedStatement st =
          vendor.prepareStatement(
              "SELECT " + String.join(", ", columns) + (oracle ? " FROM dual" : ""))) {
        st.setQueryTimeout(timeout);
        try (ResultSet rs = st.executeQuery()) {
          rs.next();
          for (int i = 0; i < pending.size(); i++) {
            results.put(pending.get(i), rs.getBoolean(i + 1));
          }
        }
      }
    }
    // the fallback covers every table of the read: once it found none, the rest need no check
    boolean cleared = false;
    for (String check : route.checks) {
      boolean inFlight;
      lock.lock();
      try {
        if (passed.contains(check)) {
          continue;
        }
        inFlight = found.contains(check);
      } finally {
        lock.unlock();
      }
      if (!inFlight && results.containsKey(check)) {
        inFlight = Boolean.TRUE.equals(results.get(check));
      } else if (!inFlight) {
        // prepared on PostgreSQL, so that pgjdbc's statement cache keeps its plan (todo 329); a
        // prepare and close a call costs other drivers round trips
        try (Statement st =
            engine == Engine.POSTGRESQL
                ? vendor.prepareStatement(check)
                : vendor.createStatement()) {
          st.setQueryTimeout(timeout);
          try (ResultSet rs =
              st instanceof PreparedStatement
                  ? ((PreparedStatement) st).executeQuery()
                  : st.executeQuery(check)) {
            inFlight = rs.next();
          }
        }
      }
      if (inFlight && cleared) {
        continue;
      }
      if (inFlight) {
        Writes.Step fallback = route.fallback;
        if (fallback != null) {
          lock.lock();
          try {
            found.add(check);
          } finally {
            lock.unlock();
          }
        }
        if (fallback == null || binder == null) {
          throw new Writes.Aborted(
              "a table the read reaches has a row a ScalarDB transaction is writing");
        }
        try (PreparedStatement ps = vendor.prepareStatement(fallback.sql)) {
          ps.setQueryTimeout(timeout);
          int i = 1;
          for (int b : fallback.binds) {
            binder.bind(ps, i++, b);
          }
          try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
              throw new Writes.Aborted("the read matches a row a ScalarDB transaction is writing");
            }
          }
        }
        cleared = true;
        continue; // passed for this predicate only, not recorded
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
        routedLevel(),
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
        List.of(),
        null,
        null,
        null,
        List.of());
  }

  /**
   * The statement as written over the data tables, which a transaction at SNAPSHOT or SERIALIZABLE
   * on PostgreSQL or Oracle runs once it found every ScalarDB table of the read clear (design 4.2,
   * todos 329 and 334): no row of those tables is in flight in its snapshot, so the rows the data
   * tables show are the committed ones, as the rewritten read would return them. Null where it
   * cannot be: an inline table read whole, whose metadata columns would show, or a text the names
   * cannot be replaced in.
   */
  private Route withPlain(
      Route r, SqlAst ast, IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb) {
    if ((engine != Engine.POSTGRESQL && engine != Engine.ORACLE)
        || !snapshot()
        || ast.hasLockingOrIntoClause()) {
      return r;
    }
    Set<String> cleared = new TreeSet<>();
    for (Map.Entry<SqlAst.TableRef, TableInfo> e : scalarDb.entrySet()) {
      TableInfo t = e.getValue();
      if (t.layout() == Layout.INLINE && ast.columnsReadFrom(e.getKey()) == null) {
        return r;
      }
      cleared.add(inFlightCheck(t));
    }
    String plain =
        ast.joinTokens(
            ref -> {
              TableInfo t = scalarDb.get(ref);
              return t == null
                  ? null
                  : engine.quote(t.namespace()) + "." + engine.quote(t.dataTable());
            });
    return plain == null ? r : r.plain(plain, List.copyOf(cleared));
  }

  /** Whether the open transaction found every table of {@code checks} clear (todo 329). */
  boolean allPassed(List<String> checks) {
    lock.lock();
    try {
      return !checks.isEmpty() && passed.containsAll(checks);
    } finally {
      lock.unlock();
    }
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
   * The table's rows in flight on MySQL and MariaDB, counted through the in-flight index up to
   * {@link #PER_ROW_ABOVE_IN_RANGES} + 1, and when they were counted; read again once a count is
   * older than {@link #COUNT_TTL_NANOS}. The count only chooses a plan, so one that is a second old
   * costs time, never rows.
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
                engine == Engine.SQLSERVER
                    ? "SELECT COUNT(*) FROM (SELECT TOP "
                        + (PER_ROW_ABOVE_IN_RANGES + 1)
                        + " 1 AS one FROM "
                        + meta
                        + " WHERE "
                        + engine.quote("tx_state")
                        + " IN (1, 2)) x"
                    : "SELECT COUNT(*) FROM (SELECT 1 AS one FROM "
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
      if (sessionWorkers == 0) {
        workersOff = true; // the session's own value is the driver's: nothing to switch, ever
        return;
      }
      if (sessionWorkers > 0) {
        // the session's value known and the setting not: set the one needed, one round trip
        setWorkers(vendor, off ? 0 : sessionWorkers);
        workersOff = off;
        switched(vendor);
        return;
      }
      long shown;
      try (Statement st = vendor.createStatement();
          ResultSet rs = st.executeQuery("SHOW max_parallel_workers_per_gather")) {
        rs.next();
        shown = Long.parseLong(rs.getString(1));
      }
      sessionWorkers = shown;
      // a 0 with a known session value is the driver's own: only a statement that sets it, which
      // forgets the value, or a transaction's end after one, changes the session's
      workersOff = shown == 0;
      if (!workersSwitchedInTransaction) {
        workersOffAtStart = workersOff; // as the transaction began: the driver has not switched
      }
    }
    if (off && !workersOff) {
      setWorkers(vendor, 0);
      workersOff = true;
      switched(vendor);
    } else if (!off && workersOff && sessionWorkers > 0) {
      setWorkers(vendor, sessionWorkers);
      workersOff = false;
      switched(vendor);
    }
  }

  /**
   * After the driver set the workers: in autocommit the session keeps the value; in a transaction a
   * rollback undoes it, to the value the transaction began with, or to a savepoint's, unknown.
   */
  private void switched(Connection vendor) {
    boolean auto;
    try {
      auto = vendor.getAutoCommit();
    } catch (SQLException e) {
      auto = false; // unknown: taken as in a transaction, whose rollback restores the start's
    }
    if (auto) {
      workersOffAtStart = workersOff;
    } else {
      workersSwitchedInTransaction = true;
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

  /**
   * After a rollback, which undoes a SET made in the transaction: the whole transaction's leaves
   * the setting as the transaction began, known without a read; one to a savepoint leaves the
   * driver's own SET, if it made one, undone or not, so the setting is read again.
   */
  void rolledBack(boolean toSavepoint) {
    lock.lock();
    try {
      if (!toSavepoint) {
        workersOff = workersOffAtStart;
        workersSwitchedInTransaction = false;
      } else if (workersSwitchedInTransaction) {
        workersOff = null;
      }
    } finally {
      lock.unlock();
    }
  }

  /** At commit or rollback: a SET of the workers in the transaction may have been undone. */
  void transactionEnded() {
    lock.lock();
    try {
      passed.clear();
      found.clear();
      if (workersSetInTransaction) {
        sessionWorkers = -1;
        workersOff = null;
        workersSetInTransaction = false;
      }
      workersOffAtStart = workersOff; // the next transaction begins with the setting as it is
      workersSwitchedInTransaction = false;
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
              && prepared.isolation == routedLevel()
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
        rolledBack(false);
        session.transactionEnded();
        transactionEnded();
      } else if (route.kind == SqlAst.Kind.ROLLBACK_TO_SAVEPOINT) {
        rolledBack(true);
        session.rolledBackToSavepoint();
      }
      if (route.changesSession) {
        if (engine == Engine.POSTGRESQL && setsWorkers(route.source)) {
          sessionWorkers = -1;
          workersOff = null;
          workersOffAtStart = null;
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
          if (engine == Engine.ORACLE) {
            // ojdbc reports the level it last set, and Oracle shows no session's own: the level
            // set in SQL is the connection's again, which the driver's reads are written for
            try (Statement s = vendor.createStatement()) {
              // passthrough runs in autocommit, whose level is READ COMMITTED
              s.execute("ALTER SESSION SET ISOLATION_LEVEL = READ COMMITTED");
            }
            return;
          }
          int level =
              engine == Engine.SQLSERVER
                  ? sqlServerLevel(vendor)
                  : driverLevel(vendor.getTransactionIsolation());
          if (engine == Engine.SQLSERVER) {
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
