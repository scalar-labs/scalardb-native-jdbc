package com.scalar.db.nativejdbc.samples.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Result;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.samples.jdbc.ItemStore.Item;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The plain JDBC sample as its acceptance test, on every engine and both of ScalarDB's layouts: an
 * application's table imported into ScalarDB, read and written through the driver on a pool while
 * ScalarDB transactions write the same rows.
 */
class JdbcSampleIntegrationTest {

  /** Every engine with each layout: decoupled, and inline (ScalarDB's default). */
  static List<Arguments> layouts() {
    List<Arguments> out = new ArrayList<>();
    for (Engines e : Engines.selected()) {
      out.add(Arguments.of(e, false));
      out.add(Arguments.of(e, true));
    }
    return out;
  }

  @ParameterizedTest
  @MethodSource("layouts")
  void anApplication_besideScalarDb(Engines e, boolean inline) throws Exception {
    String schema = inline ? "sji" : "sjd";
    String ns = e.storedSchema(schema);
    String table = e.storedTable("item");
    try (Connection c = e.open()) {
      e.execute(c, e.createSchema(schema));
      if (e.engine() == Engine.SQLSERVER) {
        e.execute(c, "CREATE SEQUENCE " + schema + ".item_id START WITH 1");
      }
      e.execute(
          c,
          "CREATE TABLE "
              + schema
              + ".item (id "
              + key(e.engine(), schema)
              + " PRIMARY KEY, name VARCHAR(50), qty "
              + e.intType()
              + ", version "
              + e.intType()
              + ")");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.importTable(
          ns,
          table,
          inline ? Map.of() : Map.of("transaction-metadata-decoupling", "true"),
          Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    // ScalarDB addresses an imported decoupled table by its virtual table
    String scalarDbTable =
        inline
            ? table
            : Objects.requireNonNull(catalog.lookup(List.of(), ns, table).virtualTable());

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:scalardb-native:" + e.url().substring("jdbc:".length()));
    config.setUsername(e.username());
    config.setPassword(e.password());
    config.setMaximumPoolSize(4);
    // the table was imported moments ago, in this JVM: in place of the restart of the application
    // that the README asks for after adding a table (todo 298), no loaded catalog is trusted
    config.addDataSourceProperty("scalar.db.native_jdbc.catalog_ttl_millis", "0");
    try (HikariDataSource ds = new HikariDataSource(config);
        DistributedTransactionManager tm = e.scalarDb().getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = e.scalarDb().getTwoPhaseCommitTransactionManager()) {
      ItemStore store = new ItemStore(ds, schema + ".item");

      // the database generates the keys
      long apple = store.add("apple", 10);
      long pear = store.add("pear", 0);
      assertThat(pear).isNotEqualTo(apple);
      Item read = store.find(apple).orElseThrow();
      assertThat(read.name).isEqualTo("apple");
      assertThat(read.qty).isEqualTo(10);

      // optimistic locking: a second write at the version read loses
      assertThat(store.setQty(read, 9)).isTrue();
      assertThat(store.setQty(read, 8)).isFalse();
      assertThat(store.find(apple).orElseThrow().qty).isEqualTo(9);

      // a transaction, and one that fails and changes nothing
      store.transfer(apple, pear, 4);
      assertThat(store.find(apple).orElseThrow().qty).isEqualTo(5);
      assertThat(store.find(pear).orElseThrow().qty).isEqualTo(4);
      // the failing transfer wrote both rows before its check: rolled back whole
      assertThatThrownBy(() -> store.transfer(apple, pear, 100)).isInstanceOf(SQLException.class);
      assertThat(store.find(apple).orElseThrow().qty).isEqualTo(5);
      assertThat(store.find(pear).orElseThrow().qty).isEqualTo(4);

      // ScalarDB's transaction commits between the application's read and its write: the
      // application's version check sees it, as it would another application's
      read = store.find(apple).orElseThrow();
      DistributedTransaction tx = tm.begin();
      Result row =
          tx.get(
                  Get.newBuilder()
                      .namespace(ns)
                      .table(scalarDbTable)
                      .partitionKey(key(e, apple))
                      .build())
              .orElseThrow();
      tx.update(
          Update.newBuilder()
              .namespace(ns)
              .table(scalarDbTable)
              .partitionKey(key(e, apple))
              .value(intColumn(e, "qty", 1))
              .value(intColumn(e, "version", intOf(e, row, "version") + 1))
              .build());
      tx.commit();
      assertThat(store.setQty(read, 2)).isFalse();
      assertThat(store.find(apple).orElseThrow().qty).isEqualTo(1);

      // a ScalarDB transaction holds the row prepared: the application's write is a retryable
      // conflict, and the row stays as ScalarDB's transaction leaves it
      TwoPhaseCommitTransaction held = tpc.begin();
      try {
        held.update(
            Update.newBuilder()
                .namespace(ns)
                .table(scalarDbTable)
                .partitionKey(key(e, pear))
                .value(intColumn(e, "qty", 40))
                .build());
        held.prepare();
        Item pearNow = store.find(pear).orElseThrow();
        assertThat(pearNow.qty).as("the committed value, not the prepared one").isEqualTo(4);
        assertThatThrownBy(() -> store.setQty(pearNow, 5))
            .isInstanceOf(SQLException.class)
            .extracting(ex -> ((SQLException) ex).getSQLState())
            .isEqualTo("40001");
      } finally {
        held.rollback();
      }
      assertThat(store.find(pear).orElseThrow().qty).isEqualTo(4);

      assertThat(store.remove(pear)).isTrue();
      assertThat(store.find(pear)).isEmpty();
    }
  }

  /**
   * An integer key the database generates. On SQL Server from a sequence, not an IDENTITY column,
   * which ScalarDB cannot write: its prepare inserts the key explicitly.
   */
  private static String key(Engine engine, String schema) {
    return switch (engine) {
      case POSTGRESQL -> "INT GENERATED BY DEFAULT AS IDENTITY";
      case SQLSERVER -> "INT DEFAULT NEXT VALUE FOR " + schema + ".item_id";
      case ORACLE -> "NUMBER(10) GENERATED BY DEFAULT AS IDENTITY";
      default -> "INT AUTO_INCREMENT";
    };
  }

  // ScalarDB imports Oracle's NUMBER as BIGINT, and stores Oracle's names upper case

  private static Key key(Engines e, long id) {
    return e.engine() == Engine.ORACLE ? Key.ofBigInt("ID", id) : Key.ofInt("id", (int) id);
  }

  private static Column<?> intColumn(Engines e, String name, int value) {
    return e.engine() == Engine.ORACLE
        ? BigIntColumn.of(name.toUpperCase(Locale.ROOT), value)
        : IntColumn.of(name, value);
  }

  private static int intOf(Engines e, Result r, String name) {
    return e.engine() == Engine.ORACLE
        ? (int) r.getBigInt(name.toUpperCase(Locale.ROOT))
        : r.getInt(name);
  }
}
