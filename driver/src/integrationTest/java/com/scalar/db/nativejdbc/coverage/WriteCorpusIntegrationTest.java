package com.scalar.db.nativejdbc.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.TableMetadata;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone T3 of the testing plan, with nothing in flight: every statement of the write corpus
 * runs through the driver on the corpus's tables imported into ScalarDB, in each layout, and
 * directly on a plain twin of each. The update counts or returned rows, and then the three tables,
 * must be the same on both sides after every statement. At the end ScalarDB reads every row the
 * driver left and must see the twin's data, and a ScalarDB transaction writes every row: the
 * metadata the driver wrote is ScalarDB's. Writes {@code build/coverage/<engine>-writes.md}.
 */
@EnabledIfSystemProperty(named = "nativejdbc.coverage", matches = "true")
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
    justification = "the corpus's statements and the test's own DDL")
class WriteCorpusIntegrationTest {

  private static final List<String> IMPORTED = List.of("c", "o", "k");

  /** Each layout's schema, and its plain twin's. */
  private static final Map<String, String> TWINS = Map.of("wd", "wpd", "wi", "wpi");

  private static final String REFUSED = "0AN01";

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void writeCorpus_leavesThroughTheDriverWhatTheDatabaseLeaves(Engines e) throws Exception {
    Engine engine = e.engine();
    String ddl =
        CorpusIntegrationTest.types(engine, CorpusIntegrationTest.resource("corpus/schema.sql"));
    try (Connection c = e.open()) {
      for (String schema : List.of("wd", "wpd", "wi", "wpi")) {
        e.execute(c, e.createSchema(schema));
        e.execute(
            c, CorpusIntegrationTest.statements(ddl.replace("{S}", schema)).toArray(String[]::new));
      }
    }
    try (DistributedTransactionAdmin admin = e.scalarDb().getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      for (String layout : TWINS.keySet()) {
        String ns = e.storedSchema(layout);
        admin.createNamespace(ns, true);
        for (String t : IMPORTED) {
          admin.importTable(
              ns,
              e.storedTable(t),
              layout.equals("wd") ? Map.of("transaction-metadata-decoupling", "true") : Map.of(),
              Map.of());
        }
      }
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      List<String> scalarDb = List.of(e.storedSchema("wd"), e.storedSchema("wi"));
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
            .append("\n\n| file | statement | layout | outcome |\n|---|---|---|---|\n");
    List<String> failures = new ArrayList<>();
    int passed = 0;
    int refused = 0;
    try (Connection driver = e.openDriver()) {
      for (String layout : List.of("wd", "wi")) {
        String twin = Objects.requireNonNull(TWINS.get(layout));
        try (Connection vendor = e.open()) {
          vendor.setAutoCommit(false);
          for (Corpus.Entry st : Corpus.load("writes", engine)) {
            String sql = CorpusIntegrationTest.types(engine, st.sql());
            String through = CorpusIntegrationTest.tables(sql, layout);
            Outcome actual = Outcome.run(driver, through);
            Outcome expected = Outcome.run(vendor, CorpusIntegrationTest.tables(sql, twin));
            if (expected.state != null || REFUSED.equals(actual.state)) {
              vendor.rollback();
            } else {
              vendor.commit();
            }
            String outcome = compare(expected, actual);
            if (outcome.equals("pass")) {
              outcome = sameTables(vendor, driver, twin, layout);
            }
            if (outcome.startsWith("refused")) {
              refused++;
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
          String scalarDb = scalarDbAgrees(e, catalog, vendor, twin, layout);
          if (!scalarDb.equals("pass")) {
            failures.add(layout + ", read through ScalarDB: " + scalarDb);
          }
          report.append("\nRead through ScalarDB, ").append(layout).append(": ").append(scalarDb);
          report.append("\n");
        }
      }
    }
    report.insert(
        report.indexOf("\n\n") + 2,
        String.format(
            Locale.ROOT,
            "%d passed, %d refused, %d failed (each statement in both layouts).%n%n",
            passed,
            refused,
            failures.size()));
    Path out = Path.of("build", "coverage", engine.name().toLowerCase(Locale.ROOT) + "-writes.md");
    Files.createDirectories(Objects.requireNonNull(out.getParent()));
    Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
    assertThat(failures).as("statements whose effects differ; see " + out).isEmpty();
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
    for (String t : IMPORTED) {
      CorpusIntegrationTest.Side want =
          CorpusIntegrationTest.Side.run(vendor, "SELECT * FROM " + twin + "." + t);
      vendor.rollback(); // the read's own transaction
      CorpusIntegrationTest.Side got =
          CorpusIntegrationTest.Side.run(driver, "SELECT * FROM " + layout + "." + t);
      // the rows decide here; a column type that differs is the read corpus's to fail (T1)
      String types = got.columns.equals(want.columns) ? "" : ", types of " + t + " " + got.columns;
      got.columns.clear();
      got.columns.addAll(want.columns);
      String outcome = CorpusIntegrationTest.compare(want, got, false);
      if (!outcome.startsWith("pass")) {
        return "then " + t + " differs: " + outcome;
      }
      if (!types.isEmpty()) {
        return "pass" + types;
      }
    }
    return "pass";
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
            layout.equals("wd")
                ? Objects.requireNonNull(catalog.lookup(List.of(), ns, table).virtualTable())
                : table;
        TableMetadata md = Objects.requireNonNull(admin.getTableMetadata(ns, scalarDb));
        List<String> columns = new ArrayList<>();
        for (String c : md.getColumnNames()) {
          if (!c.startsWith("tx_") && !c.startsWith("before_")) {
            columns.add(c);
          }
        }
        List<String> want = new ArrayList<>();
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
            want.add(row.toString());
          }
        }
        vendor.rollback();
        List<String> got = new ArrayList<>();
        DistributedTransaction tx = tm.begin();
        List<Result> rows = tx.scan(Scan.newBuilder().namespace(ns).table(scalarDb).all().build());
        for (Result r : rows) {
          List<String> row = new ArrayList<>();
          for (String c : columns) {
            Column<?> col = Objects.requireNonNull(r.getColumns().get(c), c);
            row.add(col.hasNullValue() ? null : normal(col.getValueAsObject()));
          }
          got.add(row.toString());
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
        want.sort(null);
        got.sort(null);
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
