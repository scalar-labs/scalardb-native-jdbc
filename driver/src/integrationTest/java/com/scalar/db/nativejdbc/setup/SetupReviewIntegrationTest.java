package com.scalar.db.nativejdbc.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/** Reproductions of the milestone 2 review's findings (todos 069-073), on PostgreSQL and MySQL. */
class SetupReviewIntegrationTest {

  private static final Engines PG = Engines.POSTGRESQL;
  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  @BeforeEach
  void selectedEngine(TestInfo test) {
    Engines engine = test.getDisplayName().contains("MySql") ? Engines.MYSQL : PG;
    Assumptions.assumeTrue(Engines.selected().contains(engine), engine + " is not selected");
  }

  private static Catalog catalog() {
    return new Catalog(PG.engine(), Catalog.DEFAULT_METADATA_SCHEMA, PG::open);
  }

  private static String setup(Catalog catalog, String schema, Duration wait) throws SQLException {
    return setup(PG, catalog, schema, wait, List.of());
  }

  private static String setup(
      Engines engine, Catalog catalog, String schema, Duration wait, List<String> grantees)
      throws SQLException {
    try (Connection c = engine.open()) {
      Setup.Result r =
          Setup.run(catalog, c, t -> t.namespace().equals(schema), wait, grantees).get(0);
      SQLException error = r.error();
      return error == null ? "completed" : "failed " + error.getSQLState();
    }
  }

  private static Catalog catalogAs(Engines engine, String user) {
    return new Catalog(
        engine.engine(),
        Catalog.DEFAULT_METADATA_SCHEMA,
        () -> DriverManager.getConnection(engine.url(), user, "pw"));
  }

  private static long count(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void todo069_anInFlightScalarDbInsert_doesNotBlockTheBackfill() throws Exception {
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE SCHEMA r69",
          "CREATE TABLE r69.t (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO r69.t VALUES (1, 'a')");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("r69", true);
      admin.importTable("r69", "t", DECOUPLED, Map.of());
    }
    try (Connection c = PG.open()) {
      // an in-flight ScalarDB insert: prepared, no before image (PrepareMutationComposer)
      PG.execute(
          c,
          "INSERT INTO r69.t VALUES (2, 'new')",
          "INSERT INTO r69.t_tx_metadata (id, tx_id, tx_state, tx_version) VALUES (2, 'i', 1, 1)");
    }
    Catalog catalog = catalog();
    String outcome = setup(catalog, "r69", Duration.ofSeconds(2));
    TableInfo t = catalog.lookup(List.of(), "r69", "t");
    // 070: and a backfill that did not complete must not leave the table marked set up
    assertThat(outcome + ", set up " + t.isSetUp()).isEqualTo("completed, set up true");
  }

  @Test
  void todo070_aTableThatDiffersOnlyInCase_isNotSetUpByTheOthersObjects() throws Exception {
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE SCHEMA r70",
          "CREATE TABLE r70.orders (id INT PRIMARY KEY)",
          "CREATE TABLE r70.\"Orders\" (id INT PRIMARY KEY)");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("r70", true);
      admin.importTable("r70", "orders", DECOUPLED, Map.of());
      admin.importTable("r70", "Orders", DECOUPLED, Map.of());
    }
    // the objects the setup creates for r70.orders only
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE VIEW r70.rc_orders AS SELECT id FROM r70.orders",
          "CREATE INDEX orders_inflight ON r70.orders_tx_metadata (id)");
    }
    Catalog catalog = catalog();
    TableInfo upper = catalog.lookup(List.of(), "r70", "\"Orders\"");
    assertThat(Objects.requireNonNull(upper.metadataTable())).isEqualTo("Orders_tx_metadata");
    assertThat(upper.isSetUp()).as("r70.\"Orders\" has no view or index of its own").isFalse();
  }

  @Test
  void todo070_aHandMadeViewOfTheRightName_doesNotCountAsTheSetup() throws Exception {
    try (Connection c = PG.open()) {
      PG.execute(
          c, "CREATE SCHEMA r70b", "CREATE TABLE r70b.t (id INT PRIMARY KEY, x TEXT, y TEXT)");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("r70b", true);
      admin.importTable("r70b", "t", DECOUPLED, Map.of());
    }
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE VIEW r70b.rc_t AS SELECT id FROM r70b.t", // not the committed image
          "CREATE INDEX t_inflight ON r70b.t_tx_metadata (tx_id)");
    }
    assertThat(catalog().lookup(List.of(), "r70b", "t").isSetUp()).isFalse();
  }

  @Test
  void todo071_aPredicateOnANonKeyColumn_usesTheTablesIndexThroughTheView() throws Exception {
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE SCHEMA r71",
          "CREATE TABLE r71.t (id INT PRIMARY KEY, x INT)",
          "CREATE INDEX t_x ON r71.t (x)",
          "INSERT INTO r71.t SELECT g, g FROM generate_series(1, 1000) g");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("r71", true);
      admin.importTable("r71", "t", DECOUPLED, Map.of());
    }
    assertThat(setup(catalog(), "r71", Duration.ofSeconds(30))).isEqualTo("completed");
    try (Connection c = PG.open();
        Statement st = c.createStatement()) {
      st.execute("SET enable_seqscan = off");
      StringBuilder plan = new StringBuilder();
      try (ResultSet rs = st.executeQuery("EXPLAIN SELECT * FROM r71.rc_t WHERE x = 42")) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
      assertThat(plan.toString()).contains("t_x");
    }
  }

  @Test
  void todo072_rowLevelSecurityOnTheTable_holdsThroughTheView() throws Exception {
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE SCHEMA r72",
          "CREATE TABLE r72.t (id INT PRIMARY KEY, owner TEXT)",
          "INSERT INTO r72.t VALUES (1, 'r72app'), (2, 'someone')",
          "CREATE ROLE r72app LOGIN PASSWORD 'pw'");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("r72", true);
      admin.importTable("r72", "t", DECOUPLED, Map.of());
    }
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "GRANT USAGE ON SCHEMA r72, scalardb TO r72app",
          "GRANT SELECT ON ALL TABLES IN SCHEMA r72, scalardb TO r72app",
          "ALTER TABLE r72.t ENABLE ROW LEVEL SECURITY",
          "CREATE POLICY own ON r72.t USING (owner = current_user)");
    }
    assertThat(setup(PG, catalog(), "r72", Duration.ofSeconds(30), List.of("r72app")))
        .isEqualTo("completed");
    assertThat(catalogAs(PG, "r72app").lookup(List.of(), "r72", "t").isSetUp()).isTrue();
    try (Connection app = DriverManager.getConnection(PG.url(), "r72app", "pw")) {
      assertThat(count(app, "SELECT COUNT(*) FROM r72.rc_t"))
          .isEqualTo(count(app, "SELECT COUNT(*) FROM r72.t"))
          .isEqualTo(1);
    }
  }

  @Test
  void todo072_onMySql_anApplicationUserSeesTheSetupAndReadsTheView() throws Exception {
    Engines my = Engines.MYSQL;
    try (Connection c = my.open()) {
      my.execute(
          c,
          "CREATE DATABASE r72m",
          "CREATE TABLE r72m.t (id INT PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO r72m.t VALUES (1, 'a')",
          "CREATE USER 'r72app'@'%' IDENTIFIED BY 'pw'");
    }
    try (DistributedTransactionAdmin admin = my.admin()) {
      admin.createNamespace("r72m", true);
      admin.importTable("r72m", "t", DECOUPLED, Map.of());
    }
    try (Connection c = my.open()) {
      my.execute(
          c,
          "GRANT SELECT, INSERT, UPDATE, DELETE ON r72m.t TO 'r72app'@'%'",
          "GRANT SELECT, INSERT, UPDATE, DELETE ON r72m.t_tx_metadata TO 'r72app'@'%'",
          "GRANT SELECT ON scalardb.* TO 'r72app'@'%'",
          "GRANT SELECT ON test.* TO 'r72app'@'%'"); // the URL's database
    }
    Catalog admin = new Catalog(my.engine(), Catalog.DEFAULT_METADATA_SCHEMA, my::open);
    assertThat(setup(my, admin, "r72m", Duration.ofSeconds(30), List.of("r72app@%")))
        .isEqualTo("completed");
    assertThat(catalogAs(my, "r72app").lookup(List.of(), "r72m", "t").isSetUp()).isTrue();
    try (Connection app = DriverManager.getConnection(my.url(), "r72app", "pw")) {
      assertThat(count(app, "SELECT COUNT(*) FROM r72m.rc_t")).isEqualTo(1);
    }
  }

  @Test
  void todo073_aConstraintViolation_failsTheBackfillAtOnce() throws Exception {
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE SCHEMA r73",
          "CREATE TABLE r73.t (id INT PRIMARY KEY)",
          "INSERT INTO r73.t VALUES (1)");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("r73", true);
      admin.importTable("r73", "t", DECOUPLED, Map.of());
    }
    try (Connection c = PG.open()) {
      PG.execute(c, "ALTER TABLE r73.t_tx_metadata ADD CONSTRAINT v CHECK (tx_version > 1)");
    }
    long start = System.nanoTime();
    String outcome = setup(catalog(), "r73", Duration.ofSeconds(30));
    assertThat(outcome).isEqualTo("failed 23514");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
  }
}
