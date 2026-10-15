package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 5 on PostgreSQL and both decoupled layouts: a driver transaction and a ScalarDB
 * transaction interleaved step by step on the same rows. Each fixed scenario is a row of design
 * section 9 that applies to READ COMMITTED, and its assertions name the rule that blocks the
 * anomaly; the randomized schedules increment counters from both sides and check that no committed
 * increment is lost.
 */
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "OBL_UNSATISFIED_OBLIGATION",
      "ODR_OPEN_DATABASE_RESOURCE",
      "PREDICTABLE_RANDOM"
    },
    justification =
        "statements written in the test sources, closed with their connection; schedules seeded"
            + " so that a failing one replays")
class InterleavingIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  /** Consensus Commit's isolation levels the ScalarDB side runs at, a table pair for each. */
  private static final List<String> ISOLATIONS = List.of("READ_COMMITTED", "SNAPSHOT");

  /** The counters of the randomized schedules. */
  private static final int[] COUNTERS = {100, 101};

  /** The accounts of the randomized transfers, 100 each to start with. */
  private static final int[] ACCOUNTS = {200, 201, 202, 203};

  static List<Engines> engines() {
    return Engines.selected();
  }

  /** One ScalarDB table as the test reaches it: by SQL, and by ScalarDB's own name. */
  private static final class Table {
    final String sql;
    final String scalarDbName;
    final boolean imported;

    Table(String sql, String scalarDbName, boolean imported) {
      this.sql = sql;
      this.scalarDbName = scalarDbName;
      this.imported = imported;
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void interleavings(Engines e) throws Exception {
    assumeTrue(e == Engines.POSTGRESQL, "writes are PostgreSQL only so far (milestone 4)");
    try (Connection c = e.open()) {
      e.execute(
          c,
          "CREATE SCHEMA il",
          // run once per statement, after its snapshot: opens a window inside one read
          "CREATE FUNCTION il.pause() RETURNS BOOLEAN AS 'SELECT pg_sleep(1) IS NOT NULL'"
              + " LANGUAGE sql VOLATILE");
      for (String isolation : ISOLATIONS) {
        String imp = "il.imp_" + isolation.toLowerCase(Locale.ROOT);
        e.execute(
            c,
            "CREATE TABLE " + imp + " (id INT PRIMARY KEY, x TEXT, v INT)",
            // rows without metadata, as an imported table has them
            "INSERT INTO " + imp + " SELECT g, 'a', 0 FROM generate_series(1, 12) g",
            "INSERT INTO "
                + imp
                + " SELECT g, 'a', 0 FROM unnest(ARRAY"
                + Arrays.toString(COUNTERS)
                + ") g",
            "INSERT INTO "
                + imp
                + " SELECT g, 'a', 100 FROM unnest(ARRAY"
                + Arrays.toString(ACCOUNTS)
                + ") g");
      }
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("il", true);
      for (String isolation : ISOLATIONS) {
        String suffix = "_" + isolation.toLowerCase(Locale.ROOT);
        admin.importTable("il", "imp" + suffix, DECOUPLED, Map.of());
        admin.createTable(
            "il",
            "made" + suffix,
            TableMetadata.newBuilder()
                .addColumn("id", DataType.INT)
                .addColumn("x", DataType.TEXT)
                .addColumn("v", DataType.INT)
                .addPartitionKey("id")
                .build(),
            DECOUPLED);
      }
    }
    try (DistributedTransactionManager tm = e.scalarDb().getTransactionManager()) {
      for (String isolation : ISOLATIONS) {
        DistributedTransaction tx = tm.begin();
        List<Integer> ids = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
          ids.add(i);
        }
        for (int id : COUNTERS) {
          ids.add(id);
        }
        for (int id : ids) {
          tx.insert(made(isolation, id, 0));
        }
        for (int id : ACCOUNTS) {
          tx.insert(made(isolation, id, 100));
        }
        tx.commit();
      }
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals("il"), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    for (String isolation : ISOLATIONS) {
      String suffix = "_" + isolation.toLowerCase(Locale.ROOT);
      TableInfo imp = catalog.lookup(List.of(), "il", "imp" + suffix);
      List<Table> tables =
          List.of(
              new Table("il.imp" + suffix, Objects.requireNonNull(imp.virtualTable()), true),
              new Table("il.made" + suffix, "made" + suffix, false));
      Properties scalarDb = new Properties();
      scalarDb.setProperty("scalar.db.consensus_commit.isolation_level", isolation);
      TransactionFactory factory = e.scalarDb(scalarDb);
      try (DistributedTransactionManager tm = factory.getTransactionManager();
          TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
        for (Table t : tables) {
          String at = "ScalarDB " + isolation + ", " + t.sql;
          scenarios(e, tm, tpc, t);
          int[] aborted = new int[2];
          for (long seed = 1; seed <= 20; seed++) {
            int[] counters =
                schedule(e, tpc, t, InterleavingIntegrationTest::counter, COUNTERS, at, seed);
            int[] transfers =
                schedule(e, tpc, t, InterleavingIntegrationTest::transfer, ACCOUNTS, at, seed);
            for (int side = 0; side < 2; side++) {
              aborted[side] += counters[side] + transfers[side];
            }
          }
          // the schedules prove something only if both sides run into conflicts
          assertThat(aborted[0]).as("%s: driver aborts", at).isPositive();
          assertThat(aborted[1]).as("%s: ScalarDB aborts", at).isPositive();
        }
      }
    }
  }

  private static Insert made(String isolation, int id, int v) {
    return Insert.newBuilder()
        .namespace("il")
        .table("made_" + isolation.toLowerCase(Locale.ROOT))
        .partitionKey(Key.ofInt("id", id))
        .textValue("x", "a")
        .intValue("v", v)
        .build();
  }

  /** The rows of design section 9 that apply to READ COMMITTED, one by one. */
  private static void scenarios(
      Engines e, DistributedTransactionManager tm, TwoPhaseCommitTransactionManager tpc, Table t)
      throws Exception {
    ExecutorService pool = Executors.newCachedThreadPool();
    try (Connection d = e.openDriver();
        Connection d2 = e.openDriver()) {
      // dirty read of an after image: the in-flight copy reads the before image
      TwoPhaseCommitTransaction held = prepared(tpc, t, 1, "after");
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 1"))
          .as("dirty read: in-flight copies read their before image")
          .containsExactly("a");
      assertThat(rows(d, "SELECT id FROM " + t.sql + " WHERE x = 'after'"))
          .as("dirty read: committed copies exclude rows in flight")
          .isEmpty();
      held.commit();
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 1")).containsExactly("after");

      // a before image used as a result: a prepared insert has none, and its NULL before_x must
      // not match
      held = tpc.begin();
      held.insert(
          Insert.newBuilder()
              .namespace("il")
              .table(t.scalarDbName)
              .partitionKey(Key.ofInt("id", 50))
              .textValue("x", "new")
              .build());
      held.prepare();
      assertThat(rows(d, "SELECT id FROM " + t.sql + " WHERE id = 50 OR x IS NULL"))
          .as("before image used as a result: consulted only as the committed image")
          .isEmpty();
      held.rollback();

      // the driver overwrites an in-flight row, and the ScalarDB commit goes on
      held = prepared(tpc, t, 3, "s");
      assertThatThrownBy(() -> update(d, t, 3))
          .as("driver overwrites an in-flight row: the CAS requires tx_state = 3")
          .isInstanceOf(SQLTransactionRollbackException.class);
      held.commit();
      assertThat(scalarDbX(tm, t, 3)).contains("s");

      // an aborted after image becomes permanent
      held = prepared(tpc, t, 4, "s");
      assertThatThrownBy(() -> update(d, t, 4))
          .as("aborted after image made permanent: the CAS requires tx_state = 3")
          .isInstanceOf(SQLTransactionRollbackException.class);
      held.rollback();
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 4")).containsExactly("a");
      assertThat(update(d, t, 4)).isEqualTo(1);

      // ScalarDB overwrites a driver write: ScalarDB read before the driver committed
      DistributedTransaction tx = tm.begin();
      tx.get(get(t, 5));
      assertThat(update(d, t, 5)).isEqualTo(1);
      tx.update(set(t, 5, "s"));
      assertThatThrownBy(tx::commit)
          .as("ScalarDB overwrites a driver write: ScalarDB's prepare condition (I2)")
          .isInstanceOf(TransactionException.class);
      assertThat(scalarDbX(tm, t, 5)).contains("a+");

      // a lost update, driver against ScalarDB: the driver's read anchors its write
      d.setAutoCommit(false);
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 6")).containsExactly("a");
      scalarDbSet(tm, t, 6, "s");
      assertThatThrownBy(() -> update(d, t, 6))
          .as("lost update, driver vs ScalarDB: the anchored CAS")
          .isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      // and driver against driver
      d2.setAutoCommit(false);
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 7")).containsExactly("a");
      assertThat(rows(d2, "SELECT x FROM " + t.sql + " WHERE id = 7")).containsExactly("a");
      assertThat(update(d, t, 7)).isEqualTo(1);
      d.commit();
      assertThatThrownBy(() -> update(d2, t, 7))
          .as("lost update, driver vs driver: the anchored CAS")
          .isInstanceOf(SQLTransactionRollbackException.class);
      d2.rollback();
      d2.setAutoCommit(true);
      d.setAutoCommit(true);
      assertThat(scalarDbX(tm, t, 6)).contains("s");
      assertThat(scalarDbX(tm, t, 7)).contains("a+");

      if (t.imported) {
        // a ScalarDB rollback over a row without metadata leaves NULL metadata, read as committed
        held = prepared(tpc, t, 8, "s");
        held.rollback();
        assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 8"))
            .as("NULL-metadata row vanishes: NULL tx_state read as 3")
            .containsExactly("a");
        assertThat(update(d, t, 8)).as("the NULL-pair CAS").isEqualTo(1);

        // a ScalarDB transaction read a row without metadata; the driver deletes it
        tx = tm.begin();
        assertThat(tx.get(get(t, 11))).isPresent();
        assertThat(d.createStatement().executeUpdate("DELETE FROM " + t.sql + " WHERE id = 11"))
            .isEqualTo(1);
        tx.update(set(t, 11, "back"));
        assertThatThrownBy(tx::commit)
            .as("deleted row resurrected: the tombstone fails the prepare")
            .isInstanceOf(TransactionException.class);
        assertThat(scalarDbX(tm, t, 11)).isEmpty();
        assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 11")).isEmpty();
      }

      // a user lock does not reach the in-flight row, so ScalarDB's commit does not wait for it
      held = prepared(tpc, t, 10, "s");
      d.setAutoCommit(false);
      assertThat(
              rows(
                  d,
                  "SELECT id FROM "
                      + t.sql
                      + " WHERE id IN (9, 10) ORDER BY id FOR UPDATE SKIP LOCKED"))
          .containsExactly("9");
      TwoPhaseCommitTransaction committing = held;
      pool.submit(
              () -> {
                committing.commit();
                return null;
              })
          .get(20, TimeUnit.SECONDS);
      d.rollback();
      d.setAutoCommit(true);
      assertThat(scalarDbX(tm, t, 10))
          .as("in-flight row pinned by a user lock: the lock goes to the committed copy only")
          .contains("s");

      // why one statement: the writer rolls back while the read runs, and the row still matches
      held = prepared(tpc, t, 12, "s");
      Future<List<String>> read =
          pool.submit(
              () ->
                  rows(
                      d,
                      "SELECT id FROM "
                          + t.sql
                          + " WHERE x = 'a' AND id = 12"
                          + " AND (SELECT il.pause())"));
      Thread.sleep(300);
      held.rollback();
      assertThat(read.get(20, TimeUnit.SECONDS))
          .as("one statement, one snapshot for both copies (4.2)")
          .containsExactly("12");
    } finally {
      pool.shutdownNow();
    }
  }

  /** Increments one of the counters. */
  private static int[][] counter(Random random) {
    return new int[][] {{COUNTERS[random.nextInt(COUNTERS.length)]}, {1}};
  }

  /** Moves an amount from one account to another, the two in a random order. */
  private static int[][] transfer(Random random) {
    int from = random.nextInt(ACCOUNTS.length);
    int to = (from + 1 + random.nextInt(ACCOUNTS.length - 1)) % ACCOUNTS.length;
    int amount = 1 + random.nextInt(5);
    return random.nextBoolean()
        ? new int[][] {{ACCOUNTS[from], ACCOUNTS[to]}, {-amount, amount}}
        : new int[][] {{ACCOUNTS[to], ACCOUNTS[from]}, {amount, -amount}};
  }

  /**
   * One randomized schedule: two driver connections, one of them with the bounded wait, and two
   * ScalarDB two-phase streams each run five transactions of {@code workload} over {@code rows}. A
   * transaction reads each of its rows and writes it back changed by its delta: the driver reads
   * every row, writes every row, then commits; ScalarDB reads and writes each row, prepares, then
   * commits. The steps run one at a time in an order drawn from {@code seed}; a step that blocks on
   * a lock lets the others run. Each row must end changed by exactly the deltas of the transactions
   * that committed: a lost update in either direction, or half a transfer, breaks it.
   *
   * @return the aborts, the driver's then ScalarDB's
   */
  private static int[] schedule(
      Engines e,
      TwoPhaseCommitTransactionManager tpc,
      Table t,
      Function<Random, int[][]> workload,
      int[] rows,
      String at,
      long seed)
      throws Exception {
    String where = at + ", seed " + seed;
    Random random = new Random(seed);
    int[] start = values(e, t, rows);
    List<Actor> actors = new ArrayList<>();
    try {
      Properties waits = new Properties();
      waits.setProperty("scalardbnative.inflightWaitMs", "2000");
      actors.add(new DriverActor(e.openDriver(waits), t));
      actors.add(new DriverActor(e.openDriver(), t));
      actors.add(new ScalarDbActor(tpc, t));
      actors.add(new ScalarDbActor(tpc, t));
      while (true) {
        for (Actor a : actors) {
          if (a.running != null && a.running.isDone()) {
            a.finish(where);
          }
        }
        List<Actor> ready = new ArrayList<>();
        boolean blocked = false;
        for (Actor a : actors) {
          if (a.running != null) {
            blocked = true;
          } else if (a.left > 0) {
            ready.add(a);
          }
        }
        if (ready.isEmpty()) {
          if (!blocked) {
            break;
          }
          // any step may be the one the others wait for; PostgreSQL's deadlock detection runs
          // after a second
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
          while (actors.stream().noneMatch(a -> a.running != null && a.running.isDone())) {
            if (System.nanoTime() > deadline) {
              throw new AssertionError(
                  where
                      + ": every actor is blocked: "
                      + rows(
                          e.open(),
                          "SELECT pid, pg_blocking_pids(pid), state, wait_event, query"
                              + " FROM pg_stat_activity WHERE datname = current_database()"));
            }
            Thread.sleep(10);
          }
          continue;
        }
        Actor a = ready.get(random.nextInt(ready.size()));
        if (a.step == 0) {
          int[][] tx = workload.apply(random);
          a.rows = tx[0];
          a.deltas = tx[1];
        }
        int step = a.step;
        a.running =
            a.thread.submit(
                () -> {
                  try {
                    a.run(step);
                  } catch (Exception ex) {
                    // on the actor's thread: a rollback that waits for a lock lets the others run
                    if (a.aborts(ex)) {
                      a.rollback();
                    }
                    throw ex;
                  }
                  return null;
                });
        try {
          a.running.get(50, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ex) {
          // blocked, or done: the loop's next pass tells
        }
      }
      int[] end = values(e, t, rows);
      int committed = 0;
      for (int i = 0; i < rows.length; i++) {
        int expected = 0;
        for (Actor a : actors) {
          expected += a.committed.getOrDefault(rows[i], 0);
        }
        assertThat(end[i] - start[i])
            .as(
                "%s, row %d: the deltas of the committed transactions, and nothing else",
                where, rows[i])
            .isEqualTo(expected);
      }
      for (Actor a : actors) {
        committed += a.commits;
      }
      assertThat(committed).as("%s: some transaction commits", where).isPositive();
      int[] aborted = new int[2];
      for (Actor a : actors) {
        aborted[a instanceof DriverActor ? 0 : 1] += a.aborted;
      }
      return aborted;
    } finally {
      for (Actor a : actors) {
        a.close();
      }
    }
  }

  /** One side of a randomized schedule, on a thread of its own. */
  private abstract static class Actor implements AutoCloseable {
    final ExecutorService thread = Executors.newSingleThreadExecutor();

    /** The deltas of the committed transactions, per row. */
    final Map<Integer, Integer> committed = new HashMap<>();

    int left = 5;
    int commits;
    int aborted;
    int step;
    int[] rows = {};
    int[] deltas = {};
    @Nullable Future<?> running;

    /** The steps of one transaction over {@link #rows}; the last commits. */
    abstract int steps();

    abstract void run(int step) throws Exception;

    abstract void rollback() throws Exception;

    /** Whether the step failed as an abort the application retries, not as a fault. */
    abstract boolean aborts(Throwable cause);

    void finish(String where) throws Exception {
      Future<?> done = Objects.requireNonNull(running);
      running = null;
      try {
        done.get();
        if (step < steps() - 1) {
          step++;
          return;
        }
        for (int i = 0; i < rows.length; i++) {
          committed.merge(rows[i], deltas[i], Integer::sum);
        }
        commits++;
      } catch (ExecutionException ex) {
        Throwable cause = Objects.requireNonNull(ex.getCause());
        if (!aborts(cause)) {
          throw new AssertionError(where + ", step " + step, cause);
        }
        aborted++;
      }
      step = 0;
      left--;
    }

    @Override
    public void close() throws Exception {
      thread.shutdownNow();
    }
  }

  private static final class DriverActor extends Actor {
    final Connection d;
    final Table t;
    int[] values = {};

    DriverActor(Connection d, Table t) throws SQLException {
      this.d = d;
      this.t = t;
      d.setAutoCommit(false);
    }

    @Override
    int steps() {
      return 2 * rows.length + 1;
    }

    @Override
    void run(int step) throws Exception {
      int n = rows.length;
      if (step == 0) {
        values = new int[n];
      }
      if (step < n) {
        values[step] =
            Integer.parseInt(
                rows(d, "SELECT v FROM " + t.sql + " WHERE id = " + rows[step]).get(0));
      } else if (step < 2 * n) {
        int i = step - n;
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + t.sql + " SET v = ? WHERE id = ?")) {
          ps.setInt(1, values[i] + deltas[i]);
          ps.setInt(2, rows[i]);
          if (ps.executeUpdate() != 1) {
            throw new IllegalStateException("row " + rows[i] + " is gone");
          }
        }
      } else {
        d.commit();
      }
    }

    @Override
    void rollback() throws SQLException {
      d.rollback();
    }

    @Override
    boolean aborts(Throwable cause) {
      return cause instanceof SQLException s && String.valueOf(s.getSQLState()).startsWith("40");
    }

    @Override
    public void close() throws Exception {
      super.close();
      d.rollback();
      d.close();
    }
  }

  private static final class ScalarDbActor extends Actor {
    final TwoPhaseCommitTransactionManager tpc;
    final Table t;
    @Nullable TwoPhaseCommitTransaction tx;

    ScalarDbActor(TwoPhaseCommitTransactionManager tpc, Table t) {
      this.tpc = tpc;
      this.t = t;
    }

    @Override
    int steps() {
      return rows.length + 2;
    }

    @Override
    void run(int step) throws Exception {
      int n = rows.length;
      if (step == 0) {
        tx = tpc.begin();
      }
      TwoPhaseCommitTransaction tx = Objects.requireNonNull(this.tx);
      if (step < n) {
        int value = tx.get(get(t, rows[step])).orElseThrow().getInt("v");
        tx.update(
            Update.newBuilder()
                .namespace("il")
                .table(t.scalarDbName)
                .partitionKey(Key.ofInt("id", rows[step]))
                .intValue("v", value + deltas[step])
                .build());
      } else if (step == n) {
        tx.prepare();
      } else {
        tx.validate();
        tx.commit();
      }
    }

    @Override
    void rollback() throws TransactionException {
      Objects.requireNonNull(tx).rollback();
    }

    @Override
    boolean aborts(Throwable cause) {
      return cause instanceof TransactionException
          && !(cause instanceof UnknownTransactionStatusException);
    }
  }

  private static int[] values(Engines e, Table t, int[] rows) throws SQLException {
    try (Connection v = e.open()) {
      int[] out = new int[rows.length];
      for (int i = 0; i < rows.length; i++) {
        out[i] =
            Integer.parseInt(rows(v, "SELECT v FROM " + t.sql + " WHERE id = " + rows[i]).get(0));
      }
      return out;
    }
  }

  private static Get get(Table t, int id) {
    return Get.newBuilder()
        .namespace("il")
        .table(t.scalarDbName)
        .partitionKey(Key.ofInt("id", id))
        .build();
  }

  private static Update set(Table t, int id, String x) {
    return Update.newBuilder()
        .namespace("il")
        .table(t.scalarDbName)
        .partitionKey(Key.ofInt("id", id))
        .textValue("x", x)
        .build();
  }

  private static void scalarDbSet(DistributedTransactionManager tm, Table t, int id, String x)
      throws TransactionException {
    DistributedTransaction tx = tm.begin();
    tx.get(get(t, id));
    tx.update(set(t, id, x));
    tx.commit();
  }

  private static TwoPhaseCommitTransaction prepared(
      TwoPhaseCommitTransactionManager tpc, Table t, int id, String x) throws TransactionException {
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(set(t, id, x));
    tx.prepare();
    return tx;
  }

  private static int update(Connection d, Table t, int id) throws SQLException {
    try (Statement st = d.createStatement()) {
      return st.executeUpdate("UPDATE " + t.sql + " SET x = x || '+' WHERE id = " + id);
    }
  }

  private static Optional<String> scalarDbX(DistributedTransactionManager tm, Table t, int id)
      throws TransactionException {
    DistributedTransaction tx = tm.begin();
    Optional<Result> r = tx.get(get(t, id));
    tx.commit();
    return r.map(x -> x.getText("x"));
  }

  private static List<String> rows(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      int n = rs.getMetaData().getColumnCount();
      List<String> out = new ArrayList<>();
      while (rs.next()) {
        List<String> row = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
          row.add(rs.getString(i));
        }
        out.add(String.join("/", row));
      }
      return out;
    }
  }
}
