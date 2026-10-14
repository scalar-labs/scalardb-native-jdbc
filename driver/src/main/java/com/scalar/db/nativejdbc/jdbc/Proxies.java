package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.NativeJdbcDriver;
import com.scalar.db.nativejdbc.ScalarDbTables;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    // a prepared statement's is replaced when it is prepared again; read by cancel() on any thread
    volatile Object target;

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
            if (iface.isInstance(proxy)) {
              return proxy;
            }
            handedOut();
            return forward(target, method, args);
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

    /** The application now holds the vendor object itself. */
    void handedOut() {}
  }

  private static final class ConnectionHandler extends Handler {
    final Connection vendor;
    final Router router;
    final Session session;
    // after an abort the driver decided, until the application rolls back (design 4.4)
    volatile boolean rollbackOnly;

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
      if (route.kind == SqlAst.Kind.ROLLBACK) {
        rollbackOnly = false; // as Connection.rollback()
      }
      router.ran(vendor, route);
    }

    void requireNotRollbackOnly() throws SQLException {
      if (rollbackOnly) {
        throw new Writes.Aborted("the transaction was aborted by an earlier statement");
      }
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
            if (route.write != null
                && (method.getName().equals("prepareCall") || asksForGeneratedKeys(args))) {
              throw Errors.unsupported(
                  "a write of a ScalarDB table cannot be a call or return generated keys yet");
            }
            Object[] routed = args.clone();
            routed[0] = route.sql;
            StatementHandler h =
                new StatementHandler((Statement) created(vendor, method, routed), me, this, route);
            // only a route that depends on the in-flight counts is ever prepared again
            if (method.getName().equals("prepareStatement") && route.recheckAt != 0) {
              h.preparedWith = method;
              h.preparedArgs = args.clone();
            }
            return proxy(method.getReturnType(), h);
          }
        case "nativeSQL":
          // the statement as the database receives it: rewritten for ScalarDB tables
          return router.nativeSql(vendor, (String) args[0]);
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
            requireNotRollbackOnly();
            Object r = forward(vendor, method, args);
            session.transactionEnded();
            router.transactionEnded();
            return r;
          }
        case "rollback":
          {
            Object r = forward(vendor, method, args);
            router.rolledBack();
            if (args == null || args.length == 0) {
              rollbackOnly = false;
              session.transactionEnded();
              router.transactionEnded();
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
            if (wasInTransaction && on) {
              requireNotRollbackOnly();
            }
            Object r = forward(vendor, method, args);
            if (wasInTransaction && on) {
              session.transactionEnded();
              router.transactionEnded();
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

  /**
   * A parameter setter whose stream or reader is read now, as {@code setBytes} or {@code
   * setString}: a write binds each value into several statements, and a stream reads once.
   */
  // ponytail: the whole value in memory; a LOB of hundreds of megabytes costs that much here
  private static Call buffered(Method method, Object[] args) throws SQLException {
    Object value = args[1];
    if (!(value instanceof InputStream) && !(value instanceof Reader)) {
      return new Call(method, args);
    }
    String name = method.getName();
    long limit = args.length > 2 && args[2] instanceof Number ? ((Number) args[2]).longValue() : -1;
    try {
      if (value instanceof Reader) {
        StringBuilder text = new StringBuilder();
        char[] buf = new char[8192];
        Reader r = (Reader) value;
        int n;
        while ((limit < 0 || text.length() < limit)
            && (n =
                    r.read(
                        buf,
                        0,
                        limit < 0 ? buf.length : (int) Math.min(buf.length, limit - text.length())))
                > 0) {
          text.append(buf, 0, n);
        }
        String setter = name.startsWith("setN") ? "setNString" : "setString";
        return new Call(
            PreparedStatement.class.getMethod(setter, int.class, String.class),
            new Object[] {args[0], text.toString()});
      }
      if (name.equals("setUnicodeStream")) {
        throw Errors.unsupported("setUnicodeStream cannot be bound to a write of a ScalarDB table");
      }
      InputStream in = (InputStream) value;
      byte[] bytes = limit < 0 ? in.readAllBytes() : in.readNBytes((int) limit);
      return name.equals("setAsciiStream")
          ? new Call(
              PreparedStatement.class.getMethod("setString", int.class, String.class),
              new Object[] {args[0], new String(bytes, StandardCharsets.US_ASCII)})
          : new Call(
              PreparedStatement.class.getMethod("setBytes", int.class, byte[].class),
              new Object[] {args[0], bytes});
    } catch (IOException e) {
      throw new SQLException("the parameter's stream could not be read", "22000", e);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  /** Whether the arguments after a statement's text ask for generated keys. */
  private static boolean asksForGeneratedKeys(Object[] args) {
    if (args.length != 2) {
      return false; // the text alone, or with result set type, concurrency and holdability
    }
    return args[1] instanceof int[]
        || args[1] instanceof String[]
        || Integer.valueOf(Statement.RETURN_GENERATED_KEYS).equals(args[1]);
  }

  /** A recorded call, replayed on a statement prepared again. */
  private static final class Call {
    final Method method;
    final Object[] args;

    Call(Method method, Object[] args) {
      this.method = method;
      this.args = args.clone();
    }
  }

  private static final class StatementHandler extends Handler {
    final Connection connection;
    final ConnectionHandler owner;
    Router.@Nullable Route prepared;
    // the routes of the statements added with addBatch(String), applied when the batch runs
    final List<Router.Route> batched = new ArrayList<>();
    // for a prepared split read, what it takes to prepare it again when the in-flight counts change
    // its form (todo 103): how it was prepared, its settings, its bound parameters
    @Nullable Method preparedWith;
    Object @Nullable [] preparedArgs;
    final Map<String, Call> settings = new LinkedHashMap<>();
    final Map<Integer, Call> parameters = new LinkedHashMap<>();
    // the statement's state, like its vendor statement, may be read from another thread
    volatile boolean batchPending;
    volatile boolean unwrapped;
    // the update count of the last write the driver ran itself, -1 once read past; null otherwise
    @Nullable Long writeCount;
    // the driver's statement a write is running, which cancel() reaches from another thread
    volatile @Nullable Statement runningWrite;

    @Override
    void handedOut() {
      unwrapped = true;
    }

    /** The vendor statement: replaced when the statement is prepared again. */
    Statement vendor() {
      return (Statement) target;
    }

    StatementHandler(
        Statement vendor,
        Connection connection,
        ConnectionHandler owner,
        Router.@Nullable Route prepared) {
      super(vendor);
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
        batchPending = false;
        return forward(vendor(), method, args);
      }
      if (name.equals("clearParameters")) {
        parameters.clear();
        return forward(vendor(), method, args);
      }
      boolean takesSql = args != null && args.length > 0 && args[0] instanceof String;
      if (name.startsWith("execute") || name.equals("addBatch")) {
        if (!(takesSql && name.startsWith("execute"))) {
          owner.requireNotRollbackOnly(); // a statement's text is checked once it is routed
        }
        writeCount = null;
      }
      if (name.equals("cancel")) {
        Statement running = runningWrite;
        if (running != null) {
          running.cancel(); // the driver's statement of a write, not the idle vendor one (136)
          return null;
        }
      }
      if (writeCount != null) {
        switch (name) {
          case "getUpdateCount":
            return (int) Math.min(writeCount, Integer.MAX_VALUE);
          case "getLargeUpdateCount":
            return writeCount;
          case "getResultSet":
            return null;
          case "getMoreResults":
            writeCount = -1L;
            return false;
          default:
            break;
        }
      }
      if (prepared != null && prepared.write != null) {
        if (name.equals("addBatch")) {
          throw Errors.unsupported("a write of a ScalarDB table cannot be batched yet");
        }
        if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
          // nothing can be added, so the batch is empty (133)
          return name.equals("executeBatch") ? new int[0] : new long[0];
        }
        if (name.startsWith("set")
            && args != null
            && args.length >= 2
            && args[0] instanceof Integer) {
          // read each value once: the write binds it into several statements (131)
          Call c = buffered(method, args);
          Object r = forward(vendor(), c.method, c.args); // kept for getParameterMetaData
          parameters.put((Integer) args[0], c);
          return r;
        }
      }
      if (name.equals("addBatch") && (args == null || args.length == 0)) {
        batchPending = true;
        return forward(vendor(), method, args);
      }
      if ((name.startsWith("set") && args != null && args.length == 1)
          || name.equals("closeOnCompletion")) {
        Object r = forward(vendor(), method, args);
        if (preparedWith != null) {
          settings.remove(name); // replayed in the order of the last calls (todo 111)
          settings.put(name, new Call(method, args == null ? new Object[0] : args));
        }
        return r;
      }
      Router.Route split = prepared;
      if (split != null && split.parameterSources != null) {
        if (name.startsWith("set")
            && args != null
            && args.length >= 2
            && args[0] instanceof Integer) {
          bind(split, method, args);
          parameters.put((Integer) args[0], new Call(method, args));
          return null;
        }
        if (name.equals("getParameterMetaData")) {
          return proxy(
              ParameterMetaData.class,
              new ParameterHandler(created(vendor(), method, args), split));
        }
      }
      if (takesSql && name.equals("addBatch")) {
        Router.Route route = owner.route((String) args[0]);
        if (route.write != null) {
          throw Errors.unsupported("a write of a ScalarDB table cannot be batched yet");
        }
        if (!route.passthrough && (route.changesSession || route.changesNames)) {
          // the entries after it were routed before it changed what their names mean
          throw Errors.unsupported(
              "a statement that changes the session or what names resolve to cannot be batched;"
                  + " run it on its own");
        }
        Object result = forward(vendor(), method, withSql(args, route));
        batched.add(route);
        return result;
      }
      List<Router.Route> ran = new ArrayList<>();
      Object[] routed = args;
      if (takesSql && name.startsWith("execute")) {
        Router.Route route = owner.route((String) args[0]);
        if (route.kind != SqlAst.Kind.ROLLBACK && route.kind != SqlAst.Kind.ROLLBACK_TO_SAVEPOINT) {
          owner.requireNotRollbackOnly();
        }
        if (route.write != null) {
          return runWrite(name, args, route);
        }
        routed = withSql(args, route);
        ran.add(route);
      } else if (prepared != null && prepared.write != null && name.startsWith("execute")) {
        Router.Route route = owner.current(prepared);
        if (route.write == null || !route.sql.equals(prepared.sql)) {
          throw Errors.unsupported(
              "the statement now routes differently from when it was prepared; prepare it again");
        }
        prepared = route;
        return runWrite(name, args, route);
      } else if (prepared != null && name.startsWith("execute")) {
        Router.Route route = owner.current(prepared);
        if (route.write != null) {
          // its text would run as written, outside the write's protocol (130)
          throw Errors.unsupported(
              "the statement now writes a ScalarDB table, which it did not when it was prepared;"
                  + " prepare it again");
        }
        if (!route.sql.equals(prepared.sql)) {
          if (!route.countsOnly) {
            // the vendor statement holds the old text
            throw Errors.unsupported(
                "the statement now routes differently from when it was prepared; prepare it"
                    + " again");
          }
          if (canPrepareAgain(prepared, route)) {
            prepareAgain(route);
          } else {
            // the old form returns the same rows; checked again after the new route's interval
            route = Router.keepText(prepared, route);
          }
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
            if (now.write != null) {
              throw Errors.unsupported(
                  "a batched statement now writes a ScalarDB table, which it did not when it was"
                      + " added");
            }
            if (!now.sql.equals(entry.sql)) {
              if (!now.countsOnly) {
                throw Errors.unsupported(
                    "a batched statement now routes differently from when it was added");
              }
              now = entry; // the text the batch holds returns the same rows
            }
            ran.add(now);
          }
        } catch (SQLException e) {
          vendor().clearBatch();
          throw e;
        }
      }
      if (name.startsWith("execute") && name.endsWith("Batch")) {
        batchPending = false;
      }
      owner.router.beforeRun(owner.vendor, ran);
      Object result;
      try {
        result = forward(vendor(), method, routed);
      } finally {
        for (Router.Route route : ran) {
          owner.ran(route);
        }
      }
      return result instanceof ResultSet
          ? proxy(ResultSet.class, new ChildHandler(result, connection, (Statement) proxy))
          : result;
    }

    /** Runs a write the driver plans, and answers as the vendor's statement would. */
    private @Nullable Object runWrite(String name, @Nullable Object[] args, Router.Route route)
        throws SQLException {
      if (name.equals("executeQuery")) {
        throw new SQLException("the statement returns no result set", "02000");
      }
      boolean prepared = args == null || args.length == 0 || !(args[0] instanceof String);
      if (!prepared && asksForGeneratedKeys(args)) {
        throw Errors.unsupported("a write of a ScalarDB table cannot return generated keys yet");
      }
      // an execute closes the statement's current result set
      ResultSet open = vendor().getResultSet();
      if (open != null) {
        open.close();
      }
      long count =
          Writes.run(
              owner.vendor,
              Objects.requireNonNull(route.write),
              new Writes.Context() {
                @Override
                public void bind(PreparedStatement ps, int position, int placeholder)
                    throws SQLException {
                  Call c = prepared ? parameters.get(placeholder) : null;
                  if (c == null) {
                    throw new SQLException(
                        "no value is bound to parameter " + placeholder, "07001");
                  }
                  Object[] at = c.args.clone();
                  at[0] = position;
                  try {
                    forward(ps, c.method, at);
                  } catch (SQLException | RuntimeException e) {
                    throw e;
                  } catch (Throwable t) {
                    throw new SQLException(t);
                  }
                }

                @Override
                public int queryTimeout() throws SQLException {
                  return vendor().getQueryTimeout();
                }

                @Override
                public void running(@Nullable Statement s) {
                  runningWrite = s;
                }

                @Override
                public void rollbackOnly() {
                  owner.rollbackOnly = true;
                }
              });
      writeCount = count;
      switch (name) {
        case "executeUpdate":
          return (int) Math.min(count, Integer.MAX_VALUE);
        case "executeLargeUpdate":
          return count;
        default:
          return false; // execute: an update count, no result set
      }
    }

    /**
     * Whether a prepared split read can be prepared again for a route that differs only in its
     * committed check: the same placeholders, and no batch the vendor statement already holds.
     */
    private boolean canPrepareAgain(Router.Route old, Router.Route now) throws SQLException {
      // not a closed statement, which must keep failing, nor one the application holds unwrapped
      return preparedWith != null
          && !unwrapped
          && !vendor().isClosed()
          && !batchPending
          && old.parameterSources != null
          && old.parameterSources.equals(now.parameterSources);
    }

    /**
     * Prepares the statement again with the route's text, replays its settings and parameters, and
     * closes the old vendor statement. An application that unwrapped the old one keeps a closed
     * statement.
     */
    private void prepareAgain(Router.Route route) throws Throwable {
      Object[] args = Objects.requireNonNull(preparedArgs).clone();
      args[0] = route.sql;
      Statement fresh =
          (Statement) created(owner.vendor, Objects.requireNonNull(preparedWith), args);
      Statement old = vendor();
      try {
        for (Call c : settings.values()) {
          forward(fresh, c.method, c.args);
        }
        target = fresh;
        for (Call c : parameters.values()) {
          bind(route, c.method, c.args);
        }
      } catch (Throwable t) {
        target = old;
        try {
          fresh.close();
        } catch (SQLException e) {
          t.addSuppressed(e);
        }
        throw t;
      }
      old.close();
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
          forward(vendor(), method, at);
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
