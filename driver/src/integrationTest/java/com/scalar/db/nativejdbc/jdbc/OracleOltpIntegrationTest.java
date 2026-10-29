package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.N;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.Q;
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
import java.math.BigDecimal;
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
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"
    },
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
              + ".gen (k NUMBER(10) GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, x VARCHAR2(10))",
          // a double or float into each numeric type (the bulk block's element as ojdbc binds it)
          "CREATE TABLE "
              + NS
              + ".dbl (k NUMBER(10) PRIMARY KEY, a NUMBER(6,2), b NUMBER, c BINARY_DOUBLE,"
              + " f BINARY_FLOAT)",
          // a row, for the table's segment to exist: at SERIALIZABLE the first INSERT into a
          // table without one is ORA-08177
          "INSERT INTO " + NS + ".dbl (k) VALUES (1)");
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
      admin.importTable(N(NS), N("dbl"), DECOUPLED, Map.of());
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
   * The setup's statistics on the table that holds the metadata, each layout's: every column has
   * them, tx_state still the fixed histogram's three values, and so a hard parse of a keyed write
   * runs no dynamic sampling of the table, which without the key's statistics counted the predicate
   * through the whole in-flight index at each parse (todo 334, 0.3 s a text at 3.2M rows).
   */
  @Test
  void theSetupsStatistics_coverEveryColumn_andAKeyedParseSamplesNothing() throws Exception {
    try (Connection v = ORA.open()) {
      for (String table : List.of("t", "ti")) {
        String meta =
            table.equals("t")
                ? java.util.Objects.requireNonNull(tableInfo(table).metadataTable())
                : N(table);
        String layout = table.equals("t") ? "decoupled" : "inline";
        try (PreparedStatement ps =
            v.prepareStatement(
                "SELECT c.column_name, s.num_distinct FROM all_tab_columns c LEFT JOIN"
                    + " all_tab_col_statistics s ON s.owner = c.owner AND s.table_name ="
                    + " c.table_name AND s.column_name = c.column_name WHERE c.owner = ? AND"
                    + " c.table_name = ? ORDER BY c.column_id")) {
          ps.setString(1, N(NS));
          ps.setString(2, meta);
          Map<String, @Nullable Long> distinct = new java.util.LinkedHashMap<>();
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              String column = rs.getString(1);
              long n = rs.getLong(2);
              distinct.put(column, rs.wasNull() ? null : n);
            }
          }
          log("statistics (" + layout + "): " + meta + " " + distinct);
          assertThat(distinct).as(layout + ": the columns").containsKey("tx_state");
          assertThat(distinct.values())
              .as(layout + ": every column's statistics")
              .doesNotContainNull();
          assertThat(distinct.get("tx_state")).as(layout + ": the fixed histogram").isEqualTo(3L);
        }
        long before = samplings(v, meta);
        // a text of its own, hard-parsed now; no row has the key
        update(
            v,
            "UPDATE /* stats "
                + System.nanoTime()
                + " */ "
                + NS
                + "."
                + Q(meta)
                + " SET \"tx_state\" = \"tx_state\" WHERE id = -1 AND COALESCE(\"tx_state\", 3) = 3");
        assertThat(samplings(v, meta))
            .as(layout + ": dynamic sampling at the parse")
            .isEqualTo(before);
      }
    }
  }

  /** The executions {@code V$SQL} has of dynamic sampling queries over {@code table}. */
  private static long samplings(Connection v, String table) throws SQLException {
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT NVL(SUM(executions), 0) FROM v$sql WHERE sql_text LIKE 'SELECT /* DS_SVC */%'"
                + " AND DBMS_LOB.INSTR(sql_fulltext, '\"' || ? || '\"') > 0")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * Form 1 (design 4.2, "On Oracle both hold"): at SNAPSHOT in a transaction no read records an
   * anchor, the state column's two probes and the coarse check read the in-flight index under the
   * INDEX hint, and a table found clear is remembered: its later reads run as the vendor's. An
   * aggregate, which records no anchor, runs as written behind the table's gate, which marks it
   * clear (todo 334, D2), one round trip where the coarse check went first before.
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
          "form 1: an aggregate read, as written behind the table's gate: "
              + first
              + " round trip(s), then "
              + second
              + " once the gate found the table clear (decoupled)");
      // the gate stands for the table's coarse check, sent before the read until todo 334's D2
      assertThat(first).isEqualTo(1);
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
      assertThat(firstInline).isEqualTo(1);
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
    waits.setProperty("scalar.db.native_jdbc.inflight_wait_millis", "10000");
    waits.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
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
        long checked;
        long missing;
        try (PreparedStatement up =
                d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?");
            PreparedStatement del = d.prepareStatement("DELETE FROM " + sql + " WHERE id = ?")) {
          // at READ COMMITTED the statement first (todo 334): a write that goes through runs no
          // block, the first-use check of the OUT binds among it
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
          d.commit();
          // the table's first block in the process returns its key through the OUT binds and the
          // cursor both, the check of the OUT binds' typing, one round trip more, once: a key not
          // read after one read goes to the block
          assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 70")).hasSize(1);
          checked =
              rt.measure(
                  () -> {
                    up.setString(1, "b");
                    up.setInt(2, 9);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
          d.commit();
          // a missing key: the statement writes nothing, the block counts 0
          missing =
              rt.measure(
                  () -> {
                    up.setString(1, "z");
                    up.setInt(2, 998);
                    assertThat(up.executeUpdate()).isEqualTo(0);
                  });
        }
        d.commit();
        log(
            "form 2 ("
                + layout
                + "): the table's first keyed UPDATE in the process, the statement alone, "
                + first
                + " round trip(s); the block with the check of the OUT binds "
                + checked);
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
        assertThat(first).isEqualTo(1);
        assertThat(update).isEqualTo(1);
        assertThat(delete).isEqualTo(1);
        assertThat(checked).isEqualTo(2);
        assertThat(missing).isEqualTo(2);
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
   * The write beyond the key at READ COMMITTED on an inline table as the statement first (design
   * 4.3 "The write beyond the key as the statement first", todo 334): Delivery's order_line UPDATE
   * shape, by an order's key prefix, as one nested statement with the count 10, one round trip,
   * with a row of another order in flight; a row of the order in flight undoes it and the block
   * takes the write, an abort without a wait set and the wait and the write with one; a row in
   * flight whose before image the predicate matches and whose after image does not, which the DML
   * leaves alone, found by the look after it; a DELETE beyond the key likewise; the DML's gets a
   * call against the vendor's statement's.
   */
  @Test
  void form3InlineWriteBeyondTheKeyAsTheStatementFirst() throws Exception {
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      admin.createTable(
          N(NS),
          N("tol"),
          TableMetadata.newBuilder()
              .addColumn(N("w"), intType())
              .addColumn(N("o"), intType())
              .addColumn(N("n"), intType())
              .addColumn(N("x"), DataType.TEXT)
              .addPartitionKey(N("w"))
              .addClusteringKey(N("o"))
              .addClusteringKey(N("n"))
              .build(),
          true);
    }
    try (Connection v = ORA.open()) {
      // 3,000 committed rows, ten to an order, as ScalarDB leaves them, their stamps full-sized:
      // a stamp that grows the row migrates it
      ORA.execute(
          v,
          "INSERT INTO "
              + NS
              + ".tol (w, o, n, x, \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
              + " \"tx_committed_at\") SELECT 1, TRUNC((LEVEL - 1) / 10), MOD(LEVEL - 1, 10), 'a',"
              + " RPAD('seed', 36, '-'), 3, 1, 1761600000000, 1761600000000 FROM dual CONNECT BY"
              + " LEVEL <= 3000",
          "COMMIT");
      assertThat(
              Setup.run(
                  catalog,
                  v,
                  t -> t.namespace().equals(N(NS)) && t.table().equals(N("tol")),
                  Duration.ofMinutes(1),
                  List.of()))
          .hasSize(1)
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    // a catalog of its own, read after the table was created, then kept: the driver shares one per
    // TTL in the process, and one read per statement is a round trip
    Properties ttl = new Properties();
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "59001");
    Properties waits = new Properties();
    waits.putAll(ttl);
    waits.setProperty("scalar.db.native_jdbc.inflight_wait_millis", "10000");
    String delivery = "UPDATE " + NS + ".tol SET x = ? WHERE w = 1 AND o = ?";
    String count = "SELECT COUNT(*) FROM " + NS + ".tol WHERE o = %d AND x = '%s'";
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = ORA.openDriver(ttl);
        Connection waiting = ORA.openDriver(waits);
        Connection v = ORA.open();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      d.setAutoCommit(false);
      Counter rt = new Counter(d);
      // a row of another order in flight: the statement goes through
      TwoPhaseCommitTransaction elsewhere = preparedLine(tpc, "tol", 8, 0, "p");
      long one;
      try (PreparedStatement ps = d.prepareStatement(delivery)) {
        ps.setString(1, "b");
        ps.setInt(2, 6);
        assertThat(ps.executeUpdate()).isEqualTo(10); // the text's first parse, unmeasured
        one =
            rt.measure(
                () -> {
                  ps.setString(1, "b");
                  ps.setInt(2, 7);
                  assertThat(ps.executeUpdate()).isEqualTo(10);
                });
      }
      d.commit();
      elsewhere.rollback();
      log("form 3 (inline): Delivery's UPDATE at READ COMMITTED, " + one + " round trip(s)");

      assertThat(one).isEqualTo(1);
      assertThat(rows(d, String.format(count, 7, "b"))).containsExactly("10");
      d.commit();
      // stamped: ScalarDB reads the order's rows committed at the driver's version
      assertThat(scalarDbLine("tol", 7, 3)).isEqualTo("b");
      // a row of the order in flight: an abort without a wait set, nothing of the order written
      TwoPhaseCommitTransaction held = preparedLine(tpc, "tol", 9, 3, "p");
      assertAborts(
          d, "UPDATE " + NS + ".tol SET x = 'c' WHERE w = 1 AND o = 9", "a row of the order");
      d.rollback();
      // with one: the wait, then the write of the whole order once the row settles
      waiting.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      waiting.setAutoCommit(false);
      Future<Integer> write =
          pool.submit(
              () -> update(waiting, "UPDATE " + NS + ".tol SET x = 'd' WHERE w = 1 AND o = 9"));
      Thread.sleep(700);
      assertThat(write.isDone()).as("waits on the row in flight").isFalse();
      held.rollback();
      assertThat(write.get(20, TimeUnit.SECONDS)).isEqualTo(10);
      waiting.commit();
      assertThat(rows(d, String.format(count, 9, "d"))).containsExactly("10");
      d.commit();
      // a row in flight whose before image matches and whose after image does not: the DML
      // skips it, the look after it finds it
      TwoPhaseCommitTransaction before = preparedLine(tpc, "tol", 10, 3, "p");
      assertAborts(
          d,
          "UPDATE " + NS + ".tol SET x = 'e' WHERE w = 1 AND o = 10 AND x = 'a'",
          "a row in flight by its before image");
      d.rollback();
      before.rollback();
      assertThat(rows(d, String.format(count, 10, "a"))).containsExactly("10");
      d.commit();
      // a DELETE beyond the key, one round trip once its text is parsed
      long deleted;
      try (PreparedStatement ps =
          d.prepareStatement("DELETE FROM " + NS + ".tol WHERE w = 1 AND o = ?")) {
        ps.setInt(1, 12);
        assertThat(ps.executeUpdate()).isEqualTo(10); // the text's first parse, unmeasured
        deleted =
            rt.measure(
                () -> {
                  ps.setInt(1, 11);
                  assertThat(ps.executeUpdate()).isEqualTo(10);
                });
      }
      d.commit();
      log("form 3 (inline): a DELETE beyond the key " + deleted + " round trip(s)");
      assertThat(deleted).isEqualTo(1);
      assertThat(rows(d, "SELECT COUNT(*) FROM " + NS + ".tol WHERE o = 11")).containsExactly("0");
      d.commit();
      // the gets a call: the block, its lock, DML and look for rows in flight, against the
      // vendor's, counted over 50 calls after 10, which the parses' gets are not among
      String[] patterns = {
        "DECLARE l_i \"OO\".\"TOL\"%",
        "SELECT /*+ scalardb_native_f1_%TOL%",
        "UPDATE /*+ scalardb_native_f2_%TOL%",
        "SELECT /*+ scalardb_native_f3_%TOL%",
        "%vtol%"
      };
      long[][] at = new long[5][];
      try (PreparedStatement ps = d.prepareStatement(delivery)) {
        for (int o = 100; o < 160; o++) {
          if (o == 110) {
            for (int i = 0; i < 4; i++) {
              at[i] = gets(v, patterns[i]);
            }
          }
          ps.setString(1, "f");
          ps.setInt(2, o);
          assertThat(ps.executeUpdate()).isEqualTo(10);
        }
      }
      d.commit();
      v.setAutoCommit(false);
      try (PreparedStatement ps =
          v.prepareStatement("UPDATE /* vtol */ " + NS + ".tol SET x = ? WHERE w = 1 AND o = ?")) {
        for (int o = 160; o < 220; o++) {
          if (o == 170) {
            at[4] = gets(v, patterns[4]);
          }
          ps.setString(1, "g");
          ps.setInt(2, o);
          assertThat(ps.executeUpdate()).isEqualTo(10);
        }
      }
      v.rollback();
      v.setAutoCommit(true);
      double[] perCall = new double[5];
      long[][] now = new long[5][];
      for (int i = 0; i < 5; i++) {
        now[i] = gets(v, patterns[i]);
        assertThat(now[i][1] - at[i][1]).as(patterns[i]).isEqualTo(50);
        perCall[i] = (now[i][0] - at[i][0]) / 50.0;
      }
      log("form 3 (inline): " + times(at[0], now[0], at[4], now[4]));
      log(
          String.format(
              "form 3 (inline): gets a call, the block %.1f, its lock %.1f, DML %.1f and look for"
                  + " rows in flight %.1f, the vendor's UPDATE %.1f",
              perCall[0], perCall[1], perCall[2], perCall[3], perCall[4]));
      // the block of the keys read and the DML by key costs 43 here
      assertThat(perCall[0]).isLessThan(36);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The write beyond the key on a decoupled table as the statement first (design 4.3 "The write
   * beyond the key as the statement first", todo 334), at both levels: Delivery's order_line UPDATE
   * shape as one block of the DML and the CAS over the keys it returned, the count 10 and every
   * metadata row stamped, one round trip at READ COMMITTED; a row of the order in flight, which the
   * CAS falls short on, undoes it for the block of steps, an abort without a wait set and the wait
   * and the write with one; a row in flight by its before image alone found by the look after the
   * DML; a created table's DELETE removing the metadata rows; at SNAPSHOT on a table with a row in
   * flight elsewhere, the gated block closed, then the statement first; an imported table's row
   * without a metadata row, a CAS short, stamped by the steps; the block's gets a call against the
   * vendor's statement's.
   */
  @Test
  void form3DecoupledWriteBeyondTheKeyAsTheStatementFirst() throws Exception {
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      admin.createTable(
          N(NS),
          N("tod"),
          TableMetadata.newBuilder()
              .addColumn(N("w"), intType())
              .addColumn(N("o"), intType())
              .addColumn(N("n"), intType())
              .addColumn(N("x"), DataType.TEXT)
              .addPartitionKey(N("w"))
              .addClusteringKey(N("o"))
              .addClusteringKey(N("n"))
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
    }
    catalog.invalidate();
    TableInfo info = tableInfo("tod");
    String data = NS + ".\"" + info.dataTable() + "\"";
    String meta = NS + ".\"" + Objects.requireNonNull(info.metadataTable()) + "\"";
    try (Connection v = ORA.open()) {
      // 3,000 committed rows, ten to an order, as ScalarDB leaves them, their stamps full-sized
      ORA.execute(
          v,
          "INSERT INTO "
              + data
              + " (w, o, n, x) SELECT 1, TRUNC((LEVEL - 1) / 10), MOD(LEVEL - 1, 10), 'a' FROM"
              + " dual CONNECT BY LEVEL <= 3000",
          "INSERT INTO "
              + meta
              + " (w, o, n, \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
              + " \"tx_committed_at\") SELECT 1, TRUNC((LEVEL - 1) / 10), MOD(LEVEL - 1, 10),"
              + " RPAD('seed', 36, '-'), 3, 1, 1761600000000, 1761600000000 FROM dual CONNECT BY"
              + " LEVEL <= 3000",
          "COMMIT",
          // an imported table whose rows the predicate reaches beyond the key, one of them added
          // after the setup without a metadata row
          "CREATE TABLE " + NS + ".impw (k NUMBER(10) PRIMARY KEY, g NUMBER(10), x NUMBER(10))",
          "INSERT INTO " + NS + ".impw VALUES (1, 1, 0)",
          "INSERT INTO " + NS + ".impw VALUES (2, 1, 0)");
    }
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      admin.importTable(N(NS), N("impw"), DECOUPLED, Map.of());
    }
    catalog.invalidate();
    try (Connection v = ORA.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  v,
                  t ->
                      t.namespace().equals(N(NS))
                          && (t.table().equals(N("tod")) || t.table().equals(N("impw"))),
                  Duration.ofMinutes(1),
                  List.of()))
          .hasSize(2)
          .allMatch(Setup.Result::succeeded, "succeeded");
      ORA.execute(v, "INSERT INTO " + NS + ".impw VALUES (3, 1, 0)");
    }
    // a catalog of its own, read after the tables were created (the driver shares one per TTL)
    Properties ttl = new Properties();
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "59002");
    Properties waits = new Properties();
    waits.putAll(ttl);
    waits.setProperty("scalar.db.native_jdbc.inflight_wait_millis", "10000");
    String delivery = "UPDATE " + NS + ".tod SET x = ? WHERE w = 1 AND o = ?";
    String count = "SELECT COUNT(*) FROM " + NS + ".tod WHERE o = %d AND x = '%s'";
    String stamped = "SELECT COUNT(*) FROM " + meta + " WHERE o = %d AND \"tx_version\" = 2";
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = ORA.openDriver(ttl);
        Connection waiting = ORA.openDriver(waits);
        Connection v = ORA.open();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      d.setAutoCommit(false);
      Counter rt = new Counter(d);
      // a row of another order in flight: the statement goes through
      TwoPhaseCommitTransaction elsewhere = preparedLine(tpc, "tod", 8, 0, "p");
      long one;
      try (PreparedStatement ps = d.prepareStatement(delivery)) {
        ps.setString(1, "b");
        ps.setInt(2, 6);
        assertThat(ps.executeUpdate()).isEqualTo(10); // the text's first parse, unmeasured
        one =
            rt.measure(
                () -> {
                  ps.setString(1, "b");
                  ps.setInt(2, 7);
                  assertThat(ps.executeUpdate()).isEqualTo(10);
                });
      }
      d.commit();
      log("form 3 (decoupled): Delivery's UPDATE at READ COMMITTED, " + one + " round trip(s)");
      assertThat(one).isEqualTo(1);
      assertThat(rows(d, String.format(count, 7, "b"))).containsExactly("10");
      d.commit();
      assertThat(rows(v, String.format(stamped, 7))).containsExactly("10");
      assertThat(scalarDbLine("tod", 7, 3)).isEqualTo("b");
      // a row of the order in flight: the CAS short, the block of steps, an abort without a wait
      TwoPhaseCommitTransaction held = preparedLine(tpc, "tod", 9, 3, "p");
      assertAborts(
          d, "UPDATE " + NS + ".tod SET x = 'c' WHERE w = 1 AND o = 9", "a row of the order");
      d.rollback();
      // with one: the wait, then the write of the whole order once the row settles
      waiting.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      waiting.setAutoCommit(false);
      Future<Integer> write =
          pool.submit(
              () -> update(waiting, "UPDATE " + NS + ".tod SET x = 'd' WHERE w = 1 AND o = 9"));
      Thread.sleep(700);
      assertThat(write.isDone()).as("waits on the row in flight").isFalse();
      held.rollback();
      assertThat(write.get(20, TimeUnit.SECONDS)).isEqualTo(10);
      waiting.commit();
      assertThat(rows(d, String.format(count, 9, "d"))).containsExactly("10");
      d.commit();
      // a row in flight by its before image alone: the look after the DML finds it
      TwoPhaseCommitTransaction before = preparedLine(tpc, "tod", 10, 3, "p");
      assertAborts(
          d,
          "UPDATE " + NS + ".tod SET x = 'e' WHERE w = 1 AND o = 10 AND x = 'a'",
          "a row in flight by its before image");
      d.rollback();
      before.rollback();
      assertThat(rows(d, String.format(count, 10, "a"))).containsExactly("10");
      d.commit();
      // a created table's DELETE beyond the key: the data rows and their metadata rows
      long deleted;
      try (PreparedStatement ps =
          d.prepareStatement("DELETE FROM " + NS + ".tod WHERE w = 1 AND o = ?")) {
        ps.setInt(1, 12);
        assertThat(ps.executeUpdate()).isEqualTo(10); // the text's first parse, unmeasured
        deleted =
            rt.measure(
                () -> {
                  ps.setInt(1, 11);
                  assertThat(ps.executeUpdate()).isEqualTo(10);
                });
      }
      d.commit();
      log("form 3 (decoupled): a DELETE beyond the key " + deleted + " round trip(s)");
      assertThat(deleted).isEqualTo(1);
      assertThat(rows(v, "SELECT COUNT(*) FROM " + meta + " WHERE o = 11")).containsExactly("0");
      // at SNAPSHOT, a row of another order in flight: the gated block, which writes nothing, the
      // coarse check finding the gate closed, then the statement first
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      long snapshot;
      try (PreparedStatement ps = d.prepareStatement(delivery)) {
        ps.setString(1, "h");
        ps.setInt(2, 13);
        assertThat(ps.executeUpdate()).isEqualTo(10); // the texts' first parses, unmeasured
        d.commit();
        snapshot =
            rt.measure(
                () -> {
                  ps.setString(1, "h");
                  ps.setInt(2, 14);
                  assertThat(ps.executeUpdate()).isEqualTo(10);
                });
      }
      d.commit();
      elsewhere.rollback();
      log("form 3 (decoupled): Delivery's UPDATE at SNAPSHOT, " + snapshot + " round trip(s)");
      assertThat(snapshot).isEqualTo(3);
      assertThat(rows(v, String.format(stamped, 14))).containsExactly("10");
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      // an imported table's row without a metadata row: the CAS short, the steps stamp it
      assertThat(update(d, "UPDATE " + NS + ".impw SET x = 1 WHERE g = 1")).isEqualTo(3);
      d.commit();
      assertThat(
              rows(
                  v,
                  "SELECT COUNT(*) FROM "
                      + NS
                      + ".\""
                      + Objects.requireNonNull(tableInfo("impw").metadataTable())
                      + "\" WHERE \"tx_state\" = 3"))
          .containsExactly("3");
      // the gets a call: the block, its nested statements, against the vendor's, counted over 50
      // calls after 10, which the parses' gets are not among
      String[] patterns = {
        "DECLARE%scalardb_native_g1_%TOD%",
        "SELECT /*+ scalardb_native_g1_%",
        "UPDATE /*+ scalardb_native_g2_%",
        "UPDATE /*+ scalardb_native_g3_%",
        "SELECT /*+ scalardb_native_g4_%",
        "%vtod%"
      };
      long[][] at = new long[6][];
      try (PreparedStatement ps = d.prepareStatement(delivery)) {
        for (int o = 100; o < 160; o++) {
          if (o == 110) {
            for (int i = 0; i < 5; i++) {
              at[i] = gets(v, patterns[i]);
            }
          }
          ps.setString(1, "f");
          ps.setInt(2, o);
          assertThat(ps.executeUpdate()).isEqualTo(10);
        }
      }
      d.commit();
      v.setAutoCommit(false);
      try (PreparedStatement ps =
          v.prepareStatement("UPDATE /* vtod */ " + data + " SET x = ? WHERE w = 1 AND o = ?")) {
        for (int o = 160; o < 220; o++) {
          if (o == 170) {
            at[5] = gets(v, patterns[5]);
          }
          ps.setString(1, "g");
          ps.setInt(2, o);
          assertThat(ps.executeUpdate()).isEqualTo(10);
        }
      }
      v.rollback();
      v.setAutoCommit(true);
      double[] perCall = new double[6];
      long[][] now = new long[6][];
      for (int i = 0; i < 6; i++) {
        now[i] = gets(v, patterns[i]);
        assertThat(now[i][1] - at[i][1]).as(patterns[i]).isEqualTo(50);
        perCall[i] = (now[i][0] - at[i][0]) / 50.0;
      }
      log("form 3 (decoupled): " + times(at[0], now[0], at[5], now[5]));
      log(
          String.format(
              "form 3 (decoupled): gets a call, the block %.1f, its lock %.1f, DML %.1f, CAS %.1f"
                  + " and look for rows in flight %.1f, the vendor's UPDATE %.1f",
              perCall[0], perCall[1], perCall[2], perCall[3], perCall[4], perCall[5]));
      // the block of the keys read, the DML by key and the CAS costs 73 here, the CAS 36
      assertThat(perCall[0]).isLessThan(65);
    } finally {
      pool.shutdownNow();
    }
  }

  /** A line of an order table prepared by a ScalarDB transaction with its x, left in flight. */
  private static TwoPhaseCommitTransaction preparedLine(
      TwoPhaseCommitTransactionManager tpc, String table, int o, int line, String x)
      throws Exception {
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(
        Update.newBuilder()
            .namespace(N(NS))
            .table(N(table))
            .partitionKey(ScalarDbNames.key("w", 1))
            .clusteringKey(
                com.scalar.db.io.Key.newBuilder()
                    .addBigInt(N("o"), o)
                    .addBigInt(N("n"), line)
                    .build())
            .textValue(N("x"), x)
            .build());
    tx.prepare();
    return tx;
  }

  private static @Nullable String scalarDbLine(String table, int o, int line) throws Exception {
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      String x =
          tx.get(
                  com.scalar.db.api.Get.newBuilder()
                      .namespace(N(NS))
                      .table(N(table))
                      .partitionKey(ScalarDbNames.key("w", 1))
                      .clusteringKey(
                          com.scalar.db.io.Key.newBuilder()
                              .addBigInt(N("o"), o)
                              .addBigInt(N("n"), line)
                              .build())
                      .build())
              .map(r -> r.getText(N("x")))
              .orElse(null);
      tx.commit();
      return x;
    }
  }

  /**
   * Buffer gets, executions, elapsed and CPU time (µs) of the statements whose text is LIKE {@code
   * pattern}.
   */
  private static long[] gets(Connection v, String pattern) throws SQLException {
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT NVL(SUM(buffer_gets), 0), NVL(SUM(executions), 0), NVL(SUM(elapsed_time), 0),"
                + " NVL(SUM(cpu_time), 0) FROM v$sql WHERE sql_text LIKE ? AND sql_text NOT LIKE"
                + " '%v$sql%'")) {
      ps.setString(1, pattern);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)};
      }
    }
  }

  /** The block's and the vendor's elapsed and CPU time a call over the window, in µs. */
  private static String times(long[] blockAt, long[] blockNow, long[] vendorAt, long[] vendorNow) {
    return String.format(
        "the block %.0f µs elapsed, %.0f µs CPU a call, the vendor's UPDATE %.0f and %.0f",
        (blockNow[2] - blockAt[2]) / 50.0,
        (blockNow[3] - blockAt[3]) / 50.0,
        (vendorNow[2] - vendorAt[2]) / 50.0,
        (vendorNow[3] - vendorAt[3]) / 50.0);
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
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
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
        long returning = returningInserts(v, sql);
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
        // no generated keys asked for: ojdbc appends no RETURNING ROWID INTO (todo 334)
        assertThat(returningInserts(v, sql))
            .as(layout + ": RETURNING unasked")
            .isEqualTo(returning);
        // the table's first INSERT in the process: the decoupled one is INSERT ALL, a statement
        // without OUT binds, so no first-use check of them either (design 4.3, todo 334)
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
        assertThat(checked).isEqualTo(1);
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
          long before = returningInserts(v, sql);
          long asked = rt.measure(() -> assertThat(ps.executeUpdate()).isEqualTo(1));
          if (layout.equals("inline")) {
            assertThat(returningInserts(v, sql)).as("RETURNING asked").isEqualTo(before + 1);
          }
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
      // INSERT ALL meets the tombstone on the metadata table's constraint and leaves no data row,
      // and the block takes the row over: the INSERT ALL and the block, of which Oracle's count
      // shows the block alone, a failed call adding no round trip to it (checked on ojdbc: a
      // failing INSERT ALL then an UPDATE count 1). Measured on a statement already planned and
      // past its block's first-use check, with the constraint's table known
      d.setAutoCommit(false);
      long overTombstone = 0;
      try (PreparedStatement ins = d.prepareStatement("INSERT INTO " + NS + ".imp VALUES (?, ?)");
          PreparedStatement del = d.prepareStatement("DELETE FROM " + NS + ".imp WHERE k = ?")) {
        for (int x : List.of(11, 12)) {
          del.setInt(1, 7);
          assertThat(del.executeUpdate()).isEqualTo(1);
          d.commit();
          Counter trips = new Counter(d);
          ins.setInt(1, 7);
          ins.setInt(2, x);
          overTombstone = trips.measure(() -> assertThat(ins.executeUpdate()).isEqualTo(1));
          d.commit();
        }
      }
      d.setAutoCommit(true);
      log(
          "form 4: the INSERT over a tombstone, INSERT ALL then the block, "
              + overTombstone
              + " round trip(s) counted");
      assertThat(overTombstone).isEqualTo(1);
      assertThat(rows(v, "SELECT \"tx_state\", \"tx_version\" FROM " + meta + " WHERE k = 7"))
          .containsExactly("3/7");
      assertThat(rows(d, "SELECT x FROM " + NS + ".imp WHERE k = 7")).containsExactly("12");
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
   * Form 4, the batches (design 4.4, "Batches on Oracle"): at READ COMMITTED a prepared statement's
   * entries go as one bulk block where the write is a block (the decoupled keyed write and INSERT),
   * and through ojdbc's own batching where it is one statement (the inline INSERT and keyed write),
   * one round trip for ten either way; an entry in flight is a 0 of the bulk block or the vendor's
   * batch, run after it through the keyed block, whose wait settles it, every count 1; at SNAPSHOT
   * such an entry fails the batch with {@code 40001} and the counts before it, the gate closed for
   * the vendor's batch handing it to the block of entries; a duplicate in the middle of an INSERT
   * batch fails it with the counts before it, the vendor's own or the replay's.
   */
  @Test
  void form4BatchesAsOneBlock() throws Exception {
    Properties ttl = new Properties();
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
    Properties waits = new Properties();
    waits.putAll(ttl);
    waits.setProperty("scalar.db.native_jdbc.inflight_wait_millis", "10000");
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
        // the table verified first, by a block of its own, so that the batches measured are the
        // form itself and not the first-use check
        verifyOutBinds(d, sql);
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
        // the table's first batch in the process: no check of the OUT binds, which only the block
        // of entries makes, and neither layout's batch is one at READ COMMITTED
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
                + "): the table's first batch in the process "
                + checked
                + " round trip(s)");
        assertThat(checked).isEqualTo(1);
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
   * Form 4, an inline table's keyed batches through ojdbc's own batching (design 4.4 "The inline
   * layout's entries through the vendor's batching", todo 334): at both levels one execution of the
   * single form's statement a batch, one cursor over sizes 5 to 15 and no parse after the prepare,
   * where the decoupled table's go as the block of entries, every count 1 on both; a key without a
   * row is the vendor's 0; at READ COMMITTED two entries on one key, read first or not, end two
   * versions on, as the entries one by one do; a row in flight in the middle waits, then is
   * written, or aborts {@code 40001} with the counts before it without the wait; an entry the
   * engine refuses fails the batch with the counts before it and the single form's error; at
   * SNAPSHOT a row in flight elsewhere in the table closes the gate, and the block of entries
   * writes the entries.
   */
  @Test
  void form4InlineKeyedBatchesThroughTheVendorsBatching() throws Exception {
    // ojdbc's statement cache as the benchmarks run it, a prepare of a cached text no parse call
    Properties cached = new Properties();
    cached.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
    cached.setProperty("oracle.jdbc.implicitStatementCacheSize", "32");
    Properties waits = new Properties();
    waits.putAll(cached);
    waits.setProperty("scalar.db.native_jdbc.inflight_wait_millis", "10000");
    int rc = Connection.TRANSACTION_READ_COMMITTED;
    int snapshot = Connection.TRANSACTION_REPEATABLE_READ;
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = ORA.openDriver(cached);
        Connection waiting = ORA.openDriver(waits);
        Connection v = ORA.open();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      for (String table : List.of("t", "ti")) {
        try (PreparedStatement ps =
            d.prepareStatement(
                "INSERT INTO " + NS + "." + table + " (id, x, v) VALUES (?, 'a', 0)")) {
          for (int id = 300; id < 340; id++) {
            ps.setInt(1, id);
            ps.addBatch();
          }
          assertThat(ps.executeBatch()).hasSize(40).containsOnly(1);
        }
      }
      for (int level : List.of(rc, snapshot)) {
        String name = level == rc ? "READ COMMITTED" : "SNAPSHOT";
        for (String table : List.of("t", "ti")) {
          String tag = "vb" + level + table;
          d.setTransactionIsolation(level);
          d.setAutoCommit(false);
          try (PreparedStatement ps =
              d.prepareStatement(
                  "UPDATE /* " + tag + " */ " + NS + "." + table + " SET x = ? WHERE id = ?")) {
            for (int size : List.of(5, 9, 15, 7)) {
              for (int k = 0; k < size; k++) {
                ps.setString(1, "b" + size);
                ps.setInt(2, 300 + k);
                ps.addBatch();
              }
              assertThat(ps.executeBatch()).as(name + " " + table).hasSize(size).containsOnly(1);
              d.commit();
            }
          }
          assertThat(scalarDbX(table, 300)).isEqualTo("b7");
          assertThat(scalarDbX(table, 310)).isEqualTo("b15");
          long[] cursor = updates(v, tag);
          log(
              "form 4 at "
                  + name
                  + " ("
                  + table
                  + "): four keyed batches of 5 to 15, the UPDATE's cursors "
                  + cursor[0]
                  + ", parse calls "
                  + cursor[1]
                  + ", executions "
                  + cursor[2]);
          if (table.equals("ti")) {
            assertThat(cursor)
                .as(name + ": one cursor, one parse, an execution a batch")
                .containsExactly(1, 1, 4);
          } else {
            assertThat(cursor[0])
                .as(name + ": the block of entries, no UPDATE of its own")
                .isZero();
          }
          // a key without a row, the vendor's 0
          try (PreparedStatement ps =
              d.prepareStatement("UPDATE " + NS + "." + table + " SET x = ? WHERE id = ?")) {
            for (int id : new int[] {300, 301, 999, 302}) {
              ps.setString(1, "m");
              ps.setInt(2, id);
              ps.addBatch();
            }
            assertThat(ps.executeBatch()).as(name + " " + table).containsExactly(1, 1, 0, 1);
            d.commit();
            // a batch of keys without rows alone, on a table not yet found clear
            for (int id : new int[] {998, 999}) {
              ps.setString(1, "m");
              ps.setInt(2, id);
              ps.addBatch();
            }
            assertThat(ps.executeBatch()).as(name + " " + table).containsExactly(0, 0);
            d.commit();
          }
          assertThat(scalarDbX(table, 302)).isEqualTo("m");
        }
      }
      // at READ COMMITTED two entries on one key, without and with its version read first, as the
      // entries one by one
      d.setTransactionIsolation(rc);
      for (boolean read : List.of(false, true)) {
        int batched = read ? 322 : 320;
        int single = batched + 1;
        long[] was = {inlineVersion(v, batched), inlineVersion(v, single)};
        if (read) {
          assertThat(
                  rows(
                      d,
                      "SELECT x FROM " + NS + ".ti WHERE id IN (" + batched + ", " + single + ")"))
              .hasSize(2);
        }
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + NS + ".ti SET x = ? WHERE id = ?")) {
          for (String x : List.of("k1", "k2")) {
            ps.setString(1, x);
            ps.setInt(2, batched);
            ps.addBatch();
          }
          assertThat(ps.executeBatch()).containsExactly(1, 1);
          for (String x : List.of("k1", "k2")) {
            ps.setString(1, x);
            ps.setInt(2, single);
            assertThat(ps.executeUpdate()).isEqualTo(1);
          }
        }
        d.commit();
        long batchedMoved = inlineVersion(v, batched) - was[0];
        long singleMoved = inlineVersion(v, single) - was[1];
        log(
            "form 4 at READ COMMITTED: two entries on one key"
                + (read ? ", read first" : "")
                + ", the version "
                + batchedMoved
                + " on, one by one "
                + singleMoved);
        assertThat(batchedMoved).isEqualTo(2).isEqualTo(singleMoved);
        assertThat(scalarDbX("ti", batched)).isEqualTo("k2");
      }
      // a row in flight in the middle at READ COMMITTED: the wait, then the write
      TwoPhaseCommitTransaction held = prepared(tpc, "ti", 330, "p");
      waiting.setTransactionIsolation(rc);
      waiting.setAutoCommit(false);
      Future<int[]> batch =
          pool.submit(
              () -> {
                try (PreparedStatement ps =
                    waiting.prepareStatement("UPDATE " + NS + ".ti SET x = ? WHERE id = ?")) {
                  for (int id = 329; id < 332; id++) {
                    ps.setString(1, "w");
                    ps.setInt(2, id);
                    ps.addBatch();
                  }
                  return ps.executeBatch();
                }
              });
      Thread.sleep(700);
      assertThat(batch.isDone()).as("waits on the entry in flight").isFalse();
      held.rollback();
      assertThat(batch.get(20, TimeUnit.SECONDS)).containsExactly(1, 1, 1);
      waiting.commit();
      assertThat(scalarDbX("ti", 330)).isEqualTo("w");
      // without the wait, the abort and the counts before it
      held = prepared(tpc, "ti", 330, "p");
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".ti SET x = ? WHERE id = ?")) {
        for (int id = 329; id < 332; id++) {
          ps.setString(1, "n");
          ps.setInt(2, id);
          ps.addBatch();
        }
        assertThatThrownBy(ps::executeBatch)
            .as("an entry in flight without the wait")
            .isInstanceOf(BatchUpdateException.class)
            .satisfies(
                e -> {
                  assertThat(((BatchUpdateException) e).getUpdateCounts()).containsExactly(1);
                  assertThat(((SQLException) e).getSQLState()).isEqualTo("40001");
                });
      }
      d.rollback();
      held.rollback();
      // an entry the engine refuses, at both levels: the counts before it, the single form's error
      BigDecimal tooLarge = new BigDecimal("1E20");
      for (int level : List.of(rc, snapshot)) {
        d.setTransactionIsolation(level);
        String single = null;
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + NS + ".ti SET v = ? WHERE id = ?")) {
          ps.setBigDecimal(1, tooLarge);
          ps.setInt(2, 334);
          ps.executeUpdate();
        } catch (SQLException e) {
          single = e.getSQLState() + " " + e.getErrorCode();
        }
        d.rollback();
        assertThat(single).as("the single form refuses it").isNotNull();
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + NS + ".ti SET v = ? WHERE id = ?")) {
          for (int id = 332; id < 336; id++) {
            ps.setBigDecimal(1, id == 334 ? tooLarge : BigDecimal.ONE);
            ps.setInt(2, id);
            ps.addBatch();
          }
          String expected = single;
          assertThatThrownBy(ps::executeBatch)
              .as("ORA-01438 as the third entry")
              .isInstanceOf(BatchUpdateException.class)
              .satisfies(
                  e -> {
                    SQLException x = (SQLException) e;
                    assertThat(((BatchUpdateException) e).getUpdateCounts()).containsExactly(1, 1);
                    assertThat(x.getSQLState() + " " + x.getErrorCode()).isEqualTo(expected);
                  });
        }
        d.rollback();
      }
      // at SNAPSHOT a row in flight elsewhere in the table: the gate closed, the block of entries
      d.setTransactionIsolation(snapshot);
      held = prepared(tpc, "ti", 336, "p");
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".ti SET x = ? WHERE id = ?")) {
        for (int id : new int[] {337, 338}) {
          ps.setString(1, "g");
          ps.setInt(2, id);
          ps.addBatch();
        }
        assertThat(ps.executeBatch()).containsExactly(1, 1);
      }
      d.commit();
      held.rollback();
      assertThat(scalarDbX("ti", 338)).isEqualTo("g");
      d.setAutoCommit(true);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Form 4 at READ COMMITTED on a decoupled table (design 4.4 "The READ COMMITTED decoupled
   * entries", todo 334): the keyed batch as one bulk block over the DML and the CAS, one cursor for
   * the block and one for each of its two statements over sizes 5 to 15, no parse after the first;
   * a key without a row a 0 through the keyed block; two entries on one key, read first or not,
   * through the block of entries, the version two on as one by one; a row in flight in the middle
   * waits then is written, or aborts {@code 40001} with the counts before it without the wait; a
   * version moved by another connection after the read aborts {@code 40001}; the INSERT batch
   * through the INSERT's bulk block, a tombstone taken over, a duplicate in the middle failing the
   * batch with the counts before it.
   */
  @Test
  void form4ReadCommittedDecoupledBatchesAsBulkBlocks() throws Exception {
    Properties cached = new Properties();
    cached.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
    cached.setProperty("oracle.jdbc.implicitStatementCacheSize", "32");
    Properties waits = new Properties();
    waits.putAll(cached);
    waits.setProperty("scalar.db.native_jdbc.inflight_wait_millis", "10000");
    String meta =
        NS
            + "."
            + Engine.ORACLE.quote(
                Objects.requireNonNull(catalog.lookup(List.of(), N(NS), N("t")).metadataTable()));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = ORA.openDriver(cached);
        Connection waiting = ORA.openDriver(waits);
        Connection other = driver();
        Connection v = ORA.open();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      try (PreparedStatement ps =
          d.prepareStatement("INSERT INTO " + NS + ".t (id, x, v) VALUES (?, 'a', 0)")) {
        for (int id = 400; id < 440; id++) {
          ps.setInt(1, id);
          ps.addBatch();
        }
        assertThat(ps.executeBatch()).hasSize(40).containsOnly(1);
      }
      // texts of this run's own, whose cursors are new
      String rcb = "/* rcb" + System.nanoTime() + " */";
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      d.setAutoCommit(false);
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + rcb + " " + NS + ".t SET x = ? WHERE id = ?")) {
        for (int size : List.of(5, 9, 15, 7)) {
          for (int k = 0; k < size; k++) {
            ps.setString(1, "b" + size);
            ps.setInt(2, 400 + k);
            ps.addBatch();
          }
          assertThat(ps.executeBatch()).hasSize(size).containsOnly(1);
          d.commit();
        }
      }
      assertThat(scalarDbX("t", 400)).isEqualTo("b7");
      assertThat(scalarDbX("t", 410)).isEqualTo("b15");
      long[][] cursors = rcBlocks(v, rcb);
      log(
          "form 4 at READ COMMITTED (decoupled): four keyed batches of 5 to 15, the bulk block and"
              + " its two statements, cursors, parse calls, executions "
              + java.util.Arrays.deepToString(cursors));
      assertThat(cursors)
          .as("one cursor each, one parse, an execution a batch")
          .isDeepEqualTo(new long[][] {{1, 1, 4}, {2, 2, 8}});
      // a key without a row: a 0 through the keyed block
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
        for (int id : new int[] {401, 402, 999, 403}) {
          ps.setString(1, "m");
          ps.setInt(2, id);
          ps.addBatch();
        }
        assertThat(ps.executeBatch()).containsExactly(1, 1, 0, 1);
      }
      d.commit();
      assertThat(scalarDbX("t", 403)).isEqualTo("m");
      // two entries on one key, without and with its version read first, as the entries one by one
      for (boolean read : List.of(false, true)) {
        int batched = read ? 422 : 420;
        int single = batched + 1;
        long[] was = {metaVersion(v, meta, batched), metaVersion(v, meta, single)};
        if (read) {
          assertThat(
                  rows(
                      d,
                      "SELECT x FROM "
                          + NS
                          + ".t WHERE id IN ("
                          + batched
                          + ", "
                          + single
                          + ", 425)"))
              .hasSize(3);
        }
        try (PreparedStatement ps =
            d.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
          for (int id : new int[] {batched, 425, batched}) {
            ps.setString(1, id == batched ? "k" : "o");
            ps.setInt(2, id);
            ps.addBatch();
          }
          assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
          for (int k = 0; k < 2; k++) {
            ps.setString(1, "k");
            ps.setInt(2, single);
            assertThat(ps.executeUpdate()).isEqualTo(1);
          }
        }
        d.commit();
        long batchedMoved = metaVersion(v, meta, batched) - was[0];
        long singleMoved = metaVersion(v, meta, single) - was[1];
        log(
            "form 4 at READ COMMITTED (decoupled): two entries on one key"
                + (read ? ", read first" : "")
                + ", the version "
                + batchedMoved
                + " on, one by one "
                + singleMoved);
        assertThat(batchedMoved).isEqualTo(2).isEqualTo(singleMoved);
      }
      // a row in flight in the middle: the wait, then the write
      TwoPhaseCommitTransaction held = prepared(tpc, "t", 430, "p");
      waiting.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      waiting.setAutoCommit(false);
      Future<int[]> batch =
          pool.submit(
              () -> {
                try (PreparedStatement ps =
                    waiting.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
                  for (int id = 429; id < 432; id++) {
                    ps.setString(1, "w");
                    ps.setInt(2, id);
                    ps.addBatch();
                  }
                  return ps.executeBatch();
                }
              });
      Thread.sleep(700);
      assertThat(batch.isDone()).as("waits on the entry in flight").isFalse();
      held.rollback();
      assertThat(batch.get(20, TimeUnit.SECONDS)).containsExactly(1, 1, 1);
      waiting.commit();
      assertThat(scalarDbX("t", 430)).isEqualTo("w");
      // without the wait, the abort and the counts before it
      held = prepared(tpc, "t", 430, "p");
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
        for (int id = 429; id < 432; id++) {
          ps.setString(1, "n");
          ps.setInt(2, id);
          ps.addBatch();
        }
        assertThatThrownBy(ps::executeBatch)
            .as("an entry in flight without the wait")
            .isInstanceOf(BatchUpdateException.class)
            .satisfies(
                e -> {
                  assertThat(((BatchUpdateException) e).getUpdateCounts()).containsExactly(1);
                  assertThat(((SQLException) e).getSQLState()).isEqualTo("40001");
                });
      }
      d.rollback();
      held.rollback();
      // a version moved by another connection between the read and the batch
      assertThat(rows(d, "SELECT x FROM " + NS + ".t WHERE id BETWEEN 433 AND 436")).hasSize(4);
      assertThat(update(other, "UPDATE " + NS + ".t SET x = 'z' WHERE id = 435")).isEqualTo(1);
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
        for (int id = 433; id < 437; id++) {
          ps.setString(1, "r");
          ps.setInt(2, id);
          ps.addBatch();
        }
        assertThatThrownBy(ps::executeBatch)
            .as("a version moved since the read")
            .isInstanceOf(BatchUpdateException.class)
            .satisfies(
                e -> {
                  assertThat(((BatchUpdateException) e).getUpdateCounts()).containsExactly(1, 1);
                  assertThat(((SQLException) e).getSQLState()).isEqualTo("40001");
                });
      }
      d.rollback();
      assertThat(scalarDbX("t", 435)).isEqualTo("z");
      // the INSERT batch through the INSERT's bulk block: a tombstone taken over, then a duplicate
      d.setAutoCommit(true);
      assertThat(update(d, "INSERT INTO " + NS + ".imp VALUES (40, 1)")).isEqualTo(1);
      assertThat(update(d, "DELETE FROM " + NS + ".imp WHERE k = 40")).isEqualTo(1);
      d.setAutoCommit(false);
      String impMeta =
          NS
              + "."
              + Engine.ORACLE.quote(
                  Objects.requireNonNull(
                      catalog.lookup(List.of(), N(NS), N("imp")).metadataTable()));
      String tombstone = rows(v, "SELECT \"tx_version\" FROM " + impMeta + " WHERE k = 40").get(0);
      // a text of this run's own, whose block's cursor is new
      String tag = "/* ib" + System.nanoTime() + " */";
      Counter rt = new Counter(d);
      long inserts;
      try (PreparedStatement ps =
          d.prepareStatement("INSERT " + tag + " INTO " + NS + ".imp VALUES (?, ?)")) {
        inserts =
            rt.measure(
                () -> {
                  for (int k : List.of(41, 40, 42)) {
                    ps.setInt(1, k);
                    ps.setInt(2, 2);
                    ps.addBatch();
                  }
                  assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
                });
      }
      d.commit();
      List<String> stamped =
          rows(
              v,
              "SELECT k, \"tx_state\", \"tx_version\" FROM "
                  + impMeta
                  + " WHERE k IN (40, 41, 42) ORDER BY k");
      log(
          "form 4 at READ COMMITTED: three INSERTs over a tombstone at version "
              + tombstone
              + ", "
              + inserts
              + " round trip(s), metadata "
              + stamped);
      assertThat(insertBulks(v, tag)).as("the INSERT's bulk block").isEqualTo(1);
      assertThat(inserts).as("one bulk block").isEqualTo(1);
      assertThat(stamped)
          .containsExactly("40/3/" + (Integer.parseInt(tombstone) + 1), "41/3/1", "42/3/1");
      assertThat(rows(d, "SELECT k, x FROM " + NS + ".imp WHERE k BETWEEN 40 AND 42 ORDER BY k"))
          .containsExactly("40/2", "41/2", "42/2");
      try (PreparedStatement ps = d.prepareStatement("INSERT INTO " + NS + ".imp VALUES (?, ?)")) {
        for (int k : List.of(43, 44, 41, 45)) {
          ps.setInt(1, k);
          ps.setInt(2, 3);
          ps.addBatch();
        }
        assertThatThrownBy(ps::executeBatch)
            .as("a duplicate as the third entry")
            .isInstanceOf(BatchUpdateException.class)
            .satisfies(
                e -> {
                  assertThat(((BatchUpdateException) e).getUpdateCounts()).containsExactly(1, 1);
                  assertThat(((SQLException) e).getSQLState()).isEqualTo("23000");
                });
      }
      d.rollback();
      assertThat(rows(d, "SELECT COUNT(*) FROM " + NS + ".imp WHERE k IN (43, 44)"))
          .containsExactly("0");
      d.setAutoCommit(true);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * The READ COMMITTED bulk block whose text holds {@code tag}, and its nested statements, as
   * {@code V$SQL} has them: their cursors, parse calls and executions.
   */
  private static long[][] rcBlocks(Connection v, String tag) throws SQLException {
    long[] block = new long[3];
    String hash = null;
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT parse_calls, executions, REGEXP_SUBSTR(sql_fulltext,"
                + " 'scalardb_native_qa?1_([0-9a-f]+)', 1, 1, NULL, 1) FROM v$sql WHERE"
                + " command_type = 47 AND DBMS_LOB.INSTR(sql_fulltext, ?) > 0")) {
      ps.setString(1, tag);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          block[0]++;
          block[1] += rs.getLong(1);
          block[2] += rs.getLong(2);
          hash = rs.getString(3);
        }
      }
    }
    long[] nested = new long[3];
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT parse_calls, executions FROM v$sql WHERE command_type <> 47 AND sql_text LIKE"
                + " '%scalardb_native_q%' || ? || ' */%'")) {
      ps.setString(1, String.valueOf(hash));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          nested[0]++;
          nested[1] += rs.getLong(1);
          nested[2] += rs.getLong(2);
        }
      }
    }
    return new long[][] {block, nested};
  }

  /** The executions {@code V$SQL} has of the INSERT's bulk blocks whose text holds {@code tag}. */
  private static long insertBulks(Connection v, String tag) throws SQLException {
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT NVL(SUM(executions), 0) FROM v$sql WHERE command_type = 47 AND"
                + " DBMS_LOB.INSTR(sql_fulltext, 'FORALL i IN 1 .. l_n INSERT') > 0 AND"
                + " DBMS_LOB.INSTR(sql_fulltext, ?) > 0")) {
      ps.setString(1, tag);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The metadata row's version of the decoupled table's key {@code id}. */
  private static long metaVersion(Connection v, String meta, int id) throws SQLException {
    try (PreparedStatement ps =
        v.prepareStatement("SELECT \"tx_version\" FROM " + meta + " WHERE id = ?")) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * The UPDATE cursors {@code V$SQL} has of a text tagged {@code tag}: their count, parse calls,
   * executions.
   */
  private static long[] updates(Connection v, String tag) throws SQLException {
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT COUNT(*), NVL(SUM(parse_calls), 0), NVL(SUM(executions), 0) FROM v$sql WHERE"
                + " command_type = 6 AND sql_text LIKE '%/* ' || ? || ' */%'")) {
      ps.setString(1, tag);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
      }
    }
  }

  /** The inline row's version as the row holds it. */
  private static long inlineVersion(Connection v, int id) throws SQLException {
    try (PreparedStatement ps =
        v.prepareStatement("SELECT \"tx_version\" FROM " + NS + ".ti WHERE id = ?")) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
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
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
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
   * Form 5's scope on a decoupled table (design 4.3, "The locking read on Oracle", todo 334): the
   * one-statement lock at READ COMMITTED locks the data row alone, {@code FOR UPDATE OF} the
   * derived table's first key column, {@code NOWAIT} kept after it; the metadata row stays free for
   * a write block, which takes the data row first, so a lock waiting on the data row holds nothing
   * that block waits for (ORA-00060 otherwise). The inline form, one table, keeps its clause.
   */
  @Test
  void form5LocksTheDataRowAlone() throws Exception {
    try (Connection v = ORA.open()) {
      Router router = new Router(Engine.ORACLE, catalog, new Session(Engine.ORACLE));
      router.setIsolation(Connection.TRANSACTION_READ_COMMITTED);
      for (String table : List.of("t", "ti")) {
        for (String wait : List.of("", " NOWAIT")) {
          String lock = "SELECT x FROM " + NS + "." + table + " WHERE id = ? FOR UPDATE" + wait;
          Writes.LockPlan plan =
              (Writes.LockPlan) Objects.requireNonNull(router.route(v, lock).write);
          String fast = Objects.requireNonNull(plan.fastRc, "the one-statement lock").sql;
          log("form 5 scope (" + table + wait + "): " + fast);
          assertThat(fast)
              .endsWith(
                  table.equals("t")
                      ? " FOR UPDATE OF " + exposedKey(fast) + wait
                      : " FOR UPDATE" + wait);
        }
      }
    }
    try (Connection d = ORA.openDriver();
        Connection v = ORA.open()) {
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      d.setAutoCommit(false);
      v.setAutoCommit(false);
      String meta = Objects.requireNonNull(tableInfo("t").metadataTable());
      for (String wait : List.of("", " NOWAIT")) {
        assertThat(rows(d, "SELECT x FROM " + NS + ".t WHERE id = 56 FOR UPDATE" + wait))
            .containsExactly("a");
        assertThat(
                rows(
                    v,
                    "SELECT 1 FROM "
                        + Q(N(NS))
                        + "."
                        + Q(meta)
                        + " WHERE id = 56 FOR UPDATE NOWAIT"))
            .as("the metadata row is free")
            .hasSize(1);
        v.rollback();
        assertThatThrownBy(
                () -> rows(v, "SELECT x FROM " + NS + ".t WHERE id = 56 FOR UPDATE NOWAIT"))
            .as("the data row is locked")
            .isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(54));
        v.rollback();
        d.rollback();
      }
      d.setAutoCommit(true);
    }
  }

  /**
   * Form 5 at SNAPSHOT on an inline table read whole: the statement as written, gated or not, its
   * {@code *} or {@code q.*} spelled out as the user's columns, no metadata column among them
   * (design 4.3 "The locking read on Oracle", todo 334); one round trip a lock, the vendor's row.
   * Before, the whole-row lock took the steps over the committed copy, five round trips with the
   * row held (the second Oracle BenchBase run).
   */
  @Test
  void form5LocksAnInlineRowReadWholeInOneStatement() throws Exception {
    try (Connection v = ORA.open()) {
      Router router = new Router(Engine.ORACLE, catalog, new Session(Engine.ORACLE));
      router.setIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      for (String[] form :
          List.of(new String[] {"*", ""}, new String[] {"ti.*", ""}, new String[] {"a.*", " a"})) {
        String lock =
            "SELECT " + form[0] + " FROM " + NS + ".ti" + form[1] + " WHERE id = ? FOR UPDATE";
        Writes.LockPlan plan =
            (Writes.LockPlan) Objects.requireNonNull(router.route(v, lock).write);
        for (Writes.Step step :
            List.of(
                Objects.requireNonNull(plan.oraclePlain, "the plain lock"),
                Objects.requireNonNull(plan.oracleGated, "the gated lock"))) {
          log("form 5 at SNAPSHOT, read whole (" + form[0] + "): " + step.sql);
          // the select list, after the mark's comment and before the first FROM: the gate's own
          // probe names the state column
          String items =
              step.sql.substring(step.sql.indexOf("SELECT "), step.sql.indexOf(" FROM "));
          assertThat(items)
              .doesNotContain(form[0])
              .doesNotContain("tx_")
              .contains(Q(N("x")))
              .contains(Q(N("v")));
        }
      }
    }
    try (Connection d = driver();
        Connection v = ORA.open()) {
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      Counter rt = new Counter(d);
      String lock = "SELECT * FROM " + NS + ".ti WHERE id = 64 FOR UPDATE";
      List<String> expected = rows(v, "SELECT id, x, v FROM " + NS + ".ti WHERE id = 64");
      List<?>[] got = new List<?>[1];
      long gated = rt.measure(() -> got[0] = rows(d, lock));
      assertThat(got[0]).isEqualTo(expected);
      long plain = rt.measure(() -> got[0] = rows(d, lock));
      assertThat(got[0]).isEqualTo(expected);
      try (Statement st = d.createStatement();
          ResultSet rs = st.executeQuery(lock)) {
        assertThat(rs.getMetaData().getColumnCount()).isEqualTo(3);
      }
      log("form 5 at SNAPSHOT, read whole: " + gated + " round trip(s), then " + plain);
      assertThat(gated).isEqualTo(1);
      assertThat(plain).isEqualTo(1);
      assertThatThrownBy(
              () -> rows(v, "SELECT x FROM " + NS + ".ti WHERE id = 64 FOR UPDATE NOWAIT"))
          .as("the row is locked")
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getErrorCode()).isEqualTo(54));
      d.rollback();
      d.setAutoCommit(true);
    }
  }

  /** The {@code x."k"} after the lock's {@code FOR UPDATE OF}, as the text spells it. */
  private static String exposedKey(String sql) {
    int of = sql.lastIndexOf(" FOR UPDATE OF ");
    return of < 0 ? "(none)" : sql.substring(of + 15).split(" ")[0];
  }

  private static TableInfo tableInfo(String table) throws SQLException {
    return catalog.scalarDbTables().stream()
        .filter(t -> t.namespace().equals(N(NS)) && t.table().equals(N(table)))
        .findFirst()
        .orElseThrow();
  }

  /**
   * Form 6 on an inline table (design 4.2 "The read side on Oracle", todo 334): the committed copy
   * at READ COMMITTED carries {@code NO_INDEX_SS(t "T_INFLIGHT")}, its state condition a filter on
   * the row and never a key, and a range scan in a transaction reaches the rows by the primary
   * key's range, not a skip scan of the in-flight index (tx_state, key), as the sixth run's plan
   * had it.
   */
  @Test
  void form6InlineRangeScanTakesThePrimaryKey() throws Exception {
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      admin.createTable(
          N(NS),
          N("tscan"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("x"), DataType.TEXT)
              .addPartitionKey(N("id"))
              .build(),
          true);
    }
    try (Connection v = ORA.open()) {
      // 30,000 committed rows, as ScalarDB leaves them
      ORA.execute(
          v,
          "INSERT INTO "
              + NS
              + ".tscan (id, x, \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
              + " \"tx_committed_at\") SELECT LEVEL, 'r', 'seed', 3, 1, 1, 1 FROM dual CONNECT BY"
              + " LEVEL <= 30000",
          "COMMIT");
      assertThat(
              Setup.run(
                  catalog,
                  v,
                  t -> t.namespace().equals(N(NS)) && t.table().equals(N("tscan")),
                  Duration.ofMinutes(1),
                  List.of()))
          .hasSize(1)
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    String tag = "/* ns" + System.nanoTime() + " */";
    String sql = "SELECT " + tag + " x FROM " + NS + ".tscan WHERE id BETWEEN 1000 AND 1499";
    // a catalog read afresh: one cached since before the table was created would not route it
    try (Connection d = ORA.openDriver();
        Connection v = ORA.open()) {
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      d.setAutoCommit(false);
      assertThat(d.nativeSQL(sql)).contains("/*+ NO_INDEX_SS(t \"TSCAN_INFLIGHT\") */");
      assertThat(rows(d, sql)).hasSize(500);
      d.commit();
      d.setAutoCommit(true);
      String pk =
          rows(
                  v,
                  "SELECT index_name FROM all_constraints WHERE owner = '"
                      + N(NS)
                      + "' AND table_name = '"
                      + N("TSCAN")
                      + "' AND constraint_type = 'P'")
              .get(0);
      StringBuilder plan = new StringBuilder();
      try (Statement st = v.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT p.plan_table_output FROM v$sql s, TABLE(DBMS_XPLAN.DISPLAY_CURSOR(s.sql_id,"
                      + " s.child_number)) p WHERE s.sql_text LIKE '%"
                      + tag
                      + "%' AND s.sql_text NOT LIKE '%v$sql%'")) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
      log("form 6 (inline): a 500-row range scan at READ COMMITTED, the plan\n" + plan);
      // the committed copy by the primary key's range; the in-flight arm by the in-flight index
      assertThat(plan.toString())
          .containsPattern("INDEX RANGE SCAN +\\| " + pk + " ")
          .doesNotContain("SKIP SCAN");
    }
  }

  /**
   * The SNAPSHOT state column on an inline table (design 4.2 "On Oracle both hold", todo 334 D1):
   * the row's own state behind the gate, not the key set of the in-flight index, which Oracle plans
   * as two probes of that index per row once a row elsewhere in the table closes the gate. A 10-row
   * prefix read's gets near the vendor's with a row in flight elsewhere; the column 1 for the row
   * in flight and 0 for a clear table; a decoupled table's text keeps the key set.
   */
  @Test
  void form1InlineStateColumnIsTheRowsOwn() throws Exception {
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      admin.createTable(
          N(NS),
          N("tstate"),
          TableMetadata.newBuilder()
              .addColumn(N("w"), intType())
              .addColumn(N("o"), intType())
              .addColumn(N("n"), intType())
              .addColumn(N("x"), DataType.TEXT)
              .addPartitionKey(N("w"))
              .addClusteringKey(N("o"))
              .addClusteringKey(N("n"))
              .build(),
          true);
    }
    String insert =
        "INSERT INTO "
            + NS
            + ".tstate (w, o, n, x, \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
            + " \"tx_committed_at\") ";
    try (Connection v = ORA.open()) {
      // 30,000 committed rows, ten to an order, as ScalarDB leaves them
      ORA.execute(
          v,
          insert
              + "SELECT 1, TRUNC((LEVEL - 1) / 10), MOD(LEVEL - 1, 10), 'r', 'seed', 3, 1, 1, 1"
              + " FROM dual CONNECT BY LEVEL <= 30000",
          "COMMIT");
      assertThat(
              Setup.run(
                  catalog,
                  v,
                  t -> t.namespace().equals(N(NS)) && t.table().equals(N("tstate")),
                  Duration.ofMinutes(1),
                  List.of()))
          .hasSize(1)
          .allMatch(Setup.Result::succeeded, "succeeded");
      // a row of another order in flight, prepared and not committed
      ORA.execute(v, insert + "VALUES (1, 5000, 0, 'p', 'other', 2, 1, 1, NULL)", "COMMIT");
    }
    String tag = "/* st" + System.nanoTime() + " */";
    String sql = "SELECT " + tag + " x FROM " + NS + ".tstate WHERE w = 1 AND o = 1200";
    try (Connection d = ORA.openDriver();
        Connection v = ORA.open()) {
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      String routed = d.nativeSQL(sql);
      log("form 1 (inline): the state column at SNAPSHOT: " + routed);
      assertThat(routed)
          .contains("\"scalardb_native_v2\" IN (1, 2) THEN 1 END")
          .doesNotContain(") IN (SELECT");
      assertThat(d.nativeSQL("SELECT x FROM " + NS + ".t WHERE id = 1"))
          .as("decoupled: the key set kept")
          .contains(") IN (SELECT");
      for (int i = 0; i < 300; i++) {
        assertThat(rows(d, sql)).hasSize(10);
        d.rollback(); // each read in its own transaction, the table never remembered clear
      }
      long[] gets = new long[2];
      try (Statement st = v.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT SUM(buffer_gets), SUM(executions) FROM v$sql WHERE sql_text LIKE '%"
                      + tag
                      + "%' AND sql_text NOT LIKE '%v$sql%'")) {
        rs.next();
        gets[0] = rs.getLong(1);
        gets[1] = rs.getLong(2);
      }
      StringBuilder plan = new StringBuilder();
      try (Statement st = v.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT p.plan_table_output FROM v$sql s, TABLE(DBMS_XPLAN.DISPLAY_CURSOR(s.sql_id,"
                      + " s.child_number)) p WHERE s.sql_text LIKE '%"
                      + tag
                      + "%' AND s.sql_text NOT LIKE '%v$sql%'")) {
        while (rs.next()) {
          plan.append(rs.getString(1)).append('\n');
        }
      }
      double perRead = (double) gets[0] / gets[1];
      log(
          "form 1 (inline): a 10-row read at SNAPSHOT with a row in flight elsewhere, "
              + gets[1]
              + " executions, "
              + String.format("%.1f", perRead)
              + " gets a read, the plan\n"
              + plan);
      // each copy's gate alone reads the in-flight index, no probe per row: about 11 gets, where
      // the key set's probes cost about 30 and the vendor's read 6
      assertThat(gets[1]).isGreaterThanOrEqualTo(300);
      assertThat(perRead).isLessThan(15);
      assertThat(plan.toString().lines().filter(l -> l.contains("| TSTATE_INFLIGHT "))).hasSize(2);
      // the column itself: 1 for the row in flight, NULL for a committed row; 0 for a clear table
      String one =
          d.nativeSQL("SELECT x FROM " + NS + ".tstate WHERE w = 1 AND o = 5000 AND n = 0");
      String committed =
          d.nativeSQL("SELECT x FROM " + NS + ".tstate WHERE w = 1 AND o = 1200 AND n = 0");
      d.rollback();
      assertThat(lastColumn(v, one)).isEqualTo("1");
      assertThat(lastColumn(v, committed)).isNull();
      ORA.execute(v, "DELETE FROM " + NS + ".tstate WHERE o = 5000", "COMMIT");
      assertThat(lastColumn(v, committed)).isEqualTo("0");
    }
  }

  /** The last column of a statement's one row, as the vendor reads it. */
  private static @Nullable String lastColumn(Connection v, String sql) throws SQLException {
    try (Statement st = v.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      String value = rs.getString(rs.getMetaData().getColumnCount());
      assertThat(rs.next()).isFalse();
      return value;
    }
  }

  /**
   * Anchor-less reads on Oracle as written behind a gate column per table (design 4.2 "Anchor-less
   * reads as written behind a gate column", todo 334), on both layouts: Delivery's SUM over an
   * order at READ COMMITTED one round trip, the statement as written, the right sum with a row of
   * another order in flight, and the split read's sum, the before image's amount, with one of the
   * order; a GROUP BY and a DISTINCT, their gate columns hidden; StockLevel's count over a join,
   * its unqualified key columns each table's; a predicate without key conjuncts keeping the split
   * read; at SNAPSHOT the whole table's gate, a table found clear by it, the next read the plain
   * statement; the gets a call of the gated SUM against the vendor's and the split read's.
   */
  @Test
  void form7AnchorlessReadsAsWrittenBehindGates() throws Exception {
    try (DistributedTransactionAdmin admin = ORA.admin()) {
      for (String layout : List.of("i", "d")) {
        Map<String, String> options =
            layout.equals("d") ? Map.of("transaction-metadata-decoupling", "true") : Map.of();
        admin.createTable(
            N(NS),
            N("gol" + layout),
            TableMetadata.newBuilder()
                .addColumn(N("ol_w"), intType())
                .addColumn(N("ol_o"), intType())
                .addColumn(N("ol_n"), intType())
                .addColumn(N("ol_i"), intType())
                .addColumn(N("ol_amt"), intType())
                .addPartitionKey(N("ol_w"))
                .addClusteringKey(N("ol_o"))
                .addClusteringKey(N("ol_n"))
                .build(),
            options);
        admin.createTable(
            N(NS),
            N("gst" + layout),
            TableMetadata.newBuilder()
                .addColumn(N("s_w"), intType())
                .addColumn(N("s_i"), intType())
                .addColumn(N("s_q"), intType())
                .addPartitionKey(N("s_w"))
                .addClusteringKey(N("s_i"))
                .build(),
            options);
      }
    }
    catalog.invalidate();
    try (Connection v = ORA.open()) {
      for (String layout : List.of("i", "d")) {
        // 3,000 order lines, ten to an order, and 100 items of stock, their stamps full-sized
        seed(
            v,
            "gol" + layout,
            "ol_w, ol_o, ol_n, ol_i, ol_amt",
            "1, TRUNC((LEVEL - 1) / 10), MOD(LEVEL - 1, 10), MOD(LEVEL * 7, 100),"
                + " MOD(LEVEL, 10) + 1",
            "ol_w, ol_o, ol_n",
            "1, TRUNC((LEVEL - 1) / 10), MOD(LEVEL - 1, 10)",
            3000);
        seed(
            v,
            "gst" + layout,
            "s_w, s_i, s_q",
            "1, LEVEL - 1, MOD(LEVEL, 20)",
            "s_w, s_i",
            "1, LEVEL - 1",
            100);
      }
      assertThat(
              Setup.run(
                  catalog,
                  v,
                  t -> t.namespace().equals(N(NS)) && t.table().matches("(?i)g(ol|st)[id]"),
                  Duration.ofMinutes(1),
                  List.of()))
          .hasSize(4)
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    // a catalog of its own, read after the tables were created (the driver shares one per TTL)
    Properties ttl = new Properties();
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "59003");
    try (Connection d = ORA.openDriver(ttl);
        Connection v = ORA.open();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      for (String layout : List.of("i", "d")) {
        String name = layout.equals("i") ? "inline" : "decoupled";
        String ol = NS + ".gol" + layout;
        String st = NS + ".gst" + layout;
        String olData = NS + ".\"" + tableInfo("gol" + layout).dataTable() + "\"";
        String tag = "/* g" + layout + System.nanoTime() + " */";
        String sum = "SELECT " + tag + " SUM(ol_amt) FROM " + ol + " WHERE ol_w = 1 AND ol_o = ?";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        Counter rt = new Counter(d);
        String sum7 = rows(v, "SELECT SUM(ol_amt) FROM " + olData + " WHERE ol_o = 7").get(0);
        String sum9 = rows(v, "SELECT SUM(ol_amt) FROM " + olData + " WHERE ol_o = 9").get(0);
        // a row of another order in flight: the gate open, the statement as written
        TwoPhaseCommitTransaction elsewhere = preparedAmount(tpc, "gol" + layout, 8, 0, 100);
        long open;
        long closed;
        try (PreparedStatement ps = d.prepareStatement(sum)) {
          ps.setInt(1, 6);
          assertThat(rowsOf(ps)).hasSize(1); // the texts' first parses, unmeasured
          open =
              rt.measure(
                  () -> {
                    ps.setInt(1, 7);
                    assertThat(rowsOf(ps)).containsExactly(sum7);
                  });
          // a row of the order in flight: the gate closed, the split read, the before image's
          // amount in the sum
          TwoPhaseCommitTransaction held = preparedAmount(tpc, "gol" + layout, 9, 3, 100);
          closed =
              rt.measure(
                  () -> {
                    ps.setInt(1, 9);
                    assertThat(rowsOf(ps)).containsExactly(sum9);
                  });
          held.rollback();
        }
        d.commit();
        elsewhere.rollback();
        log(
            "form 7 ("
                + name
                + "): Delivery's SUM at READ COMMITTED, the gate open "
                + open
                + ", closed "
                + closed
                + " round trip(s)");
        assertThat(open).isEqualTo(1);
        assertThat(closed).isEqualTo(2);
        assertThat(executedTexts(v, tag))
            .as(name + ": the gated text ran")
            .anyMatch(t -> t.contains("\"scalardb_native_g0\""));
        // a GROUP BY and a DISTINCT, the gate columns hidden
        try (PreparedStatement ps =
            d.prepareStatement(
                "SELECT ol_n, COUNT(*) FROM "
                    + ol
                    + " WHERE ol_w = 1 AND ol_o = ? GROUP BY ol_n ORDER BY ol_n")) {
          ps.setInt(1, 11);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
            int groups = 0;
            while (rs.next()) {
              assertThat(rs.getInt(1)).isEqualTo(groups++);
              assertThat(rs.getInt(2)).isEqualTo(1);
            }
            assertThat(groups).isEqualTo(10);
          }
        }
        try (PreparedStatement ps =
            d.prepareStatement(
                "SELECT DISTINCT ol_o FROM " + ol + " WHERE ol_w = 1 AND ol_o BETWEEN ? AND ?")) {
          ps.setInt(1, 20);
          ps.setInt(2, 22);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(1);
            List<Integer> orders = new ArrayList<>();
            while (rs.next()) {
              orders.add(rs.getInt(1));
            }
            assertThat(orders).containsExactlyInAnyOrder(20, 21, 22);
          }
          // no row: at READ COMMITTED the split read says so
          ps.setInt(1, 5000);
          ps.setInt(2, 5001);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isFalse();
          }
        }
        // StockLevel's count over a join, its key columns unqualified
        String stockTag = "/* s" + layout + System.nanoTime() + " */";
        String stockLevel =
            "SELECT "
                + stockTag
                + " COUNT(DISTINCT s_i) FROM "
                + ol
                + ", "
                + st
                + " WHERE ol_w = ? AND ol_o < ? AND ol_o >= ? AND s_w = ? AND s_i = ol_i AND s_q"
                + " < ?";
        String expected =
            rows(
                    v,
                    "SELECT COUNT(DISTINCT s_i) FROM "
                        + olData
                        + ", "
                        + NS
                        + ".\""
                        + tableInfo("gst" + layout).dataTable()
                        + "\" WHERE ol_w = 1 AND ol_o < 50 AND ol_o >= 30 AND s_w = 1 AND s_i ="
                        + " ol_i AND s_q < 10")
                .get(0);
        try (PreparedStatement ps = d.prepareStatement(stockLevel)) {
          ps.setInt(1, 1);
          ps.setInt(2, 50);
          ps.setInt(3, 30);
          ps.setInt(4, 1);
          ps.setInt(5, 10);
          assertThat(rowsOf(ps)).containsExactly(expected);
        }
        assertThat(executedTexts(v, stockTag))
            .as(name + ": StockLevel's gated text, a gate per table")
            .anyMatch(t -> t.contains("\"scalardb_native_g1\""));
        // a predicate without key conjuncts: the split read alone
        String noKeyTag = "/* n" + layout + System.nanoTime() + " */";
        assertThat(rows(d, "SELECT " + noKeyTag + " COUNT(*) FROM " + st + " WHERE s_q < 0"))
            .containsExactly("0");
        assertThat(executedTexts(v, noKeyTag))
            .as(name + ": no gate without a key conjunct")
            .isNotEmpty()
            .noneMatch(t -> t.contains("scalardb_native_g0"));
        d.commit();
        // at SNAPSHOT the whole table's gate: one round trip, the table found clear, the next read
        // the plain statement
        d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        String snapTag = "/* p" + layout + System.nanoTime() + " */";
        String count = "SELECT " + snapTag + " COUNT(*) FROM " + st + " WHERE s_q >= 0";
        long first = rt.measure(() -> assertThat(rows(d, count)).containsExactly("100"));
        long second = rt.measure(() -> assertThat(rows(d, count)).containsExactly("100"));
        d.commit();
        log(
            "form 7 ("
                + name
                + "): at SNAPSHOT an aggregate on a table not yet found clear "
                + first
                + ", then "
                + second
                + " round trip(s)");
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(executedTexts(v, snapTag))
            .anyMatch(t -> t.contains("\"scalardb_native_g0\""))
            .anyMatch(t -> !t.contains("scalardb_native"));
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        // gets and times a call over 50 calls after 10: the gated SUM, the split read's text run
        // by the vendor, and the vendor's SUM over the data table
        String measured = "/* m" + layout + System.nanoTime() + " */";
        String gatedSum =
            "SELECT " + measured + " SUM(ol_amt) FROM " + ol + " WHERE ol_w = 1 AND ol_o = ?";
        String split = d.nativeSQL(gatedSum).replace(measured, "/* split */ " + measured);
        String[] texts = {
          gatedSum,
          split,
          "SELECT /* vendor */ " + measured + " SUM(ol_amt) FROM " + olData + " WHERE ol_o = ?"
        };
        String[] patterns = {
          "SELECT " + measured + "%scalardb_native_g0%",
          "SELECT /* split */ " + measured + "%",
          "SELECT /* vendor */ " + measured + "%"
        };
        long[][] per = new long[3][];
        for (int f = 0; f < 3; f++) {
          Connection c = f == 0 ? d : v;
          long[] at = null;
          try (PreparedStatement ps = c.prepareStatement(texts[f])) {
            int marks = ps.getParameterMetaData().getParameterCount();
            for (int o = 100; o < 160; o++) {
              if (o == 110) {
                at = gets(v, patterns[f]);
              }
              for (int i = 1; i <= marks; i++) {
                ps.setInt(i, o);
              }
              assertThat(rowsOf(ps)).hasSize(1);
            }
          }
          long[] now = gets(v, patterns[f]);
          long[] from = Objects.requireNonNull(at);
          assertThat(now[1] - from[1]).as(patterns[f]).isEqualTo(50);
          per[f] = new long[] {now[0] - from[0], now[2] - from[2], now[3] - from[3]};
        }
        d.commit();
        log(
            String.format(
                "form 7 (%s): Delivery's SUM a call, gets / elapsed µs / CPU µs: as written behind"
                    + " the gate %.1f / %.0f / %.0f, the split read %.1f / %.0f / %.0f, the"
                    + " vendor's %.1f / %.0f / %.0f",
                name,
                per[0][0] / 50.0,
                per[0][1] / 50.0,
                per[0][2] / 50.0,
                per[1][0] / 50.0,
                per[1][1] / 50.0,
                per[1][2] / 50.0,
                per[2][0] / 50.0,
                per[2][1] / 50.0,
                per[2][2] / 50.0));
        assertThat(per[0][0]).isLessThan(per[1][0]);
      }
    }
  }

  /** Seeds a ScalarDB table of either layout with committed rows, their stamps full-sized. */
  private static void seed(
      Connection v,
      String table,
      String columns,
      String values,
      String keys,
      String keyValues,
      int rows)
      throws SQLException {
    TableInfo t = tableInfo(table);
    String stamps =
        "\"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\", \"tx_committed_at\"";
    String stamped = "RPAD('seed', 36, '-'), 3, 1, 1761600000000, 1761600000000";
    String data = NS + ".\"" + t.dataTable() + "\"";
    if (t.layout() == com.scalar.db.nativejdbc.catalog.Layout.INLINE) {
      ORA.execute(
          v,
          "INSERT INTO "
              + data
              + " ("
              + columns
              + ", "
              + stamps
              + ") SELECT "
              + values
              + ", "
              + stamped
              + " FROM dual CONNECT BY LEVEL <= "
              + rows,
          "COMMIT");
    } else {
      ORA.execute(
          v,
          "INSERT INTO "
              + data
              + " ("
              + columns
              + ") SELECT "
              + values
              + " FROM dual CONNECT BY LEVEL <= "
              + rows,
          "INSERT INTO "
              + NS
              + ".\""
              + Objects.requireNonNull(t.metadataTable())
              + "\" ("
              + keys
              + ", "
              + stamps
              + ") SELECT "
              + keyValues
              + ", "
              + stamped
              + " FROM dual CONNECT BY LEVEL <= "
              + rows,
          "COMMIT");
    }
  }

  /** An order line's amount prepared by a ScalarDB transaction, left in flight. */
  private static TwoPhaseCommitTransaction preparedAmount(
      TwoPhaseCommitTransactionManager tpc, String table, int o, int line, int amount)
      throws Exception {
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(
        Update.newBuilder()
            .namespace(N(NS))
            .table(N(table))
            .partitionKey(ScalarDbNames.key("ol_w", 1))
            .clusteringKey(
                com.scalar.db.io.Key.newBuilder()
                    .addBigInt(N("ol_o"), o)
                    .addBigInt(N("ol_n"), line)
                    .build())
            .bigIntValue(N("ol_amt"), amount)
            .build());
    tx.prepare();
    return tx;
  }

  /** The texts V$SQL holds that carry {@code tag} and ran at least once. */
  private static List<String> executedTexts(Connection v, String tag) throws SQLException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT sql_fulltext FROM v$sql WHERE sql_text LIKE ? AND executions > 0 AND sql_text"
                + " NOT LIKE '%v$sql%'")) {
      ps.setString(1, "%" + tag + "%");
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  /** A prepared read's rows, each its columns joined by '/'. */
  private static List<String> rowsOf(PreparedStatement ps) throws SQLException {
    List<String> out = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        List<String> row = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
          row.add(rs.getString(i));
        }
        out.add(String.join("/", row));
      }
    }
    return out;
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
    ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
    return ORA.openDriver(ttl);
  }

  /**
   * The keyed write at READ COMMITTED on Oracle, the statement first and the block on a miss
   * (design 4.3, todo 334), counted in round trips: a key written without a read, one, the
   * statement alone; a missing key, the statement and the block, two, count 0; a key read then
   * written, one, its anchor in the statement; the same key written again, whose row carries the
   * transaction's own stamp and not the anchor, through the block; and a CHAR key bound unpadded,
   * which the anchor recorded as the row spells it does not match, through the block, which still
   * finds the anchor: a version moved by another connection aborts {@code 40001}.
   */
  @Test
  void form2StatementFirstAtReadCommitted() throws Exception {
    try (Connection d = driver();
        Connection other = driver()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        // the table's OUT binds verified first, so that the misses measured are the form itself
        verifyOutBinds(d, sql);
        Counter rt = new Counter(d);
        long blind;
        long missing;
        long read;
        long twice;
        try (PreparedStatement up =
            d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
          blind =
              rt.measure(
                  () -> {
                    up.setString(1, "p");
                    up.setInt(2, 22);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
          missing =
              rt.measure(
                  () -> {
                    up.setString(1, "p");
                    up.setInt(2, 996);
                    assertThat(up.executeUpdate()).isEqualTo(0);
                  });
          assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 23")).hasSize(1);
          read =
              rt.measure(
                  () -> {
                    up.setString(1, "q");
                    up.setInt(2, 23);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
          twice =
              rt.measure(
                  () -> {
                    up.setString(1, "r");
                    up.setInt(2, 23);
                    assertThat(up.executeUpdate()).isEqualTo(1);
                  });
        }
        d.commit();
        log(
            "form 2 ("
                + layout
                + ") at READ COMMITTED: a key written without a read "
                + blind
                + ", a missing key "
                + missing
                + ", a key read then written "
                + read
                + ", the same key again "
                + twice
                + " round trip(s)");
        assertThat(blind).isEqualTo(1);
        assertThat(missing).isEqualTo(2);
        assertThat(read).isEqualTo(1);
        assertThat(twice).isEqualTo(2);
        assertThat(scalarDbX(table, 22)).isEqualTo("p");
        assertThat(scalarDbX(table, 23)).isEqualTo("r");
        d.setAutoCommit(true);
      }
      // the CHAR(5) key, stored padded: the read records it as the row spells it, the statement
      // spells it unpadded, a CHAR literal compared padded, so the block runs, which matches the
      // key as the row returns it (a VARCHAR2 bind of 'a' matches no row, the vendor's statement
      // alone neither)
      String ch = KeyType.K_CH.table();
      d.setAutoCommit(false);
      Counter rt = new Counter(d);
      assertThat(rows(d, "SELECT x FROM " + ch + " WHERE k = 'a'")).hasSize(1);
      long unpadded;
      try (PreparedStatement up = d.prepareStatement("UPDATE " + ch + " SET x = x WHERE k = 'a'")) {
        unpadded = rt.measure(() -> assertThat(up.executeUpdate()).isEqualTo(1));
      }
      d.commit();
      // padded as the row spells it: the anchor found, the statement alone
      assertThat(rows(d, "SELECT x FROM " + ch + " WHERE k = 'a'")).hasSize(1);
      long padded;
      try (PreparedStatement up = d.prepareStatement("UPDATE " + ch + " SET x = x WHERE k = ?")) {
        padded =
            rt.measure(
                () -> {
                  up.setString(1, "a    ");
                  assertThat(up.executeUpdate()).isEqualTo(1);
                });
      }
      d.commit();
      log(
          "form 2 at READ COMMITTED: a CHAR(5) key read then written, bound unpadded "
              + unpadded
              + ", padded "
              + padded
              + " round trip(s)");
      assertThat(padded).isEqualTo(1);
      // unpadded, the version moved by another connection: the block finds the anchor
      assertThat(rows(d, "SELECT x FROM " + ch + " WHERE k = 'a'")).hasSize(1);
      assertThat(update(other, "UPDATE " + ch + " SET x = x WHERE k = 'a'")).isEqualTo(1);
      try (PreparedStatement up = d.prepareStatement("UPDATE " + ch + " SET x = x WHERE k = 'a'")) {
        assertThatThrownBy(up::executeUpdate)
            .as("CHAR(5): the anchor moved")
            .isInstanceOf(SQLTransactionRollbackException.class)
            .extracting(e -> ((SQLException) e).getSQLState())
            .isEqualTo("40001");
      }
      d.rollback();
      d.setAutoCommit(true);
    }
  }

  /**
   * Form 2 at READ COMMITTED, a row another connection's keyed write holds under the vendor's lock
   * when the statement reaches it (design 4.3 "The keyed write at READ COMMITTED on Oracle", todo
   * 334). Oracle evaluates the statement's WHERE clause on the row as it is once the lock is given,
   * restarting the statement where the row changed: a transaction that read no version of the key
   * writes over the other connection's committed write, as the vendor's statement does; one that
   * read the key first finds its anchor moved, the statement writes nothing, and the block aborts
   * {@code 40001}, the row as the other connection left it.
   */
  @Test
  void form2RowWrittenByAnotherWhileTheStatementWaits() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection d = driver();
        Connection other = driver()) {
      for (String table : List.of("t", "ti")) {
        String sql = NS + "." + table;
        String layout = table.equals("t") ? "decoupled" : "inline";
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        d.setAutoCommit(false);
        other.setAutoCommit(false);
        assertThat(update(other, "UPDATE " + sql + " SET x = 'o' WHERE id = 21")).isEqualTo(1);
        Future<Integer> write =
            pool.submit(() -> update(d, "UPDATE " + sql + " SET x = 'd' WHERE id = 21"));
        Thread.sleep(700);
        assertThat(write.isDone()).as(layout + ": waits on the row").isFalse();
        other.commit();
        assertThat(write.get(20, TimeUnit.SECONDS)).as(layout + ": written").isEqualTo(1);
        d.commit();
        assertThat(scalarDbX(table, 21)).isEqualTo("d");
        // the key read first: its anchor in the statement, which the other's write moves
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 21")).containsExactly("d");
        assertThat(update(other, "UPDATE " + sql + " SET x = 'o' WHERE id = 21")).isEqualTo(1);
        Future<Integer> anchored =
            pool.submit(() -> update(d, "UPDATE " + sql + " SET x = 'e' WHERE id = 21"));
        Thread.sleep(700);
        assertThat(anchored.isDone()).as(layout + ": waits on the row").isFalse();
        other.commit();
        assertThatThrownBy(() -> anchored.get(20, TimeUnit.SECONDS))
            .as(layout + ": the row moved since the transaction read it")
            .cause()
            .isInstanceOf(SQLTransactionRollbackException.class)
            .extracting(e -> ((SQLException) e).getSQLState())
            .isEqualTo("40001");
        d.rollback();
        assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 21")).containsExactly("o");
        assertThat(scalarDbX(table, 21)).isEqualTo("o");
        d.rollback();
        d.setAutoCommit(true);
        other.setAutoCommit(true);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Form 4's batches at SNAPSHOT on a clear decoupled table as one bulk block per write shape
   * (design 4.4 "The entries merged on a clear table at SNAPSHOT"): a batch of keyed UPDATEs, a key
   * without a row among them counted 0, in one round trip; a row in flight in the table closes the
   * gate, nothing written, and the block of entries writes the entries; a batch of INSERTs with a
   * tombstone among them, taken over; a duplicate data row among the INSERTs, the application's,
   * the entries before it counted and the rest not run. Whether the bulk block ran is read from the
   * session's executions: the block, its gate, the bulk DML and the stamp, where the block of
   * entries executes three statements or more an entry.
   */
  @Test
  void form4BatchesAsOneBulkBlockAtSnapshot() throws Exception {
    String meta =
        NS
            + "."
            + Engine.ORACLE.quote(
                Objects.requireNonNull(catalog.lookup(List.of(), N(NS), N("t")).metadataTable()));
    try (Connection d = driver();
        Connection v = ORA.open();
        TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      Counter rt = new Counter(d);
      List<String> before =
          rows(
              v,
              "SELECT id, \"tx_version\" FROM " + meta + " WHERE id BETWEEN 65 AND 69 ORDER BY id");
      // once unmeasured, for the block's text to be parsed: its first parse runs recursive SQL
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
        for (int id : List.of(65, 66, 900, 67, 68, 69)) {
          ps.setString(1, "w");
          ps.setInt(2, id);
          ps.addBatch();
        }
        assertThat(ps.executeBatch()).containsExactly(1, 1, 0, 1, 1, 1);
      }
      d.commit();
      long bulks = executions(d);
      long keyed;
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
        keyed =
            rt.measure(
                () -> {
                  for (int id : List.of(65, 66, 900, 67, 68, 69)) {
                    ps.setString(1, "k");
                    ps.setInt(2, id);
                    ps.addBatch();
                  }
                  assertThat(ps.executeBatch()).containsExactly(1, 1, 0, 1, 1, 1);
                });
      }
      d.commit();
      long ran = executions(d) - bulks;
      List<String> after =
          rows(
              v,
              "SELECT id, \"tx_version\" FROM " + meta + " WHERE id BETWEEN 65 AND 69 ORDER BY id");
      log(
          "form 4 at SNAPSHOT: a batch of six keyed UPDATEs, one key without a row, "
              + keyed
              + " round trip(s), statements executed "
              + ran
              + ", versions "
              + before
              + " -> "
              + after);
      assertThat(keyed).isEqualTo(1);
      assertThat(ran).isLessThan(12);
      for (int i = 0; i < after.size(); i++) {
        int was = Integer.parseInt(before.get(i).split("/")[1]);
        assertThat(after.get(i)).isEqualTo(before.get(i).split("/")[0] + "/" + (was + 2));
      }
      assertThat(scalarDbX("t", 66)).isEqualTo("k");
      // a row in flight in the table: the gate closed, the entries through the block of entries
      TwoPhaseCommitTransaction held = prepared(tpc, "t", 64, "p");
      bulks = executions(d);
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + NS + ".t SET x = ? WHERE id = ?")) {
        for (int id : List.of(65, 66)) {
          ps.setString(1, "g");
          ps.setInt(2, id);
          ps.addBatch();
        }
        assertThat(ps.executeBatch()).containsExactly(1, 1);
      }
      d.commit();
      held.rollback();
      log(
          "form 4 at SNAPSHOT: the gate closed by a row in flight, the entries written by the block of entries, statements executed "
              + (executions(d) - bulks));
      assertThat(scalarDbX("t", 65)).isEqualTo("g");
      // INSERTs with a tombstone among them: imp's key 30 deleted keeps its metadata row
      d.setAutoCommit(true);
      assertThat(update(d, "INSERT INTO " + NS + ".imp VALUES (30, 1)")).isEqualTo(1);
      assertThat(update(d, "DELETE FROM " + NS + ".imp WHERE k = 30")).isEqualTo(1);
      d.setAutoCommit(false);
      String impMeta =
          NS
              + "."
              + Engine.ORACLE.quote(
                  Objects.requireNonNull(
                      catalog.lookup(List.of(), N(NS), N("imp")).metadataTable()));
      bulks = executions(d);
      long inserts;
      try (PreparedStatement ps = d.prepareStatement("INSERT INTO " + NS + ".imp VALUES (?, ?)")) {
        inserts =
            rt.measure(
                () -> {
                  for (int k : List.of(31, 30, 32)) {
                    ps.setInt(1, k);
                    ps.setInt(2, 5);
                    ps.addBatch();
                  }
                  assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
                });
      }
      d.commit();
      List<String> stamped =
          rows(
              v,
              "SELECT k, \"tx_state\", \"tx_version\" FROM "
                  + impMeta
                  + " WHERE k IN (30, 31, 32) ORDER BY k");
      log(
          "form 4 at SNAPSHOT: three INSERTs, a tombstone among them, "
              + inserts
              + " round trip(s), statements executed "
              + (executions(d) - bulks - 0)
              + ", metadata "
              + stamped);
      assertThat(inserts).isEqualTo(1);
      assertThat(stamped).containsExactly("30/3/3", "31/3/1", "32/3/1");
      assertThat(rows(d, "SELECT k, x FROM " + NS + ".imp WHERE k IN (30, 31, 32) ORDER BY k"))
          .containsExactly("30/5", "31/5", "32/5");
      d.commit();
      // a duplicate data row among the INSERTs: the block undone whole, the entries one by one,
      // the application's duplicate with the count before it
      try (PreparedStatement ps = d.prepareStatement("INSERT INTO " + NS + ".imp VALUES (?, ?)")) {
        for (int k : List.of(33, 31, 34)) {
          ps.setInt(1, k);
          ps.setInt(2, 6);
          ps.addBatch();
        }
        assertThatThrownBy(ps::executeBatch)
            .isInstanceOf(java.sql.BatchUpdateException.class)
            .satisfies(
                e -> {
                  java.sql.BatchUpdateException b = (java.sql.BatchUpdateException) e;
                  assertThat(b.getUpdateCounts()).containsExactly(1);
                  assertThat(b.getSQLState()).isEqualTo("23000");
                });
      }
      assertThat(rows(d, "SELECT k FROM " + NS + ".imp WHERE k IN (33, 34) ORDER BY k"))
          .containsExactly("33");
      d.rollback();
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      d.setAutoCommit(true);
    }
  }

  private static final double[] DOUBLES = {
    0.1, 1.005, 2.675, 0.1 + 0.2, 1e-7, 1234.5678, -0.005, Math.PI, 9999.994, 1.0 / 3
  };

  /**
   * A batch of doubles and floats at SNAPSHOT on a clear decoupled table as one bulk block, each
   * element typed as ojdbc binds the scalar on the connection (design 4.4): with {@code
   * SetFloatAndDoubleUseBinary} unset and set, the rows the batch stores are byte for byte, by
   * DUMP, those of the statement alone, 2.675 into a NUMBER(6,2) and 0.1 into a NUMBER among them,
   * and a setNull of DOUBLE and REAL among the entries; a double against a NUMBER and a
   * BINARY_DOUBLE key finds the rows the statement alone finds.
   */
  @Test
  void form4BatchesDoublesAsOneBulkBlockAsOjdbcBindsThem() throws Exception {
    for (boolean binary : List.of(false, true)) {
      Properties p = new Properties();
      if (binary) {
        p.setProperty("oracle.jdbc.SetFloatAndDoubleUseBinary", "true");
      }
      p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "600000");
      int base = binary ? 2000 : 1000;
      try (Connection d = ORA.openDriver(p);
          Connection v = ORA.open()) {
        d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        d.setAutoCommit(false);
        String insert = "INSERT INTO " + NS + ".dbl (k, a, b, c, f) VALUES (?, ?, ?, ?, ?)";
        // once unmeasured, for the block's text to be parsed
        try (PreparedStatement ps = d.prepareStatement(insert)) {
          addDoubles(ps, base + 100);
          assertThat(ps.executeBatch()).hasSize(DOUBLES.length + 1);
        }
        d.commit();
        long before = executions(d);
        try (PreparedStatement ps = d.prepareStatement(insert)) {
          addDoubles(ps, base);
          assertThat(ps.executeBatch()).containsOnly(1).hasSize(DOUBLES.length + 1);
        }
        d.commit();
        long ran = executions(d) - before;
        // the statement alone, entry by entry
        try (PreparedStatement ps = d.prepareStatement(insert)) {
          for (int i = 0; i <= DOUBLES.length; i++) {
            bindDouble(ps, base + 50, i);
            assertThat(ps.executeUpdate()).isEqualTo(1);
          }
        }
        d.commit();
        String dump =
            "SELECT k, DUMP(a), DUMP(b), DUMP(c), DUMP(f) FROM " + NS + ".dbl WHERE k BETWEEN ";
        List<String> batched = dumps(v, dump + base + " AND " + (base + DOUBLES.length));
        List<String> alone = dumps(v, dump + (base + 50) + " AND " + (base + 50 + DOUBLES.length));
        log(
            "form 4 at SNAPSHOT, SetFloatAndDoubleUseBinary "
                + binary
                + ": a batch of "
                + (DOUBLES.length + 1)
                + " INSERTs of doubles, statements executed "
                + ran);
        assertThat(batched).hasSize(DOUBLES.length + 1).isEqualTo(alone);
        assertThat(ran).as("the bulk block, not the block of entries").isLessThan(12);
        if (!binary) {
          // as ojdbc binds a double by default, NUMBER: as the statement alone stores it
          assertThat(rows(v, "SELECT TO_CHAR(a), TO_CHAR(b) FROM " + NS + ".dbl WHERE k = " + base))
              .containsExactly(".1/.1");
          assertThat(rows(v, "SELECT TO_CHAR(a) FROM " + NS + ".dbl WHERE k = " + (base + 2)))
              .containsExactly("2.68");
        } else {
          // bound as BINARY_DOUBLE, as the statement alone stores it: the iteration ran as binary
          assertThat(rows(v, "SELECT TO_CHAR(a) FROM " + NS + ".dbl WHERE k = " + (base + 2)))
              .containsExactly("2.67");
          assertThat(rows(v, "SELECT TO_CHAR(b) FROM " + NS + ".dbl WHERE k = " + base))
              .doesNotContain(".1")
              .hasSize(1);
        }
        // a double against a NUMBER key and a BINARY_DOUBLE key: the rows the statement finds
        for (KeyType k : List.of(KeyType.K_N10, KeyType.K_BD)) {
          double[] keys = k == KeyType.K_N10 ? new double[] {5.0, 5.5} : new double[] {0.1, 0.2};
          String update = "UPDATE " + k.table() + " SET x = ? WHERE k = ?";
          int[] counts;
          try (PreparedStatement ps = d.prepareStatement(update)) {
            for (double key : keys) {
              ps.setInt(1, 0); // as the setup left it, which form 2 reads
              ps.setDouble(2, key);
              ps.addBatch();
            }
            counts = ps.executeBatch();
          }
          d.commit();
          List<Integer> one = new ArrayList<>();
          try (PreparedStatement ps = d.prepareStatement(update)) {
            for (double key : keys) {
              ps.setInt(1, 0);
              ps.setDouble(2, key);
              one.add(ps.executeUpdate());
            }
          }
          d.commit();
          assertThat(counts).as(k.name()).containsExactly(1, 0);
          assertThat(one).as(k.name()).containsExactly(1, 0);
        }
        d.setAutoCommit(true);
        d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      }
    }
  }

  /**
   * ojdbc's resolved {@code SetFloatAndDoubleUseBinary} as the driver reads it, under the key
   * without its {@code oracle.jdbc.} prefix: from a connect property, the URL and the system
   * property, and false by default. An ojdbc that no longer reports it fails here, not as a batch
   * of doubles that silently keeps the block of entries.
   */
  @Test
  void ojdbcReportsSetFloatAndDoubleUseBinary() throws Exception {
    String key = "oracle.jdbc.SetFloatAndDoubleUseBinary";
    try (Connection c = ORA.open()) {
      assertThat(Proxies.binaryDoubles(c)).isFalse();
    }
    Properties p = new Properties();
    p.setProperty("user", ORA.username());
    p.setProperty("password", ORA.password());
    p.setProperty(key, "true");
    try (Connection c = java.sql.DriverManager.getConnection(ORA.url(), p)) {
      assertThat(Proxies.binaryDoubles(c)).isTrue();
    }
    String url = ORA.url() + (ORA.url().contains("?") ? "&" : "?") + key + "=true";
    try (Connection c = java.sql.DriverManager.getConnection(url, ORA.username(), ORA.password())) {
      assertThat(Proxies.binaryDoubles(c)).isTrue();
    }
    System.setProperty(key, "true");
    try (Connection c = ORA.open()) {
      assertThat(Proxies.binaryDoubles(c)).isTrue();
    } finally {
      System.clearProperty(key);
    }
  }

  /** Entry {@code i} of the doubles at key {@code base + i}; the last one's doubles all NULL. */
  private static void bindDouble(PreparedStatement ps, int base, int i) throws SQLException {
    ps.setInt(1, base + i);
    if (i == DOUBLES.length) {
      ps.setNull(2, java.sql.Types.DOUBLE);
      ps.setNull(3, java.sql.Types.FLOAT);
      ps.setNull(4, java.sql.Types.DOUBLE);
      ps.setNull(5, java.sql.Types.REAL);
      return;
    }
    double x = DOUBLES[i];
    ps.setDouble(2, x);
    ps.setDouble(3, x);
    // setObject of a Double and a Float, as setDouble and setFloat
    ps.setObject(4, x);
    ps.setFloat(5, (float) x);
  }

  private static void addDoubles(PreparedStatement ps, int base) throws SQLException {
    for (int i = 0; i <= DOUBLES.length; i++) {
      bindDouble(ps, base, i);
      ps.addBatch();
    }
  }

  /** Each row's DUMPs of its four numeric columns, in key order. */
  private static List<String> dumps(Connection v, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = v.createStatement();
        ResultSet rs = st.executeQuery(sql + " ORDER BY k")) {
      while (rs.next()) {
        out.add(
            rs.getString(2)
                + " | "
                + rs.getString(3)
                + " | "
                + rs.getString(4)
                + " | "
                + rs.getString(5));
      }
    }
    return out;
  }

  /**
   * The nested statements of the blocks told apart (design 4.3, todo 334): at SNAPSHOT a keyed
   * UPDATE runs the clear-table block gated and then ungated, two blocks whose stamp of the
   * metadata rows is the same statement; at READ COMMITTED a batch's block of entries repeats each
   * entry's statements. Named per block and per occurrence, none of them parses again once the
   * session has run it: {@code V$SQL}'s parse calls of the named statements do not move over
   * further transactions while their executions do.
   */
  @Test
  void nestedStatementsParseOnceAcrossBlocks() throws Exception {
    String sql = NS + ".t";
    // ojdbc's statement cache, as the benchmarks run: a block closed after each call closes its
    // nested statements' cursors with it, and every call parses them again whatever their text
    Properties cached = new Properties();
    cached.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "60000");
    cached.setProperty("oracle.jdbc.implicitStatementCacheSize", "128");
    try (Connection d = ORA.openDriver(cached);
        Connection v = ORA.open()) {
      Action round =
          () -> {
            d.setAutoCommit(false);
            d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(update(d, "UPDATE " + sql + " SET x = 'n' WHERE id = 24")).isEqualTo(1);
            assertThat(update(d, "UPDATE " + sql + " SET x = 'n' WHERE id = 25")).isEqualTo(1);
            d.commit();
            d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            // a key read, so that the batch's entries go to the block of entries
            assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 70")).hasSize(1);
            try (PreparedStatement ps =
                d.prepareStatement("UPDATE " + sql + " SET x = ? WHERE id = ?")) {
              for (int id = 26; id <= 28; id++) {
                ps.setString(1, "n");
                ps.setInt(2, id);
                ps.addBatch();
              }
              assertThat(ps.executeBatch()).containsExactly(1, 1, 1);
            }
            d.commit();
            d.setAutoCommit(true);
          };
      for (int i = 0; i < 3; i++) {
        round.run();
      }
      Map<String, long[]> before = nestedParses(v);
      for (int i = 0; i < 5; i++) {
        round.run();
      }
      Map<String, long[]> after = nestedParses(v);
      long parses = 0;
      long executions = 0;
      for (Map.Entry<String, long[]> e : after.entrySet()) {
        long[] was = before.getOrDefault(e.getKey(), new long[2]);
        long p = e.getValue()[0] - was[0];
        long x = e.getValue()[1] - was[1];
        if (x > 0) {
          log("  " + e.getKey() + ": " + x + " executions, " + p + " parse calls");
        }
        parses += p;
        executions += x;
      }
      log(
          "the blocks' named statements over five rounds: "
              + executions
              + " executions, "
              + parses
              + " parse calls");
      assertThat(executions).isGreaterThan(0);
      assertThat(parses).isZero();
    }
  }

  /**
   * The executions {@code V$SQL} has of INSERTs into {@code table} with ojdbc's RETURNING ROWID.
   */
  private static long returningInserts(Connection v, String table) throws SQLException {
    try (PreparedStatement ps =
        v.prepareStatement(
            "SELECT NVL(SUM(executions), 0) FROM v$sql WHERE UPPER(sql_text) LIKE 'INSERT INTO '"
                + " || UPPER(?) || ' %' AND DBMS_LOB.INSTR(sql_fulltext, 'RETURNING ROWID INTO')"
                + " > 0")) {
      ps.setString(1, table);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * Per named statement of the blocks, its parse calls and executions as {@code V$SQL} has them.
   */
  private static Map<String, long[]> nestedParses(Connection v) throws SQLException {
    Map<String, long[]> out = new java.util.TreeMap<>();
    try (Statement st = v.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT SUBSTR(sql_text, 1, 60), parse_calls, executions FROM v$sql WHERE"
                    + " REGEXP_LIKE(sql_text, 'scalardb' || '_native_[a-z]+[0-9]+_[0-9a-f]+')"
                    + " AND command_type <> 47")) {
      while (rs.next()) {
        out.merge(
            rs.getString(1),
            new long[] {rs.getLong(2), rs.getLong(3)},
            (x, y) -> new long[] {x[0] + y[0], x[1] + y[1]});
      }
    }
    return out;
  }

  /**
   * Runs the table's keyed block on a row of its own, in {@code d}'s transaction at READ COMMITTED,
   * which it commits, so that its OUT binds are verified: a key read records the table's anchors,
   * and a key not read then goes to the block, whose row decides the check; a missing key decides
   * nothing.
   */
  private static void verifyOutBinds(Connection d, String sql) throws SQLException {
    assertThat(rows(d, "SELECT x FROM " + sql + " WHERE id = 70")).hasSize(1);
    assertThat(update(d, "UPDATE " + sql + " SET x = x WHERE id = 63")).isEqualTo(1);
    d.commit();
  }

  /** The statements the session behind a connection has executed, recursive ones included. */
  private static long executions(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT s.value FROM v$mystat s JOIN v$statname n ON n.statistic# = s.statistic#"
                    + " WHERE n.name = 'execute count'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /**
   * Form 4's INSERT ALL on keys whose column changes the value given: a CHAR(5) key padded, a
   * NUMBER(10) key rounded, and a TIMESTAMP(6) key of milliseconds, all ScalarDB's metadata table
   * keeps of it (TIMESTAMP(3): a key of microseconds finds no metadata row by either form). The
   * metadata row carries the key as the data row holds it, as the block's RETURNING gives it: every
   * data row has its metadata row by the key.
   */
  @Test
  void form4InsertAllKeysAsTheDataRowHoldsThem() throws Exception {
    try (Connection d = driver();
        Connection v = ORA.open()) {
      d.setAutoCommit(false);
      for (Object[] c :
          List.of(
              new Object[] {KeyType.K_CH, "b"},
              new Object[] {KeyType.K_N10, new java.math.BigDecimal("7.6")},
              new Object[] {KeyType.K_TS, java.sql.Timestamp.valueOf("2024-03-04 05:06:07.123")})) {
        KeyType k = (KeyType) c[0];
        try (PreparedStatement ps =
            d.prepareStatement("INSERT INTO " + k.table() + " (k, x) VALUES (?, 1)")) {
          ps.setObject(1, c[1]);
          assertThat(ps.executeUpdate()).as(k.name()).isEqualTo(1);
        }
        d.commit();
        String meta =
            NS
                + "."
                + Engine.ORACLE.quote(
                    Objects.requireNonNull(
                        catalog
                            .lookup(
                                List.of(), N(NS), N(k.name().toLowerCase(java.util.Locale.ROOT)))
                            .metadataTable()));
        List<String> orphans =
            rows(
                v,
                "SELECT COUNT(*) FROM "
                    + k.table()
                    + " t WHERE NOT EXISTS (SELECT 1 FROM "
                    + meta
                    + " m WHERE m.k = t.k)");
        log(
            "form 4: INSERT ALL of "
                + k.type
                + " key "
                + c[1]
                + ", data rows without metadata "
                + orphans);
        assertThat(orphans).as(k.name()).containsExactly("0");
      }
    }
  }

  /**
   * Form 4's INSERT row the test map had none for: on an imported table a metadata row in flight
   * for a key with no data row (ScalarDB's insert between its metadata upsert and its data insert,
   * section 1), written here by the vendor connection. The block's MERGE finds it in flight, {@code
   * meta} 0 and {@code stamped} 0: abort {@code 40001}, "being written by ScalarDB", no data row
   * left by the rollback; the metadata row gone, the INSERT goes through at version 1.
   */
  @Test
  void form4InsertOverAMetadataRowInFlight() throws Exception {
    String meta =
        NS
            + "."
            + Engine.ORACLE.quote(
                Objects.requireNonNull(catalog.lookup(List.of(), N(NS), N("imp")).metadataTable()));
    try (Connection d = driver();
        Connection v = ORA.open()) {
      assertThat(
              update(
                  v,
                  "INSERT INTO "
                      + meta
                      + " (k, \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\")"
                      + " VALUES (8, 'held', 1, 1, "
                      + System.currentTimeMillis()
                      + ")"))
          .isEqualTo(1);
      d.setAutoCommit(false);
      assertThatThrownBy(() -> update(d, "INSERT INTO " + NS + ".imp VALUES (8, 1)"))
          .as("a metadata row in flight for a key without a data row")
          .isInstanceOf(SQLTransactionRollbackException.class)
          .hasMessageContaining("being written by ScalarDB")
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("40001");
      d.rollback();
      assertThat(rows(v, "SELECT COUNT(*) FROM " + NS + ".imp WHERE k = 8")).containsExactly("0");
      assertThat(update(v, "DELETE FROM " + meta + " WHERE k = 8")).isEqualTo(1);
      assertThat(update(d, "INSERT INTO " + NS + ".imp VALUES (8, 1)")).isEqualTo(1);
      d.commit();
      assertThat(rows(v, "SELECT \"tx_state\", \"tx_version\" FROM " + meta + " WHERE k = 8"))
          .containsExactly("3/1");
      assertThat(rows(d, "SELECT x FROM " + NS + ".imp WHERE k = 8")).containsExactly("1");
    }
  }

  /**
   * A NewOrder-shaped transaction through the driver at SNAPSHOT on the decoupled table, measured
   * from the session's statistics (todo 334's before-and-after for the bulk block): a batch of ten
   * keyed UPDATEs and a batch of ten INSERTs, then the commit. Run only with {@code
   * -Dnativejdbc.oracle.neworder=<transactions>}; it prints per transaction the server CPU, the DB
   * time, the parse calls and the round trips.
   */
  @Test
  void newOrderShapedMeasure() throws Exception {
    int n = Integer.getInteger("nativejdbc.oracle.neworder", 0);
    Assumptions.assumeTrue(n > 0, "-Dnativejdbc.oracle.neworder not set");
    Properties p = new Properties();
    p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "600000");
    p.setProperty("oracle.jdbc.implicitStatementCacheSize", "128");
    try (Connection d = ORA.openDriver(p)) {
      d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      d.setAutoCommit(false);
      int[] next = {1_000_000};
      Action tx =
          () -> {
            try (PreparedStatement up =
                    d.prepareStatement("UPDATE " + NS + ".t SET x = ?, v = v + ? WHERE id = ?");
                PreparedStatement ins =
                    d.prepareStatement("INSERT INTO " + NS + ".t (id, x, v) VALUES (?, ?, ?)")) {
              for (int l = 0; l < 10; l++) {
                up.setString(1, "s");
                up.setInt(2, 1);
                up.setInt(3, 1 + (next[0] + l) % 60);
                up.addBatch();
              }
              up.executeBatch();
              for (int l = 0; l < 10; l++) {
                ins.setInt(1, next[0]++);
                ins.setString(2, "ol");
                ins.setInt(3, l);
                ins.addBatch();
              }
              ins.executeBatch();
            }
            d.commit();
          };
      for (int i = 0; i < 50; i++) {
        tx.run();
      }
      Map<String, Long> a = sessionStats(d);
      long t0 = System.nanoTime();
      for (int i = 0; i < n; i++) {
        tx.run();
      }
      double wall = (System.nanoTime() - t0) / 1000.0 / n;
      Map<String, Long> b = sessionStats(d);
      double per = n;
      System.out.printf(
          "[334] NewOrder-shaped at SNAPSHOT, %d transactions: wall %.0f µs, server CPU %.0f µs, DB"
              + " time %.0f µs, parse calls %.2f, round trips %.2f a transaction%n",
          n,
          wall,
          (b.get("CPU used by this session") - a.get("CPU used by this session")) * 10000.0 / per,
          (b.get("DB time") - a.get("DB time")) * 10000.0 / per,
          (b.get("parse count (total)") - a.get("parse count (total)")) / per,
          (b.get("SQL*Net roundtrips to/from client") - a.get("SQL*Net roundtrips to/from client"))
              / per);
      d.setAutoCommit(true);
      d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }
  }

  /**
   * The keyed UPDATE at READ COMMITTED through the driver beside the vendor's on the data table,
   * each with its commit, and the read of the key before it (todo 334's measure of the statement
   * first): per operation the client thread's CPU, the session's CPU and DB time, its executes,
   * parse calls and round trips, ojdbc's implicit cache at 128 on both. Run only with {@code
   * -Dnativejdbc.oracle.rcmeasure=<operations>}; it changes the rows' x, so alone.
   */
  @Test
  void readCommittedUpdateMeasure() throws Exception {
    int n = Integer.getInteger("nativejdbc.oracle.rcmeasure", 0);
    Assumptions.assumeTrue(n > 0, "-Dnativejdbc.oracle.rcmeasure not set");
    Properties p = new Properties();
    p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "600000");
    p.setProperty("oracle.jdbc.implicitStatementCacheSize", "128");
    Properties raw = new Properties();
    raw.setProperty("user", ORA.username());
    raw.setProperty("password", ORA.password());
    raw.setProperty("oracle.jdbc.implicitStatementCacheSize", "128");
    java.lang.management.ThreadMXBean cpu =
        java.lang.management.ManagementFactory.getThreadMXBean();
    for (int round = 0; round < 2; round++) {
      for (String table : List.of("ti", "t")) {
        String layout = table.equals("t") ? "decoupled" : "inline";
        String data =
            NS + "." + Engine.ORACLE.quote(catalog.lookup(List.of(), N(NS), N(table)).dataTable());
        try (Connection d = ORA.openDriver(p);
            Connection v = java.sql.DriverManager.getConnection(ORA.url(), raw)) {
          d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          d.setAutoCommit(false);
          v.setAutoCommit(false);
          for (boolean rmw : List.of(false, true)) {
            for (boolean driver : List.of(false, true)) {
              Connection c = driver ? d : v;
              String target = driver ? NS + "." + table : data;
              int[] next = {0};
              try (PreparedStatement read =
                      c.prepareStatement("SELECT x FROM " + target + " WHERE id = ?");
                  PreparedStatement up =
                      c.prepareStatement("UPDATE " + target + " SET x = ? WHERE id = ?")) {
                Action op =
                    () -> {
                      int id = 1 + next[0]++ % 60;
                      if (rmw) {
                        read.setInt(1, id);
                        try (ResultSet rs = read.executeQuery()) {
                          rs.next();
                        }
                      }
                      up.setString(1, "m");
                      up.setInt(2, id);
                      assertThat(up.executeUpdate()).isEqualTo(1);
                      c.commit();
                    };
                for (int i = 0; i < 300; i++) {
                  op.run();
                }
                Map<String, Long> a = sessionStats(c);
                long cpu0 = cpu.getCurrentThreadCpuTime();
                long t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                  op.run();
                }
                long wall = System.nanoTime() - t0;
                long client = cpu.getCurrentThreadCpuTime() - cpu0;
                Map<String, Long> b = sessionStats(c);
                double per = n;
                System.out.printf(
                    "[334] round %d, %s, %s %s at READ COMMITTED, %d ops: wall %.0f µs, client CPU"
                        + " %.0f µs, server CPU %.0f µs, DB time %.0f µs, executes %.2f, parses"
                        + " %.2f, round trips %.2f an op%n",
                    round,
                    layout,
                    driver ? "driver" : "vendor",
                    rmw ? "read+update" : "update",
                    n,
                    wall / 1000.0 / per,
                    client / 1000.0 / per,
                    (b.get("CPU used by this session") - a.get("CPU used by this session"))
                        * 10000.0
                        / per,
                    (b.get("DB time") - a.get("DB time")) * 10000.0 / per,
                    (b.get("execute count") - a.get("execute count")) / per,
                    (b.get("parse count (total)") - a.get("parse count (total)")) / per,
                    (b.get("SQL*Net roundtrips to/from client")
                            - a.get("SQL*Net roundtrips to/from client"))
                        / per);
              }
            }
          }
          d.setAutoCommit(true);
        }
      }
    }
  }

  private static Map<String, Long> sessionStats(Connection c) throws SQLException {
    Map<String, Long> out = new java.util.HashMap<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT n.name, s.value FROM v$mystat s JOIN v$statname n ON n.statistic# ="
                    + " s.statistic# WHERE n.name IN ('parse count (total)', 'CPU used by this"
                    + " session', 'DB time', 'SQL*Net roundtrips to/from client', 'execute"
                    + " count')")) {
      while (rs.next()) {
        out.put(rs.getString(1), rs.getLong(2));
      }
    }
    return out;
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
