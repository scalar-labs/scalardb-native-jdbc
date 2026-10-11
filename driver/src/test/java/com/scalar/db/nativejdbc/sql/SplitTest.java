package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The split read's shape analysis and emission (design 4.2). */
class SplitTest {

  private static SqlAst parse(String sql) {
    return SqlAst.parse(sql).orElseThrow();
  }

  private static List<String> splittable(String sql) {
    List<String> out = new ArrayList<>();
    for (SqlAst.TableRef r : parse(sql).splittableRefs()) {
      out.add(r.name());
    }
    return out;
  }

  /** Two copies: each table becomes {@code (select * from <name><copy>)}. */
  private static SqlAst.@Nullable SplitText twoCopies(
      SqlAst ast, Map<SqlAst.TableRef, List<String>> cols) {
    List<SqlAst.Naming> copies = new ArrayList<>();
    for (int c = 0; c < 2; c++) {
      int copy = c;
      copies.add(r -> "(select * from " + r.name() + copy + ")");
    }
    return ast.emitSplit(copies, cols);
  }

  private static SqlAst.SplitText split(SqlAst ast, Map<SqlAst.TableRef, List<String>> cols) {
    return Objects.requireNonNull(twoCopies(ast, cols));
  }

  @Test
  void splittableRefs_areTheInnerJoinedTablesOfTheTopLevelFrom() {
    assertThat(splittable("select * from a join b on a.id = b.aid where a.x = ?"))
        .containsExactly("a", "b");
    assertThat(splittable("select * from a, b cross join c")).containsExactly("a", "b", "c");
    // the preserved side of a LEFT JOIN, and an inner join after it
    assertThat(splittable("select * from a left join b on a.id = b.aid join c on c.id = a.cid"))
        .containsExactly("a", "c");
    // a subquery's table would make an outer row match in two copies
    assertThat(splittable("select * from a where a.id in (select aid from b)"))
        .containsExactly("a");
    assertThat(splittable("select a.x, upper(a.y) from a order by a.x limit 3"))
        .containsExactly("a");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "select * from a right join b on a.id = b.aid",
        "select * from a full join b on a.id = b.aid",
        "select count(*) from a",
        "select max(x) from a",
        "select x from a group by x",
        "select x, row_number() over (order by x) from a",
        "select my_fn(x) from a",
        "select s.upper(x) from a",
        "select x from a order by count(*)",
        "with w as (select 1 as n) select * from a, w",
        "select * from a union all select * from b",
        "select * from a where id = :id",
        "select distinct on (x) x from a"
      })
  void splittableRefs_areNone_forShapesTheSplitDoesNotHandle(String sql) {
    assertThat(SqlAst.parse(sql).map(SqlAst::splittableRefs).orElse(List.of())).isEmpty();
  }

  @Test
  void splittableRefs_areNone_forOraclesRownum() {
    SqlAst ast = SqlAst.parse("SELECT x FROM a WHERE ROWNUM <= 3", Engine.ORACLE).orElseThrow();
    assertThat(ast.splittableRefs()).isEmpty();
  }

  @Test
  void emitSplit_joinsTheCopies_andRepeatsThePlaceholders() {
    SqlAst ast = parse("select a.x, b.y from a join b on a.id = b.aid where a.k = ? and b.z > ?");
    SqlAst.SplitText split = split(ast, Map.of());
    assertThat(split.sql())
        .isEqualTo(
            "select a.x, b.y from (select * from a0) a join (select * from b0) b on a.id = b.aid"
                + " where a.k = ? and b.z > ? UNION ALL select a.x, b.y from (select * from a1) a"
                + " join (select * from b1) b on a.id = b.aid where a.k = ? and b.z > ?");
    assertThat(split.parameterSources()).containsExactly(1, 2, 1, 2);
  }

  @Test
  void emitSplit_aliasesEveryReplacedTable() {
    SqlAst ast = parse("select x from s.a where s.a.id = 1");
    assertThat(split(ast, Map.of()).sql())
        .isEqualTo(
            "select x from (select * from a0) a where a.id = 1 UNION ALL select x from (select *"
                + " from a1) a where a.id = 1");
  }

  @Test
  void emitSplit_appliesOrderByAndTheLimitOnTop_withTheLabelsRestored() {
    SqlAst ast =
        parse(
            "select a.x, b.y as yy from a join b on a.id = b.aid where a.k = ?"
                + " order by a.z desc, yy, 1 limit ?");
    SqlAst.SplitText split = split(ast, Map.of());
    assertThat(split.sql())
        .isEqualTo(
            "SELECT v.c1 AS x, v.c2 AS yy FROM (SELECT a.x AS c1, b.y AS c2, a.z AS s1 from"
                + " (select * from a0) a join (select * from b0) b on a.id = b.aid where a.k = ?"
                + " UNION ALL SELECT a.x AS c1, b.y AS c2, a.z AS s1 from (select * from a1) a"
                + " join (select * from b1) b on a.id = b.aid where a.k = ?) v order by v.s1"
                + " desc, yy, 1 limit ?");
    assertThat(split.parameterSources()).containsExactly(1, 1, 2);
  }

  @Test
  void emitSplit_keepsDistinctAndTopOnTop_withTheirPlaceholdersFirst() {
    SqlAst ast =
        SqlAst.parse("SELECT DISTINCT TOP (?) a.x FROM a WHERE a.k = ?", Engine.SQLSERVER)
            .orElseThrow();
    SqlAst.SplitText split = split(ast, Map.of());
    assertThat(split.sql())
        .isEqualTo(
            "SELECT DISTINCT TOP(?) v.c1 AS x FROM (SELECT a.x AS c1 FROM (select * from a0) a"
                + " WHERE a.k = ? UNION ALL SELECT a.x AS c1 FROM (select * from a1) a WHERE a.k"
                + " = ?) v");
    assertThat(split.parameterSources()).containsExactly(1, 2, 2);
  }

  @Test
  void emitSplit_expandsAStar_overTablesWhoseColumnsAreKnown() {
    SqlAst ast = parse("select *, b.* from a join b on a.id = b.aid order by 1");
    Map<SqlAst.TableRef, List<String>> cols =
        Map.of(ast.tableRefs().get(0), List.of("id", "x"), ast.tableRefs().get(1), List.of("aid"));
    assertThat(split(ast, cols).sql())
        .startsWith(
            "SELECT v.c1 AS \"id\", v.c2 AS \"x\", v.c3 AS \"aid\", v.c4 AS \"aid\" FROM (SELECT"
                + " a.\"id\" AS c1, a.\"x\" AS c2, b.\"aid\" AS c3, b.\"aid\" AS c4 from");
    // a table whose columns are not known
    assertThat(twoCopies(ast, Map.of(ast.tableRefs().get(0), List.of("id")))).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // the engines label an unaliased expression differently
        "select a.x + 1 from a order by 1",
        // on top, DISTINCT may sort only by what it selects
        "select distinct a.x from a order by a.y",
        // * shows a USING join's columns once
        "select * from a join b using (id) order by 1",
        // the copies would be joined after the terminator
        "select * from a;"
      })
  void emitSplit_returnsNull_whenTheTopCannotBeRebuilt(String sql) {
    SqlAst ast = parse(sql);
    Map<SqlAst.TableRef, List<String>> cols = new HashMap<>(); // a TableRef equals only itself
    for (SqlAst.TableRef r : ast.tableRefs()) {
      cols.put(r, List.of("id"));
    }
    assertThat(twoCopies(ast, cols)).isNull();
  }
}
