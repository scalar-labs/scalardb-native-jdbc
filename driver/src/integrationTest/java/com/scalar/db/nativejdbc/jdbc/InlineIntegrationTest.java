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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ScalarDB's inline layout, the metadata in the row, as milestone 10a builds it: a table imported
 * without decoupling holds its rows with NULL metadata, which the setup stamps as the backfill of a
 * decoupled table does, and the driver reads at READ COMMITTED. The reads with rows in flight run
 * in {@code ReadIntegrationTest}, by layout.
 */
@SuppressFBWarnings(
    value = "SQL_INJECTION_JDBC",
    justification = "fixture DDL and queries written in the test sources")
class InlineIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void anImportedTable_isBackfilled_andReadAndWritten(Engines e) throws Exception {
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
      // writes (10b): none names a metadata column; an INSERT that lists no columns gives its
      // values to the user's, ScalarDB's metadata columns after them (todo 329), and is refused
      // where they would reach one
      assertThat(st.executeUpdate("INSERT INTO inl.imp VALUES (3, 'c')")).isEqualTo(1);
      try (PreparedStatement ps = d.prepareStatement("INSERT INTO inl.imp VALUES (?, ?)")) {
        ps.setInt(1, 4);
        ps.setString(2, "d");
        assertThat(ps.executeUpdate()).isEqualTo(1);
      }
      assertThatThrownBy(() -> st.executeUpdate("INSERT INTO inl.imp VALUES (5, 'e', 'x')"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      assertThatThrownBy(() -> st.executeUpdate("UPDATE inl.imp SET tx_state = 3"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      // nor reads one, in a SET value, a RETURNING item or the generated keys asked (todo 293)
      assertThatThrownBy(() -> st.executeUpdate("UPDATE inl.imp SET x = tx_id WHERE id = 1"))
          .isInstanceOf(SQLFeatureNotSupportedException.class);
      if (e.engine() == Engine.POSTGRESQL || e.engine() == Engine.MARIADB) {
        assertThatThrownBy(
                () -> rows(d, "INSERT INTO inl.imp (id, x) VALUES (8, 'r') RETURNING tx_id"))
            .isInstanceOf(SQLFeatureNotSupportedException.class);
      }
      assertThatThrownBy(
              () -> {
                try (PreparedStatement ps =
                    d.prepareStatement(
                        "INSERT INTO inl.imp (id, x) VALUES (9, 'k')", new String[] {"tx_id"})) {
                  ps.executeUpdate();
                }
              })
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
            .containsExactly("1/z/2", "3/c/1", "4/d/1");
      }
      // and at the stronger levels, a locking read included (10c)
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      assertThat(rows(d, "SELECT x FROM inl.imp WHERE id = 1 FOR UPDATE")).containsExactly("z");
      // read whole, the user's columns alone, as the vendor's SELECT * would give them without
      // the metadata columns, a q.* too (design 4.3 "The locking read on Oracle", todo 334)
      for (String items : List.of("*", "imp.*", "i.*")) {
        String alias = items.equals("i.*") ? " i" : "";
        try (Statement lock = d.createStatement();
            ResultSet rs =
                lock.executeQuery(
                    "SELECT " + items + " FROM inl.imp" + alias + " WHERE id = 1 FOR UPDATE")) {
          assertThat(rs.getMetaData().getColumnCount()).as(items).isEqualTo(2);
          assertThat(rs.next()).isTrue();
          assertThat(rs.getString(2)).isEqualTo("z");
          assertThat(rs.next()).isFalse();
        }
      }
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

  @ParameterizedTest
  @MethodSource("engines")
  void aPredicateDelete_racingAnUpdate_abortsOrSkipsAsTheRowNowMatches(Engines e) throws Exception {
    String ns = e.storedSchema("inl3");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("inl3"),
          "CREATE TABLE inl3.t (id " + e.intType() + " PRIMARY KEY, x " + e.intType() + ")",
          "INSERT INTO inl3.t VALUES (1, 1)",
          "INSERT INTO inl3.t VALUES (2, 1)");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.importTable(ns, e.storedTable("t"), Map.of(), Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection writer = e.openDriver();
        Connection deleter = e.openDriver()) {
      writer.setAutoCommit(false);
      deleter.setAutoCommit(false);
      // another writer changes row 1 so that it still matches, row 2 so that it no longer does,
      // while the DELETE waits on their locks (todo 292)
      for (int[] change : new int[][] {{1, 2}, {2, 0}}) {
        try (Statement st = writer.createStatement()) {
          st.executeUpdate("UPDATE inl3.t SET x = " + change[1] + " WHERE id = " + change[0]);
        }
        Future<Integer> delete =
            pool.submit(
                () -> {
                  try (Statement st = deleter.createStatement()) {
                    return st.executeUpdate("DELETE FROM inl3.t WHERE x > 0 AND id = " + change[0]);
                  }
                });
        Thread.sleep(500);
        writer.commit();
        if (change[1] > 0) {
          assertThatThrownBy(() -> delete.get(20, TimeUnit.SECONDS))
              .as("a row that still matches")
              .hasCauseInstanceOf(SQLTransactionRollbackException.class);
        } else {
          assertThat(delete.get(20, TimeUnit.SECONDS)).as("a row that no longer matches").isZero();
        }
        deleter.rollback();
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
