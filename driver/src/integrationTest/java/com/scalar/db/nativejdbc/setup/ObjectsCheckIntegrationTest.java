package com.scalar.db.nativejdbc.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The check reports the database's own objects that reach a ScalarDB table around the driver (todo
 * 323): a view over it, a trigger elsewhere that writes it, a foreign key on it that cascades; and
 * not the driver's read views or ScalarDB's virtual tables.
 */
@SuppressFBWarnings(
    value = "SQL_INJECTION_JDBC",
    justification = "statements written in the test sources")
class ObjectsCheckIntegrationTest {

  static Stream<Engines> engines() {
    return Engines.selected().stream().filter(e -> e != Engines.MYSQL_LOWER_CASE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("engines")
  void objects_reachingAScalarDbTable_areReported_andTheDriversOwnAreNot(Engines e)
      throws Exception {
    String ns = e.storedSchema("oc");
    // a run before left the view, which depends on orders_oc, and the parent its foreign key names
    dropIfThere(e, "VIEW oc.recent_oc");
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      for (String table : List.of("orders_oc", "items_oc")) {
        admin.dropTable(ns, e.storedTable(table), true);
        admin.createTable(
            ns,
            e.storedTable(table),
            TableMetadata.newBuilder()
                .addColumn(e.storedColumn("id"), DataType.INT)
                .addColumn(e.storedColumn("p"), DataType.INT)
                .addColumn(e.storedColumn("v"), DataType.TEXT)
                .addPartitionKey(e.storedColumn("id"))
                .build(),
            // orders_oc inline, a physical table the objects can name; items_oc decoupled
            table.equals("orders_oc")
                ? Map.of()
                : Map.of("transaction-metadata-decoupling", "true"));
      }
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    dropIfThere(e, "TABLE oc.audit_oc");
    dropIfThere(e, "TABLE oc.parent_oc");
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "setup succeeded");
      // the read views the setup made, and ScalarDB's virtual table of items_oc, are not reported;
      // the objects other test classes leave in the database are not this test's
      catalog.invalidate();
      assertThat(Check.objects(catalog, c))
          .noneMatch(o -> o.toLowerCase(Locale.ROOT).contains("_oc"));

      e.execute(
          c,
          "CREATE VIEW oc.recent_oc AS SELECT id, v FROM oc.orders_oc",
          "CREATE TABLE oc.audit_oc (id " + e.intType() + " PRIMARY KEY)",
          "CREATE TABLE oc.parent_oc (id " + e.intType() + " PRIMARY KEY)",
          "ALTER TABLE oc.orders_oc ADD CONSTRAINT fk_oc FOREIGN KEY (p)"
              + " REFERENCES oc.parent_oc (id) ON DELETE CASCADE");
      for (String sql : trigger(e)) {
        e.execute(c, sql);
      }
      catalog.invalidate();
      String found = String.join("\n", Check.objects(catalog, c)).toLowerCase(Locale.ROOT);
      assertThat(found)
          .contains("view oc.recent_oc")
          .contains("trigger oc.tr_oc")
          .contains("foreign key fk_oc on scalardb table oc.orders_oc is on delete cascade")
          .doesNotContain("rc_")
          .doesNotContain("items_oc");
    }
  }

  private static void dropIfThere(Engines e, String object) throws SQLException {
    try (Connection c = e.open()) {
      e.execute(c, "DROP " + object);
    } catch (SQLException absent) {
      // the first run
    }
  }

  /** A trigger on oc.audit_oc that writes oc.orders_oc, in the engine's syntax. */
  private static List<String> trigger(Engines e) {
    String update = "UPDATE oc.orders_oc SET v = 'x' WHERE id = 1";
    return switch (e.engine()) {
      case POSTGRESQL ->
          List.of(
              "CREATE OR REPLACE FUNCTION oc.touch_oc() RETURNS trigger LANGUAGE plpgsql AS"
                  + " $$ BEGIN "
                  + update
                  + "; RETURN NEW; END $$",
              "CREATE TRIGGER tr_oc AFTER INSERT ON oc.audit_oc FOR EACH ROW"
                  + " EXECUTE FUNCTION oc.touch_oc()");
      case MYSQL, MARIADB ->
          List.of("CREATE TRIGGER oc.tr_oc AFTER INSERT ON oc.audit_oc FOR EACH ROW " + update);
      case SQLSERVER -> List.of("CREATE TRIGGER oc.tr_oc ON oc.audit_oc AFTER INSERT AS " + update);
      case ORACLE ->
          List.of(
              "CREATE TRIGGER oc.tr_oc AFTER INSERT ON oc.audit_oc FOR EACH ROW BEGIN "
                  + update
                  + "; END;");
    };
  }
}
