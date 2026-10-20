package com.scalar.db.nativejdbc.samples.springdata;

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
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.relational.core.dialect.Dialect;
import org.springframework.data.relational.core.mapping.NamingStrategy;

/**
 * The Spring Data JDBC sample as its acceptance test, on every engine and both of ScalarDB's
 * layouts: repositories, a {@code @Transactional} service and optimistic locking by
 * {@code @Version}, through the driver on a pool, while ScalarDB transactions write the same rows.
 */
class SpringDataSampleIntegrationTest {

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
    String schema = inline ? "ssi" : "ssd";
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
    config.addDataSourceProperty("scalardbnative.catalogTtlMs", "0");
    try (HikariDataSource ds = new HikariDataSource(config);
        AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();
        DistributedTransactionManager tm = e.scalarDb().getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = e.scalarDb().getTwoPhaseCommitTransactionManager()) {
      spring.registerBean(DataSource.class, () -> ds);
      spring.registerBean(
          NamingStrategy.class,
          () ->
              new NamingStrategy() {
                @Override
                public String getSchema() {
                  return schema;
                }
              });
      spring.register(SampleConfig.class);
      spring.refresh();
      ItemRepository items = spring.getBean(ItemRepository.class);
      ItemService service = spring.getBean(ItemService.class);

      // the dialect resolved from the metadata the driver forwards: the vendor's own
      assertThat(spring.getBean(Dialect.class).getClass().getSimpleName().toLowerCase(Locale.ROOT))
          .contains(
              switch (e.engine()) {
                case POSTGRESQL -> "postgres";
                case MYSQL -> "mysql";
                case MARIADB -> "maria";
                case SQLSERVER -> "sqlserver";
                case ORACLE -> "oracle";
              });

      // keys generated by the database, a derived query
      Item apple = items.save(new Item("apple", 10));
      Item pear = items.save(new Item("pear", 0));
      long appleId = Objects.requireNonNull(apple.getId());
      long pearId = Objects.requireNonNull(pear.getId());
      assertThat(pearId).isNotEqualTo(appleId);
      assertThat(items.findByName("apple")).extracting(Item::getQty).containsExactly(10);

      // @Version: a stale copy loses
      Item first = items.findById(appleId).orElseThrow();
      Item second = items.findById(appleId).orElseThrow();
      first.setQty(9);
      items.save(first);
      second.setQty(8);
      assertThatThrownBy(() -> items.save(second))
          .isInstanceOf(OptimisticLockingFailureException.class);
      assertThat(items.findById(appleId).orElseThrow().getQty()).isEqualTo(9);

      // @Transactional: committed, and rolled back whole
      service.transfer(appleId, pearId, 4);
      assertThat(items.findById(appleId).orElseThrow().getQty()).isEqualTo(5);
      assertThat(items.findById(pearId).orElseThrow().getQty()).isEqualTo(4);
      assertThatThrownBy(() -> service.transfer(appleId, pearId, 100))
          .isInstanceOf(IllegalStateException.class);
      assertThat(items.findById(appleId).orElseThrow().getQty()).isEqualTo(5);
      assertThat(items.findById(pearId).orElseThrow().getQty()).isEqualTo(4);

      // @Lock and SERIALIZABLE: Spring Data qualifies the columns by schema and table there too
      service.restock(appleId, 1);
      service.restock(appleId, -1);
      if (e.engine() != Engine.ORACLE) { // Oracle's SERIALIZABLE is refused (design 5)
        assertThat(service.total()).isEqualTo(9);
        // read-only too, but on MySQL, whose Connector/J refuses the savepoints (todo 301)
        if (e.engine() == Engine.MYSQL) {
          assertThatThrownBy(service::totalReadOnly).hasMessageContaining("read-only transaction");
        } else {
          assertThat(service.totalReadOnly()).isEqualTo(9);
        }
      }

      // a ScalarDB transaction commits between the application's read and its write: @Version
      // sees it, as it would another application's
      Item stale = items.findById(appleId).orElseThrow();
      DistributedTransaction tx = tm.begin();
      Result row =
          tx.get(
                  Get.newBuilder()
                      .namespace(ns)
                      .table(scalarDbTable)
                      .partitionKey(key(e, appleId))
                      .build())
              .orElseThrow();
      tx.update(
          Update.newBuilder()
              .namespace(ns)
              .table(scalarDbTable)
              .partitionKey(key(e, appleId))
              .value(intColumn(e, "qty", 1))
              .value(intColumn(e, "version", intOf(e, row, "version") + 1))
              .build());
      tx.commit();
      stale.setQty(2);
      assertThatThrownBy(() -> items.save(stale))
          .isInstanceOf(OptimisticLockingFailureException.class);
      assertThat(items.findById(appleId).orElseThrow().getQty()).isEqualTo(1);

      // a ScalarDB transaction holds the row prepared: the application reads the committed value
      // and its write is a transient failure, SQLState 40001, which the caller retries
      TwoPhaseCommitTransaction held = tpc.begin();
      try {
        held.update(
            Update.newBuilder()
                .namespace(ns)
                .table(scalarDbTable)
                .partitionKey(key(e, pearId))
                .value(intColumn(e, "qty", 40))
                .build());
        held.prepare();
        Item pearNow = items.findById(pearId).orElseThrow();
        assertThat(pearNow.getQty()).as("the committed value").isEqualTo(4);
        pearNow.setQty(5);
        assertThatThrownBy(() -> items.save(pearNow))
            .isInstanceOf(TransientDataAccessException.class)
            .satisfies(
                ex -> {
                  Throwable c = ex;
                  while (c != null
                      && !(c instanceof SQLException
                          && "40001".equals(((SQLException) c).getSQLState()))) {
                    c = c.getCause();
                  }
                  assertThat(c).as("a 40001 among the causes").isNotNull();
                });
      } finally {
        held.rollback();
      }
      assertThat(items.findById(pearId).orElseThrow().getQty()).isEqualTo(4);

      items.deleteById(pearId);
      assertThat(items.findById(pearId)).isEmpty();
      assertThat(items.count()).isEqualTo(1);
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
