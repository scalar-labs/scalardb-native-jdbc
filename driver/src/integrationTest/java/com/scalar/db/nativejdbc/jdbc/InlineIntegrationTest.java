package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Result;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ScalarDB's inline layout, the metadata in the row, as milestone 10a builds it: a table imported
 * without decoupling holds its rows with NULL metadata, which the setup stamps as the backfill of a
 * decoupled table does, and the driver reads at READ COMMITTED. The reads with rows in flight run
 * in {@code ReadIntegrationTest}, by layout.
 */
class InlineIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void anImportedTable_isBackfilled_readAtReadCommitted_andRefusedElse(Engines e) throws Exception {
    String ns = e.storedSchema("inl");
    String nullTxId =
        "SELECT COUNT(*) FROM inl.imp WHERE " + e.engine().quote("tx_id") + " IS NULL";
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("inl"),
          "CREATE TABLE inl.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO inl.imp VALUES (1, 'a')",
          "INSERT INTO inl.imp VALUES (2, 'b')");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.importTable(ns, e.storedTable("imp"), Map.of(), Map.of());
    }
    // ScalarDB added the metadata columns, NULL in every row
    try (Connection c = e.open()) {
      assertThat(rows(c, nullTxId)).containsExactly("2");
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      List<Setup.Result> results =
          Setup.run(catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(1), List.of());
      assertThat(results).hasSize(1).allMatch(Setup.Result::succeeded, "succeeded");
      assertThat(results.get(0).rowsStamped()).isEqualTo(2);
      assertThat(rows(c, nullTxId)).containsExactly("0");
    }
    try (Connection d = e.openDriver();
        Statement st = d.createStatement()) {
      assertThat(rows(d, "SELECT * FROM inl.imp ORDER BY id"))
          .as("the data columns only")
          .containsExactly("1/a", "2/b");
      // writes (10b): an INSERT names its columns, and none names a metadata column
      assertThatThrownBy(() -> st.executeUpdate("INSERT INTO inl.imp VALUES (3, 'c')"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(() -> st.executeUpdate("UPDATE inl.imp SET tx_state = 3"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThat(st.executeUpdate("UPDATE inl.imp SET x = 'z' WHERE id = 1")).isEqualTo(1);
      assertThat(st.executeUpdate("DELETE FROM inl.imp WHERE id = 2")).isEqualTo(1);
      try (Connection v = e.open()) {
        assertThat(
                rows(
                    v,
                    "SELECT id, x, "
                        + e.engine().quote("tx_version")
                        + " FROM inl.imp ORDER BY id"))
            .as("the update stamped at the next version, the delete took the row")
            .containsExactly("1/z/2");
      }
      // the stronger levels come with milestone 10c
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      assertThatThrownBy(() -> rows(d, "SELECT x FROM inl.imp"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      d.rollback();
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aRowWithNullMetadata_putInFlightByScalarDb_readsAsItsBeforeImage(Engines e)
      throws Exception {
    ScalarDbNames.on(e);
    String ns = e.storedSchema("inl2");
    String table = e.storedTable("imp");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("inl2"),
          "CREATE TABLE inl2.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO inl2.imp VALUES (1, 'a')");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.importTable(ns, table, Map.of(), Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
      // rows another client writes after the setup, their metadata NULL; on SQL Server from a
      // session without QUOTED_IDENTIFIER, which a filtered index would refuse (todo 289)
      if (e.engine() == Engine.SQLSERVER) {
        e.execute(c, "SET QUOTED_IDENTIFIER OFF");
      }
      e.execute(
          c,
          "INSERT INTO inl2.imp (id, x) VALUES (2, 'b')",
          "INSERT INTO inl2.imp (id, x) VALUES (3, 'c')");
    }
    // ScalarDB updates one and deletes the other, prepared: their before images are found
    // through before_tx_version = 0, which ScalarDB writes over a NULL tx_version (todo 291)
    try (TwoPhaseCommitTransactionManager tpc =
        e.scalarDb().getTwoPhaseCommitTransactionManager()) {
      TwoPhaseCommitTransaction held = tpc.begin();
      try {
        held.update(
            Update.newBuilder()
                .namespace(ns)
                .table(table)
                .partitionKey(ScalarDbNames.key("id", 2))
                .textValue(ScalarDbNames.N("x"), "B")
                .build());
        held.delete(
            Delete.newBuilder()
                .namespace(ns)
                .table(table)
                .partitionKey(ScalarDbNames.key("id", 3))
                .build());
        held.prepare();
        try (Connection d = e.openDriver();
            Statement st = d.createStatement()) {
          assertThat(rows(d, "SELECT x FROM inl2.imp ORDER BY id")).containsExactly("a", "b", "c");
        }
      } finally {
        held.rollback();
      }
    }
    // the driver's writes of such rows (10b): an UPDATE stamps version 1, a DELETE takes the row,
    // and ScalarDB reads what they left
    try (Connection d = e.openDriver();
        Statement st = d.createStatement()) {
      assertThat(st.executeUpdate("UPDATE inl2.imp SET x = 'u' WHERE id = 2")).isEqualTo(1);
      assertThat(st.executeUpdate("DELETE FROM inl2.imp WHERE id = 3")).isEqualTo(1);
    }
    try (Connection v = e.open()) {
      assertThat(
              rows(
                  v,
                  "SELECT id, "
                      + e.engine().quote("tx_state")
                      + ", "
                      + e.engine().quote("tx_version")
                      + " FROM inl2.imp WHERE id > 1 ORDER BY id"))
          .containsExactly("2/3/1");
    }
    try (DistributedTransactionManager tm = e.scalarDb().getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      Optional<Result> two =
          tx.get(
              Get.newBuilder()
                  .namespace(ns)
                  .table(table)
                  .partitionKey(ScalarDbNames.key("id", 2))
                  .build());
      Optional<Result> three =
          tx.get(
              Get.newBuilder()
                  .namespace(ns)
                  .table(table)
                  .partitionKey(ScalarDbNames.key("id", 3))
                  .build());
      tx.commit();
      assertThat(two.map(r -> r.getText(ScalarDbNames.N("x")))).contains("u");
      assertThat(three).isEmpty();
    }
  }
}
