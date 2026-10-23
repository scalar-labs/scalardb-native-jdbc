package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.N;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.id;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.intType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import java.sql.Connection;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Closing a connection whose transaction the driver aborted commits nothing, on every engine:
 * Oracle's driver commits an open transaction at {@code close()}, which would commit what the
 * driver refuses to (design 4.4, "After an abort decided by the driver").
 */
class CloseIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void close_afterAnAbort_commitsNothing(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("cl"), true);
      admin.dropTable(N("cl"), N("t"), true);
      admin.createTable(
          N("cl"),
          N("t"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("x"), DataType.TEXT)
              .addPartitionKey(N("id"))
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
    }
    TransactionFactory factory = e.scalarDb();
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int id = 1; id <= 2; id++) {
        tx.insert(
            Insert.newBuilder()
                .namespace(N("cl"))
                .table(N("t"))
                .partitionKey(id(id))
                .textValue(N("x"), "a")
                .build());
      }
      tx.commit();
    }
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals(N("cl")),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    try (TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      TwoPhaseCommitTransaction held = tpc.begin();
      held.update(
          Update.newBuilder()
              .namespace(N("cl"))
              .table(N("t"))
              .partitionKey(id(1))
              .textValue(N("x"), "b")
              .build());
      held.prepare();
      Connection d = e.openDriver();
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      try (Statement st = d.createStatement()) {
        assertThat(st.executeUpdate("UPDATE cl.t SET x = 'y' WHERE id = 2")).isEqualTo(1);
        // a row in flight: the driver aborts the transaction (design section 6)
        assertThatThrownBy(() -> st.executeUpdate("UPDATE cl.t SET x = 'z' WHERE id = 1"))
            .isInstanceOf(SQLTransactionRollbackException.class);
      }
      // what an application's try-with-resources does when the abort propagates
      d.close();
      held.rollback();
    }
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      assertThat(
              tx.get(Get.newBuilder().namespace(N("cl")).table(N("t")).partitionKey(id(2)).build())
                  .orElseThrow()
                  .getText(N("x")))
          .as("the aborted transaction's earlier write, after close")
          .isEqualTo("a");
      tx.commit();
    }
  }
}
