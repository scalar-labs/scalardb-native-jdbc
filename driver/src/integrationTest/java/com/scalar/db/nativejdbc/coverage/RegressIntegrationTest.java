package com.scalar.db.nativejdbc.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Milestone T4 of the testing plan, on PostgreSQL: the query files of PostgreSQL's regression
 * tests, fetched at a pinned tag, run statement by statement on two sessions, one direct with
 * {@code search_path} on a plain schema and one through the driver with it on the same tables
 * imported into ScalarDB, in each layout. Every statement's result, update count or error class
 * must be the same on both sides, and so must the imported tables after each file. Run with {@code
 * -Dnativejdbc.regress=true}; writes {@code build/coverage/postgresql-regress-<layout>.md}. In
 * flight, a second run holds a share of the imported tables' keys in prepared ScalarDB
 * transactions, which leave the committed image, and so the plain tables, as they are (T2); a write
 * or a locking read that reaches a held row aborts at once, and is then not run on the plain side,
 * nor is the rest of its transaction.
 */
@EnabledIfSystemProperty(named = "nativejdbc.regress", matches = "true")
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "REDOS",
      "PREDICTABLE_RANDOM"
    },
    justification =
        "PostgreSQL's own test statements and the test's own DDL and patterns; a seeded generator")
class RegressIntegrationTest {

  /** The tag of the suite: the major version the tests run, its latest release. */
  private static final String TAG = "REL_16_15";

  private static final String SOURCE =
      "https://raw.githubusercontent.com/postgres/postgres/" + TAG + "/src/test/regress/";

  /** The query-oriented files, in the order of the suite's schedule. */
  private static final List<String> FILES =
      List.of(
          "insert",
          "insert_conflict",
          "select",
          "select_distinct",
          "select_distinct_on",
          "select_implicit",
          "select_having",
          "subselect",
          "union",
          "case",
          "join",
          "aggregates",
          "transactions",
          "update",
          "delete",
          "groupingsets",
          "join_hash",
          "merge",
          "window",
          "limit",
          "with");

  /** The tables setup.sql fills from the suite's data files, by data file. */
  static final Map<String, String> DATA =
      Map.of(
          "onek.data", "onek",
          "tenk.data", "tenk1",
          "person.data", "person",
          "emp.data", "emp",
          "student.data", "student",
          "stud_emp.data", "stud_emp",
          "streets.data", "road");

  /** The tables imported into ScalarDB: those with a primary key. */
  private static final List<String> IMPORTED =
      List.of("onek", "onek2", "tenk1", "tenk2", "int2_tbl", "int4_tbl", "int8_tbl", "text_tbl");

  /** A transaction's start, which JDBC makes with setAutoCommit, and its isolation level. */
  private static final Pattern BEGIN =
      Pattern.compile(
          "(?is)^(?:BEGIN|START\\s+TRANSACTION)(?:\\s+(?:WORK|TRANSACTION))?"
              + "(?:.*\\bISOLATION\\s+LEVEL\\s+(READ\\s+COMMITTED|REPEATABLE\\s+READ|SERIALIZABLE"
              + "|READ\\s+UNCOMMITTED))?.*");

  /** A transaction's end, which JDBC makes with commit or rollback. */
  private static final Pattern END =
      Pattern.compile("(?is)^(COMMIT|END|ROLLBACK|ABORT)(?:\\s+(?:WORK|TRANSACTION))?\\s*");

  private static final Pattern EXPLAIN = Pattern.compile("(?is)^\\s*EXPLAIN\\b.*");

  /** A role is the cluster's: made once, through the driver. */
  private static final Pattern ROLE =
      Pattern.compile("(?is)^\\s*(CREATE|DROP|ALTER)\\s+(ROLE|USER|GROUP)\\b.*");

  /** A transaction mode in SQL, which the driver refuses: the transaction then differs. */
  private static final Pattern MODE =
      Pattern.compile(
          "(?is)^\\s*(SET|RESET)\\s+(SESSION\\s+CHARACTERISTICS|TRANSACTION|transaction_\\w+)\\b.*");

  /** The table a write writes, by name. */
  private static final Pattern WRITES =
      Pattern.compile(
          "(?is)^\\s*(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|MERGE\\s+INTO)\\s+(?:ONLY\\s+)?"
              + "(?:\\w+\\.)?(\\w+)\\b.*");

  /** What a statement creates, by name. */
  private static final Pattern CREATES =
      Pattern.compile(
          "(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMP(?:ORARY)?\\s+)?(?:RECURSIVE\\s+)?"
              + "(?:TABLE|VIEW|FUNCTION|AGGREGATE|RULE|TRIGGER|TYPE|PROCEDURE|MATERIALIZED\\s+VIEW)"
              + "\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:\\w+\\.)?(\\w+).*"
              + "|(?is)^\\s*DECLARE\\s+(\\w+)\\b.*");

  /**
   * A window function, whose value may depend on the order of rows its window does not order:
   * reading the committed image of rows in flight takes another plan, and so another order.
   */
  private static final Pattern WINDOW = Pattern.compile("(?i)\\bOVER\\s*[(\\w]");

  /** What may abort for a row in flight: a write, or a read that locks rows. */
  private static final Pattern ABORTABLE =
      Pattern.compile(
          "(?is)^\\s*(?:INSERT|UPDATE|DELETE|MERGE)\\b.*"
              + "|^\\s*WITH\\b.*\\b(?:INSERT|UPDATE|DELETE|MERGE)\\b.*"
              + "|.*\\bFOR\\s+(?:NO\\s+KEY\\s+)?(?:UPDATE|SHARE|KEY\\s+SHARE)\\b.*");

  /** A row limit: rows tied under it are any of several, so only their number is compared. */
  private static final Pattern LIMIT = Pattern.compile("(?i)\\b(LIMIT|FETCH|OFFSET)\\b");

  @ParameterizedTest
  @CsvSource({"d, false", "i, false", "d, true", "i, true"})
  void regress_returnsThroughTheDriverWhatTheDatabaseReturns(String layout, boolean inFlight)
      throws Exception {
    Engines e = Engines.POSTGRESQL;
    Assumptions.assumeTrue(Engines.selected().contains(e), "PostgreSQL's suite runs on PostgreSQL");
    String scalarDb = "r" + layout + (inFlight ? "f" : "");
    String plain = scalarDb + "p";
    String setup = CorpusIntegrationTest.resource("regress/postgresql/setup.sql");
    int load = setup.indexOf("\n-- load\n");
    try (Connection c = e.open()) {
      for (String schema : List.of(plain, scalarDb)) {
        e.execute(c, e.createSchema(schema));
        e.execute(
            c,
            PsqlScript.statements(setup.substring(0, load).replace("{S}", schema))
                .toArray(String[]::new));
        for (Map.Entry<String, String> d : DATA.entrySet()) {
          load(c, schema + "." + d.getValue(), fetch("data/" + d.getKey()));
        }
        e.execute(
            c,
            PsqlScript.statements(setup.substring(load).replace("{S}", schema))
                .toArray(String[]::new));
      }
    }
    try (DistributedTransactionAdmin admin = e.scalarDb().getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(scalarDb, true);
      for (String t : IMPORTED) {
        admin.importTable(
            scalarDb,
            t,
            layout.equals("d") ? Map.of("transaction-metadata-decoupling", "true") : Map.of(),
            Map.of());
      }
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals(scalarDb),
                  Duration.ofMinutes(5),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "setup succeeded");
    }

    StringBuilder report =
        new StringBuilder("# PostgreSQL's regression tests (")
            .append(TAG)
            .append("), layout ")
            .append(layout)
            .append(inFlight ? ", rows in flight" : "")
            .append("\n\n| file | passed | aborted | refused | failed |\n|---|---|---|---|---|\n");
    StringBuilder failed = new StringBuilder("\n## Failures\n\n");
    List<String> failures = new ArrayList<>();
    Map<String, List<String>> refusals = new java.util.TreeMap<>();
    // -Dnativejdbc.regress.trace=<file> lists every statement of that file with its outcome
    String traced = System.getProperty("nativejdbc.regress.trace", "");
    StringBuilder trace = new StringBuilder("\n## Trace of " + traced + "\n\n");
    int passed = 0;
    int aborted = 0;
    int refused = 0;
    List<TwoPhaseCommitTransaction> held = new ArrayList<>();
    TwoPhaseCommitTransactionManager tpc = e.scalarDb().getTwoPhaseCommitTransactionManager();
    Properties noWait = new Properties();
    if (inFlight) {
      long seed = Long.getLong("nativejdbc.seed", System.nanoTime());
      System.out.printf(Locale.ROOT, "RegressIntegrationTest: -Dnativejdbc.seed=%d%n", seed);
      InFlight.prepare(
          e,
          catalog,
          tpc,
          scalarDb,
          layout.equals("d"),
          IMPORTED,
          t -> !t.equals("int2_tbl"), // no room past 32767
          0.1,
          new Random(seed),
          held,
          new HashMap<>());
      noWait.setProperty(
          "scalar.db.native_jdbc.inflight_wait_millis", "0"); // a row in flight aborts at once
      try (Connection c = e.open()) {
        for (String t : IMPORTED) {
          String states =
              layout.equals("d")
                  ? Objects.requireNonNull(catalog.lookup(List.of(), scalarDb, t).metadataTable())
                  : t;
          try (Statement st = c.createStatement();
              ResultSet rs =
                  st.executeQuery(
                      "SELECT COUNT(*) FROM "
                          + scalarDb
                          + "."
                          + states
                          + " WHERE tx_state IN (1, 2)")) {
            rs.next();
            int n = rs.getInt(1);
            assertThat(n).as("rows in flight in " + t).isPositive();
            report.insert(
                report.indexOf("\n\n") + 2,
                String.format(Locale.ROOT, "In flight in %s: %d rows.%n%n", t, n));
          }
        }
      }
    }
    try {
      for (String file : FILES) {
        List<String> statements = PsqlScript.statements(fetch("sql/" + file + ".sql"));
        int filePassed = 0;
        int fileAborted = 0;
        int fileRefused = 0;
        int fileFailed = 0;
        try (Connection vendor = e.open();
            Connection driver = e.openDriver(noWait)) {
          // the driver's session itself, for what the driver refuses and the database runs
          Connection under =
              (Connection) driver.unwrap(Class.forName("org.postgresql.PGConnection"));
          for (Connection c : List.of(vendor, driver)) {
            WriteCorpusIntegrationTest.Outcome.run(c, "SET statement_timeout = '60s'");
          }
          WriteCorpusIntegrationTest.Outcome.run(
              vendor, "SET search_path TO " + plain + ", public");
          WriteCorpusIntegrationTest.Outcome.run(
              driver, "SET search_path TO " + scalarDb + ", public");
          // what a refusal left out: objects it would have created; a transaction's mode, or a
          // refused statement that failed in the driver's session, which aborted its transaction
          List<String> notCreated = new ArrayList<>();
          List<String> notWritten = new ArrayList<>();
          boolean tainted = false;
          // a statement aborted for rows in flight in a transaction: the rest of it is not run
          boolean abortedTx = false;
          List<String> all = new ArrayList<>(statements);
          all.add(null); // then the imported tables
          for (String sql : all) {
            String outcome;
            String shown;
            if (sql == null) {
              shown = "(the imported tables)";
              outcome = sameTables(vendor, driver, plain, scalarDb);
            } else if (abortedTx) {
              shown = sql;
              outcome = "aborted before, in this transaction";
              if (END.matcher(sql).matches()) {
                for (Connection c : List.of(vendor, driver)) {
                  c.rollback();
                  c.setAutoCommit(true);
                  c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                }
                abortedTx = false;
              }
            } else {
              shown = sql;
              WriteCorpusIntegrationTest.Outcome actual = run(driver, sql);
              if (inFlight
                  && "40001".equals(actual.state)
                  && ABORTABLE.matcher(sql).matches()
                  && namesImported(sql)) {
                outcome = "aborted for rows in flight";
                abortedTx = !driver.getAutoCommit();
              } else {
                WriteCorpusIntegrationTest.Outcome expected =
                    ROLE.matcher(sql).matches() ? actual : run(vendor, sql);
                outcome = compare(sql, expected, actual, inFlight);
                boolean ended = END.matcher(sql).matches();
                if (!outcome.startsWith("pass") && !outcome.startsWith("refused")) {
                  String error = String.valueOf(actual.error) + " " + expected.error;
                  String missing =
                      notCreated.stream()
                          .filter(n -> error.contains("\"" + n + "\"") || error.contains(n + "("))
                          .findFirst()
                          .orElse(null);
                  String unwritten =
                      notWritten.stream()
                          .filter(
                              t ->
                                  Pattern.compile("(?i)\\b" + Pattern.quote(t) + "\\b")
                                      .matcher(sql)
                                      .find())
                          .findFirst()
                          .orElse(null);
                  if (missing != null) {
                    outcome = "refused before: " + missing + " was not created; " + outcome;
                  } else if (unwritten != null) {
                    outcome = "refused before: a write of " + unwritten + "; " + outcome;
                  } else if (tainted) {
                    outcome = "refused before, in this transaction; " + outcome;
                  }
                }
                tainted =
                    !ended
                        && (tainted
                            || (outcome.startsWith("refused") && MODE.matcher(sql).matches()));
                Matcher writes = WRITES.matcher(sql);
                if (outcome.startsWith("refused") && writes.matches() && namesImported(sql)) {
                  notWritten.add(writes.group(1).toLowerCase(Locale.ROOT));
                }
                Matcher creates = CREATES.matcher(sql);
                if (outcome.startsWith("refused") && creates.matches()) {
                  notCreated.add(
                      Objects.requireNonNullElse(creates.group(1), creates.group(2))
                          .toLowerCase(Locale.ROOT));
                }
                if (outcome.startsWith("refused")
                    && !namesImported(sql)
                    && !MODE.matcher(sql).matches()) {
                  // what it creates or sets, later statements use: run in the driver's session
                  // directly, as an application would with the passthrough setting
                  boolean underFailed =
                      WriteCorpusIntegrationTest.Outcome.run(under, sql).state != null;
                  tainted |= underFailed && !ended && !under.getAutoCommit();
                }
              }
            }
            if (file.equals(traced)) {
              trace
                  .append("- `")
                  .append(shown.replace('\n', ' '))
                  .append("`: ")
                  .append(outcome)
                  .append('\n');
            }
            if (outcome.startsWith("aborted")) {
              fileAborted++;
            } else if (outcome.startsWith("refused")) {
              fileRefused++;
              refusals
                  .computeIfAbsent(outcome.replaceAll("\\s+", " "), k -> new ArrayList<>())
                  .add(file + ": " + shown.replace('\n', ' '));
            } else if (outcome.startsWith("pass")) {
              filePassed++;
            } else {
              fileFailed++;
              failures.add(file + ": " + shown);
              failed
                  .append("- ")
                  .append(file)
                  .append(": `")
                  .append(shown.replace('\n', ' ').replace("`", "'"))
                  .append("`: ")
                  .append(outcome.replace('\n', ' '))
                  .append('\n');
            }
          }
        }
        passed += filePassed;
        aborted += fileAborted;
        refused += fileRefused;
        report.append(
            String.format(
                Locale.ROOT,
                "| %s | %d | %d | %d | %d |%n",
                file,
                filePassed,
                fileAborted,
                fileRefused,
                fileFailed));
      }
    } finally {
      for (TwoPhaseCommitTransaction tx : held) {
        tx.rollback();
      }
      tpc.close();
    }
    report.insert(
        report.indexOf("\n\n") + 2,
        String.format(
            Locale.ROOT,
            "%d passed, %d aborted for rows in flight, %d refused, %d failed.%n%n",
            passed,
            aborted,
            refused,
            failures.size()));
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
    if (!traced.isEmpty()) {
      report.append(trace);
    }
    Path out =
        Path.of(
            "build",
            "coverage",
            "postgresql-regress-" + layout + (inFlight ? "-inflight" : "") + ".md");
    Files.createDirectories(Objects.requireNonNull(out.getParent()));
    Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    assertThat(failures).as("statements whose results differ; see " + out).isEmpty();
  }

  /**
   * A statement on one side, a transaction's start and end through JDBC as an application makes
   * them: the driver takes them so, not in SQL.
   */
  private static WriteCorpusIntegrationTest.Outcome run(Connection c, String sql) {
    Matcher begin = BEGIN.matcher(sql);
    Matcher end = END.matcher(sql);
    if (!begin.matches() && !end.matches()) {
      return WriteCorpusIntegrationTest.Outcome.run(c, sql);
    }
    WriteCorpusIntegrationTest.Outcome o = new WriteCorpusIntegrationTest.Outcome();
    try {
      if (begin.matches()) {
        c.setAutoCommit(false);
        String level = begin.group(1);
        if (level != null) {
          String l = level.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
          c.setTransactionIsolation(
              l.equals("SERIALIZABLE")
                  ? Connection.TRANSACTION_SERIALIZABLE
                  : l.equals("REPEATABLE READ")
                      ? Connection.TRANSACTION_REPEATABLE_READ
                      : l.equals("READ UNCOMMITTED")
                          ? Connection.TRANSACTION_READ_UNCOMMITTED
                          : Connection.TRANSACTION_READ_COMMITTED);
        }
      } else if (!c.getAutoCommit()) {
        String verb = end.group(1).toUpperCase(Locale.ROOT);
        if (verb.equals("COMMIT") || verb.equals("END")) {
          c.commit();
        } else {
          c.rollback();
        }
        c.setAutoCommit(true);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      }
      o.count = 0;
    } catch (SQLException ex) {
      o.state = Objects.requireNonNullElse(ex.getSQLState(), "");
      o.error = ex.getSQLState() + " " + ex.getMessage();
    }
    return o;
  }

  /** Whether a statement names an imported table, which only the driver may write. */
  private static boolean namesImported(String sql) {
    String lower = sql.toLowerCase(Locale.ROOT);
    return IMPORTED.stream()
        .anyMatch(t -> Pattern.compile("\\b" + t + "\\b").matcher(lower).find());
  }

  /**
   * As T1 and T3 compare; a statement with a row limit, or in flight one with a window function, by
   * its number of rows only, and EXPLAIN by whether it runs, its plan naming the other schema.
   */
  private static String compare(
      String sql,
      WriteCorpusIntegrationTest.Outcome expected,
      WriteCorpusIntegrationTest.Outcome actual,
      boolean inFlight) {
    if (EXPLAIN.matcher(sql).matches()
        && expected.rows != null
        && actual.rows != null
        && expected.rows.error == null
        && actual.rows.error == null) {
      return "pass, plan not compared";
    }
    if ((LIMIT.matcher(sql).find() || (inFlight && WINDOW.matcher(sql).find()))
        && expected.rows != null
        && actual.rows != null
        && expected.rows.error == null
        && actual.rows.error == null) {
      int want = expected.rows.rows.size();
      int got = actual.rows.rows.size();
      return want == got ? "pass, rows counted" : got + " rows, expected " + want;
    }
    return WriteCorpusIntegrationTest.compare(expected, actual);
  }

  /** The imported tables, the plain schema's read directly and the ScalarDB schema's by driver. */
  private static String sameTables(
      Connection vendor, Connection driver, String plain, String scalarDb) {
    for (String t : IMPORTED) {
      CorpusIntegrationTest.Side want =
          CorpusIntegrationTest.Side.run(vendor, "SELECT * FROM " + plain + "." + t);
      CorpusIntegrationTest.Side got =
          CorpusIntegrationTest.Side.run(driver, "SELECT * FROM " + scalarDb + "." + t);
      String outcome = CorpusIntegrationTest.compare(want, got, false);
      if (!outcome.startsWith("pass")) {
        return t + " differs: " + outcome;
      }
    }
    return "pass";
  }

  /** A file of the suite, fetched once into {@code build/regress/<tag>/}. */
  private static String fetch(String path) throws IOException, InterruptedException {
    return download(SOURCE + path, Path.of("build", "regress", TAG, path));
  }

  /** A file fetched once into {@code local}. */
  static String download(String url, Path local) throws IOException, InterruptedException {
    if (!Files.exists(local)) {
      HttpResponse<String> r =
          HttpClient.newBuilder()
              .followRedirects(HttpClient.Redirect.NORMAL)
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).build(),
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (r.statusCode() != 200) {
        throw new IOException(url + ": HTTP " + r.statusCode());
      }
      Files.createDirectories(Objects.requireNonNull(local.getParent()));
      Files.writeString(local, r.body(), StandardCharsets.UTF_8);
    }
    return Files.readString(local, StandardCharsets.UTF_8);
  }

  /** A data file in COPY's text format into a table, its values typed by the table. */
  private static void load(Connection c, String table, String data) throws SQLException {
    List<String[]> rows = new ArrayList<>();
    for (String line : data.split("\n", -1)) {
      if (!line.isEmpty()) {
        rows.add(line.split("\t", -1));
      }
    }
    if (rows.isEmpty()) {
      return;
    }
    int n = rows.get(0).length;
    String sql =
        "INSERT INTO " + table + " VALUES (" + String.join(", ", "?".repeat(n).split("")) + ")";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (String[] row : rows) {
        for (int i = 0; i < n; i++) {
          String v = row[i];
          ps.setObject(i + 1, v.equals("\\N") ? null : unescaped(v), Types.OTHER);
        }
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /** A value of COPY's text format without its backslash escapes. */
  private static String unescaped(String v) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < v.length(); i++) {
      char ch = v.charAt(i);
      if (ch == '\\' && i + 1 < v.length()) {
        char next = v.charAt(++i);
        out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
      } else {
        out.append(ch);
      }
    }
    return out.toString();
  }
}
