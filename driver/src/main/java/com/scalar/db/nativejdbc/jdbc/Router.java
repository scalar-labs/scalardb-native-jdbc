package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.Outcome;
import com.scalar.db.nativejdbc.sql.SqlAst;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides, for one statement on one connection, what the vendor driver receives (design section
 * 4.1). A statement on plain tables only is forwarded exactly as written; one the parser cannot
 * interpret is refused with the reason; one that names a ScalarDB table is refused until the read
 * and write paths exist.
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
    final long sessionGeneration;
    final long catalogGeneration;
    final Instant trustedUntil;

    private Route(
        String source,
        String sql,
        boolean changesSession,
        boolean changesNames,
        boolean passthrough,
        long sessionGeneration,
        long catalogGeneration,
        Instant trustedUntil) {
      this.trustedUntil = trustedUntil;
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
      return route(sql, true, true, true);
    }
    Outcome<SqlAst> parsed =
        SqlAst.analyze(sql, engine, session.doubleQuotesQuoteIdentifiers(vendor));
    if (!parsed.isPresent()) {
      throw Errors.unsupported(parsed.reason());
    }
    SqlAst ast = parsed.get();
    SqlAst.Kind kind = ast.kind();
    List<String> schemas = session.schemas(vendor);
    List<String> scalarDb = new ArrayList<>();
    for (SqlAst.TableRef ref : ast.tableRefs()) {
      TableInfo table = catalog.lookup(schemas, ref.schema(), ref.name());
      if (table.isScalarDb()) {
        scalarDb.add(table.namespace() + "." + table.table());
      }
    }
    if (!scalarDb.isEmpty()) {
      throw Errors.unsupported(
          "statements on ScalarDB tables are not supported yet ("
              + String.join(", ", scalarDb)
              + ")");
    }
    boolean changesSession = kind != SqlAst.Kind.SELECT && kind != SqlAst.Kind.DML;
    boolean changesNames = kind == SqlAst.Kind.DDL || kind == SqlAst.Kind.TRUNCATE;
    return route(sql, changesSession, changesNames, false);
  }

  private Route route(
      String sql, boolean changesSession, boolean changesNames, boolean passthrough) {
    return new Route(
        sql,
        sql,
        changesSession,
        changesNames,
        passthrough,
        session.generation(),
        catalog.generation(),
        catalog.trustedUntil());
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
