package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.sf.jsqlparser.statement.select.ForMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StatementShapeTest {

  private static StatementShape shape(String sql) {
    return StatementShape.of(sql).orElseThrow();
  }

  @Test
  void select_byKey() {
    StatementShape s =
        shape(
            "SELECT \"t\".\"id\" AS \"id\", \"t\".\"x\" AS \"x\" FROM \"t\" WHERE \"t\".\"id\" = ?");
    assertThat(s.kind()).isEqualTo(StatementShape.Kind.SELECT);
    assertThat(s.table().resolvedName()).isEqualTo("t");
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
    assertThat(s.table().resolvedSchema()).isEqualTo("s");
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
    assertThat(StatementShape.of(sql)).isEmpty();
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
