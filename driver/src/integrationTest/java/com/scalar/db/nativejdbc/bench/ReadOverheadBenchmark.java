package com.scalar.db.nativejdbc.bench;

import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How much slower a read of the committed image is than the same query on the plain tables, per
 * engine, per query shape and per rewrite. Run with {@code -Dnativejdbc.bench=true}; the result is
 * written to {@code build/bench/read-overhead-<engine>.md}.
 *
 * <p>Two ScalarDB-style tables, {@code c} (customers, 100k rows) and {@code o} (orders, 500k rows,
 * five per customer), each with a metadata table holding 100 rows in flight whose before image
 * equals the current values, so every rewrite must return the rows of the plain query. The
 * rewrites, through the vendor driver directly so only the SQL is measured:
 *
 * <ul>
 *   <li>BASE: the query on the data tables, ignoring the metadata; the target.
 *   <li>VIEW_LJ: through the UNION ALL view whose committed arm is a left join on the metadata.
 *   <li>VIEW_AJ: the same view, the committed arm an anti-join on the in-flight rows.
 *   <li>VIEW_CASE: through the single left-join view with a CASE per column.
 *   <li>SPLIT_LJ, SPLIT_AJ: the query itself repeated per combination of arms of its ScalarDB
 *       tables and joined with UNION ALL, with the left-join or the anti-join committed arm.
 * </ul>
 */
@EnabledIfSystemProperty(named = "nativejdbc.bench", matches = "true")
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "SBSC_USE_STRINGBUFFER_CONCATENATION",
      "PREDICTABLE_RANDOM",
      "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD"
    },
    justification =
        "benchmark SQL written in this file; the random parameters are meant to repeat; one proxy"
            + " per test run")
class ReadOverheadBenchmark {

  enum Mode {
    BASE,
    VIEW_LJ,
    VIEW_AJ,
    VIEW_CASE,
    SPLIT_LJ,
    SPLIT_AJ,
    SPLIT_NI,
    SPLIT_HJ,
    SPLIT_HD,
    /** SPLIT_AJ with an aggregate computed per arm and combined on top. */
    SPLIT_PA,
    /** SPLIT_HD with an aggregate computed per arm and combined on top. */
    SPLIT_PH,
    /**
     * n + 1 copies instead of 2^n: all tables committed, then per table t in order, the tables
     * before t committed, t in flight, and each table after t in either image, joined per row
     * through LATERAL or CROSS APPLY; NOT EXISTS as the committed form.
     */
    SPLIT_LT,
    /** SPLIT_LT with the DISTINCT in-flight set as the committed form. */
    SPLIT_LH,
    /**
     * SPLIT_AJ in the copy with every table committed, the left join on the metadata (a key lookup
     * per row) for the committed tables of the copies with a table in flight.
     */
    SPLIT_MX,
    /** SPLIT_MX with SPLIT_HD in the copy with every table committed. */
    SPLIT_MH,
    /**
     * SPLIT_AJ with each table of a copy replaced by a derived table holding that copy's rows under
     * the table's own column names, so that the rest of the statement is left as written.
     */
    SPLIT_DAJ,
    /** SPLIT_HD as derived tables. */
    SPLIT_DHD,
    /** SPLIT_MX as derived tables. */
    SPLIT_DMX,
    /** SPLIT_MH as derived tables. */
    SPLIT_DMH,
    /** SPLIT_PA as derived tables. */
    SPLIT_DPA,
    /** SPLIT_PH as derived tables. */
    SPLIT_DPH
  }

  enum Param {
    CUSTOMER,
    REGION,
    NAME,
    /** Regions below 3, 30 or 300: 300, 3k or 30k customers. */
    SPAN3,
    SPAN30,
    SPAN300
  }

  private static final Map<String, String> TABLE = Map.of("C", "c", "O", "o", "R", "r");
  private static final Map<String, List<String>> NON_KEY =
      Map.of(
          "c",
          List.of("name", "region"),
          "o",
          List.of("customer_id", "total"),
          "r",
          List.of("label"));

  /** One query shape: its natural text, and for the split modes the text repeated per arm. */
  static final class Query {
    final String name;
    final String sql;
    final String split;
    final @Nullable String outer;
    final List<String> splitAliases;
    final Param[] params;
    @Nullable String partialInner;
    @Nullable String partialOuter;
    @Nullable Graph graph;

    /** The same query as a join graph, for SPLIT_LT and SPLIT_LH. */
    Query graph(Graph g) {
      graph = g;
      return this;
    }

    /** Computes the aggregate per arm and combines the arms' results with {@code outer}. */
    Query partial(String inner, String outer) {
      partialInner = inner;
      partialOuter = outer;
      return this;
    }

    Query(
        String name,
        String sql,
        @Nullable String split,
        @Nullable String outer,
        List<String> splitAliases,
        Param... params) {
      this.name = name;
      this.sql = sql;
      this.split = split == null ? sql : split;
      this.outer = outer;
      this.splitAliases = splitAliases;
      this.params = params;
    }
  }

  /**
   * An inner join of ScalarDB tables: "x:C" per table in order, "x.col=y.col" per join condition
   * (id is the key), the filter "x.col" compared with a parameter, and the selected "x.col".
   */
  static final class Graph {
    final List<String> tables;
    final List<String> edges;
    final String filter;
    final List<String> select;

    Graph(List<String> tables, List<String> edges, String filter, List<String> select) {
      this.tables = tables;
      this.edges = edges;
      this.filter = filter;
      this.select = select;
    }
  }

  // {C:x} / {O:x}: the table with alias x; {x.col}: a non-key column of x; {W:x}: x's state
  // condition; a suffix /A or /B forces an arm. {LIMIT}: three rows. {ARMS}: the split arms.
  static final List<Query> QUERIES =
      List.of(
          new Query(
              "key lookup",
              "SELECT a.id, {a.name} FROM {C:a} WHERE {W:a} AND a.id = ?",
              null,
              null,
              List.of("a"),
              Param.CUSTOMER),
          new Query(
              "non-key filter (100 rows)",
              "SELECT a.id FROM {C:a} WHERE {W:a} AND {a.region} = ?",
              null,
              null,
              List.of("a"),
              Param.REGION),
          new Query(
              "FK filter, ORDER BY, LIMIT 3",
              "SELECT b.id, {b.total} FROM {O:b} WHERE {W:b} AND {b.customer_id} = ? ORDER BY b.id"
                  + "{LIMIT}",
              "SELECT b.id AS id, {b.total} AS total FROM {O:b} WHERE {W:b} AND {b.customer_id} = ?",
              "SELECT v.id, v.total FROM ({ARMS}) v ORDER BY v.id{LIMIT}",
              List.of("b"),
              Param.CUSTOMER),
          new Query(
              "COUNT by FK",
              "SELECT COUNT(*) FROM {O:b} WHERE {W:b} AND {b.customer_id} = ?",
              "SELECT b.id AS id FROM {O:b} WHERE {W:b} AND {b.customer_id} = ?",
              "SELECT COUNT(*) FROM ({ARMS}) v",
              List.of("b"),
              Param.CUSTOMER),
          new Query(
              "plain table JOIN on key (10 rows)",
              "SELECT p.id, {a.name} FROM bn.p p JOIN {C:a} ON a.id = p.cid WHERE {W:a}",
              null,
              null,
              List.of("a")),
          new Query(
              "key JOIN FK, both ScalarDB",
              "SELECT a.id, {b.total} FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                  + " WHERE {W:a} AND {W:b} AND a.id = ?",
              null,
              null,
              List.of("a", "b"),
              Param.CUSTOMER),
          new Query(
              "plain table JOIN on non-key (1000 rows)",
              "SELECT p.id, a.id FROM bn.p p JOIN {C:a} ON {a.region} = p.region WHERE {W:a}",
              null,
              null,
              List.of("a")),
          new Query(
              "IN subquery on non-key",
              "SELECT b.id FROM {O:b} WHERE {W:b} AND {b.customer_id} IN"
                  + " (SELECT a.id FROM {C:a} WHERE {W:a} AND {a.region} = ?)",
              // split at the top over the subquery's table too: an IN over a UNION is a dependent
              // subquery on MySQL; the subquery returns a's key, so no row is in two arms
              null,
              null,
              List.of("b", "a"),
              Param.REGION),
          new Query(
              "LEFT JOIN, ScalarDB on both sides",
              "SELECT a.id, b.id FROM {C:a} LEFT JOIN {O:b} ON {b.customer_id} = a.id AND {W:b}"
                  + " WHERE {W:a} AND a.id = ?",
              "SELECT a.id AS aid, b.id AS bid FROM {C:a} JOIN {O:b/A} ON {b.customer_id/A} = a.id"
                  + " AND {W:b/A} WHERE {W:a} AND a.id = ?"
                  + " UNION ALL SELECT a.id, b.id FROM {C:a} JOIN {O:b/B} ON {b.customer_id/B} ="
                  + " a.id AND {W:b/B} WHERE {W:a} AND a.id = ?"
                  + " UNION ALL SELECT a.id, NULL FROM {C:a} WHERE {W:a} AND a.id = ?"
                  + " AND NOT EXISTS (SELECT 1 FROM {O:e/A} WHERE {W:e/A} AND {e.customer_id/A} ="
                  + " a.id) AND NOT EXISTS (SELECT 1 FROM {O:f/B} WHERE {W:f/B} AND"
                  + " {f.customer_id/B} = a.id)",
              null,
              List.of("a"),
              Param.CUSTOMER),
          new Query(
                  "full scan: COUNT(*) of 100k rows",
                  "SELECT COUNT(*) FROM {C:a} WHERE {W:a}",
                  "SELECT a.id AS id FROM {C:a} WHERE {W:a}",
                  "SELECT COUNT(*) FROM ({ARMS}) v",
                  List.of("a"))
              .partial(
                  "SELECT COUNT(*) AS n FROM {C:a} WHERE {W:a}", "SELECT SUM(v.n) FROM ({ARMS}) v"),
          new Query(
              "non-key filter JOIN FK, both ScalarDB",
              "SELECT a.id, b.id FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                  + " WHERE {W:a} AND {W:b} AND {a.region} = ?",
              null,
              null,
              List.of("a", "b"),
              Param.REGION),
          new Query(
                  "full scan: SUM of 500k rows",
                  "SELECT SUM({b.total}) FROM {O:b} WHERE {W:b}",
                  "SELECT {b.total} AS total FROM {O:b} WHERE {W:b}",
                  "SELECT SUM(v.total) FROM ({ARMS}) v",
                  List.of("b"))
              .partial(
                  "SELECT SUM({b.total}) AS s FROM {O:b} WHERE {W:b}",
                  "SELECT SUM(v.s) FROM ({ARMS}) v"),
          new Query(
                  "full scan: GROUP BY of 100k rows",
                  "SELECT {a.region}, COUNT(*) FROM {C:a} WHERE {W:a} GROUP BY {a.region}",
                  "SELECT {a.region} AS region FROM {C:a} WHERE {W:a}",
                  "SELECT v.region, COUNT(*) FROM ({ARMS}) v GROUP BY v.region",
                  List.of("a"))
              .partial(
                  "SELECT {a.region} AS region, COUNT(*) AS n FROM {C:a} WHERE {W:a} GROUP BY"
                      + " {a.region}",
                  "SELECT v.region, SUM(v.n) FROM ({ARMS}) v GROUP BY v.region"),
          new Query(
              "full scan: unindexed filter",
              "SELECT a.id FROM {C:a} WHERE {W:a} AND {a.name} = ?",
              null,
              null,
              List.of("a"),
              Param.NAME),
          new Query(
              "many rows: JOIN FK, 300 customers (1.5k rows)",
              "SELECT a.id, b.id FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                  + " WHERE {W:a} AND {W:b} AND {a.region} < ?",
              null,
              null,
              List.of("a", "b"),
              Param.SPAN3),
          new Query(
              "many rows: JOIN FK, 3k customers (15k rows)",
              "SELECT a.id, b.id FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                  + " WHERE {W:a} AND {W:b} AND {a.region} < ?",
              null,
              null,
              List.of("a", "b"),
              Param.SPAN30),
          new Query(
              "many rows: JOIN FK, 30k customers (150k rows)",
              "SELECT a.id, b.id FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                  + " WHERE {W:a} AND {W:b} AND {a.region} < ?",
              null,
              null,
              List.of("a", "b"),
              Param.SPAN300),
          new Query(
                  "3 tables: key lookup (5 rows)",
                  "SELECT g.id, {g.label}, a.id, {b.total} FROM {R:g} JOIN {C:a} ON {a.region} = g.id"
                      + " JOIN {O:b} ON {b.customer_id} = a.id WHERE {W:g} AND {W:a} AND {W:b}"
                      + " AND a.id = ?",
                  null,
                  null,
                  List.of("g", "a", "b"),
                  Param.CUSTOMER)
              .graph(
                  new Graph(
                      List.of("g:R", "a:C", "b:O"),
                      List.of("a.region=g.id", "b.customer_id=a.id"),
                      "a.id",
                      List.of("g.id", "g.label", "a.id", "b.total"))),
          new Query(
                  "3 tables: one region (500 rows)",
                  "SELECT g.id, {g.label}, a.id, b.id FROM {R:g} JOIN {C:a} ON {a.region} = g.id"
                      + " JOIN {O:b} ON {b.customer_id} = a.id WHERE {W:g} AND {W:a} AND {W:b}"
                      + " AND g.id = ?",
                  null,
                  null,
                  List.of("g", "a", "b"),
                  Param.REGION)
              .graph(
                  new Graph(
                      List.of("g:R", "a:C", "b:O"),
                      List.of("a.region=g.id", "b.customer_id=a.id"),
                      "g.id",
                      List.of("g.id", "g.label", "a.id", "b.id"))),
          new Query(
                  "4 tables: key lookup (25 rows)",
                  "SELECT g.id, {g.label}, a.id, b.id, e.id FROM {R:g} JOIN {C:a} ON {a.region} ="
                      + " g.id JOIN {O:b} ON {b.customer_id} = a.id JOIN {O:e} ON {e.customer_id} ="
                      + " a.id WHERE {W:g} AND {W:a} AND {W:b} AND {W:e} AND a.id = ?",
                  null,
                  null,
                  List.of("g", "a", "b", "e"),
                  Param.CUSTOMER)
              .graph(
                  new Graph(
                      List.of("g:R", "a:C", "b:O", "e:O"),
                      List.of("a.region=g.id", "b.customer_id=a.id", "e.customer_id=a.id"),
                      "a.id",
                      List.of("g.id", "g.label", "a.id", "b.id", "e.id"))),
          new Query(
                  "4 tables: one region (2.5k rows)",
                  "SELECT g.id, {g.label}, a.id, b.id, e.id FROM {R:g} JOIN {C:a} ON {a.region} ="
                      + " g.id JOIN {O:b} ON {b.customer_id} = a.id JOIN {O:e} ON {e.customer_id} ="
                      + " a.id WHERE {W:g} AND {W:a} AND {W:b} AND {W:e} AND g.id = ?",
                  null,
                  null,
                  List.of("g", "a", "b", "e"),
                  Param.REGION)
              .graph(
                  new Graph(
                      List.of("g:R", "a:C", "b:O", "e:O"),
                      List.of("a.region=g.id", "b.customer_id=a.id", "e.customer_id=a.id"),
                      "g.id",
                      List.of("g.id", "g.label", "a.id", "b.id", "e.id"))));

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void readOverhead(Engines e) throws Exception {
    Engine engine = e.engine();
    try (Connection admin = e.open()) {
      load(e, admin);
    }
    // -Dnativejdbc.bench.rttMs=1: measure through a proxy adding this round-trip time
    double rtt = Double.parseDouble(System.getProperty("nativejdbc.bench.rttMs", "0"));
    Matcher hostPort = Pattern.compile("(?<=//|@)([^:/;@]+):(\\d+)").matcher(e.url());
    if (!hostPort.find()) {
      throw new IllegalStateException("no host and port in " + e.url());
    }
    try (DelayProxy remote =
            rtt > 0
                ? new DelayProxy(hostPort.group(1), Integer.parseInt(hostPort.group(2)), rtt)
                : null;
        Connection c =
            remote == null
                ? e.open()
                : DriverManager.getConnection(
                    e.url().substring(0, hostPort.start())
                        + "127.0.0.1:"
                        + remote.port()
                        + e.url().substring(hostPort.end()),
                    e.username(),
                    e.password())) {
      proxy = remote;
      // -Dnativejdbc.bench.session=SET x = 1: a statement run once on the measuring connection
      String session = System.getProperty("nativejdbc.bench.session", "");
      if (!session.isEmpty()) {
        e.execute(c, session);
      }
      StringBuilder report = new StringBuilder();
      report
          .append("## ")
          .append(e)
          .append(
              "\n\nMedian ms over up to 200 runs (ratio to BASE); p95 in brackets. `!` marks rows"
                  + " that differ from BASE.\n\n| query |");
      List<Mode> modes = modes();
      for (Mode m : modes) {
        report.append(' ').append(m).append(" |");
      }
      report.append("\n|---|");
      for (int i = 0; i < modes.size(); i++) {
        report.append("---|");
      }
      report.append('\n');
      String explain = System.getProperty("nativejdbc.bench.explain", "");
      if (!explain.isEmpty()) {
        StringBuilder plans = new StringBuilder();
        for (Query q : QUERIES) {
          if (!Pattern.compile(explain).matcher(q.name).find()) {
            continue;
          }
          for (Mode m : modes) {
            String sql = render(q, m, engine);
            if (sql == null) {
              continue;
            }
            noParallel(c, engine, q, m, true);
            plans.append("==== ").append(q.name).append(" / ").append(m).append('\n');
            plans.append(sql).append('\n');
            plans.append(explain(c, engine, sql, q.params)).append('\n');
            noParallel(c, engine, q, m, false);
          }
        }
        Path out = Path.of("build", "bench", "explain-" + e + ".txt");
        Files.createDirectories(Path.of("build", "bench"));
        Files.writeString(out, plans.toString(), StandardCharsets.UTF_8);
        return;
      }
      String only = System.getProperty("nativejdbc.bench.only", "");
      for (Query q : QUERIES) {
        if (!Pattern.compile(only).matcher(q.name).find()) {
          continue;
        }
        report.append("| ").append(q.name).append(" |");
        List<String> expected = null;
        double base = 0;
        Map<Mode, String> sqls = new LinkedHashMap<>();
        for (Mode m : modes) {
          String sql = render(q, m, engine);
          if (sql != null) {
            sqls.put(m, sql);
          }
        }
        Map<Mode, Result> results =
            proxy == null ? sequential(c, e, q, sqls) : interleaved(c, e, q, sqls);
        for (Mode m : modes) {
          Result r = results.get(m);
          if (r == null) {
            report.append(sqls.containsKey(m) ? " error |" : " - |");
            continue;
          }
          if (m == Mode.BASE) {
            expected = r.rows;
            base = r.median;
          }
          report
              .append(
                  String.format(
                      Locale.ROOT, " %.2f (%.1fx) [%.2f]", r.median, r.median / base, r.p95))
              .append(r.rows.equals(expected) ? "" : " !")
              .append(" |");
          System.out.printf(
              Locale.ROOT,
              "BENCH %s | %s | %s | %.3f ms | rows %d%n",
              e,
              q.name,
              m,
              r.median,
              r.rows.size());
        }
        report.append('\n');
      }
      String suffix =
          (only.isEmpty() ? "" : "-" + only.replaceAll("\\W+", "_"))
              + (rtt > 0 ? "-rtt" + System.getProperty("nativejdbc.bench.rttMs") : "")
              + (HOT ? "-hot" : "");
      Path out = Path.of("build", "bench", "read-overhead-" + e + suffix + ".md");
      Files.createDirectories(Path.of("build", "bench"));
      Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    }
  }

  /** Each mode measured in turn; a mode that fails is left out. */
  private static Map<Mode, Result> sequential(
      Connection c, Engines e, Query q, Map<Mode, String> sqls) throws SQLException {
    Map<Mode, Result> results = new LinkedHashMap<>();
    for (Map.Entry<Mode, String> m : sqls.entrySet()) {
      noParallel(c, e.engine(), q, m.getKey(), true);
      try {
        results.put(m.getKey(), measure(c, m.getValue(), q.params));
      } catch (SQLException ex) {
        System.out.printf(
            "BENCH %s | %s | %s | error %s%n", e, q.name, m.getKey(), ex.getMessage());
      } finally {
        noParallel(c, e.engine(), q, m.getKey(), false);
      }
    }
    return results;
  }

  /**
   * The modes measured in rounds, each round running every mode once with the same parameters in a
   * rotating order, so that the network's slow spells fall on all modes alike: ten rounds of
   * warm-up (fewer past 5 s), then up to 300 rounds or 20 s, at least three. The PostgreSQL
   * settings of a split mode are applied outside the timing.
   */
  private static Map<Mode, Result> interleaved(
      Connection c, Engines e, Query q, Map<Mode, String> sqls) throws SQLException {
    Map<Mode, PreparedStatement> statements = new LinkedHashMap<>();
    Map<Mode, List<String>> rows = new LinkedHashMap<>();
    Map<Mode, List<Double>> times = new LinkedHashMap<>();
    try {
      for (Map.Entry<Mode, String> m : sqls.entrySet()) {
        try {
          PreparedStatement ps = c.prepareStatement(m.getValue());
          statements.put(m.getKey(), ps);
          rows.put(m.getKey(), timed(c, e, q, m.getKey(), ps, 4242, 7, null));
          times.put(m.getKey(), new ArrayList<>());
        } catch (SQLException ex) {
          statements.remove(m.getKey());
          System.out.printf(
              "BENCH %s | %s | %s | error %s%n", e, q.name, m.getKey(), ex.getMessage());
        }
      }
      List<Mode> order = new ArrayList<>(times.keySet());
      Random random = new Random(42);
      long start = System.nanoTime();
      int warmUp = 10;
      for (int round = 0; round < warmUp + 300; round++) {
        long elapsed = System.nanoTime() - start;
        if (round < warmUp && elapsed > 5_000_000_000L) {
          warmUp = round; // a slow query: fewer warm-up rounds
        }
        if (round >= warmUp + 3 && elapsed > 20_000_000_000L) {
          break;
        }
        int customer = customer(random);
        int region = region(random);
        Collections.rotate(order, 1);
        for (Mode m : order) {
          timed(
              c,
              e,
              q,
              m,
              Objects.requireNonNull(statements.get(m)),
              customer,
              region,
              round < warmUp ? null : times.get(m));
        }
      }
    } finally {
      for (PreparedStatement ps : statements.values()) {
        ps.close();
      }
    }
    Map<Mode, Result> results = new LinkedHashMap<>();
    for (Map.Entry<Mode, List<Double>> m : times.entrySet()) {
      List<Double> t = m.getValue();
      Collections.sort(t);
      results.put(
          m.getKey(),
          new Result(
              t.get(t.size() / 2),
              t.get((int) Math.min(t.size() - 1, t.size() * 0.95)),
              Objects.requireNonNull(rows.get(m.getKey()))));
    }
    return results;
  }

  // some drivers ask the server for the parameter metadata each time
  private static final Map<PreparedStatement, Integer> PLACEHOLDERS = new java.util.HashMap<>();

  private static int placeholders(PreparedStatement ps) throws SQLException {
    Integer n = PLACEHOLDERS.get(ps);
    if (n == null) {
      n = ps.getParameterMetaData().getParameterCount();
      PLACEHOLDERS.put(ps, n);
    }
    return n;
  }

  private static List<String> timed(
      Connection c,
      Engines e,
      Query q,
      Mode m,
      PreparedStatement ps,
      int customer,
      int region,
      @Nullable List<Double> times)
      throws SQLException {
    noParallel(c, e.engine(), q, m, true);
    try {
      long t0 = System.nanoTime();
      List<String> rows = run(ps, placeholders(ps), q.params, customer, region);
      if (times != null) {
        times.add((System.nanoTime() - t0) / 1e6);
      }
      return rows;
    } finally {
      noParallel(c, e.engine(), q, m, false);
    }
  }

  /** -Dnativejdbc.bench.modes=BASE,SPLIT_AJ: the modes to run; BASE always runs first. */
  private static List<Mode> modes() {
    String names = System.getProperty("nativejdbc.bench.modes", "");
    if (names.isEmpty()) {
      return List.of(Mode.values());
    }
    List<Mode> modes = new ArrayList<>(List.of(Mode.BASE));
    for (String name : names.split(",")) {
      Mode m = Mode.valueOf(name.trim());
      if (m != Mode.BASE) {
        modes.add(m);
      }
    }
    return modes;
  }

  /**
   * -Dnativejdbc.bench.pgSet=a=1;b=2: PostgreSQL settings for the split statements, set before and
   * reset after each split mode. -Dnativejdbc.bench.pgAuto=true: no parallel plan for a split
   * statement unless the plan of the plain query is parallel.
   */
  private static void noParallel(Connection c, Engine engine, Query q, Mode m, boolean on)
      throws SQLException {
    if (engine != Engine.POSTGRESQL || !split(m)) {
      return;
    }
    String set = System.getProperty("nativejdbc.bench.pgSet", "");
    if (Boolean.getBoolean("nativejdbc.bench.pgAuto")
        && !PARALLEL.computeIfAbsent(q.name, n -> basePlanIsParallel(c, engine, q))) {
      set = "max_parallel_workers_per_gather=0";
    }
    if (set.isEmpty()) {
      return;
    }
    try (java.sql.Statement st = c.createStatement()) {
      for (String one : set.split(";")) {
        st.execute(on ? "SET " + one : "RESET " + one.substring(0, one.indexOf('=')));
      }
    }
  }

  /** Per query, whether the plain query's plan is parallel. */
  private static final Map<String, Boolean> PARALLEL = new java.util.HashMap<>();

  private static boolean basePlanIsParallel(Connection c, Engine engine, Query q) {
    try {
      return basePlanIsParallel(c, Objects.requireNonNull(render(q, Mode.BASE, engine)), q.params);
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** Whether the plain query's plan, for the parameters of the first run, is parallel. */
  private static boolean basePlanIsParallel(Connection c, String sql, Param[] params)
      throws SQLException {
    StringBuilder bound = new StringBuilder();
    int n = 0;
    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      if (ch != '?') {
        bound.append(ch);
        continue;
      }
      Param p = params[n++ % params.length];
      bound.append(
          p == Param.NAME
              ? "'n4242'"
              : p.name().startsWith("SPAN")
                  ? p.name().substring(4)
                  : p == Param.CUSTOMER ? "4242" : "7");
    }
    try (java.sql.Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("EXPLAIN " + bound)) {
      while (rs.next()) {
        if (rs.getString(1).contains("Gather")) {
          return true;
        }
      }
    }
    return false;
  }

  // ---- rendering

  private static final Pattern FROM = Pattern.compile("\\{([COR]):(\\w+)(?:/([AB]))?\\}");
  private static final Pattern WHERE = Pattern.compile("\\{W:(\\w+)(?:/([AB]))?\\}");
  private static final Pattern COLUMN = Pattern.compile("\\{(\\w+)\\.(\\w+)(?:/([AB]))?\\}");

  static @Nullable String render(Query q, Mode m, Engine engine) {
    if (m == Mode.SPLIT_LT || m == Mode.SPLIT_LH) {
      return q.graph == null ? null : lateral(q.graph, m, engine);
    }
    String limit =
        engine == Engine.SQLSERVER || engine == Engine.ORACLE
            ? " OFFSET 0 ROWS FETCH NEXT 3 ROWS ONLY"
            : " LIMIT 3";
    if (!split(m)) {
      return render(q.sql, m, Map.of()).replace("{LIMIT}", limit);
    }
    boolean partial =
        m == Mode.SPLIT_PA || m == Mode.SPLIT_PH || m == Mode.SPLIT_DPA || m == Mode.SPLIT_DPH;
    boolean derived = m.name().startsWith("SPLIT_D");
    List<String> arms = new ArrayList<>();
    int n = q.splitAliases.size();
    for (int bits = 0; bits < (1 << n); bits++) {
      Map<String, Character> arm = new LinkedHashMap<>();
      for (int i = 0; i < n; i++) {
        arm.put(q.splitAliases.get(i), (bits & (1 << i)) == 0 ? 'A' : 'B');
      }
      Mode armMode = derived ? direct(m) : m;
      if (armMode == Mode.SPLIT_MX || armMode == Mode.SPLIT_MH) {
        armMode =
            bits != 0 ? Mode.SPLIT_LJ : armMode == Mode.SPLIT_MX ? Mode.SPLIT_AJ : Mode.SPLIT_HD;
      }
      String template = partial && q.partialInner != null ? q.partialInner : q.split;
      arms.add(derived ? renderDerived(template, armMode, arm) : render(template, armMode, arm));
    }
    String union = String.join(" UNION ALL ", arms);
    String outer = partial && q.partialOuter != null ? q.partialOuter : q.outer;
    String sql = outer == null ? union : outer.replace("{ARMS}", union);
    return sql.replace("{LIMIT}", limit);
  }

  /** The direct form a derived-table mode stands for. */
  private static Mode direct(Mode m) {
    switch (m) {
      case SPLIT_DAJ:
        return Mode.SPLIT_AJ;
      case SPLIT_DHD:
        return Mode.SPLIT_HD;
      case SPLIT_DMX:
        return Mode.SPLIT_MX;
      case SPLIT_DMH:
        return Mode.SPLIT_MH;
      case SPLIT_DPA:
        return Mode.SPLIT_PA;
      case SPLIT_DPH:
        return Mode.SPLIT_PH;
      default:
        throw new IllegalArgumentException(m.name());
    }
  }

  /**
   * A template with each table replaced by a derived table of its copy's rows, named by the table's
   * alias and exposing the table's own column names, and nothing else in the statement changed.
   */
  private static String renderDerived(String template, Mode m, Map<String, Character> arms) {
    Map<String, String> tables = new LinkedHashMap<>();
    Matcher f = FROM.matcher(template);
    while (f.find()) {
      tables.put(f.group(2), TABLE.get(f.group(1)));
    }
    String s =
        replace(
            FROM,
            template,
            g -> {
              String x = g.group(2);
              String t = Objects.requireNonNull(tables.get(x));
              Character arm = arm(arms, x, g.group(3));
              StringBuilder cols = new StringBuilder(x + ".id");
              for (String col : Objects.requireNonNull(NON_KEY.get(t))) {
                cols.append(", ").append(column(x, col, m, arm)).append(" AS ").append(col);
              }
              return "(SELECT "
                  + cols
                  + " FROM "
                  + from(t, x, m, arm)
                  + " WHERE "
                  + where(t, x, m, arm)
                  + ") "
                  + x;
            });
    s = replace(WHERE, s, g -> "1 = 1");
    return replace(COLUMN, s, g -> g.group(1) + "." + g.group(2));
  }

  private static String lateral(Graph g, Mode m, Engine engine) {
    Mode form = m == Mode.SPLIT_LH ? Mode.SPLIT_HD : Mode.SPLIT_AJ;
    List<String> copies = new ArrayList<>();
    for (int first = -1; first < g.tables.size(); first++) {
      copies.add(copy(g, form, engine, first));
    }
    return String.join(" UNION ALL ", copies);
  }

  /** One copy: tables before {@code first} committed, it in flight ('B'), the rest either ('L'). */
  private static String copy(Graph g, Mode m, Engine engine, int first) {
    Map<String, Character> form = new LinkedHashMap<>();
    Map<String, String> table = new LinkedHashMap<>();
    for (int i = 0; i < g.tables.size(); i++) {
      String[] t = g.tables.get(i).split(":");
      table.put(t[0], Objects.requireNonNull(TABLE.get(t[1])));
      form.put(t[0], first < 0 || i < first ? 'A' : i == first ? 'B' : 'L');
    }
    // from the in-flight table outwards, so that every later table joins one already placed
    List<String> order = new ArrayList<>();
    order.add(g.tables.get(Math.max(first, 0)).split(":")[0]);
    while (order.size() < table.size()) {
      for (String edge : g.edges) {
        String[] sides = edge.split("=");
        String x = sides[0].split("\\.")[0];
        String y = sides[1].split("\\.")[0];
        if (order.contains(x) != order.contains(y)) {
          order.add(order.contains(x) ? y : x);
        }
      }
    }
    String fx = g.filter.split("\\.")[0];
    String fcol = g.filter.split("\\.")[1];
    StringBuilder from = new StringBuilder();
    List<String> where = new ArrayList<>();
    List<String> placed = new ArrayList<>();
    for (String x : order) {
      String t = Objects.requireNonNull(table.get(x));
      char f = Objects.requireNonNull(form.get(x));
      if (f != 'L') {
        List<String> on = joins(g, x, f, placed, form);
        String ref = from(t, x, m, f);
        from.append(placed.isEmpty() ? ref : " JOIN " + ref + " ON " + String.join(" AND ", on));
        where.add(where(t, x, m, f));
        if (x.equals(fx)) {
          where.add(ref(x, fcol, f) + " = ?");
        }
      } else {
        List<String> arms = new ArrayList<>();
        for (char arm : new char[] {'A', 'B'}) {
          StringBuilder cols = new StringBuilder(x + ".id AS id");
          for (String col : Objects.requireNonNull(NON_KEY.get(t))) {
            cols.append(", ").append(ref(x, col, arm)).append(" AS ").append(col);
          }
          List<String> conds = new ArrayList<>(List.of(where(t, x, m, arm)));
          String group = "";
          if (engine == Engine.MARIADB) {
            if (Boolean.getBoolean("nativejdbc.bench.mariaGroup")) {
              group = " GROUP BY " + cols.toString().replaceAll(" AS \\w+", "");
            }
          } else {
            conds.addAll(joins(g, x, arm, placed, form));
            if (x.equals(fx)) {
              conds.add(ref(x, fcol, arm) + " = ?");
            }
          }
          arms.add(
              "SELECT "
                  + cols
                  + " FROM "
                  + from(t, x, m, arm)
                  + " WHERE "
                  + String.join(" AND ", conds)
                  + group);
        }
        if (engine == Engine.MARIADB) {
          // no LATERAL: a derived table joined on its output; -Dnativejdbc.bench.mariaGroup=true
          // groups each arm so that MariaDB may split it per outer row (lateral derived)
          List<String> on = joins(g, x, 'L', placed, form);
          if (x.equals(fx)) {
            on.add(x + "." + fcol + " = ?");
          }
          from.append(" JOIN (")
              .append(String.join(" UNION ALL ", arms))
              .append(") ")
              .append(x)
              .append(" ON ")
              .append(String.join(" AND ", on));
        } else {
          String apply =
              engine == Engine.SQLSERVER || engine == Engine.ORACLE
                  ? " CROSS APPLY ("
                  : " CROSS JOIN LATERAL (";
          from.append(apply).append(String.join(" UNION ALL ", arms)).append(") ").append(x);
        }
      }
      placed.add(x);
    }
    List<String> select = new ArrayList<>();
    for (String col : g.select) {
      String[] xc = col.split("\\.");
      select.add(ref(xc[0], xc[1], Objects.requireNonNull(form.get(xc[0]))));
    }
    return "SELECT "
        + String.join(", ", select)
        + " FROM "
        + from
        + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where));
  }

  /** The join conditions between {@code x}, read in image {@code f}, and the tables placed. */
  private static List<String> joins(
      Graph g, String x, char f, List<String> placed, Map<String, Character> form) {
    List<String> conds = new ArrayList<>();
    for (String edge : g.edges) {
      String[] sides = edge.split("=");
      String[] l = sides[0].split("\\.");
      String[] r = sides[1].split("\\.");
      if (l[0].equals(x) && placed.contains(r[0])) {
        conds.add(
            ref(x, l[1], f) + " = " + ref(r[0], r[1], Objects.requireNonNull(form.get(r[0]))));
      } else if (r[0].equals(x) && placed.contains(l[0])) {
        conds.add(
            ref(l[0], l[1], Objects.requireNonNull(form.get(l[0]))) + " = " + ref(x, r[1], f));
      }
    }
    return conds;
  }

  /** A column of x read in image f: the before image for 'B', the lateral's output for 'L'. */
  private static String ref(String x, String col, char f) {
    return f == 'B' && !col.equals("id") ? x + "m.before_" + col : x + "." + col;
  }

  private static String render(String template, Mode m, Map<String, Character> arms) {
    Map<String, String> tables = new LinkedHashMap<>();
    Matcher f = FROM.matcher(template);
    while (f.find()) {
      tables.put(f.group(2), TABLE.get(f.group(1)));
    }
    String s =
        replace(
            FROM,
            template,
            g ->
                from(
                    Objects.requireNonNull(tables.get(g.group(2))),
                    g.group(2),
                    m,
                    arm(arms, g.group(2), g.group(3))));
    s =
        replace(
            WHERE,
            s,
            g ->
                where(
                    Objects.requireNonNull(tables.get(g.group(1))),
                    g.group(1),
                    m,
                    arm(arms, g.group(1), g.group(2))));
    s =
        replace(
            COLUMN, s, g -> column(g.group(1), g.group(2), m, arm(arms, g.group(1), g.group(3))));
    return s;
  }

  private static @Nullable Character arm(
      Map<String, Character> arms, String alias, @Nullable String forced) {
    return forced != null ? Character.valueOf(forced.charAt(0)) : arms.get(alias);
  }

  private interface Replacer {
    String apply(Matcher m);
  }

  private static String replace(Pattern p, String s, Replacer r) {
    Matcher m = p.matcher(s);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(r.apply(m)));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static boolean split(Mode m) {
    return m.name().startsWith("SPLIT_");
  }

  private static String from(String t, String x, Mode m, @Nullable Character arm) {
    if (split(m) && arm != null) {
      if (arm == 'B') {
        return String.format("(bn.%s %s JOIN bn.%sm %sm ON %sm.id = %s.id)", t, x, t, x, x, x);
      }
      switch (m) {
        case SPLIT_LJ:
          return String.format(
              "(bn.%s %s LEFT JOIN bn.%sm %sm ON %sm.id = %s.id)", t, x, t, x, x, x);
        case SPLIT_HJ:
          return String.format(
              "(bn.%s %s LEFT JOIN (SELECT %sq.id FROM bn.%sm %sq WHERE %sq.tx_state IN (1, 2))"
                  + " %sf ON %sf.id = %s.id)",
              t, x, x, t, x, x, x, x, x);
        case SPLIT_HD:
        case SPLIT_PH:
          return String.format(
              "(bn.%s %s LEFT JOIN (SELECT DISTINCT %sq.id FROM bn.%sm %sq WHERE %sq.tx_state IN (1, 2))"
                  + " %sf ON %sf.id = %s.id)",
              t, x, x, t, x, x, x, x, x);
        default:
          return "bn." + t + " " + x;
      }
    }
    switch (m) {
      case BASE:
        return "bn." + t + " " + x;
      case VIEW_LJ:
      case SPLIT_LJ:
        return "bn.rc_" + t + " " + x;
      case VIEW_CASE:
        return "bn.rcj_" + t + " " + x;
      default:
        return "bn.rca_" + t + " " + x;
    }
  }

  private static String where(String t, String x, Mode m, @Nullable Character arm) {
    if (!split(m) || arm == null) {
      return "1 = 1";
    }
    if (arm == 'B') {
      return String.format(
          "%sm.tx_state IN (1, 2) AND (%sm.before_tx_id IS NOT NULL OR %sm.before_tx_version IS NOT"
              + " NULL)",
          x, x, x);
    }
    switch (m) {
      case SPLIT_LJ:
        return String.format("COALESCE(%sm.tx_state, 3) = 3", x);
      case SPLIT_NI:
        return String.format(
            "%s.id NOT IN (SELECT %sq.id FROM bn.%sm %sq WHERE %sq.tx_state IN (1, 2))",
            x, x, t, x, x);
      case SPLIT_HJ:
      case SPLIT_HD:
      case SPLIT_PH:
        return x + "f.id IS NULL";
      default:
        return String.format(
            "NOT EXISTS (SELECT 1 FROM bn.%sm %sq WHERE %sq.id = %s.id AND %sq.tx_state IN (1, 2))",
            t, x, x, x, x);
    }
  }

  private static String column(String x, String col, Mode m, @Nullable Character arm) {
    return split(m) && arm != null && arm == 'B' ? x + "m.before_" + col : x + "." + col;
  }

  // ---- measuring

  static final class Result {
    final double median;
    final double p95;
    final List<String> rows;

    Result(double median, double p95, List<String> rows) {
      this.median = median;
      this.p95 = p95;
      this.rows = rows;
    }
  }

  private static Result measure(Connection c, String sql, Param[] params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int placeholders = ps.getParameterMetaData().getParameterCount();
      Random random = new Random(42);
      List<String> first =
          run(ps, placeholders, params, 4242, 7); // a customer and region in flight
      long warmUntil = System.nanoTime() + 2_000_000_000L;
      for (int i = 0; i < 20 && System.nanoTime() < warmUntil; i++) {
        run(ps, placeholders, params, customer(random), region(random));
      }
      List<Double> times = new ArrayList<>();
      long requestsBefore = proxy == null ? 0 : proxy.requests.get();
      long until = System.nanoTime() + 3_000_000_000L;
      while (times.size() < 200 && System.nanoTime() < until) {
        int customer = customer(random);
        int region = region(random);
        long t0 = System.nanoTime();
        run(ps, placeholders, params, customer, region);
        times.add((System.nanoTime() - t0) / 1e6);
      }
      if (proxy != null) {
        System.out.printf(
            Locale.ROOT,
            "BENCH requests per execution %.2f%n",
            (proxy.requests.get() - requestsBefore) / (double) times.size());
      }
      Collections.sort(times);
      return new Result(
          times.get(times.size() / 2),
          times.get((int) Math.min(times.size() - 1, times.size() * 0.95)),
          first);
    }
  }

  /** The proxy the measuring connection goes through, if any. */
  private static @Nullable DelayProxy proxy;

  // -Dnativejdbc.bench.hot=true: only customers and the region with rows in flight
  private static final boolean HOT = Boolean.getBoolean("nativejdbc.bench.hot");

  private static int customer(Random random) {
    return HOT ? 7 + 1000 * random.nextInt(100) : random.nextInt(100_000);
  }

  private static int region(Random random) {
    return HOT ? 7 : random.nextInt(1000);
  }

  private static List<String> run(
      PreparedStatement ps, int placeholders, Param[] params, int customer, int region)
      throws SQLException {
    for (int i = 0; i < placeholders; i++) {
      Param p = params[i % params.length];
      if (p == Param.NAME) {
        ps.setString(i + 1, "n" + customer);
      } else if (p.name().startsWith("SPAN")) {
        ps.setInt(i + 1, Integer.parseInt(p.name().substring(4)));
      } else {
        ps.setInt(i + 1, p == Param.CUSTOMER ? customer : region);
      }
    }
    List<String> rows = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        String[] row = new String[n];
        for (int i = 0; i < n; i++) {
          row[i] = rs.getString(i + 1);
        }
        rows.add(Arrays.toString(row));
      }
    }
    Collections.sort(rows);
    return rows;
  }

  /** The executed plan, with a customer and a region that have rows in flight. */
  private static String explain(Connection c, Engine engine, String sql, Param[] params)
      throws SQLException {
    String prefix;
    switch (engine) {
      case POSTGRESQL:
        // the plan a statement runs with after its fifth execution, which the timings measure
        try (java.sql.Statement st = c.createStatement()) {
          st.execute("SET plan_cache_mode = force_custom_plan");
        }
        prefix = "EXPLAIN (ANALYZE, COSTS ON, TIMING OFF, SUMMARY ON) ";
        break;
      case MYSQL:
        prefix = "EXPLAIN ANALYZE ";
        break;
      case MARIADB:
        prefix = "ANALYZE FORMAT=JSON ";
        break;
      case ORACLE:
        {
          // estimated plan; the binds are not peeked
          try (java.sql.Statement st = c.createStatement()) {
            st.execute("EXPLAIN PLAN FOR " + sql);
            StringBuilder out = new StringBuilder();
            try (ResultSet rs =
                st.executeQuery("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
              while (rs.next()) {
                out.append(rs.getString(1)).append('\n');
              }
            }
            return out.toString();
          }
        }
      case SQLSERVER:
        return sqlServerPlan(c, sql, params);
      default:
        return "(no plan output for this engine)";
    }
    StringBuilder out = new StringBuilder();
    try (PreparedStatement ps = c.prepareStatement(prefix + sql)) {
      int placeholders = ps.getParameterMetaData().getParameterCount();
      for (int i = 0; i < placeholders; i++) {
        Param p = params[i % params.length];
        if (p == Param.NAME) {
          ps.setString(i + 1, "n4242");
        } else {
          ps.setInt(i + 1, value(p));
        }
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.append(rs.getString(1)).append('\n');
        }
      }
    }
    return out.toString();
  }

  private static int value(Param p) {
    return p.name().startsWith("SPAN")
        ? Integer.parseInt(p.name().substring(4))
        : p == Param.CUSTOMER ? (HOT ? 4007 : 4242) : 7;
  }

  /** The executed plan with actual rows and executions per operator. */
  private static String sqlServerPlan(Connection c, String sql, Param[] params)
      throws SQLException {
    StringBuilder out = new StringBuilder();
    try (java.sql.Statement st = c.createStatement()) {
      st.execute("SET STATISTICS PROFILE ON");
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        int placeholders = ps.getParameterMetaData().getParameterCount();
        for (int i = 0; i < placeholders; i++) {
          ps.setInt(i + 1, value(params[i % params.length]));
        }
        boolean isResult = ps.execute();
        while (true) {
          if (isResult) {
            try (ResultSet rs = ps.getResultSet()) {
              boolean plan =
                  rs.getMetaData().getColumnCount() > 5
                      && rs.getMetaData().getColumnName(3).equals("StmtText");
              while (rs.next()) {
                if (plan) {
                  out.append(
                      String.format(
                          Locale.ROOT,
                          "%8s %6s %s%n",
                          rs.getString("Rows"),
                          rs.getString("Executes"),
                          rs.getString("StmtText")));
                }
              }
            }
          } else if (ps.getUpdateCount() == -1) {
            break;
          }
          isResult = ps.getMoreResults();
        }
      }
      st.execute("SET STATISTICS PROFILE OFF");
    }
    return out.toString();
  }

  // ---- data

  private static void load(Engines e, Connection c) throws SQLException, IOException {
    Engine engine = e.engine();
    String in = engine == Engine.ORACLE ? "NUMBER(10)" : "INT";
    String vc = "VARCHAR(128)";
    List<String> ddl = new ArrayList<>();
    ddl.add(e.createSchema("bn"));
    ddl.add("CREATE TABLE bn.d (n " + in + ")");
    for (int i = 0; i < 10; i++) {
      ddl.add("INSERT INTO bn.d VALUES (" + i + ")");
    }
    ddl.add("CREATE TABLE bn.c (id " + in + " PRIMARY KEY, name VARCHAR(20), region " + in + ")");
    ddl.add(
        "CREATE TABLE bn.o (id " + in + " PRIMARY KEY, customer_id " + in + ", total " + in + ")");
    ddl.add("CREATE TABLE bn.r (id " + in + " PRIMARY KEY, label VARCHAR(20))");
    ddl.add("CREATE TABLE bn.p (id " + in + " PRIMARY KEY, cid " + in + ", region " + in + ")");
    for (String t : List.of("c", "o", "r")) {
      StringBuilder cols = new StringBuilder();
      for (String col : NON_KEY.get(t)) {
        cols.append(", before_")
            .append(col)
            .append(col.equals("name") || col.equals("label") ? " VARCHAR(20)" : " " + in);
      }
      ddl.add(
          "CREATE TABLE bn."
              + t
              + "m (id "
              + in
              + " PRIMARY KEY, tx_id "
              + vc
              + ", tx_state "
              + in
              + ", tx_version "
              + in
              + ", before_tx_id "
              + vc
              + ", before_tx_version "
              + in
              + cols
              + ")");
    }
    String ids5 = "d1.n + 10 * d2.n + 100 * d3.n + 1000 * d4.n + 10000 * d5.n";
    String digits5 = "bn.d d1, bn.d d2, bn.d d3, bn.d d4, bn.d d5";
    ddl.add(
        "INSERT INTO bn.c (id, name, region) SELECT x.id, CONCAT('n', x.id), x.id - 1000 *"
            + " FLOOR(x.id / 1000) FROM (SELECT "
            + ids5
            + " AS id FROM "
            + digits5
            + ") x");
    ddl.add(
        "INSERT INTO bn.o (id, customer_id, total) SELECT x.id, x.id - 100000 * FLOOR(x.id /"
            + " 100000), x.id - 1000 * FLOOR(x.id / 1000) FROM (SELECT "
            + ids5
            + " + 100000 * d6.n AS id FROM "
            + digits5
            + ", bn.d d6 WHERE d6.n < 5) x");
    ddl.add(
        "INSERT INTO bn.r (id, label) SELECT x.id, CONCAT('r', x.id) FROM (SELECT d1.n + 10 *"
            + " d2.n + 100 * d3.n AS id FROM bn.d d1, bn.d d2, bn.d d3) x");
    ddl.add(
        "INSERT INTO bn.rm (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.r");
    ddl.add(
        "UPDATE bn.rm SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_label = CONCAT('r', id)"
            + " WHERE id - 100 * FLOOR(id / 100) = 7");
    ddl.add("INSERT INTO bn.p (id, cid, region) SELECT n, n * 7919, n * 37 FROM bn.d");
    ddl.add(
        "INSERT INTO bn.cm (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.c");
    ddl.add(
        "INSERT INTO bn.om (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.o");
    // 100 rows in flight per table, their before image equal to the current values
    ddl.add(
        "UPDATE bn.cm SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_name = CONCAT('n', id), before_region = id - 1000 *"
            + " FLOOR(id / 1000) WHERE id - 1000 * FLOOR(id / 1000) = 7");
    ddl.add(
        "UPDATE bn.om SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_customer_id = id - 100000 * FLOOR(id / 100000),"
            + " before_total = id - 1000 * FLOOR(id / 1000) WHERE id - 5000 * FLOOR(id / 5000) = 3");
    String ix = engine == Engine.ORACLE ? "bn." : "";
    ddl.add("CREATE INDEX " + ix + "c_region ON bn.c (region)");
    ddl.add("CREATE INDEX " + ix + "o_customer ON bn.o (customer_id)");
    for (String t : List.of("c", "o", "r")) {
      // -Dnativejdbc.bench.ssIndex=composite or unique: SQL Server's in-flight index
      String ss = System.getProperty("nativejdbc.bench.ssIndex", "filtered");
      boolean partial =
          engine == Engine.POSTGRESQL || (engine == Engine.SQLSERVER && !ss.equals("composite"));
      ddl.add(
          (engine == Engine.SQLSERVER && ss.equals("unique")
                  ? "CREATE UNIQUE INDEX "
                  : "CREATE INDEX ")
              + ix
              + t
              + "m_inflight ON bn."
              + t
              + "m "
              + (partial ? "(id) WHERE tx_state IN (1, 2)" : "(tx_state, id)"));
      ddl.addAll(views(t));
    }
    switch (engine) {
      case POSTGRESQL:
        ddl.add("ANALYZE");
        break;
      case MYSQL:
        ddl.add("ANALYZE TABLE bn.c, bn.cm, bn.o, bn.om, bn.r, bn.rm, bn.p");
        // without a histogram MySQL takes tx_state IN (1, 2) for the whole table in a subquery
        ddl.add("ANALYZE TABLE bn.cm, bn.om, bn.rm UPDATE HISTOGRAM ON tx_state WITH 16 BUCKETS");
        break;
      case MARIADB:
        ddl.add("ANALYZE TABLE bn.c, bn.cm, bn.o, bn.om, bn.r, bn.rm, bn.p");
        break;
      case SQLSERVER:
        for (String t : List.of("c", "cm", "o", "om", "r", "rm", "p")) {
          ddl.add("UPDATE STATISTICS bn." + t + " WITH FULLSCAN");
        }
        break;
      case ORACLE:
        ddl.add("BEGIN DBMS_STATS.GATHER_SCHEMA_STATS('BN'); END;");
        // without a histogram Oracle takes tx_state IN (1, 2) for half the table and scans it
        for (String t : List.of("CM", "OM", "RM")) {
          ddl.add(
              "BEGIN DBMS_STATS.GATHER_TABLE_STATS('BN', '"
                  + t
                  + "', method_opt => 'FOR"
                  + " COLUMNS TX_STATE SIZE 254'); END;");
        }
        break;
    }
    e.execute(c, ddl.toArray(new String[0]));
  }

  private static List<String> views(String t) {
    List<String> nonKey = NON_KEY.get(t);
    String raw = "x.id";
    String before = "x.id";
    String cased = "x.id";
    for (String col : nonKey) {
      raw += ", x." + col;
      before += ", xm.before_" + col + " AS " + col;
      cased +=
          ", CASE WHEN xm.tx_state IN (1, 2) THEN xm.before_"
              + col
              + " ELSE x."
              + col
              + " END AS "
              + col;
    }
    String inFlight =
        " UNION ALL SELECT "
            + before
            + " FROM bn."
            + t
            + " x JOIN bn."
            + t
            + "m xm ON xm.id = x.id WHERE xm.tx_state IN (1, 2) AND (xm.before_tx_id IS NOT NULL"
            + " OR xm.before_tx_version IS NOT NULL)";
    return List.of(
        "CREATE VIEW bn.rc_"
            + t
            + " AS SELECT "
            + raw
            + " FROM bn."
            + t
            + " x LEFT JOIN bn."
            + t
            + "m xm ON xm.id = x.id WHERE COALESCE(xm.tx_state, 3) = 3"
            + inFlight,
        "CREATE VIEW bn.rca_"
            + t
            + " AS SELECT "
            + raw
            + " FROM bn."
            + t
            + " x WHERE NOT EXISTS"
            + " (SELECT 1 FROM bn."
            + t
            + "m xq WHERE xq.id = x.id AND xq.tx_state IN (1, 2))"
            + inFlight,
        "CREATE VIEW bn.rcj_"
            + t
            + " AS SELECT "
            + cased
            + " FROM bn."
            + t
            + " x LEFT JOIN bn."
            + t
            + "m xm ON xm.id = x.id WHERE COALESCE(xm.tx_state, 3) = 3 OR (xm.tx_state IN (1,"
            + " 2) AND (xm.before_tx_id IS NOT NULL OR xm.before_tx_version IS NOT NULL))");
  }
}
