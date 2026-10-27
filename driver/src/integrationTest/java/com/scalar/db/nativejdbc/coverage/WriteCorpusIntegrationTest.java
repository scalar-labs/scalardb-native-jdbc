package com.scalar.db.nativejdbc.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Upsert;
import com.scalar.db.io.Column;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone T3 of the testing plan: every statement of the write corpus runs through the driver on
 * the corpus's tables imported into ScalarDB, in each layout, and directly on a plain twin of each.
 * The update counts or returned rows, and then the three tables, must be the same on both sides
 * after every statement. At the end ScalarDB reads every row the driver left and must see the
 * twin's data, and a ScalarDB transaction writes every row: the metadata the driver wrote is
 * ScalarDB's. Run again with ScalarDB transactions holding a share of the keys prepared: a
 * statement that reaches a key in flight, by its {@code VALUES} or by its {@code WHERE} on the
 * committed rows, must abort with 40001, and any other must do what it does on the twin. A
 * statement marked {@code -- refused} must be refused, and no other. Writes {@code
 * build/coverage/<engine>-writes[-inflight].md}.
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
class WriteCorpusIntegrationTest {

  private static final List<String> IMPORTED = List.of("c", "o", "k");

  /** The key columns of the corpus's tables. */
  private static final Map<String, List<String>> KEYS =
      Map.of("c", List.of("id"), "o", List.of("id"), "k", List.of("a", "b"));

  private static final String REFUSED = "0AN01";

  /** Every engine, with nothing in flight (schemas w*) and with rows in flight (schemas x*). */
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
  void writeCorpus_leavesThroughTheDriverWhatTheDatabaseLeaves(Engines e, boolean inFlight)
      throws Exception {
    Engine engine = e.engine();
    String set = inFlight ? "x" : "w";
    // each layout's schema and its plain twin's
    Map<String, String> twins = Map.of(set + "d", set + "pd", set + "i", set + "pi");
    String ddl =
        CorpusIntegrationTest.types(engine, CorpusIntegrationTest.resource("corpus/schema.sql"));
    try (Connection c = e.open()) {
      for (String schema : List.of(set + "d", set + "pd", set + "i", set + "pi")) {
        e.execute(c, e.createSchema(schema));
        e.execute(
            c, CorpusIntegrationTest.statements(ddl.replace("{S}", schema)).toArray(String[]::new));
      }
    }
    try (DistributedTransactionAdmin admin = e.scalarDb().getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      for (String layout : twins.keySet()) {
        String ns = e.storedSchema(layout);
        admin.createNamespace(ns, true);
        for (String t : IMPORTED) {
          admin.importTable(
              ns,
              e.storedTable(t),
              layout.endsWith("d") ? Map.of("transaction-metadata-decoupling", "true") : Map.of(),
              Map.of());
        }
      }
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      List<String> scalarDb = List.of(e.storedSchema(set + "d"), e.storedSchema(set + "i"));
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> scalarDb.contains(t.namespace()),
                  Duration.ofMinutes(2),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "setup succeeded");
    }

    StringBuilder report =
        new StringBuilder("# Write corpus on ")
            .append(engine)
            .append(inFlight ? ", rows in flight" : ", nothing in flight")
            .append("\n\n| file | statement | layout | outcome |\n|---|---|---|---|\n");
    List<String> failures = new ArrayList<>();
    int passed = 0;
    int refused = 0;
    int aborted = 0;
    long seed = Long.getLong("nativejdbc.seed", System.nanoTime());
    System.out.printf(
        Locale.ROOT, "WriteCorpusIntegrationTest %s: -Dnativejdbc.seed=%d%n", engine, seed);
    Properties noWait = new Properties();
    noWait.setProperty(
        "scalar.db.native_jdbc.inflight_wait_millis", "0"); // a row in flight aborts at once
    List<TwoPhaseCommitTransaction> held = new ArrayList<>();
    Map<String, Set<List<String>>> heldKeys = new HashMap<>();
    Map<String, Integer> abortsByLayout = new HashMap<>();
    try (Connection driver = e.openDriver(noWait);
        TwoPhaseCommitTransactionManager tpc = e.scalarDb().getTwoPhaseCommitTransactionManager()) {
      if (inFlight) {
        InFlight.prepare(e, catalog, tpc, set, 0.3, new Random(seed), held, heldKeys);
        for (String layout : List.of(set + "d", set + "i")) {
          for (String t : IMPORTED) {
            assertThat(heldKeys.getOrDefault(layout + "." + t, Set.of()))
                .as("keys in flight in " + layout + "." + t)
                .isNotEmpty();
          }
        }
      }
      try {
        for (String layout : List.of(set + "d", set + "i")) {
          String twin = Objects.requireNonNull(twins.get(layout));
          try (Connection vendor = e.open()) {
            vendor.setAutoCommit(false);
            for (Corpus.Entry st : Corpus.load("writes", engine)) {
              String sql = CorpusIntegrationTest.types(engine, st.sql());
              String through = CorpusIntegrationTest.tables(sql, layout);
              String onTwin = CorpusIntegrationTest.tables(sql, twin);
              // the held keys of the committed rows the statement reaches, which must abort it
              Set<String> reached = new TreeSet<>();
              Map<String, Set<List<String>>> reaches = Map.of();
              Map<String, Map<List<String>, List<String>>> before = Map.of();
              if (inFlight && !st.refused()) {
                reaches = reaches(vendor, onTwin, twin);
                before = rows(vendor, twin);
              }
              Outcome actual = Outcome.run(driver, through);
              Outcome expected = Outcome.run(vendor, onTwin);
              if (inFlight && !st.refused()) {
                Map<String, Map<List<String>, List<String>>> after =
                    expected.state == null ? rows(vendor, twin) : before;
                for (String t : IMPORTED) {
                  Set<List<String>> mine = heldKeys.getOrDefault(layout + "." + t, Set.of());
                  Set<List<String>> keys = new HashSet<>(reaches.getOrDefault(t, Set.of()));
                  keys.addAll(changed(before.get(t), after.get(t)));
                  for (List<String> k : keys) {
                    if (mine.contains(k)) {
                      reached.add(t + k);
                    }
                  }
                }
              }
              boolean abort = actual.state != null && actual.state.startsWith("40");
              if (expected.state != null || REFUSED.equals(actual.state) || abort) {
                vendor.rollback();
              } else {
                vendor.commit();
              }
              String outcome;
              if (st.refused() || REFUSED.equals(actual.state)) {
                outcome = compare(expected, actual);
              } else if (inFlight && reaches.isEmpty()) {
                outcome = "the test cannot tell the rows it reaches: add its form to reaches()";
              } else if (!reached.isEmpty()) {
                outcome =
                    "40001".equals(actual.state)
                        ? "pass, aborted"
                        : "reaches rows in flight "
                            + reached
                            + (actual.state == null
                                ? " and runs"
                                : ", failing with " + actual.error);
              } else if (abort && inFlight) {
                outcome = "aborted, reaching no row in flight: " + actual.error;
              } else {
                outcome = compare(expected, actual);
              }
              outcome = CorpusIntegrationTest.expectRefusal(outcome, st);
              // after an abort or a refusal too: neither may leave a write behind
              if (outcome.startsWith("pass") || outcome.startsWith("refused")) {
                String tables = sameTables(vendor, driver, twin, layout);
                if (!tables.startsWith("pass") || outcome.equals("pass")) {
                  outcome = tables;
                }
              }
              if (outcome.startsWith("pass, aborted")) {
                abortsByLayout.merge(layout, 1, Integer::sum);
              }
              if (outcome.startsWith("refused")) {
                refused++;
              } else if (outcome.startsWith("pass, aborted")) {
                aborted++;
              } else if (outcome.startsWith("pass")) {
                passed++;
              } else {
                failures.add(st.file() + ": " + through + ": " + outcome);
              }
              report
                  .append("| ")
                  .append(st.file())
                  .append(" | `")
                  .append(through.replace("|", "\\|"))
                  .append("` | ")
                  .append(layout)
                  .append(" | ")
                  .append(outcome.replace("|", "\\|").replace('\n', ' '))
                  .append(" |\n");
            }
          }
        }
      } finally {
        for (TwoPhaseCommitTransaction tx : held) {
          tx.rollback();
        }
      }
      if (inFlight) {
        for (String layout : List.of(set + "d", set + "i")) {
          if (abortsByLayout.getOrDefault(layout, 0) == 0) {
            failures.add(layout + ": no statement reached a row in flight");
          }
        }
      }
      // with the transactions rolled back, the tables are the twins' and ScalarDB's own
      try (Connection vendor = e.open()) {
        vendor.setAutoCommit(false);
        for (String layout : List.of(set + "d", set + "i")) {
          String twin = Objects.requireNonNull(twins.get(layout));
          String tables = sameTables(vendor, driver, twin, layout);
          String scalarDb =
              tables.startsWith("pass") ? scalarDbAgrees(e, catalog, vendor, twin, layout) : tables;
          if (!scalarDb.equals("pass")) {
            failures.add(layout + ", at the end: " + scalarDb);
          }
          report.append("\nAt the end, read through ScalarDB, ").append(layout).append(": ");
          report.append(scalarDb).append("\n");
        }
      }
    }
    report.insert(
        report.indexOf("\n\n") + 2,
        String.format(
            Locale.ROOT,
            "%d passed, %d aborted for rows in flight, %d refused, %d failed (each statement in"
                + " both layouts). Keys in flight: %s.%n%n",
            passed,
            aborted,
            refused,
            failures.size(),
            heldKeys));
    Path out =
        Path.of(
            "build",
            "coverage",
            engine.name().toLowerCase(Locale.ROOT)
                + (inFlight ? "-writes-inflight" : "-writes")
                + ".md");
    Files.createDirectories(Objects.requireNonNull(out.getParent()));
    Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    assertThat(failures).as("statements whose effects differ; see " + out).isEmpty();
  }

  /** The rows of the twin's tables by table and key, as {@link #normal} spells them. */
  private static Map<String, Map<List<String>, List<String>>> rows(Connection vendor, String twin)
      throws SQLException {
    Map<String, Map<List<String>, List<String>>> out = new HashMap<>();
    for (String t : IMPORTED) {
      List<String> keys = Objects.requireNonNull(KEYS.get(t));
      Map<List<String>, List<String>> byKey = new HashMap<>();
      try (Statement st = vendor.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT " + String.join(", ", keys) + ", t.* FROM " + twin + "." + t + " t")) {
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
          List<String> key = new ArrayList<>();
          List<String> row = new ArrayList<>();
          for (int i = 1; i <= n; i++) {
            (i <= keys.size() ? key : row).add(normal(rs.getObject(i)));
          }
          byKey.put(key, row);
        }
      }
      out.put(t, byKey);
    }
    return out;
  }

  /** The keys whose rows differ, appear or disappear. */
  private static Set<List<String>> changed(
      @Nullable Map<List<String>, List<String>> before,
      @Nullable Map<List<String>, List<String>> after) {
    Map<List<String>, List<String>> b = Objects.requireNonNullElse(before, Map.of());
    Map<List<String>, List<String>> a = Objects.requireNonNullElse(after, Map.of());
    Set<List<String>> out = new HashSet<>();
    for (Map.Entry<List<String>, List<String>> r : b.entrySet()) {
      if (!Objects.equals(r.getValue(), a.get(r.getKey()))) {
        out.add(r.getKey());
      }
    }
    for (List<String> k : a.keySet()) {
      if (!b.containsKey(k)) {
        out.add(k);
      }
    }
    return out;
  }

  /**
   * Per table, the keys of the twin's rows the statement reaches: those its {@code VALUES} name, or
   * those its {@code WHERE} matches, read before it runs. Empty for a form the test does not take
   * apart; the corpus's writes in scope are single-table {@code INSERT ... VALUES}, {@code UPDATE}
   * and {@code DELETE}.
   */
  static Map<String, Set<List<String>>> reaches(Connection vendor, String sql, String twin)
      throws SQLException {
    Matcher m = INSERT.matcher(sql);
    if (m.matches()) {
      String t = table(m.group(1), twin);
      if (t == null) {
        return Map.of();
      }
      List<String> columns = new ArrayList<>();
      for (String c : split(m.group(2))) {
        columns.add(c.strip().toLowerCase(Locale.ROOT));
      }
      Set<List<String>> keys = new HashSet<>();
      String rest = m.group(3).strip();
      while (rest.startsWith("(")) {
        int end = topLevel(rest, ")", 1);
        List<String> values = split(rest.substring(1, end));
        List<String> key = new ArrayList<>();
        for (String k : Objects.requireNonNull(KEYS.get(t))) {
          int i = columns.indexOf(k);
          String v = i < 0 || i >= values.size() ? "" : values.get(i).strip();
          if (!v.matches("-?\\d+")) {
            return Map.of();
          }
          key.add(normal(new BigDecimal(v)));
        }
        keys.add(key);
        rest = rest.substring(end + 1).strip();
        rest = rest.startsWith(",") ? rest.substring(1).strip() : rest;
      }
      return Map.of(t, keys);
    }
    String target;
    String where;
    Matcher update = UPDATE.matcher(sql);
    Matcher delete = DELETE.matcher(sql);
    if (update.matches()) {
      target = update.group(1);
      int w = topLevel(sql, " WHERE ", 0);
      where = w < 0 ? "" : sql.substring(w);
    } else if (delete.matches()) {
      target = delete.group(1);
      where = Objects.requireNonNullElse(delete.group(2), "");
    } else {
      return Map.of();
    }
    String t = table(target, twin);
    if (t == null || TRAILER.matcher(where).find()) {
      return Map.of();
    }
    List<String> keyColumns = Objects.requireNonNull(KEYS.get(t));
    Set<List<String>> keys = new HashSet<>();
    try (Statement st = vendor.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT " + String.join(", ", keyColumns) + " FROM " + target + where)) {
      while (rs.next()) {
        List<String> key = new ArrayList<>();
        for (int i = 1; i <= keyColumns.size(); i++) {
          key.add(normal(rs.getObject(i)));
        }
        keys.add(key);
      }
    }
    return Map.of(t, keys);
  }

  private static final Pattern INSERT =
      Pattern.compile("(?is)INSERT INTO (\\S+) \\(([^)]*)\\) VALUES (.*)");
  private static final Pattern UPDATE = Pattern.compile("(?is)UPDATE (\\S+) SET .*");
  private static final Pattern DELETE = Pattern.compile("(?is)DELETE FROM (\\S+)( WHERE .*)?");

  /** What follows a WHERE that a key read cannot take: the statement is outside the scope. */
  private static final Pattern TRAILER =
      Pattern.compile("(?i)\\b(RETURNING|ORDER BY|LIMIT|OUTPUT)\\b");

  /** The corpus's name of a twin's table, or null for another table. */
  private static @Nullable String table(String name, String twin) {
    for (String t : IMPORTED) {
      if (name.equalsIgnoreCase(twin + "." + t)) {
        return t;
      }
    }
    return null;
  }

  /** The first {@code token} outside parentheses and quotes from {@code from}, or -1. */
  private static int topLevel(String s, String token, int from) {
    int depth = 0;
    boolean quoted = false;
    for (int i = from; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '\'') {
        quoted = !quoted;
      } else if (!quoted) {
        if (depth == 0 && s.regionMatches(true, i, token, 0, token.length())) {
          return i;
        }
        depth += ch == '(' ? 1 : ch == ')' ? -1 : 0;
      }
    }
    return -1;
  }

  /** A list split at its commas outside parentheses and quotes. */
  private static List<String> split(String list) {
    List<String> out = new ArrayList<>();
    int start = 0;
    for (int i; (i = topLevel(list, ",", start)) >= 0; start = i + 1) {
      out.add(list.substring(start, i));
    }
    out.add(list.substring(start));
    return out;
  }

  /** "pass", "refused: ...", or what differs between the two results of one statement. */
  static String compare(Outcome expected, Outcome actual) {
    if (REFUSED.equals(actual.state)) {
      return "refused: " + actual.error;
    }
    if (expected.state != null || actual.state != null) {
      if (expected.state == null) {
        return "the driver fails: " + actual.error;
      }
      if (actual.state == null) {
        return "the database fails (" + expected.error + "), the driver does not";
      }
      // the class: the driver may report a write's conflict in its own words
      return expected.state.regionMatches(0, actual.state, 0, 2)
          ? "pass"
          : "the database fails with " + expected.error + ", the driver with " + actual.error;
    }
    if (expected.count != actual.count) {
      return "count " + actual.count + ", expected " + expected.count;
    }
    if (expected.rows != null) {
      String rows =
          CorpusIntegrationTest.compare(
              Objects.requireNonNull(expected.rows), Objects.requireNonNull(actual.rows), false);
      return rows.startsWith("pass") ? "pass" : "returned " + rows;
    }
    return "pass";
  }

  /** The three tables, the twin's read directly and the layout's through the driver. */
  private static String sameTables(Connection vendor, Connection driver, String twin, String layout)
      throws SQLException {
    StringBuilder types = new StringBuilder();
    for (String t : IMPORTED) {
      CorpusIntegrationTest.Side want =
          CorpusIntegrationTest.Side.run(vendor, "SELECT * FROM " + twin + "." + t);
      vendor.rollback(); // the read's own transaction
      CorpusIntegrationTest.Side got =
          CorpusIntegrationTest.Side.run(driver, "SELECT * FROM " + layout + "." + t);
      if (got.error == null && !names(got.columns).equals(names(want.columns))) {
        return "then " + t + " has columns " + got.columns + ", expected " + want.columns;
      }
      // the rows decide here; a column type that differs is the read corpus's to fail (T1)
      if (!got.columns.equals(want.columns)) {
        types.append(", types of ").append(t).append(' ').append(got.columns);
      }
      got.columns.clear();
      got.columns.addAll(want.columns);
      String outcome = CorpusIntegrationTest.compare(want, got, false);
      if (!outcome.startsWith("pass")) {
        return "then " + t + " differs: " + outcome;
      }
    }
    return "pass" + types;
  }

  /** The labels of {@link CorpusIntegrationTest.Side#columns}, without their types. */
  private static List<String> names(List<String> columns) {
    List<String> out = new ArrayList<>();
    for (String c : columns) {
      out.add(c.substring(0, c.lastIndexOf(':')));
    }
    return out;
  }

  /**
   * Every row of the layout's tables as ScalarDB reads it against the twin's, and then a ScalarDB
   * transaction that writes every row and commits.
   */
  private static String scalarDbAgrees(
      Engines e, Catalog catalog, Connection vendor, String twin, String layout) throws Exception {
    String ns = e.storedSchema(layout);
    try (DistributedTransactionAdmin admin = e.admin();
        DistributedTransactionManager tm = e.scalarDb().getTransactionManager()) {
      for (String t : IMPORTED) {
        String table = e.storedTable(t);
        String scalarDb =
            layout.endsWith("d")
                ? Objects.requireNonNull(catalog.lookup(List.of(), ns, table).virtualTable())
                : table;
        TableMetadata md = Objects.requireNonNull(admin.getTableMetadata(ns, scalarDb));
        List<String> columns = new ArrayList<>();
        for (String c : md.getColumnNames()) {
          if (!c.startsWith("tx_") && !c.startsWith("before_")) {
            columns.add(c);
          }
        }
        List<List<String>> want = new ArrayList<>();
        try (Statement st = vendor.createStatement();
            ResultSet rs =
                st.executeQuery(
                    "SELECT "
                        + String.join(", ", columns.stream().map(e.engine()::quote).toList())
                        + " FROM "
                        + twin
                        + "."
                        + t)) {
          ResultSetMetaData rmd = rs.getMetaData();
          while (rs.next()) {
            List<String> row = new ArrayList<>();
            for (int i = 1; i <= rmd.getColumnCount(); i++) {
              int type = rmd.getColumnType(i);
              // every engine's own timestamp class read as one, in the session's time
              row.add(
                  normal(
                      type == java.sql.Types.TIMESTAMP
                          ? rs.getObject(i, LocalDateTime.class)
                          : rs.getObject(i)));
            }
            want.add(row);
          }
        }
        vendor.rollback();
        List<List<String>> got = new ArrayList<>();
        DistributedTransaction tx = tm.begin();
        List<Result> rows = tx.scan(Scan.newBuilder().namespace(ns).table(scalarDb).all().build());
        for (Result r : rows) {
          List<String> row = new ArrayList<>();
          for (String c : columns) {
            Column<?> col = Objects.requireNonNull(r.getColumns().get(c), c);
            row.add(col.hasNullValue() ? null : normal(col.getValueAsObject()));
          }
          got.add(row);
        }
        // ScalarDB writes every row the driver left: their versions and states are its own
        for (Result r : rows) {
          Key.Builder key = Key.newBuilder();
          for (String k : md.getPartitionKeyNames()) {
            key.add(Objects.requireNonNull(r.getColumns().get(k), k));
          }
          tx.upsert(
              Upsert.newBuilder().namespace(ns).table(scalarDb).partitionKey(key.build()).build());
        }
        tx.commit();
        want.sort(CorpusIntegrationTest.Side.ROW_ORDER);
        got.sort(CorpusIntegrationTest.Side.ROW_ORDER);
        if (!want.equals(got)) {
          return t + ": " + got + ", expected " + want;
        }
      }
    }
    return "pass";
  }

  /** A value as both sides spell it: numbers by value, booleans as 0 and 1, times as ISO text. */
  static @Nullable String normal(@Nullable Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Boolean b) {
      return b ? "1" : "0";
    }
    if (v instanceof Number n) {
      BigDecimal d = new BigDecimal(n.toString()).stripTrailingZeros();
      return d.toPlainString();
    }
    if (v instanceof java.sql.Timestamp ts) {
      return normal(ts.toLocalDateTime());
    }
    if (v instanceof java.sql.Date d) {
      return normal(d.toLocalDate());
    }
    if (v instanceof LocalDate d) {
      return d.atStartOfDay().toString();
    }
    if (v instanceof LocalDateTime t) {
      return t.toString();
    }
    if (v instanceof java.time.Instant i) {
      // ScalarDB's TIMESTAMPTZ; the engines' sessions run in UTC in the containers
      return normal(LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC));
    }
    return v.toString();
  }

  /** One statement's result on one side: an update count, returned rows, or an error. */
  static final class Outcome {
    int count = -1;
    CorpusIntegrationTest.@Nullable Side rows;
    @Nullable String state;
    @Nullable String error;

    static Outcome run(Connection c, String sql) {
      Outcome o = new Outcome();
      try (Statement st = c.createStatement()) {
        if (st.execute(sql)) {
          o.rows = CorpusIntegrationTest.Side.of(st.getResultSet());
        } else {
          o.count = st.getUpdateCount();
        }
      } catch (SQLException ex) {
        o.state = Objects.requireNonNullElse(ex.getSQLState(), "");
        o.error = ex.getSQLState() + " " + ex.getMessage();
      }
      return o;
    }
  }
}
