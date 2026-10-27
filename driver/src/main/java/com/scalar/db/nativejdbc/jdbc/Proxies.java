package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.NativeJdbcDriver;
import com.scalar.db.nativejdbc.ScalarDbTables;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
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
   * @param inflightWait how long a write waits for a row in flight to settle, zero to abort at once
   * @param predicateChecks whether a read of one table checks its predicate's rows in flight before
   *     it aborts on its table's (design 4.2)
   * @param coordinatorNamespace ScalarDB's namespace for its coordinator table
   * @return the connection the application uses
   * @throws SQLException if the session cannot be read or the isolation level set
   */
  public static Connection connection(
      Connection vendor,
      Engine engine,
      Catalog catalog,
      Duration inflightWait,
      boolean predicateChecks,
      String coordinatorNamespace)
      throws SQLException {
    // vendor defaults differ (Connector/J and MariaDB start at REPEATABLE READ), so every engine
    // starts at READ COMMITTED, the driver's default level (design section 1)
    vendor.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    Session session = new Session(engine);
    if (engine == Engine.SQLSERVER) {
      session.requireReadCommittedSnapshot(vendor);
    }
    session.schemas(vendor); // read now, outside any transaction
    Router router = new Router(engine, catalog, session, predicateChecks, coordinatorNamespace);
    // a URL may open the connection read-only, as pgjdbc's readOnly does
    router.setReadOnly(vendor.isReadOnly());
    // ScalarDbTables, reached with unwrap, is the driver's own interface (design section 1)
    return (Connection)
        Proxy.newProxyInstance(
            Proxies.class.getClassLoader(),
            new Class<?>[] {Connection.class, ScalarDbTables.class},
            new ConnectionHandler(vendor, router, session, inflightWait));
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

    /** The application now holds the vendor object itself, or is refused it. */
    void handedOut() throws SQLException {}
  }

  private static final class ConnectionHandler extends Handler {
    final Connection vendor;
    final Router router;
    final Session session;
    // after an abort the driver decided, until the application rolls back (design 4.4)
    volatile boolean rollbackOnly;
    // whether a statement ran in the open transaction, whose level is then fixed (todo 219)
    volatile boolean ranInTransaction;
    // the versions the open transaction read, the anchors of its writes (4.2)
    final Anchors anchors = new Anchors();
    final Duration inflightWait;
    // what the vendor reports for a BOOLEAN column of a ScalarDB table read as it is (todo 304)
    volatile @Nullable BooleanType booleanType;
    // a batch's entries sent together left their savepoint set, which the next such text releases
    // first; anything that may set, release or roll back a savepoint forgets it, and it stays,
    // below theirs, to the transaction's end, never undone to (todo 329)
    volatile boolean together;
    // pgjdbc's getAutosave, or null where there is none
    private @Nullable Method autosave;
    private boolean autosaveRead;

    ConnectionHandler(Connection vendor, Router router, Session session, Duration inflightWait) {
      super(vendor);
      this.vendor = vendor;
      this.router = router;
      this.session = session;
      this.inflightWait = inflightWait;
    }

    Router.Route route(String sql) throws SQLException {
      return router.route(vendor, sql);
    }

    /**
     * What the vendor reports for the BOOLEAN columns a route returns, read once with its probe,
     * before the statement runs: a streaming result would hold the connection then; null to leave
     * the columns as the vendor returns them.
     */
    @SuppressFBWarnings(
        value = "SQL_INJECTION_JDBC",
        justification = "the driver's own read of a column, its names quoted from the catalog")
    @Nullable BooleanType booleanType(Router.@Nullable Booleans b) {
      BooleanType t = booleanType;
      if (b == null || t != null) {
        return b == null ? null : t;
      }
      try (Statement st = vendor.createStatement();
          ResultSet rs = st.executeQuery(b.probe)) {
        t = new BooleanType(rs.getMetaData());
      } catch (SQLException e) {
        return null; // ponytail: the columns stay as the vendor returns them; read again next time
      }
      booleanType = t;
      return t;
    }

    Router.Route current(Router.Route prepared) throws SQLException {
      return router.current(vendor, prepared);
    }

    void ran(Router.Route route) {
      if (route.kind != SqlAst.Kind.SELECT
          && route.kind != SqlAst.Kind.DML
          && route.kind != SqlAst.Kind.SESSION) {
        together = false; // transaction control, or what may be
      }
      if (route.kind == SqlAst.Kind.ROLLBACK) {
        rollbackOnly = false; // as Connection.rollback()
      }
      if (route.kind == SqlAst.Kind.COMMIT || route.kind == SqlAst.Kind.ROLLBACK) {
        anchors.transactionEnded();
        ranInTransaction = false;
      } else {
        try {
          ranInTransaction |= !vendor.getAutoCommit();
        } catch (SQLException e) {
          ranInTransaction = true; // unknown: refuse a change of level until the transaction ends
        }
      }
      router.ran(vendor, route);
    }

    /**
     * Whether a batch's entries may go together: not under pgjdbc's autosave, whose savepoint
     * before each statement the text's release of the batch's would release too, so that its
     * rollback after an error would undo statements before the text. Read at each batch, as the
     * application may change it through {@code unwrap}.
     */
    boolean sendsTogether() {
      if (router.engine() != Engine.POSTGRESQL) {
        return false;
      }
      try {
        if (!autosaveRead) {
          Class<?> pg =
              Class.forName(
                  "org.postgresql.PGConnection", false, vendor.getClass().getClassLoader());
          autosave = pg.getMethod("getAutosave");
          autosaveRead = true;
        }
        Method m = autosave;
        return m != null
            && "NEVER".equals(String.valueOf(m.invoke(vendor.unwrap(m.getDeclaringClass()))));
      } catch (ReflectiveOperationException | SQLException | LinkageError e) {
        autosaveRead = true; // unknown: one at a time
        return false;
      }
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
        case "commit":
        case "rollback":
        case "setAutoCommit":
        case "setSavepoint":
        case "releaseSavepoint":
          together = false; // ends the savepoint, or sets one above it (todo 329)
          break;
        default:
          break;
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
            if (route.write != null && method.getName().equals("prepareCall")) {
              throw Errors.unsupported("a write of a ScalarDB table cannot be a call");
            }
            String keys =
                route.write != null && asksForGeneratedKeys(args)
                    ? Writes.keysAsked(route.write, args[1])
                    : null;
            Object[] routed = args.clone();
            routed[0] = route.sql;
            StatementHandler h =
                new StatementHandler((Statement) created(vendor, method, routed), me, this, route);
            h.keysAsked = keys;
            // only a route that depends on the in-flight counts, or on whether its anchors are
            // recorded, or a plain read, whose text a level locks on MySQL and MariaDB (todo 242),
            // or a read with a statement for tables found clear (todo 329), is ever prepared
            // again
            if (method.getName().equals("prepareStatement")
                && (route.recheckAt != 0
                    || route.anchoring != null
                    || route.plainSql != null
                    || (route.kind == SqlAst.Kind.SELECT
                        && StatementHandler.plain(route)
                        && router.locksAtSerializable()))) {
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
              DatabaseMetaData.class,
              router.engine() == Engine.ORACLE
                  ? new OracleMetaData(created(vendor, method, args), me)
                  : new ChildHandler(created(vendor, method, args), me, null));
        case "setTransactionIsolation":
          if ((Integer) args[0] == Connection.TRANSACTION_READ_UNCOMMITTED) {
            throw Errors.unsupported(
                "READ UNCOMMITTED is not supported: it would read ScalarDB's uncommitted writes");
          }
          if (ranInTransaction && !vendor.getAutoCommit()) {
            // MySQL and MariaDB would change the next transaction's level, not this one's, which
            // the driver's reads then would not match (todo 219); PostgreSQL's driver refuses too
            throw new SQLException(
                "the isolation level cannot be changed in the middle of a transaction", "25001");
          }
          {
            int level = (Integer) args[0];
            if (level == Connection.TRANSACTION_SERIALIZABLE && router.engine() == Engine.ORACLE) {
              throw Errors.unsupported(
                  "SERIALIZABLE is not supported on Oracle, whose SERIALIZABLE is snapshot"
                      + " isolation: set TRANSACTION_REPEATABLE_READ for that (design 5)");
            }
            int vendorLevel = router.sessionLevel(level, vendor.getAutoCommit());
            if (vendorLevel == Router.SQLSERVER_SNAPSHOT) {
              session.requireSnapshotIsolation(vendor);
            }
            if (!vendor.getAutoCommit()) {
              // nothing of the application's ran, but the driver's own lookups, of index columns
              // say, may have opened the transaction at the old level, which SQL Server then
              // refuses to go on at SNAPSHOT in (error 3951)
              vendor.rollback();
            }
            vendor.setTransactionIsolation(vendorLevel);
            router.setIsolation(level);
            return null;
          }
        case "setReadOnly":
          {
            boolean on = Boolean.TRUE.equals(args[0]);
            if (on != router.readOnly() && ranInTransaction && !vendor.getAutoCommit()) {
              // a read-only transaction at SERIALIZABLE read without locks, which a write would
              // need (todo 324); JDBC lets a driver refuse the change in a transaction
              throw new SQLException(
                  "the read-only setting cannot be changed in the middle of a transaction",
                  "25001");
            }
            forward(vendor, method, args);
            // tracked by the driver: SQL Server's driver ignores it, and reports false
            router.setReadOnly(on);
            return null;
          }
        case "isReadOnly":
          return router.readOnly();
        case "getTransactionIsolation":
          {
            // the vendor connection's own level, unless the driver runs SERIALIZABLE at another
            int driver = router.isolation();
            if (router.engine() == Engine.ORACLE) {
              return driver; // the vendor's is Oracle's SERIALIZABLE, or READ COMMITTED
            }
            if (driver == Connection.TRANSACTION_NONE) {
              // SQL Server's lock-based REPEATABLE READ, set in SQL, at which the driver does not
              // run (todo 264)
              return Connection.TRANSACTION_REPEATABLE_READ;
            }
            int level = Router.driverLevel(vendor.getTransactionIsolation());
            return driver == Connection.TRANSACTION_SERIALIZABLE
                    && level == Connection.TRANSACTION_REPEATABLE_READ
                    && router.locksAtSerializable()
                ? driver
                : level;
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
            try {
              return forward(vendor, method, args);
            } finally {
              // a commit that fails ends the transaction too, as SERIALIZABLE's 40001 (todo 170)
              session.transactionEnded();
              router.transactionEnded();
              anchors.transactionEnded();
              ranInTransaction = false;
            }
          }
        case "rollback":
          {
            Object r = forward(vendor, method, args);
            router.rolledBack(args != null && args.length > 0);
            if (args == null || args.length == 0) {
              rollbackOnly = false;
              session.transactionEnded();
              router.transactionEnded();
              anchors.transactionEnded();
              ranInTransaction = false;
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
            try {
              return forward(vendor, method, args);
            } finally {
              if (wasInTransaction && on) {
                session.transactionEnded();
                router.transactionEnded();
                anchors.transactionEnded();
                ranInTransaction = false;
              }
              if (router.engine() == Engine.ORACLE && wasInTransaction == on) {
                vendor.setTransactionIsolation(router.sessionLevel(router.isolation(), on));
              }
            }
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
        case "close":
          // Oracle's driver commits an open transaction at close: one the driver aborted would
          // commit what it refused, a key's DML without its stamp among them (todo 325)
          if (rollbackOnly && !vendor.isClosed()) {
            try {
              vendor.rollback();
            } catch (SQLException e) {
              // the close below ends the session, and a dead session commits nothing
            }
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

  /** The rows a write or a locking read returns, handed over by {@link Writes.Context#rows}. */
  private static final class Out {
    @Nullable ResultSet rows;
    int hidden;
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
    // the parameters of each addBatch() of a prepared write, which the driver runs itself
    final List<Map<Integer, Call>> writeBatch = new ArrayList<>();
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
    // the rows its INSERTs wrote, read back: the statement's own RETURNING result when
    // writeReturns, otherwise the generated keys; keysAsked, the ones asked for at prepare
    @Nullable ResultSet writeResult;
    volatile boolean writeReturns;
    @Nullable ResultSet writeKeys;
    @Nullable String keysAsked;
    // set by cancel(), which a write's wait between its statements checks (todo 153)
    volatile boolean cancelled;
    // the driver's statement a write is running, which cancel() reaches from another thread
    volatile @Nullable Statement runningWrite;
    // the last execute's anchored read, whose result sets hide and record the versions, and whether
    // it ran in a transaction, the only place its anchors serve
    Router.@Nullable Anchored anchored;
    // the last execute's returned BOOLEAN columns of ScalarDB tables (todo 304)
    Router.@Nullable Booleans booleans;
    @Nullable BooleanType booleanType;
    boolean recording;
    long epoch;
    // whether the last execute ran in a transaction
    boolean inTransaction;
    // a prepared read's other vendor statement: the one over the data tables as written, run once
    // the transaction found the tables clear, or the rewritten one while the plain one is the
    // target (todo 329); its settings and parameters are replayed as it becomes the target
    volatile @Nullable Statement alternate;
    volatile boolean plainNow;

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
        writeBatch.clear();
        batchPending = false;
        return forward(vendor(), method, args);
      }
      if (name.equals("clearParameters")) {
        parameters.clear();
        return forward(vendor(), method, args);
      }
      boolean takesSql = args != null && args.length > 0 && args[0] instanceof String;
      if (name.startsWith("execute") || name.equals("addBatch") || name.equals("close")) {
        if (!(takesSql && name.startsWith("execute")) && !name.equals("close")) {
          owner.requireNotRollbackOnly(); // a statement's text is checked once it is routed
        }
        writeCount = null;
        cancelled = false;
        for (ResultSet rows : new ResultSet[] {writeResult, writeKeys}) {
          writeResult = null;
          writeKeys = null;
          if (rows != null) {
            rows.close();
          }
        }
      }
      if (name.equals("getGeneratedKeys") && writeKeys != null) {
        return writeKeys;
      }
      if (name.equals("close") && alternate != null) {
        Statement other = alternate;
        alternate = null;
        other.close();
      }
      if (name.equals("cancel")) {
        cancelled = true;
        Statement running = runningWrite;
        if (running != null) {
          try {
            running.cancel(); // the driver's statement of a write, not the idle vendor one (136)
          } catch (SQLException ex) {
            // between two statements of a wait, the flag ends it; MariaDB's driver refuses
            // to cancel a closed statement
            if (!running.isClosed()) {
              throw ex;
            }
          }
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
            return writeReturns ? writeResult : null;
          case "getMoreResults":
            if (writeReturns && writeResult != null) {
              writeResult.close();
              writeResult = null;
              if (writeKeys != null && writeKeys.isClosed()) {
                writeKeys = null; // they were the same rows
              }
            }
            writeCount = -1L;
            writeReturns = false;
            return false;
          default:
            break;
        }
      }
      if (prepared != null && prepared.write != null) {
        if (name.equals("addBatch")) {
          writeBatch.add(new LinkedHashMap<>(parameters));
          return null;
        }
        if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
          List<Map<Integer, Call>> entries = new ArrayList<>(writeBatch);
          writeBatch.clear(); // cleared when it runs, whatever the outcome
          Router.Route route = owner.current(prepared);
          if (route.write == null || !route.sql.equals(prepared.sql)) {
            throw Errors.unsupported(
                "the statement now routes differently from when it was prepared; prepare it"
                    + " again");
          }
          prepared = route;
          return runBatch(proxy, name, entries.size(), i -> route, entries::get, keysAsked);
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
      } else if (preparedWith != null
          && name.startsWith("set")
          && args != null
          && args.length >= 2
          && args[0] instanceof Integer) {
        // kept to bind again when the statement is prepared again
        Object r = forward(vendor(), method, args);
        parameters.put((Integer) args[0], new Call(method, args));
        return r;
      }
      if (takesSql && name.equals("addBatch")) {
        Router.Route route = owner.route((String) args[0]);
        if (route.write != null) {
          batched.add(route); // run by the driver, with the batch's other entries (below)
          return null;
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
          return runWrite(proxy, name, args, route);
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
        return runWrite(proxy, name, args, route);
      } else if (prepared != null && name.startsWith("execute")) {
        Router.Route route = owner.current(prepared);
        if (route.write != null) {
          // its text would run as written, outside the write's protocol (130)
          throw Errors.unsupported(
              "the statement now writes a ScalarDB table, which it did not when it was prepared;"
                  + " prepare it again");
        }
        if (!route.sql.equals(prepared.sql)) {
          // another isolation level reads another form: at SNAPSHOT and SERIALIZABLE the rows
          // carry their states, which the old text would not check
          boolean level = route.isolation != prepared.isolation;
          if (!route.countsOnly && !level) {
            // the vendor statement holds the old text
            throw Errors.unsupported(
                "the statement now routes differently from when it was prepared; prepare it"
                    + " again");
          }
          if (canPrepareAgain(prepared, route)) {
            prepareAgain(route);
          } else if (level) {
            throw Errors.unsupported(
                "the statement was prepared at another isolation level and cannot be prepared"
                    + " again; prepare it again");
          } else if (route.anchored != null && prepared.anchored == null) {
            // the old text reads no versions: its rows would anchor nothing, silently
            throw Errors.unsupported(
                "the statement was prepared outside a transaction and cannot be prepared again"
                    + " here, where its reads anchor the writes that follow; prepare it again");
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
        List<Router.Route> nows = new ArrayList<>();
        boolean writes = false;
        try {
          for (Router.Route entry : entries) {
            Router.Route now = owner.current(entry);
            nows.add(now);
            writes |= entry.write != null || now.write != null;
          }
        } catch (SQLException e) {
          vendor().clearBatch();
          throw e;
        }
        if (writes) {
          // a write is several statements: the driver runs every entry itself, in order
          vendor().clearBatch();
          batchPending = false;
          return runBatch(proxy, name, nows.size(), i -> nows.get(i), i -> null, null);
        }
        try {
          for (int i = 0; i < entries.size(); i++) {
            Router.Route entry = entries.get(i);
            Router.Route now = nows.get(i);
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
      if (name.startsWith("execute")) {
        anchored = ran.size() == 1 ? ran.get(0).anchored : null;
        booleans = ran.size() == 1 ? ran.get(0).booleans : null;
        booleanType = owner.booleanType(booleans);
        // nothing to anchor in autocommit, nor where nothing can be written (todo 146)
        recording =
            anchored != null
                && anchored.versions
                && !owner.vendor.getAutoCommit()
                && !owner.router.readOnly();
        epoch = owner.anchors.epoch();
        Router.Route read = ran.size() == 1 ? ran.get(0) : null;
        if (read != null
            && read.write == null
            && !read.sql.equals(read.source)
            && vendor().getResultSetConcurrency() == ResultSet.CONCUR_UPDATABLE) {
          // the vendor would update the rows through the rewritten text, outside a write's
          // protocol, or fail on it (todo 144)
          throw Errors.unsupported(
              "an updatable result set over a ScalarDB table is not supported; update the rows with"
                  + " UPDATE");
        }
      }
      if (name.equals("getMetaData")
          && prepared != null
          && prepared.anchored != null
          && !plainNow) {
        Object md = forward(vendor(), method, args);
        return md == null
            ? null
            : booleanMetaData(
                proxy(
                    java.sql.ResultSetMetaData.class,
                    new HiddenColumns(md, prepared.anchored.hidden())),
                prepared.booleans,
                owner.booleanType(prepared.booleans));
      }
      if (name.equals("getMetaData") && prepared != null && prepared.booleans != null) {
        Object md = forward(vendor(), method, args);
        return md == null
            ? null
            : booleanMetaData(md, prepared.booleans, owner.booleanType(prepared.booleans));
      }
      Router.Route checked = ran.size() == 1 && !ran.get(0).checks.isEmpty() ? ran.get(0) : null;
      if (checked != null
          && checked.anchored != null
          && checked.anchored.states
          && vendor().getResultSetType() == ResultSet.TYPE_FORWARD_ONLY) {
        checked = null; // each row the application moves to is checked by its state
      }
      // an abort marks the transaction the read ran in, not autocommit nor a later one (todos
      // 169, 180): as it was at the execute, which getResultSet may follow later
      if (name.startsWith("execute")) {
        inTransaction = !owner.vendor.getAutoCommit();
      }
      boolean marks = inTransaction;
      boolean ranBefore = owner.ranInTransaction;
      long at = epoch;
      // in autocommit the checks and the read share one snapshot in a transaction of their own,
      // its rows fetched whole, since its end closes a cursor
      boolean own = checked != null && owner.vendor.getAutoCommit();
      int fetchSize = own ? vendor().getFetchSize() : 0;
      if (own) {
        owner.vendor.setAutoCommit(false);
        if (owner.router.engine() == Engine.ORACLE) {
          // the session runs at READ COMMITTED in autocommit: the checks and the read need one
          // snapshot, Oracle's SERIALIZABLE (todo 283)
          owner.vendor.setTransactionIsolation(
              owner.router.sessionLevel(owner.router.isolation(), false));
        }
      }
      Object result;
      try {
        if (own) {
          vendor().setFetchSize(0);
        }
        if (checked != null) {
          try {
            owner.router.check(
                owner.vendor,
                checked,
                vendor().getQueryTimeout(),
                // a value read once is read by the statement itself
                boundOnce()
                    ? null
                    : (ps, position, placeholder) ->
                        bind(ps, position, placeholder, parameters.get(placeholder)));
          } catch (Writes.Aborted e) {
            if (!own) {
              owner.rollbackOnly = true;
            }
            throw e;
          }
        }
        // the statement as written over the data tables, once the transaction found every table
        // of the read clear, in place of the rewritten one (design 4.2, todo 329)
        Router.Route one = ran.size() == 1 && !name.endsWith("Batch") ? ran.get(0) : null;
        boolean plain =
            one != null
                && one.plainSql != null
                && one.write == null
                && marks
                && !unwrapped
                && (takesSql || !streamBound())
                && owner.router.allPassed(one.clearedBy);
        if (plain) {
          if (takesSql) {
            routed = routed.clone();
            routed[0] = one.plainSql;
          } else {
            switchTo(true, one);
          }
          anchored = null;
          recording = false;
        } else if (!takesSql && plainNow && one != null) {
          if (streamBound()) {
            throw Errors.unsupported(
                "a stream cannot be bound to this read of ScalarDB tables, whose text changes"
                    + " between transactions; bind the value itself");
          }
          switchTo(false, one);
        }
        // the workers stay as they are for the plain statement, unless its own plan is parallel,
        // where the rewritten one would have kept them too: a SET each way would cost a round
        // trip at every switch
        owner.router.beforeRun(
            owner.vendor, plain && !Objects.requireNonNull(one).parallel ? List.of() : ran);
        try {
          result = forward(vendor(), method, routed);
        } finally {
          for (Router.Route route : ran) {
            owner.ran(route);
          }
        }
      } catch (Throwable e) {
        if (own) {
          try {
            endOwn(false, fetchSize);
          } catch (SQLException f) {
            e.addSuppressed(f); // the read's own failure is the one to report
          }
        } else if (marks
            && e instanceof SQLException
            && Writes.endsTransaction(owner.vendor, (SQLException) e)) {
          // a deadlock ended the transaction, and the session goes on in a new one: the checks
          // passed and the anchors read hold no more (todo 230); with nothing run before, as a
          // compile error first in a transaction on SQL Server, the new one is just a new one
          // (todo 257)
          if (ranBefore) {
            owner.rollbackOnly = true;
          } else {
            owner.anchors.transactionEnded();
            owner.ranInTransaction = false;
          }
        }
        throw e instanceof SQLException ? Writes.asRetryable((SQLException) e) : e;
      }
      if (own) {
        endOwn(true, fetchSize);
      }
      if (!(result instanceof ResultSet)) {
        return result;
      }
      // only the statement's own result is anchored, not getGeneratedKeys' (todo 144)
      boolean itsOwn = name.startsWith("execute") || name.equals("getResultSet");
      Router.Anchored a = itsOwn ? anchored : null;
      // a vendor error a row brings can end the transaction too, as SQL Server's 3960 and
      // deadlocks do while rows stream: marked as at the execute (todo 263)
      UnaryOperator<SQLException> failed =
          e -> {
            if (marks && owner.anchors.epoch() == at && Writes.endsTransaction(owner.vendor, e)) {
              owner.rollbackOnly = true;
            }
            return Writes.asRetryable(e);
          };
      Object rows =
          a == null
              ? proxy(
                  ResultSet.class, new ChildHandler(result, connection, (Statement) proxy, failed))
              : proxy(
                  ResultSet.class,
                  new AnchoredHandler(
                      result,
                      connection,
                      (Statement) proxy,
                      a,
                      recording ? owner.anchors : null,
                      epoch,
                      () -> {
                        if (marks && owner.anchors.epoch() == at) {
                          owner.rollbackOnly = true;
                        }
                      },
                      marks
                          ? check -> {
                            if (owner.anchors.epoch() == at) {
                              owner.router.pass(check);
                            }
                          }
                          : null,
                      failed));
      return itsOwn ? withBooleans(rows, booleans, booleanType) : rows;
    }

    /** Ends the transaction a checked read ran in, in autocommit, and restores autocommit. */
    private void endOwn(boolean commit, int fetchSize) throws SQLException {
      try {
        if (commit) {
          owner.vendor.commit();
        } else {
          owner.vendor.rollback();
          owner.router.rolledBack(false);
        }
      } finally {
        owner.router.transactionEnded();
        owner.vendor.setAutoCommit(true);
        if (owner.router.engine() == Engine.ORACLE) {
          owner.vendor.setTransactionIsolation(
              owner.router.sessionLevel(owner.router.isolation(), true));
        }
        vendor().setFetchSize(fetchSize);
      }
    }

    /**
     * Closes the vendor statement's current result set, if any: ojdbc fails to report none for a
     * statement not executed yet (ORA-17144).
     */
    private void closeCurrentResult() throws SQLException {
      ResultSet open;
      try {
        open = vendor().getResultSet();
      } catch (SQLException e) {
        return;
      }
      if (open != null) {
        open.close();
      }
    }

    /** Runs a write the driver plans, and answers as the vendor's statement would. */
    private @Nullable Object runWrite(
        Object proxy, String name, @Nullable Object[] args, Router.Route route)
        throws SQLException {
      Writes.Plan plan = Objects.requireNonNull(route.write);
      BooleanType booleanType = owner.booleanType(route.booleans);
      if (name.equals("executeQuery") && !plan.returnsRows()) {
        throw new SQLException("the statement returns no result set", "02000");
      }
      boolean bound = args == null || args.length == 0 || !(args[0] instanceof String);
      String keys =
          bound ? keysAsked : asksForGeneratedKeys(args) ? Writes.keysAsked(plan, args[1]) : null;
      // an execute closes the statement's current result set
      closeCurrentResult();
      Writes.Keys inserted = new Writes.Keys();
      Out out = new Out();
      Writes.Context context = writeContext(bound ? parameters : null, inserted, out, keys != null);
      long[] count = {0};
      try {
        Writes.within(
            owner.vendor,
            owner.router.readOnly(),
            () -> owner.rollbackOnly = true,
            plan.writes(),
            () -> {
              count[0] = Writes.apply(owner.vendor, plan, context);
              if (keys != null && out.rows == null) {
                out.rows = Writes.returned(owner.vendor, plan, keys, inserted);
              }
            });
      } catch (SQLException | RuntimeException e) {
        if (out.rows != null) {
          out.rows.close();
        }
        throw e;
      } finally {
        // it ran in the transaction, as a routed statement does (todo 219)
        owner.ran(route);
      }
      writeReturns = plan.returnsRows();
      writeCount = writeReturns ? -1L : count[0];
      writeResult =
          out.rows == null
              ? null
              : (ResultSet)
                  withBooleans(
                      driverRows(proxy, plan, out.rows, out.hidden), route.booleans, booleanType);
      // with its own RETURNING, the generated keys asked for are its rows, as pgjdbc returns them
      writeKeys = keys == null ? null : writeResult;
      switch (name) {
        case "executeQuery":
          return writeResult;
        case "executeUpdate":
          return (int) Math.min(count[0], Integer.MAX_VALUE);
        case "executeLargeUpdate":
          return count[0];
        default:
          return writeReturns; // execute: a result set for rows, an update count otherwise
      }
    }

    /**
     * The rows the driver read for the application, the last {@code hidden} columns its own;
     * closing them closes the statement when the application asked for that (todo 155).
     */
    private ResultSet driverRows(Object proxy, Writes.Plan plan, ResultSet rows, int hidden)
        throws SQLException {
      AnchoredHandler h =
          new AnchoredHandler(
              rows,
              connection,
              (Statement) proxy,
              new Router.Anchored(plan.n.table, plan.n.keyColumns.size(), hidden),
              null,
              0,
              () -> {},
              null,
              UnaryOperator.identity());
      h.closesStatement = vendor().isCloseOnCompletion();
      return (ResultSet) proxy(ResultSet.class, h);
    }

    /**
     * What a write needs from this statement: its values bound from {@code parameters}, the keys it
     * inserts added to {@code inserted}, the rows it returns handed to {@code out}.
     */
    private Writes.Context writeContext(
        @Nullable Map<Integer, Call> parameters,
        Writes.Keys inserted,
        @Nullable Out out,
        boolean wantsKeys) {
      // a locking read anchors its rows as an anchored read does, in a transaction only
      boolean recording;
      boolean open;
      try {
        open = !owner.vendor.getAutoCommit();
        recording = open && !owner.router.readOnly();
      } catch (SQLException e) {
        open = false;
        recording = false;
      }
      boolean records = recording;
      boolean inTransaction = open;
      long epoch = owner.anchors.epoch();
      return new Writes.Context() {
        @Override
        public void bind(PreparedStatement ps, int position, int placeholder, boolean asStored)
            throws SQLException {
          Call c = parameters == null ? null : parameters.get(placeholder);
          if (asStored && c != null && c.args.length > 1 && c.args[1] instanceof Float) {
            // Connector/J sends a float as its shortest decimal text, which no stored float equals
            ps.setDouble(position, ((Float) c.args[1]).doubleValue());
            return;
          }
          StatementHandler.bind(ps, position, placeholder, c);
        }

        @Override
        public @Nullable String plsqlType(int placeholder) {
          Call c = parameters == null ? null : parameters.get(placeholder);
          return c == null ? null : Writes.plsqlType(c.method.getName(), c.args);
        }

        @Override
        public int queryTimeout() throws SQLException {
          return vendor().getQueryTimeout();
        }

        @Override
        public Duration inflightWait() {
          // a fixed snapshot is not refreshed by waiting: at SNAPSHOT and SERIALIZABLE a row in
          // flight aborts at once (design section 6)
          return owner.router.snapshot() ? Duration.ZERO : owner.inflightWait;
        }

        @Override
        public boolean cancelled() {
          return cancelled;
        }

        @Override
        public void running(@Nullable Statement s) {
          runningWrite = s;
        }

        @Override
        public Anchors anchors() {
          return owner.anchors;
        }

        @Override
        public void anchor(String table, Object[] key, Anchors.Version version) {
          if (records) {
            owner.anchors.read(epoch, table, key, version);
          }
        }

        @Override
        public boolean passed(String check) {
          return inTransaction && owner.router.passed(check);
        }

        @Override
        public void pass(String check) {
          if (inTransaction && owner.anchors.epoch() == epoch) {
            owner.router.pass(check);
          }
        }

        @Override
        public void inserted(Writes.Keys keys) {
          inserted.addAll(keys);
        }

        @Override
        public boolean wantsKeys() {
          return wantsKeys;
        }

        @Override
        public void rows(ResultSet rows, int hidden) {
          if (out == null) {
            throw new IllegalStateException("rows from a batch entry");
          }
          out.rows = rows;
          out.hidden = hidden;
        }
      };
    }

    /**
     * Runs a batch the driver runs itself, one that holds a write: each entry in order, in one
     * transaction of its own in autocommit mode (design 4.4). A failure is a {@link
     * BatchUpdateException} with the counts of the entries before it, its next exception the
     * failure itself.
     */
    // ponytail: on PostgreSQL entries that are one statement each go together, 16 to a round trip
    // (todo 329); otherwise entry by entry, a round trip per statement of each
    private Object runBatch(
        Object proxy,
        String name,
        int size,
        IntFunction<Router.Route> routes,
        IntFunction<@Nullable Map<Integer, Call>> parameters,
        @Nullable String keys)
        throws SQLException {
      closeCurrentResult();
      for (int i = 0; i < size; i++) {
        Writes.Plan plan = routes.apply(i).write;
        if (plan != null && plan.returnsRows()) {
          throw Errors.unsupported(
              "a statement that returns rows (RETURNING, a locking read) cannot be batched; run it"
                  + " alone");
        }
      }
      long[] counts = new long[size];
      int[] done = {0};
      Writes.Keys inserted = new Writes.Keys();
      Writes.Plan[] last = {null};
      Out out = new Out();
      boolean auto = owner.vendor.getAutoCommit();
      boolean sendsTogether = owner.sendsTogether();
      try {
        Writes.within(
            owner.vendor,
            owner.router.readOnly(),
            () -> owner.rollbackOnly = true,
            true,
            () -> {
              // up to here a merged statement failed: not merged again, but sent as below
              int unmerged = 0;
              for (int i = 0; i < size; ) {
                // the next entries of one plan with a merged form, as one statement (todo 329)
                Writes.Plan first = routes.apply(i).write;
                // on Oracle, consecutive parameter sets of one write as one block of entries, or
                // through the vendor's own batching where the write is one plain statement and no
                // generated keys are asked for (design 4.4 "Batches on Oracle", todo 334)
                if (i >= unmerged
                    && first != null
                    && (first.entries() != null || (first.vendorBatch() != null && keys == null))) {
                  boolean block = first.entries() != null;
                  int max = block ? Writes.ENTRIES_MAX : Writes.MERGE_MAX;
                  int same = i;
                  while (same < size
                      && same - i < max
                      && Objects.equals(routes.apply(same).write, first)) {
                    same++;
                  }
                  if (same - i > 1) {
                    List<Writes.Context> contexts = new ArrayList<>();
                    for (int j = i; j < same; j++) {
                      contexts.add(writeContext(parameters.apply(j), inserted, null, keys != null));
                    }
                    int at = i;
                    Writes.Finalized finalized =
                        (j, count) -> {
                          counts[at + j] = count;
                          owner.ran(routes.apply(at + j));
                          done[0]++;
                        };
                    if (block) {
                      int finalizedCount =
                          Writes.applyOracleEntries(owner.vendor, first, contexts, finalized);
                      if (finalizedCount > 0) {
                        last[0] = first;
                        i += finalizedCount;
                        continue;
                      }
                      if (finalizedCount < 0) {
                        unmerged = same; // the block failed, undone whole: one by one from here
                      }
                      // 0: the first entry's outcome is the single form's to make, below
                    } else {
                      Writes.applyVendorBatch(owner.vendor, first, contexts, finalized);
                      last[0] = first;
                      i = same;
                      continue;
                    }
                  }
                }
                Writes.Plan merging =
                    sendsTogether && i >= unmerged && first != null && first.merge() != null
                        ? first
                        : null;
                int same = i;
                while (merging != null
                    && same < size
                    && same - i < Writes.MERGE_MAX
                    && Objects.equals(routes.apply(same).write, merging)) {
                  same++;
                }
                int end = i;
                if (merging != null && same - i > 1) {
                  List<Writes.Context> contexts = new ArrayList<>();
                  for (int j = i; j < same; j++) {
                    contexts.add(writeContext(parameters.apply(j), inserted, null, keys != null));
                  }
                  long[] got =
                      Writes.applyMerged(
                          owner.vendor,
                          merging,
                          contexts,
                          owner.together,
                          () -> owner.together = true); // set once sent, undone to or not
                  if (got != null) {
                    for (int j = i; j < same; j++) {
                      counts[j] = got[j - i];
                      owner.ran(routes.apply(j));
                      done[0]++;
                    }
                    last[0] = merging;
                    i = same;
                    continue;
                  }
                  // sent together instead, where each entry sees the ones before it, as for two
                  // on one key; one by one if that needs more
                  unmerged = same;
                  end = Math.min(same, i + Writes.TOGETHER_MAX);
                }
                while (end == i && sendsTogether && end < size && end - i < Writes.TOGETHER_MAX) {
                  Writes.Plan p = routes.apply(end).write;
                  if (p == null || p.together() == null) {
                    break;
                  }
                  end++;
                }
                if (end - i > 1) {
                  List<Writes.Plan> plans = new ArrayList<>();
                  List<Writes.Context> contexts = new ArrayList<>();
                  for (int j = i; j < end; j++) {
                    plans.add(Objects.requireNonNull(routes.apply(j).write));
                    contexts.add(writeContext(parameters.apply(j), inserted, null, keys != null));
                  }
                  long[] got =
                      Writes.applyTogether(
                          owner.vendor,
                          plans,
                          contexts,
                          owner.together,
                          () -> owner.together = true); // set once sent, undone to or not
                  if (got != null) {
                    for (int j = i; j < end; j++) {
                      counts[j] = got[j - i];
                      last[0] = plans.get(j - i);
                      owner.ran(routes.apply(j));
                      done[0]++;
                    }
                    i = end;
                    continue;
                  }
                } else {
                  end = i + 1;
                }
                for (; i < end; i++) {
                  Router.Route route = routes.apply(i);
                  if (route.write != null) {
                    last[0] = route.write;
                    try {
                      counts[i] =
                          Writes.apply(
                              owner.vendor,
                              route.write,
                              writeContext(parameters.apply(i), inserted, null, keys != null));
                    } finally {
                      owner.ran(route); // as runWrite (todo 231)
                    }
                  } else {
                    counts[i] = runPlain(route);
                  }
                  done[0]++;
                }
              }
              if (keys != null && last[0] != null) {
                out.rows = Writes.returned(owner.vendor, last[0], keys, inserted);
              }
            });
      } catch (SQLException e) {
        if (out.rows != null) {
          out.rows.close();
        }
        BatchUpdateException b =
            new BatchUpdateException(
                e.getMessage(),
                e.getSQLState(),
                e.getErrorCode(),
                Arrays.copyOf(counts, done[0]),
                e);
        b.setNextException(e);
        throw b;
      } finally {
        if (auto) {
          owner.together = false; // the batch's own transaction ended it
        }
      }
      writeCount = -1L; // no update count of its own, as after a vendor batch
      writeReturns = false;
      writeKeys = out.rows == null ? null : driverRows(proxy, last[0], out.rows, 0);
      if (name.equals("executeLargeBatch")) {
        return counts;
      }
      int[] ints = new int[size];
      for (int i = 0; i < size; i++) {
        ints[i] = (int) Math.min(counts[i], Integer.MAX_VALUE);
      }
      return ints;
    }

    /** Runs a batch entry that is not a write of a ScalarDB table, as the vendor batch would. */
    @SuppressFBWarnings(
        value = "SQL_INJECTION_JDBC",
        justification = "the application's own statement, as the vendor batch would run it")
    private long runPlain(Router.Route route) throws SQLException {
      owner.router.beforeRun(owner.vendor, List.of(route));
      try {
        return vendor().executeLargeUpdate(route.sql);
      } finally {
        owner.ran(route);
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
          && (old.parameterSources != null
              ? old.parameterSources.equals(now.parameterSources)
              : now.parameterSources == null && plain(old) && plain(now) && !streamBound());
    }

    /**
     * Whether a route with its placeholders as the application's runs as the vendor statement
     * alone: no checks nor anchors, as the locked form of a plain read at SERIALIZABLE (todo 242).
     */
    private static boolean plain(Router.Route r) {
      return r.checks.isEmpty() && r.anchored == null && r.write == null;
    }

    /** Binds an application's value, as it was set, at {@code position} of a driver statement. */
    private static void bind(PreparedStatement ps, int position, int placeholder, @Nullable Call c)
        throws SQLException {
      if (c == null) {
        throw new SQLException("no value is bound to parameter " + placeholder, "07001");
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

    /**
     * Whether a bound value can be read once only: a stream, or a LOB, SQLXML, array, struct or
     * ref, which may be backed by one.
     */
    private boolean boundOnce() {
      for (Call c : parameters.values()) {
        for (Object a : c.args) {
          if (a instanceof InputStream
              || a instanceof Reader
              || a instanceof java.sql.Blob
              || a instanceof java.sql.Clob
              || a instanceof java.sql.SQLXML
              || a instanceof java.sql.Array
              || a instanceof java.sql.Struct
              || a instanceof java.sql.Ref) {
            return true;
          }
        }
      }
      return false;
    }

    /** Whether a bound value is a stream, which the vendor statement consumed and cannot replay. */
    private boolean streamBound() {
      for (Call c : parameters.values()) {
        for (Object a : c.args) {
          if (a instanceof InputStream || a instanceof Reader) {
            return true;
          }
        }
      }
      return false;
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
        replay(route, false);
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
      Statement other = alternate;
      alternate = null;
      plainNow = false;
      if (other != null) {
        other.close(); // prepared for a text that no longer is the route's
      }
    }

    /**
     * Makes the vendor statement of the route's statement as written over the data tables the
     * target, or the rewritten one again (todo 329): prepared once, its settings replayed then; its
     * parameters replayed as it becomes the target, the other's cleared.
     */
    private void switchTo(boolean plain, Router.Route route) throws Throwable {
      if (plainNow == plain) {
        return;
      }
      Statement other = alternate;
      if (other == null) {
        Object[] args = Objects.requireNonNull(preparedArgs).clone();
        args[0] = plain ? Objects.requireNonNull(route.plainSql) : route.sql;
        other = (Statement) created(owner.vendor, Objects.requireNonNull(preparedWith), args);
        try {
          for (Call c : settings.values()) {
            forward(other, c.method, c.args);
          }
        } catch (Throwable t) {
          try {
            other.close();
          } catch (SQLException e) {
            t.addSuppressed(e);
          }
          throw t;
        }
      }
      Statement was = vendor();
      target = other;
      alternate = was;
      plainNow = plain;
      if (other instanceof PreparedStatement) {
        ((PreparedStatement) other).clearParameters();
      }
      replay(route, plain);
    }

    /** Binds the parameters set so far onto the target: as written, or as the split read takes. */
    private void replay(Router.Route route, boolean plain) throws Throwable {
      for (Call c : parameters.values()) {
        if (plain || route.parameterSources == null) {
          forward(vendor(), c.method, c.args);
        } else {
          bind(route, c.method, c.args);
        }
      }
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
   * The result set of an anchored read: the application sees its own columns only, and each row it
   * moves to in a transaction records the row's key and version (design 4.2).
   */
  private static final class AnchoredHandler extends Handler {
    final Connection connection;
    final Statement statement;
    final Router.Anchored anchored;
    final @Nullable Anchors anchors;
    final long epoch;
    // marks the transaction rollback-only when a row in flight aborts the read
    final Runnable aborts;
    // records that a table's coarse check would find no row in the transaction's snapshot, which
    // a state of 0 says (todo 329); null outside a transaction
    final @Nullable Consumer<String> passes;
    // what a vendor error the rows bring means for the transaction
    final UnaryOperator<SQLException> failed;
    int visible = -1;
    // closing the rows closes the statement: a driver's rows under closeOnCompletion (todo 155)
    boolean closesStatement;
    // per table, whether its state column said so already
    final boolean[] passed;

    AnchoredHandler(
        Object vendor,
        Connection connection,
        Statement statement,
        Router.Anchored anchored,
        @Nullable Anchors anchors,
        long epoch,
        Runnable aborts,
        @Nullable Consumer<String> passes,
        UnaryOperator<SQLException> failed) {
      super(vendor);
      this.connection = connection;
      this.statement = statement;
      this.anchored = anchored;
      this.anchors = anchors;
      this.epoch = epoch;
      this.aborts = aborts;
      this.passes = passes;
      this.failed = failed;
      this.passed = new boolean[anchored.tables.size()];
    }

    @Override
    void handedOut() throws SQLException {
      if (anchored.states) {
        // the vendor's rows would skip the check of each row's state
        throw Errors.unsupported(
            "a result set read at SNAPSHOT or SERIALIZABLE cannot be unwrapped: each row is checked"
                + " as the application moves to it");
      }
    }

    private int visible() throws SQLException {
      if (visible < 0) {
        visible = ((ResultSet) target).getMetaData().getColumnCount() - anchored.hidden();
      }
      return visible;
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      String name = method.getName();
      switch (name) {
        case "getConnection":
          return connection;
        case "getStatement":
          return statement;
        case "close":
          {
            Object r = forward(target, method, args);
            if (closesStatement) {
              closesStatement = false;
              statement.close();
            }
            return r;
          }
        case "getMetaData":
          return proxy(
              java.sql.ResultSetMetaData.class,
              new HiddenColumns(created(target, method, args), anchored.hidden()));
        case "findColumn":
          {
            int i = (Integer) Objects.requireNonNull(forward(target, method, args));
            if (i > visible()) {
              throw new SQLException("no column " + args[0] + " in the result", "42703");
            }
            return i;
          }
        default:
          break;
      }
      if ((name.startsWith("get") || name.startsWith("update"))
          && args != null
          && args.length > 0
          && (args[0] instanceof Integer || args[0] instanceof String)) {
        // a hidden column by index, or by its label: the vendor resolves a label to its first
        // column, so an application column of the same label stays reachable (todo 144)
        int i =
            args[0] instanceof Integer
                ? (Integer) args[0]
                : ((ResultSet) target).findColumn((String) args[0]);
        if (i > visible()) {
          throw new SQLException("no column " + args[0] + " in the result", "22023");
        }
      }
      Object result;
      try {
        result = forward(target, method, args);
      } catch (SQLException e) {
        throw failed.apply(e);
      }
      if ((anchors != null || anchored.states)
          && Boolean.TRUE.equals(result)
          && (name.equals("next")
              || name.equals("previous")
              || name.equals("first")
              || name.equals("last")
              || name.equals("absolute")
              || name.equals("relative"))) {
        ResultSet rs = (ResultSet) target;
        int width = anchored.width();
        if (anchored.states) {
          // at SNAPSHOT and SERIALIZABLE a row in flight aborts the read (design 4.2)
          for (int t = 0, at = visible() + 1; t < anchored.tables.size(); t++) {
            int keys = anchored.keys.get(t);
            Object state = rs.getObject(at + keys + width - 1);
            if (Writes.inFlight(state)) {
              aborts.run();
              throw new Writes.Aborted(
                  "a row the read returns is being written by a ScalarDB transaction");
            }
            if (passes != null
                && !passed[t]
                && state != null
                && ((Number) state).intValue() == 0
                && !anchored.checks.get(t).isEmpty()) {
              passed[t] = true; // the table has no row in flight in the snapshot (todo 329)
              passes.accept(anchored.checks.get(t));
            }
            at += keys + width;
          }
        }
        for (int t = 0, at = visible() + 1; anchors != null && t < anchored.tables.size(); t++) {
          int keys = anchored.keys.get(t);
          Object[] key = Writes.readKey(rs, at, keys); // null on a LEFT JOIN's NULL-extended side
          if (key != null) {
            anchors.read(
                epoch,
                anchored.tables.get(t),
                key,
                new Anchors.Version(rs.getString(at + keys), rs.getObject(at + keys + 1)));
          }
          at += keys + width;
        }
      }
      return result instanceof ResultSet
          ? proxy(ResultSet.class, new ChildHandler(result, connection, null))
          : result;
    }
  }

  /** What the vendor reports for a BOOLEAN column of a ScalarDB table read as it is (todo 304). */
  static final class BooleanType {
    final int type;
    final String typeName;
    final String className;
    final int precision;
    final int scale;
    final int displaySize;
    final boolean signed;

    BooleanType(java.sql.ResultSetMetaData md) throws SQLException {
      type = md.getColumnType(1);
      typeName = md.getColumnTypeName(1);
      className = md.getColumnClassName(1);
      precision = md.getPrecision(1);
      scale = md.getScale(1);
      displaySize = md.getColumnDisplaySize(1);
      signed = md.isSigned(1);
    }
  }

  /** Rows whose BOOLEAN columns of ScalarDB tables read as the columns themselves do. */
  private static Object withBooleans(
      Object rows, Router.@Nullable Booleans b, @Nullable BooleanType t) {
    return b == null || t == null ? rows : proxy(ResultSet.class, new BooleanColumns(rows, b, t));
  }

  private static Object booleanMetaData(
      Object md, Router.@Nullable Booleans b, @Nullable BooleanType t) {
    return b == null || t == null
        ? md
        : proxy(java.sql.ResultSetMetaData.class, new BooleanMetaData(md, b, t));
  }

  /**
   * The BOOLEAN columns of ScalarDB tables that a UNION ALL widened on MySQL and MariaDB, read as
   * the vendor reads such a column on its own: {@code getObject} a {@code Boolean} where it returns
   * one, and the column's metadata (todo 304).
   */
  private static final class BooleanColumns extends Handler {
    final Router.Booleans booleans;
    final BooleanType type;

    BooleanColumns(Object rows, Router.Booleans booleans, BooleanType type) {
      super(rows);
      this.booleans = booleans;
      this.type = type;
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      String name = method.getName();
      if (name.equals("getMetaData")) {
        Object md = forward(target, method, args);
        return md == null ? null : booleanMetaData(md, booleans, type);
      }
      if (name.equals("getObject")
          && args != null
          && args.length == 1
          && type.className.equals(Boolean.class.getName())) {
        ResultSet rs = (ResultSet) target;
        int i = args[0] instanceof Integer ? (Integer) args[0] : rs.findColumn((String) args[0]);
        if (booleans.columns.contains(i)) {
          boolean v = rs.getBoolean(i);
          return rs.wasNull() ? null : v;
        }
      }
      return forward(target, method, args);
    }
  }

  /** The metadata of {@link BooleanColumns}. */
  private static final class BooleanMetaData extends Handler {
    final Router.Booleans booleans;
    final BooleanType type;

    BooleanMetaData(Object md, Router.Booleans booleans, BooleanType type) {
      super(md);
      this.booleans = booleans;
      this.type = type;
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      if (args != null
          && args.length == 1
          && args[0] instanceof Integer
          && booleans.columns.contains((Integer) args[0])) {
        switch (method.getName()) {
          case "getColumnType":
            return type.type;
          case "getColumnTypeName":
            return type.typeName;
          case "getColumnClassName":
            return type.className;
          case "getPrecision":
            return type.precision;
          case "getScale":
            return type.scale;
          case "getColumnDisplaySize":
            return type.displaySize;
          case "isSigned":
            return type.signed;
          default:
            break;
        }
      }
      return forward(target, method, args);
    }
  }

  /** Result set metadata without the trailing columns the driver added. */
  private static final class HiddenColumns extends Handler {
    final int hidden;

    HiddenColumns(Object vendor, int hidden) {
      super(vendor);
      this.hidden = hidden;
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      int count = ((java.sql.ResultSetMetaData) target).getColumnCount() - hidden;
      if (method.getName().equals("getColumnCount")) {
        return count;
      }
      if (args != null
          && args.length == 1
          && args[0] instanceof Integer
          && (Integer) args[0] > count) {
        throw new SQLException("the column index " + args[0] + " is out of range", "22023");
      }
      return forward(target, method, args);
    }
  }

  /**
   * Oracle's database metadata: the driver's levels, REPEATABLE READ, which runs at Oracle's
   * SERIALIZABLE, supported, and SERIALIZABLE not (design 5); and {@code getIndexInfo} answered
   * from the dictionary alone for a table whose name is not all upper case. Asked for exact
   * statistics ({@code approximate} false), ojdbc first gathers them, {@code
   * DBMS_STATS.GATHER_TABLE_STATS} with the name unquoted, which Oracle folds to upper case, so the
   * call fails with ORA-20000 for a table created quoted in lower case, as the driver creates
   * ScalarDB's metadata tables; a tool that walks the schema's tables, BenchBase among them, fails
   * with it. The index columns, uniqueness and ordering the call reports do not depend on the
   * statistics; only the cardinality and pages columns may be as of the last gathering.
   */
  private static final class OracleMetaData extends ChildHandler {
    OracleMetaData(Object vendor, Connection connection) {
      super(vendor, connection, null);
    }

    @Override
    @Nullable Object handle(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
      if (method.getName().equals("supportsTransactionIsolationLevel")) {
        int level = (Integer) Objects.requireNonNull(args)[0];
        return level == Connection.TRANSACTION_READ_COMMITTED
            || level == Connection.TRANSACTION_REPEATABLE_READ;
      }
      if (method.getName().equals("getIndexInfo")
          && args != null
          && args.length == 5
          && Boolean.FALSE.equals(args[4])
          && args[2] instanceof String
          && !((String) args[2]).equals(((String) args[2]).toUpperCase(Locale.ROOT))) {
        Object[] approximate = args.clone();
        approximate[4] = Boolean.TRUE;
        return super.handle(proxy, method, approximate);
      }
      return super.handle(proxy, method, args);
    }
  }

  /**
   * Result sets and metadata: they hand back the connection and the statement, and wrap any result
   * set they return, such as one from a {@code DatabaseMetaData} query or a PostgreSQL refcursor,
   * whose own statement is then null rather than the vendor's.
   */
  private static class ChildHandler extends Handler {
    final Connection connection;
    final @Nullable Statement statement;
    final UnaryOperator<SQLException> failed;

    ChildHandler(Object vendor, Connection connection, @Nullable Statement statement) {
      this(vendor, connection, statement, UnaryOperator.identity());
    }

    ChildHandler(
        Object vendor,
        Connection connection,
        @Nullable Statement statement,
        UnaryOperator<SQLException> failed) {
      super(vendor);
      this.connection = connection;
      this.statement = statement;
      this.failed = failed;
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
            Object result;
            try {
              result = forward(target, method, args);
            } catch (SQLException e) {
              throw failed.apply(e);
            }
            return result instanceof ResultSet
                ? proxy(ResultSet.class, new ChildHandler(result, connection, null))
                : result;
          }
      }
    }
  }
}
