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
    StatementShape shape =
        StatementShape.of(SqlAst.parse(sql, Engine.POSTGRESQL).orElseThrow()).orElseThrow();
    return Writes.plan(Engine.POSTGRESQL, shape, t, "").sql();
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
        .containsExactly("INSERT INTO t (x) VALUES ('a') RETURNING \"id\"");
    // the statement's own items first, before a trailing comment
    assertThat(plan("INSERT INTO t (id, x) VALUES (1, 'a') RETURNING x AS y -- c").get(0))
        .isEqualTo("INSERT INTO t (id, x) VALUES (1, 'a') RETURNING x AS y, \"id\" -- c");
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
