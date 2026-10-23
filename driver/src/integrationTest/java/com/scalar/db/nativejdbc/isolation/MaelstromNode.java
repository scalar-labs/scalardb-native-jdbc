package com.scalar.db.nativejdbc.isolation;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Result;
import com.scalar.db.api.TransactionCrudOperable;
import com.scalar.db.api.TransactionState;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Upsert;
import com.scalar.db.exception.transaction.CommitConflictException;
import com.scalar.db.exception.transaction.CommitException;
import com.scalar.db.exception.transaction.CrudConflictException;
import com.scalar.db.exception.transaction.CrudException;
import com.scalar.db.exception.transaction.PreparationConflictException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import com.scalar.db.exception.transaction.ValidationConflictException;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import com.scalar.db.transaction.consensuscommit.CoordinatorException;
import com.scalar.db.transaction.consensuscommit.CoordinatorStateAccessor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * A Maelstrom node for the {@code txn-list-append} and {@code txn-rw-register} workloads (testing
 * plan T5): node {@code n0} runs each transaction through the driver, the others through ScalarDB,
 * on one ScalarDB table {@code (k INT, v TEXT)} whose {@code v} holds a key's list as {@code
 * ,1,2,3}, or its register's value. A write is blind: neither side reads the key first. Started by
 * {@link MaelstromIntegrationTest} with the test's database as the fixture's external one ({@code
 * -Dnativejdbc.<engine>.url}), so that {@link Engines} opens the driver and ScalarDB on it as in
 * every other test, and with:
 *
 * <ul>
 *   <li>{@code iso.engine}: the {@link Engines} constant;
 *   <li>{@code iso.workload}: {@code txn-list-append}, the default, or {@code txn-rw-register};
 *   <li>{@code iso.table}: the table as SQL names it;
 *   <li>{@code iso.namespace}, {@code iso.kv}, {@code iso.k}, {@code iso.v}: its names as ScalarDB
 *       stores them;
 *   <li>{@code iso.concat}: the SQL that appends {@code ?} to {@code v};
 *   <li>{@code iso.level}: the driver's {@code setTransactionIsolation} level;
 *   <li>{@code iso.drivers}: the nodes that run through the driver, {@code n0} by default;
 *   <li>{@code iso.vendor}: {@code true} to run the driver's nodes on a plain vendor connection
 *       instead, which runs no protocol;
 *   <li>{@code iso.prop.*}: ScalarDB properties, without the prefix;
 *   <li>{@code iso.faults}: the share of transactions that meet a fault, 0 by default.
 * </ul>
 *
 * <p>A conflict is a definite abort ({@code txn-conflict}), any other failure before commit too
 * ({@code abort}); a failed commit whose outcome is not known is indefinite ({@code crash}).
 *
 * <p>Faults (testing plan T5), each kind in half the share: on ScalarDB's side, every transaction
 * is a two-phase one, and a faulty one is left after its prepare, without a decision or with its
 * coordinator state COMMITTED, for lazy recovery to abort or to roll forward; either is indefinite.
 * On the driver's, the connection is dropped ({@link Connection#abort}) before a statement or the
 * commit, a definite abort, or while the commit runs, indefinite; and after a failed statement it
 * is closed without a rollback.
 */
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "PREDICTABLE_RANDOM"
    },
    justification = "the table's name and the append, from the test; faults drawn at random")
public final class MaelstromNode {

  private static final long NOT_SUPPORTED = 10;
  private static final long CRASH = 13;
  private static final long ABORT = 14;
  private static final long TXN_CONFLICT = 30;

  private final PrintStream out;
  private final Engines engine;
  private final String table;
  private final String namespace;
  private final String kv;
  private final String k;
  private final String v;
  private final String concat;
  private final int level;
  private final boolean vendor = Boolean.getBoolean("iso.vendor");
  private final boolean register =
      "txn-rw-register".equals(System.getProperty("iso.workload", "txn-list-append"));
  private final double faults = Double.parseDouble(System.getProperty("iso.faults", "0"));
  private final Properties scalarDb = new Properties();
  private final ExecutorService workers = Executors.newCachedThreadPool();
  private static final ThreadLocal<@Nullable Connection> CONNECTIONS = new ThreadLocal<>();
  private volatile @Nullable String nodeId;
  private volatile @Nullable DistributedTransactionManager manager;
  private volatile @Nullable TwoPhaseCommitTransactionManager twoPhase;
  private volatile @Nullable CoordinatorStateAccessor coordinator;

  private MaelstromNode(PrintStream out) {
    this.out = out;
    engine = Engines.valueOf(property("iso.engine"));
    table = property("iso.table");
    namespace = property("iso.namespace");
    kv = property("iso.kv");
    k = property("iso.k");
    v = property("iso.v");
    concat = property("iso.concat");
    level = Integer.parseInt(property("iso.level"));
    for (String name : System.getProperties().stringPropertyNames()) {
      if (name.startsWith("iso.prop.")) {
        scalarDb.setProperty(name.substring("iso.prop.".length()), property(name));
      }
    }
  }

  public static void main(String[] args) throws Exception {
    // stdout is Maelstrom's: the logs, ScalarDB's among them, go to stderr
    PrintStream out = new PrintStream(System.out, false, StandardCharsets.UTF_8);
    System.setOut(System.err);
    new MaelstromNode(out).run();
  }

  private static String property(String name) {
    return Objects.requireNonNull(System.getProperty(name), name);
  }

  private void run() throws Exception {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    for (String line = in.readLine(); line != null; line = in.readLine()) {
      Map<?, ?> message = (Map<?, ?>) Objects.requireNonNull(Json.parse(line));
      Map<?, ?> body = (Map<?, ?>) Objects.requireNonNull(message.get("body"));
      String type = String.valueOf(body.get("type"));
      if (type.equals("init")) {
        String id = String.valueOf(body.get("node_id"));
        nodeId = id;
        if (!List.of(System.getProperty("iso.drivers", "n0").split(",")).contains(id)) {
          TransactionFactory f = engine.scalarDb(scalarDb);
          if (faults > 0) {
            twoPhase = f.getTwoPhaseCommitTransactionManager();
            // a pool as small as the fixture's: ScalarDB's 20 idle connections, with the
            // managers', took PostgreSQL's 100 and left the driver's node none
            Properties p = new Properties();
            p.putAll(scalarDb);
            p.setProperty("scalar.db.storage", "jdbc");
            p.setProperty("scalar.db.contact_points", engine.url());
            p.setProperty("scalar.db.username", engine.username());
            p.setProperty("scalar.db.password", engine.password());
            p.setProperty("scalar.db.jdbc.connection_pool.min_idle", "1");
            p.setProperty("scalar.db.jdbc.table_metadata.connection_pool.min_idle", "1");
            p.setProperty("scalar.db.jdbc.admin.connection_pool.min_idle", "1");
            coordinator = new CoordinatorStateAccessor(StorageFactory.create(p).getStorage());
          } else {
            manager = f.getTransactionManager();
          }
        }
        reply(message, body("init_ok"));
      } else if (type.equals("txn")) {
        List<?> ops = (List<?>) Objects.requireNonNull(body.get("txn"));
        workers.execute(() -> reply(message, txn(ops)));
      } else {
        reply(message, error(NOT_SUPPORTED, "not supported: " + type));
      }
    }
    workers.shutdown();
  }

  private Map<String, @Nullable Object> txn(List<?> ops) {
    DistributedTransactionManager m = manager;
    TwoPhaseCommitTransactionManager t = twoPhase;
    return t != null ? twoPhase(t, ops) : m != null ? scalarDb(m, ops) : driver(ops);
  }

  /** Whether this transaction meets a fault of a kind that takes half the share. */
  private boolean fault() {
    return faults > 0 && ThreadLocalRandom.current().nextDouble() < faults / 2;
  }

  // the driver's side

  private Map<String, @Nullable Object> driver(List<?> ops) {
    Connection c;
    try {
      c = connection();
    } catch (SQLException e) {
      return error(ABORT, "connect: " + e);
    }
    List<Object> done = new ArrayList<>();
    // the statement before which the connection is dropped, the commit at ops.size()
    int dropBefore = fault() ? ThreadLocalRandom.current().nextInt(ops.size() + 1) : -1;
    try {
      for (Object op : ops) {
        if (done.size() == dropBefore) {
          return dropped(c);
        }
        List<?> o = (List<?>) op;
        long key = (Long) Objects.requireNonNull(o.get(1));
        if ("r".equals(o.get(0))) {
          done.add(Arrays.asList("r", key, value(read(c, key))));
        } else if ("w".equals(o.get(0))) {
          write(c, key, (Long) Objects.requireNonNull(o.get(2)));
          done.add(o);
        } else {
          append(c, key, (Long) Objects.requireNonNull(o.get(2)));
          done.add(o);
        }
      }
    } catch (SQLException e) {
      if (fault()) {
        // as an application's try-with-resources does when the error propagates
        drop(c);
      } else {
        rollback(c);
      }
      return error(conflict(e) ? TXN_CONFLICT : ABORT, e.getSQLState() + " " + e.getMessage());
    }
    if (dropBefore == ops.size()) {
      return dropped(c);
    }
    if (fault()) {
      return droppedInCommit(c, done);
    }
    try {
      c.commit();
    } catch (SQLException e) {
      if (conflict(e)) {
        rollback(c);
        return error(TXN_CONFLICT, "commit: " + e.getSQLState() + " " + e.getMessage());
      }
      drop(c);
      return error(CRASH, "commit: " + e.getSQLState() + " " + e.getMessage());
    }
    return txnOk(done);
  }

  /** The connection dropped as a crashed client's, before the commit: the database rolls back. */
  private Map<String, @Nullable Object> dropped(Connection c) {
    try {
      c.abort(workers);
    } catch (SQLException e) {
      drop(c);
    }
    return error(ABORT, "fault: the connection dropped in the transaction");
  }

  /** The connection dropped while the commit runs: the outcome is not known unless it returned. */
  private Map<String, @Nullable Object> droppedInCommit(Connection c, List<Object> done) {
    Future<?> commit =
        workers.submit(
            () -> {
              c.commit();
              return null;
            });
    try {
      Thread.sleep(ThreadLocalRandom.current().nextInt(5));
      c.abort(workers);
      commit.get();
      return txnOk(done);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error(CRASH, "fault: interrupted in the commit");
    } catch (SQLException | ExecutionException e) {
      drop(c);
      return error(CRASH, "fault: the connection dropped in the commit: " + e);
    }
  }

  private Connection connection() throws SQLException {
    Connection c = CONNECTIONS.get();
    if (c == null || c.isClosed()) {
      Properties p = new Properties();
      p.setProperty("scalardbnative.catalogTtlMs", "60000");
      c = vendor ? engine.open() : engine.openDriver(p);
      c.setTransactionIsolation(level);
      c.setAutoCommit(false);
      CONNECTIONS.set(c);
    }
    return c;
  }

  private @Nullable String read(Connection c, long key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT v FROM " + table + " WHERE k = ?")) {
      ps.setLong(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private void write(Connection c, long key, long value) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET v = ? WHERE k = ?")) {
      ps.setString(1, String.valueOf(value));
      ps.setLong(2, key);
      if (ps.executeUpdate() == 1) {
        return;
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement("INSERT INTO " + table + " (k, v) VALUES (?, ?)")) {
      ps.setLong(1, key);
      ps.setString(2, String.valueOf(value));
      ps.executeUpdate();
    }
  }

  private void append(Connection c, long key, long element) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("UPDATE " + table + " SET v = " + concat + " WHERE k = ?")) {
      ps.setString(1, "," + element);
      ps.setLong(2, key);
      if (ps.executeUpdate() == 1) {
        return;
      }
    }
    try (PreparedStatement ps =
        c.prepareStatement("INSERT INTO " + table + " (k, v) VALUES (?, ?)")) {
      ps.setLong(1, key);
      ps.setString(2, "," + element);
      ps.executeUpdate();
    }
  }

  /** A serialization failure, or a key another transaction inserted first. */
  private static boolean conflict(SQLException e) {
    String state = e.getSQLState();
    return state != null && (state.startsWith("40") || state.startsWith("23"));
  }

  private static void rollback(Connection c) {
    try {
      c.rollback();
    } catch (SQLException e) {
      drop(c);
    }
  }

  private static void drop(Connection c) {
    try {
      c.close();
    } catch (SQLException ignored) {
      // replaced on the next transaction
    }
  }

  // ScalarDB's side

  private Map<String, @Nullable Object> scalarDb(DistributedTransactionManager m, List<?> ops) {
    DistributedTransaction tx;
    try {
      tx = m.begin();
    } catch (TransactionException e) {
      return error(ABORT, "begin: " + e);
    }
    List<Object> done;
    try {
      done = apply(tx, ops);
    } catch (TransactionException e) {
      abort(tx);
      return error(e instanceof CrudConflictException ? TXN_CONFLICT : ABORT, e.toString());
    }
    try {
      tx.commit();
    } catch (CommitConflictException e) {
      return error(TXN_CONFLICT, e.toString());
    } catch (UnknownTransactionStatusException e) {
      return error(CRASH, e.toString());
    } catch (CommitException e) {
      // not known to have rolled back
      return error(CRASH, e.toString());
    }
    return txnOk(done);
  }

  /** A two-phase transaction, which a fault leaves after its prepare. */
  private Map<String, @Nullable Object> twoPhase(TwoPhaseCommitTransactionManager m, List<?> ops) {
    TwoPhaseCommitTransaction tx;
    try {
      tx = m.begin();
    } catch (TransactionException e) {
      return error(ABORT, "begin: " + e);
    }
    List<Object> done;
    try {
      done = apply(tx, ops);
      tx.prepare();
      tx.validate();
    } catch (CrudConflictException | PreparationConflictException | ValidationConflictException e) {
      rollback(tx);
      return error(TXN_CONFLICT, e.toString());
    } catch (CrudException e) {
      rollback(tx);
      return error(ABORT, e.toString());
    } catch (TransactionException e) {
      // a prepare or a validation that failed otherwise: not known to have rolled back
      rollback(tx);
      return error(CRASH, e.toString());
    }
    if (fault()) {
      return error(CRASH, "fault: left after the prepare, undecided");
    }
    if (fault()) {
      try {
        Objects.requireNonNull(coordinator)
            .putState(
                new CoordinatorStateAccessor.State(
                    tx.getId(), TransactionState.COMMITTED, System.currentTimeMillis()));
      } catch (CoordinatorException e) {
        return error(CRASH, "fault: the decision not written: " + e);
      }
      return error(CRASH, "fault: left after the decision to commit");
    }
    try {
      tx.commit();
    } catch (CommitConflictException e) {
      return error(TXN_CONFLICT, e.toString());
    } catch (UnknownTransactionStatusException | CommitException e) {
      return error(CRASH, e.toString());
    }
    return txnOk(done);
  }

  /** The transaction's operations, what they return. */
  private List<Object> apply(TransactionCrudOperable tx, List<?> ops) throws CrudException {
    List<Object> done = new ArrayList<>();
    // the transaction's own writes, kept here rather than relying on Get to merge them
    Map<Long, Optional<String>> written = new HashMap<>();
    for (Object op : ops) {
      List<?> o = (List<?>) op;
      long key = (Long) Objects.requireNonNull(o.get(1));
      String f = String.valueOf(o.get(0));
      // a blind write reads nothing
      Optional<String> current =
          f.equals("w")
              ? Optional.empty()
              : written.containsKey(key) ? written.get(key) : get(tx, key);
      if (f.equals("r")) {
        done.add(Arrays.asList("r", key, value(current.orElse(null))));
      } else {
        String next =
            f.equals("w") ? String.valueOf(o.get(2)) : current.orElse("") + "," + o.get(2);
        tx.upsert(
            Upsert.newBuilder()
                .namespace(namespace)
                .table(kv)
                .partitionKey(Key.ofInt(k, Math.toIntExact(key)))
                .textValue(v, next)
                .build());
        written.put(key, Optional.of(next));
        done.add(o);
      }
    }
    return done;
  }

  private Optional<String> get(TransactionCrudOperable tx, long key) throws CrudException {
    Optional<Result> r =
        tx.get(
            Get.newBuilder()
                .namespace(namespace)
                .table(kv)
                .partitionKey(Key.ofInt(k, Math.toIntExact(key)))
                .build());
    return r.flatMap(x -> x.isNull(v) ? Optional.empty() : Optional.ofNullable(x.getText(v)));
  }

  private static void rollback(TwoPhaseCommitTransaction tx) {
    try {
      tx.rollback();
    } catch (TransactionException ignored) {
      // lazy recovery takes it
    }
  }

  private static void abort(DistributedTransaction tx) {
    try {
      tx.abort();
    } catch (TransactionException ignored) {
      // lazy recovery takes it
    }
  }

  // messages

  /** What a read returns: the register's value, or the list. */
  private @Nullable Object value(@Nullable String stored) {
    if (register) {
      return stored == null ? null : Long.valueOf(stored);
    }
    return list(stored);
  }

  private static @Nullable List<Long> list(@Nullable String value) {
    if (value == null) {
      return null;
    }
    List<Long> elements = new ArrayList<>();
    for (String element : value.split(",", -1)) {
      if (!element.isEmpty()) {
        elements.add(Long.valueOf(element));
      }
    }
    return elements;
  }

  private static Map<String, @Nullable Object> body(String type) {
    Map<String, @Nullable Object> b = new LinkedHashMap<>();
    b.put("type", type);
    return b;
  }

  private static Map<String, @Nullable Object> txnOk(List<Object> done) {
    Map<String, @Nullable Object> b = body("txn_ok");
    b.put("txn", done);
    return b;
  }

  private static Map<String, @Nullable Object> error(long code, String text) {
    Map<String, @Nullable Object> b = body("error");
    b.put("code", code);
    b.put("text", text.length() > 500 ? text.substring(0, 500) : text);
    return b;
  }

  private void reply(Map<?, ?> request, Map<String, @Nullable Object> body) {
    body.put(
        "in_reply_to", ((Map<?, ?>) Objects.requireNonNull(request.get("body"))).get("msg_id"));
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    m.put("src", nodeId);
    m.put("dest", request.get("src"));
    m.put("body", body);
    String line = Json.write(m);
    synchronized (out) {
      out.println(line);
      out.flush();
    }
    if ("error".equals(body.get("type"))) {
      System.err.println(nodeId + " error " + body.get("text"));
    }
  }
}
