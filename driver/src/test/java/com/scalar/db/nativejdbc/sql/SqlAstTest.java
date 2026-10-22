package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.List;
import java.util.stream.Stream;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.ForMode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlAstTest {

  private static SqlAst parse(String sql) {
    return SqlAst.parse(sql).orElseThrow();
  }

  @Test
  void withoutSchemaQualifiers_qualifiesTheTargetsColumnsByItsNameAlone() {
    // as Spring Data JDBC writes an UPDATE of an entity with a schema (milestone 9c)
    String sql =
        "UPDATE \"s\".\"item\" SET \"qty\" = ? WHERE \"s\".\"item\".\"id\" = ? AND"
            + " \"s\".\"item\".\"version\" = ?";
    SqlAst ast = parse(sql);
    assertThat(ast.withoutSchemaQualifiers(ast.tableRefs().get(0), sql))
        .isEqualTo(
            "UPDATE \"s\".\"item\" SET \"qty\" = ? WHERE \"item\".\"id\" = ? AND"
                + " \"item\".\"version\" = ?");
    // another schema's table of the name, and no qualifier at all, stay as they are
    String other = "DELETE FROM s.item WHERE t.item.id = 1 AND id = 2";
    SqlAst o = parse(other);
    assertThat(o.withoutSchemaQualifiers(o.tableRefs().get(0), other)).isEqualTo(other);
  }

  /** The sources as "table.column", "-" for none; columns of c and o known, of r not (todo 304). */
  private static @Nullable List<String> sources(String sql) {
    SqlAst ast = parse(sql);
    List<SqlAst.@Nullable ColumnSource> out =
        ast.selectSources(
            r ->
                r.name().equals("c")
                    ? List.of("id", "vip")
                    : r.name().equals("o") ? List.of("id", "vip", "total") : null);
    return out == null
        ? null
        : out.stream().map(c -> c == null ? "-" : c.table().name() + "." + c.column()).toList();
  }

  @Test
  void selectSources_namesTheTableColumnEachReturnedColumnPlainlyIs() {
    assertThat(sources("SELECT * FROM c")).containsExactly("c.id", "c.vip");
    assertThat(sources("SELECT x.vip AS v, total, VIP + 0, c.* FROM c x JOIN o c ON true"))
        .containsExactly("c.vip", "o.total", "-", "o.id", "o.vip", "o.total");
    // ambiguous, or a table whose columns are unknown may hold the name
    assertThat(sources("SELECT vip, c.vip FROM c JOIN o ON true")).containsExactly("-", "c.vip");
    assertThat(sources("SELECT vip, c.vip FROM c JOIN r ON true")).containsExactly("-", "c.vip");
    // the columns cannot be counted
    assertThat(sources("SELECT * FROM c JOIN r ON true")).isNull();
    assertThat(sources("SELECT vip FROM c UNION ALL SELECT vip FROM o")).isNull();
    assertThat(sources("SELECT * FROM c JOIN o USING (id)")).isNull();
    assertThat(sources("SELECT * FROM (SELECT vip FROM c) d")).isNull();
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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "update plain /*! , scalar_t */ set y = 1 where id = 1", // MySQL executes the comment
        "select 1 /*!50000 + 1 */ from t",
        "delete from scalar_t where id = 1 --1", // "- -1" on MySQL and MariaDB
        "select * from t where x = 1 --(select max(id) from u)",
        "select * from plain /* /* */ where x in (select id from scalar_t) -- */", // not nested
      })
  void commentFormsMySqlExecutes_areRefusedThere_andAreCommentsOnPostgresqlAndSqlServer(
      String sql) {
    assertThat(SqlAst.analyze(sql, Engine.MYSQL, false).isPresent()).isFalse();
    assertThat(SqlAst.analyze(sql, Engine.MARIADB, false).isPresent()).isFalse();
    assertThat(SqlAst.parse(sql, Engine.POSTGRESQL)).isPresent();
    assertThat(SqlAst.parse(sql, Engine.SQLSERVER)).isPresent();
  }

  @Test
  void bothMySqlCommentForms_areRefusedOnBothEngines() {
    // a mysql URL may be served by a MariaDB server, which runs /*M! too
    String sql = "update plain_t /*M!100000 , ns.scalar_t */ set plain_t.a = 1";
    assertThat(SqlAst.analyze(sql, Engine.MARIADB, false).isPresent()).isFalse();
    assertThat(SqlAst.analyze(sql, Engine.MYSQL, false).isPresent()).isFalse();
    assertThat(SqlAst.parse(sql, Engine.POSTGRESQL)).isPresent(); // an ordinary comment there
  }

  @Test
  void refusalReasons_neverQuoteTheStatementsText() {
    String reason =
        SqlAst.analyze("update users set password = 'S3cr3t-pw' 'x'", Engine.POSTGRESQL, true)
            .reason();
    assertThat(reason).doesNotContain("S3cr3t").doesNotContain("'x'").contains("line 1");
    assertThat(SqlAst.analyze("select 1 /*! secret text */ from t", Engine.MYSQL, false).reason())
        .doesNotContain("secret");
  }

  @Test
  void oracle_endsANestedCommentEarly_butReadsItsOwnHintsAndDashComments() {
    assertThat(
            SqlAst.parse(
                "select * from plain /* /* */ where x in (select id from u) -- */", Engine.ORACLE))
        .isEmpty();
    assertThat(SqlAst.parse("select --+ index(t i)\n * from t where id = ?", Engine.ORACLE))
        .isPresent();
    assertThat(SqlAst.parse("select * from t where id = 1 --1", Engine.ORACLE)).isPresent();
    assertThat(SqlAst.parse("select 1 /*! + 1 */ from t", Engine.ORACLE)).isPresent();
  }

  @Test
  void refusals_sayWhy() {
    assertThat(SqlAst.analyze("select 1; select 2", Engine.POSTGRESQL, true).reason())
        .contains("2 statements");
    assertThat(SqlAst.analyze("select from where", Engine.POSTGRESQL, true).reason())
        .startsWith("the statement cannot be parsed");
    assertThat(SqlAst.analyze("delete from t where id = 1 --1", Engine.MYSQL, false).reason())
        .contains("not a comment on mysql");
    assertThat(SqlAst.analyze("select * from db.s.t", Engine.SQLSERVER, true).reason())
        .contains("names a database");
    assertThat(SqlAst.analyze("select * from \"t\"", Engine.MYSQL, false).reason())
        .contains("ANSI_QUOTES");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "select /*+ full(t) */ * from t",
        "select /*k='v'*/ * from t",
        "select * from t -- a plain comment",
        "select * from t --",
        "select * from t /* a */ /* b */",
      })
  void parse_keepsOrdinaryCommentsAndHints(String sql) {
    assertThat(SqlAst.parse(sql)).isPresent();
  }

  @Test
  void parse_returnsEmptyUnlessTheTextIsExactlyOneStatement() {
    // the parser's single-statement entry point keeps only the first of these
    assertThat(SqlAst.parse("select 1 from plain_t; delete from scalar_t")).isEmpty();
    assertThat(SqlAst.parse("insert into t (id) values (1); delete from t")).isEmpty();
    assertThat(SqlAst.parse("select 1 from t;;")).isEmpty();
    // a trailing terminator, a comment after it, and a ';' inside a literal are still one statement
    assertThat(parse("select 1 from t;").tableRefs()).hasSize(1);
    assertThat(parse("select 1 from t; -- tail").tableRefs()).hasSize(1);
    assertThat(parse("select * from t where x = 'a; b'").tableRefs()).hasSize(1);
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
    assertThat(t.partCount()).isEqualTo(2);
    assertThat(t.alias()).isEqualTo("x");

    SqlAst.TableRef u = parse("select * from Orders").tableRefs().get(0);
    assertThat(u.name()).isEqualTo("Orders");
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
        "delete from orders -- cleanup */\nwhere id = ?",
        "select * from orders -- a\n-- b\nwhere id = ? -- c */",
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
  void joinTokens_closesALineCommentByItsKind_whateverItContains() {
    // "*/" inside a -- comment must not be mistaken for the end of a comment: the WHERE would
    // vanish
    assertThat(parse("delete from t -- cleanup */\nwhere id = 1").joinTokens(ref -> "v_t"))
        .isEqualTo("delete from v_t -- cleanup */\nwhere id = 1");
    assertThat(parse("select * from t -- x\n/* y */ where id = ?").joinTokens())
        .isEqualTo("select * from t -- x\n/* y */ where id = ?");
  }

  @Test
  void joinTokens_aliasesARenamedTableWithItsOwnName_whenColumnsNameIt() {
    SqlAst ast = parse("select \"order\".id, qty from s.\"order\" where \"order\".id = ?");
    assertThat(ast.joinTokens(ref -> "rc_order"))
        .isEqualTo("select \"order\".id, qty from rc_order \"order\" where \"order\".id = ?");
    // a schema-qualified qualifier is reduced to the alias
    assertThat(parse("select s.t.id from s.t where s.t.x = ?").joinTokens(ref -> "rc_t"))
        .isEqualTo("select t.id from rc_t t where t.x = ?");
    // no qualifier names the table: no alias is added
    assertThat(parse("select id from s.t where x = ?").joinTokens(ref -> "rc_t"))
        .isEqualTo("select id from rc_t where x = ?");
  }

  @Test
  void joinTokens_matchesQualifiersOnSchemaAndName() {
    // only s1.t is renamed; the column of s2.t keeps its qualifier
    SqlAst ast = parse("select s1.t.a, s2.t.b from s1.t, s2.t");
    assertThat(ast.joinTokens(ref -> "s1".equals(ref.schema()) ? "rc_t" : null))
        .isEqualTo("select t.a, s2.t.b from rc_t t, s2.t");
    // both renamed and both named by qualifiers: one alias cannot stand for two tables
    assertThat(ast.joinTokens(ref -> "rc_t")).isNull();
  }

  @Test
  void joinTokens_aliasesTablesOfOneNameInNestedQueries_eachWithItsName() {
    // the inner t1 hides the outer one as its name did; x.b < t1.b names the outer one
    SqlAst ast =
        parse(
            "select (select avg(c) from t1), (select count(*) from t1 as x where x.b < t1.b)"
                + " from t1");
    assertThat(ast.joinTokens(ref -> "rc_t1"))
        .isEqualTo(
            "select(select avg(c) from rc_t1 t1),(select count(*) from rc_t1 as x where x.b <"
                + " t1.b) from rc_t1 t1");
  }

  @Test
  void cteBody_seesTheTableOfTheSameName_unlessRecursive() {
    SqlAst shadow =
        parse("with orders as (select * from orders where id = 1) select * from orders");
    assertThat(shadow.tableRefs()).hasSize(1); // the real table, read inside the CTE body
    assertThat(shadow.cteRefs()).hasSize(1); // the outer reference to the CTE
    SqlAst chain = parse("with a as (select * from t), b as (select * from a) select * from b");
    assertThat(chain.tableRefs().stream().map(SqlAst.TableRef::name)).containsExactly("t");
    assertThat(chain.cteRefs()).hasSize(2);
    SqlAst recursive =
        parse(
            "with recursive r as (select 1 as n union all select n + 1 from r where n < 3)"
                + " select * from r");
    assertThat(recursive.tableRefs()).isEmpty();
    assertThat(recursive.cteRefs()).hasSize(2);
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
                String name = Engine.POSTGRESQL.resolveIdentifier(ref.name());
                return name.equals("id") ? null : "before_" + name;
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
  void parameterCount_isCountedOverTheWholeTree_atAnyDepth() {
    // an OR chain nests one level per term; the walk has no depth limit
    StringBuilder b = new StringBuilder("select * from t where c0 = ?");
    for (int i = 1; i < 100; i++) {
      b.append(" or c").append(i).append(" = :p").append(i);
    }
    assertThat(parse(b.toString()).parameterCount()).isEqualTo(100);
    assertThat(
            parse("select (select ? from u where u.k = ?) from t where id in (?, ?) and x = ?")
                .parameterCount())
        .isEqualTo(5);
    assertThat(parse("insert into t (id, x) values (?, ?), (?, ?)").parameterCount()).isEqualTo(4);
  }

  @Test
  void cteNames_matchTheWayTheEngineMatchesNames() {
    // PostgreSQL: the quoted CTE "Orders" and the unquoted table orders are different objects
    String pg = "with \"Orders\" as (select 1 as id) select * from orders";
    assertThat(SqlAst.parse(pg, Engine.POSTGRESQL).orElseThrow().tableRefs()).hasSize(1);
    assertThat(SqlAst.parse(pg, Engine.POSTGRESQL).orElseThrow().cteRefs()).isEmpty();
    // Oracle: the quoted CTE "orders" and the unquoted orders (folded to ORDERS) differ too
    String ora = "with \"orders\" as (select 1 as id from dual) select * from orders";
    assertThat(SqlAst.parse(ora, Engine.ORACLE).orElseThrow().cteRefs()).isEmpty();
    // the same name, unquoted on both sides, is the CTE on every engine
    String same = "with c as (select 1 as id) select * from C";
    assertThat(SqlAst.parse(same, Engine.POSTGRESQL).orElseThrow().cteRefs()).hasSize(1);
    assertThat(SqlAst.parse(same, Engine.ORACLE).orElseThrow().cteRefs()).hasSize(1);
    assertThat(SqlAst.parse(same, Engine.SQLSERVER).orElseThrow().cteRefs()).hasSize(1);
    // MySQL keeps case and compares exactly; SQL Server ignores case
    assertThat(SqlAst.parse(same, Engine.MYSQL).orElseThrow().tableRefs()).hasSize(1);
  }

  @Test
  void forUpdateOf_parses_andARewriteRenamingItsTargetByNameIsRefused() {
    SqlAst byAlias = parse("select * from t o where o.id = ? for update of o");
    assertThat(byAlias.hasLockingOrIntoClause()).isTrue();
    assertThat(byAlias.joinTokens(ref -> "v_t"))
        .isEqualTo("select * from v_t o where o.id = ? for update of o");
    SqlAst byName = parse("select * from t where id = ? for update of t");
    assertThat(byName.joinTokens(ref -> "v_t")).isNull();
  }

  @Test
  void lockingOrIntoClauses_areSeenWhereverTheyAreNested() {
    assertThat(parse("select * from t where id = ?").hasLockingOrIntoClause()).isFalse();
    assertThat(parse("select * from a join b on a.id = b.id for update").hasLockingOrIntoClause())
        .isTrue();
    assertThat(
            parse("select * from a where id in (select id from b for update skip locked)")
                .hasLockingOrIntoClause())
        .isTrue();
    assertThat(parse("select * into t2 from a join b on a.id = b.id").hasLockingOrIntoClause())
        .isTrue();
  }

  @Test
  void doubleQuotedNames_areRefusedWhereTheSessionReadsAString() {
    String sql = "update t set x = ? where status = \"active\"";
    // MySQL and MariaDB without ANSI_QUOTES: "active" is a string to the engine, a column here
    assertThat(SqlAst.parse(sql, Engine.MYSQL)).isEmpty();
    assertThat(SqlAst.parse(sql, Engine.MARIADB)).isEmpty();
    assertThat(SqlAst.parse("select * from \"t\"", Engine.MYSQL)).isEmpty();
    assertThat(SqlAst.parse(sql, Engine.MYSQL, true)).isPresent(); // with ANSI_QUOTES
    assertThat(SqlAst.parse(sql, Engine.SQLSERVER, false)).isEmpty(); // QUOTED_IDENTIFIER OFF
    assertThat(SqlAst.parse(sql, Engine.POSTGRESQL)).isPresent();
    assertThat(SqlAst.parse(sql, Engine.ORACLE)).isPresent();
    // a string literal is not affected
    assertThat(SqlAst.parse("update t set x = ? where status = 'active'", Engine.MYSQL))
        .isPresent();
  }

  @Test
  void namesTheEngineReadsDifferently_areRefused() {
    // one identifier to the engine, schema and table to the parser
    assertThat(SqlAst.parse("select * from \"a.b\" where id = ?", Engine.POSTGRESQL)).isEmpty();
    assertThat(SqlAst.parse("select * from s.\"a.b\" where id = ?", Engine.POSTGRESQL)).isEmpty();
    assertThat(SqlAst.parse("select \"x.y\" from t", Engine.POSTGRESQL)).isEmpty();
    // a database-qualified name resolves outside the catalog's database
    assertThat(SqlAst.parse("select * from db..t where id = ?", Engine.SQLSERVER)).isEmpty();
    assertThat(SqlAst.parse("select * from db.s.t where id = ?", Engine.SQLSERVER)).isEmpty();
    // ordinary qualified and quoted names still parse, and so does a quoted alias with a dot
    assertThat(SqlAst.parse("select * from s.\"Orders\" where id = ?", Engine.POSTGRESQL))
        .isPresent();
    assertThat(SqlAst.parse("select id as \"a.b\" from t", Engine.POSTGRESQL)).isPresent();
  }

  @Test
  void settingTheTransactionModeInSql_isRefused_inEverySpelling() {
    for (String sql :
        List.of(
            "SET autocommit = 0",
            "SET @@autocommit = 0",
            "SET @@session.autocommit = 0",
            "SET @@local.autocommit = 0",
            "SET SESSION autocommit = 0",
            "SET LOCAL autocommit = 0",
            "SET @@session.transaction_isolation = 'READ-UNCOMMITTED'",
            "SET tx_isolation = 'READ-UNCOMMITTED'",
            "SET transaction_read_only = 1",
            "SET default_transaction_isolation = 'read uncommitted'",
            "SET completion_type = 1",
            "SET GLOBAL transaction_isolation = 'READ-UNCOMMITTED'",
            "SET @x = 1, @@session.autocommit = 0")) {
      assertThat(SqlAst.analyze(sql, Engine.MYSQL, false).isPresent()).as(sql).isFalse();
    }
    // PostgreSQL's RESET, RESET ALL and set_config revert or set it too (todo 172)
    for (String sql :
        List.of(
            "RESET ALL",
            "RESET default_transaction_isolation",
            "RESET transaction_isolation",
            "RESET \"default_transaction_isolation\"",
            "SET \"default_transaction_isolation\" = 'read committed'",
            "SET SESSION \"transaction_isolation\" = 'read committed'",
            "SELECT set_config('default_transaction_isolation', 'read committed', false)",
            "SELECT pg_catalog.set_config('Transaction_Isolation', 'read committed', true)",
            "SELECT set_config(?, 'read committed', false)")) {
      assertThat(SqlAst.analyze(sql, Engine.POSTGRESQL, true).isPresent()).as(sql).isFalse();
    }
    assertThat(
            SqlAst.analyze("SELECT set_config('search_path', 'a', false)", Engine.POSTGRESQL, true)
                .isPresent())
        .isTrue();
    assertThat(SqlAst.analyze("SET search_path = a, b", Engine.POSTGRESQL, true).isPresent())
        .isTrue();
    assertThat(SqlAst.analyze("SET @x = 1", Engine.MYSQL, false).isPresent()).isTrue();
  }

  @Test
  void unicodeEscapedIdentifiers_areRefused_onPostgresql() {
    // the parser reads U&"x" as U & "x" with the escapes undecoded; PostgreSQL as one name
    for (String sql :
        List.of(
            "SELECT U&\"query\\005fto_xml\"('SELECT * FROM s', true, false, '')",
            "SELECT u&\"x\" FROM t")) {
      Outcome<SqlAst> o = SqlAst.analyze(sql, Engine.POSTGRESQL, true);
      assertThat(o.isPresent()).as(sql).isFalse();
      assertThat(o.reason()).as(sql).contains("Unicode-escaped");
    }
    // a bitwise AND written with spaces is not one
    assertThat(SqlAst.analyze("SELECT u & \"x\" FROM t", Engine.POSTGRESQL, true).isPresent())
        .isTrue();
  }

  @Test
  void setTo_isTheSessionStatementItSpells_onPostgresql() {
    for (String sql :
        List.of(
            "SET search_path TO app, public",
            "set SESSION search_path to \"App\"",
            "/* c */ SET LOCAL statement_timeout TO '5s'",
            "SET my.var TO DEFAULT")) {
      SqlAst ast = SqlAst.analyze(sql, Engine.POSTGRESQL, true).get();
      assertThat(ast.kind()).as(sql).isEqualTo(SqlAst.Kind.SESSION);
      assertThat(ast.source()).as(sql).hasSameSizeAs(sql);
    }
    // only the statement's own TO
    assertThat(SqlAst.setToAsEquals("SELECT 'SET a TO b'")).isEqualTo("SELECT 'SET a TO b'");
    assertThat(SqlAst.setToAsEquals("SET a = 'TO b'")).isEqualTo("SET a = 'TO b'");
  }

  @Test
  void lockInShareMode_parsesAsForShare_everyOffsetKept() {
    for (String sql :
        List.of(
            "SELECT x FROM t WHERE id = 1 LOCK IN SHARE MODE",
            "select x from t lock  in share\nmode nowait;",
            "SELECT x FROM t LOCK IN SHARE MODE SKIP LOCKED ")) {
      SqlAst ast = SqlAst.analyze(sql, Engine.MARIADB, false).get();
      assertThat(ast.hasLockingOrIntoClause()).as(sql).isTrue();
      assertThat(StatementShape.of(ast).orElseThrow().lockMode()).as(sql).isEqualTo(ForMode.SHARE);
      assertThat(ast.source()).as(sql).hasSameSizeAs(sql);
    }
    // the statement's own clause only, never a literal's text
    assertThat(SqlAst.lockInShareModeAsForShare("SELECT 'LOCK IN SHARE MODE'", false))
        .isEqualTo("SELECT 'LOCK IN SHARE MODE'");
    assertThat(SqlAst.lockInShareModeAsForShare("SELECT 1 LOCK IN SHARE MODE", false))
        .isEqualTo("SELECT 1 FOR SHARE         ");
    // a comment after it, which tools append (todo 194)
    assertThat(
            SqlAst.lockInShareModeAsForShare("SELECT 1 LOCK IN SHARE MODE /* app */ -- x", false))
        .startsWith("SELECT 1 FOR SHARE ");
    // nor a literal or comment with a comment after it (todo 200)
    for (String sql :
        List.of(
            "UPDATE t SET x = 'LOCK IN SHARE MODE #' WHERE id = 5",
            "SELECT x FROM t WHERE x <> 'LOCK IN SHARE MODE -- z'",
            "SELECT x FROM t WHERE x <> 'it\\'s LOCK IN SHARE MODE #'",
            "SELECT `LOCK IN SHARE MODE #`",
            "SELECT 1 /* LOCK IN SHARE MODE */",
            "SELECT 1 # LOCK IN SHARE MODE")) {
      assertThat(SqlAst.lockInShareModeAsForShare(sql, false)).as(sql).isEqualTo(sql);
    }
    assertThat(SqlAst.lockInShareModeAsForShare("SELECT 'a' LOCK IN SHARE MODE # 'b'", false))
        .startsWith("SELECT 'a' FOR SHARE ");
    // nor where a backslash is read either way, or not at all in a quoted name (todo 207)
    String noEscapes = "UPDATE t SET x = 'a\\', y = 'LOCK IN SHARE MODE #' WHERE k = 1";
    assertThat(SqlAst.lockInShareModeAsForShare(noEscapes, false)).isEqualTo(noEscapes);
    String ansi = "SELECT k AS \"a\\\" FROM t WHERE y <> '\" LOCK IN SHARE MODE #'";
    assertThat(SqlAst.lockInShareModeAsForShare(ansi, true)).isEqualTo(ansi);
    assertThat(SqlAst.lockInShareModeAsForShare("SELECT \"a\\\" LOCK IN SHARE MODE", true))
        .startsWith("SELECT \"a\\\" FOR SHARE ");
  }

  @Test
  void startOfLastBefore_findsKeywordsOnly() {
    String sql = "DELETE FROM /* from cache */ t WHERE x = 'from'";
    SqlAst ast = SqlAst.analyze(sql, Engine.MARIADB, false).get();
    assertThat(ast.startOfLastBefore("FROM", sql.indexOf(" t "))).isEqualTo(7);
    assertThat(ast.startOfLastBefore("FROM", 3)).isEqualTo(-1);
  }

  @Test
  void without_parsesTheStatementAgainWithoutAClause() {
    String sql = "SELECT x FROM t WHERE id = ? FOR  UPDATE\n NOWAIT -- c";
    SqlAst ast = SqlAst.analyze(sql, Engine.MYSQL, false).get();
    SqlAst bare = ast.without(ast.afterLast("FOR") - 3, ast.end());
    assertThat(bare).isNotNull();
    assertThat(bare.hasLockingOrIntoClause()).isFalse();
    assertThat(bare.parameterCount()).isEqualTo(1);
  }

  @Test
  void lockingEverySelect_locksEachSelectWhereItEnds() {
    assertThat(
            locked(
                "SELECT a.x FROM a WHERE a.id IN (SELECT id FROM b WHERE v > 1 LIMIT 3)"
                    + " AND EXISTS (SELECT 1 FROM c) ORDER BY 1 -- c",
                Engine.MYSQL))
        .isEqualTo(
            "SELECT a.x FROM a WHERE a.id IN (SELECT id FROM b WHERE v > 1 LIMIT 3 L) AND EXISTS"
                + " (SELECT 1 FROM c L) ORDER BY 1 L -- c");
    assertThat(locked("SELECT * FROM (SELECT x FROM b) d, a FOR UPDATE;", Engine.MARIADB))
        .isEqualTo("SELECT * FROM (SELECT x FROM b L) d, a FOR UPDATE;");
    // the application's own spelling of a locking clause is kept (todo 240)
    assertThat(locked("SELECT x FROM a LOCK IN SHARE MODE", Engine.MARIADB))
        .isEqualTo("SELECT x FROM a LOCK IN SHARE MODE");
    assertThat(locked("(SELECT x FROM a) UNION (SELECT x FROM b)", Engine.MYSQL))
        .isEqualTo("(SELECT x FROM a L) UNION (SELECT x FROM b L)");
    // an operand not in parentheses is put in them (todo 243)
    assertThat(locked("SELECT x FROM a UNION SELECT x FROM b ORDER BY 1", Engine.MYSQL))
        .isEqualTo("(SELECT x FROM a L) UNION (SELECT x FROM b L) ORDER BY 1");
    // TABLE too; a # comment, which the parser reads as code, is refused (todo 239)
    assertThat(locked("TABLE a", Engine.MYSQL)).isEqualTo("TABLE a L");
    // an OF list that leaves a table of the block out is refused (todo 247)
    assertThat(locked("SELECT * FROM a JOIN b USING (x) FOR SHARE OF a", Engine.MYSQL)).isNull();
    assertThat(locked("SELECT * FROM a JOIN b USING (x) FOR UPDATE OF b", Engine.MYSQL)).isNull();
    assertThat(locked("SELECT * FROM a JOIN b USING (x) FOR SHARE OF a, b", Engine.MYSQL))
        .isEqualTo("SELECT * FROM a JOIN b USING (x) FOR SHARE OF a, b");
    assertThat(locked("SELECT * FROM a FOR SHARE OF a", Engine.MYSQL))
        .isEqualTo("SELECT * FROM a FOR SHARE OF a");
    assertThat(locked("SELECT x FROM a WHERE id = 1 # c", Engine.MYSQL)).isNull();
    // MySQL does not lock information_schema (todo 241)
    assertThat(
            locked(
                "SELECT x FROM a WHERE x IN (SELECT ordinal_position FROM"
                    + " `information_schema`.columns)",
                Engine.MYSQL))
        .isEqualTo(
            "SELECT x FROM a WHERE x IN (SELECT ordinal_position FROM"
                + " `information_schema`.columns) L");
    // joined to another table, which would go unlocked with it: refused
    assertThat(
            locked(
                "SELECT a.x FROM information_schema.columns c JOIN a ON a.x = c.ordinal_position",
                Engine.MYSQL))
        .isNull();
  }

  private static @Nullable String locked(String sql, Engine engine) {
    return SqlAst.analyze(sql, engine, false).get().lockingEverySelect(sql, " L");
  }

  @Test
  void transactionControl_hasItsOwnKinds() {
    assertThat(parse("COMMIT").kind()).isEqualTo(SqlAst.Kind.COMMIT);
    assertThat(parse("ROLLBACK").kind()).isEqualTo(SqlAst.Kind.ROLLBACK);
    assertThat(parse("ROLLBACK TO SAVEPOINT s").kind())
        .isEqualTo(SqlAst.Kind.ROLLBACK_TO_SAVEPOINT);
    assertThat(parse("SAVEPOINT s").kind()).isEqualTo(SqlAst.Kind.SAVEPOINT);
  }

  @Test
  void setConfig_changesTheSession_inAnyForm() {
    for (String sql :
        List.of(
            "SELECT set_config('search_path', 'app', false)",
            "SELECT pg_catalog.set_config('search_path', 'app', false)",
            "SELECT \"set_config\"('search_path', 'app', false)",
            "SELECT x FROM t WHERE y = set_config('a.b', 'c', true)")) {
      assertThat(parse(sql).kind()).as(sql).isEqualTo(SqlAst.Kind.SESSION);
    }
    assertThat(parse("SELECT current_setting('search_path')").kind()).isEqualTo(SqlAst.Kind.SELECT);
  }

  @Test
  void functionsThatRunSqlText_areRefused_perEngine() {
    String[][] cases = {
      {"POSTGRESQL", "SELECT query_to_xml('SELECT * FROM s', true, false, '')"},
      {"POSTGRESQL", "SELECT * FROM query_to_xml('SELECT * FROM s', true, false, '')"},
      {"POSTGRESQL", "SELECT table_to_xml('s', true, false, '')"},
      {"POSTGRESQL", "SELECT x FROM t WHERE y IN (SELECT dblink('c', 'SELECT 1'))"},
      {"POSTGRESQL", "SELECT * FROM crosstab('SELECT a, b, c FROM s') AS r(a int, b int)"},
      {"ORACLE", "SELECT dbms_xmlgen.getxml('SELECT * FROM s') FROM dual"},
      {"ORACLE", "SELECT sys.dbms_xmlgen.getxml('SELECT * FROM s') FROM dual"},
      {"ORACLE", "SELECT DBURITYPE('/M1/S').getxml() FROM dual"},
      {"ORACLE", "SELECT sys.xdburitype('/M1/S').getclob() FROM dual"},
      {"ORACLE", "SELECT x FROM t WHERE y = SYS_DBURIGEN(a)"},
      {"SQLSERVER", "SELECT * FROM OPENQUERY(lnk, 'SELECT * FROM s')"},
      {"SQLSERVER", "SELECT * FROM OPENROWSET('SQLNCLI', 'x', 'SELECT * FROM s') AS r"},
    };
    for (String[] c : cases) {
      Outcome<SqlAst> o = SqlAst.analyze(c[1], Engine.valueOf(c[0]), true);
      assertThat(o.isPresent()).as(c[1]).isFalse();
      assertThat(o.reason()).as(c[1]).contains("runs SQL given as text").contains("passthrough");
    }
    // the same names are ordinary functions where the engine has no such function
    assertThat(SqlAst.analyze("SELECT query_to_xml(a) FROM t", Engine.MYSQL, true).isPresent())
        .isTrue();
    assertThat(SqlAst.analyze("SELECT count(*), upper(x) FROM t", Engine.POSTGRESQL, true))
        .matches(Outcome::isPresent);
  }

  @Test
  void sessionStatements_areAccepted_withoutTables_unlessTheyReadOne() {
    for (String sql :
        new String[] {
          "SET search_path = app, public",
          "SET ROLE app",
          "RESET search_path",
          "USE app",
          "SET NAMES utf8mb4",
          "ALTER SESSION SET CURRENT_SCHEMA = app"
        }) {
      SqlAst ast = parse(sql);
      assertThat(ast.kind()).as(sql).isEqualTo(SqlAst.Kind.SESSION);
      assertThat(ast.tableRefs()).as(sql).isEmpty();
    }
    // a subquery in a SET reads a table the walk does not visit: refused
    assertThat(SqlAst.parse("SET x = (SELECT a FROM t)")).isEmpty();
    assertThat(SqlAst.analyze("CALL p(?)", Engine.POSTGRESQL, true).reason())
        .contains("procedure call");
  }

  @Test
  void topPlaceholder_isCounted() {
    assertThat(parse("select top ? * from t where id = ?").parameterCount()).isEqualTo(2);
    assertThat(
            parse("select top (?) * from t join u on t.id = u.id where t.id = ?").parameterCount())
        .isEqualTo(2);
  }

  @Test
  void joinTokens_renamesATableToABracketQuotedName_onSqlServer() {
    SqlAst ast = SqlAst.parse("SELECT x FROM su.imp WHERE id = 1", Engine.SQLSERVER).orElseThrow();
    assertThat(ast.joinTokens(r -> "[su].[rc_imp]"))
        .isEqualTo("SELECT x FROM [su].[rc_imp] WHERE id = 1");
  }

  @Test
  void skipsRows_onlyForAnOffsetPastTheFirstRow() {
    assertThat(parse("SELECT x FROM t ORDER BY x LIMIT 3 OFFSET 3").skipsRows()).isTrue();
    assertThat(parse("SELECT x FROM t ORDER BY x LIMIT 3 OFFSET 0").skipsRows()).isFalse();
    assertThat(parse("SELECT x FROM t ORDER BY x LIMIT 3 OFFSET ?").skipsRows()).isTrue();
    // SQL Server's first page, as Spring Data writes it
    SqlAst first =
        SqlAst.parse(
                "SELECT x FROM t ORDER BY x OFFSET 0 ROWS FETCH NEXT 2 ROWS ONLY", Engine.SQLSERVER)
            .orElseThrow();
    assertThat(first.skipsRows()).isFalse();
    SqlAst next =
        SqlAst.parse(
                "SELECT x FROM t ORDER BY x OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY", Engine.SQLSERVER)
            .orElseThrow();
    assertThat(next.skipsRows()).isTrue();
  }

  @Test
  void oraclesIsolationLevelSetInSql_isRefused() {
    // ojdbc does not read the level back: the session would run at one the driver does not know
    assertThat(
            SqlAst.analyze("ALTER SESSION SET ISOLATION_LEVEL = SERIALIZABLE", Engine.ORACLE, true)
                .isPresent())
        .isFalse();
    assertThat(
            SqlAst.analyze("ALTER SESSION SET NLS_LANGUAGE = GERMAN", Engine.ORACLE, true)
                .isPresent())
        .isTrue();
  }
}
