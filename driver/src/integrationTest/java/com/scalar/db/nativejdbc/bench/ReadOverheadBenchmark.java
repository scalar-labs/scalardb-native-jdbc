package com.scalar.db.nativejdbc.bench;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
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
 * five per customer; both {@code -Dnativejdbc.bench.scale} times larger), each with a metadata
 * table holding 100 rows in flight whose before image equals the current values, so every rewrite
 * must return the rows of the plain query. Two more for the shapes of real tables: {@code w} (100k
 * rows, 49 non-key columns) and {@code k} (100k rows keyed on {@code (pid, cid)}), their rows in
 * flight at {@code c}'s ids. The rewrites, through the vendor driver directly so only the SQL is
 * measured:
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
    SPLIT_DPH,
    /**
     * The form the driver emits on the engine: derived tables, NOT EXISTS on PostgreSQL and Oracle,
     * the DISTINCT set on MySQL and MariaDB, SPLIT_DMX on SQL Server (SPLIT_DMH from three tables).
     */
    SPLIT_DRV,
    /**
     * SPLIT_DRV with the ORDER BY and row limit, or the DISTINCT, also applied inside each copy
     * (the limit as offset + count), keeping them on top as well.
     */
    SPLIT_DRVP,
    /** SPLIT_DRVP with NOT EXISTS as the committed form on every engine. */
    SPLIT_DAJP,
    /** SPLIT_DRVP with the DISTINCT set as the committed form on every engine. */
    SPLIT_DHDP,
    /** Oracle: SPLIT_DRVP with each copy's limit as {@code WHERE ROWNUM <= n}, not FETCH FIRST. */
    SPLIT_DRVR,
    /** SPLIT_LJ as derived tables. */
    SPLIT_DLJ,
    /** SPLIT_DRVP with the left join on the metadata as the committed form on every engine. */
    SPLIT_DLJP,
    /** MySQL: SPLIT_DAJP with NOT EXISTS kept a per-row probe ({@code NO_SEMIJOIN}). */
    SPLIT_DAJN,
    /** MySQL: SPLIT_DAJ with NOT EXISTS kept a per-row probe ({@code NO_SEMIJOIN}). */
    SPLIT_DAJH,
    /** SPLIT_DRV with each derived table projecting only the columns the statement references. */
    SPLIT_DRVN,
    /**
     * Approach A, the plain aggregate corrected: the aggregate over the data table as it is, minus
     * what the after images of the rows in flight add to it, plus what their before images add;
     * DISTINCT keeps the plain DISTINCT and drops a value only rows in flight carry, adding the
     * before images' values. The correction reads only the rows in flight.
     */
    CORR,
    /**
     * Approach B, the page over-fetched: the plain query's ORDER BY and limit raised by a margin on
     * the data tables as they are, each row that holds a row in flight dropped, then the copies of
     * the split read that hold one, each with the clauses applied inside it, and the page taken
     * from both. Exact while the rows kept are at least the page; the driver reads again with a
     * larger margin otherwise (not measured: the margin here always holds).
     */
    OVER,
    /**
     * Approach C, the subquery split on its own: an IN or NOT EXISTS over a ScalarDB table as one
     * predicate on its committed rows and one on its before images, each on the table itself, so
     * that the engine keeps its semijoin or antijoin.
     */
    SEMI,
    /**
     * BASE's statement through the driver, on the same tables imported into ScalarDB with their
     * metadata copied: what the driver's own rewrite costs, parsing included.
     */
    DRIVER,
    /**
     * BASE with the rows in flight held by an open native transaction instead: updated, every
     * non-key column changed, and not committed, so that the plain query reads past them as on the
     * database alone. What rows in flight cost without the driver; not with a proxy.
     */
    BASE_OPEN
  }

  enum Param {
    CUSTOMER,
    REGION,
    NAME,
    /** Regions below 3, 30 or 300: 300, 3k or 30k customers. */
    SPAN3,
    SPAN30,
    SPAN300,
    /** The composite key of the customer's row: customer / 10 and customer % 10. */
    PART,
    CLUSTER,
    /** A row of the hot table h: customer mod 20,000. */
    HOT_ID,
    /** A group of h, 100 rows: region mod 200. */
    HOT_GRP
  }

  private static final Map<String, String> TABLE =
      Map.of("C", "c", "O", "o", "R", "r", "X", "w", "K", "k", "H", "h");

  /**
   * The ScalarDB tables. h is small and hot: 20,000 rows, with the rows in flight a share of it
   * that grows with {@code -Dnativejdbc.bench.inflight} (0.5% at 100, 50% at 10,000), where the
   * others' stay a small share.
   */
  private static final List<String> TABLES = List.of("c", "o", "r", "w", "k", "h");

  /** The 49 non-key columns of the wide table w: c01 (id mod 1000, indexed), then id + n. */
  private static final List<String> WIDE = wide();

  private static List<String> wide() {
    List<String> cols = new ArrayList<>();
    for (int i = 1; i < 50; i++) {
      cols.add(String.format(Locale.ROOT, "c%02d", i));
    }
    return cols;
  }

  private static final Map<String, List<String>> NON_KEY =
      Map.of(
          "c",
          List.of("name", "region"),
          "o",
          List.of("customer_id", "total"),
          "r",
          List.of("label"),
          "w",
          WIDE,
          "k",
          List.of("grp", "val"),
          "h",
          List.of("qty", "grp"));

  /** The key columns of a table: k has a partition and a clustering key, the others id. */
  private static List<String> key(String t) {
    return t.equals("k") ? List.of("pid", "cid") : List.of("id");
  }

  /** The key columns of table t under alias x, comma-separated. */
  private static String keyList(String t, String x) {
    List<String> cols = new ArrayList<>();
    for (String k : key(t)) {
      cols.add(x + "." + k);
    }
    return String.join(", ", cols);
  }

  /** Aliases x and y of table t on the same key. */
  private static String keyEq(String t, String x, String y) {
    List<String> conds = new ArrayList<>();
    for (String k : key(t)) {
      conds.add(x + "." + k + " = " + y + "." + k);
    }
    return String.join(" AND ", conds);
  }

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
    @Nullable String pushed;
    @Nullable String copy;
    @Nullable Graph graph;
    final Map<Mode, String> forms = new EnumMap<>(Mode.class);

    /** The text of each copy for SPLIT_DRVP, with the clauses applied inside it. */
    Query pushed(String copy) {
      pushed = copy;
      return this;
    }

    /** The text of a mode written out, its forced arms and plain tables rendered as given. */
    Query form(Mode m, String template) {
      forms.put(m, template);
      return this;
    }

    /** For OVER: the copies' text where it is not {@code pushed}. */
    Query copy(String template) {
      copy = template;
      return this;
    }

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

  /** Template {@code t}, written for alias x, for alias {@code alias} in arm {@code arm}. */
  private static String as(String t, String alias, @Nullable String arm) {
    String forced = arm == null ? "" : "/" + arm;
    return t.replaceAll("\\{(\\w):x\\}", "{$1:" + alias + forced + "}")
        .replaceAll("\\{x\\.(\\w+)\\}", "{" + alias + ".$1" + forced + "}");
  }

  /**
   * CORR for an aggregate of sums: {@code inner}, written for alias x with its aggregate {@code AS
   * n}, on the plain table, negated on the after images in flight, and on their before images,
   * combined by {@code outer} over {ARMS}.
   */
  private static String corr(String inner, String outer) {
    // ponytail: a SUM whose rows are all inserted in flight gives 0, not NULL; a count of the
    // summed values beside it decides, where the driver emits this
    String negated =
        inner
            .replace("COUNT(*) AS n", "-COUNT(*) AS n")
            .replaceAll("SUM\\((.*)\\) AS n", "-SUM($1) AS n");
    return outer.replace(
        "{ARMS}",
        as(inner, "b", null)
            + " UNION ALL "
            + as(negated, "f", "F")
            + " UNION ALL "
            + as(inner, "e", "B"));
  }

  /**
   * OVER: {@code window}, the plain query over-fetched, without its rows that hold a row in flight
   * ({@code keys}: "table:column" of the window per ScalarDB table, its key), then the copies with
   * a table in flight ({COPIES}), combined by {@code outer} over {ARMS}.
   */
  private static String over(String window, String keys, String outer) {
    StringBuilder kept = new StringBuilder("SELECT w.* FROM (" + window + ") w WHERE 1 = 1");
    for (String key : keys.split(",")) {
      String[] tc = key.split(":");
      kept.append(
          String.format(
              " AND NOT EXISTS (SELECT 1 FROM bn.%sm w%s WHERE w%s.id = w.%s AND w%s.tx_state IN"
                  + " (1, 2))",
              tc[0], tc[1], tc[1], tc[1], tc[1]));
    }
    return outer.replace("{ARMS}", kept + " UNION ALL {COPIES}");
  }

  /** SEMI: {@code t}, written with arm X for the outer table, once per arm of it. */
  private static String semi(String t) {
    return t.replace("/X}", "/A}") + " UNION ALL " + t.replace("/X}", "/B}");
  }

  // {C:x} / {O:x}: the table with alias x; {x.col}: a non-key column of x; {W:x}: x's state
  // condition; a suffix /A or /B forces an arm, /F the after image of a row in flight. {LIMIT}:
  // three rows. {ARMS}: the split arms.
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
                  Param.CUSTOMER)
              .form(
                  Mode.CORR,
                  corr(
                      "SELECT COUNT(*) AS n FROM {O:x} WHERE {W:x} AND {x.customer_id} = ?",
                      "SELECT SUM(v.n) FROM ({ARMS}) v")),
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
                  "IN subquery selecting a non-key",
                  "SELECT a.id FROM {C:a} WHERE {W:a} AND {a.region} = ? AND a.id IN"
                      + " (SELECT {b.customer_id} FROM {O:b} WHERE {W:b})",
                  null,
                  null,
                  List.of("a"),
                  Param.REGION)
              .form(
                  Mode.SEMI,
                  semi(
                      "SELECT a.id FROM {C:a/X} WHERE {W:a/X} AND {a.region/X} = ? AND a.id IN"
                          + " (SELECT {b.customer_id/A} FROM {O:b/A} WHERE {W:b/A})"
                          + " UNION ALL SELECT a.id FROM {C:a/X} WHERE {W:a/X} AND {a.region/X} = ? AND a.id IN"
                          + " (SELECT {f.customer_id/B} FROM {O:f/B} WHERE {W:f/B}) AND NOT EXISTS"
                          + " (SELECT 1 FROM {O:g/A} WHERE {W:g/A} AND {g.customer_id/A} = a.id)")),
          new Query(
                  "NOT EXISTS, ScalarDB on both sides",
                  "SELECT a.id FROM {C:a} WHERE {W:a} AND {a.region} = ? AND NOT EXISTS"
                      + " (SELECT 1 FROM {O:b} WHERE {W:b} AND {b.customer_id} = a.id)",
                  null,
                  null,
                  List.of("a"),
                  Param.REGION)
              .form(
                  Mode.SEMI,
                  semi(
                      "SELECT a.id FROM {C:a/X} WHERE {W:a/X} AND {a.region/X} = ? AND NOT EXISTS"
                          + " (SELECT 1 FROM {O:b/A} WHERE {W:b/A} AND {b.customer_id/A} = a.id) AND NOT EXISTS"
                          + " (SELECT 1 FROM {O:f/B} WHERE {W:f/B} AND {f.customer_id/B} = a.id)")),
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
                  "SELECT COUNT(*) AS n FROM {C:a} WHERE {W:a}", "SELECT SUM(v.n) FROM ({ARMS}) v")
              .form(
                  Mode.CORR,
                  corr(
                      "SELECT COUNT(*) AS n FROM {C:x} WHERE {W:x}",
                      "SELECT SUM(v.n) FROM ({ARMS}) v")),
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
                  "SELECT SUM(v.s) FROM ({ARMS}) v")
              .form(
                  Mode.CORR,
                  corr(
                      "SELECT SUM({x.total}) AS n FROM {O:x} WHERE {W:x}",
                      "SELECT SUM(v.n) FROM ({ARMS}) v")),
          new Query(
                  "full scan: GROUP BY of 100k rows",
                  "SELECT {a.region}, COUNT(*) FROM {C:a} WHERE {W:a} GROUP BY {a.region}",
                  "SELECT {a.region} AS region FROM {C:a} WHERE {W:a}",
                  "SELECT v.region, COUNT(*) FROM ({ARMS}) v GROUP BY v.region",
                  List.of("a"))
              .partial(
                  "SELECT {a.region} AS region, COUNT(*) AS n FROM {C:a} WHERE {W:a} GROUP BY"
                      + " {a.region}",
                  "SELECT v.region, SUM(v.n) FROM ({ARMS}) v GROUP BY v.region")
              .form(
                  Mode.CORR,
                  corr(
                      "SELECT {x.region} AS g, COUNT(*) AS n FROM {C:x} WHERE {W:x} GROUP BY {x.region}",
                      "SELECT v.g, SUM(v.n) FROM ({ARMS}) v GROUP BY v.g HAVING SUM(v.n) <> 0")),
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
                      List.of("g.id", "g.label", "a.id", "b.id", "e.id"))),
          // {PAGE:k:n}: n rows after k, in the engine's syntax
          new Query(
                  "page: ORDER BY key DESC, 20 rows of 500k",
                  "SELECT b.id, {b.total} FROM {O:b} WHERE {W:b} ORDER BY b.id DESC{PAGE:0:20}",
                  "SELECT b.id AS id, {b.total} AS total FROM {O:b} WHERE {W:b}",
                  "SELECT v.id, v.total FROM ({ARMS}) v ORDER BY v.id DESC{PAGE:0:20}",
                  List.of("b"))
              .pushed(
                  "SELECT b.id AS id, {b.total} AS total FROM {O:b} WHERE {W:b}"
                      + " ORDER BY b.id DESC{PAGE:0:20}")
              .form(
                  Mode.OVER,
                  over(
                      "SELECT b.id AS id, {b.total} AS total FROM {O:b} ORDER BY b.id DESC{PAGE:0:120}",
                      "o:id",
                      "SELECT v.id, v.total FROM ({ARMS}) v ORDER BY v.id DESC{PAGE:0:20}")),
          new Query(
                  "page: ORDER BY key DESC, 20 rows after 10k",
                  "SELECT b.id, {b.total} FROM {O:b} WHERE {W:b} ORDER BY b.id DESC{PAGE:10000:20}",
                  "SELECT b.id AS id, {b.total} AS total FROM {O:b} WHERE {W:b}",
                  "SELECT v.id, v.total FROM ({ARMS}) v ORDER BY v.id DESC{PAGE:10000:20}",
                  List.of("b"))
              .pushed(
                  "SELECT b.id AS id, {b.total} AS total FROM {O:b} WHERE {W:b}"
                      + " ORDER BY b.id DESC{PAGE:0:10020}")
              .form(
                  Mode.OVER,
                  over(
                      "SELECT b.id AS id, {b.total} AS total FROM {O:b} ORDER BY b.id DESC{PAGE:0:11020}",
                      "o:id",
                      "SELECT v.id, v.total FROM ({ARMS}) v ORDER BY v.id DESC{PAGE:10000:20}")),
          new Query(
                  "page: JOIN FK, ORDER BY key DESC, 20 rows",
                  "SELECT a.id, b.id FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                      + " WHERE {W:a} AND {W:b} ORDER BY b.id DESC{PAGE:0:20}",
                  "SELECT a.id AS aid, b.id AS bid FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                      + " WHERE {W:a} AND {W:b}",
                  "SELECT v.aid, v.bid FROM ({ARMS}) v ORDER BY v.bid DESC{PAGE:0:20}",
                  List.of("a", "b"))
              .pushed(
                  "SELECT a.id AS aid, b.id AS bid FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                      + " WHERE {W:a} AND {W:b} ORDER BY b.id DESC{PAGE:0:20}")
              .form(
                  Mode.OVER,
                  over(
                      "SELECT a.id AS aid, b.id AS bid FROM {C:a} JOIN {O:b} ON {b.customer_id} = a.id"
                          + " ORDER BY b.id DESC{PAGE:0:120}",
                      "c:aid,o:bid",
                      "SELECT v.aid, v.bid FROM ({ARMS}) v ORDER BY v.bid DESC{PAGE:0:20}")),
          new Query(
                  "page: keyset, key < ?, ORDER BY key DESC, 20 rows",
                  "SELECT b.id, {b.total} FROM {O:b} WHERE {W:b} AND b.id < ?"
                      + " ORDER BY b.id DESC{PAGE:0:20}",
                  null,
                  null,
                  List.of("b"),
                  Param.CUSTOMER)
              .form(
                  Mode.OVER,
                  over(
                      "SELECT b.id AS id, {b.total} AS total FROM {O:b} WHERE b.id < ?"
                          + " ORDER BY b.id DESC{PAGE:0:120}",
                      "o:id",
                      "SELECT v.id, v.total FROM ({ARMS}) v ORDER BY v.id DESC{PAGE:0:20}"))
              .copy(
                  "SELECT b.id AS id, {b.total} AS total FROM {O:b} WHERE {W:b} AND b.id < ?"
                      + " ORDER BY b.id DESC{PAGE:0:20}"),
          new Query(
                  "page: 3 tables JOIN FK, ORDER BY key DESC, 20 rows",
                  "SELECT g.id, a.id, b.id FROM {R:g} JOIN {C:a} ON {a.region} = g.id"
                      + " JOIN {O:b} ON {b.customer_id} = a.id WHERE {W:g} AND {W:a} AND {W:b}"
                      + " ORDER BY b.id DESC{PAGE:0:20}",
                  null,
                  null,
                  List.of("g", "a", "b"))
              .form(
                  Mode.OVER,
                  over(
                      "SELECT g.id AS gid, a.id AS aid, b.id AS bid FROM {R:g} JOIN {C:a} ON {a.region} = g.id"
                          + " JOIN {O:b} ON {b.customer_id} = a.id ORDER BY b.id DESC{PAGE:0:120}",
                      "r:gid,c:aid,o:bid",
                      "SELECT v.gid, v.aid, v.bid FROM ({ARMS}) v ORDER BY v.bid DESC{PAGE:0:20}"))
              .copy(
                  "SELECT g.id AS gid, a.id AS aid, b.id AS bid FROM {R:g} JOIN {C:a} ON {a.region} = g.id"
                      + " JOIN {O:b} ON {b.customer_id} = a.id WHERE {W:g} AND {W:a} AND {W:b}"
                      + " ORDER BY b.id DESC{PAGE:0:20}"),
          new Query(
                  "page: indexed range of 30k rows, ORDER BY key, 20 rows",
                  "SELECT a.id, {a.name} FROM {C:a} WHERE {W:a} AND {a.region} < ?"
                      + " ORDER BY a.id{PAGE:0:20}",
                  "SELECT a.id AS id, {a.name} AS name FROM {C:a} WHERE {W:a} AND {a.region} < ?",
                  "SELECT v.id, v.name FROM ({ARMS}) v ORDER BY v.id{PAGE:0:20}",
                  List.of("a"),
                  Param.SPAN300)
              .pushed(
                  "SELECT a.id AS id, {a.name} AS name FROM {C:a} WHERE {W:a} AND {a.region} < ?"
                      + " ORDER BY a.id{PAGE:0:20}")
              .form(
                  Mode.OVER,
                  over(
                      "SELECT a.id AS id, {a.name} AS name FROM {C:a} WHERE {a.region} < ?"
                          + " ORDER BY a.id{PAGE:0:120}",
                      "c:id",
                      "SELECT v.id, v.name FROM ({ARMS}) v ORDER BY v.id{PAGE:0:20}")),
          new Query(
                  "DISTINCT over an indexed column (1000 of 100k)",
                  "SELECT DISTINCT {a.region} FROM {C:a} WHERE {W:a}",
                  "SELECT {a.region} AS region FROM {C:a} WHERE {W:a}",
                  "SELECT DISTINCT v.region FROM ({ARMS}) v",
                  List.of("a"))
              .pushed("SELECT DISTINCT {a.region} AS region FROM {C:a} WHERE {W:a}")
              .form(
                  Mode.CORR,
                  "SELECT d.region FROM (SELECT DISTINCT {a.region} AS region FROM {C:a}) d"
                      + " WHERE NOT EXISTS (SELECT 1 FROM {C:f/F} WHERE {W:f/F} AND {f.region/F} = d.region"
                      + " AND NOT EXISTS (SELECT 1 FROM {C:g/A} WHERE {W:g/A} AND {g.region/A} = d.region))"
                      + " UNION SELECT {e.region/B} FROM {C:e/B} WHERE {W:e/B}"),
          // {X:x}: the 50-column table w; {K:x}: the table k keyed on (pid, cid)
          new Query(
              "wide: key lookup, 2 of 50 columns",
              "SELECT a.id, {a.c02}, {a.c03} FROM {X:a} WHERE {W:a} AND a.id = ?",
              null,
              null,
              List.of("a"),
              Param.CUSTOMER),
          new Query(
              "wide: indexed filter, 1 of 50 columns (100 rows)",
              "SELECT a.id, {a.c02} FROM {X:a} WHERE {W:a} AND {a.c01} = ?",
              null,
              null,
              List.of("a"),
              Param.REGION),
          new Query(
              "wide: 2 tables JOIN on key (100 rows)",
              "SELECT a.id, {a.c02}, {b.c03} FROM {X:a} JOIN {X:b} ON b.id = a.id"
                  + " WHERE {W:a} AND {W:b} AND {a.c01} = ?",
              null,
              null,
              List.of("a", "b"),
              Param.REGION),
          new Query(
              "wide: 4 tables JOIN on key, key lookup",
              "SELECT a.id, {a.c02}, {b.c03}, {e.c04}, {f.c05} FROM {X:a} JOIN {X:b} ON b.id = a.id"
                  + " JOIN {X:e} ON e.id = a.id JOIN {X:f} ON f.id = a.id"
                  + " WHERE {W:a} AND {W:b} AND {W:e} AND {W:f} AND a.id = ?",
              null,
              null,
              List.of("a", "b", "e", "f"),
              Param.CUSTOMER),
          new Query(
              "composite key: full-key lookup",
              "SELECT a.pid, a.cid, {a.val} FROM {K:a} WHERE {W:a} AND a.pid = ? AND a.cid = ?",
              null,
              null,
              List.of("a"),
              Param.PART,
              Param.CLUSTER),
          new Query(
              "composite key: partition scan (10 rows)",
              "SELECT a.cid, {a.val} FROM {K:a} WHERE {W:a} AND a.pid = ?",
              null,
              null,
              List.of("a"),
              Param.PART),
          new Query(
              "composite key: JOIN on the full key (100 rows)",
              "SELECT a.pid, a.cid, {b.val} FROM {K:a} JOIN {K:b} ON b.pid = a.pid AND b.cid ="
                  + " a.cid WHERE {W:a} AND {W:b} AND {a.grp} = ?",
              null,
              null,
              List.of("a", "b"),
              Param.REGION),
          new Query(
              "hot: key lookup",
              "SELECT a.id, {a.qty} FROM {H:a} WHERE {W:a} AND a.id = ?",
              null,
              null,
              List.of("a"),
              Param.HOT_ID),
          new Query(
              "hot: indexed filter (100 rows)",
              "SELECT a.id FROM {H:a} WHERE {W:a} AND {a.grp} = ?",
              null,
              null,
              List.of("a"),
              Param.HOT_GRP),
          new Query(
              "hot: key JOIN from a customer",
              "SELECT a.id, {b.qty} FROM {C:a} JOIN {H:b} ON b.id = {a.region}"
                  + " WHERE {W:a} AND {W:b} AND a.id = ?",
              null,
              null,
              List.of("a", "b"),
              Param.CUSTOMER),
          new Query(
                  "hot: COUNT(*) of 20k rows",
                  "SELECT COUNT(*) FROM {H:a} WHERE {W:a}",
                  "SELECT a.id AS id FROM {H:a} WHERE {W:a}",
                  "SELECT COUNT(*) FROM ({ARMS}) v",
                  List.of("a"))
              .partial(
                  "SELECT COUNT(*) AS n FROM {H:a} WHERE {W:a}", "SELECT SUM(v.n) FROM ({ARMS}) v")
              .form(
                  Mode.CORR,
                  corr(
                      "SELECT COUNT(*) AS n FROM {H:x} WHERE {W:x}",
                      "SELECT SUM(v.n) FROM ({ARMS}) v")));

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void readOverhead(Engines e) throws Exception {
    Engine engine = e.engine();
    try (Connection admin = e.open()) {
      if (DROP_EXISTING) {
        dropExisting(e, admin);
      }
      load(e, admin);
    }
    if (modes().contains(Mode.DRIVER)) {
      importForDriver(e);
      // the default catalog TTL, not the tests' 0, which reloads the catalog on every execution
      driver =
          DriverManager.getConnection(
              "jdbc:scalardb-native:" + e.url().substring("jdbc:".length()),
              e.username(),
              e.password());
      if (REGATHER) {
        regather(e);
      }
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
          .append("\n\n")
          .append(environment(e, c))
          .append(
              String.format(
                  Locale.ROOT,
                  "Data: c %,d rows, o %,d rows; %d rows in flight per table, %s.%n",
                  rows("c"),
                  rows("o"),
                  INFLIGHT,
                  NEWEST ? "at the highest ids" : "spread over the key"))
          .append(
              "\nMedian ms over up to 200 runs, 300 rounds through a proxy (ratio to BASE); p95 in brackets. `!` marks rows"
                  + " that differ from "
                  + (CHANGED ? "VIEW_LJ (images changed)" : "BASE")
                  + ".\n\n| query |");
      List<Mode> modes = modes();
      for (Mode m : modes) {
        report.append(' ').append(m).append(" |");
      }
      report.append("\n|---|");
      for (int i = 0; i < modes.size(); i++) {
        report.append("---|");
      }
      report.append('\n');
      String header = report.substring(report.indexOf("| query |"));
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
            if (m == Mode.DRIVER) {
              sql = on(m, c).nativeSQL(sql); // what the driver sends
            }
            noParallel(c, engine, q, m, true);
            Connection open = holdInFlight(e, m);
            plans.append("==== ").append(q.name).append(" / ").append(m).append('\n');
            plans.append(sql).append('\n');
            plans.append(explain(c, engine, sql, q.params)).append('\n');
            release(open);
            noParallel(c, engine, q, m, false);
          }
        }
        Path out = Path.of("build", "bench", "explain-" + e + ".txt");
        Files.createDirectories(Path.of("build", "bench"));
        Files.writeString(out, plans.toString(), StandardCharsets.UTF_8);
        return;
      }
      String only = System.getProperty("nativejdbc.bench.only", "");
      StringBuilder lengths = new StringBuilder();
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
          if (sql != null && (m != Mode.BASE_OPEN || proxy == null)) {
            sqls.put(m, sql);
          }
        }
        lengths.append("| ").append(q.name).append(" |");
        for (Mode m : modes) {
          String sql = sqls.get(m);
          lengths.append(sql == null ? " -" : " " + sql.length()).append(" |");
        }
        lengths.append('\n');
        Map<Mode, Result> results =
            proxy == null ? sequential(c, e, q, sqls) : interleaved(c, e, q, sqls);
        Result reference = results.get(CHANGED ? Mode.VIEW_LJ : Mode.BASE);
        if (reference != null) {
          expected = reference.rows;
        }
        for (Mode m : modes) {
          Result r = results.get(m);
          if (r == null) {
            report.append(sqls.containsKey(m) ? " error |" : " - |");
            continue;
          }
          if (m == Mode.BASE) {
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
      report.append("\nStatement length in characters:\n\n").append(header).append(lengths);
      Connection d = driver;
      if (d != null) {
        report.append(routeCost(c, d, engine, only));
      }
      report.append(writes(c, e));
      String suffix =
          (only.isEmpty() ? "" : "-" + only.replaceAll("\\W+", "_"))
              + (rtt > 0 ? "-rtt" + System.getProperty("nativejdbc.bench.rttMs") : "")
              + (HOT ? "-hot" : "")
              + (FRESH ? "-fresh" : "")
              + (BEFORE_INDEX.isEmpty()
                  ? ""
                  : BEFORE_INDEX.equals("plain") ? "-beforeplain" : "-before")
              + (SS_INDEX.equals("filtered") ? "" : "-ss" + SS_INDEX)
              + (INFLIGHT != 100 ? "-inflight" + INFLIGHT : "")
              + (SCALE != 1 ? "-scale" + SCALE : "")
              + (NEWEST ? "-newest" : "")
              + (GATHER_INFLIGHT != INFLIGHT ? "-gather" + GATHER_INFLIGHT : "")
              + (REGATHER ? "-regather" : "")
              + (CHANGED ? "-changed" : "");
      Path out = Path.of("build", "bench", "read-overhead-" + e + suffix + ".md");
      Files.createDirectories(Path.of("build", "bench"));
      Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    } finally {
      Connection d = driver;
      driver = null;
      if (d != null) {
        d.close();
      }
    }
  }

  /** Each mode measured in turn; a mode that fails is left out. */
  private static Map<Mode, Result> sequential(
      Connection c, Engines e, Query q, Map<Mode, String> sqls) throws SQLException {
    Map<Mode, Result> results = new LinkedHashMap<>();
    for (Map.Entry<Mode, String> m : sqls.entrySet()) {
      noParallel(c, e.engine(), q, m.getKey(), true);
      Connection open = null;
      try {
        open = holdInFlight(e, m.getKey());
        results.put(m.getKey(), measure(on(m.getKey(), c), m.getValue(), q.params));
      } catch (SQLException ex) {
        System.out.printf(
            "BENCH %s | %s | %s | error %s%n", e, q.name, m.getKey(), ex.getMessage());
      } finally {
        release(open);
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
          PreparedStatement ps = on(m.getKey(), c).prepareStatement(m.getValue());
          statements.put(m.getKey(), ps);
          rows.put(m.getKey(), timed(c, e, q, m.getKey(), m.getValue(), ps, 4242, 7, null));
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
              Objects.requireNonNull(sqls.get(m)),
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
      results.put(
          m.getKey(), Result.of(m.getValue(), Objects.requireNonNull(rows.get(m.getKey()))));
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
      String sql,
      PreparedStatement ps,
      int customer,
      int region,
      @Nullable List<Double> times)
      throws SQLException {
    noParallel(c, e.engine(), q, m, true);
    try {
      long t0 = System.nanoTime();
      List<String> rows = run(on(m, c), sql, ps, placeholders(ps), q.params, customer, region);
      if (times != null) {
        times.add((System.nanoTime() - t0) / 1e6);
      }
      return rows;
    } finally {
      noParallel(c, e.engine(), q, m, false);
    }
  }

  /**
   * The driver's cost per statement before it reaches the server (todo 098): prepareStatement
   * through the driver and through the vendor driver, which none of the five sends to the server at
   * prepare, so the difference is the driver's parse and rewrite with nothing cached.
   */
  private static String routeCost(Connection vendor, Connection driver, Engine engine, String only)
      throws SQLException {
    StringBuilder out =
        new StringBuilder(
            "\nprepareStatement, median µs of 1,000 after 300 warm-up: vendor driver, through the"
                + " driver, and through the driver with a new text each time (i leading blanks),"
                + " which the route cache does not hold:\n\n| query | vendor | driver | driver, new"
                + " text |\n|---|---|---|---|\n");
    for (Query q : QUERIES) {
      if (!Pattern.compile(only).matcher(q.name).find()) {
        continue;
      }
      String sql = render(q, Mode.BASE, engine);
      if (sql == null) {
        continue;
      }
      out.append(
          String.format(
              Locale.ROOT,
              "| %s | %.0f | %.0f | %.0f |%n",
              q.name,
              prepareMicros(vendor, sql, false),
              prepareMicros(driver, sql, false),
              prepareMicros(driver, sql, true)));
    }
    return out.toString();
  }

  private static double prepareMicros(Connection c, String sql, boolean newText)
      throws SQLException {
    List<Double> times = new ArrayList<>();
    for (int i = 0; i < 1300; i++) {
      String text = newText ? " ".repeat(i) + sql : sql;
      long t0 = System.nanoTime();
      c.prepareStatement(text).close();
      if (i >= 300) {
        times.add((System.nanoTime() - t0) / 1e3);
      }
    }
    return Result.of(times, List.of()).median;
  }

  // the driver's connection, for DRIVER; one per test run
  private static @Nullable Connection driver;

  private static Connection on(Mode m, Connection vendor) {
    return m == Mode.DRIVER ? Objects.requireNonNull(driver, "driver connection") : vendor;
  }

  /**
   * For BASE_OPEN: a connection whose open transaction has updated the rows in flight of each
   * ScalarDB table, the ones of its metadata table in state 1, every non-key column changed; null
   * for the other modes. Readers pass them without waiting on every engine but SQL Server without
   * READ_COMMITTED_SNAPSHOT.
   */
  private static @Nullable Connection holdInFlight(Engines e, Mode m) throws SQLException {
    if (m != Mode.BASE_OPEN) {
      return null;
    }
    Connection open = e.open();
    try {
      open.setAutoCommit(false);
      for (String t : TABLES) {
        e.execute(open, changeInFlight(t));
      }
      return open;
    } catch (SQLException ex) {
      release(open);
      throw ex;
    }
  }

  /** The update changing every non-key column of t's rows in flight in state 1. */
  private static String changeInFlight(String t) {
    StringBuilder set = new StringBuilder();
    for (String col : Objects.requireNonNull(NON_KEY.get(t))) {
      set.append(set.length() == 0 ? "" : ", ")
          .append(col)
          .append(" = ")
          .append(
              col.equals("name") || col.equals("label")
                  ? "CONCAT(" + col + ", 'x')"
                  : col + " + 1");
    }
    StringBuilder same = new StringBuilder();
    for (String k : key(t)) {
      same.append(" AND m.").append(k).append(" = bn.").append(t).append('.').append(k);
    }
    return "UPDATE bn."
        + t
        + " SET "
        + set
        + " WHERE EXISTS (SELECT 1 FROM bn."
        + t
        + "m m WHERE m.tx_state = 1"
        + same
        + ")";
  }

  private static void release(@Nullable Connection open) throws SQLException {
    if (open != null) {
      try {
        open.rollback();
      } finally {
        open.close();
      }
    }
  }

  /**
   * For DRIVER: imports the benchmark's ScalarDB tables into ScalarDB, decoupled, with ScalarDB
   * indexes on the indexed columns (which also index their before images), copies each table's
   * metadata rows, its rows in flight included, into ScalarDB's metadata table, and runs the
   * driver's setup.
   */
  private static void importForDriver(Engines e) throws Exception {
    Engine engine = e.engine();
    String ns = e.storedSchema("bn");
    Map<String, String> indexed =
        Map.of("c", "region", "o", "customer_id", "w", "c01", "k", "grp", "h", "grp");
    if (engine == Engine.ORACLE) {
      // Oracle takes one index per column list: ScalarDB's replaces the benchmark's own
      try (Connection c = e.open()) {
        e.execute(
            c,
            "DROP INDEX bn.c_region",
            "DROP INDEX bn.o_customer",
            "DROP INDEX bn.w_c01",
            "DROP INDEX bn.k_grp",
            "DROP INDEX bn.h_grp");
      }
    }
    List<String> tables = TABLES;
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      for (String t : tables) {
        admin.importTable(
            ns, e.storedTable(t), Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      for (String t : tables) {
        String col = indexed.get(t);
        if (col != null) {
          // through the virtual table, ScalarDB's name for the imported table, so that it indexes
          // the before image as well; the data table's own name reaches the raw table only
          String virtual =
              Objects.requireNonNull(
                  catalog.lookup(List.of(), ns, e.storedTable(t)).virtualTable(), "virtual table");
          admin.createIndex(ns, virtual, e.storedColumn(col), true);
        }
      }
    }
    catalog.invalidate();
    try (Connection c = e.open()) {
      // statistics on the filled metadata tables and the indexed data tables, as load() gathers
      // them for the benchmark's own; the setup adds the tx_state histograms
      List<String> stats = new ArrayList<>();
      for (String t : tables) {
        TableInfo info = catalog.lookup(List.of(), ns, e.storedTable(t));
        stats.add(statistics(engine, info.namespace(), info.dataTable()));
        stats.add(
            statistics(engine, info.namespace(), Objects.requireNonNull(info.metadataTable())));
        List<String> cols = new ArrayList<>(key(t));
        cols.addAll(
            List.of("tx_id", "tx_state", "tx_version", "before_tx_id", "before_tx_version"));
        for (String col : Objects.requireNonNull(NON_KEY.get(t))) {
          cols.add("before_" + col);
        }
        List<String> target = new ArrayList<>();
        for (String col : cols) {
          target.add(
              engine.quote(
                  col.startsWith("tx_") || col.startsWith("before_tx_")
                      ? col
                      : col.startsWith("before_")
                          ? "before_" + e.storedColumn(col.substring("before_".length()))
                          : e.storedColumn(col)));
        }
        e.execute(
            c,
            "INSERT INTO "
                + engine.quote(info.namespace())
                + "."
                + engine.quote(Objects.requireNonNull(info.metadataTable()))
                + " ("
                + String.join(", ", target)
                + ") SELECT "
                + String.join(", ", cols)
                + " FROM bn."
                + t
                + "m");
      }
      e.execute(c, stats.toArray(new String[0]));
      if (GATHER_INFLIGHT != INFLIGHT) {
        inFlight(e, c, catalog, ns, tables, GATHER_INFLIGHT);
      }
      long started = System.nanoTime();
      List<Setup.Result> results =
          Setup.run(catalog, c, i -> i.namespace().equals(ns), Duration.ofMinutes(30), List.of());
      System.out.printf(
          Locale.ROOT,
          "BENCH %s | setup with %d rows in flight | %.0f ms%n",
          e,
          GATHER_INFLIGHT,
          (System.nanoTime() - started) / 1e6);
      if (GATHER_INFLIGHT != INFLIGHT) {
        inFlight(e, c, catalog, ns, tables, -1);
      }
      for (Setup.Result r : results) {
        if (!r.succeeded()) {
          throw new IllegalStateException("setup failed: " + r);
        }
      }
    }
  }

  /**
   * Sets {@code tx_state} in ScalarDB's metadata tables alone: {@code count} rows in flight per
   * table by the rule of {@link #load}, or with {@code -1} the rows copied in flight (their {@code
   * tx_id} is {@code w}) and no others. Only the statistics see the state between the two: the rows
   * put in flight here have no before image.
   */
  private static void inFlight(
      Engines e, Connection c, Catalog catalog, String ns, List<String> tables, int count)
      throws SQLException {
    Engine engine = e.engine();
    for (String t : tables) {
      TableInfo info = catalog.lookup(List.of(), ns, e.storedTable(t));
      String id = engine.quote(e.storedColumn("id"));
      String key =
          t.equals("k")
              ? "(10 * "
                  + engine.quote(e.storedColumn("pid"))
                  + " + "
                  + engine.quote(e.storedColumn("cid"))
                  + ")"
              : id;
      String inFlight = count < 0 ? engine.quote("tx_id") + " = 'w'" : inFlightAt(t, key, count);
      e.execute(
          c,
          "UPDATE "
              + engine.quote(info.namespace())
              + "."
              + engine.quote(Objects.requireNonNull(info.metadataTable()))
              + " SET "
              + engine.quote("tx_state")
              + " = CASE WHEN "
              + inFlight
              + " THEN 1 ELSE 3 END");
    }
  }

  /**
   * Oracle's nightly statistics job, as it runs after the application: each query once through the
   * driver, so that Oracle records the use of {@code tx_state}, then each ScalarDB metadata table's
   * statistics gathered with the defaults (MySQL's ANALYZE leaves histograms as they are).
   */
  private static void regather(Engines e) throws Exception {
    Engine engine = e.engine();
    Connection d = Objects.requireNonNull(driver, "driver connection");
    for (Query q : QUERIES) {
      String sql = render(q, Mode.DRIVER, engine);
      if (sql == null) {
        continue;
      }
      try (PreparedStatement ps = d.prepareStatement(sql)) {
        run(d, sql, ps, placeholders(ps), q.params, 4242, 7);
      }
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    String ns = e.storedSchema("bn");
    try (Connection c = e.open()) {
      if (engine == Engine.ORACLE) {
        e.execute(c, "BEGIN DBMS_STATS.FLUSH_DATABASE_MONITORING_INFO; END;");
      }
      for (String t : TABLES) {
        TableInfo info = catalog.lookup(List.of(), ns, e.storedTable(t));
        try {
          e.execute(
              c,
              statistics(engine, info.namespace(), Objects.requireNonNull(info.metadataTable())));
        } catch (SQLException ex) {
          // ORA-20005: statistics the setup locked, which the nightly job skips
          if (ex.getErrorCode() != 20005) {
            throw ex;
          }
        }
      }
    }
  }

  /** The statement that gathers one table's statistics on the engine. */
  private static String statistics(Engine engine, String schema, String table) {
    String q = engine.quote(schema) + "." + engine.quote(table);
    switch (engine) {
      case POSTGRESQL:
        return "ANALYZE " + q;
      case MYSQL:
      case MARIADB:
        return "ANALYZE TABLE " + q;
      case SQLSERVER:
        return "UPDATE STATISTICS " + q + " WITH FULLSCAN";
      case ORACLE:
        return "BEGIN DBMS_STATS.GATHER_TABLE_STATS('\""
            + schema
            + "\"', '\""
            + table
            + "\"'); END;";
    }
    throw new IllegalArgumentException(engine.name());
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
    try (java.sql.Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("EXPLAIN " + inline(sql, params, 4242, 7))) {
      while (rs.next()) {
        if (rs.getString(1).contains("Gather")) {
          return true;
        }
      }
    }
    return false;
  }

  // ---- rendering

  private static final Pattern FROM = Pattern.compile("\\{([CORXKH]):(\\w+)(?:/([ABF]))?\\}");
  private static final Pattern WHERE = Pattern.compile("\\{W:(\\w+)(?:/([ABF]))?\\}");
  private static final Pattern COLUMN = Pattern.compile("\\{(\\w+)\\.(\\w+)(?:/([ABF]))?\\}");

  static @Nullable String render(Query q, Mode m, Engine engine) {
    String sql = renderRaw(q, m == Mode.DRIVER || m == Mode.BASE_OPEN ? Mode.BASE : m, engine);
    if (sql == null) {
      return null;
    }
    Matcher page = PAGE.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (page.find()) {
      page.appendReplacement(
          out,
          Matcher.quoteReplacement(
              page(engine, Integer.parseInt(page.group(1)), Integer.parseInt(page.group(2)))));
    }
    page.appendTail(out);
    return out.toString();
  }

  private static final Pattern FIRST_ROWS = Pattern.compile("\\{PAGE:0:(\\d+)\\}$");

  private static final Pattern PAGE = Pattern.compile("\\{PAGE:(\\d+):(\\d+)\\}");

  /** {@code n} rows after the first {@code offset}, in the engine's syntax. */
  private static String page(Engine engine, int offset, int n) {
    return engine == Engine.SQLSERVER || engine == Engine.ORACLE
        ? " OFFSET " + offset + " ROWS FETCH NEXT " + n + " ROWS ONLY"
        : " LIMIT " + n + (offset > 0 ? " OFFSET " + offset : "");
  }

  /** The committed form the driver chooses on the engine for {@code n} tables (SplitRead). */
  private static Mode driverMode(Engine engine, int n) {
    switch (engine) {
      case MYSQL:
      case MARIADB:
        return Mode.SPLIT_DHD;
      case SQLSERVER:
        return n <= 2 ? Mode.SPLIT_DMX : Mode.SPLIT_DMH;
      default:
        return Mode.SPLIT_DAJ;
    }
  }

  private static @Nullable String renderRaw(Query q, Mode m, Engine engine) {
    if (written(m)) {
      String form = q.forms.get(m);
      if (form == null) {
        return null;
      }
      String sql = render(form, m, Map.of());
      if (sql.contains("{COPIES}")) {
        String copy = Objects.requireNonNull(q.copy != null ? q.copy : q.pushed, q.name);
        List<String> copies = new ArrayList<>();
        int n = q.splitAliases.size();
        for (int bits = 1; bits < (1 << n); bits++) {
          Map<String, Character> arm = new LinkedHashMap<>();
          for (int i = 0; i < n; i++) {
            arm.put(q.splitAliases.get(i), (bits & (1 << i)) == 0 ? 'A' : 'B');
          }
          copies.add("SELECT * FROM (" + render(copy, Mode.SPLIT_AJ, arm) + ") p" + bits);
        }
        sql = sql.replace("{COPIES}", String.join(" UNION ALL ", copies));
      }
      return sql;
    }
    boolean rownum = m == Mode.SPLIT_DRVR;
    boolean noSemijoin = m == Mode.SPLIT_DAJN || m == Mode.SPLIT_DAJH;
    boolean narrow = m == Mode.SPLIT_DRVN;
    boolean pushed =
        m == Mode.SPLIT_DRVP
            || m == Mode.SPLIT_DAJP
            || m == Mode.SPLIT_DHDP
            || m == Mode.SPLIT_DLJP
            || m == Mode.SPLIT_DAJN
            || rownum;
    if (pushed && q.pushed == null
        || rownum && engine != Engine.ORACLE
        || m == Mode.SPLIT_DAJH && engine != Engine.MYSQL) {
      return null;
    }
    if (m == Mode.SPLIT_DAJP || noSemijoin) {
      m = Mode.SPLIT_DAJ;
    } else if (m == Mode.SPLIT_DLJP) {
      m = Mode.SPLIT_DLJ;
    } else if (m == Mode.SPLIT_DHDP) {
      m = Mode.SPLIT_DHD;
    } else if (m == Mode.SPLIT_DRV || pushed || narrow) {
      m = driverMode(engine, q.splitAliases.size());
    }
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
      String template =
          pushed
              ? Objects.requireNonNull(q.pushed)
              : partial && q.partialInner != null ? q.partialInner : q.split;
      String copy =
          derived ? renderDerived(template, armMode, arm, narrow) : render(template, armMode, arm);
      // a copy with its own ORDER BY and limit is a derived table: UNION ALL takes it on every
      // engine
      if (noSemijoin) {
        copy = copy.replace("NOT EXISTS (SELECT 1", "NOT EXISTS (SELECT /*+ NO_SEMIJOIN() */ 1");
      }
      Matcher first = FIRST_ROWS.matcher(copy);
      if (rownum && first.find()) {
        arms.add(
            "SELECT * FROM ("
                + copy.substring(0, first.start())
                + ") p"
                + bits
                + " WHERE ROWNUM <= "
                + first.group(1));
      } else {
        arms.add(pushed ? "SELECT * FROM (" + copy + ") p" + bits : copy);
      }
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
      case SPLIT_DLJ:
        return Mode.SPLIT_LJ;
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
   * alias and exposing the table's own column names, and nothing else in the statement changed;
   * {@code narrow}: only the key and the columns the template references.
   */
  private static String renderDerived(
      String template, Mode m, Map<String, Character> arms, boolean narrow) {
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
              StringBuilder cols = new StringBuilder(keyList(t, x));
              for (String col : Objects.requireNonNull(NON_KEY.get(t))) {
                String ref = "{" + x + "." + col;
                if (narrow && !template.contains(ref + "}") && !template.contains(ref + "/")) {
                  continue;
                }
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

  /** The modes written out per query, CORR, OVER and SEMI: a table without an arm is plain. */
  private static boolean written(Mode m) {
    return m == Mode.CORR || m == Mode.OVER || m == Mode.SEMI;
  }

  private static String from(String t, String x, Mode m, @Nullable Character arm) {
    if ((split(m) || written(m)) && arm != null) {
      String meta = String.format("bn.%sm %sm ON %s", t, x, keyEq(t, x + "m", x));
      String set =
          String.format(
              "SELECT %s FROM bn.%sm %sq WHERE %sq.tx_state IN (1, 2)",
              keyList(t, x + "q"), t, x, x);
      if (arm == 'B' || arm == 'F') {
        return String.format("(bn.%s %s JOIN %s)", t, x, meta);
      }
      switch (m) {
        case SPLIT_LJ:
          return String.format("(bn.%s %s LEFT JOIN %s)", t, x, meta);
        case SPLIT_HJ:
          return String.format(
              "(bn.%s %s LEFT JOIN (%s) %sf ON %s)", t, x, set, x, keyEq(t, x + "f", x));
        case SPLIT_HD:
        case SPLIT_PH:
          return String.format(
              "(bn.%s %s LEFT JOIN (%s) %sf ON %s)",
              t, x, set.replace("SELECT ", "SELECT DISTINCT "), x, keyEq(t, x + "f", x));
        default:
          return "bn." + t + " " + x;
      }
    }
    switch (m) {
      case BASE:
      case CORR:
      case OVER:
      case SEMI:
        return "bn." + t + " " + x;
      case VIEW_LJ:
      case SPLIT_LJ:
        return "bn.rcl_" + t + " " + x;
      case VIEW_CASE:
        return "bn.rcj_" + t + " " + x;
      default:
        return "bn.rca_" + t + " " + x;
    }
  }

  private static String where(String t, String x, Mode m, @Nullable Character arm) {
    if (!split(m) && !written(m) || arm == null) {
      return "1 = 1";
    }
    if (arm == 'F') {
      return x + "m.tx_state IN (1, 2)";
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
            "(%s) NOT IN (SELECT %s FROM bn.%sm %sq WHERE %sq.tx_state IN (1, 2))",
            keyList(t, x), keyList(t, x + "q"), t, x, x);
      case SPLIT_HJ:
      case SPLIT_HD:
      case SPLIT_PH:
        return x + "f." + key(t).get(0) + " IS NULL";
      default:
        return String.format(
            "NOT EXISTS (SELECT 1 FROM bn.%sm %sq WHERE %s AND %sq.tx_state IN (1, 2))",
            t, x, keyEq(t, x + "q", x), x);
    }
  }

  private static String column(String x, String col, Mode m, @Nullable Character arm) {
    return (split(m) || written(m)) && arm != null && arm == 'B'
        ? x + "m.before_" + col
        : x + "." + col;
  }

  // ---- measuring

  static final class Result {
    final double median;
    final double p95;
    final List<String> rows;

    /** The median and 95th percentile of {@code times}, which this sorts. */
    static Result of(List<Double> times, List<String> rows) {
      Collections.sort(times);
      return new Result(
          times.get(times.size() / 2),
          times.get((int) Math.min(times.size() - 1, times.size() * 0.95)),
          rows);
    }

    Result(double median, double p95, List<String> rows) {
      this.median = median;
      this.p95 = p95;
      this.rows = rows;
    }
  }

  private static Result measure(Connection c, String sql, Param[] params) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setQueryTimeout(TIMEOUT_SECONDS); // a plan that never ends is an error, not a wait
      int placeholders = ps.getParameterMetaData().getParameterCount();
      Random random = new Random(42);
      List<String> first =
          run(c, sql, ps, placeholders, params, 4242, 7); // a customer and region in flight
      long warmUntil = System.nanoTime() + 2_000_000_000L;
      for (int i = 0; i < 20 && System.nanoTime() < warmUntil; i++) {
        run(c, sql, ps, placeholders, params, customer(random), region(random));
      }
      List<Double> times = new ArrayList<>();
      long requestsBefore = proxy == null ? 0 : proxy.requests.get();
      long until = System.nanoTime() + 3_000_000_000L;
      while (times.size() < 200 && System.nanoTime() < until) {
        int customer = customer(random);
        int region = region(random);
        long t0 = System.nanoTime();
        run(c, sql, ps, placeholders, params, customer, region);
        times.add((System.nanoTime() - t0) / 1e6);
      }
      if (proxy != null) {
        System.out.printf(
            Locale.ROOT,
            "BENCH requests per execution %.2f%n",
            (proxy.requests.get() - requestsBefore) / (double) times.size());
      }
      return Result.of(times, first);
    }
  }

  /** The proxy the measuring connection goes through, if any. */
  private static @Nullable DelayProxy proxy;

  // -Dnativejdbc.bench.hot=true: only customers and the region with rows in flight
  private static final boolean HOT = Boolean.getBoolean("nativejdbc.bench.hot");

  // -Dnativejdbc.bench.dropExisting=true: drop what an earlier run left on an external database
  private static final boolean DROP_EXISTING = Boolean.getBoolean("nativejdbc.bench.dropExisting");

  // -Dnativejdbc.bench.timeoutSeconds=30: the statement timeout of a measured statement
  private static final int TIMEOUT_SECONDS =
      Integer.getInteger("nativejdbc.bench.timeoutSeconds", 120);

  // -Dnativejdbc.bench.tier=A: the environment named in the report, "local" by default
  private static final String TIER = System.getProperty("nativejdbc.bench.tier", "local");

  /**
   * Drops the schemas a run creates, which a container never has but an external database keeps
   * from the run before: the benchmark's, and ScalarDB's metadata and coordinator, which ScalarDB
   * names in lower case quoted.
   */
  private static void dropExisting(Engines e, Connection c) throws SQLException {
    e.dropSchemas(c, e.storedSchema("bn"), "scalardb", "coordinator");
  }

  /** The settings that change plans, per engine, recorded in each report. */
  private static String settingsQuery(Engine engine) {
    switch (engine) {
      case POSTGRESQL:
        return "SELECT name || '=' || current_setting(name) FROM pg_settings WHERE name IN"
            + " ('shared_buffers', 'effective_cache_size', 'work_mem', 'random_page_cost',"
            + " 'max_parallel_workers_per_gather', 'jit') ORDER BY name";
      case MYSQL:
      case MARIADB:
        // MariaDB has no performance_schema.global_variables
        return "SELECT CONCAT(LOWER(variable_name), '=', variable_value) FROM "
            + (engine == Engine.MARIADB ? "information_schema" : "performance_schema")
            + ".global_variables WHERE LOWER(variable_name) IN"
            + " ('innodb_buffer_pool_size', 'innodb_redo_log_capacity', 'innodb_log_file_size',"
            + " 'optimizer_switch', 'join_buffer_size', 'sort_buffer_size') ORDER BY 1";
      case ORACLE:
        return "SELECT name || '=' || value FROM v$parameter WHERE name IN ('sga_target',"
            + " 'pga_aggregate_target', 'memory_target', 'optimizer_features_enable', 'cpu_count')"
            + " ORDER BY name";
      default:
        return "SELECT name + '=' + CAST(value_in_use AS VARCHAR(40)) FROM sys.configurations"
            + " WHERE name IN ('max server memory (MB)', 'max degree of parallelism',"
            + " 'cost threshold for parallelism') ORDER BY name";
    }
  }

  /** The tier, engine version and plan-changing settings, as the report's first lines. */
  private static String environment(Engines e, Connection c) throws SQLException {
    StringBuilder out = new StringBuilder();
    out.append("Environment: ")
        .append(TIER)
        .append(", ")
        .append(c.getMetaData().getDatabaseProductVersion().replace('\n', ' '))
        .append(". Settings:");
    try (java.sql.Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(settingsQuery(e.engine()))) {
      while (rs.next()) {
        out.append(" `").append(rs.getString(1)).append('`');
      }
    } catch (SQLException ex) {
      out.append(" not readable (").append(ex.getMessage().trim()).append(')');
    }
    return out.append(".\n").toString();
  }

  // -Dnativejdbc.bench.plainCaseFilter=true: VIEW_CASE's filter without COALESCE, which
  // PostgreSQL can estimate from the statistics of tx_state
  // -Dnativejdbc.bench.changed=true: rows in flight whose images differ, for checking a form's
  // rows; BASE then reads the after images, so the rows are checked against VIEW_LJ's
  private static final boolean CHANGED = Boolean.getBoolean("nativejdbc.bench.changed");

  private static final boolean PLAIN_CASE_FILTER =
      Boolean.getBoolean("nativejdbc.bench.plainCaseFilter");

  // -Dnativejdbc.bench.inflight=10000: rows in flight per table, 100 by default
  private static final int INFLIGHT = Integer.getInteger("nativejdbc.bench.inflight", 100);

  // -Dnativejdbc.bench.scale=10: c and o that many times larger, 1 to 20; h, r, w and k unchanged
  private static final int SCALE = Integer.getInteger("nativejdbc.bench.scale", 1);

  // -Dnativejdbc.bench.inflightAt=newest: each table's rows in flight at its highest ids, where
  // pages of ORDER BY key DESC read and where new rows are written, instead of spread over the key
  private static final boolean NEWEST =
      System.getProperty("nativejdbc.bench.inflightAt", "spread").equals("newest");

  private static int rows(String t) {
    switch (t) {
      case "c":
        return 100_000 * SCALE;
      case "o":
        return 500_000 * SCALE;
      case "h":
        return 20_000;
      case "r":
        return 1000;
      default:
        return 100_000;
    }
  }

  /** Which rows of t, by key, are in flight when count are: spread over the key, or the newest. */
  private static String inFlightAt(String t, String key, int count) {
    if (count == 0) {
      return "1 = 0";
    }
    int per = Math.max(2, rows(t) / count); // at most half the table
    if (NEWEST) {
      return key + " >= " + (rows(t) - rows(t) / per);
    }
    return key
        + " - "
        + per
        + " * FLOOR("
        + key
        + " / "
        + per
        + ") = "
        + (t.equals("o") ? 3 : 7) % per;
  }

  // -Dnativejdbc.bench.gatherInflight=0: rows in flight per table in ScalarDB's metadata tables
  // while the driver's setup gathers their statistics (DRIVER only), INFLIGHT by default
  private static final int GATHER_INFLIGHT =
      Integer.getInteger("nativejdbc.bench.gatherInflight", INFLIGHT);

  // -Dnativejdbc.bench.regather=true: Oracle's nightly statistics job before measuring (regather)
  private static final boolean REGATHER = Boolean.getBoolean("nativejdbc.bench.regather");

  private static int customer(Random random) {
    return HOT ? 7 + 1000 * random.nextInt(100) : random.nextInt(rows("c"));
  }

  private static int region(Random random) {
    return HOT ? 7 : random.nextInt(1000);
  }

  // -Dnativejdbc.bench.fresh=true: each execution a new statement with the values inline, so that
  // every engine parses and plans it again
  private static final boolean FRESH = Boolean.getBoolean("nativejdbc.bench.fresh");

  /** The value of parameter p for a customer and a region. */
  private static Object arg(Param p, int customer, int region) {
    switch (p) {
      case NAME:
        return "n" + customer;
      case CUSTOMER:
        return customer;
      case REGION:
        return region;
      case PART:
        return customer / 10;
      case CLUSTER:
        return customer % 10;
      case HOT_ID:
        return customer % 20_000;
      case HOT_GRP:
        return region % 200;
      default:
        return Integer.parseInt(p.name().substring(4));
    }
  }

  private static void bind(
      PreparedStatement ps, int placeholders, Param[] params, int customer, int region)
      throws SQLException {
    for (int i = 0; i < placeholders; i++) {
      Object v = arg(params[i % params.length], customer, region);
      if (v instanceof String) {
        ps.setString(i + 1, (String) v);
      } else {
        ps.setInt(i + 1, (Integer) v);
      }
    }
  }

  /** The statement with each parameter replaced by its value. */
  private static String inline(String sql, Param[] params, int customer, int region) {
    StringBuilder bound = new StringBuilder();
    int n = 0;
    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      if (ch != '?') {
        bound.append(ch);
        continue;
      }
      Object v = arg(params[n++ % params.length], customer, region);
      bound.append(v instanceof String ? "'" + v + "'" : v);
    }
    return bound.toString();
  }

  private static List<String> run(
      Connection c,
      String sql,
      PreparedStatement ps,
      int placeholders,
      Param[] params,
      int customer,
      int region)
      throws SQLException {
    if (FRESH) {
      try (java.sql.Statement st = c.createStatement();
          ResultSet rs = st.executeQuery(inline(sql, params, customer, region))) {
        return rows(rs);
      }
    }
    bind(ps, placeholders, params, customer, region);
    try (ResultSet rs = ps.executeQuery()) {
      return rows(rs);
    }
  }

  private static List<String> rows(ResultSet rs) throws SQLException {
    List<String> rows = new ArrayList<>();
    int n = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      String[] row = new String[n];
      for (int i = 0; i < n; i++) {
        row[i] = rs.getString(i + 1);
      }
      rows.add(Arrays.toString(row));
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
        // -Dnativejdbc.bench.timing=true: each node timed
        prefix =
            "EXPLAIN (ANALYZE, COSTS ON, TIMING "
                + (Boolean.getBoolean("nativejdbc.bench.timing") ? "ON" : "OFF")
                + ", SUMMARY ON) ";
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
      bind(ps, ps.getParameterMetaData().getParameterCount(), params, HOT ? 4007 : 4242, 7);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.append(rs.getString(1)).append('\n');
        }
      }
    }
    return out.toString();
  }

  /** The executed plan with actual rows and executions per operator. */
  private static String sqlServerPlan(Connection c, String sql, Param[] params)
      throws SQLException {
    StringBuilder out = new StringBuilder();
    try (java.sql.Statement st = c.createStatement()) {
      st.execute("SET STATISTICS PROFILE ON");
      try (PreparedStatement ps = c.prepareStatement(sql)) {
        bind(ps, ps.getParameterMetaData().getParameterCount(), params, HOT ? 4007 : 4242, 7);
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
    if (SCALE < 1 || SCALE > 20) {
      throw new IllegalArgumentException("nativejdbc.bench.scale must be 1 to 20: " + SCALE);
    }
    // bn.s: 0 to 5 * SCALE - 1, the blocks of 100k ids of c and o
    ddl.add("CREATE TABLE bn.s (n " + in + ")");
    for (int i = 0; i < 5 * SCALE; i++) {
      ddl.add("INSERT INTO bn.s VALUES (" + i + ")");
    }
    ddl.add("CREATE TABLE bn.c (id " + in + " PRIMARY KEY, name VARCHAR(20), region " + in + ")");
    ddl.add(
        "CREATE TABLE bn.o (id " + in + " PRIMARY KEY, customer_id " + in + ", total " + in + ")");
    ddl.add("CREATE TABLE bn.r (id " + in + " PRIMARY KEY, label VARCHAR(20))");
    ddl.add("CREATE TABLE bn.p (id " + in + " PRIMARY KEY, cid " + in + ", region " + in + ")");
    ddl.add("CREATE TABLE bn.h (id " + in + " PRIMARY KEY, qty " + in + ", grp " + in + ")");
    StringBuilder wide = new StringBuilder("CREATE TABLE bn.w (id " + in + " PRIMARY KEY");
    for (String col : WIDE) {
      wide.append(", ").append(col).append(' ').append(in);
    }
    ddl.add(wide + ")");
    ddl.add(
        "CREATE TABLE bn.k (pid "
            + in
            + ", cid "
            + in
            + ", grp "
            + in
            + ", val "
            + in
            + ", PRIMARY KEY (pid, cid))");
    for (String t : TABLES) {
      StringBuilder cols = new StringBuilder();
      for (String col : NON_KEY.get(t)) {
        cols.append(", before_")
            .append(col)
            .append(col.equals("name") || col.equals("label") ? " VARCHAR(20)" : " " + in);
      }
      ddl.add(
          "CREATE TABLE bn."
              + t
              + "m ("
              + String.join(" " + in + ", ", key(t))
              + " "
              + in
              + ", tx_id "
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
              + ", PRIMARY KEY ("
              + String.join(", ", key(t))
              + "))");
    }
    String ids5 = "d1.n + 10 * d2.n + 100 * d3.n + 1000 * d4.n + 10000 * d5.n";
    String digits5 = "bn.d d1, bn.d d2, bn.d d3, bn.d d4, bn.d d5";
    ddl.add(
        "INSERT INTO bn.c (id, name, region) SELECT x.id, CONCAT('n', x.id), x.id - 1000 *"
            + " FLOOR(x.id / 1000) FROM (SELECT "
            + ids5
            + " + 100000 * s.n AS id FROM "
            + digits5
            + ", bn.s s WHERE s.n < "
            + SCALE
            + ") x");
    ddl.add(
        "INSERT INTO bn.o (id, customer_id, total) SELECT x.id, x.id - "
            + rows("c")
            + " * FLOOR(x.id / "
            + rows("c")
            + "), x.id - 1000 * FLOOR(x.id / 1000) FROM (SELECT "
            + ids5
            + " + 100000 * s.n AS id FROM "
            + digits5
            + ", bn.s s) x");
    ddl.add(
        "INSERT INTO bn.r (id, label) SELECT x.id, CONCAT('r', x.id) FROM (SELECT d1.n + 10 *"
            + " d2.n + 100 * d3.n AS id FROM bn.d d1, bn.d d2, bn.d d3) x");
    ddl.add(
        "INSERT INTO bn.rm (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.r");
    ddl.add(
        "UPDATE bn.rm SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_label = CONCAT('r', id)"
            + " WHERE id - 100 * FLOOR(id / 100) = 7"
            + (INFLIGHT == 0 ? " AND 1 = 0" : ""));
    ddl.add("INSERT INTO bn.p (id, cid, region) SELECT n, n * 7919, n * 37 FROM bn.d");
    ddl.add(
        "INSERT INTO bn.cm (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.c");
    ddl.add(
        "INSERT INTO bn.om (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.o");
    StringBuilder wideCols = new StringBuilder("id");
    StringBuilder wideValues = new StringBuilder("x.id");
    StringBuilder wideBefore = new StringBuilder();
    for (String col : WIDE) {
      String v = wideValue(col, "x.id");
      wideCols.append(", ").append(col);
      wideValues.append(", ").append(v);
      wideBefore.append(", before_").append(col).append(" = ").append(wideValue(col, "id"));
    }
    ddl.add(
        "INSERT INTO bn.w ("
            + wideCols
            + ") SELECT "
            + wideValues
            + " FROM (SELECT "
            + ids5
            + " AS id FROM "
            + digits5
            + ") x");
    ddl.add(
        "INSERT INTO bn.k (pid, cid, grp, val) SELECT FLOOR(x.id / 10), x.id - 10 * FLOOR(x.id /"
            + " 10), x.id - 1000 * FLOOR(x.id / 1000), x.id + 1 FROM (SELECT "
            + ids5
            + " AS id FROM "
            + digits5
            + ") x");
    ddl.add(
        "INSERT INTO bn.wm (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.w");
    ddl.add(
        "INSERT INTO bn.km (pid, cid, tx_id, tx_state, tx_version) SELECT pid, cid, 'seed', 3, 1"
            + " FROM bn.k");
    ddl.add(
        "INSERT INTO bn.h (id, qty, grp) SELECT x.id, x.id, x.id - 200 * FLOOR(x.id / 200) FROM"
            + " (SELECT "
            + ids5
            + " AS id FROM "
            + digits5
            + ") x WHERE x.id < 20000");
    ddl.add(
        "INSERT INTO bn.hm (id, tx_id, tx_state, tx_version) SELECT id, 'seed', 3, 1 FROM bn.h");
    // INFLIGHT rows in flight per table (at most half of c), their before image equal to the
    // current values; the rows in flight at 100 stay in flight at any larger count
    // and at 0 none: the driver's rewrite alone
    ddl.add(
        "UPDATE bn.cm SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_name = CONCAT('n', id), before_region = id - 1000 *"
            + " FLOOR(id / 1000) WHERE "
            + inFlightAt("c", "id", INFLIGHT));
    ddl.add(
        "UPDATE bn.om SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_customer_id = id - "
            + rows("c")
            + " * FLOOR(id / "
            + rows("c")
            + "), before_total = id - 1000 * FLOOR(id / 1000) WHERE "
            + inFlightAt("o", "id", INFLIGHT));
    // w and k: the rows of c's ids in flight, k's rows numbered 10 * pid + cid
    ddl.add(
        "UPDATE bn.wm SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1"
            + wideBefore
            + " WHERE "
            + inFlightAt("w", "id", INFLIGHT));
    String kid = "(10 * pid + cid)";
    ddl.add(
        "UPDATE bn.km SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_grp = "
            + kid
            + " - 1000 * FLOOR("
            + kid
            + " / 1000), before_val = "
            + kid
            + " + 1 WHERE "
            + inFlightAt("k", kid, INFLIGHT));
    ddl.add(
        "UPDATE bn.hm SET tx_id = 'w', tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
            + " before_tx_version = 1, before_qty = id, before_grp = id - 200 * FLOOR(id / 200)"
            + " WHERE "
            + inFlightAt("h", "id", INFLIGHT));
    if (CHANGED) {
      // after images apart from the before images, half of c's and o's rows in flight deleted,
      // and ten rows of c, o and h inserted in flight: at the first run's customer and region
      for (String t : TABLES) {
        ddl.add(changeInFlight(t));
      }
      ddl.add("UPDATE bn.cm SET tx_state = 2 WHERE tx_state = 1 AND id - 2 * FLOOR(id / 2) = 0");
      ddl.add("UPDATE bn.om SET tx_state = 2 WHERE tx_state = 1 AND id - 2 * FLOOR(id / 2) = 0");
      ddl.add(
          "INSERT INTO bn.c (id, name, region) SELECT "
              + rows("c")
              + " + n, CONCAT('i', "
              + rows("c")
              + " + n), 7 FROM bn.d");
      ddl.add(
          "INSERT INTO bn.o (id, customer_id, total) SELECT "
              + rows("o")
              + " + n, 4242, 1 FROM bn.d");
      ddl.add("INSERT INTO bn.h (id, qty, grp) SELECT " + rows("h") + " + n, n, 7 FROM bn.d");
      for (String t : List.of("c", "o", "h")) {
        ddl.add(
            "INSERT INTO bn."
                + t
                + "m (id, tx_id, tx_state, tx_version) SELECT "
                + rows(t)
                + " + n, 'w', 1, 1 FROM bn.d");
      }
    }
    String ix = engine == Engine.ORACLE ? "bn." : "";
    ddl.add("CREATE INDEX " + ix + "c_region ON bn.c (region)");
    ddl.add("CREATE INDEX " + ix + "o_customer ON bn.o (customer_id)");
    ddl.add("CREATE INDEX " + ix + "w_c01 ON bn.w (c01)");
    ddl.add("CREATE INDEX " + ix + "k_grp ON bn.k (grp)");
    ddl.add("CREATE INDEX " + ix + "h_grp ON bn.h (grp)");
    for (String t : TABLES) {
      String ss = SS_INDEX;
      boolean partial =
          engine == Engine.POSTGRESQL || (engine == Engine.SQLSERVER && !ss.equals("composite"));
      StringBuilder include = new StringBuilder("before_tx_id, before_tx_version");
      for (String col : Objects.requireNonNull(NON_KEY.get(t))) {
        include.append(", before_").append(col);
      }
      ddl.add(
          (engine == Engine.SQLSERVER && ss.equals("unique")
                  ? "CREATE UNIQUE INDEX "
                  : "CREATE INDEX ")
              + ix
              + t
              + "m_inflight ON bn."
              + t
              + "m "
              + (partial
                  ? "("
                      + String.join(", ", key(t))
                      + ")"
                      + (engine == Engine.SQLSERVER && ss.equals("include")
                          ? " INCLUDE (" + include + ")"
                          : "")
                      + " WHERE tx_state IN (1, 2)"
                  : "(tx_state, " + String.join(", ", key(t)) + ")"));
      ddl.addAll(views(t));
    }
    if (!BEFORE_INDEX.isEmpty()) {
      // each secondary index of a data table mirrored on the before image: "plain" as ScalarDB
      // creates it, otherwise restricted to rows in flight
      String[][] mirrored = {
        {"c", "region"}, {"o", "customer_id"}, {"w", "c01"}, {"k", "grp"}, {"h", "grp"}
      };
      for (String[] tc : mirrored) {
        boolean plain = BEFORE_INDEX.equals("plain");
        boolean filtered = engine == Engine.POSTGRESQL || engine == Engine.SQLSERVER;
        ddl.add(
            "CREATE INDEX "
                + ix
                + tc[0]
                + "m_before ON bn."
                + tc[0]
                + "m "
                + (plain
                    ? "(before_" + tc[1] + ")"
                    : filtered
                        ? "(before_" + tc[1] + ") WHERE tx_state IN (1, 2)"
                        : "(tx_state, before_" + tc[1] + ")"));
      }
    }
    switch (engine) {
      case POSTGRESQL:
        ddl.add("ANALYZE");
        break;
      case MYSQL:
        ddl.add(
            "ANALYZE TABLE bn.c, bn.cm, bn.o, bn.om, bn.r, bn.rm, bn.p, bn.w, bn.wm, bn.k, bn.km,"
                + " bn.h, bn.hm");
        // without a histogram MySQL takes tx_state IN (1, 2) for the whole table in a subquery
        ddl.add(
            "ANALYZE TABLE bn.cm, bn.om, bn.rm, bn.wm, bn.km, bn.hm UPDATE HISTOGRAM ON tx_state"
                + " WITH 16"
                + " BUCKETS");
        break;
      case MARIADB:
        ddl.add(
            "ANALYZE TABLE bn.c, bn.cm, bn.o, bn.om, bn.r, bn.rm, bn.p, bn.w, bn.wm, bn.k, bn.km,"
                + " bn.h, bn.hm");
        break;
      case SQLSERVER:
        for (String t :
            List.of("c", "cm", "o", "om", "r", "rm", "p", "w", "wm", "k", "km", "h", "hm")) {
          ddl.add(statistics(engine, "bn", t));
        }
        break;
      case ORACLE:
        ddl.add("BEGIN DBMS_STATS.GATHER_SCHEMA_STATS('BN'); END;");
        // without a histogram Oracle takes tx_state IN (1, 2) for half the table and scans it
        for (String t : List.of("CM", "OM", "RM", "WM", "KM", "HM")) {
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

  // -Dnativejdbc.bench.beforeIndex=true or plain: the data tables' secondary indexes mirrored on
  // the before image of the metadata tables, restricted to rows in flight or (plain) as ScalarDB
  // creates them
  private static final String BEFORE_INDEX =
      System.getProperty("nativejdbc.bench.beforeIndex", "false").equals("false")
          ? ""
          : System.getProperty("nativejdbc.bench.beforeIndex");

  // -Dnativejdbc.bench.ssIndex=composite, unique or include (the before image as included columns):
  // SQL Server's in-flight index
  private static final String SS_INDEX = System.getProperty("nativejdbc.bench.ssIndex", "filtered");

  /**
   * The cost of a write under the metadata's indexes: a ScalarDB-style prepare (the row in flight
   * with its before image) and commit (the before image cleared, as ScalarDB does) of one committed
   * row of {@code cm}, each its own transaction. The rows written are never in flight at any count,
   * and stay committed.
   */
  private static String writes(Connection c, Engines e) throws SQLException {
    List<Double> times = new ArrayList<>();
    try (PreparedStatement prepare =
            c.prepareStatement(
                "UPDATE bn.cm SET tx_id = ?, tx_state = 1, tx_version = 2, before_tx_id = 'seed',"
                    + " before_tx_version = 1, before_name = ?, before_region = ? WHERE id = ? AND"
                    + " tx_state = 3");
        PreparedStatement commit =
            c.prepareStatement(
                "UPDATE bn.cm SET tx_state = 3, before_tx_id = NULL, before_tx_version = NULL,"
                    + " before_name = NULL, before_region = NULL WHERE id = ? AND tx_id = ?")) {
      for (int i = 0; i < 520; i++) {
        int id = 10 * (i * 7919 % 10_000) + 4; // scattered, each row once
        String tx = "b" + i;
        long t0 = System.nanoTime();
        prepare.setString(1, tx);
        prepare.setString(2, "n" + id);
        prepare.setInt(3, id % 1000);
        prepare.setInt(4, id);
        prepare.executeUpdate();
        commit.setInt(1, id);
        commit.setString(2, tx);
        commit.executeUpdate();
        if (i >= 20) {
          times.add((System.nanoTime() - t0) / 1e6);
        }
      }
    }
    Collections.sort(times);
    double median = times.get(times.size() / 2);
    double p95 = times.get((int) (times.size() * 0.95));
    System.out.printf(Locale.ROOT, "BENCH %s | write | %.3f ms%n", e, median);
    return String.format(
        Locale.ROOT,
        "%nWrites: a prepare and a commit of one row of `cm`, median %.2f ms, p95 %.2f ms.%n",
        median,
        p95);
  }

  /** The value of a column of w for the row with key {@code id}. */
  private static String wideValue(String col, String id) {
    return col.equals("c01")
        ? id + " - 1000 * FLOOR(" + id + " / 1000)"
        : id + " + " + Integer.parseInt(col.substring(1));
  }

  private static List<String> views(String t) {
    List<String> nonKey = NON_KEY.get(t);
    String raw = keyList(t, "x");
    String before = raw;
    String cased = raw;
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
            + "m xm ON "
            + keyEq(t, "xm", "x")
            + " WHERE xm.tx_state IN (1, 2) AND (xm.before_tx_id IS NOT NULL"
            + " OR xm.before_tx_version IS NOT NULL)";
    return List.of(
        "CREATE VIEW bn.rcl_"
            + t
            + " AS SELECT "
            + raw
            + " FROM bn."
            + t
            + " x LEFT JOIN bn."
            + t
            + "m xm ON "
            + keyEq(t, "xm", "x")
            + " WHERE COALESCE(xm.tx_state, 3) = 3"
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
            + "m xq WHERE "
            + keyEq(t, "xq", "x")
            + " AND xq.tx_state IN (1, 2))"
            + inFlight,
        "CREATE VIEW bn.rcj_"
            + t
            + " AS SELECT "
            + cased
            + " FROM bn."
            + t
            + " x LEFT JOIN bn."
            + t
            + "m xm ON "
            + keyEq(t, "xm", "x")
            + (PLAIN_CASE_FILTER
                ? " WHERE xm.tx_state IS NULL OR xm.tx_state = 3"
                : " WHERE COALESCE(xm.tx_state, 3) = 3")
            + " OR (xm.tx_state IN (1,"
            + " 2) AND (xm.before_tx_id IS NOT NULL OR xm.before_tx_version IS NOT NULL))");
  }
}
