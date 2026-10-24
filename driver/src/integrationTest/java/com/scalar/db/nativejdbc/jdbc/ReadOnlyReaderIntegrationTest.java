package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.NativeJdbcDriver;
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
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Todo 324's reader: a driver transaction on a read-only connection at SERIALIZABLE, which on the
 * engines whose SERIALIZABLE is the driver's locking re-reads reads as at SNAPSHOT, without them,
 * beside writers at SERIALIZABLE (design 5). Fekete's read-only anomaly with ScalarDB's two-step
 * commit: x = 0 and y = 0; ScalarDB transaction S1 reads x and writes y = 10, prepares and
 * validates; then S2, ScalarDB's or the driver's, writes x = 1 and commits; then S1 commits. S1
 * precedes S2, so a reader that sees x = 1 and y = 0 fits no serial order; between S2's commit and
 * S1's, y is in flight and the reader must abort. The same reader at READ COMMITTED, which returns
 * y's before image, sees the anomaly: that shows the schedule reaches it.
 */
@SuppressFBWarnings(
    value = "SQL_INJECTION_JDBC",
    justification = "statements written in the test sources")
class ReadOnlyReaderIntegrationTest {

  private static final int X = 1;
  private static final int Y = 2;

  /** The schema of the layout under test: decoupled, or inline. */
  private static String RO = "ro";

  /** When the reader reads, among S1's and S2's steps; r.1 and r.2 are its two reads. */
  private enum Timing {
    // before S1 prepares: the reader sees neither
    FIRST("s1.read r.1 r.2 r.commit s1.prepare s1.validate w2 s1.commit"),
    // the snapshot taken before S1 prepares, the second read after S2 commits: it sees neither, and
    // holds nothing that S1's prepare or S2 waits on
    SPLIT("s1.read r.1 s1.prepare s1.validate w2 r.2 r.commit s1.commit"),
    // y in flight, x not yet written
    IN_FLIGHT("s1.read s1.prepare s1.validate r.1 r.2 r.commit w2 s1.commit"),
    // y in flight, x committed by S2: the anomaly's moment
    ANOMALY("s1.read s1.prepare s1.validate w2 r.1 r.2 r.commit s1.commit"),
    // after both: it sees both
    AFTER("s1.read s1.prepare s1.validate w2 s1.commit r.1 r.2 r.commit");

    final String steps;

    Timing(String steps) {
      this.steps = steps;
    }
  }

  /** How the reader reads x and y: two statements, or one, by each read path of design 4.2. */
  private enum Read {
    X_THEN_Y(true, "SELECT id, v FROM ro.t WHERE g = ? AND id = ?"),
    Y_THEN_X(true, "SELECT id, v FROM ro.t WHERE g = ? AND id = ?"),
    // row by row
    ROWS(false, "SELECT id, v FROM ro.t WHERE g = ?"),
    // row by row, through a join
    JOIN(
        false,
        "SELECT a.id, a.v FROM ro.t a JOIN ro.t b ON b.g = a.g AND b.id = a.id WHERE a.g = ?"),
    // the per-table check: a table in a subquery
    CHECKED(
        false, "SELECT id, v FROM ro.t WHERE g = ? AND id IN (SELECT id FROM ro.t WHERE g = ?)"),
    // the per-table check: an aggregate, x + y as id 0
    SUM(false, "SELECT 0, SUM(v) FROM ro.t WHERE g = ?");

    final boolean byKey;
    final String sql;

    Read(boolean byKey, String sql) {
      this.byKey = byKey;
      this.sql = sql;
    }
  }

  /** Who writes x after S1 validated. */
  private enum Writer {
    SCALARDB,
    DRIVER
  }

  static List<Arguments> layouts() {
    List<Arguments> out = new ArrayList<>();
    for (Engines e : Engines.selected()) {
      out.add(Arguments.of(e, false));
      out.add(Arguments.of(e, true));
    }
    return out;
  }

  @ParameterizedTest
  @MethodSource("layouts")
  void readOnlyReader_isSerializable(Engines e, boolean inline) throws Exception {
    setUp(e, inline);
    TransactionFactory factory = e.scalarDb(scalarDbSettings(e));
    int g = 0;
    List<String> failures = new ArrayList<>();
    try (DistributedTransactionManager tm = factory.getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      for (Writer writer : Writer.values()) {
        for (Read read : Read.values()) {
          for (Timing timing : Timing.values()) {
            if (timing == Timing.SPLIT && !read.byKey) {
              continue; // one statement has one moment
            }
            g++;
            String at =
                String.format("%s writes x, %s read, %s, group %d", writer, read, timing, g);
            String trace =
                run(e, tm, tpc, Connection.TRANSACTION_SERIALIZABLE, writer, read, timing, g);
            check(at, read, timing, trace, failures);
          }
        }
        // the control: the anomaly's moment at READ COMMITTED
        for (Read read : List.of(Read.X_THEN_Y, Read.ROWS)) {
          g++;
          String trace =
              run(
                  e,
                  tm,
                  tpc,
                  Connection.TRANSACTION_READ_COMMITTED,
                  writer,
                  read,
                  Timing.ANOMALY,
                  g);
          if (!trace.contains("r sees {1=1, 2=0}")) {
            failures.add(
                String.format(
                    "control, %s writes x, %s read at READ COMMITTED, group %d: the anomaly, not:"
                        + " %s",
                    writer, read, g, trace));
          }
        }
      }
    }
    assertThat(failures).as("%s inline=%s", e, inline).isEmpty();
  }

  /**
   * Such a transaction runs reads alone: the setting is the driver's, SQL Server's driver ignoring
   * it; a write of either kind of table, SQL Server's SELECT ... INTO, passthrough and a change of
   * the setting in the transaction are refused; its reads hold no lock a ScalarDB write waits on,
   * where a read-write transaction's do.
   */
  @ParameterizedTest
  @MethodSource("layouts")
  void readOnlyTransaction_readsOnly(Engines e, boolean inline) throws Exception {
    assumeTrue(!inline, "one layout: the refusals do not depend on it");
    setUp(e, inline);
    try (Connection c = e.open()) {
      e.execute(c, "DROP TABLE IF EXISTS " + RO + ".plain");
      e.execute(c, "CREATE TABLE " + RO + ".plain (id INT PRIMARY KEY)");
    }
    TransactionFactory factory = e.scalarDb(scalarDbSettings(e));
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      seed(tm, 1);
      try (Connection d = e.openDriver()) {
        d.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        d.setAutoCommit(false);
        // read-write: the read holds a lock that ScalarDB's write, on its short wait, fails on
        read(d, Read.ROWS, 1, 0);
        assertThat(scalarDbWrites(tm, 1, 5))
            .as("ScalarDB's write beside a read-write read")
            .isFalse();
        d.rollback();

        d.setReadOnly(true);
        assertThat(d.isReadOnly()).isTrue();
        assertThat(d.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
        assertThat(read(d, Read.ROWS, 1, 0)).containsEntry(X, 0);
        assertThat(scalarDbWrites(tm, 1, 6))
            .as("ScalarDB's write beside a read-only read")
            .isTrue();
        assertRefused(d, "UPDATE " + RO + ".t SET v = 7 WHERE g = 1 AND id = 1", "25006");
        assertRefused(d, "INSERT INTO " + RO + ".plain (id) VALUES (1)", "25006");
        assertRefused(d, "DELETE FROM " + RO + ".plain", "25006");
        if (e.engine() == Engine.SQLSERVER) {
          assertRefused(d, "SELECT id INTO " + RO + ".copied FROM " + RO + ".plain", "25006");
        }
        // a statement ran: the setting holds to the transaction's end
        assertThatThrownBy(() -> d.setReadOnly(false))
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("25001");
        d.commit();

        // passthrough, unparsed, may write
        d.setAutoCommit(true);
        d.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
        assertRefused(d, "INSERT INTO " + RO + ".plain (id) VALUES (2)", "25006");
        d.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "false");

        d.setReadOnly(false);
        assertThat(d.isReadOnly()).isFalse();
        try (Statement st = d.createStatement()) {
          assertThat(st.executeUpdate("INSERT INTO " + RO + ".plain (id) VALUES (3)")).isEqualTo(1);
        }
      }
    }
  }

  private static void assertRefused(Connection d, String sql, String state) {
    assertThatThrownBy(
            () -> {
              try (Statement st = d.createStatement()) {
                st.execute(sql);
              }
            })
        .as(sql)
        .isInstanceOf(SQLException.class)
        .extracting("SQLState")
        .isEqualTo(state);
  }

  /** Whether ScalarDB writes x of group {@code g}, failing at once on a lock. */
  private static boolean scalarDbWrites(DistributedTransactionManager tm, int g, int v)
      throws TransactionException {
    DistributedTransaction tx = tm.begin();
    try {
      tx.get(get(g, X));
      tx.update(update(g, X, v));
      tx.commit();
      return true;
    } catch (TransactionException ex) {
      try {
        tx.rollback();
      } catch (TransactionException ignored) {
        // the failure is the answer
      }
      return false;
    }
  }

  /** ScalarDB at SERIALIZABLE, failing at once where it would wait on a lock. */
  private static Properties scalarDbSettings(Engines e) throws SQLException {
    Properties p = new Properties();
    p.setProperty("scalar.db.consensus_commit.isolation_level", "SERIALIZABLE");
    p.setProperty("scalar.db.jdbc.isolation_level", "REPEATABLE_READ");
    // a ScalarDB step that waits on a lock fails at once, as a conflict: the test runs every
    // transaction in one thread, and the reader must hold no lock any of them waits on
    p.setProperty("scalar.db.contact_points", e.shortLockWait());
    return p;
  }

  /** The table, in the layout's schema, set up for the driver; skips the other engines. */
  private static void setUp(Engines e, boolean inline) throws Exception {
    assumeTrue(
        e.engine() == Engine.MYSQL
            || e.engine() == Engine.MARIADB
            || e.engine() == Engine.SQLSERVER,
        "the engines whose SERIALIZABLE is the driver's locking re-reads");
    RO = inline ? "roi" : "ro";
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(RO, true);
      admin.dropTable(RO, "t", true);
      admin.createTable(
          RO,
          "t",
          TableMetadata.newBuilder()
              .addColumn("g", DataType.INT)
              .addColumn("id", DataType.INT)
              .addColumn("v", DataType.INT)
              .addPartitionKey("g")
              .addClusteringKey("id")
              .build(),
          inline ? Map.of() : Map.of("transaction-metadata-decoupling", "true"));
    }
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals(RO),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
  }

  private static void check(
      String at, Read read, Timing timing, String trace, List<String> failures) {
    if (!trace.contains("s1.commit ok") || !trace.contains("w2 ok")) {
      failures.add(at + ": the writers commit, the reader holding no lock: " + trace);
    }
    // every read reaches y, in flight at these two
    if (timing == Timing.IN_FLIGHT || timing == Timing.ANOMALY) {
      if (!trace.contains("aborts 40001")) {
        failures.add(at + ": the reader aborts on y in flight: " + trace);
      }
      return;
    }
    if (!trace.contains("r.commit ok")) {
      failures.add(at + ": the reader commits: " + trace);
      return;
    }
    String seen = trace.replaceAll("(?s).*r sees (\\{[^}]*\\}).*", "$1");
    List<String> serial =
        read == Read.SUM
            ? List.of("{0=0}", "{0=10}", "{0=11}")
            : List.of("{1=0, 2=0}", "{1=0, 2=10}", "{1=1, 2=10}");
    String expected =
        switch (timing) {
          case FIRST, SPLIT -> serial.get(0);
          case AFTER -> serial.get(2);
          default -> throw new AssertionError(timing);
        };
    if (!seen.equals(expected)) {
      failures.add(at + ": the reader sees " + expected + ": " + trace);
    }
  }

  /** Runs the steps; returns each with how it ended, and what the reader saw. */
  private static String run(
      Engines e,
      DistributedTransactionManager tm,
      TwoPhaseCommitTransactionManager tpc,
      int level,
      Writer writer,
      Read read,
      Timing timing,
      int g)
      throws Exception {
    seed(tm, g);
    StringBuilder trace = new StringBuilder();
    Map<Integer, Integer> seen = new TreeMap<>();
    try (Connection r = e.openDriver()) {
      r.setTransactionIsolation(level);
      r.setReadOnly(true);
      r.setAutoCommit(false);
      TwoPhaseCommitTransaction s1 = tpc.begin();
      boolean rLive = true;
      boolean s1Live = true;
      for (String step : timing.steps.split(" ", -1)) {
        if (step.startsWith("r.") ? !rLive : step.startsWith("s1.") && !s1Live) {
          continue;
        }
        trace.append(step).append(' ');
        try {
          switch (step) {
            case "s1.read" -> {
              s1.get(get(g, X));
              s1.update(update(g, Y, 10));
            }
            case "s1.prepare" -> s1.prepare();
            case "s1.validate" -> s1.validate();
            case "s1.commit" -> s1.commit();
            case "w2" -> write(e, tm, writer, g);
            case "r.1" -> {
              if (read.byKey) {
                seen.putAll(read(r, read, g, read == Read.X_THEN_Y ? X : Y));
              } else {
                seen.putAll(read(r, read, g, 0));
              }
            }
            case "r.2" -> {
              if (read.byKey) {
                seen.putAll(read(r, read, g, read == Read.X_THEN_Y ? Y : X));
              }
            }
            case "r.commit" -> {
              r.commit();
              trace.append("ok, r sees ").append(seen).append(", ");
              continue;
            }
            default -> throw new AssertionError(step);
          }
          trace.append("ok, ");
        } catch (SQLException ex) {
          if (!step.startsWith("r.") || !"40001".equals(ex.getSQLState())) {
            throw ex;
          }
          trace.append("aborts 40001, ");
          r.rollback();
          rLive = false;
        } catch (TransactionException ex) {
          trace.append("aborts ").append(ex.getClass().getSimpleName()).append(", ");
          if (step.startsWith("s1.")) {
            s1Live = false;
            try {
              s1.rollback();
            } catch (TransactionException ignored) {
              // the abort is what the trace records
            }
          }
        }
      }
      r.rollback();
    }
    return trace.toString();
  }

  /** S2: x = 1, committed, by ScalarDB or by the driver at SERIALIZABLE. */
  private static void write(Engines e, DistributedTransactionManager tm, Writer writer, int g)
      throws Exception {
    if (writer == Writer.SCALARDB) {
      DistributedTransaction s2 = tm.begin();
      try {
        s2.get(get(g, X));
        s2.update(update(g, X, 1));
        s2.commit();
      } catch (TransactionException ex) {
        try {
          s2.rollback();
        } catch (TransactionException ignored) {
          // as above
        }
        throw ex;
      }
      return;
    }
    try (Connection d = e.openDriver()) {
      d.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      d.setAutoCommit(false);
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + RO + ".t SET v = 1 WHERE g = ? AND id = ?")) {
        ps.setInt(1, g);
        ps.setInt(2, X);
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      d.commit();
    }
  }

  private static Map<Integer, Integer> read(Connection r, Read read, int g, int key)
      throws SQLException {
    Map<Integer, Integer> rows = new TreeMap<>();
    try (PreparedStatement ps = r.prepareStatement(read.sql.replace("ro.", RO + "."))) {
      int n = ps.getParameterMetaData().getParameterCount();
      for (int i = 1; i <= n; i++) {
        ps.setInt(i, read.byKey && i == 2 ? key : g);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.put(rs.getInt(1), rs.getInt(2));
        }
      }
    }
    return rows;
  }

  private static void seed(DistributedTransactionManager tm, int g) throws TransactionException {
    DistributedTransaction tx = tm.begin();
    tx.insert(insert(g, X, 0));
    tx.insert(insert(g, Y, 0));
    tx.commit();
  }

  private static Get get(int g, int id) {
    return Get.newBuilder()
        .namespace(RO)
        .table("t")
        .partitionKey(Key.ofInt("g", g))
        .clusteringKey(Key.ofInt("id", id))
        .build();
  }

  private static Insert insert(int g, int id, int v) {
    return Insert.newBuilder()
        .namespace(RO)
        .table("t")
        .partitionKey(Key.ofInt("g", g))
        .clusteringKey(Key.ofInt("id", id))
        .intValue("v", v)
        .build();
  }

  private static Update update(int g, int id, int v) {
    return Update.newBuilder()
        .namespace(RO)
        .table("t")
        .partitionKey(Key.ofInt("g", g))
        .clusteringKey(Key.ofInt("id", id))
        .intValue("v", v)
        .build();
  }
}
