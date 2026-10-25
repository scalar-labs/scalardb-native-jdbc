package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.StatementShape;
import java.lang.reflect.Constructor;
import java.sql.SQLException;
import java.util.List;
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
    // the in-flight index; nothing more for a table the transaction found clear
    String gate = " AND NOT EXISTS (SELECT 1 FROM \"ns\".\"t\" WHERE \"tx_state\" IN (1, 2))";
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
            "WITH scalardb_native_w AS (DELETE FROM \"ns\".\"t_data\" AS t WHERE (id = ?) AND NOT"
                + " EXISTS (SELECT 1 FROM \"ns\".\"t_meta\" WHERE \"tx_state\" IN (1, 2))"
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
        .contains("FROM scalardb_native_keys k WHERE (x = ?) AND (\"id\") = (k.scalardb_native_d0)")
        .contains(
            "RETURNING \"id\" AS scalardb_native_w0, \"tx_version\" <> -1 AS scalardb_native_ok");
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
