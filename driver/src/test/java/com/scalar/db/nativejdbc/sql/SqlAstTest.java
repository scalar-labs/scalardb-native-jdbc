package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SqlAstTest {

  private static SqlAst parse(String sql) {
    return SqlAst.parse(sql).orElseThrow();
  }

  @Test
  void kind_isDecidedByTheTree_notTheFirstWord() {
    assertThat(parse("/* hint */ select 1 from t").kind()).isEqualTo(SqlAst.Kind.SELECT);
    assertThat(parse("-- note\nupdate t set x = 1").kind()).isEqualTo(SqlAst.Kind.DML);
    assertThat(parse("with c as (select 1) select * from c").kind()).isEqualTo(SqlAst.Kind.SELECT);
    assertThat(parse("truncate table t").kind()).isEqualTo(SqlAst.Kind.TRUNCATE);
    assertThat(parse("create table t (id int)").kind()).isEqualTo(SqlAst.Kind.DDL);
  }

  @Test
  void parse_returnsEmptyForWhatTheParserCannotRead() {
    assertThat(SqlAst.parse("select from where")).isEmpty();
    assertThat(SqlAst.parse("")).isEmpty();
  }

  @Test
  void tableRefs_findEveryTable_includingJoinsSubqueriesAndCtes() {
    SqlAst ast =
        parse(
            "with c as (select id from s.a) select * from b join s.\"C\" x on b.id = x.id"
                + " where b.id in (select id from d) and exists (select 1 from c)");
    assertThat(ast.tableRefs().stream().map(SqlAst.TableRef::fullyQualifiedName))
        .containsExactly("s.a", "b", "s.\"C\"", "d");
    assertThat(ast.cteRefs().stream().map(SqlAst.TableRef::name)).containsExactly("c");
    assertThat(ast.cteNames()).containsExactly("c");
  }

  @Test
  void tableRef_exposesQualifierQuotingAndAlias() {
    SqlAst.TableRef t = parse("select x.id from s.\"Order\" x").tableRefs().get(0);
    assertThat(t.schema()).isEqualTo("s");
    assertThat(t.name()).isEqualTo("\"Order\"");
    assertThat(t.resolvedName()).isEqualTo("Order");
    assertThat(t.foldedName()).isEqualTo("order");
    assertThat(t.quoted()).isTrue();
    assertThat(t.partCount()).isEqualTo(2);
    assertThat(t.alias()).isEqualTo("x");

    SqlAst.TableRef u = parse("select * from Orders").tableRefs().get(0);
    assertThat(u.resolvedName()).isEqualTo("orders");
    assertThat(u.schema()).isNull();
    assertThat(u.hasAlias()).isFalse();
  }

  @Test
  void columnRefs_includeQualifiersAndStar() {
    SqlAst ast = parse("select t.*, u.id, qty from t, u where t.id = u.id");
    assertThat(ast.columnRefs().stream().map(SqlAst.ColumnRef::toString))
        .containsExactly("u.id", "qty", "t.id", "u.id", "t.*");
    assertThat(ast.columnRefs().get(4).isAllColumnsOfTable()).isTrue();
  }

  static Stream<String> corpus() {
    return Stream.of(
        "select   count(*)\n\tfrom  s.\"order\"  /* total */",
        "select\n  *\nfrom\n  orders\nwhere\n  id = ?",
        "/* load Order */ select * from orders o\n  where o.id = ?  -- trailing comment",
        "select /* middle */ * from orders where id = ?",
        "select * from orders -- line comment\nwhere id = ?",
        "select * from orders where note = 'orders' and memo = 'a -- b' and m2 = 'c /* d */ e'",
        "select * from orders where note = 'it''s a test' and qty = -1.5",
        "select * from orders where d = {d '2020-01-01'} and memo like '%a_b%'",
        "select a||b from orders where id = ?",
        "select cast(qty as varchar) from orders where qty <= 10 and qty <> 3",
        "insert into orders (id, qty) values (1, 2), (3, 4)",
        "select \"order\".\"limit\" from s.\"order\" where \"order\".id = ?",
        "SELECT * FROM S.ORDERS WHERE ID = ?",
        "update orders set qty = qty + 1, note = :note where id = ? and version = ?",
        "delete from orders where id in (?, ?, ?)",
        "select id, sum(qty) from orders where x between ? and ? group by id having sum(qty) > 1"
            + " order by id desc limit 10 offset 5",
        "select * from orders where x is null or (y = 1 and not z = 2)",
        "select case when x > 0 then 'p' else 'n' end from orders",
        "select * from orders for update");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("corpus")
  void joinTokens_preservesMeaning(String sql) throws Exception {
    SqlAst ast = parse(sql);
    String joined = ast.joinTokens();
    assertThat(joined).isNotNull();
    assertThat(CCJSqlParserUtil.parse(joined).toString())
        .isEqualTo(CCJSqlParserUtil.parse(sql).toString());
  }

  @Test
  void joinTokens_keepsCommentsCaseAndQuotingAndNormalisesWhitespace() {
    SqlAst ast = parse("/* a */ SELECT   \"Order\".id\nFROM s.\"Order\" -- tail\n WHERE id = ?");
    assertThat(ast.joinTokens())
        .isEqualTo("/* a */ SELECT \"Order\".id FROM s.\"Order\" -- tail\nWHERE id = ?");
  }

  @Test
  void joinTokens_replacesATable_andAddsAnAliasWhenColumnsNameIt() {
    SqlAst ast = parse("select \"order\".id, qty from s.\"order\" where \"order\".id = ?");
    String out = ast.joinTokens(ref -> ref.schema() != null ? "rc_order" : null);
    assertThat(out).isEqualTo("select \"order\".id, qty from rc_order where \"order\".id = ?");

    String aliased =
        ast.joinTokens(
            new SqlAst.Naming() {
              @Override
              public String replacementFor(SqlAst.TableRef ref) {
                return "rc_order";
              }

              @Override
              public String aliasFor(SqlAst.TableRef ref) {
                return "\"order\"";
              }
            });
    assertThat(aliased)
        .isEqualTo("select \"order\".id, qty from rc_order AS \"order\" where \"order\".id = ?");
  }

  @Test
  void joinTokens_leavesAnAliasedTableAndItsColumnsAlone() {
    SqlAst ast = parse("select o.id from s.orders o where o.id = ?");
    assertThat(ast.joinTokens(ref -> "rc_orders"))
        .isEqualTo("select o.id from rc_orders o where o.id = ?");
  }

  @Test
  void joinTokens_renamesColumns_forTheBeforeImagePredicate() {
    SqlAst ast = parse("select * from t where x = ? and t.y > 1 and id = 3");
    String out =
        ast.joinTokens(
            new SqlAst.Naming() {
              @Override
              public @Nullable String replacementFor(SqlAst.TableRef ref) {
                return null;
              }

              @Override
              public @Nullable String columnReplacementFor(SqlAst.ColumnRef ref) {
                return ref.resolvedName().equals("id") ? null : "before_" + ref.resolvedName();
              }
            });
    assertThat(out).isEqualTo("select * from t where before_x = ? and t.before_y > 1 and id = 3");
  }

  @Test
  void joinTokens_neverTouchesLiteralsOrPlaceholders() {
    SqlAst ast = parse("select * from t where note = 't' and x = ? and y = :y");
    assertThat(ast.joinTokens(ref -> "v_t"))
        .isEqualTo("select * from v_t where note = 't' and x = ? and y = :y");
    assertThat(ast.parameterCount()).isEqualTo(2);
  }

  @Test
  void joinTokens_replacesEveryReferenceOfATable_orNone() {
    SqlAst ast = parse("select * from t a join t b on a.id = b.id");
    assertThat(ast.joinTokens(ref -> "v_t"))
        .isEqualTo("select * from v_t a join v_t b on a.id = b.id");
  }

  @Test
  void unknownNodes_reportATableNodeTheWalkMissed_orAnUntaughtType() {
    assertThat(parse("select * from t where id = ?").unknownNodes()).isEmpty();
    assertThat(parse("select * from t").unknownNodes()).isEmpty();
    List<String> merge =
        parse("merge into t using s on t.id = s.id when matched then update set x = s.x")
            .unknownNodes();
    assertThat(merge).anyMatch(u -> u.startsWith("type:"));
  }

  @Test
  void identifiers_foldAndResolveLikePostgresAndMySql() {
    assertThat(SqlAst.resolve("Orders")).isEqualTo("orders");
    assertThat(SqlAst.resolve("\"Orders\"")).isEqualTo("Orders");
    assertThat(SqlAst.resolve("`Orders`")).isEqualTo("Orders");
    assertThat(SqlAst.resolve("[Orders]")).isEqualTo("Orders");
    assertThat(SqlAst.isQuoted("\"x\"")).isTrue();
    assertThat(SqlAst.isQuoted("x")).isFalse();
  }
}
