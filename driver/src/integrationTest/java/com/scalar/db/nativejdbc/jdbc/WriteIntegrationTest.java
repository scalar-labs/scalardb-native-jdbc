package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 4a on PostgreSQL and both decoupled layouts: single-statement writes of a ScalarDB
 * table at READ COMMITTED (design 4.3 to 4.5).
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "OBL_UNSATISFIED_OBLIGATION", "ODR_OPEN_DATABASE_RESOURCE"},
    justification = "statements written in the test sources, closed with their connection")
class WriteIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  static List<Engines> engines() {
    return Engines.selected();
  }

  /** One ScalarDB table as the test reaches it: by SQL, and by ScalarDB's own name. */
  private static final class Table {
    final String sql;
    final String meta;
    final String scalarDbName;

    Table(String sql, String meta, String scalarDbName) {
      this.sql = sql;
      this.meta = meta;
      this.scalarDbName = scalarDbName;
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void writes_onBothLayouts(Engines e) throws Exception {
    assumeTrue(e == Engines.POSTGRESQL, "writes are PostgreSQL only so far (milestone 4a)");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("wr"),
          "CREATE TABLE wr.imp (id INT PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO wr.imp VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("wr", true);
      admin.importTable("wr", "imp", DECOUPLED, Map.of());
      admin.createTable(
          "wr",
          "made",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .build(),
          DECOUPLED);
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 3; i++) {
        tx.insert(
            Insert.newBuilder()
                .namespace("wr")
                .table("made")
                .partitionKey(Key.ofInt("id", i))
                .textValue("x", String.valueOf((char) ('a' + i - 1)))
                .build());
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals("wr"), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo imp = catalog.lookup(List.of(), "wr", "imp");
    TableInfo made = catalog.lookup(List.of(), "wr", "made");
    List<Table> tables =
        List.of(
            new Table(
                "wr.imp", "wr." + imp.metadataTable(), Objects.requireNonNull(imp.virtualTable())),
            new Table("wr.made", "wr." + made.metadataTable(), "made"));
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager()) {
      for (Table t : tables) {
        eachWriteKind(e, tm, t);
        inFlightRowsAbort(e, tpc, t);
        duplicates(e, tpc, t);
      }
      metadataLessRows(e, tm, tables.get(0));
    }
  }

  private static void eachWriteKind(Engines e, DistributedTransactionManager tm, Table t)
      throws Exception {
    try (Connection d = e.openDriver()) {
      // autocommit: each statement its own transaction
      assertThat(
              d.createStatement()
                  .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (10, 'p')"))
          .isEqualTo(1);
      try (PreparedStatement ps =
          d.prepareStatement("INSERT INTO " + t.sql + " (x, id) VALUES (?, ?), ('r', 12)")) {
        ps.setString(1, "q");
        ps.setInt(2, 11);
        assertThat(ps.executeUpdate()).isEqualTo(2);
      }
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + t.sql + " SET x = ? WHERE id = ?")) {
        ps.setString(1, "P");
        ps.setInt(2, 10);
        assertThat(ps.executeUpdate()).isEqualTo(1);
        ps.setInt(2, 99);
        assertThat(ps.executeUpdate()).as("no such key").isZero();
      }
      // by predicate, with a line comment after it
      assertThat(
              d.createStatement()
                  .executeUpdate(
                      "UPDATE " + t.sql + " SET x = x || '!' WHERE id IN (11, 12) -- two"))
          .isEqualTo(2);
      // execute() answers with an update count
      Statement st = d.createStatement();
      assertThat(st.execute("DELETE FROM " + t.sql + " WHERE id = 12")).isFalse();
      assertThat(st.getUpdateCount()).isEqualTo(1);
      assertThat(st.getMoreResults()).isFalse();
      assertThat(st.getUpdateCount()).isEqualTo(-1);
      // in an explicit transaction, rolled back: nothing stays
      d.setAutoCommit(false);
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'gone' WHERE id = 10");
      d.rollback();
      d.setAutoCommit(true);
      assertThat(rows(d, "SELECT id, x FROM " + t.sql + " WHERE id >= 10 ORDER BY id"))
          .containsExactly("10/P", "11/q!");
    }
    // ScalarDB reads the driver's writes as committed values
    try (Connection v = e.open()) {
      assertThat(rows(v, "SELECT tx_state, tx_version FROM " + t.meta + " WHERE id = 10"))
          .containsExactly("3/2");
      assertThat(rows(v, "SELECT COUNT(*) FROM " + t.meta + " WHERE id = 12")).containsExactly("0");
    }
    assertThat(scalarDbX(tm, t, 10)).contains("P");
    assertThat(scalarDbX(tm, t, 12)).isEmpty();
    // a ScalarDB update that read the row before the driver wrote it cannot commit
    DistributedTransaction tx = tm.begin();
    tx.get(
        Get.newBuilder()
            .namespace("wr")
            .table(t.scalarDbName)
            .partitionKey(Key.ofInt("id", 11))
            .build());
    try (Connection d = e.openDriver()) {
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'driver' WHERE id = 11");
    }
    tx.update(
        Update.newBuilder()
            .namespace("wr")
            .table(t.scalarDbName)
            .partitionKey(Key.ofInt("id", 11))
            .textValue("x", "scalardb")
            .build());
    assertThatThrownBy(tx::commit).isInstanceOf(TransactionException.class);
    assertThat(scalarDbX(tm, t, 11)).contains("driver");
  }

  private static void inFlightRowsAbort(Engines e, TwoPhaseCommitTransactionManager tpc, Table t)
      throws Exception {
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(
        Update.newBuilder()
            .namespace("wr")
            .table(t.scalarDbName)
            .partitionKey(Key.ofInt("id", 1))
            .textValue("x", "held")
            .build());
    tx.prepare();
    try (Connection d = e.openDriver()) {
      // autocommit: the abort ends the statement's own transaction
      assertThatThrownBy(
              () ->
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'w' WHERE id = 1"))
          .isInstanceOf(SQLTransactionRollbackException.class)
          .extracting("SQLState")
          .isEqualTo("40001");
      // by predicate, in a transaction: rollback-only until the application rolls back
      d.setAutoCommit(false);
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'w2' WHERE id = 2");
      assertThatThrownBy(
              () -> d.createStatement().executeUpdate("DELETE FROM " + t.sql + " WHERE id <= 2"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      assertThatThrownBy(() -> rows(d, "SELECT 1"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      assertThatThrownBy(d::commit).isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id IN (1, 2) ORDER BY id"))
          .containsExactly("a", "b");
      d.setAutoCommit(true);
    } finally {
      tx.rollback();
    }
    // the row as ScalarDB left it
    try (Connection v = e.open()) {
      assertThat(rows(v, "SELECT x FROM " + t.sql + " WHERE id = 1")).containsExactly("a");
    }
  }

  private static void duplicates(Engines e, TwoPhaseCommitTransactionManager tpc, Table t)
      throws Exception {
    try (Connection d = e.openDriver()) {
      d.setAutoCommit(false);
      // the application's duplicate, as the database raised it; the transaction goes on
      assertThatThrownBy(
              () ->
                  d.createStatement()
                      .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (3, 'd')"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("23505");
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'C' WHERE id = 3");
      d.commit();
      d.setAutoCommit(true);
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 3")).containsExactly("C");
      // a key a ScalarDB transaction is inserting: in flight, not the application's duplicate
      TwoPhaseCommitTransaction tx = tpc.begin();
      tx.insert(
          Insert.newBuilder()
              .namespace("wr")
              .table(t.scalarDbName)
              .partitionKey(Key.ofInt("id", 20))
              .textValue("x", "s")
              .build());
      tx.prepare();
      try {
        assertThatThrownBy(
                () ->
                    d.createStatement()
                        .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (20, 'd')"))
            .isInstanceOf(SQLTransactionRollbackException.class);
      } finally {
        tx.rollback();
      }
    }
  }

  /** Rows of an imported table without metadata, or with NULL metadata, before a backfill. */
  private static void metadataLessRows(Engines e, DistributedTransactionManager tm, Table t)
      throws Exception {
    try (Connection v = e.open()) {
      e.execute(
          v,
          "INSERT INTO " + t.sql + " VALUES (30, 'n'), (31, 'n'), (32, 'n')",
          "INSERT INTO " + t.meta + " (id) VALUES (31)");
    }
    try (Connection d = e.openDriver()) {
      // no metadata row, and a NULL one: stamped at version 1
      assertThat(
              d.createStatement()
                  .executeUpdate("UPDATE " + t.sql + " SET x = 'u' WHERE id IN (30, 31)"))
          .isEqualTo(2);
    }
    try (Connection v = e.open()) {
      assertThat(
              rows(
                  v,
                  "SELECT id, tx_state, tx_version FROM "
                      + t.meta
                      + " WHERE id IN (30, 31) ORDER BY id"))
          .containsExactly("30/3/1", "31/3/1");
    }
    // a ScalarDB update that read row 32 with no metadata: the driver's DELETE leaves a tombstone,
    // and the update cannot bring the row back (4.5)
    DistributedTransaction tx = tm.begin();
    assertThat(
            tx.get(
                Get.newBuilder()
                    .namespace("wr")
                    .table(t.scalarDbName)
                    .partitionKey(Key.ofInt("id", 32))
                    .build()))
        .isPresent();
    try (Connection d = e.openDriver()) {
      assertThat(d.createStatement().executeUpdate("DELETE FROM " + t.sql + " WHERE id = 32"))
          .isEqualTo(1);
    }
    try (Connection v = e.open()) {
      assertThat(rows(v, "SELECT tx_state, tx_version FROM " + t.meta + " WHERE id = 32"))
          .containsExactly("3/1");
    }
    tx.update(
        Update.newBuilder()
            .namespace("wr")
            .table(t.scalarDbName)
            .partitionKey(Key.ofInt("id", 32))
            .textValue("x", "back")
            .build());
    assertThatThrownBy(tx::commit).isInstanceOf(TransactionException.class);
    assertThat(scalarDbX(tm, t, 32)).isEmpty();
    // the driver's INSERT takes the tombstone over
    try (Connection d = e.openDriver()) {
      assertThat(
              d.createStatement()
                  .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (32, 'again')"))
          .isEqualTo(1);
    }
    try (Connection v = e.open()) {
      assertThat(rows(v, "SELECT tx_state, tx_version FROM " + t.meta + " WHERE id = 32"))
          .containsExactly("3/2");
    }
    assertThat(scalarDbX(tm, t, 32)).contains("again");
  }

  private static Optional<String> scalarDbX(DistributedTransactionManager tm, Table t, int id)
      throws TransactionException {
    DistributedTransaction tx = tm.begin();
    Optional<Result> r =
        tx.get(
            Get.newBuilder()
                .namespace("wr")
                .table(t.scalarDbName)
                .partitionKey(Key.ofInt("id", id))
                .build());
    tx.commit();
    return r.map(x -> x.getText("x"));
  }

  private static List<String> rows(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      int n = rs.getMetaData().getColumnCount();
      List<String> out = new ArrayList<>();
      while (rs.next()) {
        List<String> row = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
          row.add(rs.getString(i));
        }
        out.add(String.join("/", row));
      }
      return out;
    }
  }
}
