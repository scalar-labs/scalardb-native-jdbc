package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    try (Connection d = e.openDriver()) {
      assertThat(rows(d, "SELECT * FROM inl.imp ORDER BY id"))
          .as("the data columns only")
          .containsExactly("1/a", "2/b");
      // writes, and the stronger levels, come with milestones 10b and 10c
      assertThatThrownBy(() -> d.createStatement().executeUpdate("UPDATE inl.imp SET x = 'z'"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      assertThatThrownBy(() -> rows(d, "SELECT x FROM inl.imp"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      d.rollback();
    }
  }
}
