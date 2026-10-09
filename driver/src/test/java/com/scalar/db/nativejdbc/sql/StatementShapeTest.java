package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.List;
import net.sf.jsqlparser.statement.select.ForMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StatementShapeTest {

  private static StatementShape shape(String sql) {
    return StatementShape.of(sql, Engine.POSTGRESQL).orElseThrow();
  }

  @Test
  void columnNames_followTheEngineRule() {
    String unquoted = "update t set x = ? where amount = ?";
    String quoted = "update t set x = ? where \"Amount\" = ?";
    assertThat(shape(unquoted).whereColumns()).containsExactly("amount");
    assertThat(StatementShape.of(unquoted, Engine.ORACLE).orElseThrow().whereColumns())
        .containsExactly("AMOUNT");
    assertThat(StatementShape.of(unquoted, Engine.MYSQL).orElseThrow().whereColumns())
        .containsExactly("amount");
    for (Engine e : Engine.values()) {
      // in a session where double quotes quote identifiers (MySQL with ANSI_QUOTES)
      SqlAst ast = SqlAst.parse(quoted, e, true).orElseThrow();
      assertThat(StatementShape.of(ast).orElseThrow().whereColumns())
          .as("%s keeps a quoted name", e)
          .containsExactly("Amount");
      assertThat(StatementShape.of(ast).orElseThrow().isKeyEquality(List.of("Amount")))
          .as("%s matches the catalog spelling when quoted", e)
          .isTrue();
    }
    // the catalog spells the key column "Amount": only engines that ignore case match the unquoted
    // spelling, and Oracle matches its own upper-case fold
    assertThat(shape(unquoted).isKeyEquality(List.of("Amount"))).isFalse();
    assertThat(
            StatementShape.of(unquoted, Engine.MYSQL)
                .orElseThrow()
                .isKeyEquality(List.of("Amount")))
        .isTrue();
    assertThat(
            StatementShape.of(unquoted, Engine.MARIADB)
                .orElseThrow()
                .isKeyEquality(List.of("Amount")))
        .isTrue();
    assertThat(
            StatementShape.of(unquoted, Engine.SQLSERVER)
                .orElseThrow()
                .isKeyEquality(List.of("Amount")))
        .isTrue();
    assertThat(
            StatementShape.of(unquoted, Engine.ORACLE)
                .orElseThrow()
                .isKeyEquality(List.of("Amount")))
        .isFalse();
    assertThat(
            StatementShape.of(unquoted, Engine.ORACLE)
                .orElseThrow()
                .isKeyEquality(List.of("AMOUNT")))
        .isTrue();
  }

  @Test
  void select_byKey() {
    StatementShape s =
        shape(
            "SELECT \"t\".\"id\" AS \"id\", \"t\".\"x\" AS \"x\" FROM \"t\" WHERE \"t\".\"id\" = ?");
    assertThat(s.kind()).isEqualTo(StatementShape.Kind.SELECT);
    assertThat(s.table().name()).isEqualTo("\"t\"");
    assertThat(s.selectColumns()).isEmpty(); // aliased items are not a plain projection
    assertThat(s.whereColumns()).containsExactly("id");
    assertThat(s.isKeyEquality(List.of("id"))).isTrue();
    assertThat(s.isKeyEquality(List.of("id", "k"))).isFalse();
  }

  @Test
  void select_star_withPredicateOrderingAndPaging() {
    StatementShape s =
        shape(
            "select * from s.orders o where o.x = ? and y in (?, ?) order by o.id limit 10 offset 2");
    assertThat(s.selectAll()).isTrue();
    assertThat(s.table().schema()).isEqualTo("s");
    assertThat(s.whereColumns()).containsExactly("x", "y");
    assertThat(s.isKeyEquality(List.of("id"))).isFalse();
    assertThat(s.ordered()).isTrue();
    assertThat(s.limited()).isTrue();
    assertThat(s.grouped()).isFalse();
    assertThat(s.lockMode()).isNull();
  }

  @Test
  void select_plainColumns_andAggregates_andLocks() {
    assertThat(shape("select id, x from t").selectColumns()).containsExactly("id", "x");
    StatementShape agg = shape("select count(*) from t where x = ?");
    assertThat(agg.grouped()).isTrue();
    assertThat(agg.selectColumns()).isEmpty();
    assertThat(shape("select distinct x from t").grouped()).isTrue();
    assertThat(shape("select x from t group by x").grouped()).isTrue();
    assertThat(shape("select * from t where id = ? for update").lockMode())
        .isEqualTo(ForMode.UPDATE);
    assertThat(shape("select * from t for share").lockMode()).isEqualTo(ForMode.SHARE);
  }

  @Test
  void keyEquality_requiresEveryKeyColumnOnceAndNothingElse() {
    assertThat(shape("select * from t where id = ? and k = 1").isKeyEquality(List.of("id", "k")))
        .isTrue();
    assertThat(shape("select * from t where k = 1 and id = ?").isKeyEquality(List.of("id", "k")))
        .isTrue();
    assertThat(shape("select * from t where id = ? and version = ?").isKeyEquality(List.of("id")))
        .isFalse();
    assertThat(shape("select * from t where id = ? or id = ?").isKeyEquality(List.of("id")))
        .isFalse();
    assertThat(shape("select * from t where id > ?").isKeyEquality(List.of("id"))).isFalse();
    assertThat(shape("select * from t where id = ? and id = ?").isKeyEquality(List.of("id")))
        .isFalse();
    assertThat(shape("select * from t where id = x").isKeyEquality(List.of("id"))).isFalse();
    assertThat(shape("select * from t").isKeyEquality(List.of("id"))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "id = ?",
        "id = :id",
        "? = id",
        "id = 1",
        "id = -1",
        "id = 'a'",
        "id = {d '2020-01-01'}",
        "id = cast(? as int)",
        "id = ?::bigint",
        "id = -cast(? as int)"
      })
  void keyEquality_acceptsPlaceholdersAndLiterals_signedOrCast(String term) {
    assertThat(shape("update t set x = ? where " + term).isKeyEquality(List.of("id"))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "id = id + 0", // every row, not one
        "id = id",
        "id = (select 5)",
        "id = null", // no row
        "id = abs(1)",
        "id = coalesce(?, 0)",
        "id = cast(k as int)",
        "id = x + ?"
      })
  void keyEquality_refusesAnythingButAValue(String term) {
    assertThat(shape("update t set x = ? where " + term).isKeyEquality(List.of("id"))).isFalse();
  }

  @Test
  void insert_withAndWithoutColumnList_andRows() {
    StatementShape one = shape("insert into t (id, x) values (?, ?)");
    assertThat(one.kind()).isEqualTo(StatementShape.Kind.INSERT);
    assertThat(one.insertColumns()).containsExactly("id", "x");
    assertThat(one.insertRowCount()).isEqualTo(1);
    assertThat(one.returning()).isFalse();

    StatementShape two = shape("insert into t values (1, 'a'), (2, 'b') returning id");
    assertThat(two.insertColumns()).isEmpty();
    assertThat(two.insertRowCount()).isEqualTo(2);
    assertThat(two.returning()).isTrue();
  }

  @Test
  void update_setColumnsAndPredicate() {
    StatementShape s = shape("update s.t set x = x + 1, note = ? where id = ? and version = ?");
    assertThat(s.kind()).isEqualTo(StatementShape.Kind.UPDATE);
    assertThat(s.setColumns()).containsExactly("x", "note");
    assertThat(s.whereColumns()).containsExactly("id", "version");
    assertThat(s.isKeyEquality(List.of("id"))).isFalse();
    assertThat(shape("update t set x = 1").where()).isNull();
  }

  @Test
  void delete_byKeyAndWithoutPredicate() {
    StatementShape s = shape("delete from t where id = ?");
    assertThat(s.kind()).isEqualTo(StatementShape.Kind.DELETE);
    assertThat(s.isKeyEquality(List.of("id"))).isTrue();
    assertThat(shape("delete from t").where()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "select * from a join b on a.id = b.id",
        "select * from a, b",
        "select * from a where id in (select id from b)",
        "select * from a union select * from b",
        "with c as (select 1) select * from c",
        "select * from (select * from a) x",
        "select o.id from t", // qualified by a name that is not the table or its alias
        "select s.t.id from u.t", // qualified by the wrong schema
        "insert into t select * from u",
        "insert into t (id) values (?); delete from t",
        "insert ignore into t (id) values (?)",
        "update ignore t set x = ? where id = ?",
        "insert into t (id) values (?) on duplicate key update x = 1",
        "insert into t (id) values (?) on conflict (id) do nothing",
        "insert into t (id) output inserted.id values (?)",
        "select * from t where id = ? for update skip locked",
        "select * from t where id = ? for update nowait",
        "select * from t where id = ? for update wait 5",
        "select /*+ full(t) */ * from t where id = ?",
        "update /*+ index(t i) */ t set x = ? where id = ?",
        "delete /*+ parallel */ from t where id = ?",
        "insert /*+ append */ into t (id) values (?)",
        "select * from t with (nolock) where id = ?",
        "select * from t use index (i) where id = ?",
        "select * from t tablesample system (10)",
        "delete from t using (select 1 where false) v where id < 5", // reads other rows
        "delete from t using u where t.id = u.id",
        "select * from t@remote where id = ?", // a database link names a table elsewhere
        "select * from t start with id = 1 connect by prior id = parent_id",
        "select * from t where id = ? for xml auto",
        "select * into #tmp from t where id = ?",
        "select * from t where id = ? into outfile '/tmp/x'",
        "select * from t where id = ? qualify row_number() over (order by id) = 1",
        "select * from t lateral view explode(x) e as y where id = ?",
        "select * from t prewhere id = ? where x = 1",
        "update t for portion of valid_time from ? to ? set x = ? where id = ?",
        "delete from t for portion of valid_time from ? to ? where id = ?",
        "merge into t using s on t.id = s.id when matched then update set x = s.x",
        "update t set (x, y) = (?, ?) where id = ?",
        "update t set x = 1 where id = ? limit 1",
        "delete from t order by id limit 1",
        "delete t from t join u on t.id = u.id",
        "select top 5 * from t",
        "select * from d.s.t",
        "insert into t (a.id) values (1)",
        "select from where"
      })
  void of_isEmptyForShapesTheDriverDoesNotHandle(String sql) {
    assertThat(StatementShape.of(sql, Engine.POSTGRESQL)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "update t set x = ?, updated_at = current_timestamp where id = ?",
        "select * from t where id = ? and n % 2 = 0",
        "select * from t where x is distinct from ?",
        "select * from t order by x nulls last",
        "select * from t where d > current_date - interval '1 day'",
        "select * from t where x is true",
        "select * from t where x ~ 'a.*'",
        "select x, row_number() over (order by y) from t where id = ?"
      })
  void of_acceptsOrdinaryExpressions_whateverTheirNodeTypes(String sql) {
    assertThat(StatementShape.of(sql, Engine.POSTGRESQL)).isPresent();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "select * from a join b on a.id = b.id | 2 tables",
        "select * from t where id = ? for update skip locked | SKIP LOCKED",
        "insert ignore into t (id) values (?) | INSERT IGNORE",
        "insert into t select * from u | 2 tables",
        "update t set (x, y) = (?, ?) where id = ? | set one column at a time",
        "delete from t using (select 1) v where id < 5 | DELETE ... USING",
        "select o.id from t | not this table's",
        "with c as (select 1) select * from c | names no table",
        "select * from t start with id = 1 connect by prior id = parent_id | CONNECT BY",
        "truncate table t | Truncate statement",
      })
  void refusals_sayWhy(String sql, String reason) {
    Outcome<StatementShape> outcome =
        StatementShape.classify(SqlAst.parse(sql, Engine.POSTGRESQL).orElseThrow());
    assertThat(outcome.isPresent()).isFalse();
    assertThat(outcome.reason()).contains(reason.strip());
  }

  @Test
  void whereColumns_areWalkedInFull_howeverLongThePredicate() {
    StringBuilder b = new StringBuilder("select * from t where c0 = ?");
    for (int i = 1; i < 100; i++) {
      b.append(" and c").append(i).append(" = ?");
    }
    assertThat(shape(b.toString()).whereColumns()).hasSize(100);
  }

  @Test
  void whereColumns_areTheColumnsInsideTheWhereClauseOnly() {
    StatementShape s = shape("select id, paid from t where paid = ? and (x + 1) > 2");
    assertThat(s.whereColumns()).containsExactly("paid", "x");
    assertThat(s.grouped()).isFalse(); // parentheses in an expression are not a function
    assertThat(shape("update t set x = x + 1 where id = ?").whereColumns()).containsExactly("id");
  }

  @Test
  void columnsQualifiedByTheTableAliasOrNameAreAccepted() {
    assertThat(shape("select t.id from t").whereColumns()).isEmpty();
    assertThat(shape("select a.id from t a where a.x = ?").whereColumns()).containsExactly("x");
    assertThat(shape("select s.t.id from s.t where s.t.x = ?").whereColumns()).containsExactly("x");
  }
}
