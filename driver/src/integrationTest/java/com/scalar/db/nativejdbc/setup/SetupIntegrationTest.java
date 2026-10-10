package com.scalar.db.nativejdbc.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 2 on every engine: a decoupled table is refused until the setup has run; the setup
 * creates the read view and the in-flight index, backfills an imported table, and changes nothing
 * when run again; the view shows the committed image of each row.
 */
class SetupIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void setup_makesTablesUsable_backfills_andIsIdempotent(Engines e) throws Exception {
    Engine engine = e.engine();
    String su = e.storedSchema("su");
    String imp = e.storedTable("imp");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("su"),
          "CREATE TABLE su.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO su.imp VALUES (1, 'a')",
          "INSERT INTO su.imp VALUES (2, 'b')",
          "INSERT INTO su.imp VALUES (3, 'c')");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createNamespace(su, true);
      admin.importTable(su, imp, DECOUPLED, Map.of());
      admin.createTable(
          su,
          "made",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .build(),
          DECOUPLED);
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    TableInfo t = catalog.lookup(List.of(), su, imp);
    String data = name(engine, su, t.dataTable());
    String meta = name(engine, su, Objects.requireNonNull(t.metadataTable()));
    String id = engine.quote(t.keyColumns().get(0));
    String x = engine.quote(t.nonKeyColumns().get(0));
    try (Connection c = e.open()) {
      // ScalarDB's rollback over a row without metadata leaves an all-NULL metadata row (fact 2)
      e.execute(c, "INSERT INTO " + meta + " (" + id + ") VALUES (3)");
    }
    assertThat(t.isSetUp()).isFalse();
    assertThat(viaDriver(e, "SELECT * FROM su.imp")).startsWith("refused 55000");

    List<String> report;
    try (Connection c = e.open()) {
      report = Setup.run(catalog, c);
    }
    assertThat(report)
        .anyMatch(line -> line.contains(imp + ": read view") && line.contains("3 rows"));
    assertThat(viaDriver(e, "SELECT * FROM su.imp")).startsWith("refused 0AN01"); // set up
    assertThat(catalog.lookup(List.of(), su, imp).isSetUp()).isTrue();
    TableInfo made = catalog.lookup(List.of(), su, engine.quote("made"));
    assertThat(made.isScalarDb()).isTrue();
    assertThat(made.isSetUp()).isTrue();
    try (Connection c = e.open()) {
      String txId = engine.quote("tx_id");
      assertThat(rows(c, "SELECT " + id + " FROM " + meta + " WHERE " + txId + " IS NULL"))
          .isEmpty();
      assertThat(rows(c, "SELECT " + id + " FROM " + meta)).hasSize(3);
      String view = name(engine, su, t.readView(engine));
      assertThat(rows(c, "SELECT " + x + " FROM " + view + " ORDER BY " + id))
          .containsExactly("a", "b", "c");
      assertThat(
              rows(
                  c,
                  "SELECT * FROM "
                      + name(
                          engine,
                          su,
                          catalog.lookup(List.of(), su, engine.quote("made")).readView(engine))))
          .isEmpty();
    }

    // again: nothing to backfill, nothing to create
    List<String> again;
    try (Connection c = e.open()) {
      again = Setup.run(catalog, c);
    }
    assertThat(again)
        .anyMatch(line -> line.contains(imp + ": read view") && line.contains(" 0 rows"));
    try (Connection c = e.open()) {
      assertThat(rows(c, "SELECT " + id + " FROM " + meta)).hasSize(3);

      // the view shows the committed image: the before image of a prepared row, not an in-flight
      // insert
      e.execute(
          c,
          "UPDATE " + data + " SET " + x + " = 'new' WHERE " + id + " = 1",
          "UPDATE "
              + meta
              + " SET "
              + engine.quote("tx_state")
              + " = 1, "
              + engine.quote("before_tx_id")
              + " = 'b', "
              + engine.quote("before_tx_version")
              + " = 1, "
              + engine.quote("before_" + t.nonKeyColumns().get(0))
              + " = 'a' WHERE "
              + id
              + " = 1",
          "INSERT INTO " + data + " VALUES (4, 'd')",
          "INSERT INTO "
              + meta
              + " ("
              + id
              + ", "
              + engine.quote("tx_id")
              + ", "
              + engine.quote("tx_state")
              + ", "
              + engine.quote("tx_version")
              + ") VALUES (4, 'i', 1, 1)");
      assertThat(
              rows(
                  c,
                  "SELECT "
                      + x
                      + " FROM "
                      + name(engine, su, t.readView(engine))
                      + " ORDER BY "
                      + id))
          .containsExactly("a", "b", "c");
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
    // a catalog of its own, so the state this test changes is read afresh
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
