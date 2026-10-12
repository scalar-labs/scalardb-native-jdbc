package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.Outcome;
import com.scalar.db.nativejdbc.sql.SqlAst;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
        int parameters) {
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

  private final Engine engine;
  private final Catalog catalog;
  private final Session session;
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
      return read(sql, ast, scalarDb);
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
  private Route read(String sql, SqlAst ast, IdentityHashMap<SqlAst.TableRef, TableInfo> scalarDb)
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
    SqlAst.SplitText split = SplitRead.emit(engine, ast, scalarDb, views);
    if (split != null) {
      return route(
          sql, split.sql(), false, false, false, split.parameterSources(), ast.parameterCount());
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
        parameters);
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
    return unchanged ? prepared : route(vendor, prepared.source);
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
