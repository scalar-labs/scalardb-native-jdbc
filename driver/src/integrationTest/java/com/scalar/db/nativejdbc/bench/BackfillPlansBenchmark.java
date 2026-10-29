package com.scalar.db.nativejdbc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What an imported decoupled table set up without the backfill costs the driver's statements,
 * against the same table backfilled (design section 3, "The backfill as an option", todo 361): on
 * Oracle the setup gathers and locks the metadata table's statistics while it is empty, on MySQL it
 * gathers the {@code tx_state} histogram then. Two tables of {@link #ROWS} rows, one backfilled and
 * one not, each written through the driver in three phases (none of its rows written since the
 * setup, 10%, all), each phase followed by the same reads and writes at both levels: median µs, and
 * per operation Oracle's session logical reads or MySQL's handler reads; on Oracle also each driver
 * text's gets a call and plan, from V$SQL. Off by default; run with {@code ./gradlew
 * :driver:integrationTestOracle --tests '*BackfillPlansBenchmark*' -Dnativejdbc.bench=true}.
 */
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      "PREDICTABLE_RANDOM"
    },
    justification = "statements written in the test sources; the keys a seeded random picks")
class BackfillPlansBenchmark {

  private static final int ROWS = 300_000;
  // the measured first writes take keys from here up, so that a key is unwritten when written
  private static final int RESERVE = 280_001;
  private static final int WARM = 5;
  private static final int MEASURED = 30;
  private static final int CHUNK = 1_000;

  static List<Engines> engines() {
    return Engines.selected();
  }

  private interface Op {
    /** One transaction, committed; {@code k} a key, or the first of ten, as the op says. */
    void run(Connection c, String table, int k) throws SQLException;
  }

  /** An operation and where its key comes from. */
  private static final class Measured {
    final String name;
    final int level;
    final Op op;
    // the next unwritten key (the reserve), else a random key of the table
    final boolean fresh;
    final int width;

    Measured(String name, int level, Op op, boolean fresh, int width) {
      this.name = name;
      this.level = level;
      this.op = op;
      this.fresh = fresh;
      this.width = width;
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void plansWithAndWithoutTheBackfill(Engines e) throws Exception {
    assumeTrue(Boolean.getBoolean("nativejdbc.bench"), "-Dnativejdbc.bench=true runs it");
    Engine engine = e.engine();
    assumeTrue(
        engine == Engine.ORACLE || engine == Engine.MYSQL || engine == Engine.MARIADB,
        "Oracle, MySQL and MariaDB");
    String name = "bp" + System.currentTimeMillis() % 1_000_000;
    String ns = e.storedSchema(name);
    String n = e.intType();
    List<String> tables = List.of("bfa", "bfn"); // backfilled, not
    try (Connection c = e.open()) {
      e.execute(c, e.createSchema(name));
      for (String t : tables) {
        e.execute(
            c,
            "CREATE TABLE "
                + name
                + "."
                + t
                + " (k "
                + n
                + " PRIMARY KEY, x "
                + n
                + ", y"
                + " VARCHAR(40))",
            "INSERT INTO "
                + name
                + "."
                + t
                + " SELECT g, 0, 'abcdefghij' FROM "
                + e.series(1, ROWS));
        // the data table's statistics, as a production table has them
        e.execute(
            c,
            engine == Engine.ORACLE
                ? "BEGIN DBMS_STATS.GATHER_TABLE_STATS('"
                    + ns
                    + "', '"
                    + e.storedTable(t)
                    + "'); END;"
                : "ANALYZE TABLE " + name + "." + t);
      }
      if (!c.getAutoCommit()) {
        c.commit();
      }
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      for (String t : tables) {
        admin.importTable(
            ns, e.storedTable(t), Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      for (String t : tables) {
        String stored = e.storedTable(t);
        assertThat(
                Setup.run(
                    catalog,
                    c,
                    x -> x.namespace().equals(ns) && x.table().equals(stored),
                    Duration.ofMinutes(5),
                    List.of(),
                    t.equals("bfa")))
            .allMatch(Setup.Result::succeeded, "succeeded");
      }
    }
    StringBuilder out =
        new StringBuilder(
            "\n"
                + e
                + ": an imported decoupled table of "
                + ROWS
                + " rows backfilled (bfa) or not (bfn) at the setup; median µs of the transaction"
                + " over "
                + MEASURED
                + " after "
                + WARM
                + ", and "
                + (engine == Engine.ORACLE ? "session logical reads" : "handler reads")
                + " per transaction\n");
    out.append(statistics(e, ns, "after the setup"));

    int rc = Connection.TRANSACTION_READ_COMMITTED;
    int snapshot = Connection.TRANSACTION_REPEATABLE_READ;
    List<Measured> ops = new ArrayList<>();
    ops.add(
        new Measured(
            "point read",
            rc,
            (c, t, k) -> read(c, "SELECT x FROM " + t + " WHERE k = ?", k, 1),
            false,
            1));
    ops.add(
        new Measured(
            "range read, 100 keys",
            rc,
            (c, t, k) -> read(c, "SELECT SUM(x) FROM " + t + " WHERE k BETWEEN ? AND ? + 99", k, 2),
            false,
            100));
    ops.add(
        new Measured(
            "read by a non-key column, none match",
            rc,
            (c, t, k) -> read(c, "SELECT COUNT(*) FROM " + t + " WHERE x = -1 - ?", k, 1),
            false,
            1));
    ops.add(
        new Measured(
            "UPDATE by key, a key not written since the setup",
            rc,
            (c, t, k) -> write(c, "UPDATE " + t + " SET x = x + 1 WHERE k = ?", k, 1, 1),
            true,
            1));
    ops.add(
        new Measured(
            "UPDATE by key, a key written",
            rc,
            (c, t, k) -> write(c, "UPDATE " + t + " SET x = x + 1 WHERE k = ?", k, 1, 1),
            false,
            1));
    ops.add(
        new Measured(
            "UPDATE of 10 keys not written",
            rc,
            (c, t, k) ->
                write(c, "UPDATE " + t + " SET x = x + 1 WHERE k BETWEEN ? AND ? + 9", k, 2, 10),
            true,
            10));
    ops.add(
        new Measured(
            "SNAPSHOT point read",
            snapshot,
            (c, t, k) -> read(c, "SELECT x FROM " + t + " WHERE k = ?", k, 1),
            false,
            1));
    ops.add(
        new Measured(
            "SNAPSHOT range read, 100 keys",
            snapshot,
            (c, t, k) -> read(c, "SELECT SUM(x) FROM " + t + " WHERE k BETWEEN ? AND ? + 99", k, 2),
            false,
            100));
    ops.add(
        new Measured(
            "SNAPSHOT UPDATE by key, a key not written",
            snapshot,
            (c, t, k) -> write(c, "UPDATE " + t + " SET x = x + 1 WHERE k = ?", k, 1, 1),
            true,
            1));

    Properties ttl = new Properties();
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
    Map<String, int[]> next = new HashMap<>();
    for (String t : tables) {
      next.put(t, new int[] {RESERVE});
    }
    Random random = new Random(361);
    try (Connection driver = e.openDriver(ttl);
        Connection vendor = e.open()) {
      driver.setAutoCommit(false);
      vendor.setAutoCommit(true);
      String session = session(engine, driver);
      String[] phases = {"none written", "10% written", "all written"};
      // -Dnativejdbc.bench.phases=1 stops after the first, the statistics right after the setup
      int last = Math.min(phases.length, Integer.getInteger("nativejdbc.bench.phases", 3));
      for (int p = 0; p < last; p++) {
        // the phase's writes through the driver, on both tables alike
        int from = p == 1 ? 1 : p == 2 ? 30_001 : 0;
        int to = p == 1 ? 30_000 : p == 2 ? ROWS : -1;
        for (String t : tables) {
          String table = name + "." + t;
          driver.setTransactionIsolation(rc);
          for (int k = from; from > 0 && k <= to; k += CHUNK) {
            try (PreparedStatement ps =
                driver.prepareStatement(
                    "UPDATE " + table + " SET x = x + 1 WHERE k BETWEEN ? AND ?")) {
              ps.setInt(1, k);
              ps.setInt(2, Math.min(to, k + CHUNK - 1));
              ps.executeUpdate();
            }
            driver.commit();
          }
        }
        out.append("\n### ")
            .append(phases[p])
            .append("\n\n")
            .append(statistics(e, ns, phases[p]))
            .append("\n| operation | bfa µs | bfn µs | ratio | bfa reads | bfn reads |\n")
            .append("|---|---|---|---|---|---|\n");
        Map<String, Map<String, long[]>> got = new LinkedHashMap<>();
        for (Measured m : ops) {
          for (String t : tables) {
            String table = name + "." + t;
            driver.setTransactionIsolation(m.level);
            for (int w = 0; w < WARM; w++) {
              m.op.run(driver, table, key(m, next.get(t), random));
            }
            long reads = reads(engine, vendor, session);
            long[] times = new long[MEASURED];
            for (int i = 0; i < MEASURED; i++) {
              int k = key(m, next.get(t), random);
              long start = System.nanoTime();
              m.op.run(driver, table, k);
              times[i] = System.nanoTime() - start;
            }
            reads = reads < 0 ? -1 : (reads(engine, vendor, session) - reads) / MEASURED;
            got.computeIfAbsent(m.name, x -> new HashMap<>())
                .put(t, new long[] {median(times), reads});
          }
          long[] a = got.get(m.name).get("bfa");
          long[] b = got.get(m.name).get("bfn");
          out.append(
              String.format(
                  Locale.ROOT,
                  "| %s | %d | %d | %.2f | %d | %d |%n",
                  m.name,
                  a[0],
                  b[0],
                  (double) b[0] / Math.max(1, a[0]),
                  a[1],
                  b[1]));
        }
        if (engine == Engine.ORACLE) {
          out.append(oracleTexts(vendor, ns));
        } else if (engine == Engine.MYSQL) {
          out.append(mysqlTexts(vendor, ns));
        }
      }
      driver.setTransactionIsolation(rc);
    }
    System.out.println(out);
    try (Connection c = e.open()) {
      e.dropSchemas(c, ns);
    }
  }

  private static int key(Measured m, int[] next, Random random) {
    if (m.fresh) {
      int k = next[0];
      next[0] += m.width;
      assertThat(k + m.width - 1).as("reserve left").isLessThanOrEqualTo(ROWS);
      return k;
    }
    return 1 + random.nextInt(ROWS - m.width);
  }

  private static void read(Connection c, String sql, int k, int binds) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 1; i <= binds; i++) {
        ps.setInt(i, k);
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rs.getObject(1);
        }
      }
    }
    c.commit();
  }

  private static void write(Connection c, String sql, int k, int binds, int expected)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 1; i <= binds; i++) {
        ps.setInt(i, k);
      }
      assertThat(ps.executeUpdate()).isEqualTo(expected);
    }
    c.commit();
  }

  /** The driver session's id: Oracle's SID, MySQL's connection id. */
  private static String session(Engine engine, Connection driver) throws SQLException {
    try (Statement s = driver.createStatement();
        ResultSet rs =
            s.executeQuery(
                engine == Engine.ORACLE
                    ? "SELECT SYS_CONTEXT('USERENV', 'SID') FROM dual"
                    : "SELECT CONNECTION_ID()")) {
      rs.next();
      String id = rs.getString(1);
      driver.commit();
      return id;
    }
  }

  /**
   * The driver session's reads so far: Oracle's logical reads, MySQL's handler reads; -1 where
   * performance_schema is off, as MariaDB's is by default.
   */
  private static long reads(Engine engine, Connection vendor, String session) throws SQLException {
    String sql =
        engine == Engine.ORACLE
            ? "SELECT s.value FROM v$sesstat s JOIN v$statname n ON n.statistic# = s.statistic#"
                + " WHERE s.sid = ? AND n.name = 'session logical reads'"
            : "SELECT SUM(s.VARIABLE_VALUE) FROM performance_schema.status_by_thread s JOIN"
                + " performance_schema.threads t ON t.THREAD_ID = s.THREAD_ID WHERE"
                + " t.PROCESSLIST_ID = ? AND s.VARIABLE_NAME LIKE 'Handler_read%'";
    try (PreparedStatement ps = vendor.prepareStatement(sql)) {
      ps.setLong(1, Long.parseLong(session));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1) == null ? -1 : rs.getLong(1);
      }
    } catch (SQLException e) {
      if (engine == Engine.ORACLE) {
        throw e;
      }
      return -1;
    }
  }

  /** The metadata tables' row counts as the optimizer has them, and the rows they hold. */
  private static String statistics(Engines e, String ns, String when) throws SQLException {
    StringBuilder b = new StringBuilder("\nstatistics " + when + ":\n");
    try (Connection c = e.open();
        Statement s = c.createStatement()) {
      for (String t : List.of("bfa", "bfn")) {
        String meta = e.storedTable(t) + "_tx_metadata";
        long rows;
        try (ResultSet rs =
            s.executeQuery(
                "SELECT COUNT(*) FROM " + e.engine().quote(ns) + "." + e.engine().quote(meta))) {
          rs.next();
          rows = rs.getLong(1);
        }
        String stats;
        String q =
            e.engine() == Engine.ORACLE
                ? "SELECT num_rows || ' rows, ' || blocks || ' blocks, locked ' ||"
                    + " NVL(stattype_locked, 'no') FROM all_tab_statistics WHERE owner = '"
                    + ns
                    + "' AND table_name = '"
                    + meta
                    + "'"
                : "SELECT CONCAT(n_rows, ' rows (innodb_table_stats)') FROM"
                    + " mysql.innodb_table_stats WHERE database_name = '"
                    + ns
                    + "' AND table_name = '"
                    + meta
                    + "'";
        try (ResultSet rs = s.executeQuery(q)) {
          stats = rs.next() ? rs.getString(1) : "none";
        }
        String histogram = "";
        if (e.engine() == Engine.MYSQL) {
          try (ResultSet rs =
              s.executeQuery(
                  "SELECT JSON_EXTRACT(HISTOGRAM, '$.buckets') FROM"
                      + " information_schema.COLUMN_STATISTICS WHERE SCHEMA_NAME = '"
                      + ns
                      + "' AND TABLE_NAME = '"
                      + meta
                      + "' AND COLUMN_NAME = 'tx_state'")) {
            histogram = rs.next() ? ", tx_state histogram " + rs.getString(1) : ", no histogram";
          }
        }
        b.append("- ")
            .append(t)
            .append(": ")
            .append(rows)
            .append(" metadata rows; optimizer: ")
            .append(stats)
            .append(histogram)
            .append("\n");
      }
    }
    return b.toString();
  }

  /** The driver's texts on the two tables, by gets a call, with the plans of bfn's top ones. */
  private static String oracleTexts(Connection vendor, String ns) throws SQLException {
    StringBuilder b = new StringBuilder("\ndriver texts, cumulative (executions, gets a call):\n");
    List<String[]> top = new ArrayList<>();
    try (PreparedStatement ps =
        vendor.prepareStatement(
            "SELECT sql_id, child_number, executions, ROUND(buffer_gets / NULLIF(executions, 0),"
                + " 1), REGEXP_REPLACE(sql_text, '\\s+', ' ') FROM v$sql WHERE (sql_text LIKE ? OR"
                + " sql_text LIKE ?) AND executions > 0 AND sql_text NOT LIKE '%v$sql%' AND"
                + " sql_text NOT LIKE '%DBMS_STATS%' AND sql_text NOT LIKE 'DECLARE%' AND"
                + " sql_text NOT LIKE 'BEGIN%' ORDER BY 4 DESC FETCH FIRST 24 ROWS ONLY")) {
      ps.setString(1, "%\"" + ns + "\".\"BF%");
      ps.setString(2, "%" + ns + ".BF%");
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String text = rs.getString(5);
          b.append(
              String.format(
                  Locale.ROOT,
                  "  %8d %10s  %s%n",
                  rs.getLong(3),
                  rs.getString(4),
                  text.substring(0, Math.min(text.length(), 200))));
          if (text.contains("BFN") && top.size() < 6) {
            top.add(new String[] {rs.getString(1), rs.getString(2)});
          }
        }
      }
    }
    for (String[] id : top) {
      b.append("\nplan of ").append(id[0]).append(":\n");
      try (PreparedStatement ps =
          vendor.prepareStatement(
              "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'BASIC ROWS'))")) {
        ps.setString(1, id[0]);
        ps.setInt(2, Integer.parseInt(id[1]));
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            String line = rs.getString(1);
            if (line.startsWith("|")) {
              b.append("  ").append(line).append("\n");
            }
          }
        }
      }
    }
    return b.toString();
  }

  /** The driver's statements on the two tables, rows examined a call, from the digest summary. */
  private static String mysqlTexts(Connection vendor, String ns) throws SQLException {
    StringBuilder b =
        new StringBuilder("\ndriver statements, cumulative (calls, rows examined a call):\n");
    try (PreparedStatement ps =
        vendor.prepareStatement(
            "SELECT COUNT_STAR, ROUND(SUM_ROWS_EXAMINED / COUNT_STAR, 1),"
                + " REGEXP_REPLACE(DIGEST_TEXT, '\\\\s+', ' ') FROM"
                + " performance_schema.events_statements_summary_by_digest WHERE DIGEST_TEXT LIKE"
                + " ? ORDER BY 2 DESC LIMIT 24")) {
      ps.setString(1, "%`" + ns + "`%");
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String text = rs.getString(3);
          b.append(
              String.format(
                  Locale.ROOT,
                  "  %8d %10s  %s%n",
                  rs.getLong(1),
                  rs.getString(2),
                  text.substring(0, Math.min(text.length(), 200))));
        }
      }
    }
    return b.toString();
  }

  private static long median(long[] t) {
    long[] s = t.clone();
    Arrays.sort(s);
    return s[s.length / 2] / 1000;
  }
}
