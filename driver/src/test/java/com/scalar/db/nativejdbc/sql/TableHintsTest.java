package com.scalar.db.nativejdbc.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.engine.Engine;
import net.sf.jsqlparser.statement.select.ForMode;
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
