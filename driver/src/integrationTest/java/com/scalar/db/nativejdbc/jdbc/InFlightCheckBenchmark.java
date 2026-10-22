package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.id;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.intColumn;
import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The SNAPSHOT check for rows in flight before a read the row-by-row detector does not cover
 * (design 4.2, the per-table check): the table-wide check the driver runs, against checks that take
 * the read's predicate. Through the vendor driver, so only the SQL is measured. Orders {@code o},
 * 100k rows in 1,000 regions of 100, each of a customer {@code cid}; customers {@code c}, 10k rows,
 * each region's orders reaching 10 of them.
 *
 * <ul>
 *   <li>One table, a grouped read of {@code o} by region: the check with the read's predicate over
 *       the before images UNION ALL over the after images, as a locking read's in-flight check
 *       ({@code Router.inFlightStep}).
 *   <li>A join of {@code o} and {@code c}, or {@code o} with a correlated EXISTS on {@code c}, both
 *       grouped: per table the conjuncts naming it alone ({@code o}'s region; none for {@code c},
 *       checked whole); or the statement itself as {@code SELECT 1} over the combinations with a
 *       table in flight, each by its after or before images.
 *   <li>Static: n rows in flight per table, spread, set in the metadata tables; per form the median
 *       µs with a random region and the share of checks that find a row, which is the share of
 *       reads that abort.
 *   <li>Live: ScalarDB transactions updating random rows of either table, w writer threads, while
 *       one reader runs the checks in turn: the share that find a row.
 * </ul>
 *
 * Run with {@code ./gradlew :driver:integrationTest --tests '*InFlightCheckBenchmark*'
 * -Dnativejdbc.bench=true}; written to {@code build/bench/inflight-check-<engine>.md}.
 */
@EnabledIfSystemProperty(named = "nativejdbc.bench", matches = "true")
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
    justification = "benchmark SQL written in this file")
class InFlightCheckBenchmark {

  private static final int ROWS = 100_000;
  private static final int CUSTOMERS = 10_000;
  private static final int REGIONS = 1000;
  private static final int RUNS = 300;
  private static final long LIVE_MS = 10_000;

  /** A form: its SQL, and whether the region is a range of 10, bound low and high per arm. */
  private record Form(String name, String sql, boolean range) {}

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void inFlightCheck(Engines e) throws Exception {
    ScalarDbNames.on(e);
    Engine engine = e.engine();
    String ns = e.storedSchema("ck");
    String n = e.intType();
    try (Connection c = e.open()) {
      if (Boolean.getBoolean("nativejdbc.bench.dropExisting")) {
        // what an earlier run left on an external database; ScalarDB's schemas in quoted lower case
        e.dropSchemas(c, ns, "scalardb", "coordinator");
      }
      e.execute(
          c,
          e.createSchema("ck"),
          "CREATE TABLE ck.o (id "
              + n
              + " PRIMARY KEY, region "
              + n
              + ", amount "
              + n
              + ", cid "
              + n
              + ")",
          "INSERT INTO ck.o SELECT g, "
              + mod("g", REGIONS)
              + ", g, "
              + mod("g", CUSTOMERS)
              + " + 1 FROM "
              + e.series(1, ROWS),
          "CREATE TABLE ck.c (id " + n + " PRIMARY KEY, tier " + n + ")",
          "INSERT INTO ck.c SELECT g, " + mod("g", 10) + " FROM " + e.series(1, CUSTOMERS));
    }
    TransactionFactory factory = e.scalarDb();
    try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      for (String table : List.of("o", "c")) {
        admin.importTable(
            ns, e.storedTable(table), Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    String vo = virtual(catalog, ns, e.storedTable("o"));
    String vc = virtual(catalog, ns, e.storedTable("c"));
    try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
      // through the virtual table, so that the before image is indexed too
      admin.createIndex(ns, vo, e.storedColumn("region"), true);
      admin.createIndex(ns, vo, e.storedColumn("cid"), true);
    }
    catalog.invalidate();
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, i -> i.namespace().equals(ns), Duration.ofMinutes(10), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo o = catalog.lookup(List.of(), ns, e.storedTable("o"));
    TableInfo cu = catalog.lookup(List.of(), ns, e.storedTable("c"));
    Map<String, List<Form>> sections = new LinkedHashMap<>();
    sections.put(
        "One table: SELECT region, SUM(amount) FROM o WHERE <region> GROUP BY region",
        single(e, o));
    sections.put(
        "Two tables: o JOIN c ON c.id = o.cid, or o WHERE EXISTS (c.id = o.cid), WHERE"
            + " o.region = ? GROUP BY o.region",
        join(e, o, cu));

    if (Boolean.getBoolean("nativejdbc.bench.arms")) {
      arms(e, o, cu);
      return;
    }
    if (Boolean.getBoolean("nativejdbc.bench.checkPlan")) {
      checkPlan(e, factory, ns, vo, o);
      return;
    }
    if (Boolean.getBoolean("nativejdbc.bench.writers")) {
      besideWriters(e, factory, ns, vo);
      return;
    }
    StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "%n## %s%n", e));
    try (Connection c = e.open()) {
      // a pass not reported: the engine settles after the setup's backfill of every row
      for (List<Form> forms : sections.values()) {
        for (Form f : forms) {
          measure(c, f, new Random(1));
        }
      }
      for (Map.Entry<String, List<Form>> s : sections.entrySet()) {
        List<Form> forms = s.getValue();
        out.append(
            String.format(
                Locale.ROOT,
                "%n%s%n%nStatic: median µs of %d runs with a random region / share that find a row"
                    + " (= reads aborted)%n%n| rows in flight per table |",
                s.getKey(),
                RUNS));
        header(out, 1, forms);
        for (int count : new int[] {0, 10, 100, 1000, 10_000}) {
          inFlight(e, c, o, ROWS, count, oBefore(e));
          inFlight(e, c, cu, CUSTOMERS, count, cBefore(e));
          out.append("| ").append(count).append(" |");
          for (Form f : forms) {
            long[] r = measure(c, f, new Random(42));
            out.append(String.format(Locale.ROOT, " %d / %d%% |", r[0], r[1]));
          }
          out.append('\n');
        }
      }
      inFlight(e, c, o, ROWS, 0, oBefore(e));
      inFlight(e, c, cu, CUSTOMERS, 0, cBefore(e));
    }

    List<Form> checks = new ArrayList<>();
    for (List<Form> forms : sections.values()) {
      forms.stream().filter(f -> f.name.contains("check")).forEach(checks::add);
    }
    out.append(
        String.format(
            Locale.ROOT,
            "%nLive: ScalarDB writers updating random rows of o or c, one each, for %d s; share"
                + " of checks that find a row%n%n| writers | commits/s |",
            LIVE_MS / 1000));
    header(out, 2, checks);
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      for (int writers : new int[] {1, 4, 16}) {
        out.append(live(e, tm, ns, vo, vc, checks, writers));
      }
    }
    System.out.println(out);
    Path file = Path.of("build/bench/inflight-check-" + e.name().toLowerCase(Locale.ROOT) + ".md");
    Files.createDirectories(file.getParent());
    Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
  }

  private static String virtual(Catalog catalog, String ns, String table) throws SQLException {
    return Objects.requireNonNull(
        catalog.lookup(List.of(), ns, table).virtualTable(), "virtual table");
  }

  /** The forms' column names, then the separator under {@code leading} fixed columns and them. */
  private static void header(StringBuilder out, int leading, List<Form> forms) {
    for (Form f : forms) {
      out.append(' ').append(f.name).append(" |");
    }
    out.append("\n|").append("---|".repeat(leading + forms.size())).append('\n');
  }

  /** The table-wide check of one table, as the driver writes it. */
  private static String tableWide(Engine engine, TableInfo t) {
    return "SELECT 1 FROM "
        + SplitRead.meta(engine, t)
        + " WHERE "
        + engine.quote("tx_state")
        + " IN (1, 2)";
  }

  private static String data(Engine engine, TableInfo t) {
    return engine.quote(t.namespace()) + "." + engine.quote(t.dataTable());
  }

  /** The checks of a grouped read of o, and the reads themselves for scale. */
  private static List<Form> single(Engines e, TableInfo t) {
    Engine engine = e.engine();
    String region = "o." + engine.quote(e.storedColumn("region"));
    String amount = "o." + engine.quote(e.storedColumn("amount"));
    String eq = " o WHERE " + region + " = ?";
    String range = " o WHERE " + region + " BETWEEN ? AND ?";
    String before = "SELECT 1 FROM " + SplitRead.inFlight(engine, t, t.dataColumns());
    String after = " UNION ALL SELECT 1 FROM " + SplitRead.afterImages(engine, t, t.dataColumns());
    String group = " GROUP BY " + region;
    return List.of(
        new Form("check: table-wide (today)", Writes.anyRow(engine, tableWide(engine, t)), false),
        new Form("check: region = ?", Writes.anyRow(engine, before + eq + after + eq), false),
        new Form(
            "check: region in 10 (1%)",
            Writes.anyRow(engine, before + range + after + range), true),
        new Form(
            "read: region = ?",
            "SELECT " + region + ", SUM(" + amount + ") FROM " + data(engine, t) + eq + group,
            false),
        new Form(
            "read: region in 10",
            "SELECT " + region + ", SUM(" + amount + ") FROM " + data(engine, t) + range + group,
            true));
  }

  /**
   * The checks of a grouped join of o and c, or of o with a correlated EXISTS on c: a row in flight
   * reaches either result only through a pair the join matches, so both take the same checks.
   */
  private static List<Form> join(Engines e, TableInfo o, TableInfo c) {
    Engine engine = e.engine();
    String region = "o." + engine.quote(e.storedColumn("region"));
    String amount = "o." + engine.quote(e.storedColumn("amount"));
    String cid = "o." + engine.quote(e.storedColumn("cid"));
    String cId = "c." + engine.quote(e.storedColumn("id"));
    String eq = " o WHERE " + region + " = ?";
    List<String> arms = pairArms(e, o, c);
    String ob = SplitRead.inFlight(engine, o, o.dataColumns());
    String oa = SplitRead.afterImages(engine, o, o.dataColumns());
    String od = data(engine, o);
    String cd = data(engine, c);
    String before = "SELECT 1 FROM " + ob;
    String after = " UNION ALL SELECT 1 FROM " + oa;
    String group = " GROUP BY " + region;
    return List.of(
        new Form(
            "join check: table-wide o, c (today)",
            Writes.anyRow(engine, tableWide(engine, o) + " UNION ALL " + tableWide(engine, c)),
            false),
        new Form(
            "join check: own conjuncts (o region, c whole)",
            Writes.anyRow(engine, before + eq + after + eq + " UNION ALL " + tableWide(engine, c)),
            false),
        new Form(
            "join check: in-flight pairs",
            Writes.anyRow(engine, String.join(" UNION ALL ", arms)),
            false),
        new Form(
            "read: join",
            "SELECT "
                + region
                + ", SUM("
                + amount
                + ") FROM "
                + od
                + " o JOIN "
                + cd
                + " c ON "
                + cId
                + " = "
                + cid
                + " WHERE "
                + region
                + " = ?"
                + group,
            false),
        new Form(
            "read: EXISTS",
            "SELECT "
                + region
                + ", SUM("
                + amount
                + ") FROM "
                + od
                + eq
                + " AND EXISTS (SELECT 1"
                + " FROM "
                + cd
                + " c WHERE "
                + cId
                + " = "
                + cid
                + ")"
                + group,
            false));
  }

  /** The arms of the in-flight-pairs check: the join over every pair with a row in flight. */
  private static List<String> pairArms(Engines e, TableInfo o, TableInfo c) {
    Engine engine = e.engine();
    String region = "o." + engine.quote(e.storedColumn("region"));
    String cid = "o." + engine.quote(e.storedColumn("cid"));
    String cId = "c." + engine.quote(e.storedColumn("id"));
    // per table, its pieces: the data table as it is (committed rows and the after images of rows
    // in flight), the after images alone, the before images
    String od = data(engine, o);
    String oa = SplitRead.afterImages(engine, o, o.dataColumns());
    String ob = SplitRead.inFlight(engine, o, o.dataColumns());
    String cd = data(engine, c);
    String ca = SplitRead.afterImages(engine, c, c.dataColumns());
    String cb = SplitRead.inFlight(engine, c, c.dataColumns());
    // every pair with a row in flight on either side, in either image: (A, *) (B, *) (*, A)
    // (*, B), * the data table, with (B, B), which no data table holds
    List<String> arms = new ArrayList<>();
    for (String[] pair : new String[][] {{oa, cd}, {ob, cd}, {od, ca}, {od, cb}, {ob, cb}}) {
      arms.add(
          "SELECT 1 FROM "
              + pair[0]
              + " o JOIN "
              + pair[1]
              + " c ON "
              + cId
              + " = "
              + cid
              + " WHERE "
              + region
              + " = ?");
    }
    return arms;
  }

  /** o's before images: the rows' own values. */
  private static Map<String, String> oBefore(Engines e) {
    String id = e.engine().quote(e.storedColumn("id"));
    return Map.of("region", mod(id, REGIONS), "amount", id, "cid", mod(id, CUSTOMERS) + " + 1");
  }

  private static Map<String, String> cBefore(Engines e) {
    return Map.of("tier", mod(e.engine().quote(e.storedColumn("id")), 10));
  }

  /**
   * With {@code -Dnativejdbc.bench.arms=true} only, at 10 and 100 rows in flight per table: the
   * in-flight-pairs check as one UNION ALL with a row limit, as OR'ed EXISTS, without a limit, and
   * as separate statements; each arm on its own with and without the limit, and its plan; written
   * to {@code build/bench/inflight-arms-<engine>.txt}.
   */
  private static void arms(Engines e, TableInfo o, TableInfo cu) throws Exception {
    Engine engine = e.engine();
    List<String> arms = pairArms(e, o, cu);
    String from =
        engine == Engine.ORACLE
            ? " FROM dual"
            : engine == Engine.MYSQL || engine == Engine.MARIADB ? " FROM DUAL" : "";
    List<String> exists = new ArrayList<>();
    for (String arm : arms) {
      exists.add("EXISTS (" + arm + ")");
    }
    Map<String, String> variants = new LinkedHashMap<>();
    variants.put(
        "anyRow(UNION ALL), as benchmarked",
        Writes.anyRow(engine, String.join(" UNION ALL ", arms)));
    variants.put(
        "EXISTS per arm, OR'ed", "SELECT 1" + from + " WHERE " + String.join(" OR ", exists));
    variants.put("UNION ALL, no limit, first row read", String.join(" UNION ALL ", arms));
    StringBuilder out = new StringBuilder();
    try (Connection c = e.open()) {
      for (int count : new int[] {10, 100}) {
        inFlight(e, c, o, ROWS, count, oBefore(e));
        inFlight(e, c, cu, CUSTOMERS, count, cBefore(e));
        for (Map.Entry<String, String> v : variants.entrySet()) {
          long[] r = measure(c, new Form("v", v.getValue(), false), new Random(42));
          out.append(
              String.format(
                  Locale.ROOT,
                  "%d in flight, %s: %d µs, %d%% found%n",
                  count,
                  v.getKey(),
                  r[0],
                  r[1]));
        }
        // each arm alone, without a limit: the engine's plan unbent by LIMIT 1
        for (int k = 0; k < arms.size(); k++) {
          for (String sql : List.of(Writes.anyRow(engine, arms.get(k)), arms.get(k))) {
            long[] r = measure(c, new Form("arm", sql, false), new Random(42));
            out.append(
                String.format(
                    Locale.ROOT,
                    "%d in flight, arm %d%s: %d µs, %d%% found%n",
                    count,
                    k,
                    sql.equals(arms.get(k)) ? " no limit" : "",
                    r[0],
                    r[1]));
            if (count == 10) {
              out.append(plan(c, engine, sql)).append('\n');
            }
          }
        }
        // the arms as separate statements, stopping at the first that finds a row
        long[] times = new long[RUNS];
        int found = 0;
        List<PreparedStatement> ps = new ArrayList<>();
        for (String arm : arms) {
          ps.add(c.prepareStatement(arm));
        }
        Random random = new Random(42);
        for (int i = -50; i < RUNS; i++) {
          long start = System.nanoTime();
          boolean row = false;
          int region = random.nextInt(REGIONS);
          for (PreparedStatement p : ps) {
            p.setMaxRows(1);
            p.setInt(1, region);
            try (ResultSet rs = p.executeQuery()) {
              row = rs.next();
            }
            if (row) {
              break;
            }
          }
          if (i >= 0) {
            times[i] = System.nanoTime() - start;
            found += row ? 1 : 0;
          }
        }
        for (PreparedStatement p : ps) {
          p.close();
        }
        Arrays.sort(times);
        out.append(
            String.format(
                Locale.ROOT,
                "%d in flight, separate statements, no limit: %d µs, %d%% found%n%n",
                count,
                times[RUNS / 2] / 1000,
                100 * found / RUNS));
      }
    }
    Path file = Path.of("build/bench/inflight-arms-" + e.name().toLowerCase(Locale.ROOT) + ".txt");
    Files.createDirectories(file.getParent());
    Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
  }

  /** The estimated plan, region 7 bound as a literal. */
  private static String plan(Connection c, Engine engine, String sql) throws SQLException {
    String literal = sql.replace("?", "7");
    StringBuilder out = new StringBuilder();
    try (java.sql.Statement st = c.createStatement()) {
      if (engine == Engine.SQLSERVER) {
        st.execute("SET SHOWPLAN_TEXT ON");
        try {
          boolean rs = st.execute(literal);
          while (rs || st.getUpdateCount() != -1) {
            if (rs) {
              try (ResultSet r = st.getResultSet()) {
                while (r.next()) {
                  out.append(r.getString(1)).append('\n');
                }
              }
            }
            rs = st.getMoreResults();
          }
        } finally {
          st.execute("SET SHOWPLAN_TEXT OFF");
        }
      } else if (engine != Engine.ORACLE) {
        try (ResultSet r = st.executeQuery("EXPLAIN " + literal)) {
          int cols = r.getMetaData().getColumnCount();
          while (r.next()) {
            for (int i = 1; i <= cols; i++) {
              out.append(r.getString(i)).append(i < cols ? " | " : "\n");
            }
          }
        }
      }
    }
    return out.toString();
  }

  /** Median µs and percent of runs that return a row, after a warm-up. */
  private static long[] measure(Connection c, Form f, Random random) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(f.sql)) {
      long[] times = new long[RUNS];
      int found = 0;
      for (int i = -50; i < RUNS; i++) {
        long start = System.nanoTime();
        boolean row = run(ps, f, random);
        if (i >= 0) {
          times[i] = System.nanoTime() - start;
          found += row ? 1 : 0;
        }
      }
      Arrays.sort(times);
      return new long[] {times[RUNS / 2] / 1000, 100L * found / RUNS};
    }
  }

  private static boolean run(PreparedStatement ps, Form f, Random random) throws SQLException {
    int region = random.nextInt(f.range ? REGIONS - 9 : REGIONS);
    int count = (int) f.sql.chars().filter(ch -> ch == '?').count();
    for (int p = 1; p <= count; p++) {
      // a range binds low, high per arm
      ps.setInt(p, f.range && p % 2 == 0 ? region + 9 : region);
    }
    try (ResultSet rs = ps.executeQuery()) {
      return rs.next();
    }
  }

  /**
   * Puts {@code count} of the table's {@code rows} in flight in the metadata table alone, spread
   * (picked a step one above rows / count apart, so that consecutive picks fall in consecutive
   * regions), their before images the rows' own values; the rest committed.
   */
  private static void inFlight(
      Engines e, Connection c, TableInfo t, int rows, int count, Map<String, String> before)
      throws SQLException {
    Engine engine = e.engine();
    String id = engine.quote(e.storedColumn("id"));
    String state = engine.quote("tx_state");
    List<String> clear = new ArrayList<>();
    List<String> set = new ArrayList<>();
    for (Map.Entry<String, String> b : before.entrySet()) {
      String col = engine.quote("before_" + e.storedColumn(b.getKey()));
      clear.add(col + " = NULL");
      set.add(col + " = " + b.getValue());
    }
    // only the rows that change: a write of every row leaves the engines dead versions to clean
    e.execute(
        c,
        "UPDATE "
            + SplitRead.meta(engine, t)
            + " SET "
            + state
            + " = 3, "
            + engine.quote("before_tx_id")
            + " = NULL, "
            + String.join(", ", clear)
            + " WHERE "
            + state
            + " IN (1, 2)");
    if (count > 0) {
      e.execute(
          c,
          "UPDATE "
              + SplitRead.meta(engine, t)
              + " SET "
              + state
              + " = 1, "
              + engine.quote("before_tx_id")
              + " = 'b', "
              + String.join(", ", set)
              + " WHERE "
              + mod(id, rows / count + 1)
              + " = 7");
    }
  }

  /** {@code x} modulo {@code m} in every engine's SQL. */
  private static String mod(String x, int m) {
    return "(" + x + " - " + m + " * FLOOR(" + x + " / " + m + "))";
  }

  /**
   * Starts {@code writers} threads of ScalarDB transactions, each updating one random row of o, or
   * of o or c when {@code vc} is given, until {@code stop}; {@code commits} counts them.
   */
  private static List<Thread> writers(
      DistributedTransactionManager tm,
      String ns,
      String vo,
      @Nullable String vc,
      int writers,
      AtomicBoolean stop,
      AtomicLong commits) {
    List<Thread> threads = new ArrayList<>();
    for (int w = 0; w < writers; w++) {
      Random random = new Random(w);
      Thread th =
          new Thread(
              () -> {
                while (!stop.get()) {
                  try {
                    DistributedTransaction tx = tm.begin();
                    try {
                      boolean order = vc == null || random.nextBoolean();
                      tx.update(
                          Update.newBuilder()
                              .namespace(ns)
                              .table(order ? vo : Objects.requireNonNull(vc))
                              .partitionKey(id(1 + random.nextInt(order ? ROWS : CUSTOMERS)))
                              .value(intColumn(order ? "amount" : "tier", random.nextInt(1000)))
                              .build());
                      tx.commit();
                      commits.incrementAndGet();
                    } catch (Exception ex) {
                      tx.rollback();
                    }
                  } catch (Exception ignored) {
                    // a conflict between writers: the next one
                  }
                }
              });
      th.start();
      threads.add(th);
    }
    return threads;
  }

  private static final int READERS = 4;

  /**
   * With {@code -Dnativejdbc.bench.checkPlan=true} only, on Oracle: why the predicate's check of a
   * grouped read of o is slow on one instance and not another. The server and the statistics of o
   * and its metadata table, then, with 100 rows in flight held fixed and again beside 4 ScalarDB
   * writers, the median of the table-wide check, the predicate's check, each of its two halves and
   * the read, each statement its own SERIALIZABLE transaction as a driver reader's, and the
   * predicate's check's plan as estimated and as run; written to {@code
   * build/bench/inflight-plan-<engine>.txt}.
   */
  private static void checkPlan(
      Engines e, TransactionFactory factory, String ns, String vo, TableInfo o) throws Exception {
    if (e.engine() != Engine.ORACLE) {
      return;
    }
    Engine engine = e.engine();
    List<Form> forms = single(e, o);
    String region = "o." + engine.quote(e.storedColumn("region"));
    String eq = " o WHERE " + region + " = ?";
    Map<String, String> checks = new LinkedHashMap<>();
    checks.put("table-wide", forms.get(0).sql());
    checks.put("predicate", forms.get(1).sql());
    checks.put(
        "before images alone",
        Writes.anyRow(
            engine, "SELECT 1 FROM " + SplitRead.inFlight(engine, o, o.dataColumns()) + eq));
    checks.put(
        "after images alone",
        Writes.anyRow(
            engine, "SELECT 1 FROM " + SplitRead.afterImages(engine, o, o.dataColumns()) + eq));
    checks.put("read", forms.get(3).sql());
    StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "## %s (%s)%n%n", e,
        System.getProperty("nativejdbc.bench.tier", "local")));
    try (Connection c = e.open()) {
      out.append(c.getMetaData().getDatabaseProductVersion().replace('\n', ' ')).append("\n\n");
      String owner = "'" + o.namespace().replace("'", "''") + "'";
      String tables =
          "'" + o.dataTable().replace("'", "''") + "', '" + Objects.requireNonNull(o.metadataTable()).replace("'", "''") + "'";
      out.append(
          rows(
              c,
              "SELECT table_name || ': num_rows=' || num_rows || ' blocks=' || blocks"
                  + " || ' analyzed=' || TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI')"
                  + " || ' locked=' || stattype_locked FROM all_tab_statistics WHERE owner = "
                  + owner + " AND table_name IN (" + tables + ") AND object_type = 'TABLE'"));
      out.append(
          rows(
              c,
              "SELECT table_name || '.' || column_name || ': distinct=' || num_distinct"
                  + " || ' nulls=' || num_nulls || ' histogram=' || histogram"
                  + " FROM all_tab_col_statistics WHERE owner = " + owner
                  + " AND table_name IN (" + tables + ") ORDER BY table_name, column_name"));
      out.append(
          rows(
              c,
              "SELECT i.table_name || ' ' || i.index_name || ' (' || LISTAGG(ic.column_name, ', ')"
                  + " WITHIN GROUP (ORDER BY ic.column_position) || ') ' || i.status"
                  + " FROM all_indexes i JOIN all_ind_columns ic ON ic.index_owner = i.owner"
                  + " AND ic.index_name = i.index_name WHERE i.table_owner = " + owner
                  + " AND i.table_name IN (" + tables + ")"
                  + " GROUP BY i.table_name, i.index_name, i.status ORDER BY 1"));
      out.append('\n');

      inFlight(e, c, o, ROWS, 100, oBefore(e));
      out.append("### 100 rows in flight, no writer\n\n");
      checkPlan(c, checks, out);
      inFlight(e, c, o, ROWS, 0, oBefore(e));
      try (DistributedTransactionManager tm = factory.getTransactionManager()) {
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> threads = writers(tm, ns, vo, null, READERS, stop, new AtomicLong());
        try {
          Thread.sleep(2000);
          out.append("### beside 4 ScalarDB writers\n\n");
          checkPlan(c, checks, out);
        } finally {
          stop.set(true);
          for (Thread th : threads) {
            th.join();
          }
        }
      }
    }
    System.out.println(out);
    Path file = Path.of("build/bench/inflight-plan-" + e.name().toLowerCase(Locale.ROOT) + ".txt");
    Files.createDirectories(file.getParent());
    Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
  }

  private static void checkPlan(Connection c, Map<String, String> checks, StringBuilder out)
      throws SQLException {
    c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    c.setAutoCommit(false);
    Random random = new Random(42);
    for (Map.Entry<String, String> check : checks.entrySet()) {
      try (PreparedStatement ps = c.prepareStatement(check.getValue())) {
        long[] times = new long[30];
        int found = 0;
        for (int i = -5; i < times.length; i++) {
          long start = System.nanoTime();
          boolean row = run(ps, new Form(check.getKey(), check.getValue(), false), random);
          c.commit();
          if (i >= 0) {
            times[i] = System.nanoTime() - start;
            found += row ? 1 : 0;
          }
        }
        Arrays.sort(times);
        out.append(
            String.format(
                Locale.ROOT,
                "%s: %.1f ms median, %.1f ms max, %d of %d found%n",
                check.getKey(),
                times[times.length / 2] / 1e6,
                times[times.length - 1] / 1e6,
                found,
                times.length));
      }
    }
    String sql = Objects.requireNonNull(checks.get("predicate"));
    out.append("\nThe predicate's check, estimated (region 7):\n");
    try (java.sql.Statement st = c.createStatement()) {
      st.execute("EXPLAIN PLAN FOR " + sql.replace("?", "7"));
      out.append(rows(c, "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, NULL,"
          + " 'TYPICAL +PREDICATE'))"));
    } catch (SQLException ex) {
      out.append("not available: ").append(String.valueOf(ex.getMessage()).trim()).append('\n');
    }
    out.append("\nAs run (region 7, its last execution):\n");
    try (java.sql.Statement st = c.createStatement()) {
      st.execute("ALTER SESSION SET statistics_level = ALL");
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setInt(1, 7);
        ps.setInt(2, 7);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
        }
      }
      out.append(rows(c, "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL,"
          + " NULL, 'ALLSTATS LAST +PEEKED_BINDS +PREDICATE'))"));
      st.execute("ALTER SESSION SET statistics_level = TYPICAL");
    } catch (SQLException ex) {
      out.append("not available: ").append(String.valueOf(ex.getMessage()).trim()).append('\n');
    }
    c.commit();
    // back to the connection's defaults: inFlight's UPDATE at SERIALIZABLE can fail, ORA-08177
    c.setAutoCommit(true);
    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    out.append('\n');
  }

  /** The first column of every row, one per line. */
  private static String rows(Connection c, String sql) throws SQLException {
    StringBuilder out = new StringBuilder();
    try (java.sql.Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.append(rs.getString(1)).append('\n');
      }
    }
    return out.toString();
  }

  /**
   * With {@code -Dnativejdbc.bench.writers=true} only: {@link #READERS} driver transactions at
   * SNAPSHOT, each two grouped reads of o by a random region, retried on {@code 40001}, alongside
   * ScalarDB writers on o, with {@code scalardbnative.predicateChecks} off and on; written to
   * {@code build/bench/inflight-writers-<engine>.md}.
   */
  private static void besideWriters(Engines e, TransactionFactory factory, String ns, String vo)
      throws Exception {
    StringBuilder out =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "%n## %s (%s)%n%nBeside ScalarDB writers: %d driver transactions at SNAPSHOT, each two grouped reads"
                    + " of o by a random region, retried on 40001, for %d s, alongside ScalarDB"
                    + " writers updating random rows of o%n%n| writers | predicateChecks |"
                    + " committed/s | retries per commit | p50 ms | p99 ms | ScalarDB commits/s"
                    + " |%n|---|---|---|---|---|---|---|%n",
                e,
                System.getProperty("nativejdbc.bench.tier", "local"),
                READERS,
                LIVE_MS / 1000));
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      // a pass not reported: the first after the setup's backfill runs slow
      besideWriters(e, tm, ns, vo, 0, true);
      for (int writers : new int[] {0, 1, 4, 16}) {
        for (boolean on : new boolean[] {false, true}) {
          out.append(besideWriters(e, tm, ns, vo, writers, on));
        }
      }
    }
    System.out.println(out);
    Path file =
        Path.of("build/bench/inflight-writers-" + e.name().toLowerCase(Locale.ROOT) + ".md");
    Files.createDirectories(file.getParent());
    Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
  }

  private static String besideWriters(
      Engines e, DistributedTransactionManager tm, String ns, String vo, int writers, boolean on)
      throws Exception {
    Properties props = new Properties();
    props.setProperty("scalardbnative.predicateChecks", Boolean.toString(on));
    // the driver's own catalog TTL, not the tests' 0, which reads the catalog on every route
    props.setProperty(
        "scalardbnative.catalogTtlMs", String.valueOf(Catalog.DEFAULT_TTL.toMillis()));
    String sql = "SELECT region, SUM(amount) FROM ck.o WHERE region = ? GROUP BY region";
    AtomicBoolean stop = new AtomicBoolean();
    AtomicLong commits = new AtomicLong();
    List<Thread> threads = writers(tm, ns, vo, null, writers, stop, commits);
    long end = System.currentTimeMillis() + LIVE_MS;
    List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    AtomicLong retries = new AtomicLong();
    List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
    List<Thread> readers = new ArrayList<>();
    for (int r = 0; r < READERS; r++) {
      Random random = new Random(100 + r);
      Thread th =
          new Thread(
              () -> {
                try (Connection d = e.openDriver(props);
                    PreparedStatement ps = d.prepareStatement(sql)) {
                  d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                  d.setAutoCommit(false);
                  while (System.currentTimeMillis() < end) {
                    long start = System.nanoTime();
                    boolean committed = false;
                    while (!committed && System.currentTimeMillis() < end) {
                      try {
                        for (int i = 0; i < 2; i++) {
                          ps.setInt(1, random.nextInt(REGIONS));
                          try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                              rs.getLong(2);
                            }
                          }
                        }
                        d.commit();
                        committed = true;
                      } catch (SQLTransactionRollbackException x) {
                        d.rollback();
                        retries.incrementAndGet();
                      }
                    }
                    if (committed) {
                      latencies.add(System.nanoTime() - start);
                    }
                  }
                } catch (Exception x) {
                  failures.add(x);
                }
              });
      th.start();
      readers.add(th);
    }
    for (Thread th : readers) {
      th.join();
    }
    stop.set(true);
    for (Thread th : threads) {
      th.join();
    }
    if (!failures.isEmpty()) {
      throw failures.get(0);
    }
    long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
    int n = sorted.length;
    return String.format(
        Locale.ROOT,
        "| %d | %s | %d | %.2f | %.1f | %.1f | %d |%n",
        writers,
        on ? "on" : "off",
        n * 1000L / LIVE_MS,
        n == 0 ? Double.NaN : (double) retries.get() / n,
        n == 0 ? Double.NaN : sorted[n / 2] / 1e6,
        n == 0 ? Double.NaN : sorted[Math.min(n - 1, n * 99 / 100)] / 1e6,
        commits.get() * 1000 / LIVE_MS);
  }

  /** One row of the live table: ScalarDB writers, and the checks run in turn by one reader. */
  private static String live(
      Engines e,
      DistributedTransactionManager tm,
      String ns,
      String vo,
      String vc,
      List<Form> checks,
      int writers)
      throws Exception {
    AtomicBoolean stop = new AtomicBoolean();
    AtomicLong commits = new AtomicLong();
    List<Thread> threads = writers(tm, ns, vo, vc, writers, stop, commits);
    long[] runs = new long[checks.size()];
    long[] found = new long[checks.size()];
    Random random = new Random(7);
    long end = System.currentTimeMillis() + LIVE_MS;
    try (Connection c = e.open()) {
      List<PreparedStatement> ps = new ArrayList<>();
      for (Form f : checks) {
        ps.add(c.prepareStatement(f.sql));
      }
      for (int i = 0; System.currentTimeMillis() < end; i++) {
        int k = i % checks.size();
        runs[k]++;
        found[k] += run(ps.get(k), checks.get(k), random) ? 1 : 0;
      }
      for (PreparedStatement p : ps) {
        p.close();
      }
    } finally {
      stop.set(true);
      for (Thread th : threads) {
        th.join();
      }
    }
    StringBuilder row =
        new StringBuilder(
            String.format(Locale.ROOT, "| %d | %d |", writers, commits.get() * 1000 / LIVE_MS));
    for (int k = 0; k < checks.size(); k++) {
      row.append(
          String.format(
              Locale.ROOT, " %.2f%% of %d |", 100.0 * found[k] / Math.max(1, runs[k]), runs[k]));
    }
    return row.append('\n').toString();
  }
}
