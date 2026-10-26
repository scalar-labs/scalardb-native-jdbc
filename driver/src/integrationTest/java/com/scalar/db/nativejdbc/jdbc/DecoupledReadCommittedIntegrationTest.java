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
    value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
    justification = "statements written in the test sources, their names from the catalog")
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
      for (String table : List.of("imp", "made")) {
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
          "INSERT INTO " + NS + ".imp SELECT g, 'a', 0 FROM generate_series(1, 20) g");
    }
    try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
      admin.createNamespace(NS, true);
      admin.importTable(NS, "imp", DECOUPLED, Map.of());
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
          Setup.run(catalog, c, t -> t.namespace().equals(NS), Duration.ofMinutes(1), List.of());
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

  /** The anchors of a driver connection, the transaction's versions read (design 4.2). */
  static Anchors anchors(Connection driver) throws Exception {
    Object h = Proxy.getInvocationHandler(driver);
    Field f = h.getClass().getDeclaredField("anchors");
    f.setAccessible(true);
    return (Anchors) f.get(h);
  }
}
