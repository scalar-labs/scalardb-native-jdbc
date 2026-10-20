package com.scalar.db.nativejdbc.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.testing.Engines;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
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
   * one imported inline, all created with unquoted DDL so the engine spells them its own way; a
   * ScalarDB namespace {@code ns} with a created decoupled table {@code Orders} (columns {@code
   * Id}, {@code K}, {@code Amount}, spelled as given) and a created inline table; and the engine's
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
          "CREATE TABLE app.imported_inline (id " + intType + " PRIMARY KEY, x VARCHAR(10))",
          "CREATE TABLE app.late1 (id " + intType + " PRIMARY KEY)",
          "CREATE TABLE app.late2 (id " + intType + " PRIMARY KEY)",
          "CREATE TABLE app.Mixed_Case (Id " + intType + " PRIMARY KEY)");
      switch (e) {
        case POSTGRESQL -> {
          e.execute(c, "CREATE TABLE public.orders (id INT PRIMARY KEY)");
        }
        case SQLSERVER -> {
          e.execute(
              c,
              "CREATE TABLE dbo.Orders (id INT PRIMARY KEY)",
              "CREATE SYNONYM app.orders_syn FOR app.Orders");
        }
        case ORACLE -> {
          e.execute(
              c,
              "CREATE PUBLIC SYNONYM orders_syn FOR app.orders",
              "CREATE SYNONYM app.orders_priv FOR app.orders",
              "CREATE SYNONYM app.plain_syn FOR app.plain",
              // an unquoted name beyond ASCII, folded by Oracle itself
              "CREATE TABLE app.größe (id NUMBER(10) PRIMARY KEY)");
        }
        default -> {}
      }
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      String app = e.storedSchema("app");
      admin.importTable(app, e.storedTable("Orders"), DECOUPLED, Map.of());
      admin.importTable(app, e.storedTable("imported_inline"), Map.of(), Map.of());
      // where the engine ignores case, ScalarDB accepts a spelling that differs from the stored
      // one and records it as given
      boolean ignoresCase;
      try (Connection c = e.open()) {
        ignoresCase = e.engine().tableNamesCaseInsensitive(c);
      }
      admin.importTable(
          app, ignoresCase ? "MIXED_case" : e.storedTable("Mixed_Case"), DECOUPLED, Map.of());
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
      // a composite key declared in an order that differs from the column order
      admin.createTable(
          "ns",
          "composite",
          TableMetadata.newBuilder()
              .addColumn("a", DataType.INT)
              .addColumn("b", DataType.INT)
              .addColumn("c", DataType.INT)
              .addColumn("d", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("b")
              .addPartitionKey("a")
              .addClusteringKey("d")
              .addClusteringKey("c")
              .build(),
          DECOUPLED);
      admin.createTable(
          "ns",
          "inline",
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
    TableInfo t = catalog(e).lookup(NO_SESSION, "app", e.storedTable("Orders"));
    String orders = e.storedTable("Orders");
    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_IMPORTED);
    assertThat(t.namespace()).isEqualTo(e.storedSchema("app"));
    assertThat(t.table()).isEqualTo(orders);
    assertThat(t.dataTable()).isEqualTo(orders);
    assertThat(t.metadataTable()).isEqualTo(orders + "_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo(orders + "_scalardb");
    assertThat(t.keyColumns()).containsExactly(e.storedColumn("Id"));
    assertThat(t.dataColumns())
        .containsExactlyInAnyOrder(e.storedColumn("Id"), e.storedColumn("Amount"));
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
      case MYSQL_LOWER_CASE -> {
        // stored in lower case, compared without regard to case, ScalarDB recorded app.Orders
        assertThat(layout(catalog, "app", "Orders")).isEqualTo(imported);
        assertThat(layout(catalog, "app", "orders")).isEqualTo(imported);
        assertThat(layout(catalog, "APP", "ORDERS")).isEqualTo(imported);
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
    assertThat(t.table()).isEqualTo(e == Engines.MYSQL_LOWER_CASE ? "orders" : "Orders");
    assertThat(t.dataTable()).isEqualTo("Orders_data");
    assertThat(t.metadataTable()).isEqualTo("Orders_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo(t.table()); // the virtual name is the user's name
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
  void compositeKeys_keepTheOrderScalarDbWasGiven(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    TableInfo t = catalog.lookup(NO_SESSION, e.engine().quote("ns"), e.engine().quote("composite"));
    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_CREATED);
    assertThat(t.partitionKey()).containsExactly("b", "a");
    assertThat(t.clusteringKey()).containsExactly("d", "c");
    assertThat(t.keyColumns()).containsExactly("b", "a", "d", "c");
    assertThat(t.nonKeyColumns()).containsExactly("x");
  }

  @ParameterizedTest
  @MethodSource("engines")
  void inlineTables_createdAndImported(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    // ScalarDB created it quoted, so only the quoted spelling reaches it where the engine folds
    TableInfo created =
        catalog.lookup(NO_SESSION, e.engine().quote("ns"), e.engine().quote("inline"));
    assertThat(created.layout()).isEqualTo(Layout.INLINE);
    assertThat(created.keyColumns()).containsExactly("id");
    assertThat(created.dataColumns()).containsExactly("id", "x");

    TableInfo imported = catalog.lookup(NO_SESSION, "app", "imported_inline");
    assertThat(imported.layout()).isEqualTo(Layout.INLINE);
    assertThat(imported.metadataTable()).isNull();
    assertThat(imported.keyColumns()).containsExactly(e.storedColumn("id"));
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
      case MYSQL_LOWER_CASE -> {
        try (Connection c = e.open()) {
          e.execute(c, "USE app");
          assertThat(catalog.lookup(engine.sessionSchemas(c), null, "orders").layout())
              .isEqualTo(Layout.DECOUPLED_IMPORTED);
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
          assertRefusedAsSynonym(catalog, NO_SESSION, "app", "orders_syn");
          assertRefusedAsSynonym(catalog, List.of("app"), null, "orders_syn");
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
          // a synonym of a ScalarDB table is refused, public or private, qualified or not; one of
          // a table ScalarDB does not manage reads as that table, DUAL's among them (todo 282)
          assertThat(catalog.lookup(List.of("APP"), null, "plain_syn").layout())
              .isEqualTo(Layout.PLAIN);
          assertThat(catalog.lookup(List.of("APP"), null, "dual").layout()).isEqualTo(Layout.PLAIN);
          assertRefusedAsSynonym(catalog, List.of("SYSTEM"), null, "orders_syn");
          assertRefusedAsSynonym(catalog, List.of("APP"), null, "orders_priv");
          assertRefusedAsSynonym(catalog, NO_SESSION, "app", "orders_priv");
        }
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void namesAUserStatementMustNotUseAreRefused(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    Engine engine = e.engine();
    String orders = e.storedTable("Orders");
    assertRefused(catalog, "app", engine.quote(orders + "_scalardb"), "virtual table");
    assertRefused(catalog, "app", engine.quote(orders + "_tx_metadata"), "transaction metadata");
    assertRefused(catalog, engine.quote("ns"), engine.quote("Orders_data"), "data table");
    assertRefused(
        catalog, engine.quote("ns"), engine.quote("Orders_tx_metadata"), "transaction metadata");
  }

  @ParameterizedTest
  @MethodSource("engines")
  void theSessionSaysWhetherDoubleQuotesQuoteIdentifiers(Engines e) throws Exception {
    prepare(e);
    try (Connection c = e.open()) {
      Engine engine = e.engine();
      assertThat(engine.doubleQuotesQuoteIdentifiers(c))
          .isEqualTo(engine.doubleQuotesQuoteIdentifiersByDefault());
      switch (e) {
        case MYSQL, MYSQL_LOWER_CASE, MARIADB -> {
          e.execute(c, "SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',ANSI_QUOTES')");
          assertThat(engine.doubleQuotesQuoteIdentifiers(c)).isTrue();
        }
        case SQLSERVER -> {
          e.execute(c, "SET QUOTED_IDENTIFIER OFF");
          assertThat(engine.doubleQuotesQuoteIdentifiers(c)).isFalse();
        }
        default -> {}
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void anUnquotedNonAsciiName_isFoldedByOracle(Engines e) throws Exception {
    Assumptions.assumeTrue(e == Engines.ORACLE, "only Oracle folds beyond ASCII its own way");
    prepare(e);
    String stored;
    try (Connection c = e.open();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT table_name FROM all_tables WHERE owner = 'APP' AND table_name LIKE 'GR%'");
        java.sql.ResultSet rs = ps.executeQuery()) {
      assertThat(rs.next()).isTrue();
      stored = rs.getString(1);
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.importTable("APP", stored, DECOUPLED, Map.of());
    }
    try (Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open)) {
      TableInfo t = catalog.lookup(NO_SESSION, "app", "größe");
      assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_IMPORTED);
      assertThat(t.table()).isEqualTo(stored);
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aHit_returnsTheClassificationAlreadyMade(Engines e) throws Exception {
    prepare(e);
    try (Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open)) {
      TableInfo first = catalog.lookup(NO_SESSION, "app", e.storedTable("Orders"));
      assertThat(catalog.lookup(NO_SESSION, "app", e.storedTable("Orders"))).isSameAs(first);
      String virtual = e.engine().quote(e.storedTable("Orders") + "_scalardb");
      for (int i = 0; i < 2; i++) {
        assertThatThrownBy(() -> catalog.lookup(NO_SESSION, "app", virtual))
            .isInstanceOf(SQLException.class)
            .extracting("SQLState")
            .isEqualTo("42000");
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aDatabaseWithoutScalarDb_isAnErrorUnlessAllowed(Engines e) throws Exception {
    prepare(e);
    try (Catalog none = new Catalog(e.engine(), "nosuch", e::open)) {
      assertThatThrownBy(() -> none.lookup(NO_SESSION, "app", e.storedTable("Orders")))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("nosuch")
          .extracting("SQLState")
          .isEqualTo("08004");
    }
    try (Catalog allowed =
        new Catalog(e.engine(), "nosuch", e::open, Catalog.DEFAULT_TTL, Clock.systemUTC(), true)) {
      assertThat(allowed.lookup(NO_SESSION, "app", e.storedTable("Orders")).layout())
          .isEqualTo(Layout.PLAIN);
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aNameRecordedInAnotherCaseThanStored_isStillTheScalarDbTable(Engines e) throws Exception {
    Catalog catalog = catalog(e);
    boolean ignoresCase;
    try (Connection c = e.open()) {
      ignoresCase = e.engine().tableNamesCaseInsensitive(c);
    }
    assertThat(ignoresCase).isEqualTo(e == Engines.SQLSERVER || e == Engines.MYSQL_LOWER_CASE);
    // the import recorded app.MIXED_case where the engine ignores case, app.Mixed_Case elsewhere
    assertThat(catalog.lookup(NO_SESSION, "app", e.storedTable("Mixed_Case")).layout())
        .isEqualTo(Layout.DECOUPLED_IMPORTED);
    if (ignoresCase) {
      assertThat(catalog.lookup(NO_SESSION, "app", "mixed_CASE").layout())
          .isEqualTo(Layout.DECOUPLED_IMPORTED);
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aUserWhoCannotSeeTheCatalog_getsAnError_notPlainTables(Engines e) throws Exception {
    Assumptions.assumeTrue(
        e == Engines.ORACLE, "Oracle reports a table it does not grant as absent");
    prepare(e);
    String url = e.url();
    try (Connection c = e.open()) {
      e.execute(
          c,
          "CREATE USER limited IDENTIFIED BY limited",
          "GRANT CREATE SESSION TO limited",
          "GRANT SELECT, INSERT, UPDATE, DELETE ON app.orders TO limited");
    }
    try (Catalog asLimited =
        new Catalog(
            Engine.ORACLE,
            Catalog.DEFAULT_METADATA_SCHEMA,
            () -> DriverManager.getConnection(url, "limited", "limited"))) {
      assertThatThrownBy(() -> asLimited.lookup(List.of("LIMITED"), "app", "orders"))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("lacks SELECT")
          .extracting("SQLState")
          .isEqualTo("42501");
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aReloadNeverBlocksCacheHits(Engines e) throws Exception {
    Assumptions.assumeTrue(e == Engines.POSTGRESQL, "one engine is enough");
    prepare(e);
    // a catalog connection whose queries take 200 ms stands in for a large catalog or a slow miss
    Catalog.ConnectionSupplier slow = () -> slowConnection(e.open(), 200);
    Instant[] now = {Instant.parse("2026-10-09T00:00:00Z")};
    try (Catalog catalog =
        new Catalog(
            e.engine(),
            Catalog.DEFAULT_METADATA_SCHEMA,
            slow,
            Duration.ofSeconds(60),
            fixedClock(now),
            false)) {
      catalog.lookup(NO_SESSION, "app", "plain"); // the first load and resolution
      ExecutorService pool = Executors.newFixedThreadPool(9);
      try {
        now[0] = now[0].plusSeconds(61); // the next lookup reloads
        Future<?> reload = pool.submit(() -> catalog.lookup(NO_SESSION, "app", "plain"));
        Thread.sleep(50); // let the reload start
        List<Future<Long>> hits = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
          hits.add(
              pool.submit(
                  () -> {
                    long t0 = System.nanoTime();
                    catalog.lookup(NO_SESSION, "app", "plain");
                    return (System.nanoTime() - t0) / 1_000_000;
                  }));
        }
        for (Future<Long> h : hits) {
          assertThat(h.get()).as("a hit during a reload, in ms").isLessThan(100);
        }
        reload.get();
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void theLoadedCatalogIsTrustedUntilTheTtl_orInvalidate(Engines e) throws Exception {
    prepare(e);
    Instant[] now = {Instant.parse("2026-10-09T00:00:00Z")};
    Catalog byTtl =
        new Catalog(
            e.engine(),
            Catalog.DEFAULT_METADATA_SCHEMA,
            e::open,
            Duration.ofSeconds(60),
            fixedClock(now),
            false);
    Catalog byInvalidate = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    assertThat(layout(byTtl, "app", "late1")).isEqualTo(Layout.PLAIN);
    assertThat(layout(byInvalidate, "app", "late2")).isEqualTo(Layout.PLAIN);

    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.importTable(e.storedSchema("app"), e.storedTable("late1"), DECOUPLED, Map.of());
      admin.importTable(e.storedSchema("app"), e.storedTable("late2"), DECOUPLED, Map.of());
    }
    assertThat(layout(byTtl, "app", "late1")).isEqualTo(Layout.PLAIN); // still the old load
    now[0] = now[0].plusSeconds(61);
    assertThat(layout(byTtl, "app", "late1")).isEqualTo(Layout.DECOUPLED_IMPORTED);

    assertThat(layout(byInvalidate, "app", "late2")).isEqualTo(Layout.PLAIN);
    byInvalidate.invalidate();
    assertThat(layout(byInvalidate, "app", "late2")).isEqualTo(Layout.DECOUPLED_IMPORTED);
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aNameResolvedAnew_toAnotherTable_movesTheGeneration(Engines e) throws Exception {
    Assumptions.assumeTrue(e == Engines.POSTGRESQL, "the search path shows it on one engine");
    prepare(e);
    Instant[] now = {Instant.parse("2026-10-14T00:00:00Z")};
    Catalog catalog =
        new Catalog(
            e.engine(),
            Catalog.DEFAULT_METADATA_SCHEMA,
            e::open,
            Duration.ofSeconds(60),
            fixedClock(now),
            false);
    List<String> path = List.of("public", "app");
    try (Connection c = e.open()) {
      e.execute(c, "CREATE TABLE app.r128 (id int)");
      catalog.lookup(path, null, "orders"); // the catalog loaded at 0 s
      now[0] = now[0].plusSeconds(10);
      assertThat(catalog.lookup(path, null, "r128").namespace()).isEqualTo("app"); // at 10 s
      now[0] = now[0].plusSeconds(51);
      catalog.lookup(path, null, "orders"); // loaded again at 61 s
      long before = catalog.generation();
      // a table that shadows the name, found when its resolution expires at 70 s (todo 128)
      e.execute(c, "CREATE TABLE public.r128 (id int)");
      now[0] = now[0].plusSeconds(10);
      assertThat(catalog.lookup(path, null, "r128").namespace()).isEqualTo("public");
      assertThat(catalog.generation()).isGreaterThan(before);
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aSynonymRedefined_toAScalarDbTable_isRefusedOnceResolvedAnew(Engines e) throws Exception {
    Assumptions.assumeTrue(e == Engines.ORACLE, "the driver follows synonyms on Oracle only");
    prepare(e);
    Instant[] now = {Instant.parse("2026-10-20T00:00:00Z")};
    Catalog catalog =
        new Catalog(
            e.engine(),
            Catalog.DEFAULT_METADATA_SCHEMA,
            e::open,
            Duration.ofSeconds(60),
            fixedClock(now),
            false);
    List<String> app = List.of("APP");
    try (Connection c = e.open()) {
      e.execute(c, "CREATE OR REPLACE SYNONYM app.moving FOR app.plain");
      catalog.lookup(app, null, "plain"); // the catalog loaded at 0 s
      now[0] = now[0].plusSeconds(10);
      assertThat(catalog.lookup(app, null, "moving").layout()).isEqualTo(Layout.PLAIN); // at 10 s
      now[0] = now[0].plusSeconds(51);
      catalog.lookup(app, null, "plain"); // loaded again at 61 s
      long before = catalog.generation();
      // the same synonym, standing for a ScalarDB table now: refused when its resolution expires
      // at 70 s, and what was prepared on the old answer is routed again
      e.execute(c, "CREATE OR REPLACE SYNONYM app.moving FOR app.orders");
      now[0] = now[0].plusSeconds(10);
      assertThatThrownBy(() -> catalog.lookup(app, null, "moving"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("42000");
      assertThat(catalog.generation()).isGreaterThan(before);
    }
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

  private static Clock fixedClock(Instant[] now) {
    return new Clock() {
      @Override
      public Instant instant() {
        return now[0];
      }

      @Override
      public ZoneId getZone() {
        return ZoneOffset.UTC;
      }

      @Override
      public Clock withZone(ZoneId zone) {
        return this;
      }
    };
  }

  /** The connection, with every query delayed by {@code millis}. */
  private static Connection slowConnection(Connection real, long millis) {
    InvocationHandler onConnection =
        (proxy, method, args) -> {
          Object r;
          try {
            r = method.invoke(real, args);
          } catch (InvocationTargetException ex) {
            throw ex.getCause();
          }
          if (!method.getName().equals("prepareStatement")) {
            return r;
          }
          PreparedStatement ps = (PreparedStatement) r;
          return Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              (p2, m2, a2) -> {
                if (m2.getName().equals("executeQuery")) {
                  Thread.sleep(millis);
                }
                try {
                  return m2.invoke(ps, a2);
                } catch (InvocationTargetException ex) {
                  throw ex.getCause();
                }
              });
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, onConnection);
  }

  private static void assertRefusedAsSynonym(
      Catalog catalog, List<String> session, @Nullable String rawSchema, String rawTable) {
    assertThatThrownBy(() -> catalog.lookup(session, rawSchema, rawTable))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("synonym")
        .extracting("SQLState")
        .isEqualTo("42000");
  }
}
