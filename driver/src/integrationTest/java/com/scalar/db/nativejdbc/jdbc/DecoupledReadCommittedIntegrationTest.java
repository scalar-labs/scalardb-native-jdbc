package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.N;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.id;
import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Todo 336 on PostgreSQL, a decoupled table at READ COMMITTED: the confirmation tests of the brief,
 * each the design's statement written out by hand on the vendor connection, or the driver's own
 * behaviour where the test is about it, run before any builder (design 4.3, "The keyed write at
 * READ COMMITTED on a decoupled table" and "The write beyond the key ...").
 */
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "ODR_OPEN_DATABASE_RESOURCE",
      "OBL_UNSATISFIED_OBLIGATION"
    },
    justification =
        "statements written in the test sources, their names from the catalog, closed with their"
            + " connection")
class DecoupledReadCommittedIntegrationTest {

  private static final Engines PG = Engines.POSTGRESQL;
  private static final String NS = "rcd";
  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");
  private static TransactionFactory factory;
  private static Catalog catalog;
  private static TableInfo imp;
  private static TableInfo made;

  @BeforeAll
  static void setUp() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Engines.selected().contains(PG), "PostgreSQL is not selected");
    ScalarDbNames.on(PG);
    factory = PG.scalarDb();
    try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      for (String table : List.of("imp", "made", "big", "mil")) {
        if (admin.namespaceExists(NS) && admin.tableExists(NS, table)) {
          admin.dropTable(NS, table);
        }
      }
      if (admin.namespaceExists(NS)) {
        admin.dropNamespace(NS);
      }
    }
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "DROP SCHEMA IF EXISTS " + NS + " CASCADE",
          "CREATE SCHEMA " + NS,
          "CREATE TABLE " + NS + ".imp (id INT PRIMARY KEY, x TEXT, v INT)",
          "INSERT INTO " + NS + ".imp SELECT g, 'a', 0 FROM generate_series(1, 20) g",
          "CREATE TABLE " + NS + ".big (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO " + NS + ".big SELECT g, 'a' FROM generate_series(1, 5000) g");
    }
    try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
      admin.createNamespace(NS, true);
      admin.importTable(NS, "imp", DECOUPLED, Map.of());
      admin.importTable(NS, "big", DECOUPLED, Map.of());
      admin.createTable(
          NS,
          "made",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addColumn("v", DataType.INT)
              .addPartitionKey("id")
              .build(),
          DECOUPLED);
    }
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 20; i++) {
        tx.insert(
            Insert.newBuilder()
                .namespace(NS)
                .table("made")
                .partitionKey(id(i))
                .textValue("x", "a")
                .intValue("v", 0)
                .build());
      }
      tx.commit();
    }
    catalog = new Catalog(Engine.POSTGRESQL, Catalog.DEFAULT_METADATA_SCHEMA, PG::open);
    try (Connection c = PG.open()) {
      List<Setup.Result> set =
          Setup.run(
              catalog, c, t -> t.namespace().equals(NS), Duration.ofMinutes(1), List.of(), true);
      assertThat(set).allMatch(Setup.Result::succeeded, "succeeded");
    }
    imp = catalog.lookup(List.of(), NS, "imp");
    made = catalog.lookup(List.of(), NS, "made");
    // rows written by an application beside ScalarDB after the setup: keys without a metadata row
    try (Connection c = PG.open()) {
      PG.execute(c, "INSERT INTO " + NS + ".imp VALUES (30, 'a', 0), (31, 'a', 0)");
    }
  }

  private static String data(TableInfo t) {
    return NS + "." + t.dataTable();
  }

  private static String meta(TableInfo t) {
    return NS + "." + Objects.requireNonNull(t.metadataTable());
  }

  /**
   * The design's keyed statement for {@code UPDATE T SET x = ? WHERE id = ?} (or the DELETE), by
   * hand: the key's metadata row in a CTE as the snapshot reads it, the DML as written on the data
   * table returning its key, the clear-table stamp with the CAS {@code EXISTS} on the CTE, and the
   * row of counts and the snapshot's tx_id, version and state. Binds: the key; the DML's (x, id, or
   * id alone for the DELETE); then the stamp's three, where it has them.
   */
  private static String keyed(TableInfo t, boolean delete) {
    boolean imported = t.layout() == Layout.DECOUPLED_IMPORTED;
    String cas =
        " AND COALESCE(m.tx_state, 3) = 3 AND EXISTS (SELECT 1 FROM scalardb_native_keys c WHERE"
            + " c.scalardb_native_m0 = m.id AND c.scalardb_native_id IS NOT DISTINCT FROM m.tx_id"
            + " AND c.scalardb_native_version IS NOT DISTINCT FROM m.tx_version)";
    String dml =
        delete
            ? "DELETE FROM " + data(t) + " t WHERE id = ? RETURNING t.id AS scalardb_native_w0"
            : "UPDATE "
                + data(t)
                + " t SET x = ? WHERE id = ? RETURNING t.id AS scalardb_native_w0";
    String stamp;
    if (imported) {
      stamp =
          "INSERT INTO "
              + meta(t)
              + " AS m (id, tx_id, tx_state, tx_version, tx_prepared_at, tx_committed_at) SELECT"
              + " w.scalardb_native_w0, ?, 3, 1, ?, ? FROM scalardb_native_w w ON CONFLICT (id) DO"
              + " UPDATE SET tx_id = EXCLUDED.tx_id, tx_prepared_at = EXCLUDED.tx_prepared_at,"
              + " tx_committed_at = EXCLUDED.tx_committed_at, tx_state = 3, tx_version = CASE"
              + " WHEN m.tx_id IS NULL THEN 1 ELSE COALESCE(m.tx_version, 0) + 1 END WHERE TRUE"
              + cas;
    } else if (delete) {
      stamp =
          "DELETE FROM "
              + meta(t)
              + " m USING scalardb_native_w w WHERE m.id = w.scalardb_native_w0"
              + cas;
    } else {
      stamp =
          "UPDATE "
              + meta(t)
              + " m SET tx_id = ?, tx_prepared_at = ?, tx_committed_at = ?, tx_state = 3,"
              + " tx_version = CASE WHEN m.tx_id IS NULL THEN 1 ELSE COALESCE(m.tx_version, 0) +"
              + " 1 END FROM scalardb_native_w w WHERE m.id = w.scalardb_native_w0"
              + cas;
    }
    return "WITH scalardb_native_keys (scalardb_native_m0, scalardb_native_id,"
        + " scalardb_native_version, scalardb_native_state) AS (SELECT s.id, s.tx_id,"
        + " s.tx_version, s.tx_state FROM "
        + meta(t)
        + " s WHERE id = ?), scalardb_native_w AS ("
        + dml
        + "), scalardb_native_s AS ("
        + stamp
        + " RETURNING 1) SELECT (SELECT count(*) FROM scalardb_native_w), (SELECT count(*) FROM"
        + " scalardb_native_s), k.scalardb_native_id, k.scalardb_native_version,"
        + " k.scalardb_native_state FROM (SELECT 1) scalardb_native_one LEFT JOIN"
        + " scalardb_native_keys k ON TRUE";
  }

  /** Runs {@link #keyed} for key {@code id}: written, stamped, then the snapshot's three. */
  private static List<String> runKeyed(Connection c, TableInfo t, boolean delete, int id)
      throws SQLException {
    boolean imported = t.layout() == Layout.DECOUPLED_IMPORTED;
    try (PreparedStatement ps = c.prepareStatement(keyed(t, delete))) {
      int i = 1;
      ps.setInt(i++, id);
      if (!delete) {
        ps.setString(i++, "w");
      }
      ps.setInt(i++, id);
      if (imported || !delete) {
        ps.setString(i++, "driver-tx");
        ps.setLong(i++, 1000L);
        ps.setLong(i++, 1000L);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rows(rs);
      }
    }
  }

  private static List<String> metaRow(Connection c, TableInfo t, int id) throws SQLException {
    return rows(c, "SELECT tx_id, tx_version, tx_state FROM " + meta(t) + " WHERE id = " + id);
  }

  /**
   * Test 1: the CAS sees the newest metadata row and the snapshot's. B holds the data row and the
   * metadata row, changed; A's statement takes its snapshot first (B's old version into the CTE),
   * its DML waits on the data row; once B commits, A's DML writes the row and its stamp, on the
   * metadata row as B left it, finds a version other than the snapshot's: written 1, stamped 0.
   * Without B: written 1, stamped 1, the version raised by one, the tx_id the statement's. For the
   * imported table's upsert, the created table's UPDATE, and the created table's DELETE.
   */
  @Test
  void test1_theCasSeesTheNewestRowAndTheSnapshots() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection a = PG.open();
        Connection b = PG.open()) {
      a.setAutoCommit(false);
      b.setAutoCommit(false);
      record Case(TableInfo t, boolean delete, int raced, int alone) {}
      for (Case k :
          List.of(
              new Case(imp, false, 1, 2),
              new Case(made, false, 1, 2),
              new Case(made, true, 3, 4))) {
        String what =
            (k.t() == imp ? "imported" : "created") + (k.delete() ? " DELETE" : " UPDATE");
        // give the rows a ScalarDB-like committed metadata row with a tx_id, so that a raise is
        // a raise and not the NULL tx_id's version 1
        PG.execute(
            b,
            "UPDATE "
                + meta(k.t())
                + " SET tx_id = 'seed', tx_version = 5, tx_state = 3 WHERE id IN ("
                + k.raced()
                + ", "
                + k.alone()
                + ")");
        b.commit();
        PG.execute(
            b,
            "UPDATE " + data(k.t()) + " SET x = x WHERE id = " + k.raced(),
            "UPDATE "
                + meta(k.t())
                + " SET tx_id = 'b', tx_version = tx_version + 1 WHERE id = "
                + k.raced());
        Future<List<String>> raced = pool.submit(() -> runKeyed(a, k.t(), k.delete(), k.raced()));
        Thread.sleep(700);
        assertThat(raced.isDone()).as(what + ": A's DML waits on B's data row").isFalse();
        b.commit();
        List<String> got = raced.get(20, TimeUnit.SECONDS);
        System.out.println("[336] test 1, " + what + ", B committed in between: " + got);
        // written 1, stamped 0, the snapshot's tx_id and version as before B
        assertThat(got).as(what + ": raced").containsExactly("1/0/seed/5/3");
        a.rollback();
        List<String> alone = runKeyed(a, k.t(), k.delete(), k.alone());
        System.out.println("[336] test 1, " + what + ", alone: " + alone);
        assertThat(alone).as(what + ": alone").containsExactly("1/1/seed/5/3");
        List<String> after = metaRow(a, k.t(), k.alone());
        System.out.println("[336] test 1, " + what + ", the metadata row after: " + after);
        if (k.delete()) {
          assertThat(after).as(what + ": the metadata row removed").isEmpty();
        } else {
          assertThat(after).as(what + ": stamped").containsExactly("driver-tx/6/3");
        }
        a.rollback();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Test 2: a key without a metadata row on an imported table is stamped by the upsert's insert at
   * version 1, the row returned NULL in its three; and a driver read of that key records an anchor
   * that compares equal to NULLs.
   */
  @Test
  void test2_aKeyWithoutMetadataIsInsertedAtVersion1() throws Exception {
    try (Connection a = PG.open()) {
      a.setAutoCommit(false);
      assertThat(metaRow(a, imp, 30)).as("no metadata row before").isEmpty();
      List<String> got = runKeyed(a, imp, false, 30);
      System.out.println("[336] test 2, a key without a metadata row: " + got);
      assertThat(got).containsExactly("1/1/null/null/null");
      assertThat(metaRow(a, imp, 30)).containsExactly("driver-tx/1/3");
      a.rollback();
    }
    try (Connection d = PG.openDriver()) {
      d.setAutoCommit(false);
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      assertThat(rows(d, "SELECT x FROM " + NS + ".imp WHERE id = 31")).containsExactly("a");
      Anchors anchors = anchors(d);
      String table = new Writes.Names(Engine.POSTGRESQL, imp).table;
      assertThat(anchors.recorded(table)).as("the key read recorded an anchor").isTrue();
      assertThat(anchors.unchanged(table, new Object[] {31}, null, null))
          .as("the anchor of a key without a metadata row equals NULLs")
          .isTrue();
      assertThat(anchors.unchanged(table, new Object[] {31}, "someone", 1L))
          .as("and not a version")
          .isFalse();
      d.rollback();
    }
  }

  /** Test 3: an aggregate read at READ COMMITTED in a transaction records no anchor. */
  @Test
  void test3_anAggregateRecordsNoAnchor() throws Exception {
    try (Connection d = PG.openDriver()) {
      d.setAutoCommit(false);
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      for (TableInfo t : List.of(imp, made)) {
        String table = new Writes.Names(Engine.POSTGRESQL, t).table;
        assertThat(
                rows(d, "SELECT SUM(v) FROM " + NS + "." + t.table() + " WHERE id BETWEEN 5 AND 9"))
            .hasSize(1);
        boolean recorded = anchors(d).recorded(table);
        System.out.println(
            "[336] test 3, " + t.table() + ": an aggregate recorded an anchor: " + recorded);
        assertThat(recorded).as(t.table() + ": an aggregate records no anchor").isFalse();
        d.rollback();
      }
    }
  }

  /**
   * Test 4: a row the transaction wrote before: a read, a keyed write, a second keyed write of the
   * same key in one transaction, both succeed, the version raised twice.
   */
  @Test
  void test4_aRowTheTransactionWroteBefore() throws Exception {
    try (Connection d = PG.openDriver();
        Connection v = PG.open()) {
      d.setAutoCommit(false);
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      for (TableInfo t : List.of(imp, made)) {
        String sql = NS + "." + t.table();
        PG.execute(v, "UPDATE " + meta(t) + " SET tx_id = 'seed', tx_version = 5 WHERE id = 10");
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 10")).hasSize(1);
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          ps.setString(1, "first");
          ps.setInt(2, 10);
          assertThat(ps.executeUpdate()).isEqualTo(1);
          ps.setString(1, "second");
          assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        d.commit();
        assertThat(rows(v, "SELECT tx_version FROM " + meta(t) + " WHERE id = 10"))
            .as(t.table() + ": raised twice")
            .containsExactly("7");
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 10")).containsExactly("second");
        d.commit();
      }
    }
  }

  /**
   * Test 5: the before-image rule beyond the key. A ScalarDB transaction holds a row prepared whose
   * before image matches the predicate on a non-key column and whose after image does not: the
   * design's statement beyond the key, by hand, returns its before-image column true, written 0.
   */
  @Test
  void test5_aBeforeImageAloneMatchedAborts() throws Exception {
    try (TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager();
        Connection a = PG.open()) {
      a.setAutoCommit(false);
      for (TableInfo t : List.of(imp, made)) {
        String scalarDbName =
            t.layout() == Layout.DECOUPLED_IMPORTED ? t.virtualTable() : t.table();
        PG.execute(a, "UPDATE " + data(t) + " SET x = 'pick' WHERE id = 12");
        a.commit();
        TwoPhaseCommitTransaction held = tpc.begin();
        held.update(
            Update.newBuilder()
                .namespace(NS)
                .table(N(Objects.requireNonNull(scalarDbName)))
                .partitionKey(id(12))
                .textValue("x", "moved")
                .build());
        held.prepare();
        try {
          String before = SplitRead.inFlight(Engine.POSTGRESQL, t, t.dataColumns());
          String sql =
              "WITH scalardb_native_w AS (UPDATE "
                  + data(t)
                  + " t SET v = 9 WHERE x = 'pick' RETURNING t.id AS scalardb_native_w0),"
                  + " scalardb_native_s AS (UPDATE "
                  + meta(t)
                  + " m SET tx_id = 'driver-tx', tx_state = 3, tx_version = CASE WHEN m.tx_id IS"
                  + " NULL THEN 1 ELSE COALESCE(m.tx_version, 0) + 1 END FROM scalardb_native_w w"
                  + " WHERE m.id = w.scalardb_native_w0 AND COALESCE(m.tx_state, 3) = 3 AND EXISTS"
                  + " (SELECT 1 FROM "
                  + meta(t)
                  + " s WHERE s.id = m.id AND s.tx_id IS NOT DISTINCT FROM m.tx_id AND"
                  + " s.tx_version IS NOT DISTINCT FROM m.tx_version) RETURNING 1) SELECT (SELECT"
                  + " count(*) FROM scalardb_native_w), (SELECT count(*) FROM scalardb_native_s),"
                  + " EXISTS (SELECT 1 FROM "
                  + before
                  + " t WHERE x = 'pick')";
          List<String> got;
          try (PreparedStatement ps = a.prepareStatement(sql);
              ResultSet rs = ps.executeQuery()) {
            got = rows(rs);
          }
          System.out.println("[336] test 5, " + t.table() + ": " + got);
          assertThat(got).as(t.table()).containsExactly("0/0/t");
          a.rollback();
        } finally {
          held.rollback();
        }
      }
    }
  }

  /**
   * Form 1: the locking read's first statement at READ COMMITTED on a decoupled table is the lock
   * over the rows as they are with their keys appended and nothing else, the metadata table named
   * only by the second statement; through the driver the rows come back with the user's columns
   * alone, and a write of a locked row holds to the version the second statement read.
   */
  @Test
  void form1_theLocksFirstStatementHasItsKeysOnly() throws Exception {
    try (Connection v = PG.open()) {
      Router router = new Router(Engine.POSTGRESQL, catalog, new Session(Engine.POSTGRESQL));
      router.setIsolation(Connection.TRANSACTION_READ_COMMITTED);
      for (TableInfo t : List.of(imp, made)) {
        String lock = "SELECT x FROM " + NS + "." + t.table() + " WHERE id = ? FOR UPDATE";
        Writes.LockPlan plan =
            (Writes.LockPlan) Objects.requireNonNull(router.route(v, lock).write);
        Writes.Step fast = Objects.requireNonNull(plan.fastRc, "the one-round-trip lock");
        int split = fast.sql.indexOf("; ");
        String first = fast.sql.substring(0, split);
        String second = fast.sql.substring(split + 2);
        System.out.println("[336] form 1, " + t.table() + ": " + first);
        // the keys alone over the rows as they are: the metadata table named nowhere
        assertThat(first)
            .as(t.table() + ": the lock names no metadata table")
            .doesNotContain(Objects.requireNonNull(t.metadataTable()))
            .contains("FOR UPDATE");
        assertThat(second).contains(Objects.requireNonNull(t.metadataTable()));
      }
    }
    try (Connection d = PG.openDriver();
        Connection other = PG.openDriver()) {
      d.setAutoCommit(false);
      for (TableInfo t : List.of(imp, made)) {
        String sql = NS + "." + t.table();
        try (PreparedStatement ps =
                d.prepareStatement(
                    "SELECT id, x FROM " + sql + " WHERE id IN (14, 15) FOR UPDATE");
            ResultSet rs = ps.executeQuery()) {
          assertThat(rs.getMetaData().getColumnCount()).as("the user's columns alone").isEqualTo(2);
          assertThat(rows(rs)).hasSize(2);
        }
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 14")).hasSize(1);
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          ps.setString(1, "locked");
          ps.setInt(2, 14);
          assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        d.commit();
        assertThat(rows(other, "SELECT x FROM " + sql + " WHERE id = 14"))
            .containsExactly("locked");
      }
    }
  }

  private static void assertAborts(Connection d, String sql, String message) {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.createStatement().executeUpdate(sql))
        .isInstanceOf(java.sql.SQLTransactionRollbackException.class)
        .hasMessageContaining(message)
        .extracting(e -> ((SQLException) e).getSQLState())
        .isEqualTo("40001");
  }

  private static TwoPhaseCommitTransaction prepared(
      TwoPhaseCommitTransactionManager tpc, TableInfo t, int id, String x) throws Exception {
    String name = t.layout() == Layout.DECOUPLED_IMPORTED ? t.virtualTable() : t.table();
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(
        Update.newBuilder()
            .namespace(NS)
            .table(Objects.requireNonNull(name))
            .partitionKey(id(id))
            .textValue("x", x)
            .build());
    tx.prepare();
    return tx;
  }

  /**
   * Form 2 through the driver, without a wait: a missing key counts 0; a key written counts 1 and
   * ScalarDB reads it; an anchor moved since the read aborts; a row in flight aborts; a row moved
   * between the statement's snapshot and its lock aborts (written, not stamped); a batch's entries
   * go as the merged statement; with a wait configured the slim statement keeps the wait.
   */
  @Test
  void form2_theKeyedWriteThroughTheDriver() throws Exception {
    java.util.Properties waits = new java.util.Properties();
    waits.setProperty("scalar.db.native_jdbc.inflight_wait_millis", "10000");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = PG.openDriver();
        Connection other = PG.openDriver();
        Connection waiting = PG.openDriver(waits);
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      d.setAutoCommit(false);
      for (TableInfo t : List.of(imp, made)) {
        String sql = NS + "." + t.table();
        String what = t.table();
        assertThat(
                d.createStatement().executeUpdate("UPDATE " + sql + " SET x = 'z' WHERE id = 999"))
            .isZero();
        assertThat(
                d.createStatement().executeUpdate("UPDATE " + sql + " SET x = 'w' WHERE id = 15"))
            .isEqualTo(1);
        d.commit();
        assertThat(rows(other, "SELECT x FROM " + sql + " WHERE id = 15")).containsExactly("w");
        // the anchor: read, changed by another connection, written
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 16")).containsExactly("a");
        assertThat(
                other
                    .createStatement()
                    .executeUpdate("UPDATE " + sql + " SET x = 'o' WHERE id = 16"))
            .isEqualTo(1);
        assertAborts(
            d, "UPDATE " + sql + " SET x = 'd' WHERE id = 16", "changed by another one since");
        d.rollback();
        // a row in flight
        TwoPhaseCommitTransaction held = prepared(tpc, t, 17, "p");
        try {
          assertAborts(d, "UPDATE " + sql + " SET x = 'd' WHERE id = 17", "being written");
          d.rollback();
          assertThat(rows(other, "SELECT x FROM " + sql + " WHERE id = 17")).containsExactly("a");
        } finally {
          held.rollback();
        }
        // moved between the snapshot and the lock: another connection's keyed write holds the row
        other.setAutoCommit(false);
        assertThat(
                other
                    .createStatement()
                    .executeUpdate("UPDATE " + sql + " SET x = 'o' WHERE id = 18"))
            .isEqualTo(1);
        Future<Object> raced =
            pool.submit(
                () -> {
                  try {
                    return d.createStatement()
                        .executeUpdate("UPDATE " + sql + " SET x = 'd' WHERE id = 18");
                  } catch (SQLException e) {
                    return e;
                  }
                });
        Thread.sleep(700);
        assertThat(raced.isDone()).as(what + ": waits on the row").isFalse();
        other.commit();
        other.setAutoCommit(true);
        Object got = raced.get(20, TimeUnit.SECONDS);
        assertThat(got)
            .as(what + ": raced")
            .isInstanceOf(java.sql.SQLTransactionRollbackException.class);
        assertThat(((SQLException) got).getMessage()).contains("changed after it was read");
        d.rollback();
        assertThat(rows(other, "SELECT x FROM " + sql + " WHERE id = 18")).containsExactly("o");
        // a batch's entries, merged
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          for (int id = 5; id <= 7; id++) {
            ps.setString(1, "b");
            ps.setInt(2, id);
            ps.addBatch();
          }
          assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
        }
        d.commit();
        assertThat(rows(other, "SELECT x FROM " + sql + " WHERE id BETWEEN 5 AND 7"))
            .containsExactly("b", "b", "b");
        // with a wait, the slim statement: the write waits on the row in flight and goes on
        waiting.setAutoCommit(false);
        TwoPhaseCommitTransaction wait = prepared(tpc, t, 19, "p");
        Future<Integer> write =
            pool.submit(
                () ->
                    waiting
                        .createStatement()
                        .executeUpdate("UPDATE " + sql + " SET x = 'f' WHERE id = 19"));
        Thread.sleep(700);
        assertThat(write.isDone()).as(what + ": waits on the row in flight").isFalse();
        wait.rollback();
        assertThat(write.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        waiting.commit();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Form 3 through the driver: a write beyond the key counts its rows; a row in flight whose before
   * image alone matches aborts; after a read that recorded anchors of the table the fused statement
   * runs, with the same count.
   */
  @Test
  void form3_theWriteBeyondTheKeyThroughTheDriver() throws Exception {
    try (Connection d = PG.openDriver();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      d.setAutoCommit(false);
      for (TableInfo t : List.of(imp, made)) {
        String sql = NS + "." + t.table();
        assertThat(
                d.createStatement()
                    .executeUpdate("UPDATE " + sql + " SET v = 3 WHERE id BETWEEN 1 AND 3"))
            .isEqualTo(3);
        d.commit();
        assertThat(rows(d, "SELECT SUM(v) FROM " + sql + " WHERE id BETWEEN 1 AND 3"))
            .containsExactly("9");
        d.commit();
        PG.execute(d, "UPDATE " + sql + " SET x = 'pick2' WHERE id = 13");
        d.commit();
        TwoPhaseCommitTransaction held = prepared(tpc, t, 13, "moved");
        try {
          // a predicate on another column takes the fused statement, which aborts as well
          assertAborts(d, "UPDATE " + sql + " SET v = 9 WHERE x = 'pick2'", "being written");
          d.rollback();
          // a key-only predicate over the row in flight: its stamp refused, an abort
          assertAborts(d, "UPDATE " + sql + " SET v = 9 WHERE id BETWEEN 12 AND 13", "");
          d.rollback();
        } finally {
          held.rollback();
        }
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 4")).containsExactly("a");
        assertThat(
                d.createStatement()
                    .executeUpdate("UPDATE " + sql + " SET v = 4 WHERE id IN (4, 20)"))
            .isEqualTo(2);
        d.commit();
      }
    }
  }

  /**
   * Todo 337, candidate 1: the lock's versions read from the metadata table alone. A key locked
   * without a metadata row (a row the application inserted after the setup) takes the NULL-metadata
   * anchor, and its write after the lock goes through, the metadata row inserted at version 1; a
   * tombstone under the predicate (an imported table's DELETE keeps its metadata row, 4.5) is shown
   * by the read and not locked, and changes nothing.
   */
  @Test
  void todo337_theLocksVersionsReadFromTheMetadataTableAlone() throws Exception {
    try (Connection v = PG.open()) {
      Router router = new Router(Engine.POSTGRESQL, catalog, new Session(Engine.POSTGRESQL));
      router.setIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Writes.LockPlan plan =
          (Writes.LockPlan)
              Objects.requireNonNull(
                  router.route(v, "SELECT x FROM " + NS + ".imp WHERE id = ? FOR UPDATE").write);
      String second = Objects.requireNonNull(plan.fastRc).sql;
      second = second.substring(second.indexOf("; ") + 2);
      System.out.println("[337] the versions read: " + second);
      assertThat(second)
          .contains("FROM " + "\"" + NS + "\".\"" + imp.metadataTable() + "\" imp WHERE id = ?")
          .doesNotContain("\"" + NS + "\".\"imp\" ");
      PG.execute(v, "INSERT INTO " + NS + ".imp VALUES (40, 'a', 0), (41, 'a', 0), (42, 'a', 0)");
    }
    try (Connection d = PG.openDriver();
        Connection v = PG.open()) {
      d.setAutoCommit(false);
      String table = new Writes.Names(Engine.POSTGRESQL, imp).table;
      // a key without a metadata row, locked
      assertThat(metaRow(v, imp, 40)).isEmpty();
      assertThat(rows(d, "SELECT x FROM " + NS + ".imp WHERE id = 40 FOR UPDATE"))
          .containsExactly("a");
      assertThat(anchors(d).recorded(table)).isTrue();
      assertThat(anchors(d).unchanged(table, new Object[] {40}, null, null)).isTrue();
      assertThat(
              d.createStatement().executeUpdate("UPDATE " + NS + ".imp SET x = 'l' WHERE id = 40"))
          .isEqualTo(1);
      d.commit();
      assertThat(rows(v, "SELECT tx_version, tx_state FROM " + meta(imp) + " WHERE id = 40"))
          .containsExactly("1/3");
      // a tombstone under the predicate: 41 deleted through the driver keeps its metadata row
      assertThat(d.createStatement().executeUpdate("DELETE FROM " + NS + ".imp WHERE id = 41"))
          .isEqualTo(1);
      d.commit();
      assertThat(metaRow(v, imp, 41)).hasSize(1);
      assertThat(rows(d, "SELECT id FROM " + NS + ".imp WHERE id BETWEEN 41 AND 42 FOR UPDATE"))
          .containsExactly("42");
      assertThat(
              d.createStatement().executeUpdate("UPDATE " + NS + ".imp SET x = 'm' WHERE id = 42"))
          .isEqualTo(1);
      d.commit();
      assertThat(rows(v, "SELECT x FROM " + NS + ".imp WHERE id = 42")).containsExactly("m");
    }
  }

  /**
   * Todo 337, candidate 3: on PostgreSQL a decoupled table's committed copy probes the in-flight
   * index once for the statement before its per-row probe. With nothing in flight the per-row set
   * is never built; with rows in flight the gate is kept and the count is right. The plans as
   * EXPLAIN shows them, printed for the log.
   */
  @Test
  void todo337_thePerRowProbeBehindOneProbeOfTheIndex() throws Exception {
    try (Connection v = PG.open()) {
      Router router = new Router(Engine.POSTGRESQL, catalog, new Session(Engine.POSTGRESQL));
      router.setIsolation(Connection.TRANSACTION_READ_COMMITTED);
      String read = "SELECT COUNT(*) FROM " + NS + ".big WHERE id BETWEEN 100 AND 4000";
      String routed = router.route(v, read).sql;
      String metaTable = "\"" + NS + "\".\"" + big().metadataTable() + "\"";
      System.out.println("[337] the routed read: " + routed);
      assertThat(routed)
          .contains(
              "(SELECT 1 FROM "
                  + metaTable
                  + " WHERE \"tx_state\" IN (1, 2) ORDER BY \"id\" LIMIT 1) IS NULL OR NOT"
                  + " EXISTS");
      assertThat(rows(v, read)).containsExactly("3901");
      // nothing in flight: the set the per-row probe reads is never built
      String clear = String.join("\n", rows(v, "EXPLAIN (ANALYZE, COSTS OFF) " + routed));
      System.out.println("[337] nothing in flight:\n" + clear);
      assertThat(clear).contains("never executed");
      // thousands in flight, work_mem small: the per-row probe, not a hashed set
      v.setAutoCommit(false);
      try {
        PG.execute(
            v,
            "UPDATE " + metaTable + " SET tx_state = 2 WHERE id <= 3000",
            "ANALYZE " + metaTable,
            "SET LOCAL work_mem = '64kB'");
        String held = String.join("\n", rows(v, "EXPLAIN (COSTS OFF) " + routed));
        System.out.println("[337] 3000 in flight, work_mem 64kB:\n" + held);
        // the gate kept; whether the set is hashed or probed per row is the planner's by size (a
        // 50,000-row table with 30,000 in flight probes per row, todo 337's log)
        // PostgreSQL 17 names the gate's InitPlan output (InitPlan 1).col1, 16 $0
        assertThat(held)
            .containsAnyOf("($0 IS NULL) OR (NOT ", "((InitPlan 1).col1 IS NULL) OR (NOT ");
        assertThat(rows(v, routed)).containsExactly("1000");
      } finally {
        v.rollback();
        v.setAutoCommit(true);
        PG.execute(v, "ANALYZE " + metaTable);
      }
    }
  }

  /**
   * Todo 337's follow-up (bc2077f): every probe of the whole in-flight index on PostgreSQL is its
   * first row in the index's order, which the planner takes from the partial index under any
   * statistics: each text the driver sends, run against a copy of the metadata table that was never
   * analyzed (200,000 rows, nothing in flight), plans no scan of it, where the probe spelled {@code
   * LIMIT 1} without the order scans it. And the setup analyzes the metadata table.
   */
  @Test
  void todo337_everyProbeOfTheIndexTakesItUnanalyzed() throws Exception {
    try (Connection v = PG.open()) {
      TableInfo t = big();
      String metaTable = "\"" + NS + "\".\"" + t.metadataTable() + "\"";
      String copy = "\"" + NS + "\".\"unanalyzed\"";
      PG.execute(
          v,
          "DROP TABLE IF EXISTS " + copy,
          "CREATE TABLE "
              + copy
              + " (LIKE "
              + metaTable
              + " INCLUDING ALL) WITH (autovacuum_enabled = off)",
          "INSERT INTO "
              + copy
              + " (id, tx_id, tx_state, tx_version) SELECT g, 't', 3, 1 FROM generate_series(1,"
              + " 200000) g");
      Router router = new Router(Engine.POSTGRESQL, catalog, new Session(Engine.POSTGRESQL));
      router.setIsolation(Connection.TRANSACTION_READ_COMMITTED);
      List<String> texts = new java.util.ArrayList<>();
      texts.add(router.inFlightCheck(t)); // the coarse check
      texts.add(router.route(v, "SELECT x FROM " + NS + ".big WHERE id BETWEEN 1 AND 10").sql);
      router.setIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      texts.add(router.route(v, "SELECT x FROM " + NS + ".big WHERE x = 'a'").sql);
      Writes.Plan write =
          Objects.requireNonNull(
              router.route(v, "UPDATE " + NS + ".big SET x = ? WHERE id = ?").write);
      texts.addAll(write.sql());
      String first = "\"tx_state\" IN (1, 2) ORDER BY \"id\" LIMIT 1";
      int probes = 0;
      for (String text : texts) {
        if (!text.contains(first)) {
          continue;
        }
        probes++;
        String plan = generic(v, text.replace(metaTable, copy));
        System.out.println("[337] unanalyzed: " + text + "\n" + plan);
        assertThat(plan).as(text).doesNotContain("Seq Scan on unanalyzed");
      }
      // the coarse check, the read's gate, the state at SNAPSHOT and the write's gate
      assertThat(probes).as("the texts with the probe").isGreaterThanOrEqualTo(4);
      String unordered =
          generic(v, "SELECT 1 FROM " + copy + " WHERE \"tx_state\" IN (1, 2) LIMIT 1");
      System.out.println("[337] unanalyzed, without the order:\n" + unordered);
      assertThat(unordered).contains("Seq Scan on unanalyzed");
      PG.execute(v, "DROP TABLE " + copy);
      List<String> planned =
          Setup.plan(catalog, v, x -> x.table().equals("big"), List.of()).get(0).actions();
      assertThat(planned).contains("ANALYZE " + metaTable);
    }
  }

  /**
   * A range read of a decoupled table in a transaction (design 4.2, todo 334): PostgreSQL forms no
   * equivalence class across an outer join's ON clause, so the committed copy's left join to the
   * metadata table repeats the predicate's key range there, and at a million rows the metadata side
   * is an index range scan, not a probe per row or a scan of the table; the rows read are the
   * vendor's.
   */
  @Test
  void aRangeReadInATransaction_reachesTheMetadataRowsByTheKeyRange() throws Exception {
    try (Connection v = PG.open()) {
      PG.execute(
          v,
          "CREATE TABLE " + NS + ".mil (id INT PRIMARY KEY, x TEXT)",
          "INSERT INTO " + NS + ".mil SELECT g, 'a' FROM generate_series(1, 1000000) g");
    }
    try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
      admin.importTable(NS, "mil", DECOUPLED, Map.of());
    }
    catalog.invalidate(); // the table ScalarDB has just imported
    TableInfo mil = catalog.lookup(List.of(), NS, "mil");
    String meta = "\"" + NS + "\".\"" + mil.metadataTable() + "\"";
    try (Connection v = PG.open()) {
      // the metadata rows as a backfill leaves them, written here in one statement
      PG.execute(
          v,
          "INSERT INTO "
              + meta
              + " (id, tx_id, tx_state, tx_version) SELECT g, 'seed', 3, 1 FROM"
              + " generate_series(1, 1000000) g");
      assertThat(
              Setup.run(
                  catalog, v, x -> x.table().equals("mil"), Duration.ofMinutes(1), List.of(), true))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    String sql = "SELECT x FROM " + NS + ".mil WHERE id > ? AND id < ?";
    try (Connection d = PG.openDriver();
        Connection v = PG.open()) {
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      d.setAutoCommit(false);
      String routed = d.nativeSQL(sql);
      System.out.println("[334] the range read in a transaction: " + routed);
      assertThat(routed)
          .contains(" m ON m.\"id\" = t.\"id\" AND m.\"id\" > ? AND m.\"id\" < ? WHERE");
      String plan = generic(v, routed);
      System.out.println("[334] its generic plan:\n" + plan);
      assertThat(plan)
          .as("both sides by the key range")
          .containsPattern(
              "Index Scan using \\S+ on "
                  + mil.metadataTable()
                  + " m\\s+Index Cond: \\(\\(id > \\$1\\) AND \\(id < \\$2\\)\\)")
          .doesNotContain("Seq Scan on " + mil.metadataTable());
      try (PreparedStatement ps = d.prepareStatement(sql)) {
        ps.setInt(1, 1000);
        ps.setInt(2, 1501);
        try (ResultSet rs = ps.executeQuery()) {
          int n = 0;
          while (rs.next()) {
            n++;
          }
          assertThat(n).isEqualTo(500);
        }
      }
      d.commit();
    }
  }

  /**
   * The generic plan of a text with the driver's {@code ?} placeholders, costs off: prepared on the
   * server, as pgjdbc would bind the {@code $n} of an EXPLAIN (GENERIC_PLAN) itself.
   */
  private static String generic(Connection v, String text) throws SQLException {
    StringBuilder numbered = new StringBuilder();
    List<String> nulls = new java.util.ArrayList<>();
    for (char c : text.toCharArray()) {
      if (c == '?') {
        nulls.add("NULL");
        numbered.append('$').append(nulls.size());
      } else {
        numbered.append(c);
      }
    }
    PG.execute(v, "SET plan_cache_mode = force_generic_plan", "PREPARE p337 AS " + numbered);
    try {
      return String.join(
          "\n",
          rows(
              v,
              "EXPLAIN (COSTS OFF) EXECUTE p337"
                  + (nulls.isEmpty() ? "" : "(" + String.join(", ", nulls) + ")")));
    } finally {
      PG.execute(v, "DEALLOCATE p337", "RESET plan_cache_mode");
    }
  }

  private static TableInfo big() throws SQLException {
    return catalog.lookup(List.of(), NS, "big");
  }

  /** The anchors of a driver connection, the transaction's versions read (design 4.2). */
  static Anchors anchors(Connection driver) throws Exception {
    Object h = Proxy.getInvocationHandler(driver);
    Field f = h.getClass().getDeclaredField("anchors");
    f.setAccessible(true);
    return (Anchors) f.get(h);
  }
}
