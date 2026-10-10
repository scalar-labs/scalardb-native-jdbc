package com.scalar.db.nativejdbc.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reproductions of the milestone 2 review's P1 findings (todos 069, 070), on PostgreSQL. */
class SetupReviewIntegrationTest {

  private static final Engines PG = Engines.POSTGRESQL;
  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  @BeforeAll
  static void onlyPostgresql() {
    Assumptions.assumeTrue(Engines.selected().contains(PG), "PostgreSQL is not selected");
  }

  private static Catalog catalog() {
    return new Catalog(PG.engine(), Catalog.DEFAULT_METADATA_SCHEMA, PG::open);
  }

  private static String setup(Catalog catalog, String schema, Duration wait) throws SQLException {
    try (Connection c = PG.open()) {
      Setup.Result r = Setup.run(catalog, c, t -> t.namespace().equals(schema), wait).get(0);
      SQLException error = r.error();
      return error == null ? "completed" : "failed " + error.getSQLState();
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
}
