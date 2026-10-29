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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What a write costs on a row of an imported decoupled table that has no metadata row, as before
 * the backfill reaches it, against one that has. The table is set up and backfilled, then the
 * metadata rows of every other block of ten keys are deleted; each measured write takes keys no
 * write took before, so an unstamped key is unstamped when written, and the metadata rows each
 * write creates are counted to show it. On PostgreSQL the statements each write sends are counted
 * from {@code pg_stat_statements}, on Oracle the driver session's round trips. Off by default; run
 * with {@code ./gradlew :driver:integrationTestPostgresql --tests '*UnstampedWriteBenchmark*'
 * -Dnativejdbc.bench=true}.
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
    justification = "statements written in the test sources")
class UnstampedWriteBenchmark {

  private static final int ROWS = 50_000;
  private static final int BLOCK = 10;
  private static final int WARM = 50;
  private static final int MEASURED = 200;

  static List<Engines> engines() {
    return Engines.selected();
  }

  /** The next unused block of ten keys, stamped or not; block b is stamped when b is even. */
  private static final class Blocks {
    private int stamped = 0;
    private int unstamped = 1;

    int next(boolean stampedKind) {
      int b;
      if (stampedKind) {
        b = stamped;
        stamped += 2;
      } else {
        b = unstamped;
        unstamped += 2;
      }
      assertThat(b * BLOCK + BLOCK).as("keys left").isLessThanOrEqualTo(ROWS);
      return b * BLOCK + 1;
    }
  }

  private interface Op {
    /** One transaction over the block of ten keys starting at {@code first}, committed. */
    void run(Connection c, int first) throws SQLException;
  }

  @ParameterizedTest
  @MethodSource("engines")
  void unstampedWrites(Engines e) throws Exception {
    assumeTrue(Boolean.getBoolean("nativejdbc.bench"), "-Dnativejdbc.bench=true runs it");
    Engine engine = e.engine();
    // a new namespace each run: ScalarDB's catalog outlives the schema a run drops
    String name = "uw" + System.currentTimeMillis() % 1_000_000;
    String ns = e.storedSchema(name);
    String n = e.intType();
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema(name),
          "CREATE TABLE " + name + ".t (id " + n + " PRIMARY KEY, x " + n + ", y VARCHAR(40))",
          "INSERT INTO " + name + ".t SELECT g, 0, 'abcdefghij' FROM " + e.series(1, ROWS));
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      admin.importTable(
          ns, e.storedTable("t"), Map.of("transaction-metadata-decoupling", "true"), Map.of());
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    String meta = engine.quote(ns) + "." + engine.quote(e.storedTable("t") + "_tx_metadata");
    String id = engine.quote(e.storedColumn("id"));
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
      // the odd blocks back to "not reached by the backfill"
      e.execute(
          c,
          "DELETE FROM " + meta + " WHERE MOD(FLOOR((" + id + " - 1) / " + BLOCK + "), 2) = 1",
          engine == Engine.POSTGRESQL
              ? "ANALYZE " + meta
              : "SELECT 1 FROM " + name + ".t WHERE id = 1");
      if (!c.getAutoCommit()) {
        c.commit();
      }
    }

    String table = name + ".t";
    Map<String, Op> ops = new LinkedHashMap<>();
    ops.put(
        "UPDATE by key", (c, k) -> update(c, "UPDATE " + table + " SET x = x + 1 WHERE id = ?", k));
    ops.put(
        "UPDATE by key range, 10 rows",
        (c, k) -> {
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE " + table + " SET x = x + 1 WHERE id BETWEEN ? AND ?")) {
            ps.setInt(1, k);
            ps.setInt(2, k + BLOCK - 1);
            assertThat(ps.executeUpdate()).isEqualTo(BLOCK);
          }
          c.commit();
        });
    ops.put(
        "batch of 10 UPDATEs by key",
        (c, k) -> {
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE " + table + " SET x = x + 1 WHERE id = ?")) {
            for (int i = 0; i < BLOCK; i++) {
              ps.setInt(1, k + i);
              ps.addBatch();
            }
            ps.executeBatch();
          }
          c.commit();
        });
    ops.put("DELETE by key", (c, k) -> update(c, "DELETE FROM " + table + " WHERE id = ?", k));

    Properties ttl = new Properties();
    ttl.setProperty(
        "scalar.db.native_jdbc.catalog_ttl_millis", String.valueOf(Catalog.DEFAULT_TTL.toMillis()));
    StringBuilder out =
        new StringBuilder(
            "\n"
                + e
                + ": a write on keys with and without a metadata row, decoupled imported table,"
                + " median µs of the transaction (write + commit) over "
                + MEASURED
                + " after "
                + WARM
                + " warm-up\n\n"
                + "| level | write | with metadata row | without | ratio | "
                + (engine == Engine.ORACLE ? "round trips" : "statements")
                + " with | without | metadata rows created per write, with | without |\n"
                + "|---|---|---|---|---|---|---|---|---|\n");
    StringBuilder texts = new StringBuilder("\nstatements per write without a metadata row:\n");
    Blocks blocks = new Blocks();
    try (Connection driver = e.openDriver(ttl);
        Connection vendor = e.open()) {
      driver.setAutoCommit(false);
      vendor.setAutoCommit(true);
      String sid = null;
      if (engine == Engine.ORACLE) {
        try (Statement s = driver.createStatement();
            ResultSet rs = s.executeQuery("SELECT SYS_CONTEXT('USERENV', 'SID') FROM dual")) {
          rs.next();
          sid = rs.getString(1);
        }
        driver.commit();
      }
      int[] levels = {
        Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ
      };
      for (int level : levels) {
        driver.setTransactionIsolation(level);
        String levelName = level == Connection.TRANSACTION_READ_COMMITTED ? "RC" : "SNAPSHOT";
        for (Map.Entry<String, Op> op : ops.entrySet()) {
          for (int w = 0; w < WARM; w++) {
            op.getValue().run(driver, blocks.next(true));
            op.getValue().run(driver, blocks.next(false));
          }
          long[] with = new long[MEASURED];
          long[] without = new long[MEASURED];
          // each kind on its own, so that pg_stat_statements counts it alone
          double[] calls = new double[2];
          double[] created = new double[2];
          for (int kind = 0; kind < 2; kind++) {
            reset(engine, vendor);
            long trips = roundTrips(vendor, sid);
            long metaRows = count(vendor, meta);
            long[] t = kind == 0 ? with : without;
            for (int i = 0; i < MEASURED; i++) {
              int first = blocks.next(kind == 0);
              long start = System.nanoTime();
              op.getValue().run(driver, first);
              t[i] = System.nanoTime() - start;
            }
            calls[kind] =
                (engine == Engine.ORACLE
                        ? roundTrips(vendor, sid) - trips
                        : statements(
                            engine, vendor, kind == 1 ? texts : null, levelName, op.getKey()))
                    / MEASURED;
            created[kind] = (double) (count(vendor, meta) - metaRows) / MEASURED;
          }
          long a = median(with);
          long b = median(without);
          out.append(
              String.format(
                  Locale.ROOT,
                  "| %s | %s | %d | %d | %.2f | %.1f | %.1f | %.1f | %.1f |%n",
                  levelName,
                  op.getKey(),
                  a,
                  b,
                  (double) b / a,
                  calls[0],
                  calls[1],
                  created[0],
                  created[1]));
        }
      }
    }
    System.out.println(out);
    System.out.println(texts);
    try (Connection c = e.open()) {
      e.dropSchemas(c, ns);
    }
  }

  private static void update(Connection c, String sql, int key) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setInt(1, key);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
    c.commit();
  }

  private static long count(Connection vendor, String table) throws SQLException {
    try (Statement s = vendor.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** The driver session's round trips on Oracle, from the vendor session; 0 elsewhere. */
  private static long roundTrips(Connection vendor, @Nullable String sid) throws SQLException {
    if (sid == null) {
      return 0;
    }
    try (PreparedStatement ps =
        vendor.prepareStatement(
            "SELECT s.value FROM v$sesstat s JOIN v$statname n ON n.statistic# = s.statistic#"
                + " WHERE s.sid = ? AND n.name = 'SQL*Net roundtrips to/from client'")) {
      ps.setInt(1, Integer.parseInt(sid));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static void reset(Engine engine, Connection vendor) throws SQLException {
    if (engine == Engine.POSTGRESQL) {
      try (Statement s = vendor.createStatement()) {
        s.execute("SELECT pg_stat_statements_reset()");
      }
    }
  }

  /** The statements the driver sent since the reset; their texts, most called first, if asked. */
  private static double statements(
      Engine engine, Connection vendor, @Nullable StringBuilder texts, String level, String op)
      throws SQLException {
    if (engine != Engine.POSTGRESQL) {
      return Double.NaN;
    }
    String q =
        "SELECT calls, regexp_replace(query, '\\s+', ' ', 'g') FROM pg_stat_statements"
            + " WHERE query NOT LIKE '%pg_stat_statements%' AND query NOT LIKE 'SELECT COUNT(*)%' ORDER BY calls DESC";
    long sum = 0;
    if (texts != null) {
      texts.append("\n").append(level).append(", ").append(op).append(":\n");
    }
    try (Statement s = vendor.createStatement();
        ResultSet rs = s.executeQuery(q)) {
      while (rs.next()) {
        long calls = rs.getLong(1);
        sum += calls;
        if (texts != null) {
          String text = rs.getString(2);
          texts
              .append(String.format(Locale.ROOT, "  %6.2f  ", (double) calls / MEASURED))
              .append(text, 0, Math.min(text.length(), 160))
              .append('\n');
        }
      }
    }
    return sum;
  }

  private static long median(long[] t) {
    long[] s = t.clone();
    Arrays.sort(s);
    return s[s.length / 2] / 1000;
  }
}
