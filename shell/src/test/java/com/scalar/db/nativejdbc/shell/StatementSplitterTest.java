package com.scalar.db.nativejdbc.shell;

import static com.scalar.db.nativejdbc.shell.StatementSplitter.Dialect.MYSQL;
import static com.scalar.db.nativejdbc.shell.StatementSplitter.Dialect.ORACLE;
import static com.scalar.db.nativejdbc.shell.StatementSplitter.Dialect.POSTGRESQL;
import static com.scalar.db.nativejdbc.shell.StatementSplitter.Dialect.SQLSERVER;
import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.shell.StatementSplitter.Dialect;
import com.scalar.db.nativejdbc.shell.StatementSplitter.Result;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StatementSplitterTest {

  @ParameterizedTest
  @EnumSource(Dialect.class)
  void statements_endAtSemicolons(Dialect d) {
    Result r = StatementSplitter.split("select 1; select 2;\nselect", d);

    assertThat(r.statements()).containsExactly("select 1", "select 2");
    assertThat(r.remainder()).isEqualTo("\nselect");
    assertThat(r.pending()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(Dialect.class)
  void aSemicolon_insideQuotesOrComments_doesNotEndTheStatement(Dialect d) {
    Result r = StatementSplitter.split("select 'a;b', \"c;d\" -- e;f\n/* g;h */ from t; ", d);

    assertThat(r.statements()).containsExactly("select 'a;b', \"c;d\" -- e;f\n/* g;h */ from t");
    assertThat(r.pending()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(Dialect.class)
  void anOpenQuote_waits(Dialect d) {
    Result r = StatementSplitter.split("select 'it''s;\n", d);

    assertThat(r.statements()).isEmpty();
    assertThat(r.pending()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(Dialect.class)
  void onlyCommentsAndEmptyStatements_areNotPending(Dialect d) {
    Result r = StatementSplitter.split(";; -- nothing\n/* here */\n", d);

    assertThat(r.statements()).isEmpty();
    assertThat(r.pending()).isFalse();
  }

  @Test
  void postgresql_dollarQuotesAndNestedComments_holdSemicolons() {
    String function =
        "create function f() returns int as $body$ begin return 1; end; $body$ language plpgsql";

    assertThat(StatementSplitter.split(function + ";", POSTGRESQL).statements())
        .containsExactly(function);
    assertThat(StatementSplitter.split("select /* a /* b; */ c; */ 1;", POSTGRESQL).statements())
        .containsExactly("select /* a /* b; */ c; */ 1");
    assertThat(StatementSplitter.split("select E'\\';';", POSTGRESQL).statements())
        .containsExactly("select E'\\';'");
    // $1 is a parameter, not a dollar quote
    assertThat(StatementSplitter.split("select $1; select 2;", POSTGRESQL).statements())
        .containsExactly("select $1", "select 2");
  }

  @Test
  void mysql_backslashesBackticksAndHashComments() {
    assertThat(StatementSplitter.split("select 'a\\';b', `c;d` # e;f\nfrom t;", MYSQL).statements())
        .containsExactly("select 'a\\';b', `c;d` # e;f\nfrom t");
    // on PostgreSQL, # is an operator and a backslash is literal
    assertThat(StatementSplitter.split("select 1 # 2; select 'a\\';", POSTGRESQL).statements())
        .containsExactly("select 1 # 2", "select 'a\\'");
  }

  @Test
  void sqlServer_bracketsAndGo() {
    assertThat(StatementSplitter.split("select [a;b] from t;", SQLSERVER).statements())
        .containsExactly("select [a;b] from t");
    String procedure = "create procedure p as begin select 1; select 2; end";

    Result open = StatementSplitter.split(procedure + ";\n", SQLSERVER);
    Result closed = StatementSplitter.split(procedure + ";\ngo\nselect 3;", SQLSERVER);

    assertThat(open.statements()).isEmpty();
    assertThat(open.pending()).isTrue();
    assertThat(closed.statements()).containsExactly(procedure + ";", "select 3");
  }

  @Test
  void oracle_plsqlBlocks_endAtASlashLine() {
    String block = "begin\n  insert into t values (1);\n  commit;\nend;";

    Result open = StatementSplitter.split(block + "\n", ORACLE);
    Result closed = StatementSplitter.split(block + "\n/\nselect 1 from dual;", ORACLE);

    assertThat(open.statements()).isEmpty();
    assertThat(closed.statements()).containsExactly(block, "select 1 from dual");
  }

  @Test
  void oracle_beginAlone_isTheTransactionCommand() {
    assertThat(StatementSplitter.split("begin;", ORACLE).statements()).containsExactly("begin");
    assertThat(StatementSplitter.split("begin isolation level serializable;", ORACLE).statements())
        .containsExactly("begin isolation level serializable");
  }

  @Test
  void blocks_needATerminatorLine_onlyWhereTheEngineHasOne() {
    for (Dialect d : EnumSet.of(POSTGRESQL, MYSQL)) {
      assertThat(StatementSplitter.split("begin; select 1;", d).statements())
          .containsExactly("begin", "select 1");
    }
  }

  @Test
  void leadingComments_areStripped() {
    assertThat(StatementSplitter.withoutLeadingComments(" -- a\n /* b */ commit"))
        .isEqualTo("commit");
  }
}
