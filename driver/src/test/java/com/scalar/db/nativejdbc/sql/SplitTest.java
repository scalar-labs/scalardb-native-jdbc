package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.ArrayList;
import java.util.Collections;
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
        "select string_agg(x, ',') from a",
        "select x from a group by upper(x)",
        "select x, y from a group by x",
        "select (select max(y) from b) from a group by x",
        "select count(*) over () from a",
        "select max(count(*)) from a",
        "select * from a group by x",
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        // an old-style outer join: b is a nullable side
        "SELECT a.x, b.y FROM a, b WHERE a.id = b.aid(+)",
        "SELECT a.x FROM a JOIN b ON a.id = b.aid (+)",
        // a sequence is not allowed in a UNION
        "SELECT s.NEXTVAL, x FROM a",
        "SELECT s.CURRVAL, x FROM a"
      })
  void splittableRefs_areNone_forOracleShapes(String sql) {
    assertThat(SqlAst.parse(sql, Engine.ORACLE).orElseThrow().splittableRefs()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "SELECT HIGH_PRIORITY x FROM a",
        "SELECT SQL_NO_CACHE x FROM a",
        "SELECT SQL_BUFFER_RESULT x FROM a"
      })
  void splittableRefs_areNone_forMySqlModifiersAUnionRefuses(String sql) {
    assertThat(SqlAst.parse(sql, Engine.MYSQL).orElseThrow().splittableRefs()).isEmpty();
    assertThat(SqlAst.parse(sql, Engine.MARIADB).orElseThrow().splittableRefs()).isEmpty();
  }

  @Test
  void splittableRefs_areNone_forASequenceOnSqlServer() {
    SqlAst ast = SqlAst.parse("SELECT NEXT VALUE FOR s, x FROM a", Engine.SQLSERVER).orElseThrow();
    assertThat(ast.splittableRefs()).isEmpty();
  }

  @Test
  void bracketQuotedNames_parseOnSqlServer() {
    SqlAst ast =
        SqlAst.parse("SELECT [x] FROM rd.[made] WHERE [id] = ?", Engine.SQLSERVER).orElseThrow();
    assertThat(ast.splittableRefs()).hasSize(1);
    SqlAst.SplitText split =
        ast.emitSplit(List.of(r -> "(select * from t0)", r -> "(select * from t1)"), Map.of());
    assertThat(Objects.requireNonNull(split).sql())
        .isEqualTo(
            "SELECT [x] FROM (select * from t0) [made] WHERE [id] = ? UNION ALL SELECT [x] FROM"
                + " (select * from t1) [made] WHERE [id] = ?");
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
  void emitSplit_endsALineComment_beforeTheNextCopy() {
    SqlAst ast = parse("select x from a where id = ? -- note");
    SqlAst.SplitText split = split(ast, Map.of());
    assertThat(split.sql())
        .isEqualTo(
            "select x from (select * from a0) a where id = ? -- note\n"
                + " UNION ALL select x from (select * from a1) a where id = ? -- note\n");
    assertThat(split.parameterSources()).containsExactly(1, 1);
    // MySQL's # comment, which the parser reads as an operator: nothing is re-emitted
    SqlAst my =
        SqlAst.parse("select x from a where id = 1 # c\nand y = 2", Engine.MYSQL).orElseThrow();
    assertThat(twoCopies(my, Map.of())).isNull();
    assertThat(my.joinTokens(r -> "rc_" + r.name())).isNull();
    // before the first item of a statement lifted on top: in every copy, closed
    SqlAst top = parse("select -- note\n a.x from a order by a.x");
    assertThat(split(top, Map.of()).sql())
        .isEqualTo(
            "SELECT v.c1 AS x FROM (SELECT -- note\na.x AS c1, a.x AS s1 from (select * from a0) a"
                + " UNION ALL SELECT -- note\na.x AS c1, a.x AS s1 from (select * from a1) a) v"
                + " order by v.s1");
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
    // each copy sorts and stops after the rows the limit takes
    assertThat(split.sql())
        .isEqualTo(
            "SELECT v.c1 AS x, v.c2 AS yy FROM (SELECT * FROM(SELECT a.x AS c1, b.y AS c2, a.z"
                + " AS s1 from (select * from a0) a join (select * from b0) b on a.id = b.aid"
                + " where a.k = ? ORDER BY s1 DESC, c2, c1 LIMIT ?) p1 UNION ALL SELECT * FROM("
                + "SELECT a.x AS c1, b.y AS c2, a.z AS s1 from (select * from a1) a join"
                + " (select * from b1) b on a.id = b.aid where a.k = ? ORDER BY s1 DESC, c2, c1"
                + " LIMIT ?) p2) v order by v.s1 desc, yy, 1 limit ?");
    assertThat(split.parameterSources()).containsExactly(1, 2, 1, 2, 2);
  }

  @Test
  void emitSplit_limitsEachCopy_toTheOffsetAndTheCount() {
    SqlAst pg = parse("select a.x from a order by a.x limit 20 offset 100");
    assertThat(pg.limitsEachCopy()).isTrue();
    assertThat(split(pg, Map.of()).sql()).contains("ORDER BY s1 LIMIT 120) p1");
    SqlAst oracle =
        SqlAst.parse("SELECT a.x FROM a ORDER BY 1 DESC FETCH FIRST 5 ROWS ONLY", Engine.ORACLE)
            .orElseThrow();
    assertThat(split(oracle, Map.of()).sql())
        .contains(
            "SELECT * FROM(SELECT a.x AS c1 FROM (select * from a0) a ORDER BY c1 DESC)"
                + " WHERE ROWNUM <= 5 UNION ALL");
    // not when the copies cannot know how many rows the top takes from them
    for (String sql :
        List.of(
            "select a.x from a order by a.x limit ? offset 3",
            "select distinct a.x from a order by a.x limit 3",
            "select a.x from a limit 3",
            "select count(*) from a group by a.x order by 1 limit 3")) {
      assertThat(parse(sql).limitsEachCopy()).as(sql).isFalse();
    }
    assertThat(
            SqlAst.parse("SELECT TOP 3 a.x FROM a ORDER BY a.x", Engine.SQLSERVER)
                .orElseThrow()
                .limitsEachCopy())
        .isFalse();
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

  @Test
  void emitSplit_keepsLabelsAsWritten() {
    SqlAst ast = parse("select a.x as \"a  b\" from a order by 1");
    assertThat(split(ast, Map.of()).sql()).startsWith("SELECT v.c1 AS \"a  b\" FROM (");
    // a reserved word may follow a qualifier on MySQL, but not AS
    SqlAst my =
        SqlAst.parse("SELECT t.rank FROM a t ORDER BY t.id LIMIT 5", Engine.MYSQL).orElseThrow();
    assertThat(split(my, Map.of()).sql()).startsWith("SELECT v.c1 AS `rank` FROM (");
  }

  private static @Nullable List<String> read(String sql, int ref) {
    SqlAst ast = parse(sql);
    java.util.Set<String> names = ast.columnsReadFrom(ast.tableRefs().get(ref));
    return names == null ? null : names.stream().sorted().toList();
  }

  @Test
  void columnsReadFrom_areItsQualifiedAndEveryUnqualifiedColumn() {
    String join = "select a.x, y from a join b on a.id = b.aid where \"Z\" = 1 order by b.w";
    assertThat(read(join, 0)).containsExactly("id", "x", "y", "z");
    assertThat(read(join, 1)).containsExactly("aid", "w", "y", "z");
    // a correlated subquery's columns count too
    assertThat(read("select 1 from a t where exists (select 1 from c where c.k = t.k2)", 0))
        .containsExactly("k2");
    assertThat(read("select count(*) from a", 0)).isEmpty();
    assertThat(read("select * from a", 0)).isNull();
    assertThat(read("select a.* , b.x from a join b on a.id = b.aid", 0)).isNull();
    assertThat(read("select a.* , b.x from a join b on a.id = b.aid", 1))
        .containsExactly("aid", "x");
    assertThat(read("select x from a join b using (id)", 0)).isNull();
  }

  private static boolean reached(String sql, int ref, String... leading) {
    SqlAst ast = parse(sql);
    return ast.reachedThrough(ast.tableRefs().get(ref), java.util.Set.of(leading));
  }

  @Test
  void reachedThrough_aComparisonOnALeadingColumn_withNothingOfTheTable() {
    assertThat(reached("select * from c where id = ?", 0, "id")).isTrue();
    assertThat(reached("select * from c where region between 1 and ? and x > 0", 0, "region"))
        .isTrue();
    assertThat(reached("select * from c where id in (1, 2)", 0, "id")).isTrue();
    assertThat(reached("select * from c where (x > 0 and (id = ?))", 0, "id")).isTrue();
    // through a join from another table, either side
    String join = "select * from c join o on o.cid = c.id where c.id = ?";
    assertThat(reached(join, 0, "id")).isTrue();
    assertThat(reached(join, 1, "cid")).isTrue();
    assertThat(reached(join, 1, "id")).isFalse();
    // not a comparison the index serves, or under OR, or against the table itself
    assertThat(reached("select * from c where id <> ?", 0, "id")).isFalse();
    assertThat(reached("select * from c where id = ? or x = 1", 0, "id")).isFalse();
    assertThat(reached("select * from c where id = c.x + 1", 0, "id")).isFalse();
    assertThat(reached("select * from c where id not in (1, 2)", 0, "id")).isFalse();
    assertThat(reached("select * from c where name = ?", 0, "id")).isFalse();
    // an unqualified column of a join could belong to either table
    assertThat(reached("select * from c join o on o.cid = c.k where id = 1", 0, "id")).isFalse();
  }

  @Test
  void reviewFindings_fallBackOrReadEnough() {
    // 108: a count beyond a long limits no copy
    assertThat(
            SqlAst.parse(
                    "SELECT a.x FROM a ORDER BY a.x LIMIT 5, 18446744073709551615", Engine.MYSQL)
                .orElseThrow()
                .limitsEachCopy())
        .isFalse();
    assertThat(
            parse("select a.x from a order by a.x limit 9223372036854775807 offset 1")
                .limitsEachCopy())
        .isFalse();
    // 109: an item's name inside an ORDER BY expression, t.* inside an aggregate
    assertThat(splittable("select t.x, count(*) from t group by t.x order by lower(x)")).isEmpty();
    assertThat(splittable("select t.x, count(*) from t group by t.x order by x"))
        .containsExactly("t");
    assertThat(splittable("select count(t.*) from t")).isEmpty();
    // 109: a forced label holding a placeholder reads through the view
    SqlAst my =
        SqlAst.parse("SELECT SUM(CASE WHEN a.x = ? THEN 1 ELSE 0 END) FROM a", Engine.MYSQL)
            .orElseThrow();
    assertThat(twoCopies(my, Map.of())).isNull();
    // 110: a whole-row reference reads every column; a doubled quote is one
    assertThat(read("select t from t where id = 1", 0)).isNull();
    assertThat(read("select \"a\"\"b\" from t", 0)).containsExactly("a\"b");
  }

  @Test
  void splittableRefs_includeGroupingStatements() {
    assertThat(splittable("select count(*) from a")).containsExactly("a");
    assertThat(splittable("select a.x, max(b.y) from a join b on a.id = b.aid group by a.x"))
        .containsExactly("a", "b");
    assertThat(splittable("select x from a group by x having count(*) > ? order by count(*)"))
        .containsExactly("a");
  }

  @Test
  void emitSplit_appliesTheAggregatesOnTop_onPostgreSql() {
    SqlAst ast =
        parse(
            "select a.x, count(*), sum(a.y) as s from a where a.k = ? group by a.x"
                + " having max(a.y) > ? order by 2 desc");
    SqlAst.SplitText split = split(ast, Map.of());
    assertThat(split.sql())
        .isEqualTo(
            "SELECT v.c1 AS x, count(*), sum(v.c2) as s FROM (SELECT a.x AS c1, a.y AS c2 from"
                + " (select * from a0) a where a.k = ? UNION ALL SELECT a.x AS c1, a.y AS c2 from"
                + " (select * from a1) a where a.k = ?) v group by v.c1 having max(v.c2) > ?"
                + " order by 2 desc");
    assertThat(split.parameterSources()).containsExactly(1, 1, 2);
    // nothing but a count: each copy still yields its rows
    assertThat(split(parse("select count(*) from a"), Map.of()).sql())
        .isEqualTo(
            "SELECT count(*) FROM (SELECT 1 AS c1 from (select * from a0) a UNION ALL SELECT 1"
                + " AS c1 from (select * from a1) a) v");
  }

  @Test
  void emitSplit_combinesPartialAggregates_onTheOtherEngines() {
    SqlAst ast =
        SqlAst.parse(
                "SELECT a.x, COUNT(*), AVG(a.y) AS m FROM a WHERE a.k = ? GROUP BY a.x"
                    + " HAVING MIN(a.y) > ? ORDER BY a.x",
                Engine.MYSQL)
            .orElseThrow();
    SqlAst.SplitText split = split(ast, Map.of());
    assertThat(split.sql())
        .isEqualTo(
            "SELECT v.g1 AS `x`, CAST(SUM(v.a1) AS SIGNED) AS `COUNT(*)`, SUM(v.a2) /"
                + " NULLIF(SUM(v.a3), 0) AS m FROM (SELECT a.x AS g1, COUNT(*) AS a1, SUM(a.y)"
                + " AS a2, COUNT(a.y) AS a3, MIN(a.y) AS a4 FROM (select * from a0) a WHERE a.k ="
                + " ? GROUP BY a.x UNION ALL SELECT a.x AS g1, COUNT(*) AS a1, SUM(a.y) AS a2,"
                + " COUNT(a.y) AS a3, MIN(a.y) AS a4 FROM (select * from a1) a WHERE a.k = ?"
                + " GROUP BY a.x) v GROUP BY v.g1 HAVING MIN(v.a4) > ? ORDER BY v.g1");
    assertThat(split.parameterSources()).containsExactly(1, 1, 2);
    // the label is the expression as written
    SqlAst oracle = SqlAst.parse("SELECT count( a.y ) FROM a", Engine.ORACLE).orElseThrow();
    assertThat(split(oracle, Map.of()).sql()).startsWith("SELECT SUM(v.a1) AS \"COUNT(A.Y)\" FROM");
    SqlAst my = SqlAst.parse("SELECT count( a.y ) FROM a", Engine.MARIADB).orElseThrow();
    assertThat(split(my, Map.of()).sql())
        .startsWith("SELECT CAST(SUM(v.a1) AS SIGNED) AS `count( a.y )` FROM");
    // DISTINCT does not decompose: on top
    SqlAst distinct =
        SqlAst.parse("SELECT COUNT(DISTINCT a.y) AS n FROM a", Engine.SQLSERVER).orElseThrow();
    assertThat(split(distinct, Map.of()).sql())
        .isEqualTo(
            "SELECT COUNT(DISTINCT v.c1) AS n FROM (SELECT a.y AS c1 FROM (select * from a0) a"
                + " UNION ALL SELECT a.y AS c1 FROM (select * from a1) a) v");
  }

  @Test
  void emitSplit_returnsNull_whenThePlaceholdersExceedTheEnginesLimit() {
    // two copies: 1050 placeholders emit 2100, SQL Server's limit
    String in = "SELECT x FROM a WHERE id IN (" + String.join(",", Collections.nCopies(1050, "?"));
    assertThat(twoCopies(SqlAst.parse(in + ")", Engine.SQLSERVER).orElseThrow(), Map.of()))
        .isNotNull();
    assertThat(twoCopies(SqlAst.parse(in + ",?)", Engine.SQLSERVER).orElseThrow(), Map.of()))
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // on top the two items are distinct columns, so the name is ambiguous
        "select x, x from a order by x",
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
