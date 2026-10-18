package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.exception.transaction.CommitConflictException;
import com.scalar.db.exception.transaction.CrudConflictException;
import com.scalar.db.exception.transaction.PreparationConflictException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.ValidationConflictException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SERIALIZABLE (design section 5): on PostgreSQL, with the driver and ScalarDB's storage
 * connections at SERIALIZABLE, SSI breaks every cycle between a driver transaction and ScalarDB
 * transactions, including those row locks cannot: a phantom from a ScalarDB insert, write skew, and
 * a read-only ScalarDB transaction that sees a state no serial order gives. On MySQL and MariaDB
 * the driver's locking re-reads break them: a ScalarDB prepare waits on them, here until ScalarDB's
 * short lock wait fails it. Each scenario also runs with the driver at REPEATABLE READ, its
 * SNAPSHOT, or on PostgreSQL ScalarDB's storage at REPEATABLE READ, where the timings that need SSI
 * must end in the anomaly: that shows the scenario reaches it.
 */
@SuppressFBWarnings(
    value = "SQL_INJECTION_JDBC",
    justification = "statements written in the test sources")
class SerializableIntegrationTest {

  /**
   * The steps of one driver transaction (d) and one ScalarDB two-phase transaction (s), in order;
   * {@code s.read} also buffers ScalarDB's writes. A cycle needs the driver to read before
   * ScalarDB's prepare and commit after ScalarDB's validation.
   */
  private enum Timing {
    S_FIRST(true, "s.read d.read s.prepare s.validate s.commit d.write d.commit"),
    D_WRITES_FIRST(true, "s.read d.read d.write s.prepare s.validate s.commit d.commit"),
    D_WRITES_IN_BETWEEN(true, "s.read d.read s.prepare d.write s.validate s.commit d.commit"),
    D_WRITES_AFTER_VALIDATION(true, "s.read d.read s.prepare s.validate d.write d.commit s.commit"),
    // not SSI's: ScalarDB's validation sees the driver's commit
    D_COMMITS_BEFORE_VALIDATION(
        false, "s.read d.read s.prepare d.write d.commit s.validate s.commit"),
    // not SSI's: ScalarDB's validation sees the driver's commit
    D_FIRST(false, "s.read d.read d.write d.commit s.prepare s.validate s.commit"),
    // not SSI's: the driver reads a prepared row and aborts
    IN_FLIGHT(false, "s.read s.prepare d.read d.write d.commit s.validate s.commit");

    final boolean needsSsi;
    final String[] steps;

    Timing(boolean needsSsi, String steps) {
      this.needsSsi = needsSsi;
      this.steps = steps.split(" ", -1);
    }
  }

  /** A rule over one group of rows that each side keeps on its own and only a cycle breaks. */
  private interface Workload {
    /** The rows of a fresh group, {id, v} each. */
    int[][] seed();

    /** The driver's write, given what it read. */
    void d(Native d, int g, Map<Integer, Integer> read) throws SQLException;

    /** ScalarDB's read and its buffered write. */
    void s(TwoPhaseCommitTransaction s, int g) throws TransactionException;

    /** Whether the rule holds over the committed rows. */
    boolean holds(Map<Integer, Integer> rows);
  }

  /** At most one row per group: each side inserts one if it sees none. */
  private static final Workload PHANTOM =
      new Workload() {
        @Override
        public int[][] seed() {
          return new int[0][];
        }

        @Override
        public void d(Native d, int g, Map<Integer, Integer> read) throws SQLException {
          if (read.isEmpty()) {
            d.insert(g, 1, 1);
          }
        }

        @Override
        public void s(TwoPhaseCommitTransaction s, int g) throws TransactionException {
          if (s.scan(scan(g)).isEmpty()) {
            s.insert(insert(g, 2, 1));
          }
        }

        @Override
        public boolean holds(Map<Integer, Integer> rows) {
          return rows.size() <= 1;
        }
      };

  /** At least one of two rows stays 1: each side clears its own if both are 1. */
  private static final Workload WRITE_SKEW =
      new Workload() {
        @Override
        public int[][] seed() {
          return new int[][] {{1, 1}, {2, 1}};
        }

        @Override
        public void d(Native d, int g, Map<Integer, Integer> read) throws SQLException {
          if (sum(read) >= 2) {
            d.update(g, 1, 0);
          }
        }

        @Override
        public void s(TwoPhaseCommitTransaction s, int g) throws TransactionException {
          int sum = 0;
          for (Result r : s.scan(scan(g))) {
            sum += r.getInt("v");
          }
          if (sum >= 2) {
            s.update(update(g, 2, 0));
          }
        }

        @Override
        public boolean holds(Map<Integer, Integer> rows) {
          return sum(rows) >= 1;
        }
      };

  /** How the driver reads the group: each of the read paths of design 4.2. */
  private enum Read {
    // row by row
    ROWS("SELECT id, v FROM sz.t WHERE g = ?"),
    // row by row, through a join
    JOIN("SELECT a.id, a.v FROM sz.t a JOIN sz.t b ON b.g = a.g AND b.id = a.id WHERE a.g = ?"),
    // the per-table check: a table in a subquery
    CHECKED("SELECT id, v FROM sz.t WHERE g = ? AND id IN (SELECT id FROM sz.t WHERE g = ?)");

    final String sql;

    Read(String sql) {
      this.sql = sql;
    }
  }

  /** The driver's SNAPSHOT and SERIALIZABLE. */
  private static final int[] LEVELS = {
    Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE
  };

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void ssiBreaksTheCycles(Engines e) throws Exception {
    boolean pg = e.engine() == Engine.POSTGRESQL;
    assumeTrue(
        pg || e.engine() == Engine.MYSQL || e.engine() == Engine.MARIADB,
        "SERIALIZABLE is PostgreSQL's, MySQL's and MariaDB's so far");
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("sz", true);
      admin.createTable(
          "sz",
          "t",
          TableMetadata.newBuilder()
              .addColumn("g", DataType.INT)
              .addColumn("id", DataType.INT)
              .addColumn("v", DataType.INT)
              .addPartitionKey("g")
              .addClusteringKey("id")
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
    }
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals("sz"),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    int g = 0;
    // the storage's level matters to PostgreSQL's SSI only
    for (String storage :
        pg ? List.of("REPEATABLE_READ", "SERIALIZABLE") : List.of("REPEATABLE_READ")) {
      Properties p = new Properties();
      p.setProperty("scalar.db.consensus_commit.isolation_level", "SERIALIZABLE");
      p.setProperty("scalar.db.jdbc.isolation_level", storage);
      if (!pg) {
        // a prepare that waits on the driver's lock fails at once, as a conflict: the test runs
        // both sides in one thread
        p.setProperty(
            "scalar.db.contact_points",
            e.url()
                + (e.url().contains("?") ? "&" : "?")
                + "sessionVariables=innodb_lock_wait_timeout=1");
      }
      TransactionFactory factory = e.scalarDb(p);
      try (DistributedTransactionManager tm = factory.getTransactionManager();
          TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
        for (int level : LEVELS) {
          boolean ssi =
              level == Connection.TRANSACTION_SERIALIZABLE
                  && (!pg || storage.equals("SERIALIZABLE"));
          String driver =
              level == Connection.TRANSACTION_SERIALIZABLE ? "SERIALIZABLE" : "SNAPSHOT";
          for (Read read : Read.values()) {
            if (pg) {
              try (Connection c = e.openDriver()) {
                c.setTransactionIsolation(level);
                // the read takes the path its name says: row by row carries each row's state
                assertThat(c.nativeSQL(read.sql).contains("\"scalardb_native_meta\".\"tx_state\""))
                    .as("driver %s, %s read row by row", driver, read)
                    .isEqualTo(read != Read.CHECKED);
              }
            }
            for (Workload w : List.of(PHANTOM, WRITE_SKEW)) {
              String name = w == PHANTOM ? "phantom" : "write skew";
              for (Timing timing : Timing.values()) {
                g++;
                String at =
                    String.format(
                        "driver %s, storage %s, %s read, %s, %s, group %d",
                        driver, storage, read, name, timing, g);
                seed(tm, g, w.seed());
                String trace = run(e, tpc, level, read, w, g, timing);
                if (timing != Timing.IN_FLIGHT) {
                  // nothing is in flight when the driver reads: an abort there is not the cycle's
                  assertThat(trace)
                      .as("%s: the read goes through", at)
                      .doesNotContain("d.read aborts");
                }
                boolean holds = w.holds(rows(e, g));
                if (ssi || !timing.needsSsi) {
                  assertThat(holds).as("%s: the rule holds; steps: %s", at, trace).isTrue();
                  assertThat(trace).as("%s: one side commits", at).contains("commit ok");
                } else {
                  assertThat(holds)
                      .as("%s: the scenario reaches the anomaly; steps: %s", at, trace)
                      .isFalse();
                }
              }
            }
            g++;
            String at =
                String.format(
                    "driver %s, storage %s, %s read, read-only ScalarDB transaction, group %d",
                    driver, storage, read, g);
            String trace = readOnly(e, tm, level, read, g);
            assertThat(trace.contains("anomaly"))
                .as("%s: %s; steps: %s", at, ssi ? "no anomaly" : "the anomaly", trace)
                .isEqualTo(!ssi);
            if (ssi && pg) {
              // the deposit commits first: the read-only transaction sees it, and SSI aborts the
              // driver, the pivot
              assertThat(trace)
                  .as("%s: steps", at)
                  .contains("read-only sees 20/0")
                  .contains("driver aborts");
            }
          }
        }
      }
    }
  }

  /** Runs {@code timing}'s steps; returns them, each with how it ended. */
  private static String run(
      Engines e,
      TwoPhaseCommitTransactionManager tpc,
      int level,
      Read read,
      Workload w,
      int g,
      Timing timing)
      throws Exception {
    StringBuilder trace = new StringBuilder();
    try (Native d = new Native(e.openDriver(), level, read)) {
      TwoPhaseCommitTransaction s = tpc.begin();
      boolean dLive = true;
      boolean sLive = true;
      Map<Integer, Integer> seen = Map.of();
      for (String step : timing.steps) {
        if (step.startsWith("d.") ? !dLive : !sLive) {
          continue;
        }
        trace.append(step).append(' ');
        try {
          switch (step) {
            case "d.read" -> seen = d.read(g);
            case "d.write" -> w.d(d, g, seen);
            case "d.commit" -> d.c.commit();
            case "s.read" -> w.s(s, g);
            case "s.prepare" -> s.prepare();
            case "s.validate" -> s.validate();
            case "s.commit" -> s.commit();
            default -> throw new AssertionError(step);
          }
          trace.append("ok, ");
        } catch (SQLException ex) {
          if (!d.aborts(ex)) {
            throw ex;
          }
          trace.append("aborts ").append(ex.getSQLState()).append(", ");
          d.c.rollback();
          dLive = false;
        } catch (TransactionException ex) {
          if (!conflict(ex)) {
            throw ex;
          }
          trace.append("aborts ").append(ex.getClass().getSimpleName()).append(", ");
          rollback(s);
          sLive = false;
        }
      }
      return trace.toString();
    }
  }

  /**
   * Fekete's read-only anomaly, the read-only side ScalarDB's: x and y start at 0; ScalarDB
   * deposits 20 into x; the driver takes 10 from y, and a penalty of 1 more if it saw x + y below
   * 10; a read-only ScalarDB transaction reads both. If it sees x = 20 and y = 0 and the driver
   * still charged the penalty, no serial order fits.
   */
  private static String readOnly(
      Engines e, DistributedTransactionManager tm, int level, Read readPath, int g)
      throws Exception {
    seed(tm, g, new int[][] {{1, 0}, {2, 0}});
    StringBuilder trace = new StringBuilder();
    try (Native d = new Native(e.openDriver(), level, readPath)) {
      Map<Integer, Integer> read = d.read(g);
      DistributedTransaction deposit = tm.begin();
      try {
        int x = deposit.get(get(g, 1)).orElseThrow().getInt("v");
        deposit.update(update(g, 1, x + 20));
        deposit.commit();
        trace.append("deposit, ");
      } catch (TransactionException ex) {
        if (!conflict(ex)) {
          throw ex;
        }
        // waited on the driver's lock
        rollback(deposit);
        trace.append("deposit aborts ").append(ex.getClass().getSimpleName()).append(", ");
      }
      int[] seen = null;
      DistributedTransaction ro = tm.begin();
      try {
        int rx = ro.get(get(g, 1)).orElseThrow().getInt("v");
        int ry = ro.get(get(g, 2)).orElseThrow().getInt("v");
        ro.commit();
        seen = new int[] {rx, ry};
        trace.append("read-only sees ").append(rx).append('/').append(ry).append(", ");
      } catch (TransactionException ex) {
        if (!conflict(ex)) {
          throw ex;
        }
        rollback(ro);
        trace.append("read-only aborts ").append(ex.getClass().getSimpleName()).append(", ");
      }
      boolean penalty = read.getOrDefault(1, 0) + read.getOrDefault(2, 0) < 10;
      try {
        d.update(g, 2, read.getOrDefault(2, 0) - 10 - (penalty ? 1 : 0));
        d.c.commit();
        trace.append("driver commits").append(penalty ? " the penalty" : "");
        if (penalty && seen != null && seen[0] == 20 && seen[1] == 0) {
          trace.append(", anomaly");
        }
      } catch (SQLException ex) {
        if (!d.aborts(ex)) {
          throw ex;
        }
        d.c.rollback();
        trace.append("driver aborts ").append(ex.getSQLState());
      }
    }
    return trace.toString();
  }

  /** A driver transaction at {@code level}, over the table's SQL name. */
  private static final class Native implements AutoCloseable {
    final Connection c;
    final Read read;

    Native(Connection c, int level, Read read) throws SQLException {
      this.c = c;
      this.read = read;
      c.setAutoCommit(false);
      c.setTransactionIsolation(level);
    }

    Map<Integer, Integer> read(int g) throws SQLException {
      Map<Integer, Integer> rows = new TreeMap<>();
      try (PreparedStatement ps = c.prepareStatement(read.sql)) {
        for (int i = 1; i <= ps.getParameterMetaData().getParameterCount(); i++) {
          ps.setInt(i, g);
        }
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            rows.put(rs.getInt(1), rs.getInt(2));
          }
        }
      }
      return rows;
    }

    void insert(int g, int key, int v) throws SQLException {
      execute("INSERT INTO sz.t (g, id, v) VALUES (?, ?, ?)", g, key, v);
    }

    void update(int g, int key, int v) throws SQLException {
      execute("UPDATE sz.t SET v = ? WHERE g = ? AND id = ?", v, g, key);
    }

    private void execute(String sql, int... values) throws SQLException {
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        for (int i = 0; i < values.length; i++) {
          ps.setInt(i + 1, values[i]);
        }
        ps.executeUpdate();
      }
    }

    boolean aborts(SQLException ex) {
      return "40001".equals(ex.getSQLState()) || "40P01".equals(ex.getSQLState());
    }

    @Override
    public void close() throws SQLException {
      c.rollback();
      c.close();
    }
  }

  private static void seed(DistributedTransactionManager tm, int g, int[][] rows)
      throws TransactionException {
    DistributedTransaction tx = tm.begin();
    for (int[] r : rows) {
      tx.insert(insert(g, r[0], r[1]));
    }
    tx.commit();
  }

  /** The group's committed rows, id to v, read by ScalarDB. */
  private static Map<Integer, Integer> rows(Engines e, int g) throws Exception {
    try (DistributedTransactionManager tm = e.scalarDb().getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      Map<Integer, Integer> rows = new TreeMap<>();
      for (Result r : tx.scan(scan(g))) {
        rows.put(r.getInt("id"), r.getInt("v"));
      }
      tx.commit();
      return rows;
    }
  }

  /** Whether ScalarDB aborted for a conflict, not for any other failure. */
  private static boolean conflict(TransactionException ex) {
    return ex instanceof CrudConflictException
        || ex instanceof PreparationConflictException
        || ex instanceof ValidationConflictException
        || ex instanceof CommitConflictException;
  }

  private static int sum(Map<Integer, Integer> rows) {
    return rows.values().stream().mapToInt(Integer::intValue).sum();
  }

  private static void rollback(TwoPhaseCommitTransaction s) {
    try {
      s.rollback();
    } catch (TransactionException ignored) {
      // the abort that brought us here is what the trace records
    }
  }

  private static void rollback(DistributedTransaction s) {
    try {
      s.rollback();
    } catch (TransactionException ignored) {
      // as above
    }
  }

  private static Scan scan(int g) {
    return Scan.newBuilder().namespace("sz").table("t").partitionKey(Key.ofInt("g", g)).build();
  }

  private static Get get(int g, int id) {
    return Get.newBuilder()
        .namespace("sz")
        .table("t")
        .partitionKey(Key.ofInt("g", g))
        .clusteringKey(Key.ofInt("id", id))
        .build();
  }

  private static Insert insert(int g, int id, int v) {
    return Insert.newBuilder()
        .namespace("sz")
        .table("t")
        .partitionKey(Key.ofInt("g", g))
        .clusteringKey(Key.ofInt("id", id))
        .intValue("v", v)
        .build();
  }

  private static Update update(int g, int id, int v) {
    return Update.newBuilder()
        .namespace("sz")
        .table("t")
        .partitionKey(Key.ofInt("g", g))
        .clusteringKey(Key.ofInt("id", id))
        .intValue("v", v)
        .build();
  }
}
