package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

  /** The client-info key that sends statements verbatim, in autocommit only. */
  public static final String PASSTHROUGH = "scalardbnative.passthrough";

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
    session.schemas(vendor); // read now, outside any transaction
    ConnectionHandler h =
        new ConnectionHandler(vendor, new Router(engine, catalog, session), session);
    return proxy(Connection.class, h);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler h) {
    return (T) Proxy.newProxyInstance(Proxies.class.getClassLoader(), new Class<?>[] {type}, h);
  }

  /** A vendor method that creates a JDBC object never returns null. */
  private static Object created(Object target, Method method, @Nullable Object[] args)
      throws Throwable {
    return Objects.requireNonNull(forward(target, method, args), method.getName());
  }

  private static @Nullable Object forward(Object target, Method method, @Nullable Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  // The methods of Object and java.sql.Wrapper, the same for every proxy; a proxy equals only
  // itself.
  @SuppressWarnings("ReferenceEquality")
  private static @Nullable Object common(
      Object proxy, Object target, Method method, @Nullable Object[] args, boolean[] handled)
      throws Throwable {
    handled[0] = true;
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
        handled[0] = false;
        return null;
    }
  }

  private static final class ConnectionHandler implements InvocationHandler {
    final Connection vendor;
    final Router router;
    final Session session;

    ConnectionHandler(Connection vendor, Router router, Session session) {
      this.vendor = vendor;
      this.router = router;
      this.session = session;
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
        throws Throwable {
      boolean[] handled = {false};
      Object common = common(proxy, vendor, method, args, handled);
      if (handled[0]) {
        return common;
      }
      Connection me = (Connection) proxy;
      switch (method.getName()) {
        case "createStatement":
          return statement(Statement.class, (Statement) created(vendor, method, args), me, null);
        case "prepareStatement":
          {
            Router.Route route = router.route(vendor, (String) args[0]);
            Object[] routed = args.clone();
            routed[0] = route.sql;
            return statement(
                PreparedStatement.class,
                (PreparedStatement) created(vendor, method, routed),
                me,
                route);
          }
        case "prepareCall":
          {
            Router.Route route = router.route(vendor, (String) args[0]);
            Object[] routed = args.clone();
            routed[0] = route.sql;
            return statement(
                CallableStatement.class,
                (CallableStatement) created(vendor, method, routed),
                me,
                route);
          }
        case "getMetaData":
          return proxy(
              DatabaseMetaData.class, new ChildHandler(created(vendor, method, args), me, null));
        case "setTransactionIsolation":
          if ((Integer) args[0] == Connection.TRANSACTION_READ_UNCOMMITTED) {
            throw Errors.unsupported(
                "READ UNCOMMITTED is not supported: it would read ScalarDB's uncommitted writes");
          }
          return forward(vendor, method, args);
        case "setSchema":
        case "setCatalog":
          {
            Object r = forward(vendor, method, args);
            session.changed(vendor);
            return r;
          }
        case "commit":
        case "rollback":
          {
            Object r = forward(vendor, method, args);
            session.transactionEnded();
            return r;
          }
        case "setClientInfo":
          return setClientInfo(method, args);
        case "getClientInfo":
          if (args != null && args.length == 1 && PASSTHROUGH.equals(args[0])) {
            return Boolean.toString(router.passthrough());
          }
          return forward(vendor, method, args);
        default:
          return forward(vendor, method, args);
      }
    }

    private @Nullable Object setClientInfo(Method method, @Nullable Object[] args)
        throws Throwable {
      if (args[0] instanceof String) {
        if (PASSTHROUGH.equals(args[0])) {
          router.setPassthrough(Boolean.parseBoolean((String) args[1]));
          return null;
        }
        return forward(vendor, method, args);
      }
      Properties all = (Properties) args[0];
      Properties rest = new Properties();
      for (String key : all.stringPropertyNames()) {
        if (PASSTHROUGH.equals(key)) {
          router.setPassthrough(Boolean.parseBoolean(all.getProperty(key)));
        } else {
          rest.setProperty(key, all.getProperty(key));
        }
      }
      vendor.setClientInfo(rest);
      return null;
    }

    private <T extends Statement> T statement(
        Class<T> type, T vendorStatement, Connection me, Router.@Nullable Route prepared) {
      return proxy(type, new StatementHandler(vendorStatement, me, this, prepared));
    }
  }

  private static final class StatementHandler implements InvocationHandler {
    final Statement vendor;
    final Connection connection;
    final ConnectionHandler owner;
    final Router.@Nullable Route prepared;

    StatementHandler(
        Statement vendor,
        Connection connection,
        ConnectionHandler owner,
        Router.@Nullable Route prepared) {
      this.vendor = vendor;
      this.connection = connection;
      this.owner = owner;
      this.prepared = prepared;
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
        throws Throwable {
      boolean[] handled = {false};
      Object common = common(proxy, vendor, method, args, handled);
      if (handled[0]) {
        return common;
      }
      String name = method.getName();
      if (name.equals("getConnection")) {
        return connection;
      }
      boolean takesSql = args != null && args.length > 0 && args[0] instanceof String;
      Router.Route route = null;
      Object[] routed = args;
      if (takesSql && (name.startsWith("execute") || name.equals("addBatch"))) {
        route = owner.router.route(owner.vendor, (String) args[0]);
        routed = args.clone();
        routed[0] = route.sql;
      } else if (prepared != null && name.startsWith("execute")) {
        route = prepared;
      }
      Object result = forward(vendor, method, routed);
      if (route != null && !name.equals("addBatch")) {
        owner.router.ran(owner.vendor, route);
      }
      if (result instanceof ResultSet) {
        return proxy(ResultSet.class, new ChildHandler(result, connection, (Statement) proxy));
      }
      return result;
    }
  }

  // Result sets and metadata: they only hand back the connection and the statement.
  private static final class ChildHandler implements InvocationHandler {
    final Object vendor;
    final Connection connection;
    final @Nullable Statement statement;

    ChildHandler(Object vendor, Connection connection, @Nullable Statement statement) {
      this.vendor = vendor;
      this.connection = connection;
      this.statement = statement;
    }

    @Override
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
        throws Throwable {
      boolean[] handled = {false};
      Object common = common(proxy, vendor, method, args, handled);
      if (handled[0]) {
        return common;
      }
      switch (method.getName()) {
        case "getConnection":
          return connection;
        case "getStatement":
          return statement;
        default:
          return forward(vendor, method, args);
      }
    }
  }
}
