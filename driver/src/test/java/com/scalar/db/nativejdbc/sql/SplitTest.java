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

  @Test
  void emitSplit_keepsLabelsAsWritten() {
    SqlAst ast = parse("select a.x as \"a  b\" from a order by 1");
    assertThat(split(ast, Map.of()).sql()).startsWith("SELECT v.c1 AS \"a  b\" FROM (");
    // a reserved word may follow a qualifier on MySQL, but not AS
    SqlAst my =
        SqlAst.parse("SELECT t.rank FROM a t ORDER BY t.id LIMIT 5", Engine.MYSQL).orElseThrow();
    assertThat(split(my, Map.of()).sql()).startsWith("SELECT v.c1 AS `rank` FROM (");
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
