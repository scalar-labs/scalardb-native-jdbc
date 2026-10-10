package com.scalar.db.nativejdbc.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.engine.SetupObjects;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 2 on every engine: a decoupled table is refused until the setup has run; the setup
 * creates the in-flight index, backfills an imported table and creates the read view, and changes
 * nothing when run again; the view shows the committed image of each row; the teardown removes the
 * setup. Every run is limited to this test's schema.
 */
class SetupIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void setup_makesTablesUsable_backfills_isIdempotent_andTearsDown(Engines e) throws Exception {
    Engine engine = e.engine();
    String su = e.storedSchema("su");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("su"),
          "CREATE TABLE su.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO su.imp VALUES (1, 'a')",
          "INSERT INTO su.imp VALUES (2, 'b')",
          "INSERT INTO su.imp VALUES (3, 'c')",
          "CREATE TABLE su.konly (id " + e.intType() + " PRIMARY KEY)",
          "INSERT INTO su.konly VALUES (1)");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createNamespace(su, true);
      admin.importTable(su, e.storedTable("imp"), DECOUPLED, Map.of());
      admin.importTable(su, e.storedTable("konly"), DECOUPLED, Map.of());
      admin.createTable(
          su,
          "comp",
          TableMetadata.newBuilder()
              .addColumn("a", DataType.INT)
              .addColumn("b", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("b")
              .addClusteringKey("a")
              .build(),
          DECOUPLED);
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    TableInfo imp = catalog.lookup(List.of(), su, e.storedTable("imp"));
    String meta = name(engine, su, Objects.requireNonNull(imp.metadataTable()));
    String data = name(engine, su, imp.dataTable());
    String view = name(engine, su, imp.readView(engine));
    String id = engine.quote(imp.keyColumns().get(0));
    String x = engine.quote(imp.nonKeyColumns().get(0));
    try (Connection c = e.open()) {
      // ScalarDB's rollback over a row without metadata leaves an all-NULL metadata row (fact 2)
      e.execute(c, "INSERT INTO " + meta + " (" + id + ") VALUES (3)");
    }
    assertThat(imp.isSetUp()).isFalse();
    assertThat(viaDriver(e, "SELECT * FROM su.imp")).startsWith("refused 55000");

    List<Setup.Result> first = setup(e, catalog, Duration.ofMinutes(1));
    assertThat(first).hasSize(3).allMatch(Setup.Result::succeeded, "succeeded");
    assertThat(result(first, "imp").rowsStamped()).isEqualTo(3); // two missing rows, one NULL
    assertThat(result(first, "konly").rowsStamped()).isEqualTo(1);
    assertThat(result(first, "comp").rowsStamped()).isZero(); // created, not imported
    assertThat(viaDriver(e, "SELECT x FROM su.imp WHERE id = 1")).isEqualTo("read a"); // set up
    for (String table :
        List.of(e.storedTable("imp"), e.storedTable("konly"), engine.quote("comp"))) {
      TableInfo t = catalog.lookup(List.of(), su, table);
      assertThat(t.isSetUp()).as(table).isTrue();
      assertThat(
              objects(e, t)
                  .hasIndex(su, Objects.requireNonNull(t.metadataTable()), t.inflightIndex(engine)))
          .as(table)
          .isTrue();
    }
    try (Connection c = e.open()) {
      assertThat(
              rows(
                  c,
                  "SELECT "
                      + id
                      + " FROM "
                      + meta
                      + " WHERE "
                      + engine.quote("tx_id")
                      + " IS NULL"))
          .isEmpty();
      assertThat(rows(c, "SELECT " + id + " FROM " + meta)).hasSize(3);
      assertThat(rows(c, "SELECT " + x + " FROM " + view + " ORDER BY " + id))
          .containsExactly("a", "b", "c");
      TableInfo konly = catalog.lookup(List.of(), su, e.storedTable("konly"));
      assertThat(rows(c, "SELECT * FROM " + name(engine, su, konly.readView(engine)))).hasSize(1);
      TableInfo comp = catalog.lookup(List.of(), su, engine.quote("comp"));
      assertThat(rows(c, "SELECT * FROM " + name(engine, su, comp.readView(engine)))).isEmpty();
    }

    // again: nothing to create, nothing to backfill
    List<Setup.Result> again = setup(e, catalog, Duration.ofMinutes(1));
    assertThat(again).allMatch(Setup.Result::succeeded, "succeeded");
    assertThat(result(again, "imp").rowsStamped()).isZero();
    assertThat(result(again, "imp").actions()).noneMatch(a -> a.startsWith("created"));

    try (Connection c = e.open()) {
      String state = engine.quote("tx_state");
      String beforeId = engine.quote("before_tx_id");
      String beforeVersion = engine.quote("before_tx_version");
      String beforeX = engine.quote("before_" + imp.nonKeyColumns().get(0));
      e.execute(
          c,
          // 1: prepared by ScalarDB over a committed row: the before image shows
          "UPDATE " + data + " SET " + x + " = 'new' WHERE " + id + " = 1",
          "UPDATE "
              + meta
              + " SET "
              + state
              + " = 1, "
              + beforeId
              + " = 'b', "
              + beforeVersion
              + " = 1, "
              + beforeX
              + " = 'a' WHERE "
              + id
              + " = 1",
          // 2: committed by ScalarDB but not yet cleaned up (state 2): still the before image
          "UPDATE " + data + " SET " + x + " = 'new2' WHERE " + id + " = 2",
          "UPDATE "
              + meta
              + " SET "
              + state
              + " = 2, "
              + beforeId
              + " = 'b2', "
              + beforeVersion
              + " = 1, "
              + beforeX
              + " = 'b' WHERE "
              + id
              + " = 2",
          // 4: an in-flight insert: no committed image, hidden
          "INSERT INTO " + data + " VALUES (4, 'd')",
          "INSERT INTO "
              + meta
              + " ("
              + id
              + ", "
              + engine.quote("tx_id")
              + ", "
              + state
              + ", "
              + engine.quote("tx_version")
              + ") VALUES (4, 'i', 1, 1)",
          // 5: prepared over a row with NULL metadata: before_tx_version 0 marks its before image
          "INSERT INTO " + data + " VALUES (5, 'new5')",
          "INSERT INTO "
              + meta
              + " ("
              + id
              + ", "
              + engine.quote("tx_id")
              + ", "
              + state
              + ", "
              + engine.quote("tx_version")
              + ", "
              + beforeVersion
              + ", "
              + beforeX
              + ") VALUES (5, 'p', 1, 1, 0, 'e')");
      assertThat(rows(c, "SELECT " + x + " FROM " + view + " ORDER BY " + id))
          .containsExactly("a", "b", "c", "e");
    }
    // the backfill waits for row 5, which ScalarDB will settle, but not for the insert of row 4
    Setup.Result waiting = result(setup(e, catalog, Duration.ofSeconds(1)), "imp");
    assertThat((Throwable) waiting.error())
        .isInstanceOf(SQLTransientException.class)
        .hasMessageContaining(" 1 rows");

    List<Setup.Result> down;
    try (Connection c = e.open()) {
      down = Setup.teardown(catalog, c, t -> t.namespace().equals(su));
      e.execute(c, "DELETE FROM " + meta + " WHERE " + engine.quote("tx_state") + " IN (1, 2)");
    }
    assertThat(down).hasSize(3).allMatch(Setup.Result::succeeded, "succeeded");
    assertThat(catalog.lookup(List.of(), su, e.storedTable("imp")).isSetUp()).isFalse();
  }

  private static List<Setup.Result> setup(Engines e, Catalog catalog, Duration wait)
      throws SQLException {
    String su = e.storedSchema("su");
    try (Connection c = e.open()) {
      return Setup.run(catalog, c, t -> t.namespace().equals(su), wait, List.of());
    }
  }

  private static Setup.Result result(List<Setup.Result> results, String table) {
    return results.stream()
        .filter(r -> r.table().toLowerCase(java.util.Locale.ROOT).endsWith("." + table))
        .findFirst()
        .orElseThrow();
  }

  private static SetupObjects objects(Engines e, TableInfo t) throws SQLException {
    Engine engine = e.engine();
    try (Connection c = e.open()) {
      return engine.setupObjects(
          c,
          List.of(t.namespace()),
          List.of(t.readView(engine)),
          List.of(t.inflightIndex(engine)),
          engine.tableNamesCaseInsensitive(c));
    }
  }

  private static String name(Engine engine, String schema, String table) {
    return engine.quote(schema) + "." + engine.quote(table);
  }

  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC", justification = "test queries from constants")
  private static List<String> rows(Connection c, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  /** What the driver does with a statement: its first value, or the SQLState it refused with. */
  private static String viaDriver(Engines e, String sql) throws SQLException {
    Properties p = new Properties();
    try (Connection direct = e.open()) {
      p.setProperty("user", direct.getMetaData().getUserName().split("@", 2)[0]);
    }
    p.setProperty("password", e.password());
    // a catalog of its own, read afresh on every lookup
    p.setProperty("scalardbnative.catalogTtlMs", "0");
    String url = "jdbc:scalardb-native:" + e.url().substring("jdbc:".length());
    try (Connection c = DriverManager.getConnection(url, p);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? "read " + rs.getString(1) : "read nothing";
    } catch (SQLException ex) {
      return "refused " + ex.getSQLState() + " " + ex.getMessage();
    }
  }
}
