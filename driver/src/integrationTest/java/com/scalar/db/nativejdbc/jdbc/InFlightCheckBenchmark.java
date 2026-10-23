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
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "PREDICTABLE_RANDOM",
      "DMI_RANDOM_USED_ONLY_ONCE"
    },
    justification = "benchmark SQL written in this file; seeded randoms, so runs repeat")
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

    // the diagnostics, one or both, instead of the benchmark
    boolean diagnostics = false;
    if (Boolean.getBoolean("nativejdbc.bench.arms")) {
      arms(e, o, cu, join(e, o, cu));
      diagnostics = true;
    }
    if (Boolean.getBoolean("nativejdbc.bench.coldPlan") && engine == Engine.SQLSERVER) {
      coldPlan(e, o, cu);
      diagnostics = true;
    }
    if (diagnostics) {
      return;
    }
    if (Boolean.getBoolean("nativejdbc.bench.checkPlan")) {
      checkPlan(e, factory, ns, vo, o);
      return;
    }
    if (Boolean.getBoolean("nativejdbc.bench.writers")) {
      besideWriters(e, factory, ns, vo, vc);
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
    Files.createDirectories(Objects.requireNonNull(file.getParent()));
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

  /**
   * The arms of the in-flight-pairs check, as the driver's: the join over every pair with a row in
   * flight.
   */
  private static List<String> pairArms(Engines e, TableInfo o, TableInfo c) {
    return pairArms(e, o, c, true);
  }

  /** As the driver's with {@code hinted} (on Oracle, through the in-flight index). */
  private static List<String> pairArms(Engines e, TableInfo o, TableInfo c, boolean hinted) {
    Engine engine = e.engine();
    String region = "o." + engine.quote(e.storedColumn("region"));
    String cid = "o." + engine.quote(e.storedColumn("cid"));
    String cId = "c." + engine.quote(e.storedColumn("id"));
    // per table, its pieces: the data table as it is (committed rows and the after images of rows
    // in flight), the after images alone, the before images
    String od = data(engine, o);
    String oa = SplitRead.afterImages(engine, o, o.dataColumns(), hinted);
    String ob = SplitRead.inFlight(engine, o, o.dataColumns(), hinted);
    String cd = data(engine, c);
    String ca = SplitRead.afterImages(engine, c, c.dataColumns(), hinted);
    String cb = SplitRead.inFlight(engine, c, c.dataColumns(), hinted);
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
   * as separate statements; each arm on its own with and without the limit, and its plan; the join
   * read's own cost beside them; at 100 the check's plan as run, with its rows per step; written to
   * {@code build/bench/inflight-arms-<engine>.txt}.
   */
  private static void arms(Engines e, TableInfo o, TableInfo cu, List<Form> joinForms)
      throws Exception {
    Engine engine = e.engine();
    // without the driver's hints, which the Oracle variants below add
    List<String> arms = pairArms(e, o, cu, false);
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
      // an optimizer setting to plan under, as
      // -Dnativejdbc.bench.session="optimizer_features_enable
      // = '19.1.0'" for Oracle 19c's plans on Oracle 23
      String session = System.getProperty("nativejdbc.bench.session", "");
      if (!session.isEmpty()) {
        e.execute(c, "ALTER SESSION SET " + session);
        out.append("ALTER SESSION SET ").append(session).append("\n\n");
      }
      if (engine == Engine.ORACLE && Boolean.getBoolean("nativejdbc.bench.gatherStats")) {
        // the data tables' statistics, as the nightly job would have them; the setup's on the
        // metadata tables are locked
        for (TableInfo t : List.of(o, cu)) {
          e.execute(
              c,
              "BEGIN DBMS_STATS.GATHER_TABLE_STATS('\""
                  + t.namespace()
                  + "\"', '\""
                  + t.dataTable()
                  + "\"'); END;");
        }
        out.append("statistics gathered on the data tables\n\n");
      }
      for (int count : new int[] {10, 100}) {
        inFlight(e, c, o, ROWS, count, oBefore(e));
        inFlight(e, c, cu, CUSTOMERS, count, cBefore(e));
        for (Form f : joinForms) {
          if (f.name().startsWith("read")) {
            long[] r = measure(c, f, new Random(42));
            out.append(
                String.format(Locale.ROOT, "%d in flight, %s: %d µs%n", count, f.name(), r[0]));
          }
        }
        if (count == 100) {
          // a region the check finds nothing for, the usual case, which runs every arm through
          String check = variants.values().iterator().next();
          int clear = -1;
          try (PreparedStatement ps = c.prepareStatement(check)) {
            for (int r = 0; r < REGIONS && clear < 0; r++) {
              int marks = (int) check.chars().filter(ch -> ch == '?').count();
              for (int p = 1; p <= marks; p++) {
                ps.setInt(p, r);
              }
              try (ResultSet rs = ps.executeQuery()) {
                clear = rs.next() ? -1 : r;
              }
            }
          }
          out.append("the check as run, 100 in flight, region ")
              .append(clear)
              .append(", which it finds nothing for:\n")
              .append(actualPlan(c, engine, check.replace("?", String.valueOf(clear))))
              .append('\n');
        }
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
        if (engine == Engine.ORACLE) {
          oracleHints(c, e, o, cu, arms, count, out);
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
    Files.createDirectories(Objects.requireNonNull(file.getParent()));
    Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
  }

  /**
   * On Oracle, the check and each arm with hints the driver could add: NO_MERGE in the pieces with
   * rows in flight, LEADING the arm's piece with rows in flight, and each piece's metadata table
   * read through the in-flight index; median µs, and at 10 rows in flight arm 1's plans.
   */
  private static void oracleHints(
      Connection c,
      Engines e,
      TableInfo o,
      TableInfo cu,
      List<String> arms,
      int count,
      StringBuilder out)
      throws Exception {
    Engine engine = e.engine();
    Map<String, java.util.function.BiFunction<String, Integer, String>> hints =
        new LinkedHashMap<>();
    hints.put(
        "NO_MERGE in the pieces", (arm, k) -> arm.replace("(SELECT ", "(SELECT /*+ NO_MERGE */ "));
    // the arm's piece with rows in flight: o's in arms 0, 1 and 4, c's in 2 and 3
    hints.put(
        "LEADING(the piece in flight)",
        (arm, k) ->
            arm.replaceFirst(
                "^SELECT 1 ", "SELECT /*+ LEADING(" + (k == 2 || k == 3 ? "c" : "o") + ") */ 1 "));
    hints.put(
        "INDEX(m, the in-flight index) in the pieces, as the driver",
        (arm, k) -> inflightIndexHint(engine, o, cu, arm));
    for (Map.Entry<String, java.util.function.BiFunction<String, Integer, String>> h :
        hints.entrySet()) {
      List<String> hinted = new ArrayList<>();
      for (int k = 0; k < arms.size(); k++) {
        hinted.add(h.getValue().apply(arms.get(k), k));
      }
      long[] all =
          measure(
              c,
              new Form("v", Writes.anyRow(engine, String.join(" UNION ALL ", hinted)), false),
              new Random(42));
      out.append(
          String.format(
              Locale.ROOT,
              "%d in flight, %s: the check %d µs, %d%% found; arms",
              count,
              h.getKey(),
              all[0],
              all[1]));
      for (String arm : hinted) {
        out.append(' ')
            .append(
                measure(c, new Form("arm", Writes.anyRow(engine, arm), false), new Random(42))[0]);
      }
      out.append(" µs\n");
      if (count == 10) {
        out.append(plan(c, engine, Writes.anyRow(engine, hinted.get(1)))).append('\n');
      }
    }
  }

  /** Each piece's metadata table, as {@code m}, read through the in-flight index. */
  private static String inflightIndexHint(Engine engine, TableInfo o, TableInfo cu, String arm) {
    String out = arm;
    for (TableInfo t : List.of(o, cu)) {
      String hint = "/*+ INDEX(m " + engine.quote(t.inflightIndex(engine)) + ") */ ";
      String m = " FROM " + SplitRead.meta(engine, t) + " m";
      // a before-image piece reads the metadata table alone; an after-image piece joins it
      int at = 0;
      while ((at = out.indexOf(m, at)) >= 0) {
        int open = out.lastIndexOf("(SELECT ", at);
        out = out.substring(0, open + 8) + hint + out.substring(open + 8);
        at += m.length() + hint.length();
      }
      String joined = " JOIN " + SplitRead.meta(engine, t) + " m ON ";
      at = 0;
      while ((at = out.indexOf(joined, at)) >= 0) {
        int open = out.lastIndexOf("(SELECT ", at);
        out = out.substring(0, open + 8) + hint + out.substring(open + 8);
        at += joined.length() + hint.length();
      }
    }
    return out;
  }

  /**
   * With {@code -Dnativejdbc.bench.coldPlan=true} only, on SQL Server: the in-flight-pairs check
   * planned while no row is in flight, as the statistics of the filtered in-flight index have it
   * from the setup on, then run with 100 rows in flight per table on that plan; and planned again
   * after the statistics are updated. Without a hint and with hints, median µs; the plans without a
   * hint and with the driver's; written to {@code build/bench/inflight-coldplan-sqlserver.txt}.
   */
  private static void coldPlan(Engines e, TableInfo o, TableInfo cu) throws Exception {
    Engine engine = e.engine();
    String check = Writes.anyRow(engine, String.join(" UNION ALL ", pairArms(e, o, cu, false)));
    Map<String, String> variants = new LinkedHashMap<>();
    variants.put("no hint", check);
    variants.put("OPTION (RECOMPILE)", check + " OPTION (RECOMPILE)");
    variants.put("OPTION (LOOP JOIN)", check + " OPTION (LOOP JOIN)");
    variants.put(
        "FORCESEEK on the metadata tables",
        check
            .replace(" m WHERE ", " m WITH (FORCESEEK) WHERE ")
            .replace(" m ON ", " m WITH (FORCESEEK) ON "));
    String hinted = check;
    for (TableInfo t : List.of(o, cu)) {
      String m = SplitRead.meta(engine, t) + " m";
      hinted =
          hinted.replace(m, m + " WITH (INDEX(" + engine.quote(t.inflightIndex(engine)) + "))");
    }
    variants.put("INDEX(the in-flight index) on the metadata tables", hinted);
    StringBuilder out = new StringBuilder();
    try (Connection c = e.open()) {
      for (Map.Entry<String, String> v : variants.entrySet()) {
        Form f = new Form("v", v.getValue(), false);
        inFlight(e, c, o, ROWS, 0, oBefore(e));
        inFlight(e, c, cu, CUSTOMERS, 0, cBefore(e));
        e.execute(
            c,
            "UPDATE STATISTICS " + SplitRead.meta(engine, o),
            "UPDATE STATISTICS " + SplitRead.meta(engine, cu),
            "DBCC FREEPROCCACHE");
        long[] none = measure(c, f, new Random(42));
        inFlight(e, c, o, ROWS, 100, oBefore(e));
        inFlight(e, c, cu, CUSTOMERS, 100, cBefore(e));
        long[] stale = measure(c, f, new Random(42));
        String stalePlan = plan(c, engine, v.getValue());
        e.execute(
            c,
            "UPDATE STATISTICS " + SplitRead.meta(engine, o),
            "UPDATE STATISTICS " + SplitRead.meta(engine, cu),
            "DBCC FREEPROCCACHE");
        long[] fresh = measure(c, f, new Random(42));
        out.append(
            String.format(
                Locale.ROOT,
                "%s: none in flight %d µs; 100 in flight on that plan %d µs, %d%% found;"
                    + " planned again after UPDATE STATISTICS %d µs, %d%% found%n",
                v.getKey(),
                none[0],
                stale[0],
                stale[1],
                fresh[0],
                fresh[1]));
        if (!v.getKey().startsWith("OPTION") && !v.getKey().startsWith("FORCESEEK")) {
          out.append("\nplan, statistics from no row in flight:\n")
              .append(stalePlan)
              .append("\nplan, statistics updated:\n")
              .append(plan(c, engine, v.getValue()))
              .append('\n');
        }
      }
    }
    Path file = Path.of("build/bench/inflight-coldplan-sqlserver.txt");
    Files.createDirectories(Objects.requireNonNull(file.getParent()));
    Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
  }

  /**
   * The plan as run, of a statement with its literals bound, with what each step did: SQL Server's
   * STATISTICS PROFILE (rows, executions per step), Oracle's DBMS_XPLAN.DISPLAY_CURSOR with the
   * session's statistics (actual rows, time, buffers); other engines EXPLAIN ANALYZE, MySQL's and
   * MariaDB's ANALYZE. An error is written in its place.
   */
  private static String actualPlan(Connection c, Engine engine, String literal) {
    StringBuilder out = new StringBuilder();
    try (java.sql.Statement st = c.createStatement()) {
      switch (engine) {
        case SQLSERVER -> {
          st.execute("SET STATISTICS PROFILE ON");
          try {
            boolean rs = st.execute(literal);
            while (rs || st.getUpdateCount() != -1) {
              if (rs) {
                try (ResultSet r = st.getResultSet()) {
                  int cols = r.getMetaData().getColumnCount();
                  boolean profile = cols > 3;
                  while (r.next()) {
                    if (profile) {
                      out.append(r.getString("Rows"))
                          .append(" rows, ")
                          .append(r.getString("Executes"))
                          .append(" executions: ")
                          .append(r.getString("StmtText"))
                          .append('\n');
                    }
                  }
                }
              }
              rs = st.getMoreResults();
            }
          } finally {
            st.execute("SET STATISTICS PROFILE OFF");
          }
        }
        case ORACLE -> {
          st.execute("ALTER SESSION SET statistics_level = ALL");
          try (ResultSet r = st.executeQuery(literal)) {
            while (r.next()) {
              // the rows are read for the cursor's statistics
            }
          }
          try (ResultSet r =
              st.executeQuery(
                  "SELECT plan_table_output FROM"
                      + " TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL, NULL, 'ALLSTATS LAST'))")) {
            while (r.next()) {
              out.append(r.getString(1)).append('\n');
            }
          }
          st.execute("ALTER SESSION SET statistics_level = TYPICAL");
        }
        case POSTGRESQL -> {
          try (ResultSet r = st.executeQuery("EXPLAIN ANALYZE " + literal)) {
            while (r.next()) {
              out.append(r.getString(1)).append('\n');
            }
          }
        }
        default -> {
          try (ResultSet r =
              st.executeQuery(
                  (engine == Engine.MARIADB ? "ANALYZE FORMAT=JSON " : "EXPLAIN ANALYZE ")
                      + literal)) {
            while (r.next()) {
              out.append(r.getString(1)).append('\n');
            }
          }
        }
      }
    } catch (SQLException x) {
      out.append("no plan: ").append(x.getMessage()).append('\n');
    }
    return out.toString();
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
      } else if (engine == Engine.ORACLE) {
        st.execute("EXPLAIN PLAN FOR " + literal);
        try (ResultSet r =
            st.executeQuery("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY)")) {
          while (r.next()) {
            out.append(r.getString(1)).append('\n');
          }
        }
      } else {
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
    StringBuilder out =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "## %s (%s)%n%n",
                e,
                System.getProperty("nativejdbc.bench.tier", "local")));
    try (Connection c = e.open()) {
      out.append(c.getMetaData().getDatabaseProductVersion().replace('\n', ' ')).append("\n\n");
      String owner = "'" + o.namespace().replace("'", "''") + "'";
      String tables =
          "'"
              + o.dataTable().replace("'", "''")
              + "', '"
              + Objects.requireNonNull(o.metadataTable()).replace("'", "''")
              + "'";
      out.append(
          rows(
              c,
              "SELECT table_name || ': num_rows=' || num_rows || ' blocks=' || blocks"
                  + " || ' analyzed=' || TO_CHAR(last_analyzed, 'YYYY-MM-DD HH24:MI')"
                  + " || ' locked=' || stattype_locked FROM all_tab_statistics WHERE owner = "
                  + owner
                  + " AND table_name IN ("
                  + tables
                  + ") AND object_type = 'TABLE'"));
      out.append(
          rows(
              c,
              "SELECT table_name || '.' || column_name || ': distinct=' || num_distinct"
                  + " || ' nulls=' || num_nulls || ' histogram=' || histogram"
                  + " FROM all_tab_col_statistics WHERE owner = "
                  + owner
                  + " AND table_name IN ("
                  + tables
                  + ") ORDER BY table_name, column_name"));
      out.append(
          rows(
              c,
              "SELECT i.table_name || ' ' || i.index_name || ' (' || LISTAGG(ic.column_name, ', ')"
                  + " WITHIN GROUP (ORDER BY ic.column_position) || ') ' || i.status"
                  + " FROM all_indexes i JOIN all_ind_columns ic ON ic.index_owner = i.owner"
                  + " AND ic.index_name = i.index_name WHERE i.table_owner = "
                  + owner
                  + " AND i.table_name IN ("
                  + tables
                  + ")"
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
    Files.createDirectories(Objects.requireNonNull(file.getParent()));
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
      out.append(
          rows(
              c,
              "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, NULL,"
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
      out.append(
          rows(
              c,
              "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(NULL,"
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

  // -Dnativejdbc.bench.writersRead=join: the writers mode reads o JOIN c, ScalarDB writing both
  /**
   * With {@code -Dnativejdbc.bench.breakdown=true}, the writers mode also writes where the readers'
   * time goes, per configuration: the reads and the commits, and on SQL Server the statements' own
   * time and the waits, to {@code build/bench/inflight-breakdown-<engine>.txt}.
   */
  private static final boolean BREAKDOWN = Boolean.getBoolean("nativejdbc.bench.breakdown");

  /**
   * SQL Server's account since the plan cache and the wait statistics were cleared: the statements
   * by elapsed time, with executions, mean elapsed and CPU µs and logical reads; the waits by time.
   */
  private static String serverBreakdown(Engines e) throws SQLException {
    StringBuilder out = new StringBuilder("statements by elapsed time:\n");
    try (Connection c = e.open();
        java.sql.Statement st = c.createStatement()) {
      try (ResultSet r =
          st.executeQuery(
              "SELECT TOP 12 s.execution_count, s.total_elapsed_time / s.execution_count,"
                  + " s.total_worker_time / s.execution_count,"
                  + " s.total_logical_reads / s.execution_count, s.total_elapsed_time / 1000,"
                  + " SUBSTRING(t.text, s.statement_start_offset / 2 + 1, 300)"
                  + " FROM sys.dm_exec_query_stats s CROSS APPLY sys.dm_exec_sql_text(s.sql_handle) t"
                  + " ORDER BY s.total_elapsed_time DESC")) {
        while (r.next()) {
          out.append(
              String.format(
                  Locale.ROOT,
                  "  %d ms in all, %d runs, %d µs elapsed / %d µs CPU / %d reads each: %s%n",
                  r.getLong(5),
                  r.getLong(1),
                  r.getLong(2),
                  r.getLong(3),
                  r.getLong(4),
                  r.getString(6).replaceAll("\\s+", " ")));
        }
      }
      out.append("waits by time:\n");
      try (ResultSet r =
          st.executeQuery(
              "SELECT TOP 10 wait_type, wait_time_ms, waiting_tasks_count, signal_wait_time_ms"
                  + " FROM sys.dm_os_wait_stats WHERE wait_time_ms > 0 AND wait_type NOT IN"
                  + " ('SLEEP_TASK', 'LAZYWRITER_SLEEP', 'SQLTRACE_INCREMENTAL_FLUSH_SLEEP',"
                  + " 'XE_TIMER_EVENT', 'XE_DISPATCHER_WAIT', 'REQUEST_FOR_DEADLOCK_SEARCH',"
                  + " 'LOGMGR_QUEUE', 'CHECKPOINT_QUEUE', 'DIRTY_PAGE_POLL', 'HADR_FILESTREAM_IOMGR_IOCOMPLETION',"
                  + " 'SP_SERVER_DIAGNOSTICS_SLEEP', 'BROKER_TO_FLUSH', 'SOS_WORK_DISPATCHER',"
                  + " 'QDS_PERSIST_TASK_MAIN_LOOP_SLEEP', 'QDS_ASYNC_QUEUE', 'BROKER_EVENTHANDLER',"
                  + " 'SLEEP_SYSTEMTASK', 'WAIT_XTP_OFFLINE_CKPT_NEW_LOG', 'XE_LIVE_TARGET_TVF',"
                  + " 'PVS_PREALLOCATE', 'ONDEMAND_TASK_QUEUE', 'BROKER_TRANSMITTER',"
                  + " 'DISPATCHER_QUEUE_SEMAPHORE', 'FT_IFTS_SCHEDULER_IDLE_WAIT',"
                  + " 'SQLTRACE_BUFFER_FLUSH', 'CXCONSUMER')"
                  + " ORDER BY wait_time_ms DESC")) {
        while (r.next()) {
          out.append(
              String.format(
                  Locale.ROOT,
                  "  %s: %d ms over %d waits (%d ms signal)%n",
                  r.getString(1),
                  r.getLong(2),
                  r.getLong(3),
                  r.getLong(4)));
        }
      }
    }
    return out.toString();
  }

  private static final boolean JOIN_READ =
      "join".equals(System.getProperty("nativejdbc.bench.writersRead"));

  /**
   * With {@code -Dnativejdbc.bench.writers=true} only: {@link #READERS} driver transactions at
   * SNAPSHOT, each two grouped reads of o by a random region, retried on {@code 40001}, alongside
   * ScalarDB writers on o, with {@code scalardbnative.predicateChecks} off and on; written to
   * {@code build/bench/inflight-writers-<engine>.md}. With {@code
   * -Dnativejdbc.bench.writersRead=join} as well, the reads are of o JOIN c on c.id = o.cid and the
   * writers write both tables; written to {@code build/bench/inflight-writers-<engine>-join.md}.
   */
  private static void besideWriters(
      Engines e, TransactionFactory factory, String ns, String vo, String vc) throws Exception {
    StringBuilder out =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "%n## %s (%s)%n%nBeside ScalarDB writers: %d driver transactions at SNAPSHOT, each two grouped reads"
                    + (JOIN_READ ? " of o JOIN c" : " of o")
                    + " by a random region, retried on 40001, for %d s, alongside ScalarDB"
                    + (JOIN_READ
                        ? " writers updating random rows of o and c"
                        : " writers updating random rows of o")
                    + "%n%n| writers | predicateChecks |"
                    + " committed/s | retries per commit | p50 ms | p99 ms | ScalarDB commits/s"
                    + " |%n|---|---|---|---|---|---|---|%n",
                e,
                System.getProperty("nativejdbc.bench.tier", "local"),
                READERS,
                LIVE_MS / 1000));
    StringBuilder breakdown = new StringBuilder();
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      // a pass not reported: the first after the setup's backfill runs slow
      besideWriters(e, tm, ns, vo, vc, 0, true, null);
      for (int writers : new int[] {0, 1, 4, 16}) {
        for (boolean on : new boolean[] {false, true}) {
          out.append(besideWriters(e, tm, ns, vo, vc, writers, on, BREAKDOWN ? breakdown : null));
        }
      }
    }
    System.out.println(out);
    if (BREAKDOWN) {
      Path file =
          Path.of("build/bench/inflight-breakdown-" + e.name().toLowerCase(Locale.ROOT) + ".txt");
      Files.createDirectories(Objects.requireNonNull(file.getParent()));
      Files.writeString(file, breakdown.toString(), StandardCharsets.UTF_8);
    }
    Path file =
        Path.of(
            "build/bench/inflight-writers-"
                + e.name().toLowerCase(Locale.ROOT)
                + (JOIN_READ ? "-join" : "")
                + ".md");
    Files.createDirectories(Objects.requireNonNull(file.getParent()));
    Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
  }

  private static String besideWriters(
      Engines e,
      DistributedTransactionManager tm,
      String ns,
      String vo,
      String vc,
      int writers,
      boolean on,
      @Nullable StringBuilder breakdown)
      throws Exception {
    Properties props = new Properties();
    props.setProperty("scalardbnative.predicateChecks", Boolean.toString(on));
    // the driver's own catalog TTL, not the tests' 0, which reads the catalog on every route
    props.setProperty(
        "scalardbnative.catalogTtlMs", String.valueOf(Catalog.DEFAULT_TTL.toMillis()));
    String sql =
        JOIN_READ
            ? "SELECT o.region, SUM(o.amount) FROM ck.o o JOIN ck.c c ON c.id = o.cid"
                + " WHERE o.region = ? GROUP BY o.region"
            : "SELECT region, SUM(amount) FROM ck.o WHERE region = ? GROUP BY region";
    AtomicBoolean stop = new AtomicBoolean();
    AtomicLong commits = new AtomicLong();
    if (breakdown != null && e.engine() == Engine.SQLSERVER) {
      try (Connection c = e.open()) {
        e.execute(
            c,
            "DBCC FREEPROCCACHE",
            "DBCC SQLPERF('sys.dm_os_wait_stats', CLEAR) WITH NO_INFOMSGS");
      }
    }
    List<Thread> threads = writers(tm, ns, vo, JOIN_READ ? vc : null, writers, stop, commits);
    long end = System.currentTimeMillis() + LIVE_MS;
    List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
    AtomicLong retries = new AtomicLong();
    // the readers' time by phase: the reads, executeQuery to the last row; the commits; rollbacks
    AtomicLong readNs = new AtomicLong();
    AtomicLong reads = new AtomicLong();
    AtomicLong commitNs = new AtomicLong();
    AtomicLong commitCalls = new AtomicLong();
    AtomicLong rollbackNs = new AtomicLong();
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
                          long at = System.nanoTime();
                          try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                              rs.getLong(2);
                            }
                          }
                          readNs.addAndGet(System.nanoTime() - at);
                          reads.incrementAndGet();
                        }
                        long at = System.nanoTime();
                        commitCalls.incrementAndGet();
                        d.commit();
                        commitNs.addAndGet(System.nanoTime() - at);
                        committed = true;
                      } catch (SQLTransactionRollbackException x) {
                        long at = System.nanoTime();
                        d.rollback();
                        rollbackNs.addAndGet(System.nanoTime() - at);
                        retries.incrementAndGet();
                      }
                    }
                    if (committed) {
                      latencies.add(System.nanoTime() - start);
                    }
                  }
                } catch (SQLException | RuntimeException x) {
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
    if (breakdown != null) {
      breakdown.append(
          String.format(
              Locale.ROOT,
              "%n=== %d writers, predicateChecks %s: %d reads, %.2f ms each; %d commits, %.2f ms"
                  + " each; rollbacks %.0f ms in all; readers' time %.0f ms (%d threads x %d"
                  + " ms)%n",
              writers,
              on ? "on" : "off",
              reads.get(),
              reads.get() == 0 ? Double.NaN : readNs.get() / 1e6 / reads.get(),
              commitCalls.get(),
              commitCalls.get() == 0 ? Double.NaN : commitNs.get() / 1e6 / commitCalls.get(),
              rollbackNs.get() / 1e6,
              (readNs.get() + commitNs.get() + rollbackNs.get()) / 1e6,
              READERS,
              LIVE_MS));
      if (e.engine() == Engine.SQLSERVER) {
        breakdown.append(serverBreakdown(e));
      }
    }
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
