package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.StatementShape;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The statements a write runs (design 4.3), as text. */
class WritesTest {

  private static TableInfo table() throws Exception {
    return table("t", null);
  }

  private static TableInfo created() throws Exception {
    return table("t_data", "t");
  }

  /** A table keyed by id; created when its virtual table has the user's name. */
  private static TableInfo table(String data, @Nullable String virtual) throws Exception {
    Constructor<TableInfo> c =
        TableInfo.class.getDeclaredConstructor(
            String.class,
            String.class,
            Layout.class,
            String.class,
            String.class,
            String.class,
            List.class,
            List.class,
            List.class,
            List.class,
            boolean.class);
    c.setAccessible(true);
    return c.newInstance(
        "ns",
        "t",
        virtual == null ? Layout.DECOUPLED_IMPORTED : Layout.DECOUPLED_CREATED,
        data,
        "t_meta",
        virtual == null ? "t_scalardb" : virtual,
        List.of("id"),
        List.of(),
        List.of("id", "x"),
        List.of(),
        true);
  }

  private static List<String> plan(String sql) throws Exception {
    return plan(table(), sql);
  }

  private static List<String> plan(TableInfo t, String sql) throws Exception {
    return plan(t, sql, false);
  }

  private static List<String> plan(TableInfo t, String sql, boolean snapshot) throws Exception {
    StatementShape shape =
        StatementShape.of(SqlAst.parse(sql, Engine.POSTGRESQL).orElseThrow()).orElseThrow();
    return Writes.plan(Engine.POSTGRESQL, shape, t, "", snapshot, false, null).sql();
  }

  /** The last two texts of a plan at SNAPSHOT: the fast statement gated, and for a clear table. */
  private static List<String> fast(TableInfo t, String sql) throws Exception {
    List<String> all = plan(t, sql, true);
    return all.subList(all.size() - 2, all.size());
  }

  @Test
  void atSnapshot_aWriteIsTheVendorsStatementWithTheStamp_gatedOnRowsInFlight() throws Exception {
    String stamp =
        "\"tx_id\" = ?, \"tx_prepared_at\" = ?, \"tx_committed_at\" = ?, \"tx_state\" = 3,"
            + " \"tx_version\" = CASE WHEN \"tx_id\" IS NULL THEN 1 ELSE"
            + " COALESCE(\"tx_version\", 0) + 1 END";
    // an inline table: the stamp in the SET list, ahead of the user's items, the gate one probe of
    // the in-flight index, its first row in the index's order (todo 337); nothing more for a
    // table the transaction found clear
    String gate =
        " AND (SELECT 1 FROM \"ns\".\"t\" WHERE \"tx_state\" IN (1, 2) ORDER BY \"id\" LIMIT 1)"
            + " IS NULL";
    assertThat(fast(inline(), "UPDATE t SET x = x || ? WHERE id = ? -- note"))
        .containsExactly(
            "UPDATE t SET " + stamp + ", x = x || ? WHERE (id = ?)" + gate + " -- note",
            "UPDATE t SET " + stamp + ", x = x || ? WHERE (id = ?) -- note");
    assertThat(fast(inline(), "DELETE FROM t WHERE x = ?"))
        .containsExactly("DELETE FROM t WHERE (x = ?)" + gate, "DELETE FROM t WHERE (x = ?)");
    assertThat(fast(inline(), "UPDATE t SET x = 1"))
        .containsExactly(
            "UPDATE t SET " + stamp + ", x = 1 WHERE" + gate.substring(" AND".length()),
            "UPDATE t SET " + stamp + ", x = 1");
    // a decoupled table: the user's DML returns the keys it wrote, whose metadata rows are
    // stamped from them; a created table's by an UPDATE, which its DELETE removes
    String counts =
        " RETURNING 1) SELECT (SELECT count(*) FROM scalardb_native_w), (SELECT count(*) FROM"
            + " scalardb_native_s)";
    assertThat(fast(created(), "UPDATE t SET x = ? WHERE id = ?").get(1))
        .isEqualTo(
            "WITH scalardb_native_w AS (UPDATE \"ns\".\"t_data\" AS t SET x = ? WHERE (id = ?)"
                + " RETURNING \"id\" AS scalardb_native_w0), scalardb_native_s AS (UPDATE"
                + " \"ns\".\"t_meta\" m SET "
                + stamp
                + " FROM scalardb_native_w w WHERE m.\"id\" = w.scalardb_native_w0 AND"
                + " COALESCE(m.\"tx_state\", 3) = 3"
                + counts);
    assertThat(fast(created(), "DELETE FROM t WHERE id = ?").get(0))
        .isEqualTo(
            "WITH scalardb_native_w AS (DELETE FROM \"ns\".\"t_data\" AS t WHERE (id = ?) AND"
                + " (SELECT 1 FROM \"ns\".\"t_meta\" WHERE \"tx_state\" IN (1, 2) ORDER BY \"id\""
                + " LIMIT 1) IS NULL"
                + " RETURNING \"id\" AS scalardb_native_w0), scalardb_native_s AS (DELETE FROM"
                + " \"ns\".\"t_meta\" m USING scalardb_native_w w WHERE m.\"id\" ="
                + " w.scalardb_native_w0 AND COALESCE(m.\"tx_state\", 3) = 3"
                + counts);
    // an imported table's by an upsert: a key without a metadata row gets one, a DELETE's as a
    // tombstone, and a committed row is stamped at the next version
    assertThat(fast(table(), "DELETE FROM t WHERE id = ?").get(1))
        .isEqualTo(
            "WITH scalardb_native_w AS (DELETE FROM t WHERE (id = ?) RETURNING \"id\" AS"
                + " scalardb_native_w0), scalardb_native_s AS (INSERT INTO \"ns\".\"t_meta\" AS m"
                + " (\"id\", \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
                + " \"tx_committed_at\") SELECT w.scalardb_native_w0, ?, 3, 1, ?, ? FROM"
                + " scalardb_native_w w ON CONFLICT (\"id\") DO UPDATE SET \"tx_id\" ="
                + " EXCLUDED.\"tx_id\", \"tx_prepared_at\" = EXCLUDED.\"tx_prepared_at\","
                + " \"tx_committed_at\" = EXCLUDED.\"tx_committed_at\", \"tx_state\" = 3,"
                + " \"tx_version\" = CASE WHEN m.\"tx_id\" IS NULL THEN 1 ELSE"
                + " COALESCE(m.\"tx_version\", 0) + 1 END WHERE COALESCE(m.\"tx_state\", 3) = 3"
                + counts);
    // not at READ COMMITTED, where each statement has its own snapshot
    assertThat(plan(inline(), "UPDATE t SET x = ? WHERE id = ?"))
        .noneMatch(s -> s.startsWith("UPDATE t SET \"tx_id\""));
    // a batch's entries by the key: the same statement joined to the entries, which returns the
    // number of each entry it wrote; a gated one once per table and transaction
    Writes.Merge merged = fastMerge(inline(), "UPDATE t SET x = x || ? WHERE id = ?", false);
    assertThat(merged).isNotNull();
    assertThat(merged.sql(2))
        .isEqualTo(
            "WITH scalardb_native_e (scalardb_native_n, scalardb_native_p1, scalardb_native_p2)"
                + " AS (VALUES (1, ?, ?), (2, ?, ?)) UPDATE t SET "
                + stamp
                + ", x = x || scalardb_native_e.scalardb_native_p1 FROM scalardb_native_e WHERE"
                + " (id = scalardb_native_e.scalardb_native_p2) RETURNING"
                + " scalardb_native_e.scalardb_native_n");
    assertThat(merged.stamps).isTrue();
    Writes.Merge deletes = fastMerge(inline(), "DELETE FROM t WHERE id = ?", true);
    assertThat(deletes).isNotNull();
    assertThat(deletes.sql(1))
        .isEqualTo(
            "WITH scalardb_native_e (scalardb_native_n, scalardb_native_p1) AS (VALUES (1, ?))"
                + " DELETE FROM t USING scalardb_native_e WHERE (id ="
                + " scalardb_native_e.scalardb_native_p1)"
                + gate
                + " RETURNING scalardb_native_e.scalardb_native_n");
    assertThat(deletes.stamps).isFalse();
    Writes.Merge decoupled = fastMerge(created(), "UPDATE t SET x = ? WHERE id = ?", false);
    assertThat(decoupled).isNotNull();
    assertThat(decoupled.sql(2))
        .isEqualTo(
            "WITH scalardb_native_e (scalardb_native_n, scalardb_native_p1, scalardb_native_p2)"
                + " AS (VALUES (1, ?, ?), (2, ?, ?)), scalardb_native_w AS (UPDATE"
                + " \"ns\".\"t_data\" AS t SET x = scalardb_native_e.scalardb_native_p1 FROM"
                + " scalardb_native_e WHERE (id = scalardb_native_e.scalardb_native_p2) RETURNING"
                + " scalardb_native_e.scalardb_native_n AS scalardb_native_n, \"id\" AS"
                + " scalardb_native_w0), scalardb_native_s AS (UPDATE \"ns\".\"t_meta\" m SET "
                + stamp
                + " FROM scalardb_native_w w WHERE m.\"id\" = w.scalardb_native_w0 AND"
                + " COALESCE(m.\"tx_state\", 3) = 3 RETURNING 1) SELECT w.scalardb_native_n,"
                + " (SELECT count(*) FROM scalardb_native_s) FROM scalardb_native_w w");
    // beyond the key, no merged form at all
    assertThat(fastMerge(inline(), "UPDATE t SET x = ? WHERE x = ?", false)).isNull();
  }

  @Test
  void aWritesTransactionId_isARandomVersion4Uuid() {
    String a = Writes.freshTxId();
    String b = Writes.freshTxId();
    assertThat(a).isNotEqualTo(b);
    for (String id : List.of(a, b)) {
      java.util.UUID u = java.util.UUID.fromString(id);
      assertThat(u.version()).isEqualTo(4);
      assertThat(u.variant()).isEqualTo(2);
      assertThat(id).hasSize(36);
    }
  }

  private static Writes.@Nullable Merge fastMerge(TableInfo t, String sql, boolean gated)
      throws Exception {
    StatementShape shape =
        StatementShape.of(SqlAst.parse(sql, Engine.POSTGRESQL).orElseThrow()).orElseThrow();
    return Writes.plan(Engine.POSTGRESQL, shape, t, "", true, false, null).fastMerge(gated);
  }

  /** A context without a wait, its anchors empty: the forms of todo 336 apply. */
  private static Writes.Context noWait() {
    return fakeContext(new Anchors());
  }

  private static Writes.Plan rcPlan(TableInfo t, String sql) throws Exception {
    StatementShape shape =
        StatementShape.of(SqlAst.parse(sql, Engine.POSTGRESQL).orElseThrow()).orElseThrow();
    return Writes.plan(Engine.POSTGRESQL, shape, t, "", false, false, null);
  }

  @Test
  void atReadCommitted_aDecoupledKeyedWriteIsTheClearTableStatementWithTheCas() throws Exception {
    // the CAS in the stamp's WHERE: the metadata row as the stamp finds it, at the tx_id and
    // version the snapshot read into the keys CTE (design 4.3, todo 336)
    String cas =
        " AND COALESCE(m.\"tx_state\", 3) = 3 AND EXISTS (SELECT 1 FROM scalardb_native_keys c"
            + " WHERE c.scalardb_native_m0 = m.\"id\" AND c.scalardb_native_id IS NOT DISTINCT"
            + " FROM m.\"tx_id\" AND c.scalardb_native_version IS NOT DISTINCT FROM"
            + " m.\"tx_version\") RETURNING 1)";
    String keys =
        "WITH scalardb_native_keys (scalardb_native_m0, scalardb_native_id,"
            + " scalardb_native_version, scalardb_native_state) AS (SELECT t.\"id\", t.\"tx_id\","
            + " t.\"tx_version\", t.\"tx_state\" FROM \"ns\".\"t_meta\" t WHERE id = ?),"
            + " scalardb_native_w AS (";
    String row =
        " SELECT (SELECT count(*) FROM scalardb_native_w), (SELECT count(*) FROM"
            + " scalardb_native_s), k.scalardb_native_id, k.scalardb_native_version,"
            + " k.scalardb_native_state, (SELECT scalardb_native_w0 FROM scalardb_native_w) FROM"
            + " (SELECT 1) scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE";
    String upsert =
        "scalardb_native_s AS (INSERT INTO \"ns\".\"t_meta\" AS m (\"id\", \"tx_id\","
            + " \"tx_state\", \"tx_version\", \"tx_prepared_at\", \"tx_committed_at\") SELECT"
            + " w.scalardb_native_w0, ?, 3, 1, ?, ? FROM scalardb_native_w w ON CONFLICT (\"id\")"
            + " DO UPDATE SET \"tx_id\" = EXCLUDED.\"tx_id\", \"tx_prepared_at\" ="
            + " EXCLUDED.\"tx_prepared_at\", \"tx_committed_at\" = EXCLUDED.\"tx_committed_at\","
            + " \"tx_state\" = 3, \"tx_version\" = CASE WHEN m.\"tx_id\" IS NULL THEN 1 ELSE"
            + " COALESCE(m.\"tx_version\", 0) + 1 END WHERE TRUE";
    // an imported table: the DML as written on the table itself, the upsert stamp
    Writes.KeyedPlan imported =
        (Writes.KeyedPlan) rcPlan(table(), "UPDATE t SET x = x || ? WHERE id = ?");
    assertThat(Objects.requireNonNull(imported.clearRc).sql)
        .isEqualTo(
            keys
                + "UPDATE t SET x = x || ? WHERE (id = ?) AND NOT EXISTS (SELECT 1 FROM"
                + " scalardb_native_keys WHERE COALESCE(scalardb_native_state, 3) <> 3) RETURNING"
                + " \"id\" AS scalardb_native_w0), "
                + upsert.replace(" WHERE TRUE", "")
                + " WHERE TRUE".replace(" WHERE TRUE", "")
                + cas.replace(" AND COALESCE", " WHERE COALESCE")
                + row);
    assertThat(imported.clearRc.binds).containsExactly(2, 1, 2);
    // a created table: the DML on the data table, the UPDATE ... FROM stamp; a DELETE removes
    Writes.KeyedPlan created =
        (Writes.KeyedPlan) rcPlan(created(), "UPDATE t SET x = x || ? WHERE id = ?");
    assertThat(Objects.requireNonNull(created.clearRc).sql)
        .startsWith(
            keys
                + "UPDATE \"ns\".\"t_data\" AS t SET x = x || ? WHERE (id = ?) AND NOT EXISTS"
                + " (SELECT 1 FROM scalardb_native_keys WHERE COALESCE(scalardb_native_state, 3)"
                + " <> 3)")
        .contains(
            "scalardb_native_s AS (UPDATE \"ns\".\"t_meta\" m SET \"tx_id\" = ?,"
                + " \"tx_prepared_at\" = ?, \"tx_committed_at\" = ?, \"tx_state\" = 3,")
        .contains("FROM scalardb_native_w w WHERE m.\"id\" = w.scalardb_native_w0" + cas)
        .endsWith(row);
    Writes.KeyedPlan deleted = (Writes.KeyedPlan) rcPlan(created(), "DELETE FROM t WHERE id = ?");
    assertThat(Objects.requireNonNull(deleted.clearRc).sql)
        .contains(
            "scalardb_native_s AS (DELETE FROM \"ns\".\"t_meta\" m USING scalardb_native_w w"
                + " WHERE m.\"id\" = w.scalardb_native_w0"
                + cas)
        .doesNotContain("scalardb_native_gate");
    // the slim statement stays for a configured wait, and the inline layout has none of this
    assertThat(imported.slim).isNotNull();
    assertThat(((Writes.KeyedPlan) rcPlan(inline(), "UPDATE t SET x = ? WHERE id = ?")).clearRc)
        .isNull();
    // nor at SNAPSHOT
    StatementShape shape =
        StatementShape.of(
                SqlAst.parse("UPDATE t SET x = ? WHERE id = ?", Engine.POSTGRESQL).orElseThrow())
            .orElseThrow();
    assertThat(
            ((Writes.KeyedPlan)
                    Writes.plan(Engine.POSTGRESQL, shape, table(), "", true, false, null))
                .clearRc)
        .isNull();
  }

  @Test
  void atReadCommitted_aDecoupledBatchsEntriesMergeIntoTheClearTableStatement() throws Exception {
    Writes.Plan plan = rcPlan(table(), "UPDATE t SET x = x || ? WHERE id = ?");
    Writes.Merge m = Objects.requireNonNull(plan.fastMerge(noWait()));
    assertThat(m.sql(2))
        .startsWith(
            "WITH scalardb_native_e (scalardb_native_n, scalardb_native_p1, scalardb_native_p2)"
                + " AS (VALUES (1, ?, ?), (2, ?, ?)), scalardb_native_keys (scalardb_native_n,"
                + " scalardb_native_m0, scalardb_native_id, scalardb_native_version,"
                + " scalardb_native_state) AS (SELECT scalardb_native_e.scalardb_native_n,"
                + " x.\"id\", x.\"tx_id\", x.\"tx_version\", x.\"tx_state\" FROM"
                + " scalardb_native_e CROSS JOIN LATERAL (SELECT * FROM \"ns\".\"t_meta\" t"
                + " WHERE id = scalardb_native_e.scalardb_native_p2 OFFSET 0) x),"
                + " scalardb_native_w AS (UPDATE t SET x = x || scalardb_native_e.scalardb_native_p1"
                + " FROM scalardb_native_e WHERE (id = scalardb_native_e.scalardb_native_p2) AND NOT"
                + " EXISTS (SELECT 1 FROM scalardb_native_keys g WHERE g.scalardb_native_n ="
                + " scalardb_native_e.scalardb_native_n AND COALESCE(g.scalardb_native_state, 3) <>"
                + " 3) RETURNING scalardb_native_e.scalardb_native_n AS scalardb_native_n, \"id\" AS"
                + " scalardb_native_w0)")
        .contains("AND EXISTS (SELECT 1 FROM scalardb_native_keys c WHERE c.scalardb_native_m0 =")
        .endsWith(
            " SELECT w.scalardb_native_n, (SELECT count(*) FROM scalardb_native_s),"
                + " k.scalardb_native_id, k.scalardb_native_version, w.scalardb_native_w0 FROM"
                + " scalardb_native_w w LEFT JOIN scalardb_native_keys k ON k.scalardb_native_n ="
                + " w.scalardb_native_n");
    assertThat(m.stamps).isTrue();
    // a created table's DELETE binds no stamp
    Writes.Merge deletes =
        Objects.requireNonNull(rcPlan(created(), "DELETE FROM t WHERE id = ?").fastMerge(noWait()));
    assertThat(deletes.stamps).isFalse();
    assertThat(deletes.sql(1))
        .contains("DELETE FROM \"ns\".\"t_data\" AS t USING scalardb_native_e WHERE")
        .contains("scalardb_native_s AS (DELETE FROM \"ns\".\"t_meta\" m USING");
  }

  @Test
  void atReadCommitted_aDecoupledWriteBeyondTheKeyIsTheClearTableStatement() throws Exception {
    Writes.KeyedPlan plan =
        (Writes.KeyedPlan) rcPlan(created(), "UPDATE t SET x = ? WHERE id > ? -- note");
    assertThat(plan.clearRc).isNull();
    assertThat(Objects.requireNonNull(plan.predicateRc).sql)
        .isEqualTo(
            "WITH scalardb_native_before (scalardb_native_b) AS (SELECT EXISTS (SELECT 1 FROM"
                + " (SELECT m.\"id\", m.\"before_x\" AS \"x\" FROM \"ns\".\"t_meta\" m"
                + " WHERE m.\"tx_state\" IN (1, 2) AND (m.\"before_tx_id\" IS NOT NULL OR"
                + " m.\"before_tx_version\" IS NOT NULL)) t WHERE id > ?)), scalardb_native_w AS"
                + " (UPDATE \"ns\".\"t_data\" AS t SET x = ? WHERE (id > ?) RETURNING \"id\" AS"
                + " scalardb_native_w0), scalardb_native_s AS (UPDATE \"ns\".\"t_meta\" m SET"
                + " \"tx_id\" = ?, \"tx_prepared_at\" = ?, \"tx_committed_at\" = ?,"
                + " \"tx_state\" = 3, \"tx_version\" = CASE WHEN \"tx_id\" IS NULL THEN 1 ELSE"
                + " COALESCE(\"tx_version\", 0) + 1 END FROM scalardb_native_w w WHERE m.\"id\" ="
                + " w.scalardb_native_w0 AND COALESCE(m.\"tx_state\", 3) = 3 AND EXISTS (SELECT 1"
                + " FROM \"ns\".\"t_meta\" s WHERE s.\"id\" = m.\"id\" AND s.\"tx_id\" IS NOT"
                + " DISTINCT FROM m.\"tx_id\" AND s.\"tx_version\" IS NOT DISTINCT FROM"
                + " m.\"tx_version\") RETURNING 1) SELECT (SELECT count(*) FROM"
                + " scalardb_native_w), (SELECT count(*) FROM scalardb_native_s), (SELECT"
                + " scalardb_native_b FROM scalardb_native_before)");
    // the predicate's placeholder for the before images, then the statement's
    assertThat(plan.predicateRc.binds).containsExactly(2, 1, 2);
    // the fused statement stays, for a transaction that recorded anchors of the table
    assertThat(plan.fused).isNotNull();
    // a predicate on another column keeps the fused statement and its zero-row rule: a row its
    // snapshot matched can stop matching through that column before the DML writes it (finding
    // 134); and so does an impure one
    assertThat(((Writes.KeyedPlan) rcPlan(created(), "UPDATE t SET x = ? WHERE x = ?")).predicateRc)
        .isNull();
    assertThat(
            ((Writes.KeyedPlan) rcPlan(created(), "UPDATE t SET x = ? WHERE id > random()"))
                .predicateRc)
        .isNull();
  }

  @Test
  void update_readsKeysFromTheCommittedImages_andAddsTheKeyToItsPredicate() throws Exception {
    List<String> sql = plan("UPDATE t SET x = ? WHERE x = ? OR id > 3 -- note");
    String view = Engine.POSTGRESQL.quote(Engine.POSTGRESQL.readViewName("t"));
    assertThat(sql.get(0))
        .isEqualTo(
            "SELECT d.\"id\", m.\"id\", m.\"tx_id\", m.\"tx_version\", m.\"tx_state\" FROM (SELECT"
                + " t.\"id\" AS \"id\" FROM \"ns\"."
                + view
                + " t WHERE x = ? OR id > 3) d LEFT JOIN \"ns\".\"t_meta\" m ON m.\"id\" = d.\"id\"");
    // the predicate kept whole, the key after it and before the comment
    assertThat(sql.get(1))
        .isEqualTo("UPDATE t SET x = ? WHERE (x = ? OR id > 3) AND (\"id\" = ?) -- note");
  }

  private static Writes.@Nullable Merge merge(TableInfo t, String sql) throws Exception {
    StatementShape shape =
        StatementShape.of(SqlAst.parse(sql, Engine.POSTGRESQL).orElseThrow()).orElseThrow();
    return Writes.plan(Engine.POSTGRESQL, shape, t, "", false, false, null).merge();
  }

  @Test
  void aWriteByItsKey_mergesABatchsEntries_intoOneStatement() throws Exception {
    Writes.Merge update = merge(table(), "UPDATE t SET x = x || ? WHERE id = ?");
    assertThat(update).isNotNull();
    String sql = update.sql(2);
    // the entries, numbered, a column per placeholder; the stamp's three after them
    assertThat(sql)
        .startsWith(
            "WITH scalardb_native_e (scalardb_native_n, scalardb_native_p1, scalardb_native_p2)"
                + " AS (VALUES (1, ?, ?), (2, ?, ?)), scalardb_native_keys")
        .containsOnlyOnce("\"tx_id\" = ?, \"tx_prepared_at\" = ?, \"tx_committed_at\" = ?");
    assertThat(sql.chars().filter(c -> c == '?').count()).isEqualTo(7);
    // each entry's key read on its own, the DML joined to the entries
    assertThat(sql)
        .contains(
            "FROM scalardb_native_e CROSS JOIN LATERAL (SELECT t.\"id\" AS \"id\" FROM"
                + " \"ns\".\"rc_t\" t WHERE id = scalardb_native_e.scalardb_native_p2 OFFSET 0) d")
        .contains(
            "UPDATE t SET x = x || scalardb_native_e.scalardb_native_p1 FROM scalardb_native_e"
                + " WHERE (id = scalardb_native_e.scalardb_native_p2) AND")
        // two entries on one key close the gate: one by one, the second sees the first's write
        .contains("GROUP BY scalardb_native_d0 HAVING count(*) > 1")
        .endsWith(
            "FROM scalardb_native_e LEFT JOIN scalardb_native_keys k ON k.scalardb_native_n ="
                + " scalardb_native_e.scalardb_native_n ORDER BY 1");
    assertThat(merge(created(), "DELETE FROM t WHERE ? = id").sql(1))
        .contains("DELETE FROM \"ns\".\"t_data\" AS t USING scalardb_native_e WHERE")
        .contains("scalardb_native_removed AS (DELETE FROM");
    // an INSERT of one row: the row once per entry, in the fused INSERT
    Writes.Merge insert = merge(table(), "INSERT INTO t (x, id) VALUES (lower(?), ?)");
    assertThat(insert).isNotNull();
    assertThat(insert.sql(3))
        .startsWith(
            "WITH scalardb_native_inserted AS (INSERT INTO t (x, id) VALUES (lower(?), ?),"
                + " (lower(?), ?), (lower(?), ?) RETURNING \"id\" AS scalardb_native_k0),"
                + " scalardb_native_stamped AS (INSERT INTO \"ns\".\"t_meta\"");
    assertThat(merge(table(), "INSERT INTO t (x, id) VALUES (?, ?), (?, ?)")).isNull();
    assertThat(merge(table(), "INSERT INTO t (x, id) VALUES ('a', 1)")).isNull();
    assertThat(merge(table(), "INSERT INTO t (x, id) VALUES (?, ?) RETURNING x")).isNull();
    // the key as the engine resolves its name, unquoted upper case among them
    assertThat(merge(table(), "UPDATE t SET x = ? WHERE ID = ?")).isNotNull();
    assertThat(merge(table(), "UPDATE t SET x = ? WHERE \"ID\" = ?")).isNull();
    // more than the key, or not all of it: no merged form
    assertThat(merge(table(), "UPDATE t SET x = ? WHERE id = ? AND x = ?")).isNull();
    assertThat(merge(table(), "UPDATE t SET x = ? WHERE id > ?")).isNull();
    assertThat(merge(table(), "UPDATE t SET x = ? WHERE t.id = ?")).isNull();
    assertThat(merge(table(), "UPDATE t SET x = ? WHERE x = ?")).isNull();
    assertThat(merge(table(), "UPDATE t SET x = 'a' WHERE id = 1")).isNull();
  }

  @Test
  void aWriteByItsKey_isTheSlimStatementFirst() throws Exception {
    List<String> update = plan("UPDATE t SET x = ? WHERE id = ?");
    // the key's metadata row by the key, the DML gated on it being committed, the CAS on it
    assertThat(update.get(update.size() - 1))
        .isEqualTo(
            "WITH scalardb_native_keys (scalardb_native_m0, scalardb_native_id,"
                + " scalardb_native_version, scalardb_native_state) AS (SELECT t.\"id\","
                + " t.\"tx_id\", t.\"tx_version\", t.\"tx_state\" FROM \"ns\".\"t_meta\" t"
                + " WHERE id = ?), scalardb_native_gate (scalardb_native_go) AS (SELECT EXISTS"
                + " (SELECT 1 FROM scalardb_native_keys WHERE COALESCE(scalardb_native_state, 3) ="
                + " 3)), scalardb_native_written AS (UPDATE t SET x = ? WHERE (id = ?) AND (SELECT"
                + " scalardb_native_go FROM scalardb_native_gate) RETURNING \"id\" AS"
                + " scalardb_native_w0), scalardb_native_stamped AS (UPDATE \"ns\".\"t_meta\" SET"
                + " \"tx_id\" = ?, \"tx_prepared_at\" = ?, \"tx_committed_at\" = ?, \"tx_state\" ="
                + " 3, \"tx_version\" = CASE WHEN k.scalardb_native_id IS NULL THEN 1 ELSE"
                + " COALESCE(\"tx_version\", 0) + 1 END FROM scalardb_native_keys k WHERE \"id\" ="
                + " k.scalardb_native_m0 AND (k.scalardb_native_m0) IN (SELECT scalardb_native_w0"
                + " FROM scalardb_native_written) AND COALESCE(\"tx_state\", 3) = 3 AND \"tx_id\""
                + " IS NOT DISTINCT FROM k.scalardb_native_id AND (k.scalardb_native_id IS NULL OR"
                + " \"tx_version\" IS NOT DISTINCT FROM k.scalardb_native_version) RETURNING 1)"
                + " SELECT k.scalardb_native_m0, k.scalardb_native_id, k.scalardb_native_version,"
                + " k.scalardb_native_state, (SELECT count(*) FROM scalardb_native_written), (SELECT"
                + " count(*) FROM scalardb_native_stamped), (SELECT scalardb_native_w0 FROM"
                + " scalardb_native_written) FROM (SELECT 1) scalardb_native_one LEFT JOIN"
                + " scalardb_native_keys k ON TRUE");
    // a created table's DELETE removes the metadata row, as the fused statement does
    List<String> delete = plan(created(), "DELETE FROM t WHERE id = ?");
    assertThat(delete.get(delete.size() - 1))
        .contains("scalardb_native_removed AS (DELETE FROM \"ns\".\"t_meta\" USING")
        .contains("+ (SELECT count(*) FROM scalardb_native_removed)");
    // a predicate beyond the key: the fused statement alone
    assertThat(plan("UPDATE t SET x = ? WHERE id = ? AND x = ?"))
        .noneMatch(q -> q.contains("scalardb_native_gate (scalardb_native_go) AS (SELECT EXISTS"));
  }

  private static TableInfo inline() throws Exception {
    Constructor<TableInfo> c =
        TableInfo.class.getDeclaredConstructor(
            String.class,
            String.class,
            Layout.class,
            String.class,
            String.class,
            String.class,
            List.class,
            List.class,
            List.class,
            List.class,
            boolean.class);
    c.setAccessible(true);
    return c.newInstance(
        "ns",
        "t",
        Layout.INLINE,
        "t",
        "t",
        null,
        List.of("id"),
        List.of(),
        List.of("id", "x"),
        List.of(),
        true);
  }

  @Test
  void anInlineTablesWrite_stampsItsOwnRow_inTheSameStatement() throws Exception {
    String stamp =
        "\"tx_id\" = ?, \"tx_prepared_at\" = ?, \"tx_committed_at\" = ?, \"tx_state\" = 3,"
            + " \"tx_version\" = CASE WHEN \"tx_id\" IS NULL THEN 1 ELSE"
            + " COALESCE(\"tx_version\", 0) + 1 END";
    // by the key: the user's SET with the stamp, the CAS in its WHERE, no metadata table
    List<String> slim = plan(inline(), "UPDATE t SET x = x || ? WHERE id = ?");
    assertThat(slim.get(slim.size() - 1))
        .startsWith(
            "WITH scalardb_native_keys (scalardb_native_m0, scalardb_native_id,"
                + " scalardb_native_version, scalardb_native_state) AS (SELECT t.\"id\","
                + " t.\"tx_id\", t.\"tx_version\", t.\"tx_state\" FROM \"ns\".\"t\" t WHERE id ="
                + " ?), scalardb_native_written AS (UPDATE t SET x = x || ?, "
                + stamp
                + " WHERE (\"id\") IN (SELECT scalardb_native_m0 FROM scalardb_native_keys) AND"
                + " COALESCE(\"tx_state\", 3) = 3 AND \"tx_id\" IS NOT DISTINCT FROM (SELECT"
                + " scalardb_native_id FROM scalardb_native_keys) AND \"tx_version\" IS NOT"
                + " DISTINCT FROM (SELECT scalardb_native_version FROM scalardb_native_keys)")
        .doesNotContain("scalardb_native_stamped");
    // a DELETE by the key takes the row and its metadata at once; the stamp's binds go unused
    List<String> delete = plan(inline(), "DELETE FROM t WHERE id = ?");
    assertThat(delete.get(delete.size() - 1))
        .contains("scalardb_native_written AS (DELETE FROM t WHERE (\"id\") IN")
        .endsWith(
            "FROM (SELECT CAST(? AS text), CAST(? AS bigint), CAST(? AS bigint))"
                + " scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE");
    // a batch by the key: each entry's key looked up by itself, the stamp after the entries
    assertThat(merge(inline(), "UPDATE t SET x = x || ? WHERE id = ?").sql(2))
        .startsWith(
            "WITH scalardb_native_e (scalardb_native_n, scalardb_native_p1, scalardb_native_p2)"
                + " AS (VALUES (1, ?, ?), (2, ?, ?))")
        .contains("CROSS JOIN LATERAL (SELECT t.\"id\"")
        .contains("GROUP BY scalardb_native_d0 HAVING count(*) > 1")
        .contains("UPDATE t SET x = x || scalardb_native_e.scalardb_native_p1, " + stamp + " FROM")
        .contains(
            "AND \"tx_id\" IS NOT DISTINCT FROM k.scalardb_native_id AND \"tx_version\" IS NOT"
                + " DISTINCT FROM k.scalardb_native_version RETURNING");
    // an INSERT carries its metadata columns; a batch of them reads one stamp bound first
    List<String> insert = plan(inline(), "INSERT INTO t (id, x) VALUES (?, ?)");
    assertThat(insert.get(insert.size() - 1))
        .contains(
            "INSERT INTO t (id, x, \"tx_id\", \"tx_state\", \"tx_version\","
                + " \"tx_prepared_at\", \"tx_committed_at\") VALUES (?, ?, ?, 3, 1, ?, ?)");
    assertThat(merge(inline(), "INSERT INTO t (id, x) VALUES (?, ?)").sql(2))
        .startsWith(
            "WITH scalardb_native_s (scalardb_native_i, scalardb_native_p, scalardb_native_c) AS"
                + " (SELECT CAST(? AS text), CAST(? AS bigint), CAST(? AS bigint)),"
                + " scalardb_native_inserted AS (INSERT INTO t (id, x, \"tx_id\", \"tx_state\","
                + " \"tx_version\", \"tx_prepared_at\", \"tx_committed_at\") VALUES (?, ?,"
                + " (SELECT scalardb_native_i FROM scalardb_native_s), 3, 1,");
    // beyond the key: one statement still, its stamp first and its CAS folded into the version
    List<String> fused = plan(inline(), "UPDATE t SET x = ? WHERE x = ?");
    assertThat(fused.get(fused.size() - 1))
        .startsWith(
            "WITH scalardb_native_s (scalardb_native_i, scalardb_native_p, scalardb_native_c) AS"
                + " (SELECT CAST(? AS text), CAST(? AS bigint), CAST(? AS bigint)),")
        .contains(
            "\"tx_version\" = CASE WHEN \"tx_id\" IS NOT DISTINCT FROM k.scalardb_native_id"
                + " AND \"tx_version\" IS NOT DISTINCT FROM k.scalardb_native_version THEN")
        .contains(
            "FROM scalardb_native_s s, scalardb_native_keys k WHERE (x = ?) AND (\"id\") ="
                + " (k.scalardb_native_d0) AND NOT EXISTS (SELECT 1 FROM scalardb_native_keys WHERE"
                + " COALESCE(scalardb_native_state, 3) IN (1, 2)) AND COALESCE(\"tx_state\", 3) = 3")
        .contains(
            "RETURNING \"id\" AS scalardb_native_w0, \"tx_version\" <> -1 AS scalardb_native_ok)"
                + " SELECT k.*, w.scalardb_native_w0 IS NOT NULL, COALESCE(w.scalardb_native_ok,"
                + " false), NOT EXISTS")
        // the keys from the table itself: the committed rows, and the rows in flight whose before
        // image matches, which close the gate
        .contains(
            "scalardb_native_keys (scalardb_native_d0, scalardb_native_m0, scalardb_native_id,"
                + " scalardb_native_version, scalardb_native_state) AS (SELECT t.\"id\", t.\"id\","
                + " t.\"tx_id\", t.\"tx_version\", t.\"tx_state\" FROM \"ns\".\"t\" t WHERE (x = ?)"
                + " AND COALESCE(t.\"tx_state\", 3) = 3 UNION ALL SELECT t.\"id\","
                + " t.\"id\", NULL, NULL, 1 FROM (SELECT m.\"id\", m.\"before_x\" AS \"x\" FROM"
                + " \"ns\".\"t\" m WHERE m.\"tx_state\" IN (1, 2) AND (m.\"before_tx_id\" IS NOT"
                + " NULL OR m.\"before_tx_version\" IS NOT NULL)) t WHERE (x = ?))");
    List<String> fusedDelete = plan(inline(), "DELETE FROM t WHERE x = ?");
    assertThat(fusedDelete.get(fusedDelete.size() - 1))
        .contains("DELETE FROM t USING scalardb_native_keys k WHERE (x = ?)")
        .contains("RETURNING \"id\" AS scalardb_native_w0, true AS scalardb_native_ok");
  }

  @Test
  void delete_withoutAPredicate_getsTheKeyBeforeATrailingComment() throws Exception {
    assertThat(plan("DELETE FROM t AS a -- all").get(1))
        .isEqualTo("DELETE FROM t AS a WHERE \"id\" = ? -- all");
  }

  @Test
  void insert_returnsItsKeys_andTellsADuplicateFromAKeyInFlight() throws Exception {
    List<String> sql = plan("INSERT INTO t (x, id) VALUES (?, ?), ('b', 7)");
    assertThat(sql.get(0))
        .isEqualTo("INSERT INTO t (x, id) VALUES (?, ?), ('b', 7) RETURNING \"id\"");
    assertThat(sql.get(1))
        .isEqualTo(
            "SELECT COUNT(*) FROM \"ns\".\"t_meta\" WHERE ((\"id\" = ?) OR (\"id\" = 7)) AND"
                + " \"tx_state\" IN (1, 2)");
    // a key the database generates: no duplicate to classify
    assertThat(plan("INSERT INTO t (x) VALUES ('a')"))
        .containsExactly(
            "INSERT INTO t (x) VALUES ('a') RETURNING \"id\"",
            "WITH scalardb_native_inserted AS (INSERT INTO t (x) VALUES ('a') RETURNING \"id\" AS"
                + " scalardb_native_k0), scalardb_native_stamped AS (INSERT INTO \"ns\".\"t_meta\""
                + " (\"id\", \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
                + " \"tx_committed_at\") SELECT scalardb_native_k0, ?, 3, 1, ?, ? FROM"
                + " scalardb_native_inserted ON CONFLICT DO NOTHING RETURNING 1) SELECT"
                + " scalardb_native_inserted.*, (SELECT COUNT(*) FROM scalardb_native_stamped) FROM"
                + " scalardb_native_inserted");
    // the statement's own items first, before a trailing comment
    assertThat(plan("INSERT INTO t (id, x) VALUES (1, 'a') RETURNING x AS y -- c").get(0))
        .isEqualTo("INSERT INTO t (id, x) VALUES (1, 'a') RETURNING x AS y, \"id\" -- c");
  }

  @Test
  void onPostgreSql_aWriteIsOneStatement() throws Exception {
    // the DML over the keys read, gated on their checks, then the CAS of each key it wrote
    List<String> update = plan("UPDATE t SET x = ? WHERE x = ? -- note");
    assertThat(update.get(update.size() - 1))
        .isEqualTo(
            "WITH scalardb_native_keys (scalardb_native_d0, scalardb_native_m0, "
                + "scalardb_native_id, scalardb_native_version, scalardb_native_state) AS (SELECT "
                + "d.\"id\", m.\"id\", m.\"tx_id\", m.\"tx_version\", m.\"tx_state\" FROM (SELECT t.\"id\" AS "
                + "\"id\" FROM \"ns\".\"rc_t\" t WHERE x = ?) d LEFT JOIN \"ns\".\"t_meta\" m ON m.\"id\" = "
                + "d.\"id\"), scalardb_native_gate (scalardb_native_go) AS (SELECT NOT EXISTS "
                + "(SELECT 1 FROM scalardb_native_keys WHERE scalardb_native_state IN (1, 2) OR "
                + "scalardb_native_m0 IS NULL)), scalardb_native_written AS (UPDATE t SET x = ? "
                + "WHERE (x = ?) AND ((\"id\") IN (SELECT scalardb_native_d0 FROM "
                + "scalardb_native_keys) AND (SELECT scalardb_native_go FROM "
                + "scalardb_native_gate)) RETURNING \"id\" AS scalardb_native_w0), "
                + "scalardb_native_stamped AS (UPDATE \"ns\".\"t_meta\" SET \"tx_id\" = ?, "
                + "\"tx_prepared_at\" = ?, \"tx_committed_at\" = ?, \"tx_state\" = 3, \"tx_version\" = "
                + "CASE WHEN k.scalardb_native_id IS NULL THEN 1 ELSE COALESCE(\"tx_version\", 0) + "
                + "1 END FROM scalardb_native_keys k WHERE \"id\" = k.scalardb_native_m0 AND "
                + "(k.scalardb_native_d0) IN (SELECT scalardb_native_w0 FROM "
                + "scalardb_native_written) AND COALESCE(\"tx_state\", 3) = 3 AND \"tx_id\" IS NOT "
                + "DISTINCT FROM k.scalardb_native_id AND (k.scalardb_native_id IS NULL OR "
                + "\"tx_version\" IS NOT DISTINCT FROM k.scalardb_native_version) RETURNING "
                + "k.scalardb_native_d0) SELECT k.*, EXISTS (SELECT 1 FROM "
                + "scalardb_native_written w WHERE w.scalardb_native_w0 = k.scalardb_native_d0), "
                + "EXISTS (SELECT 1 FROM scalardb_native_stamped c WHERE c.scalardb_native_d0 = "
                + "k.scalardb_native_d0), (SELECT scalardb_native_go FROM scalardb_native_gate) "
                + "FROM (SELECT 1) scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE");
    // a key given: the INSERT under a savepoint set and released in its own round trip
    List<String> insert = plan("INSERT INTO t (x, id) VALUES (?, ?) -- c");
    assertThat(insert.get(insert.size() - 1))
        .startsWith(
            "SAVEPOINT scalardb_native_write; WITH scalardb_native_inserted AS (INSERT INTO"
                + " t (x, id) VALUES (?, ?) RETURNING \"id\" AS scalardb_native_k0), ")
        .endsWith("FROM scalardb_native_inserted; RELEASE SAVEPOINT scalardb_native_write");
  }

  private static List<String> oracle(TableInfo t, String sql, boolean snapshot) throws Exception {
    StatementShape shape =
        StatementShape.of(SqlAst.parse(sql, Engine.ORACLE).orElseThrow()).orElseThrow();
    return Writes.plan(Engine.ORACLE, shape, t, "", snapshot, false, null).sql();
  }

  @Test
  void onOracle_aKeyedWriteIsOneBlock() throws Exception {
    String stamp =
        "\"tx_id\" = ?, \"tx_prepared_at\" = ?, \"tx_committed_at\" = ?, \"tx_state\" = 3,"
            + " \"tx_version\" = CASE WHEN \"tx_id\" IS NULL THEN 1 ELSE"
            + " COALESCE(\"tx_version\", 0) + 1 END";
    // the key is quoted in the texts: unquoted it would fold to ID, not the fixture's id
    // the inline layout: the row read with its version and state, then the user's statement with
    // the stamp in its SET list and the CAS in its WHERE clause, steps 2 and 3 in one row; the row
    // back through a cursor over dual; a trailing comment left out, which would run on in a block
    String inlineHead =
        "DECLARE l_rows NUMBER := 0; l_written NUMBER := 0; l_id \"ns\".\"t\".\"tx_id\"%TYPE;"
            + " l_version \"ns\".\"t\".\"tx_version\"%TYPE; l_state \"ns\".\"t\".\"tx_state\"%TYPE;"
            + " l_k0 \"ns\".\"t\".\"id\"%TYPE; BEGIN BEGIN SELECT 1, t.\"tx_id\", t.\"tx_version\","
            + " t.\"tx_state\", t.\"id\" INTO l_rows, l_id, l_version, l_state, l_k0 FROM"
            + " \"ns\".\"t\" t WHERE \"id\" = ?; EXCEPTION WHEN NO_DATA_FOUND THEN NULL; END;"
            + " IF l_rows = 1 AND COALESCE(l_state, 3) = 3 THEN ";
    String inlineCas =
        " AND COALESCE(t.\"tx_state\", 3) = 3 AND DECODE(t.\"tx_id\", l_id, 1, 0) = 1 AND"
            + " COALESCE(t.\"tx_version\", 0) = COALESCE(l_version, 0); l_written := SQL%ROWCOUNT;"
            + " END IF; OPEN ? FOR SELECT l_rows, 1, l_id, l_version, l_state, l_written,"
            + " l_written, l_k0 FROM dual; END;";
    List<String> update =
        oracle(inline(), "UPDATE t SET x = x || ? WHERE \"id\" = ? -- note", false);
    assertThat(update.get(update.size() - 1))
        .isEqualTo(
            inlineHead + "UPDATE t SET " + stamp + ", x = x || ? WHERE (\"id\" = ?)" + inlineCas);
    List<String> delete = oracle(inline(), "DELETE FROM t WHERE \"id\" = ?", false);
    assertThat(delete.get(delete.size() - 1))
        .isEqualTo(inlineHead + "DELETE FROM t WHERE (\"id\" = ?)" + inlineCas);
    // a decoupled table: the data row with its metadata row, the metadata row's own key read
    // beside the data row's for the CAS; the user's DML on the data table, then the CAS on the
    // metadata row where it wrote one; a created table's DELETE removes the metadata row, an
    // imported table's stamps it as a tombstone
    String decoupledHead =
        "DECLARE l_rows NUMBER := 0; l_written NUMBER := 0; l_meta NUMBER := 0; l_stamped NUMBER"
            + " := 0; l_id \"ns\".\"t_meta\".\"tx_id\"%TYPE; l_version"
            + " \"ns\".\"t_meta\".\"tx_version\"%TYPE; l_state \"ns\".\"t_meta\".\"tx_state\"%TYPE;"
            + " l_k0 \"ns\".\"%DATA%\".\"id\"%TYPE; l_m0 \"ns\".\"t_meta\".\"id\"%TYPE; BEGIN BEGIN"
            + " SELECT 1, CASE WHEN m.\"id\" IS NULL THEN 0 ELSE 1 END, m.\"tx_id\","
            + " m.\"tx_version\", m.\"tx_state\", d.\"id\", m.\"id\" INTO l_rows, l_meta, l_id,"
            + " l_version, l_state, l_k0, l_m0 FROM (SELECT t.\"id\" AS \"id\" FROM"
            + " \"ns\".\"%DATA%\" t WHERE \"id\" = ?) d LEFT JOIN \"ns\".\"t_meta\" m ON m.\"id\" ="
            + " d.\"id\"; EXCEPTION WHEN NO_DATA_FOUND THEN NULL; END; IF l_rows = 1 AND l_meta = 1"
            + " AND COALESCE(l_state, 3) = 3 THEN ";
    String metaCas =
        " WHERE \"id\" = l_m0 AND COALESCE(\"tx_state\", 3) = 3 AND DECODE(\"tx_id\", l_id, 1, 0)"
            + " = 1 AND COALESCE(\"tx_version\", 0) = COALESCE(l_version, 0); l_stamped :="
            + " SQL%ROWCOUNT; END IF; END IF; OPEN ? FOR SELECT l_rows, l_meta, l_id, l_version,"
            + " l_state, l_written, l_stamped, l_k0 FROM dual; END;";
    List<String> created = oracle(created(), "UPDATE t SET x = ? WHERE \"id\" = ?", false);
    assertThat(created.get(created.size() - 1))
        .isEqualTo(
            decoupledHead.replace("%DATA%", "t_data")
                + "UPDATE \"ns\".\"t_data\" t SET x = ? WHERE (\"id\" = ?); l_written := SQL%ROWCOUNT;"
                + " IF l_written = 1 THEN UPDATE \"ns\".\"t_meta\" SET "
                + stamp
                + metaCas);
    List<String> createdDelete = oracle(created(), "DELETE FROM t WHERE \"id\" = ?", false);
    assertThat(createdDelete.get(createdDelete.size() - 1))
        .isEqualTo(
            decoupledHead.replace("%DATA%", "t_data")
                + "DELETE FROM \"ns\".\"t_data\" t WHERE (\"id\" = ?); l_written := SQL%ROWCOUNT;"
                + " IF l_written = 1 THEN DELETE FROM \"ns\".\"t_meta\""
                + metaCas);
    List<String> imported = oracle(table(), "DELETE FROM t WHERE \"id\" = ?", false);
    assertThat(imported.get(imported.size() - 1))
        .isEqualTo(
            decoupledHead.replace("%DATA%", "t")
                + "DELETE FROM t WHERE (\"id\" = ?); l_written := SQL%ROWCOUNT;"
                + " IF l_written = 1 THEN UPDATE \"ns\".\"t_meta\" SET "
                + stamp
                + metaCas);
    // at SNAPSHOT the inline layout has the vendor's statement with the stamp first, gated on the
    // in-flight index under its hint, then the same for a table found clear, then the block; the
    // decoupled layout has no PostgreSQL text, its clear-table block a later form
    String index = Engine.ORACLE.quote(Engine.ORACLE.inflightIndexName("t"));
    List<String> fast = oracle(inline(), "UPDATE t SET x = ? WHERE \"id\" = ?", true);
    assertThat(fast.subList(fast.size() - 3, fast.size() - 1))
        .containsExactly(
            "UPDATE t SET "
                + stamp
                + ", x = ? WHERE (\"id\" = ?) AND NOT EXISTS (SELECT /*+ INDEX(m "
                + index
                + ") */ 1 FROM \"ns\".\"t\" m WHERE m.\"tx_state\" IN (1, 2))",
            "UPDATE t SET " + stamp + ", x = ? WHERE (\"id\" = ?)");
    assertThat(fast.get(fast.size() - 1)).startsWith("DECLARE");
    assertThat(oracle(created(), "UPDATE t SET x = ? WHERE \"id\" = ?", true))
        .noneMatch(sql -> sql.startsWith("WITH"))
        .last()
        .asString()
        .startsWith("DECLARE");
  }

  @Test
  void onOracle_aWriteBeyondTheKeyIsOneBlock() throws Exception {
    // the decoupled layout: the keys read from the read view with their metadata rows, the gate,
    // the DML once per key by FORALL, the zero-row rule, the CAS once per key written; counts back
    List<String> update = oracle(created(), "UPDATE t SET x = ? WHERE x = ? -- note", false);
    String block = update.get(update.size() - 1);
    assertThat(block)
        .startsWith("DECLARE l_i \"ns\".\"t_meta\".\"tx_id\"%TYPE;")
        .contains(" BEGIN l_i := ?; l_p := ?; l_c := ?;")
        .contains(
            "BULK COLLECT INTO l_k0, l_nometa, l_id, l_version, l_state, l_m0 FROM (SELECT"
                + " t.\"id\" AS \"id\" FROM \"ns\".\"RC_t\" t WHERE x = ?) d LEFT JOIN"
                + " \"ns\".\"t_meta\" m ON m.\"id\" = d.\"id\";")
        .contains(
            " IF l_held = 0 AND l_missing = 0 AND l_after = 0 AND l_found > 0 THEN FORALL i IN"
                + " 1 .. l_found UPDATE \"ns\".\"t_data\" t SET x = ? WHERE (x = ?) AND t.\"id\" ="
                + " l_k0(i); FOR i IN 1 .. l_found LOOP l_cnt(i) := SQL%BULK_ROWCOUNT(i); END LOOP;")
        .contains("EXCEPTION WHEN NO_DATA_FOUND THEN l_abort := 1; EXIT; END;")
        .contains("FORALL i IN VALUES OF l_do UPDATE \"ns\".\"t_meta\" SET \"tx_id\" = l_i,")
        .contains(
            "WHERE \"id\" = l_m0(i) AND COALESCE(\"tx_state\", 3) = 3 AND DECODE(\"tx_id\","
                + " l_id(i), 1, 0) = 1 AND COALESCE(\"tx_version\", 0) = COALESCE(l_version(i), 0);"
                + " l_stamped := SQL%ROWCOUNT; END IF;")
        .endsWith(
            " END IF; ? := l_found; ? := l_held; ? := l_missing; ? := l_after; ? := l_written;"
                + " ? := l_stamped; ? := l_skipped; ? := l_abort; END;")
        .doesNotContain("l_after FROM dual WHERE EXISTS")
        .doesNotContain("-- note");
    // at SNAPSHOT the after-image check, hinted, before the gate; and the clear-table block ahead
    // of it: the DML with the gate returning its keys, the stamp over them as a FORALL
    List<String> snapshot = oracle(created(), "UPDATE t SET x = ? WHERE x = ?", true);
    assertThat(snapshot.get(snapshot.size() - 1))
        .contains(
            " SELECT COUNT(*) INTO l_after FROM dual WHERE EXISTS (SELECT 1 FROM (SELECT /*+"
                + " INDEX(m ");
    String index = Engine.ORACLE.quote(Engine.ORACLE.inflightIndexName("t"));
    assertThat(snapshot.get(snapshot.size() - 3))
        .isEqualTo(
            "DECLARE l_i \"ns\".\"t_meta\".\"tx_id\"%TYPE; l_p \"ns\".\"t_meta\".\"tx_prepared_at\"%TYPE;"
                + " l_c \"ns\".\"t_meta\".\"tx_committed_at\"%TYPE; TYPE t_w0 IS TABLE OF"
                + " \"ns\".\"t_data\".\"id\"%TYPE; l_w0 t_w0; l_written NUMBER := 0; l_stamped NUMBER"
                + " := 0; BEGIN l_i := ?; l_p := ?; l_c := ?; UPDATE \"ns\".\"t_data\" t SET x = ?"
                + " WHERE (x = ?) AND NOT EXISTS (SELECT /*+ INDEX(m "
                + index
                + ") */ 1 FROM \"ns\".\"t_meta\" m WHERE m.\"tx_state\" IN (1, 2)) RETURNING \"id\""
                + " BULK COLLECT INTO l_w0; l_written := SQL%ROWCOUNT; FORALL i IN 1 .. l_w0.COUNT"
                + " UPDATE \"ns\".\"t_meta\" SET \"tx_id\" = l_i, \"tx_prepared_at\" = l_p,"
                + " \"tx_committed_at\" = l_c, \"tx_state\" = 3, \"tx_version\" = CASE WHEN \"tx_id\""
                + " IS NULL THEN 1 ELSE COALESCE(\"tx_version\", 0) + 1 END WHERE \"id\" = l_w0(i) AND"
                + " COALESCE(\"tx_state\", 3) = 3; l_stamped := SQL%ROWCOUNT; ? := l_written;"
                + " ? := l_stamped; END;");
    // a created table's DELETE removes the metadata rows; an imported table's stamp is a MERGE
    // per key, which writes the row a key had none of yet
    List<String> createdDelete = oracle(created(), "DELETE FROM t WHERE x = ?", true);
    assertThat(createdDelete.get(createdDelete.size() - 3))
        .contains(
            "FORALL i IN 1 .. l_w0.COUNT DELETE FROM \"ns\".\"t_meta\" WHERE \"id\" = l_w0(i)"
                + " AND COALESCE(\"tx_state\", 3) = 3;");
    List<String> importedDelete = oracle(table(), "DELETE FROM t WHERE x = ?", true);
    assertThat(importedDelete.get(importedDelete.size() - 3))
        .contains(
            "FORALL i IN 1 .. l_w0.COUNT MERGE INTO \"ns\".\"t_meta\" m USING (SELECT l_w0(i)"
                + " AS \"id\" FROM dual) s ON (m.\"id\" = s.\"id\") WHEN MATCHED THEN UPDATE SET m.\"tx_id\""
                + " = l_i, m.\"tx_prepared_at\" = l_p, m.\"tx_committed_at\" = l_c, m.\"tx_state\" = 3,"
                + " m.\"tx_version\" = CASE WHEN m.\"tx_id\" IS NULL THEN 1 ELSE COALESCE(m.\"tx_version\","
                + " 0) + 1 END WHERE COALESCE(m.\"tx_state\", 3) = 3 WHEN NOT MATCHED THEN INSERT (\"id\","
                + " \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\", \"tx_committed_at\") VALUES"
                + " (s.\"id\", l_i, 3, 1, l_p, l_c); l_stamped := SQL%ROWCOUNT;");
    // the inline layout: the committed rows and the before images in flight as the keys read, the
    // UPDATE with the CAS folded into the version it writes (-1 where the row moved), returning
    // the versions; the DELETE with its CAS in its WHERE clause and the predicate re-read on a
    // skipped row
    List<String> inlineUpdate = oracle(inline(), "UPDATE t SET x = ? WHERE x = ?", false);
    assertThat(inlineUpdate.get(inlineUpdate.size() - 1))
        .contains("UNION ALL SELECT t.\"id\", NULL, NULL, 1 FROM (SELECT /*+ INDEX(m ")
        .contains(
            "FORALL i IN 1 .. l_found UPDATE t SET \"tx_id\" = l_i, \"tx_prepared_at\" = l_p,"
                + " \"tx_committed_at\" = l_c, \"tx_state\" = 3, \"tx_version\" = CASE WHEN"
                + " DECODE(t.\"tx_id\", l_id(i), 1, 0) = 1 AND COALESCE(t.\"tx_version\", 0) ="
                + " COALESCE(l_version(i), 0) THEN CASE WHEN t.\"tx_id\" IS NULL THEN 1 ELSE"
                + " COALESCE(t.\"tx_version\", 0) + 1 END ELSE -1 END, x = ? WHERE (x = ?) AND t.\"id\" ="
                + " l_k0(i) AND COALESCE(t.\"tx_state\", 3) = 3 RETURNING t.\"tx_version\" BULK COLLECT"
                + " INTO l_v;")
        .contains(
            " IF l_abort = 0 THEN FOR i IN 1 .. l_v.COUNT LOOP IF l_v(i) <> -1 THEN l_stamped"
                + " := l_stamped + 1; END IF; END LOOP; END IF;");
    List<String> inlineDelete = oracle(inline(), "DELETE FROM t WHERE x = ?", false);
    assertThat(inlineDelete.get(inlineDelete.size() - 1))
        .contains(
            "FORALL i IN 1 .. l_found DELETE FROM t WHERE (x = ?) AND t.\"id\" = l_k0(i) AND"
                + " COALESCE(t.\"tx_state\", 3) = 3 AND DECODE(t.\"tx_id\", l_id(i), 1, 0) = 1 AND"
                + " COALESCE(t.\"tx_version\", 0) = COALESCE(l_version(i), 0);")
        .contains(
            " SELECT COUNT(*) INTO n_matches FROM \"ns\".\"t\" t WHERE (x = ?) AND t.\"id\" ="
                + " l_k0(i); IF n_matches > 0 THEN l_abort := 4; EXIT; END IF;")
        .contains(" IF l_abort = 0 THEN l_stamped := l_written; END IF;");
  }

  @Test
  void onOracle_anInsertIsOneStatement() throws Exception {
    // a decoupled table: the row, its key into locals, the metadata row under the hint that skips
    // a key with one, and that row taken over when skipped; the key back through the cursor
    List<String> created = oracle(created(), "INSERT INTO t (id, x) VALUES (?, ?) -- c", false);
    assertThat(created.get(created.size() - 1))
        .isEqualTo(
            "DECLARE l_i \"ns\".\"t_meta\".\"tx_id\"%TYPE; l_p \"ns\".\"t_meta\".\"tx_prepared_at\"%TYPE;"
                + " l_c \"ns\".\"t_meta\".\"tx_committed_at\"%TYPE; l_k0 \"ns\".\"t_data\".\"id\"%TYPE;"
                + " l_meta NUMBER := 0; l_stamped NUMBER := 0; BEGIN l_i := ?; l_p := ?; l_c := ?;"
                + " INSERT INTO \"ns\".\"t_data\" (id, x) VALUES (?, ?) RETURNING \"id\" INTO l_k0;"
                + " INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(x (\"id\")) */ INTO \"ns\".\"t_meta\" x"
                + " (\"id\", \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
                + " \"tx_committed_at\") VALUES (l_k0, l_i, 3, 1, l_p, l_c); l_meta := SQL%ROWCOUNT;"
                + " IF l_meta = 0 THEN UPDATE \"ns\".\"t_meta\" SET \"tx_id\" = l_i, \"tx_prepared_at\""
                + " = l_p, \"tx_committed_at\" = l_c, \"tx_state\" = 3, \"tx_version\" = CASE WHEN"
                + " \"tx_id\" IS NULL THEN 1 ELSE COALESCE(\"tx_version\", 0) + 1 END WHERE \"id\" ="
                + " l_k0 AND COALESCE(\"tx_state\", 3) = 3; l_stamped := SQL%ROWCOUNT; END IF; OPEN ?"
                + " FOR SELECT l_meta, l_stamped, l_k0 FROM dual; END;");
    // an inline table: the statement with the stamp's columns, the one statement it is
    List<String> inline = oracle(inline(), "INSERT INTO t (id, x) VALUES (?, ?)", false);
    assertThat(inline.get(inline.size() - 1))
        .isEqualTo(
            "INSERT INTO t (id, x, \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
                + " \"tx_committed_at\") VALUES (?, ?, ?, 3, 1, ?, ?)");
    // several rows keep the steps
    assertThat(oracle(created(), "INSERT INTO t (id, x) VALUES (1, 'a'), (2, 'b')", false))
        .noneMatch(sql -> sql.startsWith("DECLARE"));
  }

  @Test
  void onOracle_aBatchsEntriesGoAsOneBlock() throws Exception {
    // the keyed block's body once per entry, each entry's locals suffixed, every entry under
    // IF l_stop = 0, which an entry whose outcome is not final sets; one cursor row per entry
    StatementShape shape =
        StatementShape.of(
                SqlAst.parse("UPDATE t SET x = ? WHERE \"id\" = ?", Engine.ORACLE).orElseThrow())
            .orElseThrow();
    Writes.Entries entries =
        Writes.plan(Engine.ORACLE, shape, inline(), "", false, false, null).entries();
    assertThat(entries).isNotNull();
    assertThat(entries.binds).containsExactly(2, 1, 2); // the key for the read, then x and the key
    assertThat(entries.stampAfter).isEqualTo(1);
    String two = entries.sql(2, Writes.Variant.CURSOR);
    assertThat(two)
        .startsWith("DECLARE l_rows_1 NUMBER := 0; l_written_1 NUMBER := 0; l_id_1")
        .contains(" l_k0_1 \"ns\".\"t\".\"id\"%TYPE; l_done_1 NUMBER := 0; l_rows_2 NUMBER := 0;")
        .contains(
            " l_done_2 NUMBER := 0; l_stop NUMBER := 0; BEGIN IF l_stop = 0 THEN l_done_1 :="
                + " 1; BEGIN SELECT 1, t.\"tx_id\", t.\"tx_version\", t.\"tx_state\", t.\"id\" INTO"
                + " l_rows_1, l_id_1, l_version_1, l_state_1, l_k0_1 FROM \"ns\".\"t\" t WHERE \"id\" = ?;")
        .contains(
            " IF NOT (l_rows_1 = 0 OR (l_state_1 IS NOT NULL AND l_state_1 NOT IN (1, 2, 3))"
                + " OR (l_written_1 = 1)) THEN l_stop := 1; END IF; END IF; IF l_stop = 0 THEN l_done_2"
                + " := 1;")
        .endsWith(
            " OPEN ? FOR SELECT 1, l_done_1, l_rows_1, 1, l_id_1, l_version_1, l_state_1,"
                + " l_written_1, l_written_1, l_k0_1 FROM dual UNION ALL SELECT 2, l_done_2, l_rows_2,"
                + " 1, l_id_2, l_version_2, l_state_2, l_written_2, l_written_2, l_k0_2 FROM dual ORDER"
                + " BY 1; END;");
    assertThat(entries.parameters()).isEqualTo(6);
    // the decoupled INSERT block's entries, each entry's stamp its own; the inline INSERT goes
    // through the vendor's batching instead
    StatementShape insert =
        StatementShape.of(
                SqlAst.parse("INSERT INTO t (id, x) VALUES (?, ?)", Engine.ORACLE).orElseThrow())
            .orElseThrow();
    Writes.Plan created = Writes.plan(Engine.ORACLE, insert, created(), "", false, false, null);
    Writes.Entries inserts = created.entries();
    assertThat(inserts).isNotNull();
    assertThat(inserts.sql(2, Writes.Variant.CURSOR))
        .contains(
            " BEGIN IF l_stop = 0 THEN l_done_1 := 1; l_i_1 := ?; l_p_1 := ?; l_c_1 := ?;"
                + " INSERT INTO \"ns\".\"t_data\" (id, x) VALUES (?, ?) RETURNING \"id\" INTO l_k0_1;")
        .contains(" IF NOT (l_meta_1 = 1 OR l_stamped_1 = 1) THEN l_stop := 1; END IF; END IF;")
        .endsWith(
            " OPEN ? FOR SELECT 1, l_done_1, l_meta_1, l_stamped_1, l_k0_1 FROM dual UNION ALL"
                + " SELECT 2, l_done_2, l_meta_2, l_stamped_2, l_k0_2 FROM dual ORDER BY 1; END;");
    assertThat(created.vendorBatch()).isNull();
    Writes.Plan inlined = Writes.plan(Engine.ORACLE, insert, inline(), "", false, false, null);
    assertThat(inlined.entries()).isNull();
    assertThat(inlined.vendorBatch()).isNotNull();
    assertThat(inlined.vendorBatch().sql)
        .isEqualTo(
            "INSERT INTO t (id, x, \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
                + " \"tx_committed_at\") VALUES (?, ?, ?, 3, 1, ?, ?)");
  }

  @Test
  void onOracle_aDecoupledInsertOfOneRowIsOneInsertAll() throws Exception {
    // the row into the data table as written, the metadata row from the key's own values again,
    // then the stamp (design 4.3 "The decoupled INSERT as one statement", todo 334)
    String one =
        "INSERT ALL INTO \"ns\".\"t_data\" (\"id\", x) VALUES (?, ?) INTO \"ns\".\"t_meta\" (\"id\","
            + " \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\", \"tx_committed_at\")"
            + " VALUES (CAST(? AS number), ?, 3, 1, ?, ?) SELECT 1 FROM dual";
    assertThat(oraclePlan(created(), "INSERT INTO t (\"id\", x) VALUES (?, ?)", false).sql())
        .contains(one);
    assertThat(oraclePlan(created(), "INSERT INTO t (x, \"id\") VALUES ('a', 7)", false).sql())
        .contains(
            "INSERT ALL INTO \"ns\".\"t_data\" (x, \"id\") VALUES ('a', 7) INTO \"ns\".\"t_meta\""
                + " (\"id\", \"tx_id\", \"tx_state\", \"tx_version\", \"tx_prepared_at\","
                + " \"tx_committed_at\") VALUES (CAST(7 AS number), ?, 3, 1, ?, ?) SELECT 1 FROM dual");
    // a key given by an expression is evaluated once per INTO, so it never takes the form: the key
    // values are placeholders and literals only (insertKeyValues), and on Oracle the INSERT is
    // refused before
    assertThatThrownBy(
            () -> oraclePlan(created(), "INSERT INTO t (\"id\", x) VALUES (ABS(?), 'a')", false))
        .isInstanceOf(SQLException.class);
    // a key column whose type the driver does not know keeps the block, which takes the key as the
    // data row stores it
    StatementShape unknown =
        StatementShape.of(
                SqlAst.parse("INSERT INTO t (\"id\", x) VALUES (?, ?)", Engine.ORACLE)
                    .orElseThrow())
            .orElseThrow();
    assertThat(
            Writes.plan(
                    Engine.ORACLE,
                    unknown,
                    created(),
                    "",
                    false,
                    false,
                    null,
                    Map.of("x", "number"))
                .sql())
        .noneMatch(q -> q.startsWith("INSERT ALL"));
    // not for the inline layout, whose INSERT is one statement already
    assertThat(oraclePlan(inline(), "INSERT INTO t (id, x) VALUES (?, ?)", false).sql())
        .noneMatch(q -> q.startsWith("INSERT ALL"));
  }

  /** The declared types a table of one NUMBER key and a text column has on Oracle. */
  private static final Map<String, String> ORACLE_COLUMNS =
      Map.of("id", "number", "x", "varchar2(10 char)");

  private static Writes.Plan oraclePlan(TableInfo t, String sql, boolean snapshot)
      throws Exception {
    StatementShape shape =
        StatementShape.of(SqlAst.parse(sql, Engine.ORACLE).orElseThrow()).orElseThrow();
    return Writes.plan(Engine.ORACLE, shape, t, "", snapshot, false, null, ORACLE_COLUMNS);
  }

  @Test
  void onOracle_theRowComesBackThroughOutBinds() throws Exception {
    // the OUT bind's type per declared key type, as the design's table has it; any other type
    // keeps the cursor
    assertThat(Writes.oracleKeyJdbcType("NUMBER")).isEqualTo(2);
    assertThat(Writes.oracleKeyJdbcType("number(10)")).isEqualTo(2);
    assertThat(Writes.oracleKeyJdbcType("varchar2(10 char)")).isEqualTo(12);
    assertThat(Writes.oracleKeyJdbcType("char(5 char)")).isEqualTo(1);
    assertThat(Writes.oracleKeyJdbcType("binary_float")).isEqualTo(101);
    assertThat(Writes.oracleKeyJdbcType("binary_double")).isEqualTo(101);
    assertThat(Writes.oracleKeyJdbcType("date")).isEqualTo(93);
    assertThat(Writes.oracleKeyJdbcType("timestamp(6)")).isEqualTo(93);
    assertThat(Writes.oracleKeyJdbcType("raw(16)")).isEqualTo(-3);
    for (String other :
        List.of(
            "timestamp(6) with time zone",
            "nvarchar2(10)",
            "nchar(5)",
            "float",
            "interval day to second")) {
      assertThat(Writes.oracleKeyJdbcType(other)).as(other).isNull();
    }
    // with the columns' types known, the keyed block returns its row as assignments to OUT
    // binds, the counts NUMBER and the key by the column's type; the checking variant adds the
    // cursor over the same locals after them; the cursor variant is the first design's
    Writes.Plan keyed = oraclePlan(inline(), "UPDATE t SET x = x || ? WHERE \"id\" = ?", false);
    Writes.Block block = keyed.oracleBlock();
    assertThat(block).isNotNull();
    assertThat(block.keyTypes).containsExactly(2);
    String assigns =
        " ? := l_rows; ? := 1; ? := l_id; ? := l_version; ? := l_state; ? := l_written;"
            + " ? := l_written; ? := l_k0;";
    String cursor =
        " OPEN ? FOR SELECT l_rows, 1, l_id, l_version, l_state, l_written, l_written, l_k0 FROM"
            + " dual;";
    assertThat(block.step(Writes.Variant.OUT).sql).endsWith(" END IF;" + assigns + " END;");
    assertThat(block.step(Writes.Variant.CHECK).sql)
        .endsWith(" END IF;" + assigns + cursor + " END;");
    assertThat(block.step(Writes.Variant.CURSOR).sql).endsWith(" END IF;" + cursor + " END;");
    assertThat(block.step(Writes.Variant.OUT).outTypes).containsExactly(2, 2, 12, 2, 2, 2, 2, 2);
    assertThat(block.step(Writes.Variant.OUT).cursor).isFalse();
    assertThat(block.step(Writes.Variant.CHECK).cursor).isTrue();
    assertThat(block.step(Writes.Variant.CURSOR).outTypes).isEmpty();
    // the texts differ in nothing but the row's return
    String body = block.step(Writes.Variant.OUT).sql;
    assertThat(block.step(Writes.Variant.CHECK).sql)
        .startsWith(body.substring(0, body.length() - " END;".length()));
    // the INSERT block likewise, meta, stamped and the key
    Writes.Plan insert = oraclePlan(created(), "INSERT INTO t (id, x) VALUES (?, ?)", false);
    Writes.Block inserted = insert.oracleBlock();
    assertThat(inserted).isNotNull();
    assertThat(inserted.step(Writes.Variant.OUT).sql)
        .endsWith(" END IF; ? := l_meta; ? := l_stamped; ? := l_k0; END;");
    assertThat(inserted.step(Writes.Variant.OUT).outTypes).containsExactly(2, 2, 2);
    // a batch's entries: per entry n, done and the row as OUT binds; the checking variant's
    // cursor is the UNION ALL as before
    Writes.Entries entries = keyed.entries();
    assertThat(entries).isNotNull();
    assertThat(entries.outTypes()).containsExactly(2, 2, 2, 2, 12, 2, 2, 2, 2, 2);
    assertThat(entries.sql(2, Writes.Variant.OUT))
        .contains(
            " ? := 1; ? := l_done_1; ? := l_rows_1; ? := 1; ? := l_id_1; ? := l_version_1;"
                + " ? := l_state_1; ? := l_written_1; ? := l_written_1; ? := l_k0_1; ? := 2;"
                + " ? := l_done_2;")
        .endsWith(" ? := l_k0_2; END;")
        .doesNotContain("OPEN ?");
    assertThat(entries.sql(2, Writes.Variant.CHECK))
        .contains(" ? := l_k0_2; OPEN ? FOR SELECT 1, l_done_1,")
        .endsWith(" FROM dual ORDER BY 1; END;");
    // without the columns' types the cursor stands: the plan's text is the first design's
    assertThat(oracle(inline(), "UPDATE t SET x = x || ? WHERE \"id\" = ?", false))
        .last()
        .asString()
        .endsWith(cursor + " END;");
    assertThat(oracle(inline(), "UPDATE t SET x = x || ? WHERE \"id\" = ?", false).size())
        .isEqualTo(
            oraclePlan(inline(), "UPDATE t SET x = x || ? WHERE \"id\" = ?", false).sql().size());
  }

  @Test
  void onOracle_theFirstUseCheck_decidesFromTheTwoKeys() {
    Object[] out = {new java.math.BigDecimal(5)};
    Object[] same = {new java.math.BigDecimal(5)};
    Object[] other = {5L};
    // equal under Anchors.key: verified; a different class or value: the cursor; no key: nothing
    assertThat(Writes.OutBindCheck.compare(out, same))
        .isEqualTo(Writes.OutBindCheck.Outcome.VERIFIED);
    assertThat(Writes.OutBindCheck.compare(out, other))
        .isEqualTo(Writes.OutBindCheck.Outcome.CURSOR);
    assertThat(Writes.OutBindCheck.compare(null, null))
        .isEqualTo(Writes.OutBindCheck.Outcome.UNDECIDED);
    assertThat(Writes.OutBindCheck.compare(out, null))
        .isEqualTo(Writes.OutBindCheck.Outcome.CURSOR);
    // recorded once per table: the first decisive pair counts, a later one changes nothing
    Writes.OutBindCheck.reset();
    assertThat(Writes.OutBindCheck.variant("ns.a")).isEqualTo(Writes.Variant.CHECK);
    assertThat(Writes.OutBindCheck.decide("ns.a", null, null))
        .isEqualTo(Writes.OutBindCheck.Outcome.UNDECIDED);
    assertThat(Writes.OutBindCheck.variant("ns.a")).isEqualTo(Writes.Variant.CHECK);
    Writes.OutBindCheck.decide("ns.a", out, same);
    assertThat(Writes.OutBindCheck.variant("ns.a")).isEqualTo(Writes.Variant.OUT);
    Writes.OutBindCheck.decide("ns.a", out, other);
    assertThat(Writes.OutBindCheck.variant("ns.a")).isEqualTo(Writes.Variant.OUT);
    Writes.OutBindCheck.decide("ns.b", out, other);
    assertThat(Writes.OutBindCheck.variant("ns.b")).isEqualTo(Writes.Variant.CURSOR);
    Writes.OutBindCheck.reset();
    assertThat(Writes.OutBindCheck.variant("ns.b")).isEqualTo(Writes.Variant.CHECK);
    // the WARN line names the classes per column, never the values (a key may be a person's
    // identifier); the values are the DEBUG line's
    assertThat(Writes.OutBindCheck.classes(new Object[] {"1", 5L, null}))
        .isEqualTo("java.lang.String, java.lang.Long, null")
        .doesNotContain("5");
    assertThat(Writes.OutBindCheck.classes(null)).isEqualTo("no key");
    assertThat(Writes.OutBindCheck.describe(new Object[] {"1", 5L}))
        .isEqualTo("1 (java.lang.String), 5 (java.lang.Long)");
  }

  @Test
  void onOracle_theBlockThatFindsAMismatch_decidesFromTheCursorsRow() throws Exception {
    // a table's first keyed write in the process, the checking variant: the OUT binds give the
    // key as a String where the cursor gives the NUMBER the reads report, so the two differ under
    // Anchors.key; the transaction read the row at version 5 under the reads' key, and the block
    // finds it at 7, written: the write must abort for the moved version, which it does only when
    // the block is decided from the cursor's row (the OUT key finds no anchor, "unchanged")
    Writes.OutBindCheck.reset();
    Writes.Plan plan = oraclePlan(inline(), "UPDATE t SET x = x || ? WHERE \"id\" = ?", false);
    Writes.Block block = Objects.requireNonNull(plan.oracleBlock());
    String table = block.table;
    Writes.Step step = block.step(Writes.Variant.CHECK);
    int at = step.outAt();
    Map<Integer, Object> out = new HashMap<>();
    Map<Integer, Object> cursor = new HashMap<>();
    Object[] row = {
      1, 1, "tx-b", 7L, 3, 1L, 1L
    }; // rows, meta, tx_id, version, state, written, stamped
    for (int i = 0; i < row.length; i++) {
      out.put(at + i, row[i]);
      cursor.put(1 + i, row[i]);
    }
    out.put(at + row.length, "1");
    cursor.put(1 + row.length, 1L);
    Anchors anchors = new Anchors();
    anchors.read(anchors.epoch(), table, new Object[] {1L}, new Anchors.Version("tx-a", 5L));
    Writes.Run run =
        new Writes.Run(fakeConnection(out, step.cursorAt(), cursor), fakeContext(anchors));
    assertThatThrownBy(() -> plan.apply(run))
        .isInstanceOf(Writes.Aborted.class)
        .hasMessageContaining("changed by another one since");
    assertThat(Writes.OutBindCheck.variant(table)).isEqualTo(Writes.Variant.CURSOR);
    // the keys equal: the write goes through against the version read, the table verified
    Writes.OutBindCheck.reset();
    out.put(at + row.length, 1L);
    Anchors same = new Anchors();
    same.read(same.epoch(), table, new Object[] {1L}, new Anchors.Version("tx-b", 7L));
    Writes.Run verified =
        new Writes.Run(fakeConnection(out, step.cursorAt(), cursor), fakeContext(same));
    assertThat(plan.apply(verified)).isEqualTo(1);
    assertThat(Writes.OutBindCheck.variant(table)).isEqualTo(Writes.Variant.OUT);
    // no row for the key: count 0 through either path, and nothing decided
    Writes.OutBindCheck.reset();
    out.put(at, 0);
    out.put(at + row.length, null);
    cursor.put(1, 0);
    cursor.put(1 + row.length, null);
    Writes.Run none =
        new Writes.Run(fakeConnection(out, step.cursorAt(), cursor), fakeContext(same));
    assertThat(plan.apply(none)).isZero();
    assertThat(Writes.OutBindCheck.variant(table)).isEqualTo(Writes.Variant.CHECK);
  }

  /**
   * A vendor connection whose callable statement answers the block's OUT binds by position from
   * {@code out} and opens, at {@code cursorAt}, a result set of one row answered from {@code
   * cursor} (its key column a NUMBER); everything else is a no-op.
   */
  private static Connection fakeConnection(
      Map<Integer, Object> out, int cursorAt, Map<Integer, Object> cursor) {
    ResultSetMetaData md =
        fake(
            ResultSetMetaData.class,
            Map.of("getColumnTypeName", "NUMBER", "getColumnType", 2),
            null);
    Map<String, Object> rsAnswers = new HashMap<>();
    rsAnswers.put("getMetaData", md);
    rsAnswers.put("next", new ArrayDeque<>(List.of(true, false)));
    ResultSet rs = fake(ResultSet.class, rsAnswers, cursor);
    Map<Integer, Object> outs = new HashMap<>(out);
    outs.put(cursorAt, rs);
    CallableStatement cs = fake(CallableStatement.class, Map.of(), outs);
    return fake(Connection.class, Map.of("prepareCall", cs), null);
  }

  private static Writes.Context fakeContext(Anchors anchors) {
    return fake(
        Writes.Context.class, Map.of("anchors", anchors, "inflightWait", Duration.ZERO), null);
  }

  /**
   * A fake of a JDBC interface: a positional getter ({@code getInt}, {@code getLong}, {@code
   * getString}, {@code getObject}) answers from {@code byPosition}; a method named in {@code
   * answers} returns its value, a queue of values one per call; every other method returns the
   * default of its type.
   */
  @SuppressWarnings("ReferenceEquality") // a proxy's equals is its identity
  private static <T> T fake(
      Class<T> type, Map<String, Object> answers, @Nullable Map<Integer, Object> byPosition) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              String name = method.getName();
              if (byPosition != null
                  && args != null
                  && args.length >= 1
                  && args[0] instanceof Integer
                  && (name.equals("getInt")
                      || name.equals("getLong")
                      || name.equals("getString")
                      || name.equals("getObject"))) {
                Object v = byPosition.get((Integer) args[0]);
                return switch (name) {
                  case "getInt" -> v == null ? 0 : ((Number) v).intValue();
                  case "getLong" -> v == null ? 0L : ((Number) v).longValue();
                  case "getString" -> v == null ? null : v.toString();
                  default -> v;
                };
              }
              if (answers.containsKey(name)) {
                Object a = answers.get(name);
                return a instanceof Deque<?> q ? (q.isEmpty() ? false : q.poll()) : a;
              }
              Class<?> r = method.getReturnType();
              if (r == boolean.class) {
                return false;
              }
              if (r == int.class) {
                return 0;
              }
              if (r == long.class) {
                return 0L;
              }
              if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
              }
              if (name.equals("equals")) {
                return proxy == args[0];
              }
              if (name.equals("toString")) {
                return "fake " + type.getSimpleName();
              }
              return null;
            }));
  }

  @Test
  void createdTable_isWrittenThroughItsDataTable_underItsName() throws Exception {
    List<String> sql = plan(created(), "UPDATE ns.t SET x = 1 WHERE t.id = ?");
    assertThat(sql.get(1))
        .isEqualTo("UPDATE \"ns\".\"t_data\" AS t SET x = 1 WHERE (t.id = ?) AND (\"id\" = ?)");
    assertThat(plan(created(), "INSERT INTO t (id, x) VALUES (1, 'a')").get(0))
        .isEqualTo("INSERT INTO \"ns\".\"t_data\" AS t (id, x) VALUES (1, 'a') RETURNING \"id\"");
  }

  @Test
  void refusesWhatTheFirstStepDoesNotRun() {
    for (String sql :
        List.of("UPDATE t SET id = 2 WHERE id = 1", "DELETE FROM t WHERE id = 1 RETURNING x")) {
      assertThatThrownBy(() -> plan(sql))
          .as(sql)
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("0AN01");
    }
  }
}
