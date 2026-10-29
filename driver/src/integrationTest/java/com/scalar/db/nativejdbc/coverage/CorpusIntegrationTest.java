package com.scalar.db.nativejdbc.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestones T1 and T2 of the testing plan. With nothing in flight, the committed image of a
 * ScalarDB table is its data, so every statement of the corpus must return through the driver, on
 * the tables imported into ScalarDB in each layout, what it returns through the vendor driver on
 * the same tables left plain (T1). Prepared rows do not change the committed image, so the plain
 * tables stay the expected answer while ScalarDB transactions hold a share of the keys prepared
 * (T2). Writes {@code build/coverage/<engine>.md}: each statement's route and outcome in both
 * phases.
 */
@EnabledIfSystemProperty(named = "nativejdbc.coverage", matches = "true")
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      "PREDICTABLE_RANDOM"
    },
    justification =
        "the corpus's statements and the test's own DDL; a seeded generator, so a run reproduces")
class CorpusIntegrationTest {

  /** The corpus's ScalarDB tables; {@code r} stays plain in every schema. */
  private static final List<String> IMPORTED = List.of("c", "o", "k");

  /** The ScalarDB layouts, by the last letter of their schema: decoupled, inline (milestone 10). */
  private static final Map<String, Map<String, String>> LAYOUTS =
      Map.of("d", Map.of("transaction-metadata-decoupling", "true"), "i", Map.of());

  /** The SQLState of a statement the driver refuses (Errors.unsupported). */
  private static final String REFUSED = "0AN01";

  /** The relative tolerance of floating-point values. */
  private static final double TOLERANCE = 1e-9;

  /**
   * Every engine with each data set: the small one built for semantics, and the same with a few
   * thousand rows more, so that the engines choose other plans. Schemas c* and l* respectively.
   */
  static List<Arguments> engines() {
    List<Arguments> out = new ArrayList<>();
    for (Engines e : Engines.selected()) {
      out.add(Arguments.of(e, false));
      out.add(Arguments.of(e, true));
    }
    return out;
  }

  @ParameterizedTest
  @MethodSource("engines")
  void corpus_returnsThroughTheDriverWhatTheDatabaseReturns(Engines e, boolean large)
      throws Exception {
    Engine engine = e.engine();
    String set = large ? "l" : "c";
    String ddl = types(engine, resource("corpus/schema.sql"));
    try (Connection c = e.open()) {
      for (String schema : List.of(set + "p", set + "d", set + "i")) {
        e.execute(c, e.createSchema(schema));
        e.execute(c, statements(ddl.replace("{S}", schema)).toArray(String[]::new));
        if (large) {
          e.execute(c, largeData(e, schema).toArray(String[]::new));
        }
      }
    }
    try (DistributedTransactionAdmin admin = e.scalarDb().getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      for (Map.Entry<String, Map<String, String>> l : LAYOUTS.entrySet()) {
        String ns = e.storedSchema(set + l.getKey());
        admin.createNamespace(ns, true);
        for (String t : IMPORTED) {
          admin.importTable(ns, e.storedTable(t), l.getValue(), Map.of());
        }
      }
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      List<String> scalarDb = List.of(e.storedSchema(set + "d"), e.storedSchema(set + "i"));
      List<Setup.Result> results =
          Setup.run(
              catalog,
              c,
              t -> scalarDb.contains(t.namespace()),
              Duration.ofMinutes(2),
              List.of(),
              true);
      assertThat(results).allMatch(Setup.Result::succeeded, "setup succeeded");
    }

    StringBuilder report =
        new StringBuilder("# Read corpus on ")
            .append(engine)
            .append(large ? ", large data set" : ", small data set")
            .append("\n\n| phase | file | statement | layout | route | outcome |\n")
            .append("|---|---|---|---|---|---|\n");
    Tally tally = new Tally();
    long seed = Long.getLong("nativejdbc.seed", System.nanoTime());
    System.out.printf(
        Locale.ROOT, "CorpusIntegrationTest %s: -Dnativejdbc.seed=%d%n", engine, seed);
    try (Connection vendor = e.open();
        Connection driver = e.openDriver()) {
      runCorpus(engine, vendor, driver, set, "settled", report, tally);
      // T2: every key in flight on the small set; on the large one a share above the per-row
      // check's threshold on MySQL and MariaDB (Router.PER_ROW_ABOVE)
      List<TwoPhaseCommitTransaction> held = new ArrayList<>();
      try (TwoPhaseCommitTransactionManager tpc =
          e.scalarDb().getTwoPhaseCommitTransactionManager()) {
        try {
          InFlight.prepare(
              e, catalog, tpc, set, large ? 0.1 : 1.0, new Random(seed), held, new HashMap<>());
          // not vacuous: every table of both layouts holds rows prepared (1) and deleted (2), the
          // inline tables in themselves, the decoupled ones in their metadata tables
          for (String layout : List.of(set + "d", set + "i")) {
            for (String t : IMPORTED) {
              String ns = e.storedSchema(layout);
              String states =
                  layout.endsWith("d")
                      ? Objects.requireNonNull(
                          catalog.lookup(List.of(), ns, e.storedTable(t)).metadataTable())
                      : e.storedTable(t);
              try (Statement st = vendor.createStatement();
                  ResultSet rs =
                      st.executeQuery(
                          "SELECT COUNT(*) FROM "
                              + engine.quote(ns)
                              + "."
                              + engine.quote(states)
                              + " WHERE "
                              + engine.quote("tx_state")
                              + " IN (1, 2)")) {
                rs.next();
                int n = rs.getInt(1);
                assertThat(n).as("rows in flight in " + layout + "." + t).isPositive();
                report.insert(
                    report.indexOf("\n\n") + 2,
                    String.format(Locale.ROOT, "In flight in %s.%s: %d rows.%n%n", layout, t, n));
              }
            }
          }
          runCorpus(engine, vendor, driver, set, "in flight", report, tally);
        } finally {
          for (TwoPhaseCommitTransaction tx : held) {
            tx.rollback();
          }
        }
      }
    }
    int passed = tally.passed;
    int refused = tally.refused;
    List<String> failures = tally.failures;
    Map<String, Integer> routes = tally.routes;
    report.insert(
        report.indexOf("\n\n") + 2,
        String.format(
            Locale.ROOT,
            "%d passed, %d refused, %d failed (each statement in both layouts and both phases)."
                + " Routes: %s; an rc_T fallback costs 24-1800x on joins.%n%n",
            passed,
            refused,
            failures.size(),
            routes));
    Path out =
        Path.of(
            "build",
            "coverage",
            engine.name().toLowerCase(Locale.ROOT) + (large ? "-large" : "") + ".md");
    Files.createDirectories(Objects.requireNonNull(out.getParent()));
    Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    assertThat(failures).as("statements whose rows differ; see " + out).isEmpty();
  }

  /** The outcomes so far. */
  static final class Tally {
    int passed;
    int refused;
    final List<String> failures = new ArrayList<>();
    final Map<String, Integer> routes = new java.util.TreeMap<>();
  }

  /**
   * Every statement of the corpus, against the plain schema and through the driver on both layouts.
   */
  private static void runCorpus(
      Engine engine,
      Connection vendor,
      Connection driver,
      String set,
      String phase,
      StringBuilder report,
      Tally tally)
      throws Exception {
    for (Corpus.Entry st : Corpus.load(engine)) {
      String sql = types(engine, st.sql());
      Side expected = Side.run(vendor, tables(sql, set + "p"));
      for (String layout : List.of(set + "d", set + "i")) {
        String through = tables(sql, layout);
        String route = route(driver, through);
        if (phase.equals("settled")) {
          tally.routes.merge(route.startsWith("refused") ? "refused" : route, 1, Integer::sum);
        }
        Side actual = Side.run(driver, through);
        String outcome = expectRefusal(compare(expected, actual, st.ordered()), st);
        if (outcome.startsWith("refused")) {
          tally.refused++;
        } else if (outcome.equals("pass") || outcome.startsWith("pass,")) {
          tally.passed++;
        } else {
          tally.failures.add(
              phase + ", " + st.file() + ": " + through + " (" + layout + "): " + outcome);
        }
        report
            .append("| ")
            .append(phase)
            .append(" | ")
            .append(st.file())
            .append(" | `")
            .append(through.replace("|", "\\|"))
            .append("` | ")
            .append(layout)
            .append(" | ")
            .append(route)
            .append(" | ")
            .append(outcome.replace("|", "\\|").replace('\n', ' '))
            .append(" |\n");
      }
    }
  }

  /**
   * The large data set's rows, beside the small set's: 3,000 customers over 50 regions with
   * repeated names and NULLs, 10,000 orders over them and over no customer, 2,500 composite keys.
   */
  static List<String> largeData(Engines e, String schema) {
    boolean tsql = e.engine() == Engine.SQLSERVER;
    java.util.function.BiFunction<String, Integer, String> mod =
        (x, n) -> tsql ? "(" + x + " % " + n + ")" : "MOD(" + x + ", " + n + ")";
    String nullEvery7 = "CASE WHEN " + mod.apply("g", 7) + " = 0 THEN NULL ELSE ";
    return List.of(
        "INSERT INTO "
            + schema
            + ".c (id, name, region, rate) SELECT g, CASE "
            + mod.apply("g", 4)
            + " WHEN 0 THEN 'ann' WHEN 1 THEN 'bob' WHEN 2 THEN 'cid' END, "
            + nullEvery7
            + mod.apply("g", 50)
            + " END, "
            + mod.apply("g", 9)
            + " * 0.25 FROM "
            + e.series(1001, 4000),
        "INSERT INTO "
            + schema
            + ".o (id, customer_id, total, note) SELECT g, "
            + nullEvery7
            + "1001 + "
            + mod.apply("g * 7", 3100)
            + " END, "
            + mod.apply("g", 500)
            + ", CASE "
            + mod.apply("g", 3)
            + " WHEN 0 THEN 'gift' WHEN 1 THEN NULL ELSE 'std' END FROM "
            + e.series(100001, 110000),
        "INSERT INTO "
            + schema
            + ".k (a, b, v) SELECT 100 + "
            + mod.apply("g", 500)
            + ", 1 + FLOOR((g - 1) / 500), "
            + nullEvery7
            + mod.apply("g", 13)
            + " END FROM "
            + e.series(1, 2500));
  }

  /** The corpus's table placeholders qualified with a schema. */
  static String tables(String sql, String schema) {
    String out = sql;
    for (String t : List.of("c", "o", "k", "r")) {
      out = out.replace("{" + t + "}", schema + "." + t);
    }
    return out;
  }

  /** The engine's spellings of the corpus's type and literal placeholders. */
  static String types(Engine engine, String sql) {
    boolean oracle = engine == Engine.ORACLE;
    boolean tsql = engine == Engine.SQLSERVER;
    String out =
        tsql // T-SQL has no typed literals
            ? sql.replaceAll("DATE '([^']*)'", "CAST('$1' AS DATE)")
                .replaceAll("TIMESTAMP '([^']*)'", "CAST('$1' AS DATETIME2)")
            : sql;
    return out.replace("{INT}", oracle ? "NUMBER(10)" : "INT")
        .replace("{DBL}", oracle ? "BINARY_DOUBLE" : tsql ? "FLOAT" : "DOUBLE PRECISION")
        .replace("{BOOL}", oracle ? "NUMBER(1)" : tsql ? "BIT" : "BOOLEAN")
        .replace("{TS}", tsql ? "DATETIME2" : "TIMESTAMP")
        .replace("{TRUE}", oracle || tsql ? "1" : "TRUE")
        .replace("{FALSE}", oracle || tsql ? "0" : "FALSE");
  }

  /**
   * How the driver routes a statement, from the text it sends: unchanged, the split read, the read
   * views {@code rc_T}, or refused with its reason.
   */
  static String route(Connection driver, String sql) {
    String sent;
    try {
      sent = driver.nativeSQL(sql);
    } catch (SQLException ex) {
      return "refused: " + ex.getMessage();
    }
    if (sent.strip().equals(sql.strip())) {
      return "unchanged";
    }
    String lower = sent.toLowerCase(Locale.ROOT); // Oracle's views are RC_T
    if (lower.matches("(?s).*\\.[\"`\\[]?rc_.*")) {
      return "rc_T";
    }
    return lower.contains("union all") ? "split" : "rewritten";
  }

  /**
   * A refusal of a statement not marked {@code -- refused}, or none of one marked, as a failure.
   */
  static String expectRefusal(String outcome, Corpus.Entry st) {
    if (outcome.startsWith("refused") == st.refused()) {
      return outcome;
    }
    return st.refused()
        ? "marked -- refused, but " + outcome
        : "not marked -- refused, but " + outcome;
  }

  /**
   * The outcome of a statement through the driver against the database: "pass", "refused: ...", or
   * what differs.
   */
  static String compare(Side expected, Side actual, boolean ordered) {
    if (expected.error != null) {
      if (actual.error == null) {
        return "the database fails (" + expected.error + "), the driver returns rows";
      }
      return actual.state().equals(expected.state())
          ? "pass, both fail with " + expected.state()
          : "the database fails with " + expected.state() + ", the driver with " + actual.state();
    }
    if (actual.error != null) {
      return REFUSED.equals(actual.state())
          ? "refused: " + actual.error
          : "the driver fails: " + actual.error;
    }
    if (!actual.columns.equals(expected.columns)) {
      return "columns " + actual.columns + ", expected " + expected.columns;
    }
    List<List<String>> want = new ArrayList<>(expected.rows);
    List<List<String>> got = new ArrayList<>(actual.rows);
    if (!ordered) {
      want.sort(Side.ROW_ORDER);
      got.sort(Side.ROW_ORDER);
    }
    if (want.size() != got.size()) {
      return got.size() + " rows, expected " + want.size() + ": " + got + " against " + want;
    }
    for (int i = 0; i < want.size(); i++) {
      if (!sameRow(want.get(i), got.get(i), expected.floating)) {
        return "row " + i + " is " + got.get(i) + ", expected " + want.get(i);
      }
    }
    // reported apart, counted as a pass: a split read's UNION ALL loses NOT NULL (todo 302)
    return actual.nullable.equals(expected.nullable)
        ? "pass"
        : "pass, nullability " + actual.nullable + ", expected " + expected.nullable;
  }

  private static boolean sameRow(List<String> want, List<String> got, List<Boolean> floating) {
    for (int i = 0; i < want.size(); i++) {
      String w = want.get(i);
      String g = got.get(i);
      if (Objects.equals(w, g)) {
        continue;
      }
      if (w == null || g == null || !floating.get(i)) {
        return false;
      }
      double a = Double.parseDouble(w);
      double b = Double.parseDouble(g);
      if (Math.abs(a - b) > TOLERANCE * Math.max(Math.abs(a), Math.abs(b))) {
        return false;
      }
    }
    return true;
  }

  /** A statement's result on one side: its columns and rows, or its error. */
  static final class Side {
    static final java.util.Comparator<List<String>> ROW_ORDER =
        (a, b) -> String.valueOf(a).compareTo(String.valueOf(b));

    /** Per column: label and JDBC type. */
    final List<String> columns = new ArrayList<>();

    /** Per column: {@link ResultSetMetaData#isNullable}. */
    final List<Integer> nullable = new ArrayList<>();

    final List<Boolean> floating = new ArrayList<>();
    final List<List<String>> rows = new ArrayList<>();
    @Nullable String error;
    private String state = "";

    String state() {
      return state;
    }

    static Side run(Connection c, String sql) {
      try (Statement st = c.createStatement();
          ResultSet rs = st.executeQuery(sql)) {
        return of(rs);
      } catch (SQLException ex) {
        Side s = new Side();
        s.error = ex.getSQLState() + " " + ex.getMessage();
        s.state = Objects.requireNonNullElse(ex.getSQLState(), "");
        return s;
      }
    }

    /** The columns and rows of a result. */
    static Side of(ResultSet rs) throws SQLException {
      Side s = new Side();
      {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        for (int i = 1; i <= n; i++) {
          int type = md.getColumnType(i);
          s.columns.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT) + ":" + type);
          s.nullable.add(md.isNullable(i));
          s.floating.add(
              type == Types.DOUBLE
                  || type == Types.FLOAT
                  || type == Types.REAL
                  || type == Types.NUMERIC
                  || type == Types.DECIMAL);
        }
        while (rs.next()) {
          List<String> row = new ArrayList<>(n);
          for (int i = 1; i <= n; i++) {
            row.add(rs.getString(i));
          }
          s.rows.add(row);
        }
      }
      return s;
    }
  }

  /** The statements of a script, one per {@code ;} at the end of a line, comments dropped. */
  static List<String> statements(String script) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (String line : script.split("\n", -1)) {
      String t = line.strip();
      if (t.isEmpty() || t.startsWith("--")) {
        continue;
      }
      cur.append(cur.length() == 0 ? "" : " ").append(t);
      if (t.endsWith(";")) {
        out.add(cur.substring(0, cur.length() - 1));
        cur.setLength(0);
      }
    }
    return Collections.unmodifiableList(out);
  }

  static String resource(String name) throws IOException {
    try (InputStream in =
        Objects.requireNonNull(
            CorpusIntegrationTest.class.getClassLoader().getResourceAsStream(name), name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
