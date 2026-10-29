package com.scalar.db.nativejdbc.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone T4 of the testing plan on every engine: the read queries of sqllogictest, fetched at a
 * pinned commit. Each file's tables are made in a plain schema and in one imported into ScalarDB,
 * in each layout, and every query must return through the driver what it returns directly; the
 * files' expected results, SQLite's, are not used. A table without a primary key gets one, {@code
 * slt_rowid}, on both sides, as its last column. Not select5 on MySQL and MariaDB, whose join-order
 * search over its joins of up to 17 read views takes minutes a query (todo 319). Run with {@code
 * -Dnativejdbc.slt=true}, and {@code -Dnativejdbc.slt.queries=<n>} for the queries run of each file
 * (1000; 0 for all); writes {@code build/coverage/<engine>-slt-<layout>.md}.
 */
@EnabledIfSystemProperty(named = "nativejdbc.slt", matches = "true")
@SuppressFBWarnings(
    value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
    justification = "sqllogictest's statements and the test's own DDL and patterns")
class SqlLogicIntegrationTest {

  /** A commit of the GitHub mirror of sqllogictest's Fossil repository. */
  private static final String COMMIT = "c93ac416f05635ff5cc490009add936d9753d01d";

  private static final String SOURCE =
      "https://raw.githubusercontent.com/gregrahn/sqllogictest/" + COMMIT + "/test/";

  /**
   * The files: the hand-written ones, and the first of each generated family that only reads
   * ({@code index/delete} drops and recreates its tables, {@code index/view} reads views, which T4
   * on PostgreSQL covers).
   */
  private static final List<String> FILES =
      List.of(
          "select1.test",
          "select2.test",
          "select3.test",
          "select4.test",
          "select5.test",
          "index/between/10/slt_good_0.test",
          "index/commute/10/slt_good_0.test",
          "index/in/10/slt_good_0.test",
          "index/orderby/10/slt_good_0.test",
          "index/orderby_nosort/10/slt_good_0.test",
          "index/random/10/slt_good_0.test",
          "random/aggregates/slt_good_0.test",
          "random/expr/slt_good_0.test",
          "random/groupby/slt_good_0.test",
          "random/select/slt_good_0.test");

  private static final Pattern CREATE_TABLE =
      Pattern.compile("(?is)^\\s*CREATE\\s+TABLE\\s+(\\w+)\\s*\\((.*)\\)\\s*$");

  private static final Pattern CREATE_INDEX =
      Pattern.compile("(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(\\w+)\\s+ON\\s+(.*)$");

  private static final Pattern INSERT_VALUES =
      Pattern.compile("(?is)^\\s*INSERT\\s+INTO\\s+(\\w+)\\s+VALUES\\b(.*)$");

  /** The files' table names, which a schema qualifies. */
  private static final Pattern TABLE = Pattern.compile("(?<![.\\w])(t\\d+|tab\\d+)(?![.\\w])");

  /** {@code tab0 AS cor0}: Oracle takes a table's alias without AS. */
  private static final Pattern TABLE_AS =
      Pattern.compile("(?i)(?<![.\\w])(t\\d+|tab\\d+)\\s+AS\\s+(?=\\w)");

  static List<Arguments> runs() {
    List<Arguments> out = new ArrayList<>();
    for (Engines e : Engines.selected()) {
      out.add(Arguments.of(e, "d"));
      out.add(Arguments.of(e, "i"));
    }
    return out;
  }

  @ParameterizedTest
  @MethodSource("runs")
  void sqlLogic_returnsThroughTheDriverWhatTheDatabaseReturns(Engines e, String layout)
      throws Exception {
    Engine engine = e.engine();
    int cap = Integer.getInteger("nativejdbc.slt.queries", 1000);
    StringBuilder report =
        new StringBuilder("# sqllogictest (")
            .append(COMMIT, 0, 10)
            .append(") on ")
            .append(engine)
            .append(", layout ")
            .append(layout)
            .append("\n\n| file | passed | refused | failed |\n|---|---|---|---|\n");
    StringBuilder failed = new StringBuilder("\n## Failures\n\n");
    List<String> failures = new ArrayList<>();
    Map<String, List<String>> refusals = new TreeMap<>();
    int passed = 0;
    int refused = 0;
    for (int f = 0; f < FILES.size(); f++) {
      String file = FILES.get(f);
      if (file.equals("select5.test") && (engine == Engine.MYSQL || engine == Engine.MARIADB)) {
        continue;
      }
      List<Item> records = Item.parse(fetch(file), label(engine));
      String plain = "slt" + f + layout + "p";
      String scalarDb = "slt" + f + layout;
      // the leading statements, and an index wherever made: the tables, before the import
      List<String> setup = new ArrayList<>();
      List<Item> queries = new ArrayList<>();
      for (Item r : records) {
        if (!r.query && (queries.isEmpty() || CREATE_INDEX.matcher(r.sql).matches())) {
          setup.add(r.sql);
        } else if (r.query && (cap == 0 || queries.size() < cap)) {
          queries.add(r);
        }
      }
      List<String> tables = new ArrayList<>();
      try (Connection c = e.open()) {
        for (String schema : List.of(plain, scalarDb)) {
          e.execute(c, e.createSchema(schema));
          Map<String, List<String>> columns = new LinkedHashMap<>();
          for (String sql : setup) {
            e.execute(c, ddl(engine, sql, e.storedSchema(schema), columns));
          }
          tables = new ArrayList<>(columns.keySet());
        }
      }
      try (DistributedTransactionAdmin admin = e.scalarDb().getTransactionAdmin()) {
        admin.createCoordinatorTables(true);
        admin.createNamespace(e.storedSchema(scalarDb), true);
        for (String t : tables) {
          admin.importTable(
              e.storedSchema(scalarDb),
              e.storedTable(t),
              layout.equals("d") ? Map.of("transaction-metadata-decoupling", "true") : Map.of(),
              Map.of());
        }
      }
      Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
      try (Connection c = e.open()) {
        String ns = e.storedSchema(scalarDb);
        assertThat(
                Setup.run(
                    catalog,
                    c,
                    t -> t.namespace().equals(ns),
                    Duration.ofMinutes(5),
                    List.of(),
                    true))
            .allMatch(Setup.Result::succeeded, "setup succeeded");
      }
      int filePassed = 0;
      int fileRefused = 0;
      int fileFailed = 0;
      // the database labels an expression by its text, which names the plain schema
      Pattern plainName =
          Pattern.compile("(?i)(" + Pattern.quote(e.storedSchema(scalarDb)) + ")p(?!\\w)");
      try (Connection vendor = e.open();
          Connection driver = e.openDriver()) {
        for (Item r : queries) {
          String sql = engine == Engine.ORACLE ? TABLE_AS.matcher(r.sql).replaceAll("$1 ") : r.sql;
          String through = qualified(sql, e.storedSchema(scalarDb));
          WriteCorpusIntegrationTest.Outcome actual =
              WriteCorpusIntegrationTest.Outcome.run(driver, through);
          WriteCorpusIntegrationTest.Outcome expected =
              WriteCorpusIntegrationTest.Outcome.run(vendor, qualified(sql, e.storedSchema(plain)));
          if (expected.rows != null) {
            expected.rows.columns.replaceAll(c -> plainName.matcher(c).replaceAll("$1"));
          }
          String outcome = WriteCorpusIntegrationTest.compare(expected, actual);
          String shown = r.sql.replace('\n', ' ');
          if (outcome.startsWith("refused")) {
            fileRefused++;
            refusals
                .computeIfAbsent(outcome.replaceAll("\\s+", " "), k -> new ArrayList<>())
                .add(file + ": " + shown);
          } else if (outcome.startsWith("pass")) {
            filePassed++;
          } else {
            fileFailed++;
            failures.add(file + ": " + shown);
            failed
                .append("- ")
                .append(file)
                .append(": `")
                .append(through.replace('\n', ' ').replace("`", "'"))
                .append("`: ")
                .append(outcome.replace('\n', ' '))
                .append('\n');
          }
        }
      }
      passed += filePassed;
      refused += fileRefused;
      report.append(
          String.format(
              Locale.ROOT, "| %s | %d | %d | %d |%n", file, filePassed, fileRefused, fileFailed));
    }
    report.insert(
        report.indexOf("\n\n") + 2,
        String.format(
            Locale.ROOT,
            "%d passed, %d refused, %d failed (the first %s queries of each file).%n%n",
            passed,
            refused,
            failures.size(),
            cap == 0 ? "all" : String.valueOf(cap)));
    report.append(failed).append("\n## Refusals, by reason\n\n");
    refusals.entrySet().stream()
        .sorted((a, b) -> b.getValue().size() - a.getValue().size())
        .forEach(
            r ->
                report
                    .append("- ")
                    .append(r.getValue().size())
                    .append(" × ")
                    .append(r.getKey())
                    .append(", as `")
                    .append(r.getValue().get(0).replace("`", "'"))
                    .append("`\n"));
    Path out =
        Path.of(
            "build", "coverage", engine.name().toLowerCase(Locale.ROOT) + "-slt-" + layout + ".md");
    Files.createDirectories(Objects.requireNonNull(out.getParent()));
    Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    assertThat(failures).as("queries whose results differ; see " + out).isEmpty();
  }

  /** The engine's name in a file's {@code skipif} and {@code onlyif} lines. */
  private static String label(Engine engine) {
    return switch (engine) {
      case POSTGRESQL -> "postgresql";
      case SQLSERVER -> "mssql";
      case ORACLE -> "oracle";
      default -> "mysql"; // MariaDB's too
    };
  }

  /**
   * A setup statement in the engine's types, its tables in the schema. A table without a primary
   * key gets {@code slt_rowid}, which the database numbers, and an INSERT without a column list
   * then names the table's columns.
   */
  static String ddl(Engine engine, String sql, String schema, Map<String, List<String>> columns) {
    Matcher table = CREATE_TABLE.matcher(sql);
    if (table.matches()) {
      List<String> names = new ArrayList<>();
      for (String def : table.group(2).split(",", -1)) {
        String name = def.strip().split("\\s+", -1)[0];
        if (!name.equalsIgnoreCase("PRIMARY") && !name.equalsIgnoreCase("UNIQUE")) {
          names.add(name);
        }
      }
      columns.put(table.group(1), names);
      String body = types(engine, table.group(2));
      if (!body.toUpperCase(Locale.ROOT).contains("PRIMARY KEY")) {
        // last: SELECT * lists it where the table holds it (todo 316)
        body = body + ", slt_rowid " + identity(engine) + " PRIMARY KEY";
      }
      return "CREATE TABLE " + schema + "." + table.group(1) + "(" + body + ")";
    }
    Matcher index = CREATE_INDEX.matcher(sql);
    if (index.matches() && engine == Engine.ORACLE) {
      // an Oracle index belongs to a schema, not to its table
      return "CREATE "
          + Objects.requireNonNullElse(index.group(1), "")
          + "INDEX "
          + schema
          + "."
          + index.group(2)
          + " ON "
          + qualified(index.group(3), schema);
    }
    Matcher insert = INSERT_VALUES.matcher(sql);
    if (insert.matches() && columns.containsKey(insert.group(1))) {
      return "INSERT INTO "
          + schema
          + "."
          + insert.group(1)
          + "("
          + String.join(", ", columns.get(insert.group(1)))
          + ") VALUES"
          + insert.group(2);
    }
    return qualified(sql, schema);
  }

  private static String types(Engine engine, String body) {
    boolean oracle = engine == Engine.ORACLE;
    return body.replaceAll("(?i)\\bINTEGER\\b", oracle ? "NUMBER(10)" : "INT")
        .replaceAll(
            "(?i)\\bFLOAT\\b",
            oracle ? "BINARY_DOUBLE" : engine == Engine.SQLSERVER ? "FLOAT" : "DOUBLE PRECISION")
        .replaceAll("(?i)\\bTEXT\\b", "VARCHAR(255)") // an index on MySQL's TEXT needs a length
        .replaceAll("(?i)\\bVARCHAR\\b", oracle ? "VARCHAR2" : "VARCHAR");
  }

  private static String identity(Engine engine) {
    return switch (engine) {
      case MYSQL, MARIADB -> "INT AUTO_INCREMENT";
      case SQLSERVER -> "INT IDENTITY";
      case ORACLE -> "NUMBER(10) GENERATED BY DEFAULT AS IDENTITY";
      default -> "INT GENERATED BY DEFAULT AS IDENTITY";
    };
  }

  /** A statement with the files' table names, outside quotes, qualified with the schema. */
  static String qualified(String sql, String schema) {
    StringBuilder out = new StringBuilder();
    String[] parts = sql.split("'", -1);
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        out.append('\'');
      }
      out.append(i % 2 == 0 ? TABLE.matcher(parts[i]).replaceAll(schema + ".$1") : parts[i]);
    }
    return out.toString();
  }

  /** A file of the suite, fetched once into {@code build/slt/<commit>/}. */
  private static String fetch(String path) throws Exception {
    return RegressIntegrationTest.download(SOURCE + path, Path.of("build", "slt", COMMIT, path));
  }

  /** An item of a file: a statement, or a query and how it sorts. */
  static final class Item {
    final boolean query;
    final String sql;

    private Item(boolean query, String sql) {
      this.query = query;
      this.sql = sql;
    }

    /**
     * The statements and queries of a file that run on the engine: those its {@code skipif} lines
     * do not skip and its {@code onlyif} lines do not keep for another, up to a {@code halt}.
     */
    static List<Item> parse(String text, String engine) {
      List<Item> out = new ArrayList<>();
      for (String block : text.replace("\r\n", "\n").split("\\n\\s*\\n", -1)) {
        List<String> lines = new ArrayList<>();
        for (String line : block.split("\n", -1)) {
          if (!line.startsWith("#") && !line.isBlank()) {
            lines.add(line);
          }
        }
        boolean runs = true;
        while (!lines.isEmpty() && lines.get(0).matches("(skipif|onlyif)\\s+\\S+.*")) {
          String[] words = lines.remove(0).split("\\s+", -1);
          runs &= words[0].equals("skipif") != words[1].equals(engine);
        }
        if (lines.isEmpty()) {
          continue;
        }
        String head = lines.get(0);
        if (head.equals("halt")) {
          if (runs) {
            break;
          }
          continue;
        }
        boolean query = head.startsWith("query");
        if (!runs || !(query || head.startsWith("statement"))) {
          continue; // hash-threshold, and what the engine does not run
        }
        int end = lines.indexOf("----");
        out.add(
            new Item(
                query, String.join("\n", lines.subList(1, end < 0 ? lines.size() : end)).strip()));
      }
      return out;
    }
  }
}
