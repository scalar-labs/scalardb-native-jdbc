package com.scalar.db.nativejdbc.setup;

import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ScalarDB alone, without the driver: the defect behind the backfill of an imported decoupled table
 * (design section 1, fact 2). A rollback over a row with no metadata row leaves the metadata row
 * with a NULL {@code tx_id}; ScalarDB's next prepare of that row is turned into an insert if not
 * exists on the metadata table and fails on every attempt. The test passes while the defect stands.
 */
class ScalarDbNullMetadataIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void rolledBackUntouchedRow_canNeverBePreparedAgain(Engines e) throws Exception {
    Engine engine = e.engine();
    String ns = e.storedSchema("nm");
    String id = e.storedColumn("id");
    String x = e.storedColumn("x");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("nm"),
          "CREATE TABLE nm.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO nm.imp VALUES (1, 'a')",
          "INSERT INTO nm.imp VALUES (2, 'b')");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      admin.importTable(ns, e.storedTable("imp"), DECOUPLED, Map.of());
    }
    // ScalarDB's name for an imported decoupled table is its LEFT OUTER virtual table
    String table = e.storedTable("imp") + "_scalardb";
    String meta = engine.quote(ns) + "." + engine.quote(e.storedTable("imp") + "_tx_metadata");
    String metaRow =
        "SELECT "
            + engine.quote("tx_id")
            + ", "
            + engine.quote("tx_state")
            + ", "
            + engine.quote("tx_version")
            + " FROM "
            + meta
            + " WHERE "
            + engine.quote(id)
            + " = 1";

    TransactionFactory scalarDb = e.scalarDb();
    try (Connection c = e.open()) {
      // right after the import the metadata table is empty
      assertThat(rows(c, "SELECT COUNT(*) FROM " + meta)).containsExactly("0");

      // 1. prepare an update of row 1, which has no metadata row, then roll it back
      try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager()) {
        TwoPhaseCommitTransaction t1 = tpc.begin();
        t1.get(get(ns, table, id, 1));
        t1.update(update(ns, table, id, x, 1, "t1"));
        t1.prepare();
        assertThat(rows(c, "SELECT COUNT(*) FROM " + meta))
            .as("the prepare's metadata row")
            .containsExactly("1");
        t1.rollback();
      }

      // 2. the rollback keeps the metadata row, with NULL tx_id and tx_state
      List<String> left = rows(c, metaRow);
      assertThat(left).as("the metadata row after the rollback").hasSize(1);
      assertThat(left.get(0)).as("tx_id, tx_state, tx_version").startsWith("null/null/");
      assertThat(rows(c, "SELECT x FROM nm.imp WHERE id = 1"))
          .as("the data restored")
          .containsExactly("a");

      try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
        // 3. every later update of row 1 through ScalarDB fails
        List<String> failures = new ArrayList<>();
        for (int attempt = 1; attempt <= 3; attempt++) {
          failures.add(updateOutcome(tm, ns, table, id, x, 1, "t2-" + attempt));
        }
        System.out.println("ScalarDB updates of row 1 after the rollback: " + failures);
        assertThat(failures).as("each retry").allMatch(f -> !f.equals("committed"));
        assertThat(rows(c, "SELECT x FROM nm.imp WHERE id = 1")).containsExactly("a");

        // control: row 2, never touched, updates fine
        assertThat(updateOutcome(tm, ns, table, id, x, 2, "t3")).isEqualTo("committed");

        // 4. removing the all-NULL metadata row by hand makes row 1 writable again
        e.execute(c, "DELETE FROM " + meta + " WHERE " + engine.quote(id) + " = 1");
        assertThat(updateOutcome(tm, ns, table, id, x, 1, "t4")).isEqualTo("committed");
        assertThat(rows(c, "SELECT x FROM nm.imp WHERE id = 1")).containsExactly("t4");
      }
    } finally {
      try (Connection c = e.open()) {
        e.dropSchemas(c, ns);
      }
    }
  }

  private static String updateOutcome(
      DistributedTransactionManager tm,
      String ns,
      String table,
      String id,
      String x,
      int key,
      String value) {
    DistributedTransaction tx = null;
    try {
      tx = tm.begin();
      tx.get(get(ns, table, id, key));
      tx.update(update(ns, table, id, x, key, value));
      tx.commit();
      return "committed";
    } catch (TransactionException ex) {
      if (tx != null) {
        try {
          tx.rollback();
        } catch (TransactionException ignored) {
          // the transaction is already aborted
        }
      }
      return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }
  }

  private static Get get(String ns, String table, String id, int key) {
    return Get.newBuilder().namespace(ns).table(table).partitionKey(Key.ofInt(id, key)).build();
  }

  private static Update update(
      String ns, String table, String id, String x, int key, String value) {
    return Update.newBuilder()
        .namespace(ns)
        .table(table)
        .partitionKey(Key.ofInt(id, key))
        .textValue(x, value)
        .build();
  }
}
