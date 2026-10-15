package com.scalar.db.nativejdbc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What routing costs a statement (todo 008): {@code prepareStatement} through the vendor driver,
 * through the driver with a text its route cache holds, and with a new text each time. None of the
 * vendor drivers contacts the server at prepare, so the difference is the driver's own work; on
 * PostgreSQL a new text also asks the server for the plain statement's plan (design 4.2). Off by
 * default; run with {@code ./gradlew :driver:integrationTest --tests '*RouteCostBenchmark*'
 * -Dnativejdbc.bench=true}.
 */
@SuppressFBWarnings(
    value = "SQL_INJECTION_JDBC",
    justification = "statements written in the test sources")
class RouteCostBenchmark {

  // {n}: an alias that differs from one new text to the next, as Hibernate's generated ones do
  private static final Map<String, String> SHAPES =
      Map.of(
          "key lookup",
          "select c{n}.id,c{n}.name,c{n}.region from rc.c c{n} where c{n}.id=?",
          "key JOIN FK, two tables",
          "select o{n}.id,o{n}.total,c1_0.name from rc.o o{n} join rc.c c1_0"
              + " on c1_0.id=o{n}.customer_id where o{n}.id=?",
          "4 tables, key lookup",
          "select o{n}.id,c.name,i.qty,p.title from rc.o o{n} join rc.c c on c.id=o{n}.customer_id"
              + " join rc.i i on i.order_id=o{n}.id join rc.p p on p.id=i.product_id"
              + " where o{n}.id=?");

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void routeCost(Engines e) throws Exception {
    assumeTrue(Boolean.getBoolean("nativejdbc.bench"), "-Dnativejdbc.bench=true runs it");
    String rc = e.storedSchema("rc");
    String n = e.intType();
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("rc"),
          "CREATE TABLE rc.c (id " + n + " PRIMARY KEY, name VARCHAR(20), region VARCHAR(20))",
          "CREATE TABLE rc.o (id " + n + " PRIMARY KEY, customer_id " + n + ", total " + n + ")",
          "CREATE TABLE rc.i (id "
              + n
              + " PRIMARY KEY, order_id "
              + n
              + ", product_id "
              + n
              + ", qty "
              + n
              + ")",
          "CREATE TABLE rc.p (id " + n + " PRIMARY KEY, title VARCHAR(20))");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(rc, true);
      for (String t : List.of("c", "o", "i", "p")) {
        admin.importTable(
            rc, e.storedTable(t), Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals(rc), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    StringBuilder out =
        new StringBuilder(
            "\n"
                + e
                + ": prepareStatement, median µs of 1,000 after 300 warm-up\n\n"
                + "| shape | vendor | driver, same text | driver, new text |\n|---|---|---|---|\n");
    try (Connection vendor = e.open();
        Connection driver = e.openDriver(defaultCatalogTtl())) {
      for (String shape :
          List.of("key lookup", "key JOIN FK, two tables", "4 tables, key lookup")) {
        String sql = SHAPES.get(shape);
        out.append(
            String.format(
                Locale.ROOT,
                "| %s | %d | %d | %d |%n",
                shape,
                micros(vendor, sql, false),
                micros(driver, sql, false),
                micros(driver, sql, true)));
      }
    }
    System.out.println(out);
  }

  /** The driver's own catalog TTL, not the tests' 0, which reads the catalog on every route. */
  private static Properties defaultCatalogTtl() {
    Properties p = new Properties();
    p.setProperty("scalardbnative.catalogTtlMs", String.valueOf(Catalog.DEFAULT_TTL.toMillis()));
    return p;
  }

  /**
   * The median; a new text each time is the statement with alias {@code n} numbered i, as long as
   * the others, not one after i leading blanks, which the parser's lexer would spend its time on.
   */
  private static long micros(Connection c, String sql, boolean newText) throws SQLException {
    long[] t = new long[1000];
    for (int i = 0; i < 1300; i++) {
      String text = sql.replace("{n}", newText ? String.format(Locale.ROOT, "%04d", i) : "1_0");
      long start = System.nanoTime();
      c.prepareStatement(text).close();
      if (i >= 300) {
        t[i - 300] = System.nanoTime() - start;
      }
    }
    Arrays.sort(t);
    return t[t.length / 2] / 1000;
  }
}
