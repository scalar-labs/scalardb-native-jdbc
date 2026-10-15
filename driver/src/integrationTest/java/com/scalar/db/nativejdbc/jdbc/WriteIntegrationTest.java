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
      // a DELETE on an imported table keeps the metadata row as a tombstone (4.5, todo 132)
      assertThat(rows(v, "SELECT COUNT(*) FROM " + t.meta + " WHERE id = 12"))
          .containsExactly(t.sql.equals("wr.imp") ? "1" : "0");
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

  /** The findings of the 4a review (todos 130-138), each asserting the fixed behaviour. */
  @ParameterizedTest
  @MethodSource("engines")
  void reviewFindings(Engines e) throws Exception {
    assumeTrue(e == Engines.POSTGRESQL, "writes are PostgreSQL only so far (milestone 4a)");
    try (Connection c = e.open()) {
      e.execute(
          c,
          "CREATE SCHEMA rpl",
          "CREATE SCHEMA rsd",
          "CREATE SCHEMA rf",
          "CREATE TABLE rpl.t (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO rpl.t VALUES (1, 'plain')",
          "CREATE TABLE rsd.t (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO rsd.t VALUES (1, 'scalardb')",
          "CREATE TABLE rf.imp (id INT PRIMARY KEY, x TEXT, b BYTEA)",
          "INSERT INTO rf.imp VALUES (60, 'a', NULL), (70, 'a', NULL), (80, 'a', NULL),"
              + " (85, 'a', NULL)",
          "CREATE TABLE rf.ch (k CHAR(4) PRIMARY KEY, x TEXT)",
          "INSERT INTO rf.ch VALUES ('ab', 'a')",
          "CREATE TABLE rf.ts (k TIMESTAMP PRIMARY KEY, x TEXT)",
          "INSERT INTO rf.ts VALUES ('2020-03-08 02:30:00', 'a')",
          "CREATE FUNCTION rf.gate(v TEXT) RETURNS BOOLEAN AS $$ BEGIN PERFORM pg_sleep(3);"
              + " RETURN v = 'a'; END $$ LANGUAGE plpgsql VOLATILE");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("rsd", true);
      admin.createNamespace("rf", true);
      admin.importTable("rsd", "t", DECOUPLED, Map.of());
      admin.importTable("rf", "imp", DECOUPLED, Map.of());
      admin.importTable("rf", "ch", DECOUPLED, Map.of());
      admin.importTable("rf", "ts", DECOUPLED, Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals("rsd") || t.namespace().equals("rf"),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo imp = catalog.lookup(List.of(), "rf", "imp");
    Table t =
        new Table(
            "rf.imp", "rf." + imp.metadataTable(), Objects.requireNonNull(imp.virtualTable()));
    String sdMeta = "rsd." + catalog.lookup(List.of(), "rsd", "t").metadataTable();
    String chMeta = "rf." + catalog.lookup(List.of(), "rf", "ch").metadataTable();

    // 130: a prepared plain write that comes to name a ScalarDB table is refused, not run as text
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      d.setSchema("rpl");
      PreparedStatement ps = d.prepareStatement("UPDATE t SET x = ? WHERE id = ?");
      ps.setString(1, "p");
      ps.setInt(2, 1);
      ps.executeUpdate();
      d.setSchema("rsd");
      ps.setString(1, "raw");
      assertThatThrownBy(ps::executeUpdate).hasMessageContaining("prepare it again");
      assertThat(rows(v, "SELECT x FROM rsd.t WHERE id = 1")).containsExactly("scalardb");
      assertThat(rows(v, "SELECT tx_version FROM " + sdMeta + " WHERE id = 1"))
          .containsExactly("1");
    }
    // 131: streams are read once and bound whole into every statement of the write
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      PreparedStatement ps = d.prepareStatement("INSERT INTO rf.imp (id, x, b) VALUES (?, ?, ?)");
      ps.setInt(1, 90);
      ps.setCharacterStream(2, new java.io.StringReader("hello"));
      ps.setBinaryStream(3, new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
      assertThat(ps.executeUpdate()).isEqualTo(1);
      ps = d.prepareStatement("UPDATE rf.imp SET x = ? WHERE id IN (60, 90)");
      ps.setCharacterStream(1, new java.io.StringReader("streamed-and-more"), 8);
      assertThat(ps.executeUpdate()).isEqualTo(2);
      assertThat(rows(v, "SELECT id, x, length(b) FROM rf.imp WHERE id IN (60, 90) ORDER BY id"))
          .containsExactly("60/streamed/null", "90/streamed/3");
      e.execute(v, "UPDATE rf.imp SET x = 'a' WHERE id = 60");
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      // 132: an UPDATE then a DELETE of a row without metadata keep a tombstone (imported table)
      try (Connection v = e.open()) {
        e.execute(v, "INSERT INTO rf.imp (id, x) VALUES (50, 'n')");
      }
      DistributedTransaction z = tm.begin();
      z.get(
          Get.newBuilder()
              .namespace("rf")
              .table(t.scalarDbName)
              .partitionKey(Key.ofInt("id", 50))
              .build());
      try (Connection d = e.openDriver()) {
        d.createStatement().executeUpdate("UPDATE rf.imp SET x = 'u' WHERE id = 50");
        d.createStatement().executeUpdate("DELETE FROM rf.imp WHERE id = 50");
      }
      z.update(
          Update.newBuilder()
              .namespace("rf")
              .table(t.scalarDbName)
              .partitionKey(Key.ofInt("id", 50))
              .textValue("x", "back")
              .build());
      assertThatThrownBy(z::commit).isInstanceOf(TransactionException.class);
      assertThat(scalarDbX(tm, t, 50)).isEmpty();
      try (Connection v = e.open()) {
        assertThat(rows(v, "SELECT tx_state, tx_version FROM " + t.meta + " WHERE id = 50"))
            .containsExactly("3/2");
      }
    }
    // 133: executeBatch on a prepared write is an empty batch
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      PreparedStatement ps = d.prepareStatement("UPDATE rf.imp SET x = ? WHERE id = ?");
      ps.setString(1, "batch");
      ps.setInt(2, 80);
      assertThat(ps.executeBatch()).isEmpty();
      assertThat(rows(v, "SELECT x FROM rf.imp WHERE id = 80")).containsExactly("a");
    }
    // 135: a CHAR(n) key is matched in the metadata table as it is stored there
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      assertThat(d.createStatement().executeUpdate("UPDATE rf.ch SET x = 'z' WHERE x = 'a'"))
          .isEqualTo(1);
      assertThat(rows(v, "SELECT tx_version FROM " + chMeta)).containsExactly("2");
    }
    // 136: the query timeout bounds the write's own statements
    try (Connection lock = e.open();
        Connection d = e.openDriver()) {
      lock.setAutoCommit(false);
      rows(lock, "SELECT x FROM rf.imp WHERE id = 70 FOR UPDATE");
      Statement st = d.createStatement();
      st.setQueryTimeout(1);
      long t0 = System.nanoTime();
      assertThatThrownBy(() -> st.executeUpdate("UPDATE rf.imp SET x = 't' WHERE id = 70"))
          .isInstanceOf(SQLException.class);
      assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(3000);
      lock.rollback();
      assertThat(d.getAutoCommit()).isTrue();
    }
    // 137: SQL ROLLBACK ends rollback-only; a write closes the statement's earlier result set
    try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
        Connection d = e.openDriver()) {
      TwoPhaseCommitTransaction held = tpc.begin();
      held.update(
          Update.newBuilder()
              .namespace("rf")
              .table(t.scalarDbName)
              .partitionKey(Key.ofInt("id", 85))
              .textValue("x", "held")
              .build());
      held.prepare();
      try {
        d.setAutoCommit(false);
        assertThatThrownBy(
                () -> d.createStatement().executeUpdate("UPDATE rf.imp SET x = 'w' WHERE id = 85"))
            .isInstanceOf(SQLTransactionRollbackException.class);
        d.createStatement().execute("ROLLBACK");
        assertThat(rows(d, "SELECT x FROM rf.imp WHERE id = 80")).containsExactly("a");
        d.setAutoCommit(true);
      } finally {
        held.rollback();
      }
      Statement st = d.createStatement();
      ResultSet rs = st.executeQuery("SELECT x FROM rf.imp WHERE id = 80");
      st.executeUpdate("UPDATE rf.imp SET x = 'a' WHERE id = 80");
      assertThat(rs.isClosed()).isTrue();
    }
    // 138: a metadata row with a tx_id and a NULL state is committed
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      e.execute(v, "UPDATE " + t.meta + " SET tx_state = NULL WHERE id = 80");
      assertThat(d.createStatement().executeUpdate("UPDATE rf.imp SET x = 'n' WHERE id = 80"))
          .isEqualTo(1);
    }
    // 138: a TIMESTAMP key in the JVM zone's spring-forward gap binds back unchanged
    java.util.TimeZone zone = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
    try (Connection d = e.openDriver()) {
      assertThat(d.createStatement().executeUpdate("UPDATE rf.ts SET x = 'z' WHERE x = 'a'"))
          .isEqualTo(1);
    } finally {
      java.util.TimeZone.setDefault(zone);
    }
    // 134: a prepare and rollback around the per-key UPDATE abort it, never skip the row
    try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
        Connection d = e.openDriver();
        Connection v = e.open()) {
      java.util.concurrent.CompletableFuture<Object> write =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return d.createStatement()
                      .executeUpdate("UPDATE rf.imp SET x = 'drv' WHERE id = 60 AND rf.gate(x)");
                } catch (SQLException ex) {
                  return ex;
                }
              });
      Thread.sleep(1000); // the keys are read, the gate sleeping
      TwoPhaseCommitTransaction tx = tpc.begin();
      tx.update(
          Update.newBuilder()
              .namespace("rf")
              .table(t.scalarDbName)
              .partitionKey(Key.ofInt("id", 60))
              .textValue("x", "held")
              .build());
      tx.prepare();
      Thread.sleep(3000); // the per-key UPDATE reads the image in flight
      tx.rollback();
      assertThat(write.join()).isInstanceOf(SQLTransactionRollbackException.class);
      assertThat(rows(v, "SELECT x FROM rf.imp WHERE id = 60")).containsExactly("a");
    }
  }

  /** Milestone 4b: a read in a transaction anchors the writes that follow it (design 4.2, 4.3). */
  @ParameterizedTest
  @MethodSource("engines")
  void anchorsFromReads(Engines e) throws Exception {
    assumeTrue(e == Engines.POSTGRESQL, "writes are PostgreSQL only so far (milestone 4)");
    try (Connection c = e.open()) {
      e.execute(
          c,
          "CREATE SCHEMA an",
          "CREATE TABLE an.imp (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO an.imp SELECT g, 'a' FROM generate_series(1, 11) g");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("an", true);
      admin.importTable("an", "imp", DECOUPLED, Map.of());
      admin.createTable(
          "an",
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
      for (int i = 1; i <= 11; i++) {
        tx.insert(
            Insert.newBuilder()
                .namespace("an")
                .table("made")
                .partitionKey(Key.ofInt("id", i))
                .textValue("x", "a")
                .build());
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals("an"), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo imp = catalog.lookup(List.of(), "an", "imp");
    List<Table> tables =
        List.of(
            new Table(
                "an.imp", "an." + imp.metadataTable(), Objects.requireNonNull(imp.virtualTable())),
            new Table(
                "an.made",
                "an." + catalog.lookup(List.of(), "an", "made").metadataTable(),
                "made"));
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager()) {
      for (Table t : tables) {
        try (Connection d = e.openDriver();
            Connection other = e.openDriver()) {
          // the application's columns only
          try (PreparedStatement ps =
              d.prepareStatement("SELECT x AS label FROM " + t.sql + " WHERE id = ?")) {
            ps.setInt(1, 1);
            assertThat(ps.getMetaData().getColumnCount()).isEqualTo(1);
            try (ResultSet rs = ps.executeQuery()) {
              assertThat(rs.getMetaData().getColumnCount()).isEqualTo(1);
              assertThat(rs.next()).isTrue();
              assertThat(rs.getString("label")).isEqualTo("a");
              assertThatThrownBy(() -> rs.getObject(2)).isInstanceOf(SQLException.class);
            }
          }
          // SELECT * returns the table's own columns, in its order
          try (ResultSet rs =
              d.createStatement().executeQuery("SELECT * FROM " + t.sql + " WHERE id = 1")) {
            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
            assertThat(rs.getMetaData().getColumnLabel(1)).isEqualTo("id");
            assertThat(rs.getMetaData().getColumnLabel(2)).isEqualTo("x");
          }
          d.setAutoCommit(false);
          // SELECT * and a row-wise function anchor too, each in a transaction of its own
          String[][] reads = {
            {"10", "SELECT * FROM %s WHERE id = 10", "10/a"},
            {"11", "SELECT upper(x) FROM %s WHERE id = 11", "A"}
          };
          for (String[] r : reads) {
            assertThat(rows(d, String.format(r[1], t.sql))).as(r[1]).containsExactly(r[2]);
            scalarDbSet(tm, t, Integer.parseInt(r[0]), "scalardb");
            assertThatThrownBy(
                    () ->
                        d.createStatement()
                            .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = " + r[0]))
                .as(r[1])
                .isInstanceOf(SQLTransactionRollbackException.class);
            d.rollback();
          }
          // read, then a ScalarDB commit of the row: the write aborts, no lost update
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 1")).containsExactly("a");
          scalarDbSet(tm, t, 1, "scalardb");
          assertThatThrownBy(
                  () ->
                      d.createStatement()
                          .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 1"))
              .isInstanceOf(SQLTransactionRollbackException.class);
          d.rollback();
          // read by predicate, then another driver transaction writes one of the rows
          assertThat(rows(d, "SELECT id FROM " + t.sql + " WHERE id IN (2, 3) ORDER BY id"))
              .containsExactly("2", "3");
          other.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'o' WHERE id = 3");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 2"))
              .isEqualTo(1);
          assertThatThrownBy(
                  () ->
                      d.createStatement()
                          .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 3"))
              .isInstanceOf(SQLTransactionRollbackException.class);
          d.rollback();
          // a key the transaction wrote is written again; its read after the write anchors nothing
          // new
          d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'w1' WHERE id = 4");
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 4")).containsExactly("w1");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'w2' WHERE id = 4"))
              .isEqualTo(1);
          d.commit();
          // a rolled-back transaction's reads anchor nothing in the next one
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 5")).containsExactly("a");
          d.rollback();
          scalarDbSet(tm, t, 5, "scalardb");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 5"))
              .isEqualTo(1);
          d.commit();
          // a row read in flight is anchored on its before image: a ScalarDB rollback keeps it
          // valid,
          // a commit does not
          for (boolean commit : new boolean[] {false, true}) {
            int id = commit ? 7 : 6;
            TwoPhaseCommitTransaction held = tpc.begin();
            held.update(
                Update.newBuilder()
                    .namespace("an")
                    .table(t.scalarDbName)
                    .partitionKey(Key.ofInt("id", id))
                    .textValue("x", "held")
                    .build());
            held.prepare();
            assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = " + id))
                .containsExactly("a");
            if (commit) {
              held.validate();
              held.commit();
            } else {
              held.rollback();
            }
            String update = "UPDATE " + t.sql + " SET x = 'd' WHERE id = " + id;
            if (commit) {
              assertThatThrownBy(() -> d.createStatement().executeUpdate(update))
                  .isInstanceOf(SQLTransactionRollbackException.class);
              d.rollback();
            } else {
              assertThat(d.createStatement().executeUpdate(update)).isEqualTo(1);
              d.commit();
            }
          }
          d.setAutoCommit(true);
          // in autocommit a read anchors nothing
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 8")).containsExactly("a");
          scalarDbSet(tm, t, 8, "scalardb");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 8"))
              .isEqualTo(1);
        }
      }
    }
  }

  /**
   * A read of a join anchors each table it returns rows of, a LEFT JOIN's nullable side included
   * (design 4.2).
   */
  @ParameterizedTest
  @MethodSource("engines")
  void anchorsFromJoins(Engines e) throws Exception {
    assumeTrue(e == Engines.POSTGRESQL, "writes are PostgreSQL only so far (milestone 4)");
    try (Connection c = e.open()) {
      e.execute(
          c,
          "CREATE SCHEMA aj",
          "CREATE TABLE aj.p (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO aj.p SELECT g, 'a' FROM generate_series(1, 9) g");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("aj", true);
      admin.importTable("aj", "p", DECOUPLED, Map.of());
      admin.createTable(
          "aj",
          "c",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("pid", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .build(),
          DECOUPLED);
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 9; i++) {
        if (i != 4) { // parent 4 has no child
          tx.insert(
              Insert.newBuilder()
                  .namespace("aj")
                  .table("c")
                  .partitionKey(Key.ofInt("id", i))
                  .intValue("pid", i)
                  .textValue("x", "c")
                  .build());
        }
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals("aj"), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo pInfo = catalog.lookup(List.of(), "aj", "p");
    Table p =
        new Table(
            "aj.p", "aj." + pInfo.metadataTable(), Objects.requireNonNull(pInfo.virtualTable()));
    Table ch = new Table("aj.c", "aj." + catalog.lookup(List.of(), "aj", "c").metadataTable(), "c");
    String join = "SELECT p.x, c.x FROM aj.p p JOIN aj.c c ON c.pid = p.id WHERE p.id = ";
    String leftJoin =
        "SELECT p.id, c.id FROM aj.p p LEFT JOIN aj.c c ON c.pid = p.id WHERE p.id = ";
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
        Connection d = e.openDriver();
        Connection other = e.openDriver()) {
      d.setAutoCommit(false);
      // the application's columns only
      try (ResultSet rs = d.createStatement().executeQuery(join + "1")) {
        assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
        assertThat(rs.next()).isTrue();
        assertThatThrownBy(() -> rs.getObject(3)).isInstanceOf(SQLException.class);
      }
      d.rollback();
      // both tables of an inner join are anchored
      for (Table t : List.of(p, ch)) {
        int id = t == p ? 1 : 3;
        assertThat(rows(d, join + id)).containsExactly("a/c");
        scalarDbSet(tm, t, id, "scalardb");
        assertThatThrownBy(
                () ->
                    d.createStatement()
                        .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = " + id))
            .as(t.sql)
            .isInstanceOf(SQLTransactionRollbackException.class);
        d.rollback();
      }
      // a LEFT JOIN: the preserved row of an unmatched parent, and the nullable side's rows
      assertThat(rows(d, leftJoin + "4")).containsExactly("4/null");
      scalarDbSet(tm, p, 4, "scalardb");
      assertThatThrownBy(
              () -> d.createStatement().executeUpdate("UPDATE aj.p SET x = 'd' WHERE id = 4"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      assertThat(rows(d, leftJoin + "2")).containsExactly("2/2");
      other.createStatement().executeUpdate("UPDATE aj.c SET x = 'o' WHERE id = 2");
      assertThatThrownBy(
              () -> d.createStatement().executeUpdate("UPDATE aj.c SET x = 'd' WHERE id = 2"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      // ORDER BY and LIMIT on top of the copies
      assertThat(
              rows(
                  d,
                  "SELECT c.id FROM aj.p p JOIN aj.c c ON c.pid = p.id WHERE p.id IN (3, 5)"
                      + " ORDER BY p.id DESC LIMIT 1"))
          .containsExactly("5");
      scalarDbSet(tm, ch, 5, "scalardb");
      assertThatThrownBy(
              () -> d.createStatement().executeUpdate("UPDATE aj.c SET x = 'd' WHERE id = 5"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      // a self-join anchors the rows of both references
      assertThat(rows(d, "SELECT b.id FROM aj.p a JOIN aj.p b ON b.id = a.id + 1 WHERE a.id = 6"))
          .containsExactly("7");
      scalarDbSet(tm, p, 7, "scalardb");
      assertThatThrownBy(
              () -> d.createStatement().executeUpdate("UPDATE aj.p SET x = 'd' WHERE id = 7"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      // an IN subquery over the key: the outer table is anchored, the subquery's returns no row
      assertThat(rows(d, "SELECT x FROM aj.p WHERE id IN (SELECT id FROM aj.c WHERE id = 2)"))
          .containsExactly("a");
      scalarDbSet(tm, p, 2, "scalardb");
      assertThatThrownBy(
              () -> d.createStatement().executeUpdate("UPDATE aj.p SET x = 'd' WHERE id = 2"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      // a nullable row read in flight is anchored on its before image: a ScalarDB rollback keeps
      // it valid, a commit does not
      for (boolean commit : new boolean[] {false, true}) {
        int id = commit ? 9 : 8;
        TwoPhaseCommitTransaction held = tpc.begin();
        held.update(
            Update.newBuilder()
                .namespace("aj")
                .table("c")
                .partitionKey(Key.ofInt("id", id))
                .textValue("x", "held")
                .build());
        held.prepare();
        assertThat(
                rows(
                    d,
                    "SELECT c.x FROM aj.p p LEFT JOIN aj.c c ON c.pid = p.id WHERE p.id = " + id))
            .containsExactly("c");
        if (commit) {
          held.validate();
          held.commit();
        } else {
          held.rollback();
        }
        String update = "UPDATE aj.c SET x = 'd' WHERE id = " + id;
        if (commit) {
          assertThatThrownBy(() -> d.createStatement().executeUpdate(update))
              .isInstanceOf(SQLTransactionRollbackException.class);
          d.rollback();
        } else {
          assertThat(d.createStatement().executeUpdate(update)).isEqualTo(1);
          d.commit();
        }
      }
    }
  }

  /** The findings of the 4b review (todos 141-145), each asserting the fixed behaviour. */
  @ParameterizedTest
  @MethodSource("engines")
  void anchorReviewFindings(Engines e) throws Exception {
    assumeTrue(e == Engines.POSTGRESQL, "writes are PostgreSQL only so far (milestone 4)");
    try (Connection c = e.open()) {
      e.execute(
          c,
          "CREATE SCHEMA af",
          "CREATE TABLE af.imp (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO af.imp SELECT g, 'a' FROM generate_series(1, 9) g");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("af", true);
      admin.importTable("af", "imp", DECOUPLED, Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals("af"), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    String sdb = Objects.requireNonNull(catalog.lookup(List.of(), "af", "imp").virtualTable());
    try (Connection d = e.openDriver();
        Connection other = e.openDriver()) {
      d.setAutoCommit(false);
      // 141: a result set iterated after its transaction ended anchors nothing in the next one
      ResultSet stale = d.createStatement().executeQuery("SELECT x FROM af.imp WHERE id = 1");
      d.commit();
      other.createStatement().executeUpdate("UPDATE af.imp SET x = 'o' WHERE id = 1");
      assertThat(stale.next()).isTrue();
      assertThat(rows(d, "SELECT x FROM af.imp WHERE id = 1")).containsExactly("o");
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'd' WHERE id = 1"))
          .isEqualTo(1);
      d.commit();
      // 142: a rollback to a savepoint keeps the transaction's own writes its own
      rows(d, "SELECT x FROM af.imp WHERE id = 2");
      d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w1' WHERE id = 2");
      d.rollback(d.setSavepoint());
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w2' WHERE id = 2"))
          .isEqualTo(1);
      d.createStatement().execute("SAVEPOINT s");
      d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w3' WHERE id = 2");
      d.createStatement().execute("ROLLBACK TO SAVEPOINT s");
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w4' WHERE id = 2"))
          .isEqualTo(1);
      d.commit();
      // 143: an aggregate in ORDER BY only
      assertThat(rows(d, "SELECT 1 FROM af.imp ORDER BY count(*)")).containsExactly("1");
      // 144: hidden columns by label, generated keys, the lookups' alias, a position past the list,
      // an updatable result set
      try (Statement st = d.createStatement();
          ResultSet rs = st.executeQuery("SELECT x FROM af.imp WHERE id = 3")) {
        assertThat(rs.next()).isTrue();
        assertThatThrownBy(() -> rs.getString("scalardb_native_x0"))
            .isInstanceOf(SQLException.class);
        assertThat(st.getGeneratedKeys().getMetaData().getColumnCount()).isZero();
      }
      assertThat(rows(d, "SELECT x FROM af.imp AS scalardb_native_meta WHERE id = 3"))
          .containsExactly("a");
      assertThatThrownBy(() -> rows(d, "SELECT x FROM af.imp ORDER BY 2"))
          .isInstanceOf(SQLException.class);
      d.rollback();
      assertThatThrownBy(
              () ->
                  d.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)
                      .executeQuery("SELECT id, x FROM af.imp WHERE id = 3"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("0AN01");
      d.rollback();
    }
    // 145: a row without metadata read in flight, then rolled back by ScalarDB, is unchanged
    try (Connection v = e.open()) {
      e.execute(v, "INSERT INTO af.imp VALUES (20, 'n')");
    }
    try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
        Connection d = e.openDriver()) {
      TwoPhaseCommitTransaction held = tpc.begin();
      held.get(
          Get.newBuilder().namespace("af").table(sdb).partitionKey(Key.ofInt("id", 20)).build());
      held.update(
          Update.newBuilder()
              .namespace("af")
              .table(sdb)
              .partitionKey(Key.ofInt("id", 20))
              .textValue("x", "held")
              .build());
      held.prepare();
      d.setAutoCommit(false);
      assertThat(rows(d, "SELECT x FROM af.imp WHERE id = 20")).containsExactly("n");
      held.rollback();
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'd' WHERE id = 20"))
          .isEqualTo(1);
      d.commit();
    }
  }

  private static void scalarDbSet(DistributedTransactionManager tm, Table t, int id, String x)
      throws TransactionException {
    String namespace = t.sql.substring(0, t.sql.indexOf('.'));
    DistributedTransaction tx = tm.begin();
    tx.get(
        Get.newBuilder()
            .namespace(namespace)
            .table(t.scalarDbName)
            .partitionKey(Key.ofInt("id", id))
            .build());
    tx.update(
        Update.newBuilder()
            .namespace(namespace)
            .table(t.scalarDbName)
            .partitionKey(Key.ofInt("id", id))
            .textValue("x", x)
            .build());
    tx.commit();
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
