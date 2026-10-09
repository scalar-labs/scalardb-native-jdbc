package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.Outcome;
import com.scalar.db.nativejdbc.sql.SqlAst;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.statement.SetStatement;

/**
 * Decides, for one statement on one connection, what the vendor driver receives (design section
 * 4.1). A statement on plain tables only is forwarded exactly as written; one the parser cannot
 * interpret is refused with the reason; one that names a ScalarDB table is refused until the read
 * and write paths exist.
 */
final class Router {

  /** What to do with a statement, and what running it does to the session and the catalog. */
  static final class Route {
    final String sql;
    final boolean changesSession;
    final boolean changesNames;

    Route(String sql, boolean changesSession, boolean changesNames) {
      this.sql = sql;
      this.changesSession = changesSession;
      this.changesNames = changesNames;
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
   * @throws SQLException 0A000 for a statement the driver does not handle, 42000 for a name a
   *     statement must not use, or a catalog failure
   */
  Route route(Connection vendor, String sql) throws SQLException {
    if (passthrough) {
      if (!vendor.getAutoCommit()) {
        throw Errors.unsupported("the passthrough setting applies in autocommit mode only");
      }
      // anything may run here, so assume it changed the session and the names
      return new Route(sql, true, true);
    }
    Outcome<SqlAst> parsed =
        SqlAst.analyze(sql, engine, session.doubleQuotesQuoteIdentifiers(vendor));
    if (!parsed.isPresent()) {
      throw Errors.unsupported(parsed.reason());
    }
    SqlAst ast = parsed.get();
    SqlAst.Kind kind = ast.kind();
    if (ast.statement() instanceof SetStatement
        && setsTransactionMode((SetStatement) ast.statement())) {
      throw Errors.unsupported(
          "the transaction mode is set through Connection.setAutoCommit and"
              + " Connection.setTransactionIsolation, not in SQL");
    }
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
    return new Route(sql, changesSession, changesNames);
  }

  /** After a routed statement ran: re-read the session, forget resolved names, as it requires. */
  void ran(Connection vendor, Route route) throws SQLException {
    if (route.changesSession) {
      session.changed(vendor);
    }
    if (route.changesNames) {
      catalog.invalidateResolutions();
    }
  }

  private static boolean setsTransactionMode(SetStatement set) {
    for (int i = 0; i < set.getKeyValuePairs().size(); i++) {
      Object name = set.getName(i);
      String n = name == null ? "" : name.toString().strip().toLowerCase(Locale.ROOT);
      if (n.equals("autocommit") || n.equals("@@autocommit") || n.startsWith("transaction")) {
        return true;
      }
    }
    return false;
  }
}
