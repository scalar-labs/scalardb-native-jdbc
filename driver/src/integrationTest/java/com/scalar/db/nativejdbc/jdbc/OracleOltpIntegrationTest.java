package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.N;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.id;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.intType;
import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.BatchUpdateException;
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
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The OLTP fast paths of design 4.2 to 4.4 as built on Oracle (todo 335), one method per form, each
 * through the driver on ScalarDB tables of both layouts, with the round trips the form costs
 * counted by Oracle ({@code SQL*Net roundtrips to/from client}) and printed under {@code [335]} for
 * the todo's log; the facts the forms rest on are {@link OracleFormsIntegrationTest}'s.
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "OBL_UNSATISFIED_OBLIGATION", "ODR_OPEN_DATABASE_RESOURCE"},
    justification = "statements written in the test sources, closed with their connection")
class OracleOltpIntegrationTest {

  private static final Engines ORA = Engines.ORACLE;
  private static final String NS = "oo";
  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  /** A key type the driver supports on Oracle, with a row's key as a literal. */
  enum KeyType {
    K_N10("NUMBER(10)", "5"),
    // ScalarDB imports an integer NUMBER of up to 15 digits (design section 1)
    K_N15("NUMBER(15)", "900719925474099"),
    K_VC("VARCHAR2(10)", "'01'"),
    K_CH("CHAR(5)", "'a'"),
    K_BF("BINARY_FLOAT", "0.1"),
    K_BD("BINARY_DOUBLE", "0.1"),
    K_DT("DATE", "TO_DATE('2024-01-02 03:04:05', 'YYYY-MM-DD HH24:MI:SS')"),
    // not RAW: ScalarDB takes no BLOB key on Oracle (DB-CORE-10246)
    // milliseconds: ScalarDB's TIMESTAMP keeps no more, and the backfill matches the key by it
    K_TS("TIMESTAMP(6)", "TIMESTAMP '2024-01-02 03:04:05.123'");

    final String type;
    final String literal;

    KeyType(String type, String literal) {
      this.type = type;
      this.literal = literal;
    }

    String table() {
      return NS + "." + name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  private static Catalog catalog;
  private static TransactionFactory factory;

  @BeforeAll
  static void setUp() throws Exception {
    Assumptions.assumeTrue(Engines.selected().contains(ORA), "Oracle is not selected");
    ScalarDbNames.on(ORA);
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      admin.createCoordinatorTables(true);
      // what an external database keeps from an earlier run: the tables ScalarDB lists, then
      // every object left in the schema (the setup's views, a table a failed import left), which
      // ScalarDB's DROP USER (without CASCADE) would stop at
      for (String table : admin.getNamespaceTableNames(N(NS))) {
        try {
          admin.dropTable(N(NS), table, true);
        } catch (Exception e) {
          // a table a failed run half dropped: its objects go with the schema below
          System.out.println("[335] left over: " + table + ": " + e.getMessage());
        }
      }
      try (Connection c = ORA.open()) {
        ORA.execute(
            c,
            "BEGIN FOR o IN (SELECT object_name, object_type FROM all_objects WHERE owner = '"
                + N(NS)
                + "' AND object_type IN ('VIEW', 'TABLE')) LOOP EXECUTE IMMEDIATE 'DROP ' ||"
                + " o.object_type || ' \"' || '"
                + N(NS)
                + "' || '\".\"' || o.object_name || '\"' || CASE WHEN o.object_type = 'TABLE' THEN"
                + " ' CASCADE CONSTRAINTS' ELSE '' END; END LOOP; END;");
        // and what a failed import of an earlier run left in ScalarDB's own bookkeeping
        for (String bookkeeping : List.of("metadata", "virtual_tables")) {
          try (Statement st = c.createStatement()) {
            st.executeUpdate(
                "DELETE FROM \"scalardb\".\""
                    + bookkeeping
                    + "\" WHERE \"full_table_name\" LIKE '"
                    + N(NS)
                    + ".%'");
          } catch (SQLException e) {
            // not there yet
          }
        }
      }
      admin.dropNamespace(N(NS), true);
      admin.createNamespace(N(NS), true);
      for (String table : List.of("t", "ti")) {
        admin.createTable(
            N(NS),
            N(table),
            TableMetadata.newBuilder()
                .addColumn(N("id"), intType())
                .addColumn(N("x"), DataType.TEXT)
                .addColumn(N("v"), intType())
                .addPartitionKey(N("id"))
                .build(),
            table.equals("t") ? Map.of("transaction-metadata-decoupling", "true") : Map.of());
      }
    }
    factory = ORA.scalarDb();
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      // rows 1 to 70: each form writes its own range (form 1 counts whatever is there)
      for (int id = 1; id <= 70; id++) {
        for (String table : List.of("t", "ti")) {
          tx.insert(
              Insert.newBuilder()
                  .namespace(N(NS))
                  .table(N(table))
                  .partitionKey(id(id))
                  .textValue(N("x"), "a")
                  .bigIntValue(N("v"), 0)
                  .build());
        }
      }
      tx.commit();
    }
    // imported tables: one per key type the driver supports on Oracle (form 2's anchor check),
    // and one of integer keys that gets a row without a metadata row after the setup (form 3)
    try (Connection c = ORA.open()) {
      ORA.execute(
          c,
          "CREATE TABLE " + NS + ".imp (k NUMBER(10) PRIMARY KEY, x NUMBER(10))",
          "INSERT INTO " + NS + ".imp VALUES (1, 0)",
          "INSERT INTO " + NS + ".imp VALUES (2, 0)",
          // an IDENTITY key, which the INSERT block returns into its locals (form 4)
          "CREATE TABLE "
              + NS
              + ".gen (k NUMBER(10) GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, x VARCHAR2(10))");
      for (KeyType k : KeyType.values()) {
        ORA.execute(
            c,
            "CREATE TABLE " + k.table() + " (k " + k.type + " PRIMARY KEY, x NUMBER(10))",
            "INSERT INTO " + k.table() + " VALUES (" + k.literal + ", 0)");
      }
    }
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      admin.importTable(N(NS), N("imp"), DECOUPLED, Map.of());
      admin.importTable(N(NS), N("gen"), DECOUPLED, Map.of());
      for (KeyType k : KeyType.values()) {
        admin.importTable(
            N(NS), N(k.name().toLowerCase(java.util.Locale.ROOT)), DECOUPLED, Map.of());
      }
    }
    catalog = new Catalog(Engine.ORACLE, Catalog.DEFAULT_METADATA_SCHEMA, ORA::open);
    try (Connection c = ORA.open()) {
      assertThat(
              Setup.run(
                  catalog, c, t -> t.namespace().equals(N(NS)), Duration.ofMinutes(1), List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
      // a row the backfill has not reached, as an application writing beside ScalarDB leaves it
      ORA.execute(c, "INSERT INTO " + NS + ".imp VALUES (3, 0)");
    }
  }

  /**
   * Form 1 (design 4.2, "On Oracle both hold"): at SNAPSHOT in a transaction no read records an
   * anchor, the state column's two probes and the coarse check read the in-flight index under the
   * INDEX hint, and a table found clear is remembered: its later reads run as the vendor's, without
   * the check, one round trip fewer for a shape that is checked table by table.
   */
  @Test
  void form1ReadsWithoutAnchorsAndTheClearTableMemory() throws Exception {
    TableInfo t = catalog.lookup(List.of(), N(NS), N("t"));
    String index = Engine.ORACLE.quote(t.inflightIndex(Engine.ORACLE));
    String hint = "/*+ INDEX(\"scalardb_native_meta\" " + index + ") */";
    try (Connection d = driver();
        Connection v = ORA.open()) {
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      String key = "SELECT x FROM " + NS + ".t WHERE id = 1";
      String routed = d.nativeSQL(key);
      // the state column, hinted twice, and no version lookups
      assertThat(routed).contains(hint + " 1 FROM").contains(hint + " \"scalardb_native_meta\".");
      assertThat(routed).doesNotContain("\"scalardb_native_meta\".\"tx_version\"");
      // Oracle's hint report: each hint resolved and used, the probes an index range scan
      String plan = plan(v, routed);
      assertThat(plan).contains(index).doesNotContain("Unresolved").doesNotContain("Unused");
      log("form 1: the state column's probes under " + hint + ": in the plan, every hint used");
      // the round trips, as Oracle counts them for the driver's session
      Counter rt = new Counter(d);
      // the rows as they are, whichever forms ran before: the count the first read gives
      String count = "SELECT COUNT(*) FROM " + NS + ".t WHERE id > 0";
      String[] counted = new String[1];
      long first = rt.measure(() -> counted[0] = rows(d, count).get(0));
      long second = rt.measure(() -> assertThat(rows(d, count)).containsExactly(counted[0]));
      log(
          "form 1: an aggregate read, checked table by table: "
              + first
              + " round trip(s), then "
              + second
              + " once the table is found clear (decoupled)");
      assertThat(first).isEqualTo(2);
      assertThat(second).isEqualTo(1);
      d.rollback();
      // a key read's state column marks the table clear by itself: the aggregate after it is one
      // round trip, and the key read is one either way
      long keyed = rt.measure(() -> assertThat(rows(d, key)).hasSize(1));
      long after = rt.measure(() -> assertThat(rows(d, count)).containsExactly(counted[0]));
      log("form 1: a key read " + keyed + " round trip(s), the aggregate after it " + after);
      assertThat(keyed).isEqualTo(1);
      assertThat(after).isEqualTo(1);
      d.rollback();
      // the inline layout likewise
      String countInline = "SELECT COUNT(*) FROM " + NS + ".ti WHERE id > 0";
      long firstInline = rt.measure(() -> counted[0] = rows(d, countInline).get(0));
      long secondInline =
          rt.measure(() -> assertThat(rows(d, countInline)).containsExactly(counted[0]));
      log("form 1: the same on the inline table: " + firstInline + " then " + secondInline);
      assertThat(firstInline).isEqualTo(2);
      assertThat(secondInline).isEqualTo(1);
      d.rollback();
      // in autocommit nothing is remembered (section 5: READ COMMITTED, and the checked read's
      // own transaction): the aggregate checks its table each time
      d.setAutoCommit(true);
      long auto1 = rt.measure(() -> assertThat(rows(d, count)).hasSize(1));
      long auto2 = rt.measure(() -> assertThat(rows(d, count)).hasSize(1));
      log("form 1: the aggregate in autocommit: " + auto1 + " then " + auto2 + " round trips");
      assertThat(auto2).isEqualTo(auto1);
    }
  }

  /**
   * Form 2 (design 4.3, "The keyed write on Oracle"): a write by the key runs as one block whose
   * row comes back through OUT binds, one round trip in a transaction, on both layouts, two for a
   * table's first block in the process, which returns the key through the cursor as well and
   * compares the two; its outcomes as the design's table: a missing key counts 0; a row this
   * transaction read and another changed since aborts with 40001; a row in flight aborts at once,
   * or waits with the wait set and goes through once the row settles; at SNAPSHOT a row changed
   * after the snapshot fails the block with ORA-08177 as 40001 and leaves the transaction
   * rollback-only; and at SNAPSHOT a clear table takes the vendor's statement with the stamp
   * (inline) or the clear-table block (decoupled), one round trip. Each statement is prepared
   * before it is measured: a text's plan, made once, reads the table's columns on Oracle.
   */
  @Test
  void form2KeyedWriteAsOneBlock() throws Exception {
    Properties waits = new Properties();
    waits.setProperty("scalardbnative.inflightWaitMs", "10000");
    waits.setProperty("scalardbnative.catalogTtlMs", "60000");
    try (Connection d = driver();
        Connection other = driver();
        Connection waiting = ORA.openDriver(waits);
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(true);
        // the rows as they are, whichever forms ran before
        int before = Integer.parseInt(rows(d, "SELECT COUNT(*) FROM " + sql).get(0));
        d.setAutoCommit(false);
        Counter rt = new Counter(d);
        long first;
        long update;
        long delete;
        long missing;
        try (PreparedStatement up =
                d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?");
            PreparedStatement del = d.prepareStatement("DELETE FROM " + sql + " WHERE id = ?")) {
          // the first block of the table in this process returns its key through the OUT binds
          // and the cursor both, the check of the OUT binds' typing: one round trip more, once
          Writes.OutBindCheck.reset();
          first =
              rt.measure(
                  () -> {
                    up.setString(1, "b");
                    up.setInt(2, 1);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
          update =
              rt.measure(
                  () -> {
                    up.setString(1, "b");
                    up.setInt(2, 8);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
          delete =
              rt.measure(
                  () -> {
                    del.setInt(1, 20);
                    assertThat(del.executeUpdate()).isEqualTo(1);
                  });
          missing =
              rt.measure(
                  () -> {
                    up.setString(1, "z");
                    up.setInt(2, 999);
                    assertThat(up.executeUpdate()).isEqualTo(0);
                  });
        }
        d.commit();
        log(
            "form 2 ("
                + layout
                + "): the table's first keyed UPDATE in the process, the check of the OUT binds, "
                + first
                + " round trip(s)");
        log(
            "form 2 ("
                + layout
                + "): a keyed UPDATE "
                + update
                + ", a keyed DELETE "
                + delete
                + ", a write of a missing key "
                + missing
                + " round trip(s), at READ COMMITTED in a transaction");
        assertThat(first).isEqualTo(2);
        assertThat(update).isEqualTo(1);
        assertThat(delete).isEqualTo(1);
        assertThat(missing).isEqualTo(1);
        d.setAutoCommit(true);
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 1")).containsExactly("b");
        assertThat(rows(d, "SELECT COUNT(*) FROM " + sql))
            .containsExactly(String.valueOf(before - 1));
        // the stamp: ScalarDB reads the row committed at the driver's version
        assertThat(scalarDbX(table, 1)).isEqualTo("b");
        // the anchor: a row read in the transaction, changed by another connection, then written
        d.setAutoCommit(false);
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 2")).containsExactly("a");
        assertThat(update(other, "UPDATE " + sql + " SET x = 'c' WHERE id = 2")).isEqualTo(1);
        assertAborts(
            d, "UPDATE " + sql + " SET x = 'd' WHERE id = 2", layout + ": the anchor moved");
        d.rollback();
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 2")).containsExactly("c");
        d.rollback();
        // a row in flight: an abort at once without a wait set, a wait and the write with one
        TwoPhaseCommitTransaction held = prepared(tpc, table, 3, "p");
        assertAborts(
            d, "UPDATE " + sql + " SET x = 'e' WHERE id = 3", layout + ": a row in flight");
        d.rollback();
        waiting.setAutoCommit(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
          Future<Integer> write =
              pool.submit(() -> update(waiting, "UPDATE " + sql + " SET x = 'f' WHERE id = 3"));
          Thread.sleep(700);
          assertThat(write.isDone()).as(layout + ": waits on the row in flight").isFalse();
          held.rollback();
          assertThat(write.get(20, TimeUnit.SECONDS)).isEqualTo(1);
          waiting.commit();
        } finally {
          pool.shutdownNow();
        }
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 3")).containsExactly("f");
        d.rollback();
        // at SNAPSHOT: a row changed after the snapshot fails the block with ORA-08177, 40001, and
        // the transaction is rollback-only
        d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 4")).containsExactly("a");
        assertThat(update(other, "UPDATE " + sql + " SET x = 'g' WHERE id = 4")).isEqualTo(1);
        assertThatThrownBy(() -> update(d, "UPDATE " + sql + " SET x = 'h' WHERE id = 4"))
            .as(layout + ": changed after the snapshot")
            .isInstanceOf(SQLTransactionRollbackException.class)
            .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(8177));
        assertThatThrownBy(() -> update(d, "UPDATE " + sql + " SET x = 'h' WHERE id = 5"))
            .as(layout + ": rollback-only after it")
            .isInstanceOf(SQLTransactionRollbackException.class);
        d.rollback();
        // at SNAPSHOT a keyed write in a transaction: the clear-table form, gated, then for the
        // table found clear, one round trip each
        long snapshot;
        long again;
        try (PreparedStatement up =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          snapshot =
              rt.measure(
                  () -> {
                    up.setString(1, "i");
                    up.setInt(2, 5);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
          again =
              rt.measure(
                  () -> {
                    up.setString(1, "j");
                    up.setInt(2, 6);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
        }
        d.commit();
        log(
            "form 2 ("
                + layout
                + "): at SNAPSHOT a keyed UPDATE "
                + snapshot
                + " then "
                + again
                + " round trip(s)");
        assertThat(snapshot).isEqualTo(1);
        assertThat(again).isEqualTo(1);
        assertThat(scalarDbX(table, 5)).isEqualTo("i");
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(true);
        // in autocommit: the write in a transaction of its own, the block and the commit
        long auto;
        try (PreparedStatement up =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          auto =
              rt.measure(
                  () -> {
                    up.setString(1, "k");
                    up.setInt(2, 7);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
        }
        log("form 2 (" + layout + "): a keyed UPDATE in autocommit " + auto + " round trip(s)");
        assertThat(auto).isEqualTo(2);
      }
    }
  }

  /**
   * Form 2, the anchor for each key type the driver supports on Oracle, the CHAR key the hard case:
   * the version a read records for the key and the one the block reads back agree, so that a change
   * by another connection in between aborts the write; the write of an unchanged row goes through
   * and ScalarDB reads it.
   */
  @Test
  void form2AnchorsOfEveryKeyType() throws Exception {
    try (Connection d = driver();
        Connection other = driver()) {
      for (KeyType k : KeyType.values()) {
        String where = " WHERE k = " + k.literal;
        d.setAutoCommit(false);
        assertThat(rows(d, "SELECT x FROM " + k.table() + where)).containsExactly("0");
        assertThat(update(other, "UPDATE " + k.table() + " SET x = 1" + where)).isEqualTo(1);
        assertAborts(
            d, "UPDATE " + k.table() + " SET x = 2" + where, k.type + ": the anchor moved");
        d.rollback();
        assertThat(rows(d, "SELECT x FROM " + k.table() + where)).containsExactly("1");
        assertThat(update(d, "UPDATE " + k.table() + " SET x = 3" + where)).isEqualTo(1);
        d.commit();
        d.setAutoCommit(true);
        assertThat(rows(d, "SELECT x FROM " + k.table() + where)).containsExactly("3");
        log("form 2: a " + k.type + " key: the anchor found, the write of the unchanged row 1 row");
      }
    }
  }

  /**
   * Form 3 (design 4.3, "The write beyond the key on Oracle"): a write whose predicate is not the
   * key runs as one block over the predicate's keys, two round trips, on both layouts, when the
   * transaction recorded no anchor of the table; a key another connection moves under the block's
   * wait so that it stops matching is skipped and the rest written; one moved that still matches
   * aborts; a predicate matching nothing counts 0; and at SNAPSHOT the clear-table block
   * (decoupled) or statement (inline), on a created table and on an imported one over a key without
   * a metadata row, which the MERGE gives one.
   */
  @Test
  void form3WriteBeyondTheKeyAsOneBlock() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = driver();
        Connection other = driver();
        Connection v = ORA.open()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        Counter rt = new Counter(d);
        long two;
        long none;
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id IN (?, ?)")) {
          two =
              rt.measure(
                  () -> {
                    ps.setString(1, "p");
                    ps.setInt(2, 9);
                    ps.setInt(3, 10);
                    assertThat(ps.executeUpdate()).isEqualTo(2);
                  });
        }
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = 'q' WHERE x = ?")) {
          none =
              rt.measure(
                  () -> {
                    ps.setString(1, "none");
                    assertThat(ps.executeUpdate()).isEqualTo(0);
                  });
        }
        d.commit();
        log(
            "form 3 ("
                + layout
                + "): a write beyond the key over two keys "
                + two
                + ", one matching nothing "
                + none
                + " round trip(s), at READ COMMITTED in a transaction");
        assertThat(two).isEqualTo(1);
        assertThat(none).isEqualTo(1);
        assertThat(scalarDbX(table, 9)).isEqualTo("p");
        assertThat(scalarDbX(table, 10)).isEqualTo("p");
        // a key the predicate matched that another connection moves under the block's wait so
        // that it stops matching: skipped, as the vendor's UPDATE skips it; the other key written
        other.setAutoCommit(false);
        assertThat(update(other, "UPDATE " + sql + " SET x = 'z' WHERE id = 11")).isEqualTo(1);
        Future<Integer> skip =
            pool.submit(
                () -> update(d, "UPDATE " + sql + " SET v = 2 WHERE x = 'a' AND id IN (11, 12)"));
        Thread.sleep(700);
        assertThat(skip.isDone()).as(layout + ": waits on the row").isFalse();
        other.commit();
        assertThat(skip.get(20, TimeUnit.SECONDS))
            .as(layout + ": the moved key skipped")
            .isEqualTo(1);
        d.commit();
        assertThat(rows(d, "SELECT id, v FROM " + sql + " WHERE id IN (11, 12) ORDER BY id"))
            .containsExactly("11/0", "12/2");
        // one moved that still matches: the row's version moved under the DML, an abort
        assertThat(update(other, "UPDATE " + sql + " SET x = 'y' WHERE id = 13")).isEqualTo(1);
        Future<Integer> moved =
            pool.submit(
                () -> update(d, "UPDATE " + sql + " SET v = 3 WHERE v = 0 AND id IN (13, 14)"));
        Thread.sleep(700);
        assertThat(moved.isDone()).as(layout + ": waits on the row").isFalse();
        other.commit();
        assertThatThrownBy(() -> moved.get(20, TimeUnit.SECONDS))
            .as(layout + ": a moved row that still matches")
            .cause()
            .isInstanceOf(SQLTransactionRollbackException.class)
            .extracting(e -> ((SQLException) e).getSQLState())
            .isEqualTo("40001");
        d.rollback();
        assertThat(rows(d, "SELECT id, v FROM " + sql + " WHERE id IN (13, 14) ORDER BY id"))
            .containsExactly("13/0", "14/0");
        d.rollback();
        other.setAutoCommit(true);
        // at SNAPSHOT the clear-table form, gated, then for a table found clear
        d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        long clear;
        long deleted;
        try (PreparedStatement up =
                d.prepareStatement("UPDATE " + sql + " SET x = 'c' WHERE id IN (?, ?)");
            PreparedStatement del =
                d.prepareStatement("DELETE FROM " + sql + " WHERE id IN (?, ?)")) {
          clear =
              rt.measure(
                  () -> {
                    up.setInt(1, 15);
                    up.setInt(2, 16);
                    assertThat(up.executeUpdate()).isEqualTo(2);
                  });
          deleted =
              rt.measure(
                  () -> {
                    del.setInt(1, 17);
                    del.setInt(2, 18);
                    assertThat(del.executeUpdate()).isEqualTo(2);
                  });
        }
        d.commit();
        log(
            "form 3 ("
                + layout
                + "): at SNAPSHOT a write beyond the key on a table not yet found clear "
                + clear
                + ", then a DELETE on it found clear "
                + deleted
                + " round trip(s)");
        assertThat(clear).isEqualTo(1);
        assertThat(deleted).isEqualTo(1);
        assertThat(scalarDbX(table, 15)).isEqualTo("c");
        assertThat(scalarDbX(table, 17)).isNull();
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(true);
      }
      // the imported table's clear-table block over a key without a metadata row: the MERGE
      // writes it, at version 1, beside the stamped ones
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      Counter rt = new Counter(d);
      long imported;
      try (PreparedStatement up =
          d.prepareStatement("UPDATE " + NS + ".imp SET x = 1 WHERE x = ?")) {
        imported =
            rt.measure(
                () -> {
                  up.setInt(1, 0);
                  assertThat(up.executeUpdate()).isEqualTo(3);
                });
      }
      d.commit();
      log(
          "form 3 (imported, decoupled): the clear-table block over three keys, one without a"
              + " metadata row: "
              + imported
              + " round trip(s)");
      assertThat(imported).isEqualTo(1);
      String meta =
          NS
              + "."
              + Engine.ORACLE.quote(
                  Objects.requireNonNull(
                      catalog.lookup(List.of(), N(NS), N("imp")).metadataTable()));
      assertThat(
              rows(
                  v,
                  "SELECT k, \"tx_state\", \"tx_version\" FROM "
                      + meta
                      + " WHERE k <= 3 ORDER BY k"))
          .containsExactly("1/3/2", "2/3/2", "3/3/1");
      d.setAutoCommit(true);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Form 4, the INSERT (design 4.3, "An INSERT on Oracle"): a decoupled table's INSERT of one row
   * with its metadata row as one block, two round trips, an inline table's as the one statement it
   * is with its stamp columns, one; a duplicate is the application's with the transaction open; a
   * key a ScalarDB prepare holds aborts; a tombstone is taken over inside the block; an IDENTITY
   * key comes back through the block, and the generated keys an application asks for are read as
   * today.
   */
  @Test
  void form4InsertAsOneStatement() throws Exception {
    // a catalog TTL, so that the INSERT's plan, which reads the table's columns and triggers, is
    // made once at prepare rather than again at each execute (the fixture's TTL of 0 re-plans)
    Properties ttl = new Properties();
    ttl.setProperty("scalardbnative.catalogTtlMs", "60000");
    try (Connection d = ORA.openDriver(ttl);
        Connection v = ORA.open();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        Counter rt = new Counter(d);
        // the table verified first, by a write of its own, so that the INSERT measured is the
        // form itself and not the first-use check
        assertThat(update(d, "UPDATE " + sql + " SET x = 'm' WHERE id = 62")).isEqualTo(1);
        long insert;
        try (PreparedStatement ps =
            d.prepareStatement("INSERT INTO " + sql + " (id, x, v) VALUES (?, ?, ?)")) {
          insert =
              rt.measure(
                  () -> {
                    ps.setInt(1, 101);
                    ps.setString(2, "n");
                    ps.setInt(3, 0);
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                  });
        }
        d.commit();
        log(
            "form 4 ("
                + layout
                + "): an INSERT of one row "
                + insert
                + " round trip(s), in a transaction");
        assertThat(insert).isEqualTo(1);
        assertThat(scalarDbX(table, 101)).isEqualTo("n");
        // the table's first INSERT block in the process, the check of the OUT binds: the key
        // through the binds and the cursor both, one round trip more, once (decoupled; the inline
        // INSERT is the vendor's statement, without a block)
        Writes.OutBindCheck.reset();
        long checked;
        try (PreparedStatement ps =
            d.prepareStatement("INSERT INTO " + sql + " (id, x, v) VALUES (?, ?, ?)")) {
          checked =
              rt.measure(
                  () -> {
                    ps.setInt(1, 105);
                    ps.setString(2, "o");
                    ps.setInt(3, 0);
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                  });
        }
        d.commit();
        log(
            "form 4 ("
                + layout
                + "): the table's first INSERT in the process, the check of the OUT binds, "
                + checked
                + " round trip(s)");
        assertThat(checked).isEqualTo(table.equals("t") ? 2 : 1);
        assertThat(scalarDbX(table, 105)).isEqualTo("o");
        // the application's duplicate, SQLState 23000, the transaction still open
        assertThatThrownBy(
                () -> update(d, "INSERT INTO " + sql + " (id, x, v) VALUES (101, 'n', 0)"))
            .as(layout + ": a duplicate")
            .isInstanceOf(SQLException.class)
            .isNotInstanceOf(SQLTransactionRollbackException.class)
            .extracting(e -> ((SQLException) e).getSQLState())
            .isEqualTo("23000");
        assertThat(update(d, "INSERT INTO " + sql + " (id, x, v) VALUES (102, 'o', 0)"))
            .isEqualTo(1);
        d.commit();
        assertThat(scalarDbX(table, 102)).isEqualTo("o");
        // a key a ScalarDB prepare holds: a conflict
        TwoPhaseCommitTransaction held = tpc.begin();
        held.insert(
            Insert.newBuilder()
                .namespace(N(NS))
                .table(N(table))
                .partitionKey(id(103))
                .textValue(N("x"), "p")
                .bigIntValue(N("v"), 0)
                .build());
        held.prepare();
        assertAborts(
            d, "INSERT INTO " + sql + " (id, x, v) VALUES (103, 'q', 0)", layout + ": in flight");
        d.rollback();
        held.rollback();
        // the generated keys an application asks for, read back as today
        try (PreparedStatement ps =
            d.prepareStatement(
                "INSERT INTO " + sql + " (id, x, v) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
          ps.setInt(1, 104);
          ps.setString(2, "r");
          ps.setInt(3, 0);
          long asked = rt.measure(() -> assertThat(ps.executeUpdate()).isEqualTo(1));
          try (ResultSet k = ps.getGeneratedKeys()) {
            assertThat(rows(k)).hasSize(1).first().asString().startsWith("104");
          }
          log(
              "form 4 ("
                  + layout
                  + "): the INSERT with its generated keys asked for "
                  + asked
                  + " round trip(s)");
        }
        d.commit();
        d.setAutoCommit(true);
      }
      // a tombstone taken over inside the block: a DELETE of an imported table's row keeps its
      // metadata row (4.5), and the INSERT of the key stamps it at the next version
      d.setAutoCommit(true);
      assertThat(update(d, "INSERT INTO " + NS + ".imp VALUES (7, 9)")).isEqualTo(1);
      assertThat(update(d, "DELETE FROM " + NS + ".imp WHERE k = 7")).isEqualTo(1);
      String meta =
          NS
              + "."
              + Engine.ORACLE.quote(
                  Objects.requireNonNull(
                      catalog.lookup(List.of(), N(NS), N("imp")).metadataTable()));
      assertThat(rows(v, "SELECT \"tx_state\", \"tx_version\" FROM " + meta + " WHERE k = 7"))
          .containsExactly("3/2");
      assertThat(update(d, "INSERT INTO " + NS + ".imp VALUES (7, 10)")).isEqualTo(1);
      assertThat(rows(v, "SELECT \"tx_state\", \"tx_version\" FROM " + meta + " WHERE k = 7"))
          .containsExactly("3/3");
      assertThat(rows(d, "SELECT x FROM " + NS + ".imp WHERE k = 7")).containsExactly("10");
      // an IDENTITY key, returned into the block's locals, and read back when asked for
      try (PreparedStatement ps =
          d.prepareStatement(
              "INSERT INTO " + NS + ".gen (x) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
        ps.setString(1, "g");
        assertThat(ps.executeUpdate()).isEqualTo(1);
        try (ResultSet k = ps.getGeneratedKeys()) {
          assertThat(rows(k)).containsExactly("1/g"); // every column, as pgjdbc returns them
        }
      }
      assertThat(rows(d, "SELECT k, x FROM " + NS + ".gen")).containsExactly("1/g");
      log("form 4: an IDENTITY key through the block, the tombstone taken over inside it");
    }
  }

  /**
   * Form 4, the batches (design 4.4, "Batches on Oracle"): a prepared statement's entries go as one
   * block of entries where the write is a block (the keyed write, the decoupled INSERT), two round
   * trips for ten, and through ojdbc's own batching where it is one statement (the inline INSERT),
   * one; an entry in flight stops the block, which the entry's own wait settles before the rest go
   * on, every count 1; at SNAPSHOT such an entry fails the batch with {@code 40001} and the counts
   * before it; a duplicate in the middle of an INSERT batch fails it with the counts before it, the
   * vendor's own or the replay's.
   */
  @Test
  void form4BatchesAsOneBlock() throws Exception {
    Properties ttl = new Properties();
    ttl.setProperty("scalardbnative.catalogTtlMs", "60000");
    Properties waits = new Properties();
    waits.putAll(ttl);
    waits.setProperty("scalardbnative.inflightWaitMs", "10000");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = ORA.openDriver(ttl);
        Connection waiting = ORA.openDriver(waits);
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        Counter rt = new Counter(d);
        // the table verified first, by a write of its own, so that the batches measured are the
        // form itself and not the first-use check
        assertThat(update(d, "UPDATE " + sql + " SET x = 'm' WHERE id = 63")).isEqualTo(1);
        long keyed;
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          keyed =
              rt.measure(
                  () -> {
                    for (int id = 30; id < 40; id++) {
                      ps.setString(1, "u");
                      ps.setInt(2, id);
                      ps.addBatch();
                    }
                    assertThat(ps.executeBatch()).hasSize(10).containsOnly(1);
                  });
        }
        d.commit();
        long inserts;
        try (PreparedStatement ps =
            d.prepareStatement("INSERT INTO " + sql + " (id, x, v) VALUES (?, ?, ?)")) {
          inserts =
              rt.measure(
                  () -> {
                    for (int id = 110; id < 120; id++) {
                      ps.setInt(1, id);
                      ps.setString(2, "i");
                      ps.setInt(3, 0);
                      ps.addBatch();
                    }
                    assertThat(ps.executeBatch()).hasSize(10).containsOnly(1);
                  });
        }
        d.commit();
        log(
            "form 4 ("
                + layout
                + "): a batch of ten keyed UPDATEs "
                + keyed
                + ", of ten INSERTs "
                + inserts
                + " round trip(s), in a transaction");
        assertThat(keyed).isEqualTo(1);
        assertThat(inserts).isEqualTo(1);
        assertThat(scalarDbX(table, 30)).isEqualTo("u");
        assertThat(scalarDbX(table, 39)).isEqualTo("u");
        assertThat(scalarDbX(table, 119)).isEqualTo("i");
        // the table's first block of entries in the process, the check of the OUT binds: each
        // reached entry's key through the binds and the cursor both, one round trip more, once
        Writes.OutBindCheck.reset();
        long checked;
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          checked =
              rt.measure(
                  () -> {
                    for (int id = 30; id < 40; id++) {
                      ps.setString(1, "v");
                      ps.setInt(2, id);
                      ps.addBatch();
                    }
                    assertThat(ps.executeBatch()).hasSize(10).containsOnly(1);
                  });
        }
        d.commit();
        log(
            "form 4 ("
                + layout
                + "): the table's first batch in the process, the check of the OUT binds, "
                + checked
                + " round trip(s)");
        assertThat(checked).isEqualTo(2);
        assertThat(scalarDbX(table, 30)).isEqualTo("v");
        assertThat(scalarDbX(table, 39)).isEqualTo("v");
        // an entry in flight at READ COMMITTED with the wait set: the block stops at it, the
        // entry's own wait settles it, and the rest go on; every count 1
        TwoPhaseCommitTransaction held = prepared(tpc, table, 42, "p");
        waiting.setAutoCommit(false);
        Future<int[]> batch =
            pool.submit(
                () -> {
                  try (PreparedStatement ps =
                      waiting.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
                    for (int id = 40; id < 45; id++) {
                      ps.setString(1, "w");
                      ps.setInt(2, id);
                      ps.addBatch();
                    }
                    return ps.executeBatch();
                  }
                });
        Thread.sleep(700);
        assertThat(batch.isDone()).as(layout + ": waits on the entry in flight").isFalse();
        held.rollback();
        assertThat(batch.get(20, TimeUnit.SECONDS)).hasSize(5).containsOnly(1);
        waiting.commit();
        assertThat(scalarDbX(table, 44)).isEqualTo("w");
        // at SNAPSHOT such an entry fails the batch with 40001 and the counts before it
        d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        held = prepared(tpc, table, 47, "p");
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          for (int id = 45; id < 50; id++) {
            ps.setString(1, "s");
            ps.setInt(2, id);
            ps.addBatch();
          }
          assertThatThrownBy(ps::executeBatch)
              .as(layout + ": an entry in flight at SNAPSHOT")
              .isInstanceOf(BatchUpdateException.class)
              .satisfies(
                  e -> {
                    assertThat(((BatchUpdateException) e).getUpdateCounts()).containsExactly(1, 1);
                    assertThat(((SQLException) e).getSQLState()).isEqualTo("40001");
                  });
        }
        d.rollback();
        held.rollback();
        // a duplicate in the middle of an INSERT batch: the counts before it, the application's
        // duplicate
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (PreparedStatement ps =
            d.prepareStatement("INSERT INTO " + sql + " (id, x, v) VALUES (?, ?, ?)")) {
          for (int id : new int[] {120, 121, 110, 122, 123}) {
            ps.setInt(1, id);
            ps.setString(2, "d");
            ps.setInt(3, 0);
            ps.addBatch();
          }
          assertThatThrownBy(ps::executeBatch)
              .as(layout + ": a duplicate as the third entry")
              .isInstanceOf(BatchUpdateException.class)
              .satisfies(
                  e -> {
                    assertThat(((BatchUpdateException) e).getUpdateCounts()).containsExactly(1, 1);
                    assertThat(((SQLException) e).getSQLState()).isEqualTo("23000");
                  });
        }
        d.rollback();
        assertThat(rows(d, "SELECT COUNT(*) FROM " + sql + " WHERE id IN (120, 121)"))
            .containsExactly("0");
        d.setAutoCommit(true);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Form 5 (design 4.3, "The locking read on Oracle"): at READ COMMITTED a {@code FOR UPDATE} by
   * the key is one statement on both layouts, the row's newest version its anchor, a row in flight
   * an abort, or ORA-00054 under NOWAIT; a lock that waited on another connection's write returns
   * the row as that write left it, and a write of it then goes through. At SNAPSHOT the statement
   * as written with the gate, one round trip when rows come back, which marks the table clear; the
   * coarse check after an empty result on a table not yet clear; a row in flight the predicate
   * matches aborts, one it does not match leaves the lock to the committed copy.
   */
  @Test
  void form5LockingReadAsOneStatement() throws Exception {
    Properties ttl = new Properties();
    ttl.setProperty("scalardbnative.catalogTtlMs", "60000");
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = ORA.openDriver(ttl);
        Connection other = ORA.openDriver(ttl);
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        Counter rt = new Counter(d);
        long lock =
            rt.measure(
                () ->
                    assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 50 FOR UPDATE"))
                        .containsExactly("a"));
        long write;
        try (PreparedStatement up =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          // the table's first block in the process checks its OUT binds, one round trip more
          // (form 2's figure): a write of another row first, so that the write measured is the
          // plain one
          up.setString(1, "a");
          up.setInt(2, 49);
          assertThat(up.executeUpdate()).isEqualTo(1);
          up.setString(1, "l");
          write =
              rt.measure(
                  () -> {
                    up.setInt(2, 50);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
        }
        d.commit();
        log(
            "form 5 ("
                + layout
                + "): a FOR UPDATE by the key at READ COMMITTED "
                + lock
                + " round trip(s), the write of the locked row after it "
                + write);
        assertThat(lock).isEqualTo(1);
        assertThat(write).isEqualTo(1);
        assertThat(scalarDbX(table, 50)).isEqualTo("l");
        // a row in flight: an abort, or the engine's own lock failure under NOWAIT
        TwoPhaseCommitTransaction held = prepared(tpc, table, 51, "p");
        assertThatThrownBy(() -> rows(d, "SELECT x FROM " + sql + " WHERE id = 51 FOR UPDATE"))
            .as(layout + ": a row in flight")
            .isInstanceOf(SQLTransactionRollbackException.class);
        d.rollback();
        assertThatThrownBy(
                () -> rows(d, "SELECT x FROM " + sql + " WHERE id = 51 FOR UPDATE NOWAIT"))
            .as(layout + ": a row in flight under NOWAIT")
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(54));
        d.rollback();
        held.rollback();
        // the restart: the lock waits on another connection's write and returns the row as that
        // write left it, whose version the write of it then holds to
        other.setAutoCommit(false);
        assertThat(update(other, "UPDATE " + sql + " SET x = 'm' WHERE id = 52")).isEqualTo(1);
        Future<List<String>> waited =
            pool.submit(() -> rows(d, "SELECT x FROM " + sql + " WHERE id = 52 FOR UPDATE"));
        Thread.sleep(700);
        assertThat(waited.isDone()).as(layout + ": waits on the row").isFalse();
        other.commit();
        assertThat(waited.get(20, TimeUnit.SECONDS)).containsExactly("m");
        assertThat(update(d, "UPDATE " + sql + " SET x = 'n' WHERE id = 52")).isEqualTo(1);
        d.commit();
        other.setAutoCommit(true);
        assertThat(scalarDbX(table, 52)).isEqualTo("n");
        // at SNAPSHOT: the statement as written with the gate, which a row returned proves open
        d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        long gated =
            rt.measure(
                () ->
                    assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 53 FOR UPDATE"))
                        .containsExactly("a"));
        long after =
            rt.measure(
                () ->
                    assertThat(rows(d, "SELECT COUNT(*) FROM " + sql + " WHERE id > 0"))
                        .hasSize(1));
        long none =
            rt.measure(
                () ->
                    assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 999 FOR UPDATE"))
                        .isEmpty());
        d.rollback();
        long empty =
            rt.measure(
                () ->
                    assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 999 FOR UPDATE"))
                        .isEmpty());
        d.rollback();
        log(
            "form 5 ("
                + layout
                + "): at SNAPSHOT a FOR UPDATE by the key on a table not yet found clear "
                + gated
                + ", an aggregate after it "
                + after
                + ", a lock matching nothing on the table found clear "
                + none
                + ", on a table not yet clear "
                + empty
                + " round trip(s)");
        assertThat(gated).isEqualTo(1);
        assertThat(after).isEqualTo(1);
        assertThat(none).isEqualTo(1);
        assertThat(empty).isEqualTo(2);
        // a row in flight the predicate matches aborts; one it does not match leaves the lock to
        // the committed copy, the rows locked
        held = prepared(tpc, table, 54, "p");
        assertThatThrownBy(() -> rows(d, "SELECT x FROM " + sql + " WHERE id = 54 FOR UPDATE"))
            .as(layout + ": a row in flight at SNAPSHOT")
            .isInstanceOf(SQLTransactionRollbackException.class);
        d.rollback();
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 55 FOR UPDATE"))
            .containsExactly("a");
        assertThat(update(d, "UPDATE " + sql + " SET x = 'o' WHERE id = 55")).isEqualTo(1);
        d.commit();
        held.rollback();
        assertThat(scalarDbX(table, 55)).isEqualTo("o");
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(true);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Form 6 (design 4.2, "The read side on Oracle"): at READ COMMITTED a read's anchors are the
   * copies' own columns, the inline row's or the metadata row's through a left join, no lookup per
   * row; the write of a row read then holds to that version.
   */
  @Test
  void form6ReadSideAnchorsAsOwnColumns() throws Exception {
    try (Connection d = driver();
        Connection other = driver()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        String routed = d.nativeSQL("SELECT x FROM " + sql + " WHERE id = 60");
        assertThat(routed)
            .as(layout + ": the copy's own columns")
            .contains("\"scalardb_native_v0\"")
            .doesNotContain("(SELECT \"scalardb_native_meta\".\"tx_id\"");
        if (table.equals("t")) {
          assertThat(routed).contains(" LEFT JOIN ").contains("COALESCE(m.\"tx_state\", 3)");
        } else {
          assertThat(routed).contains("COALESCE(t.\"tx_state\", 3)");
        }
        Counter rt = new Counter(d);
        long read =
            rt.measure(
                () ->
                    assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 60"))
                        .containsExactly("a"));
        log("form 6 (" + layout + "): a key read at READ COMMITTED " + read + " round trip(s)");
        assertThat(read).isEqualTo(1);
        assertThat(update(other, "UPDATE " + sql + " SET x = 'c' WHERE id = 60")).isEqualTo(1);
        assertAborts(
            d, "UPDATE " + sql + " SET x = 'd' WHERE id = 60", layout + ": the anchor moved");
        d.rollback();
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 61")).containsExactly("a");
        assertThat(update(d, "UPDATE " + sql + " SET x = 'e' WHERE id = 61")).isEqualTo(1);
        d.commit();
        d.setAutoCommit(true);
        assertThat(scalarDbX(table, 61)).isEqualTo("e");
      }
    }
  }

  /**
   * A driver connection with a catalog TTL: the fixture's TTL of 0 plans a statement again at each
   * execute, and a write's plan reads the table's columns on Oracle, a round trip that would be
   * counted as the form's.
   */
  private static Connection driver() throws SQLException {
    Properties ttl = new Properties();
    ttl.setProperty("scalardbnative.catalogTtlMs", "60000");
    return ORA.openDriver(ttl);
  }

  private static int update(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      return st.executeUpdate(sql);
    }
  }

  private static void assertAborts(Connection d, String sql, String what) {
    assertThatThrownBy(() -> update(d, sql))
        .as(what)
        .isInstanceOf(SQLTransactionRollbackException.class)
        .extracting(e -> ((SQLException) e).getSQLState())
        .isEqualTo("40001");
  }

  private static TwoPhaseCommitTransaction prepared(
      TwoPhaseCommitTransactionManager tpc, String table, int id, String x) throws Exception {
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(
        Update.newBuilder()
            .namespace(N(NS))
            .table(N(table))
            .partitionKey(id(id))
            .textValue(N("x"), x)
            .build());
    tx.prepare();
    return tx;
  }

  private static @Nullable String scalarDbX(String table, int id) throws Exception {
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      String x =
          tx.get(
                  com.scalar.db.api.Get.newBuilder()
                      .namespace(N(NS))
                      .table(N(table))
                      .partitionKey(id(id))
                      .build())
              .map(r -> r.getText(N("x")))
              .orElse(null);
      tx.commit();
      return x;
    }
  }

  // ---------------------------------------------------------------------------------------------

  /** EXPLAIN PLAN of a statement on the vendor connection, with Oracle's hint report. */
  private static String plan(Connection v, String sql) throws SQLException {
    try (Statement st = v.createStatement()) {
      st.execute("EXPLAIN PLAN FOR " + sql);
      try (ResultSet rs =
          st.executeQuery(
              "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(NULL, NULL, 'BASIC"
                  + " +HINT_REPORT'))")) {
        List<String> lines = new ArrayList<>();
        while (rs.next()) {
          lines.add(rs.getString(1));
        }
        return String.join("\n", lines);
      }
    }
  }

  /** Round trips as Oracle counts them for the session behind a connection. */
  static final class Counter {
    private final Connection c;
    private final long statCost;

    Counter(Connection c) throws Exception {
      this.c = c;
      long first = roundTrips();
      long second = roundTrips();
      this.statCost = second - first; // what reading the statistic itself costs
    }

    private long roundTrips() throws SQLException {
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT s.value FROM v$mystat s JOIN v$statname n ON n.statistic# = s.statistic#"
                      + " WHERE n.name = 'SQL*Net roundtrips to/from client'")) {
        assertThat(rs.next()).isTrue();
        return rs.getLong(1);
      }
    }

    long measure(Action action) throws Exception {
      long before = roundTrips();
      action.run();
      return roundTrips() - before - statCost;
    }
  }

  @FunctionalInterface
  interface Action {
    void run() throws Exception;
  }

  private static void log(String line) {
    System.out.println("[335] " + line);
  }
}
