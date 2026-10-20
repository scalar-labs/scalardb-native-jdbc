package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.List;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.ForMode;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TableHintsTest {

  private static SqlAst ast(String sql) {
    Outcome<SqlAst> o = SqlAst.analyze(sql, Engine.SQLSERVER, true);
    assertThat(o.isPresent()).as("%s: %s", sql, o.isPresent() ? "" : o.reason()).isTrue();
    return o.get();
  }

  @Test
  void hintsTheParserCannotRead_areBlankedAndKeptPerTable() {
    SqlAst a = ast("SELECT x FROM ns.t WITH (UPDLOCK, ROWLOCK) WHERE id = 1");
    assertThat(a.tableRefs()).hasSize(1);
    assertThat(a.hints(a.tableRefs().get(0))).containsExactly("UPDLOCK", "ROWLOCK");
    assertThat(a.source()).isEqualTo("SELECT x FROM ns.t                         WHERE id = 1");
    assertThat(a.hasLockingOrIntoClause()).isTrue();
    // after an alias, with arguments
    a = ast("SELECT x FROM ns.t AS a WITH (HOLDLOCK, INDEX(ix), READPAST) WHERE a.id = 1");
    assertThat(a.hints(a.tableRefs().get(0))).containsExactly("HOLDLOCK", "INDEX", "READPAST");
    // the parser's own: kept in the text
    a = ast("SELECT x FROM ns.t WITH (NOLOCK)");
    assertThat(a.hints(a.tableRefs().get(0))).isEmpty();
    assertThat(a.tableRefs().get(0).hasHintOrLink()).isTrue();
    // in a comment or a literal: text, not hints
    a = ast("SELECT 'WITH (UPDLOCK)' FROM ns.t /* WITH (UPDLOCK) */");
    assertThat(a.hints(a.tableRefs().get(0))).isEmpty();
    assertThat(a.hasLockingOrIntoClause()).isFalse();
    // a CTE is no hint list
    a = ast("WITH c AS (SELECT 1 AS a) SELECT a FROM c");
    assertThat(a.cteRefs()).hasSize(1);
  }

  @Test
  void hintsWithoutWith_nameTheirTable() {
    // the parser would read a function ns.t, naming no table, or an alias's column list
    SqlAst a = ast("SELECT x FROM ns.t (NOLOCK) WHERE id = 1");
    assertThat(a.tableRefs()).hasSize(1);
    assertThat(a.hints(a.tableRefs().get(0))).containsExactly("NOLOCK");
    a = ast("SELECT x FROM ns.t a (NOLOCK)");
    assertThat(a.hints(a.tableRefs().get(0))).containsExactly("NOLOCK");
    // a function call's arguments that are no hints stay
    a = ast("SELECT COUNT(x) FROM ns.t");
    assertThat(a.hints(a.tableRefs().get(0))).isEmpty();
  }

  @Test
  void hintListsTheParserReadsAsCalls_areRefused() throws JSQLParserException {
    // SQL Server and the parser end a line comment at a carriage return
    SqlAst a = ast("SELECT x FROM ns.t --c\r(NOLOCK) WHERE id = 1");
    assertThat(a.hints(a.tableRefs().get(0))).containsExactly("NOLOCK");
    a = ast("SELECT x FROM ns.t --c\r\n(UPDLOCK) WHERE id = 1");
    assertThat(a.hints(a.tableRefs().get(0))).containsExactly("UPDLOCK");
    // a list the hint pass does not see, however it comes about, is refused as a call
    assertThat(SqlAst.isHintList(call("t(nolock, rowlock)"))).isTrue();
    assertThat(SqlAst.isHintList(call("f(nolock, x)"))).isFalse();
    assertThat(SqlAst.isHintList(call("f()"))).isFalse();
  }

  private static Function call(String text) throws JSQLParserException {
    return (Function) CCJSqlParserUtil.parseExpression(text);
  }

  @Test
  void formattingCharacterInAName_isRefusedOnEveryEngine() {
    for (Engine e : Engine.values()) {
      assertThat(SqlAst.analyze("SELECT x FROM ns.t\u200B WHERE id = 1", e, true).isPresent())
          .as("%s", e)
          .isFalse();
      assertThat(SqlAst.analyze("SELECT x FROM ns.t\u00AD(NOLOCK)", e, true).isPresent())
          .as("%s", e)
          .isFalse();
    }
    // in a literal or a quoted name it is what it is to both
    assertThat(
            SqlAst.analyze(
                    "SELECT '\u200B', N'\u200B' AS [a\u200B] FROM ns.t", Engine.SQLSERVER, true)
                .isPresent())
        .isTrue();
  }

  @Test
  void holdingEveryTable_locksEachTableItReads() {
    assertThat(hold("SELECT x FROM ns.t WHERE id = 1"))
        .isEqualTo("SELECT x FROM ns.t WITH (HOLDLOCK) WHERE id = 1");
    assertThat(hold("SELECT a.x FROM ns.t AS a JOIN p b ON b.id = a.id"))
        .isEqualTo(
            "SELECT a.x FROM ns.t AS a WITH (HOLDLOCK) JOIN p b WITH (HOLDLOCK) ON b.id = a.id");
    // a subquery's too, a CTE's tables but not the CTE
    assertThat(hold("WITH c AS (SELECT id FROM p) SELECT id FROM c WHERE id IN (SELECT id FROM q)"))
        .isEqualTo(
            "WITH c AS (SELECT id FROM p WITH (HOLDLOCK)) SELECT id FROM c WHERE id IN (SELECT id"
                + " FROM q WITH (HOLDLOCK))");
    // into a hint list the table has, either form
    assertThat(hold("SELECT x FROM p WITH (UPDLOCK) WHERE id = 1"))
        .isEqualTo("SELECT x FROM p WITH (UPDLOCK, HOLDLOCK) WHERE id = 1");
    assertThat(hold("SELECT x FROM p a (UPDLOCK, ROWLOCK)"))
        .isEqualTo("SELECT x FROM p a WITH (UPDLOCK, ROWLOCK, HOLDLOCK)");
    // merged with the hints there (todo 270): HOLDLOCK once, REPEATABLEREAD as HOLDLOCK, padded;
    // a hint that holds nothing cannot be held; SELECT INTO's new table takes none
    assertThat(hold("SELECT x FROM p WITH (HOLDLOCK)"))
        .isEqualTo("SELECT x FROM p WITH (HOLDLOCK)");
    assertThat(hold("SELECT x FROM p WITH (REPEATABLEREAD, ROWLOCK)"))
        .isEqualTo("SELECT x FROM p WITH (HOLDLOCK      , ROWLOCK)");
    for (String h : List.of("READUNCOMMITTED", "READCOMMITTED", "READCOMMITTEDLOCK", "READPAST")) {
      assertThat(hold("SELECT x FROM p WITH (" + h + ")")).as(h).isNull();
    }
    assertThat(hold("SELECT v INTO #t FROM p WHERE id = 1"))
        .isEqualTo("SELECT v INTO #t FROM p WITH (HOLDLOCK) WHERE id = 1");
    // a write's target and every table it reads (todo 268)
    assertThat(hold("UPDATE p SET v = 0 WHERE id = 1 AND (SELECT SUM(v) FROM p) >= 2"))
        .isEqualTo(
            "UPDATE p WITH (HOLDLOCK) SET v = 0 WHERE id = 1 AND (SELECT SUM(v) FROM p WITH"
                + " (HOLDLOCK)) >= 2");
    assertThat(hold("INSERT INTO q (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM q WHERE id >= 1)"))
        .isEqualTo(
            "INSERT INTO q WITH (HOLDLOCK) (id) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM q WITH"
                + " (HOLDLOCK) WHERE id >= 1)");
    assertThat(hold("DELETE FROM p WHERE id IN (SELECT id FROM q)"))
        .isEqualTo("DELETE FROM p WITH (HOLDLOCK) WHERE id IN (SELECT id FROM q WITH (HOLDLOCK))");
    // the catalog takes no lock; a hint the parser keeps cannot be merged
    assertThat(hold("SELECT name FROM sys.tables")).isEqualTo("SELECT name FROM sys.tables");
    assertThat(hold("SELECT x FROM p WITH (NOLOCK)")).isNull();
    // a function of the application's own, whose reads take no hint (todo 269)
    assertThat(hold("SELECT v FROM pp.tvf(1)")).isNull();
    assertThat(hold("SELECT a.v FROM p CROSS APPLY tvf(p.id) a")).isNull();
    assertThat(hold("SELECT pp.fsum()")).isNull();
    assertThat(hold("UPDATE p SET v = dbo.f(v)")).isNull();
    // built-in ones read no table
    assertThat(hold("SELECT ISNULL(v, 0), sys.fn_varbintohexstr(0x1) FROM p"))
        .isEqualTo("SELECT ISNULL(v, 0), sys.fn_varbintohexstr(0x1) FROM p WITH (HOLDLOCK)");
    assertThat(hold("SELECT value FROM STRING_SPLIT('a,b', ',')"))
        .isEqualTo("SELECT value FROM STRING_SPLIT('a,b', ',')");
  }

  @Test
  void columnsNamedAsHints_areNoHintList() {
    // a list after a function, a CTE or a derived table names columns (todo 258)
    for (String sql :
        List.of(
            "SELECT MAX(snapshot) FROM p",
            "WITH x (snapshot) AS (SELECT 1) SELECT snapshot FROM x",
            "SELECT nowait FROM (SELECT 1) AS d (nowait)",
            "SELECT nowait FROM (SELECT 1) d (nowait)",
            "INSERT INTO p (snapshot) SELECT x FROM ns.t")) {
      SqlAst a = ast(sql);
      assertThat(a.tableRefs()).as(sql).allMatch(r -> a.hints(r).isEmpty());
    }
    // after a table, its alias, in a join or after a comma, it is one
    for (String sql :
        List.of(
            "SELECT x FROM p (NOLOCK)",
            "SELECT x FROM q JOIN p a (NOLOCK) ON a.id = q.id",
            "SELECT x FROM q, p AS a (NOLOCK)",
            "SELECT x FROM q WHERE id IN (SELECT id FROM p (NOLOCK))")) {
      SqlAst a = ast(sql);
      assertThat(a.tableRefs()).as(sql).anyMatch(r -> a.hints(r).contains("NOLOCK"));
    }
  }

  private static @Nullable String hold(String sql) {
    return ast(sql).holdingEveryTable(sql);
  }

  @Test
  void shape_takesTheLockingHintsAsItsClause() {
    StatementShape s =
        StatementShape.classify(ast("SELECT x FROM ns.t WITH (UPDLOCK, READPAST) WHERE id = 1"))
            .get();
    assertThat(s.lockMode()).isEqualTo(ForMode.UPDATE);
    assertThat(s.skipLocked()).isTrue();
    s = StatementShape.classify(ast("SELECT x FROM ns.t WITH (HOLDLOCK, NOWAIT)")).get();
    assertThat(s.lockMode()).isEqualTo(ForMode.SHARE);
    assertThat(s.noWait()).isTrue();
    // a dirty read, an index, or a hint on a write is no locking read
    assertThat(
            StatementShape.classify(ast("SELECT x FROM ns.t WITH (READUNCOMMITTED)")).isPresent())
        .isFalse();
    assertThat(StatementShape.classify(ast("SELECT x FROM ns.t (NOLOCK)")).isPresent()).isFalse();
    assertThat(
            StatementShape.classify(ast("UPDATE ns.t WITH (ROWLOCK) SET x = 1 WHERE id = 1"))
                .isPresent())
        .isFalse();
    assertThat(StatementShape.classify(ast("SELECT x FROM ns.t WITH (READPAST)")).isPresent())
        .isFalse();
  }
}
