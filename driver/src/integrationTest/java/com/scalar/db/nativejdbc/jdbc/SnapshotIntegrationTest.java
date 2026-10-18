package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 7 on PostgreSQL: at SNAPSHOT and SERIALIZABLE a row in flight that a read would return
 * or match, by its after image or its before image, aborts the transaction (design 4.2, section 6),
 * row by row where every ScalarDB table returns rows and table by table elsewhere; the snapshot
 * holds across statements; a write never waits; and first-committer-wins holds against ScalarDB.
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "OBL_UNSATISFIED_OBLIGATION"},
    justification = "statements written in the test sources, closed with their connection")
class SnapshotIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void snapshotLevels(Engines e) throws Exception {
    boolean pg = e.engine() == Engine.POSTGRESQL;
    assumeTrue(
        pg || e.engine() == Engine.MYSQL || e.engine() == Engine.MARIADB,
        "the snapshot levels are PostgreSQL, MySQL and MariaDB only so far");
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("sn", true);
      for (String table : List.of("t", "u")) {
        admin.createTable(
            "sn",
            table,
            TableMetadata.newBuilder()
                .addColumn("id", DataType.INT)
                .addColumn("x", DataType.TEXT)
                .addColumn("v", DataType.INT)
                .addPartitionKey("id")
                .build(),
            Map.of("transaction-metadata-decoupling", "true"));
      }
    }
    TransactionFactory factory = e.scalarDb();
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int id = 1; id <= 20; id++) {
        tx.insert(insert("t", id, "a"));
        tx.insert(insert("u", id, "a"));
      }
      tx.commit();
    }
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals("sn"),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
      // no partial index nor deferred constraint elsewhere: the cases that need them run on
      // PostgreSQL only
      if (pg) {
        try (Statement st = c.createStatement()) {
          // a commit that fails: its duplicate is found at COMMIT
          st.execute("CREATE SCHEMA IF NOT EXISTS sn_plain");
          // a unique constraint besides the key, on rows v > 100 only
          st.execute("CREATE UNIQUE INDEX IF NOT EXISTS u_v_unique ON sn.u_data (v) WHERE v > 100");
          st.execute(
              "CREATE TABLE IF NOT EXISTS sn_plain.c (id INT, CONSTRAINT c_pk PRIMARY KEY (id)"
                  + " DEFERRABLE INITIALLY DEFERRED)");
        }
      }
    }
    // SERIALIZABLE off PostgreSQL comes with milestone 8b2
    for (int level :
        pg
            ? new int[] {
              Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE
            }
            : new int[] {Connection.TRANSACTION_REPEATABLE_READ}) {
      String at = level == Connection.TRANSACTION_SERIALIZABLE ? "SERIALIZABLE" : "SNAPSHOT";
      try (DistributedTransactionManager tm = factory.getTransactionManager();
          TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
        scenarios(e, tm, tpc, level, at);
      }
    }
  }

  private static void scenarios(
      Engines e,
      DistributedTransactionManager tm,
      TwoPhaseCommitTransactionManager tpc,
      int level,
      String at)
      throws Exception {
    boolean pg = e.engine() == Engine.POSTGRESQL;
    String duplicate = pg ? "23505" : "23000";
    Properties waits = new Properties();
    waits.setProperty("scalardbnative.inflightWaitMs", "5000");
    try (Connection d = e.openDriver(waits)) {
      d.setTransactionIsolation(level);
      d.setAutoCommit(false);

      // row by row: a row in flight that the read returns aborts it, by its before image ...
      TwoPhaseCommitTransaction held = prepared(tpc, "t", 1, "b");
      assertAborts(d, "SELECT x FROM sn.t WHERE id = 1", at + ": a key read of a row in flight");
      assertThatThrownBy(() -> rows(d, "SELECT x FROM sn.t WHERE id = 2"))
          .as("%s: the transaction is rollback-only after the abort", at)
          .isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      assertAborts(d, "SELECT id FROM sn.t WHERE x = 'a'", at + ": the before image matches");
      d.rollback();
      // ... and by its after image, which no committed row shows
      assertAborts(d, "SELECT id FROM sn.t WHERE x = 'b'", at + ": the after image matches");
      d.rollback();
      // a row in flight the read does not reach does not abort it
      assertThat(rows(d, "SELECT x FROM sn.t WHERE id = 2"))
          .as("%s: another row in flight does not matter", at)
          .containsExactly("a");
      assertThat(rows(d, "SELECT t.x, u.x FROM sn.t t JOIN sn.u u ON u.id = t.id WHERE t.id = 3"))
          .as("%s: a join reads row by row too", at)
          .containsExactly("a/a");
      d.commit();
      held.commit();
      scalarDbSet(tm, 1, "a");

      // a prepared insert, which has no before image
      TwoPhaseCommitTransaction inserting = tpc.begin();
      inserting.insert(insert("t", 50, "new"));
      inserting.prepare();
      assertAborts(d, "SELECT id FROM sn.t WHERE x = 'new'", at + ": a prepared insert matches");
      d.rollback();
      // a write of it, by key (todo 166)
      assertWriteAborts(d, "UPDATE sn.t SET v = 9 WHERE id = 50", at + ": a key write of it");
      d.rollback();
      // an offset skips rows the result does not return, which still count (todo 168)
      assertAborts(
          d, "SELECT id FROM sn.t ORDER BY id DESC LIMIT 3 OFFSET 3", at + ": an offset over it");
      d.rollback();
      // a row limit alone needs the rows it returns only
      assertThat(rows(d, "SELECT id FROM sn.t ORDER BY id LIMIT 2"))
          .as("%s: a limit short of it", at)
          .containsExactly("1", "2");
      d.commit();
      inserting.rollback();

      // an update in flight that only its after image matches: a write aborts (todo 166)
      held = prepared(tpc, "t", 7, "b");
      assertWriteAborts(d, "UPDATE sn.t SET v = 9 WHERE x = 'b'", at + ": its after image");
      d.rollback();
      assertWriteAborts(d, "DELETE FROM sn.t WHERE x = 'b'", at + ": a delete by its after image");
      d.rollback();
      // a scrollable result checks its tables whole, since a move passes over rows (todo 168)
      try (Statement st =
          d.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)) {
        assertThatThrownBy(() -> st.executeQuery("SELECT id FROM sn.t ORDER BY id").close())
            .as("%s: a scrollable result", at)
            .isInstanceOf(SQLTransactionRollbackException.class);
      }
      d.rollback();
      held.rollback();

      // an unmatched row of an outer join that a row in flight would match (todo 167)
      TwoPhaseCommitTransaction matching = tpc.begin();
      matching.insert(insert("u", 60, "a", 1));
      matching.prepare();
      assertAborts(
          d,
          "SELECT t.id FROM sn.t t LEFT JOIN sn.u u ON u.v = t.id WHERE u.id IS NULL AND t.id = 1",
          at + ": an anti-join");
      d.rollback();
      matching.rollback();

      // an insert of a key ScalarDB prepared after the snapshot is a conflict (todo 171)
      rows(d, "SELECT x FROM sn.t WHERE id = 8");
      inserting = tpc.begin();
      inserting.insert(insert("t", 70, "s"));
      inserting.prepare();
      assertWriteAborts(
          d, "INSERT INTO sn.t (id, x, v) VALUES (70, 'd', 0)", at + ": an insert of its key");
      d.rollback();
      inserting.rollback();
      try (Statement st = d.createStatement()) {
        assertThatThrownBy(() -> st.executeUpdate("INSERT INTO sn.t (id, x, v) VALUES (8, 'd', 0)"))
            .as("%s: a committed key is the application's duplicate", at)
            .isInstanceOf(SQLException.class)
            .extracting(ex -> ((SQLException) ex).getSQLState())
            .isEqualTo(duplicate);
      }
      d.rollback();
      // a key the statement repeats, or another unique constraint, is the application's duplicate
      // too, never retried (todo 178)
      for (String sql :
          pg
              ? List.of(
                  "INSERT INTO sn.t (id, x, v) VALUES (90, 'p', 0), (90, 'q', 0)",
                  "INSERT INTO sn.u (id, x, v) VALUES (91, 'p', 101), (92, 'q', 101)")
              : List.of("INSERT INTO sn.t (id, x, v) VALUES (90, 'p', 0), (90, 'q', 0)")) {
        try (Statement st = d.createStatement()) {
          assertThatThrownBy(() -> st.executeUpdate(sql))
              .as("%s: %s", at, sql)
              .isInstanceOf(SQLException.class)
              .extracting(ex -> ((SQLException) ex).getSQLState())
              .isEqualTo(duplicate);
        }
        d.rollback();
      }

      // in autocommit an abort leaves the connection usable (todo 169)
      held = prepared(tpc, "t", 9, "b");
      d.setAutoCommit(true);
      assertAborts(d, "SELECT x FROM sn.t WHERE id = 9", at + ": an autocommit read of it");
      held.rollback();
      assertThat(rows(d, "SELECT x FROM sn.t WHERE id = 9"))
          .as("%s: the next autocommit read", at)
          .containsExactly("a");
      d.setAutoCommit(false);
      // ... also when its rows are fetched after autocommit went off (todo 180)
      held = prepared(tpc, "t", 11, "b");
      d.setAutoCommit(true);
      try (Statement st = d.createStatement()) {
        st.execute("SELECT x FROM sn.t WHERE id = 11");
        d.setAutoCommit(false);
        assertThatThrownBy(() -> rows(st.getResultSet()))
            .as("%s: rows fetched later", at)
            .isInstanceOf(SQLTransactionRollbackException.class);
      }
      held.rollback();
      assertThat(rows(d, "SELECT x FROM sn.t WHERE id = 12"))
          .as("%s: the transaction after it", at)
          .containsExactly("a");
      d.rollback();

      // a failed commit, set_config, RESET and the result set's class are PostgreSQL's
      if (pg) {
        // a commit that fails ends the transaction, whose checks go with it (todo 170)
        assertThat(rows(d, "SELECT COUNT(*) FROM sn.u")).containsExactly("20");
        try (Statement st = d.createStatement()) {
          st.executeUpdate("INSERT INTO sn_plain.c VALUES (1)");
          st.executeUpdate("INSERT INTO sn_plain.c VALUES (1)");
        }
        assertThatThrownBy(d::commit).isInstanceOf(SQLException.class);
        held = prepared(tpc, "u", 3, "b");
        assertAborts(d, "SELECT COUNT(*) FROM sn.u WHERE x = 'a'", at + ": after a failed commit");
        d.rollback();
        held.rollback();

        // passthrough may set the level in SQL; the driver follows the session (todo 184)
        d.setAutoCommit(true);
        d.setClientInfo("scalardbnative.passthrough", "true");
        try (Statement st = d.createStatement()) {
          st.execute("SELECT set_config('default_transaction_isolation', 'read committed', false)");
        }
        d.setClientInfo("scalardbnative.passthrough", "false");
        assertThat(d.nativeSQL("SELECT x FROM sn.t WHERE id = 1"))
            .as("%s: read as READ COMMITTED after passthrough", at)
            .doesNotContain("\"scalardb_native_meta\".\"tx_state\"");
        d.setTransactionIsolation(level);
        d.setAutoCommit(false);

        // a result set whose rows are checked one by one is not handed out unchecked (todo 175)
        try (Statement st = d.createStatement();
            ResultSet rs = st.executeQuery("SELECT x FROM sn.t WHERE id = 1")) {
          Class<?> vendor = Class.forName("org.postgresql.jdbc.PgResultSet");
          assertThatThrownBy(() -> rs.unwrap(vendor))
              .as("%s: unwrap", at)
              .isInstanceOf(SQLException.class);
        }
        d.rollback();
      }
      // nativeSQL shows the text of the connection's level (todo 175)
      String text = "SELECT x FROM sn.t WHERE id = 1";
      String snapshotText = d.nativeSQL(text);
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      assertThat(d.nativeSQL(text))
          .as("%s: nativeSQL at another level", at)
          .isNotEqualTo(snapshotText);
      d.setTransactionIsolation(level);
      assertThat(d.nativeSQL(text)).as("%s: nativeSQL back", at).isEqualTo(snapshotText);
      if (!pg) {
        // passthrough may set the level in SQL here too (todo 222)
        d.setAutoCommit(true);
        d.setClientInfo("scalardbnative.passthrough", "true");
        try (Statement st = d.createStatement()) {
          st.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
        }
        d.setClientInfo("scalardbnative.passthrough", "false");
        assertThat(d.nativeSQL(text))
            .as("%s: read as READ COMMITTED after passthrough", at)
            .isNotEqualTo(snapshotText);
        d.setTransactionIsolation(level);
        d.setAutoCommit(false);
      }
      // the level is not changed once the transaction ran a statement, which MySQL and MariaDB
      // would apply to the next transaction only (todo 219)
      rows(d, text);
      assertThatThrownBy(() -> d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED))
          .as("%s: a change of level in the transaction", at)
          .isInstanceOf(SQLException.class)
          .extracting(ex -> ((SQLException) ex).getSQLState())
          .isEqualTo("25001");
      d.rollback();

      if (pg) {
        // the transaction mode is not set in SQL (todo 172)
        for (String sql :
            List.of(
                "SELECT set_config('default_transaction_isolation', 'read committed', false)",
                "RESET ALL",
                "RESET default_transaction_isolation",
                "RESET \"default_transaction_isolation\"")) {
          assertThatThrownBy(() -> rows(d, sql))
              .as("%s: %s", at, sql)
              .isInstanceOf(SQLException.class)
              .extracting(ex -> ((SQLException) ex).getSQLState())
              .isEqualTo("0AN01");
          d.rollback();
        }
      }
      // table by table: an aggregate or a subquery checks its tables whole first
      held = prepared(tpc, "u", 20, "b");
      assertAborts(d, "SELECT COUNT(*) FROM sn.u WHERE id < 5", at + ": an aggregate");
      d.rollback();
      assertAborts(
          d,
          "SELECT id FROM sn.t WHERE id IN (SELECT id FROM sn.u WHERE id < 5)",
          at + ": a subquery");
      d.rollback();
      assertThat(rows(d, "SELECT COUNT(*) FROM sn.t WHERE id <= 20"))
          .as("%s: a table without rows in flight passes", at)
          .containsExactly("20");
      d.commit();
      // in autocommit, the check and the read share a transaction of their own
      d.setAutoCommit(true);
      try (Statement st = d.createStatement()) {
        assertThatThrownBy(() -> st.executeQuery("SELECT COUNT(*) FROM sn.u"))
            .as("%s: an autocommit aggregate checks its table", at)
            .isInstanceOf(SQLTransactionRollbackException.class);
        assertThat(d.getAutoCommit()).isTrue();
        st.setFetchSize(1);
        try (ResultSet rs =
            st.executeQuery(
                "SELECT id FROM sn.t WHERE id IN (SELECT id FROM sn.t WHERE id < 3) ORDER BY id")) {
          List<String> ids = new ArrayList<>();
          while (rs.next()) {
            ids.add(rs.getString(1));
          }
          assertThat(ids).as("%s: its rows outlive its transaction", at).containsExactly("1", "2");
        }
        assertThat(st.getFetchSize()).isEqualTo(1);
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sn.t")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isEqualTo(20);
        }
      }
      d.setAutoCommit(false);
      held.commit();

      // the snapshot holds across statements, and the first committer wins
      assertThat(rows(d, "SELECT x FROM sn.t WHERE id = 4")).containsExactly("a");
      scalarDbSet(tm, 4, "s");
      assertThat(rows(d, "SELECT x FROM sn.t WHERE id = 4"))
          .as("%s: the snapshot holds", at)
          .containsExactly("a");
      assertThat(rows(d, "SELECT COUNT(*) FROM sn.t WHERE x = 's'"))
          .as("%s: a checked read sees the snapshot too", at)
          .containsExactly("0");
      try (Statement st = d.createStatement()) {
        assertThatThrownBy(() -> st.executeUpdate("UPDATE sn.t SET v = 1 WHERE id = 4"))
            .as("%s: first-committer-wins against ScalarDB", at)
            .isInstanceOf(SQLException.class)
            .extracting(ex -> ((SQLException) ex).getSQLState())
            .isEqualTo("40001");
      }
      d.rollback();
      // a locking read of a row changed after the snapshot, which MySQL and MariaDB would return
      // as committed now
      for (String lock : List.of("FOR UPDATE", pg ? "FOR SHARE" : "LOCK IN SHARE MODE")) {
        rows(d, "SELECT x FROM sn.t WHERE id = 4");
        scalarDbSet(tm, 4, lock);
        assertThatThrownBy(() -> rows(d, "SELECT x FROM sn.t WHERE id = 4 " + lock))
            .as("%s: %s of a row changed after the snapshot", at, lock)
            .isInstanceOf(SQLException.class)
            .extracting(ex -> ((SQLException) ex).getSQLState())
            .isEqualTo("40001");
        d.rollback();
      }

      if (!pg) {
        // the driver's key lookups of many keys lock those rows alone, not the table: a write
        // below the table, which only a scan of it would lock, does not wait (todo 220)
        StringBuilder many = new StringBuilder("INSERT INTO sn.t (id, x, v) VALUES ");
        for (int id = 100; id < 1100; id++) {
          many.append(id == 100 ? "" : ", ").append("(").append(id).append(", 'm', 0)");
        }
        try (Statement st = d.createStatement()) {
          assertThat(st.executeUpdate(many.toString())).isEqualTo(1000);
        }
        // MySQL's locking read of them too; MariaDB's of a range scans the metadata (todo 228)
        if (e.engine() == Engine.MYSQL) {
          assertThat(rows(d, "SELECT id FROM sn.t WHERE id >= 100 FOR UPDATE")).hasSize(1000);
        }
        try (Connection v = e.open();
            Statement st = v.createStatement()) {
          st.execute("SET SESSION innodb_lock_wait_timeout = 1");
          st.executeUpdate("INSERT INTO sn.t_data (id, x, v) VALUES (-5, 'far', 0)");
          st.executeUpdate("DELETE FROM sn.t_data WHERE id = -5");
        }
        d.rollback();
      }

      // SKIP LOCKED with LIMIT returns the first row not locked, the queue (todo 221)
      try (Connection other = e.openDriver()) {
        other.setAutoCommit(false);
        rows(other, "SELECT x FROM sn.t WHERE id = 7 FOR UPDATE");
        assertThat(
                rows(
                    d,
                    "SELECT id FROM sn.t WHERE id >= 7 ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED"))
            .as("%s: SKIP LOCKED with LIMIT", at)
            .containsExactly("8");
        d.rollback();
        other.rollback();
      }

      // a write to a row in flight aborts at once, whatever the wait allows
      held = prepared(tpc, "t", 5, "b");
      long started = System.nanoTime();
      try (Statement st = d.createStatement()) {
        assertThatThrownBy(() -> st.executeUpdate("UPDATE sn.t SET v = 1 WHERE id = 5"))
            .as("%s: a write to a row in flight", at)
            .isInstanceOf(SQLTransactionRollbackException.class);
      }
      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .as("%s: without the wait", at)
          .isLessThan(Duration.ofSeconds(4));
      d.rollback();
      // a locking read aborts on it too, by its after image
      assertAborts(d, "SELECT id FROM sn.t WHERE x = 'b' FOR UPDATE", at + ": a locking read");
      d.rollback();
      // NOWAIT too: a row in flight aborts, it is not a lock to report (todo 175)
      assertAborts(d, "SELECT id FROM sn.t WHERE id = 5 FOR UPDATE NOWAIT", at + ": NOWAIT");
      d.rollback();
      held.rollback();

      // and a write goes through where nothing is in flight
      try (Statement st = d.createStatement()) {
        assertThat(rows(d, "SELECT x FROM sn.t WHERE id = 6")).containsExactly("a");
        assertThat(st.executeUpdate("UPDATE sn.t SET x = 'd' WHERE id = 6")).isEqualTo(1);
      }
      d.commit();
      assertThat(rows(d, "SELECT x FROM sn.t WHERE id = 6")).containsExactly("d");
      d.commit();
      scalarDbSet(tm, 6, "a");
      scalarDbSet(tm, 4, "a");
    }
  }

  private static void assertAborts(Connection d, String sql, String what) {
    assertThatThrownBy(() -> rows(d, sql))
        .as(what)
        .isInstanceOf(SQLTransactionRollbackException.class);
  }

  private static void assertWriteAborts(Connection d, String sql, String what) {
    assertThatThrownBy(
            () -> {
              try (Statement st = d.createStatement()) {
                st.executeUpdate(sql);
              }
            })
        .as(what)
        .isInstanceOf(SQLTransactionRollbackException.class);
  }

  private static TwoPhaseCommitTransaction prepared(
      TwoPhaseCommitTransactionManager tpc, String table, int id, String x) throws Exception {
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(update(table, id, x));
    tx.prepare();
    return tx;
  }

  private static void scalarDbSet(DistributedTransactionManager tm, int id, String x)
      throws Exception {
    DistributedTransaction tx = tm.begin();
    tx.update(update("t", id, x));
    tx.commit();
  }

  private static Insert insert(String table, int id, String x) {
    return insert(table, id, x, 0);
  }

  private static Insert insert(String table, int id, String x, int v) {
    return Insert.newBuilder()
        .namespace("sn")
        .table(table)
        .partitionKey(Key.ofInt("id", id))
        .textValue("x", x)
        .intValue("v", v)
        .build();
  }

  private static Update update(String table, int id, String x) {
    return Update.newBuilder()
        .namespace("sn")
        .table(table)
        .partitionKey(Key.ofInt("id", id))
        .textValue("x", x)
        .build();
  }
}
