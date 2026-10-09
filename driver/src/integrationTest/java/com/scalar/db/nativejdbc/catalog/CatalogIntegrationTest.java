package com.scalar.db.nativejdbc.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The catalog against each engine, with tables created and imported by ScalarDB itself: the four
 * layouts, the names a statement must not use, and above all the identifier rules measured per
 * engine (docs/design.md, "Identifiers per engine"): how an unquoted name folds, whether case
 * matters, where an unqualified name resolves, and that a plain table can shadow a ScalarDB one.
 */
class CatalogIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");
  private static final Set<Engines> PREPARED = EnumSet.noneOf(Engines.class);

  static List<Engines> engines() {
    return Engines.selected();
  }

  /**
   * On each engine: a schema {@code app} with a plain table, a table imported with decoupling and
   * one imported classic, all created with unquoted DDL so the engine spells them its own way; a
   * ScalarDB namespace {@code ns} with a created decoupled table {@code Orders} (columns {@code
   * Id}, {@code K}, {@code Amount}, spelled as given) and a created classic table; and the engine's
   * way of shadowing: a plain {@code public.orders} on PostgreSQL, {@code dbo.Orders} on SQL
   * Server, a public synonym on Oracle.
   */
  private static void prepare(Engines e) throws Exception {
    synchronized (PREPARED) {
      if (!PREPARED.add(e)) {
        return;
      }
    }
    String intType = e.intType();
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("app"),
          "CREATE TABLE app.plain (id " + intType + " PRIMARY KEY, x VARCHAR(10))",
          "CREATE TABLE app.Orders (Id " + intType + " PRIMARY KEY, Amount " + intType + ")",
          "CREATE TABLE app.imported_classic (id " + intType + " PRIMARY KEY, x VARCHAR(10))",
          "CREATE TABLE app.late1 (id " + intType + " PRIMARY KEY)",
          "CREATE TABLE app.late2 (id " + intType + " PRIMARY KEY)");
      switch (e) {
        case POSTGRESQL -> {
          e.execute(c, "CREATE TABLE public.orders (id INT PRIMARY KEY)");
        }
        case SQLSERVER -> {
          e.execute(c, "CREATE TABLE dbo.Orders (id INT PRIMARY KEY)");
        }
        case ORACLE -> {
          e.execute(c, "CREATE PUBLIC SYNONYM orders_syn FOR app.orders");
        }
        default -> {}
      }
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      String app = e.stored("app");
      admin.importTable(app, e.stored("Orders"), DECOUPLED, Map.of());
      admin.importTable(app, e.stored("imported_classic"), Map.of(), Map.of());
      admin.createNamespace("ns", true);
      admin.createTable(
          "ns",
          "Orders",
          TableMetadata.newBuilder()
              .addColumn("Id", DataType.INT)
              .addColumn("K", DataType.INT)
              .addColumn("Amount", DataType.INT)
              .addPartitionKey("Id")
              .addClusteringKey("K")
              .build(),
          DECOUPLED);
      admin.createTable(
          "ns",
          "classic",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .build());
    }
  }

  private static Catalog catalog(Engines e) throws Exception {
    prepare(e);
    return new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
  }

  private static final List<String> NO_SESSION = List.of();

  @ParameterizedTest
  @MethodSource("engines")
  void importedDecoupledTable_inItsStoredSpelling(Engines e) throws Exception {
    TableInfo t = catalog(e).lookup(NO_SESSION, "app", e.stored("Orders"));
    String orders = e.stored("Orders");
    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_IMPORTED);
    assertThat(t.namespace()).isEqualTo(e.stored("app"));
    assertThat(t.table()).isEqualTo(orders);
    assertThat(t.dataTable()).isEqualTo(orders);
    assertThat(t.metadataTable()).isEqualTo(orders + "_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo(orders + "_scalardb");
    assertThat(t.keyColumns()).containsExactly(e.stored("Id"));
    assertThat(t.dataColumns()).containsExactlyInAnyOrder(e.stored("Id"), e.stored("Amount"));
  }

  @ParameterizedTest
  @MethodSource("engines")
  void qualifiedNames_followTheEnginesFoldingAndComparison(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    Layout imported = Layout.DECOUPLED_IMPORTED;
    switch (e) {
      case POSTGRESQL -> {
        // unquoted folds to lower case, which is the stored spelling; quoted mixed case is not
        assertThat(layout(catalog, "app", "orders")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "Orders")).isEqualTo(imported);
        assertThat(layout(catalog, "APP", "ORDERS")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "\"orders\"")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "\"Orders\"")).isEqualTo(Layout.PLAIN);
      }
      case MYSQL, MARIADB -> {
        // the engine keeps case and compares table names exactly
        assertThat(layout(catalog, "app", "Orders")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "`Orders`")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "orders")).isEqualTo(Layout.PLAIN);
        assertThat(layout(catalog, "APP", "Orders")).isEqualTo(Layout.PLAIN);
      }
      case SQLSERVER -> {
        // the engine keeps case and compares without regard to it
        assertThat(layout(catalog, "app", "Orders")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "orders")).isEqualTo(imported);
        assertThat(layout(catalog, "APP", "ORDERS")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "[orders]")).isEqualTo(imported);
      }
      default -> {
        // unquoted folds to upper case, which is the stored spelling; quoted lower case is not
        assertThat(layout(catalog, "app", "orders")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "Orders")).isEqualTo(imported);
        assertThat(layout(catalog, "APP", "ORDERS")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "\"ORDERS\"")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "\"orders\"")).isEqualTo(Layout.PLAIN);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void createdDecoupledTable_keepsTheSpellingScalarDbWasGiven(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    Engine engine = e.engine();
    TableInfo t = catalog.lookup(NO_SESSION, engine.quote("ns"), engine.quote("Orders"));
    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_CREATED);
    assertThat(t.namespace()).isEqualTo("ns");
    assertThat(t.table()).isEqualTo("Orders");
    assertThat(t.dataTable()).isEqualTo("Orders_data");
    assertThat(t.metadataTable()).isEqualTo("Orders_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo("Orders");
    assertThat(t.partitionKey()).containsExactly("Id");
    assertThat(t.clusteringKey()).containsExactly("K");
    assertThat(t.dataColumns()).containsExactly("Id", "K", "Amount");
    assertThat(t.nonKeyColumns()).containsExactly("Amount");

    // unquoted, the name reaches the table only where the engine does not fold it
    Layout unquoted = layout(catalog, "ns", "Orders");
    switch (e) {
      case POSTGRESQL, ORACLE -> {
        assertThat(unquoted).isEqualTo(Layout.PLAIN);
      }
      default -> {
        assertThat(unquoted).isEqualTo(Layout.DECOUPLED_CREATED);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void classicTables_createdAndImported(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    // ScalarDB created it quoted, so only the quoted spelling reaches it where the engine folds
    TableInfo created =
        catalog.lookup(NO_SESSION, e.engine().quote("ns"), e.engine().quote("classic"));
    assertThat(created.layout()).isEqualTo(Layout.CLASSIC);
    assertThat(created.keyColumns()).containsExactly("id");
    assertThat(created.dataColumns()).containsExactly("id", "x");

    TableInfo imported = catalog.lookup(NO_SESSION, "app", "imported_classic");
    assertThat(imported.layout()).isEqualTo(Layout.CLASSIC);
    assertThat(imported.metadataTable()).isNull();
    assertThat(imported.keyColumns()).containsExactly(e.stored("id"));
  }

  @ParameterizedTest
  @MethodSource("engines")
  void plainTable_andUnknownTable(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    assertThat(layout(catalog, "app", "plain")).isEqualTo(Layout.PLAIN);
    assertThat(layout(catalog, "app", "nope")).isEqualTo(Layout.PLAIN);
    assertThat(layout(catalog, "nope", "nope")).isEqualTo(Layout.PLAIN);
  }

  @ParameterizedTest
  @MethodSource("engines")
  void unqualifiedNames_resolveTheWayTheSessionDoes(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    Engine engine = e.engine();
    switch (e) {
      case POSTGRESQL -> {
        try (Connection c = e.open()) {
          e.execute(c, "SET search_path TO app, public");
          List<String> session = engine.sessionSchemas(c);
          assertThat(session).containsExactly("app", "public");
          assertThat(catalog.lookup(session, null, "orders").layout())
              .isEqualTo(Layout.DECOUPLED_IMPORTED);
          // a plain table earlier in the path shadows the ScalarDB table
          e.execute(c, "SET search_path TO public, app");
          session = engine.sessionSchemas(c);
          TableInfo shadowed = catalog.lookup(session, null, "orders");
          assertThat(shadowed.layout()).isEqualTo(Layout.PLAIN);
          assertThat(shadowed.namespace()).isEqualTo("public");
        }
      }
      case MYSQL, MARIADB -> {
        try (Connection c = e.open()) {
          e.execute(c, "USE app");
          List<String> session = engine.sessionSchemas(c);
          assertThat(session).containsExactly("app");
          assertThat(catalog.lookup(session, null, "Orders").layout())
              .isEqualTo(Layout.DECOUPLED_IMPORTED);
          assertThat(catalog.lookup(session, null, "orders").layout()).isEqualTo(Layout.PLAIN);
        }
      }
      case SQLSERVER -> {
        try (Connection c = e.open()) {
          List<String> session = engine.sessionSchemas(c);
          assertThat(session).containsExactly("dbo");
          // the administrator's default schema is dbo, where a plain Orders exists
          assertThat(catalog.lookup(session, null, "Orders").layout()).isEqualTo(Layout.PLAIN);
          // a user whose default schema is app reaches the ScalarDB table; dbo is the fallback
          assertThat(catalog.lookup(List.of("app"), null, "orders").layout())
              .isEqualTo(Layout.DECOUPLED_IMPORTED);
          assertThat(catalog.lookup(List.of("app"), null, "plain").layout())
              .isEqualTo(Layout.PLAIN);
        }
      }
      default -> {
        try (Connection c = e.open()) {
          List<String> session = engine.sessionSchemas(c);
          assertThat(session).containsExactly("SYSTEM");
          assertThat(catalog.lookup(session, null, "orders").layout()).isEqualTo(Layout.PLAIN);
          e.execute(c, "ALTER SESSION SET CURRENT_SCHEMA = APP");
          session = engine.sessionSchemas(c);
          assertThat(session).containsExactly("APP");
          assertThat(catalog.lookup(session, null, "orders").layout())
              .isEqualTo(Layout.DECOUPLED_IMPORTED);
          // a public synonym from any schema leads to the table it stands for
          TableInfo viaSynonym = catalog.lookup(List.of("SYSTEM"), null, "orders_syn");
          assertThat(viaSynonym.layout()).isEqualTo(Layout.DECOUPLED_IMPORTED);
          assertThat(viaSynonym.namespace()).isEqualTo("APP");
          assertThat(viaSynonym.table()).isEqualTo("ORDERS");
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void namesAUserStatementMustNotUseAreRefused(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    Engine engine = e.engine();
    String orders = e.stored("Orders");
    assertRefused(catalog, "app", engine.quote(orders + "_scalardb"), "virtual table");
    assertRefused(catalog, "app", engine.quote(orders + "_tx_metadata"), "transaction metadata");
    assertRefused(catalog, engine.quote("ns"), engine.quote("Orders_data"), "data table");
    assertRefused(
        catalog, engine.quote("ns"), engine.quote("Orders_tx_metadata"), "transaction metadata");
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aDatabaseWithoutScalarDb_hasOnlyPlainTables(Engines e) throws Exception {
    prepare(e);
    Catalog none = new Catalog(e.engine(), "nosuch", e::open);
    assertThat(none.lookup(NO_SESSION, "app", e.stored("Orders")).layout()).isEqualTo(Layout.PLAIN);
  }

  @ParameterizedTest
  @MethodSource("engines")
  void theLoadedCatalogIsTrustedUntilTheTtl_orInvalidate(Engines e) throws Exception {
    prepare(e);
    Instant[] now = {Instant.parse("2026-10-09T00:00:00Z")};
    Clock clock =
        new Clock() {
          @Override
          public Instant instant() {
            return now[0];
          }

          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }
        };
    Catalog byTtl =
        new Catalog(
            e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open, Duration.ofSeconds(60), clock);
    Catalog byInvalidate = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    assertThat(layout(byTtl, "app", "late1")).isEqualTo(Layout.PLAIN);
    assertThat(layout(byInvalidate, "app", "late2")).isEqualTo(Layout.PLAIN);

    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.importTable(e.stored("app"), e.stored("late1"), DECOUPLED, Map.of());
      admin.importTable(e.stored("app"), e.stored("late2"), DECOUPLED, Map.of());
    }
    assertThat(layout(byTtl, "app", "late1")).isEqualTo(Layout.PLAIN); // still the old load
    now[0] = now[0].plusSeconds(61);
    assertThat(layout(byTtl, "app", "late1")).isEqualTo(Layout.DECOUPLED_IMPORTED);

    assertThat(layout(byInvalidate, "app", "late2")).isEqualTo(Layout.PLAIN);
    byInvalidate.invalidate();
    assertThat(layout(byInvalidate, "app", "late2")).isEqualTo(Layout.DECOUPLED_IMPORTED);
  }

  private static Layout layout(Catalog catalog, String rawSchema, String rawTable)
      throws SQLException {
    return catalog.lookup(NO_SESSION, rawSchema, rawTable).layout();
  }

  private static void assertRefused(
      Catalog catalog, String rawSchema, String rawTable, String message) {
    assertThatThrownBy(() -> catalog.lookup(NO_SESSION, rawSchema, rawTable))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(message)
        .extracting("SQLState")
        .isEqualTo("42000");
  }
}
