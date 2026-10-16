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
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The premise of SERIALIZABLE on PostgreSQL (design section 5): with ScalarDB's storage connections
 * at SERIALIZABLE, PostgreSQL's SSI breaks every cycle between a driver transaction and ScalarDB
 * transactions, including those row locks cannot: a phantom from a ScalarDB insert, write skew, and
 * a read-only ScalarDB transaction that sees a state no serial order gives. The driver does not run
 * SERIALIZABLE yet (milestone 7), so a native SERIALIZABLE transaction plays its part as 4.2 and
 * 4.3 describe it, without read locks: it reads committed rows only, aborts on a row in flight, and
 * writes the data row, then the metadata through the anchored CAS. Each scenario also runs with
 * ScalarDB's storage at REPEATABLE READ, where the timings that need SSI must end in the anomaly:
 * that shows the scenario reaches it.
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
    // ScalarDB's validation sees the driver's commit
    D_COMMITS_BEFORE_VALIDATION(
        false, "s.read d.read s.prepare d.write d.commit s.validate s.commit"),
    D_FIRST(false, "s.read d.read d.write d.commit s.prepare s.validate s.commit"),
    // the driver reads a prepared row
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

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void ssiBreaksTheCycles(Engines e) throws Exception {
    assumeTrue(e == Engines.POSTGRESQL, "SSI is PostgreSQL's");
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
    TableInfo info =
        new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open)
            .lookup(List.of(), "sz", "t");
    String data = "sz." + info.dataTable();
    String meta = "sz." + info.metadataTable();
    int g = 0;
    for (String storage : List.of("REPEATABLE_READ", "SERIALIZABLE")) {
      boolean ssi = storage.equals("SERIALIZABLE");
      Properties p = new Properties();
      p.setProperty("scalar.db.consensus_commit.isolation_level", "SERIALIZABLE");
      p.setProperty("scalar.db.jdbc.isolation_level", storage);
      TransactionFactory factory = e.scalarDb(p);
      try (DistributedTransactionManager tm = factory.getTransactionManager();
          TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
        for (Workload w : List.of(PHANTOM, WRITE_SKEW)) {
          String name = w == PHANTOM ? "phantom" : "write skew";
          for (Timing timing : Timing.values()) {
            g++;
            String at = "storage " + storage + ", " + name + ", " + timing + ", group " + g;
            seed(tm, g, w.seed());
            String trace = run(e, tpc, data, meta, w, g, timing);
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
        String at = "storage " + storage + ", read-only ScalarDB transaction, group " + g;
        String trace = readOnly(e, tm, data, meta, g);
        assertThat(trace.contains("anomaly"))
            .as("%s: %s; steps: %s", at, ssi ? "no anomaly" : "the anomaly", trace)
            .isEqualTo(!ssi);
      }
    }
  }

  /** Runs {@code timing}'s steps; returns them, each with how it ended. */
  private static String run(
      Engines e,
      TwoPhaseCommitTransactionManager tpc,
      String data,
      String meta,
      Workload w,
      int g,
      Timing timing)
      throws Exception {
    StringBuilder trace = new StringBuilder();
    try (Native d = new Native(e.open(), data, meta)) {
      TwoPhaseCommitTransaction s = tpc.begin();
      boolean dLive = true;
      boolean sLive = true;
      Map<Integer, Integer> read = Map.of();
      for (String step : timing.steps) {
        if (step.startsWith("d.") ? !dLive : !sLive) {
          continue;
        }
        trace.append(step).append(' ');
        try {
          switch (step) {
            case "d.read" -> read = d.read(g);
            case "d.write" -> w.d(d, g, read);
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
      Engines e, DistributedTransactionManager tm, String data, String meta, int g)
      throws Exception {
    seed(tm, g, new int[][] {{1, 0}, {2, 0}});
    StringBuilder trace = new StringBuilder();
    try (Native d = new Native(e.open(), data, meta)) {
      Map<Integer, Integer> read = d.read(g);
      DistributedTransaction deposit = tm.begin();
      int x = deposit.get(get(g, 1)).orElseThrow().getInt("v");
      deposit.update(update(g, 1, x + 20));
      deposit.commit();
      trace.append("deposit, ");
      int[] seen = null;
      DistributedTransaction ro = tm.begin();
      try {
        int rx = ro.get(get(g, 1)).orElseThrow().getInt("v");
        int ry = ro.get(get(g, 2)).orElseThrow().getInt("v");
        ro.commit();
        seen = new int[] {rx, ry};
        trace.append("read-only sees ").append(rx).append('/').append(ry).append(", ");
      } catch (TransactionException ex) {
        rollback(ro);
        trace.append("read-only aborts, ");
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

  /** The driver's part as milestone 7 will play it, on a native SERIALIZABLE transaction. */
  private static final class Native implements AutoCloseable {
    final Connection c;
    final String data;
    final String meta;
    final String id = UUID.randomUUID().toString();
    final Map<Integer, @Nullable String> anchors = new HashMap<>();

    Native(Connection c, String data, String meta) throws SQLException {
      this.c = c;
      this.data = data;
      this.meta = meta;
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    }

    /** 4.2 at SERIALIZABLE without the read lock: committed rows, and an abort on one in flight. */
    Map<Integer, Integer> read(int g) throws SQLException {
      Map<Integer, Integer> rows = new TreeMap<>();
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT d.id, d.v, m.tx_id, m.tx_state FROM "
                  + data
                  + " d LEFT JOIN "
                  + meta
                  + " m ON m.g = d.g AND m.id = d.id WHERE d.g = ?")) {
        ps.setInt(1, g);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            int state = rs.getInt(4);
            if (state == 1 || state == 2) {
              throw new SQLException("a row in flight", "40001");
            }
            rows.put(rs.getInt(1), rs.getInt(2));
            anchors.put(rs.getInt(1), rs.getString(3));
          }
        }
      }
      return rows;
    }

    /** 4.3's insert: the data row, then its committed metadata. */
    void insert(int g, int key, int v) throws SQLException {
      execute("INSERT INTO " + data + " (g, id, v) VALUES (?, ?, ?)", g, key, v);
      long now = System.currentTimeMillis();
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO "
                  + meta
                  + " (g, id, tx_id, tx_state, tx_version, tx_prepared_at, tx_committed_at)"
                  + " VALUES (?, ?, ?, 3, 1, ?, ?)")) {
        ps.setInt(1, g);
        ps.setInt(2, key);
        ps.setString(3, id);
        ps.setLong(4, now);
        ps.setLong(5, now);
        ps.executeUpdate();
      }
    }

    /** 4.3's update: the data row, then the CAS on the version read. */
    void update(int g, int key, int v) throws SQLException {
      execute("UPDATE " + data + " SET v = ? WHERE g = ? AND id = ?", v, g, key);
      long now = System.currentTimeMillis();
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE "
                  + meta
                  + " SET tx_id = ?, tx_state = 3, tx_version = tx_version + 1,"
                  + " tx_prepared_at = ?, tx_committed_at = ?"
                  + " WHERE g = ? AND id = ? AND tx_id = ? AND COALESCE(tx_state, 3) = 3")) {
        ps.setString(1, id);
        ps.setLong(2, now);
        ps.setLong(3, now);
        ps.setInt(4, g);
        ps.setInt(5, key);
        ps.setString(6, anchors.get(key));
        if (ps.executeUpdate() != 1) {
          throw new SQLException("the CAS lost", "40001");
        }
      }
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
