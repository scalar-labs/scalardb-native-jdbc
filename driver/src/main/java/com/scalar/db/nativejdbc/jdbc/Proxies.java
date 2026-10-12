package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.NativeJdbcDriver;
import com.scalar.db.nativejdbc.ScalarDbTables;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The proxies over the vendor driver's objects. Each forwards every method it does not intercept;
 * the intercepted ones are the ones that take a statement's text, that hand out another JDBC object
 * (so the application never holds a vendor object it did not ask for with {@code unwrap}), and the
 * connection settings the design governs.
 */
public final class Proxies {

  private static final String PASSTHROUGH = NativeJdbcDriver.PASSTHROUGH;

  private Proxies() {}

  /**
   * Wraps a vendor connection that has just been opened.
   *
   * @param vendor the vendor connection
   * @param engine its engine
   * @param catalog the catalog for its database and identity
   * @return the connection the application uses
   * @throws SQLException if the session cannot be read or the isolation level set
   */
  public static Connection connection(Connection vendor, Engine engine, Catalog catalog)
      throws SQLException {
    // vendor defaults differ (Connector/J and MariaDB start at REPEATABLE READ), so every engine
    // starts at READ COMMITTED, the driver's default level (design section 1)
    vendor.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Session session = new Session(engine);
    if (engine == Engine.SQLSERVER) {
      session.requireReadCommittedSnapshot(vendor);
    }
    session.schemas(vendor); // read now, outside any transaction
    // ScalarDbTables, reached with unwrap, is the driver's own interface (design section 1)
    return (Connection)
        Proxy.newProxyInstance(
            Proxies.class.getClassLoader(),
            new Class<?>[] {Connection.class, ScalarDbTables.class},
            new ConnectionHandler(vendor, new Router(engine, catalog, session), session));
  }

  private static Object proxy(Class<?> type, InvocationHandler h) {
    return Proxy.newProxyInstance(Proxies.class.getClassLoader(), new Class<?>[] {type}, h);
  }

  /** A vendor method that creates a JDBC object never returns null. */
  private static Object created(Object target, Method method, @Nullable Object[] args)
      throws Throwable {
    return Objects.requireNonNull(
        forward(target, method, args), () -> "the vendor driver's " + method.getName());
  }

  private static @Nullable Object forward(Object target, Method method, @Nullable Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /** The methods of Object and java.sql.Wrapper, the same for every proxy; the rest is handle's. */
  private abstract static class Handler implements InvocationHandler {
    final Object target;

    Handler(Object target) {
      this.target = target;
    }

    // a proxy equals only itself
    @SuppressWarnings("ReferenceEquality")
    @Override
    public final @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
        throws Throwable {
      switch (method.getName()) {
        case "equals":
          return args != null && args.length == 1 && proxy == args[0];
        case "hashCode":
          return System.identityHashCode(proxy);
        case "toString":
          return "scalardb-native:" + target;
        case "unwrap":
          {
            Class<?> iface = (Class<?>) args[0];
            return iface.isInstance(proxy) ? proxy : forward(target, method, args);
          }
        case "isWrapperFor":
          {
            Class<?> iface = (Class<?>) args[0];
            return iface.isInstance(proxy) || Boolean.TRUE.equals(forward(target, method, args));
          }
        default:
          return handle(proxy, method, args);
      }
    }

    abstract @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args)
        throws Throwable;
  }

  private static final class ConnectionHandler extends Handler {
    final Connection vendor;
    final Router router;
    final Session session;

    ConnectionHandler(Connection vendor, Router router, Session session) {
      super(vendor);
      this.vendor = vendor;
      this.router = router;
      this.session = session;
    }

    Router.Route route(String sql) throws SQLException {
      return router.route(vendor, sql);
    }

    Router.Route current(Router.Route prepared) throws SQLException {
      return router.current(vendor, prepared);
    }

    void ran(Router.Route route) {
      router.ran(vendor, route);
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      Connection me = (Connection) proxy;
      if (method.getDeclaringClass() == ScalarDbTables.class) {
        return router.kind(vendor, (String) args[0], (String) args[1]);
      }
      switch (method.getName()) {
        case "createStatement":
          return proxy(
              method.getReturnType(),
              new StatementHandler((Statement) created(vendor, method, args), me, this, null));
        case "prepareStatement":
        case "prepareCall":
          {
            Router.Route route = route((String) args[0]);
            Object[] routed = args.clone();
            routed[0] = route.sql;
            return proxy(
                method.getReturnType(),
                new StatementHandler((Statement) created(vendor, method, routed), me, this, route));
          }
        case "getMetaData":
          return proxy(
              DatabaseMetaData.class, new ChildHandler(created(vendor, method, args), me, null));
        case "setTransactionIsolation":
          if ((Integer) args[0] == Connection.TRANSACTION_READ_UNCOMMITTED) {
            throw Errors.unsupported(
                "READ UNCOMMITTED is not supported: it would read ScalarDB's uncommitted writes");
          }
          {
            Object r = forward(vendor, method, args);
            router.setIsolation((Integer) args[0]);
            return r;
          }
        case "setCatalog":
          {
            String database = session.database();
            if (database != null && !database.equals(args[0])) {
              throw Errors.unsupported(
                  "the connection stays in database "
                      + database
                      + ", where it was opened; open a connection to "
                      + args[0]
                      + " instead");
            }
            Object r = forward(vendor, method, args);
            session.changed(vendor);
            return r;
          }
        case "setSchema":
          {
            Object r = forward(vendor, method, args);
            session.changed(vendor);
            return r;
          }
        case "commit":
          {
            Object r = forward(vendor, method, args);
            session.transactionEnded();
            return r;
          }
        case "rollback":
          {
            Object r = forward(vendor, method, args);
            if (args == null || args.length == 0) {
              session.transactionEnded();
            } else {
              session.rolledBackToSavepoint(); // the transaction goes on
            }
            return r;
          }
        case "setAutoCommit":
          {
            boolean on = Boolean.TRUE.equals(args[0]);
            if (!on) {
              // passthrough is for autocommit only; leaving autocommit ends it, which also resets
              // it when a pool that rolls back on return borrowed the connection that way
              router.setPassthrough(false);
            }
            // switching autocommit on commits an open transaction (Connection.setAutoCommit)
            boolean wasInTransaction = !vendor.getAutoCommit();
            Object r = forward(vendor, method, args);
            if (wasInTransaction && on) {
              session.transactionEnded();
            }
            return r;
          }
        case "setClientInfo":
          if (args[0] instanceof String) {
            if (PASSTHROUGH.equals(args[0])) {
              router.setPassthrough(Boolean.parseBoolean((String) args[1]));
              return null;
            }
            return forward(vendor, method, args);
          }
          {
            // the set replaces all client info: a key left out is cleared, passthrough included
            Properties rest = (Properties) ((Properties) args[0]).clone();
            router.setPassthrough(Boolean.parseBoolean((String) rest.remove(PASSTHROUGH)));
            vendor.setClientInfo(rest);
            return null;
          }
        case "getClientInfo":
          if (args != null && args.length == 1 && PASSTHROUGH.equals(args[0])) {
            return Boolean.toString(router.passthrough());
          }
          return forward(vendor, method, args);
        default:
          return forward(vendor, method, args);
      }
    }
  }

  private static final class StatementHandler extends Handler {
    final Statement vendor;
    final Connection connection;
    final ConnectionHandler owner;
    Router.@Nullable Route prepared;
    // the routes of the statements added with addBatch(String), applied when the batch runs
    final List<Router.Route> batched = new ArrayList<>();

    StatementHandler(
        Statement vendor,
        Connection connection,
        ConnectionHandler owner,
        Router.@Nullable Route prepared) {
      super(vendor);
      this.vendor = vendor;
      this.connection = connection;
      this.owner = owner;
      this.prepared = prepared;
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      String name = method.getName();
      if (name.equals("getConnection")) {
        return connection;
      }
      if (name.equals("clearBatch")) {
        batched.clear();
        return forward(vendor, method, args);
      }
      Router.Route split = prepared;
      if (split != null && split.parameterSources != null) {
        if (name.startsWith("set")
            && args != null
            && args.length >= 2
            && args[0] instanceof Integer) {
          bind(split, method, args);
          return null;
        }
        if (name.equals("getParameterMetaData")) {
          return proxy(
              ParameterMetaData.class, new ParameterHandler(created(vendor, method, args), split));
        }
      }
      boolean takesSql = args != null && args.length > 0 && args[0] instanceof String;
      if (takesSql && name.equals("addBatch")) {
        Router.Route route = owner.route((String) args[0]);
        if (!route.passthrough && (route.changesSession || route.changesNames)) {
          // the entries after it were routed before it changed what their names mean
          throw Errors.unsupported(
              "a statement that changes the session or what names resolve to cannot be batched;"
                  + " run it on its own");
        }
        Object result = forward(vendor, method, withSql(args, route));
        batched.add(route);
        return result;
      }
      List<Router.Route> ran = new ArrayList<>();
      Object[] routed = args;
      if (takesSql && name.startsWith("execute")) {
        Router.Route route = owner.route((String) args[0]);
        routed = withSql(args, route);
        ran.add(route);
      } else if (prepared != null && name.startsWith("execute")) {
        Router.Route route = owner.current(prepared);
        if (!route.sql.equals(prepared.sql)) {
          // the vendor statement holds the old text
          throw Errors.unsupported(
              "the statement now routes differently from when it was prepared; prepare it again");
        }
        prepared = route;
        ran.add(route);
      }
      if (name.startsWith("execute") && name.endsWith("Batch")) {
        // each entry was routed when it was added; check it again under the state it runs in
        List<Router.Route> entries = new ArrayList<>(batched);
        batched.clear(); // the driver clears the batch when it runs, whatever the outcome
        try {
          for (Router.Route entry : entries) {
            Router.Route now = owner.current(entry);
            if (!now.sql.equals(entry.sql)) {
              throw Errors.unsupported(
                  "a batched statement now routes differently from when it was added");
            }
            ran.add(now);
          }
        } catch (SQLException e) {
          vendor.clearBatch();
          throw e;
        }
      }
      Object result;
      try {
        result = forward(vendor, method, routed);
      } finally {
        for (Router.Route route : ran) {
          owner.ran(route);
        }
      }
      return result instanceof ResultSet
          ? proxy(ResultSet.class, new ChildHandler(result, connection, (Statement) proxy))
          : result;
    }

    /** Binds a value to every placeholder of a split read that takes it. */
    private void bind(Router.Route split, Method method, Object[] args) throws Throwable {
      int index = (Integer) args[0];
      checkIndex(index, split);
      for (Object a : args) {
        if (a instanceof InputStream || a instanceof Reader) {
          throw Errors.unsupported(
              "a stream cannot be bound to this read of ScalarDB tables, which reads each value"
                  + " more than once; bind the value itself");
        }
      }
      List<Integer> sources = Objects.requireNonNull(split.parameterSources);
      for (int i = 0; i < sources.size(); i++) {
        if (sources.get(i) == index) {
          Object[] at = args.clone();
          at[0] = i + 1;
          forward(vendor, method, at);
        }
      }
    }

    private static Object[] withSql(Object[] args, Router.Route route) {
      Object[] routed = args.clone();
      routed[0] = route.sql;
      return routed;
    }
  }

  private static void checkIndex(int index, Router.Route split) throws SQLException {
    if (index < 1 || index > split.parameters) {
      throw new SQLException(
          "parameter index " + index + " is out of range 1 to " + split.parameters, "07009");
    }
  }

  /**
   * The parameters of a split read as the application wrote them: the count of its own
   * placeholders, and each described by the first placeholder that takes its value.
   */
  private static final class ParameterHandler extends Handler {
    final Router.Route split;

    ParameterHandler(Object vendor, Router.Route split) {
      super(vendor);
      this.split = split;
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      if (method.getName().equals("getParameterCount")) {
        return split.parameters;
      }
      if (args != null && args.length == 1 && args[0] instanceof Integer) {
        int index = (Integer) args[0];
        checkIndex(index, split);
        return forward(
            target,
            method,
            new Object[] {Objects.requireNonNull(split.parameterSources).indexOf(index) + 1});
      }
      return forward(target, method, args);
    }
  }

  /**
   * Result sets and metadata: they hand back the connection and the statement, and wrap any result
   * set they return, such as one from a {@code DatabaseMetaData} query or a PostgreSQL refcursor,
   * whose own statement is then null rather than the vendor's.
   */
  private static final class ChildHandler extends Handler {
    final Connection connection;
    final @Nullable Statement statement;

    ChildHandler(Object vendor, Connection connection, @Nullable Statement statement) {
      super(vendor);
      this.connection = connection;
      this.statement = statement;
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      switch (method.getName()) {
        case "getConnection":
          return connection;
        case "getStatement":
          return statement;
        default:
          {
            Object result = forward(target, method, args);
            return result instanceof ResultSet
                ? proxy(ResultSet.class, new ChildHandler(result, connection, null))
                : result;
          }
      }
    }
  }
}
