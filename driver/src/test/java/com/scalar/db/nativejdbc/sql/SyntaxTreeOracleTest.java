package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Stream;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.update.Update;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link SqlAst} trusts the parser's public syntax tree to hold every table and placeholder of a
 * statement, and every column of a clause. This checks that trust against the parser's private
 * fields, so a grammar production that builds an object without a tree node is caught here rather
 * than in production.
 */
@SuppressWarnings("IdentityHashMapUsage")
class SyntaxTreeOracleTest {

  static Stream<String> statements() {
    StringBuilder wide = new StringBuilder("select * from t where c0 = ?");
    for (int i = 1; i < 100; i++) {
      wide.append(" or c").append(i).append(" = :p").append(i);
    }
    return Stream.concat(
        SqlAstTest.corpus(),
        Stream.of(
            "select t.x, (select max(u.y) from u where u.id = t.id) from t"
                + " where t.id = ? and exists (select 1 from v where v.k = ?)",
            "select * from a join b on a.id = b.id left join c using (id) where a.x in (?, ?)",
            "select * from t join lateral (select * from u where u.id = t.id) l on true",
            "with c as (select id from u where k = ?) select * from t where id in (select id from c)",
            "update t set x = (select y from u where u.id = ?), z = ? where id = ? and k in (?, ?)",
            "update t set x = lower(:v), y = ? where id = ? and (k = :k or n = abs(z))",
            "insert into t (id, x) select id, x from u where u.id = ?",
            "insert into t (id, x) values (?, ?), (?, ?)",
            "insert into t (id) values (?) on duplicate key update x = x + 1",
            "delete from t using u where t.id = u.id and u.k = ?",
            "delete from t where id = ? and (a = 1 or (b = 2 and c = ?))",
            "merge into t using u on (t.id = u.id) when matched then update set x = u.x",
            "truncate table a, b",
            "select t.*, u.id from t, u where t.id = u.id and t.x = cast(? as int)",
            "select x, row_number() over (partition by y order by z) from t where id = ?::bigint",
            "select case when x > ? then 'p' else 'n' end from t where d between ? and ?",
            "select * from t where x is distinct from ? and y ~ 'a' and z % 2 = 0",
            "select /*+ full(t) */ * from t with (nolock) where id = ?",
            "select * from s.\"Order\" o where o.\"id\" = ? for update",
            "select * from t where id = ?::bigint and k = :k::int and n = -? and m = -1",
            "update t set x = ?::int, y = -? where id = ?::bigint",
            "insert into t (id, x) values (?::int, -?)",
            "select * from t where x = ? order by y limit ? offset ?",
            "select * from t where x = ? offset ? rows fetch first ? rows only",
            "select * from t where x = ? and y = any (?)",
            "select top ? * from t where id = ?",
            "select * from t o where o.id = ? for update of o",
            wide.toString()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("statements")
  void theTreeHoldsEveryTableAndPlaceholder_andEveryColumnOfTheWhereClause(String sql) {
    SqlAst ast = SqlAst.parse(sql).orElseThrow();
    Set<Object> tree = SqlAst.valuesUnder(ast.root());
    Set<Table> qualifiers = Collections.newSetFromMap(new IdentityHashMap<>());
    for (SqlAst.ColumnRef c : ast.columnRefs()) {
      SqlAst.TableRef q = c.qualifier();
      if (q != null) {
        qualifiers.add(q.node());
      }
    }
    int placeholders = 0;
    for (Object o : ReflectiveNodes.reachable(ast.statement())) {
      if (o instanceof Table) {
        assertThat(tree.contains(o) || qualifiers.contains(o))
            .as("table %s is a tree node or a column qualifier", o)
            .isTrue();
      } else if (o instanceof JdbcParameter || o instanceof JdbcNamedParameter) {
        placeholders++;
      } else if (o instanceof Column column) {
        assertThat(isColumnRef(ast, column)).as("column %s is a column reference", o).isTrue();
      }
    }
    assertThat(ast.parameterCount()).as("every placeholder is counted").isEqualTo(placeholders);
    Expression where = whereOf(ast);
    if (where != null) {
      Set<Object> under = SqlAst.valuesUnder(where);
      assertThat(under).as("the WHERE clause has a tree").isNotNull();
      Set<Object> underWhere = under == null ? Set.of() : under;
      for (Object o : ReflectiveNodes.reachable(where)) {
        if (o instanceof Column) {
          assertThat(underWhere.contains(o)).as("column %s under WHERE is a tree node", o).isTrue();
        }
      }
    }
  }

  private static @Nullable Expression whereOf(SqlAst ast) {
    Object s = ast.statement();
    if (s instanceof PlainSelect select) {
      return select.getWhere();
    }
    if (s instanceof Update update) {
      return update.getWhere();
    }
    if (s instanceof Delete delete) {
      return delete.getWhere();
    }
    return null;
  }

  private static boolean isColumnRef(SqlAst ast, Column c) {
    for (SqlAst.ColumnRef r : ast.columnRefs()) {
      if (r.isAmong(Set.of(c))) {
        return true;
      }
    }
    return false;
  }
}
