package com.scalar.db.nativejdbc.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** The transaction commands the driver refuses as SQL become JDBC calls. */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "OBL_UNSATISFIED_OBLIGATION"},
    justification = "statements on a mock connection, verified rather than run")
class ReplTest {

  private Connection connection;
  private Statement statement;
  private StringWriter output;
  private Repl repl;

  @BeforeEach
  void setUp() throws SQLException {
    connection = mock(Connection.class);
    statement = mock(Statement.class);
    when(connection.getCatalog()).thenReturn("shop");
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    when(connection.createStatement()).thenReturn(statement);
    when(statement.getUpdateCount()).thenReturn(1, -1);
    output = new StringWriter();
    repl =
        new Repl(
            connection,
            StatementSplitter.Dialect.POSTGRESQL,
            "jdbc:scalardb-native:postgresql://h/shop",
            new PrintWriter(output, true),
            false);
  }

  @Test
  void beginAndCommit_becomeAutoCommitAndCommit() throws SQLException {
    assertThat(repl.prompt(false)).isEqualTo("shop=> ");

    repl.feed("BEGIN;");
    assertThat(repl.prompt(false)).isEqualTo("shop=*> ");
    assertThat(repl.prompt(true)).isEqualTo("shop-*> ");
    repl.feed("insert into t values (1);");
    repl.feed("commit work;");

    InOrder order = inOrder(connection, statement);
    order.verify(connection).setAutoCommit(false);
    order.verify(statement).execute("insert into t values (1)");
    order.verify(connection).commit();
    order.verify(connection).setAutoCommit(true);
    assertThat(output.toString()).isEqualTo("BEGIN\nINSERT 1\nCOMMIT\n");
    assertThat(repl.prompt(false)).isEqualTo("shop=> ");
  }

  @Test
  void rollbackAndItsSpellings_becomeRollback() throws SQLException {
    for (String[] pair :
        new String[][] {
          {"start transaction;", "rollback;"},
          {"begin transaction;", "abort;"},
          {"/* c */ begin work;", "ROLLBACK TRANSACTION;"}
        }) {
      repl.feed(pair[0]);
      repl.feed(pair[1]);
    }

    verify(connection, times(3)).rollback();
    verify(statement, never()).execute(anyString());
  }

  @Test
  void rollbackToASavepoint_isSql() throws SQLException {
    repl.feed("begin;");
    repl.feed("rollback to savepoint a;");

    verify(statement).execute("rollback to savepoint a");
    verify(connection, never()).rollback();
  }

  @Test
  void anIsolationLevel_holdsForOneTransaction() throws SQLException {
    repl.feed("begin isolation level serializable;");
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
    repl.feed("commit;");
    repl.feed("begin;");
    repl.feed("set transaction isolation level snapshot;");

    InOrder order = inOrder(connection);
    order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    order.verify(connection).commit();
    order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
  }

  @Test
  void setTransaction_outsideATransaction_onlyWarns() throws SQLException {
    repl.feed("set transaction isolation level serializable;");

    verify(connection, never()).setTransactionIsolation(anyInt());
    assertThat(output.toString()).startsWith("WARNING:");
  }

  @Test
  void theSessionLevel_isSetAtOnce() throws SQLException {
    repl.feed("set session characteristics as transaction isolation level repeatable read;");

    verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
  }

  @Test
  void aFailedCommit_rollsBackBeforeReturningToAutocommit() throws SQLException {
    doThrow(new SQLException("could not serialize", "40001")).when(connection).commit();
    repl.feed("begin;");

    assertThat(repl.feed("commit;")).isTrue();

    InOrder order = inOrder(connection);
    order.verify(connection).commit();
    order.verify(connection).rollback();
    order.verify(connection).setAutoCommit(true);
    assertThat(output.toString()).contains("ERROR:  could not serialize");
    assertThat(repl.prompt(false)).isEqualTo("shop=> ");
  }

  @Test
  void onErrorStop_endsTheSession() throws SQLException {
    Repl stopping =
        new Repl(
            connection,
            StatementSplitter.Dialect.POSTGRESQL,
            "url",
            new PrintWriter(output, true),
            true);
    when(statement.execute("select x")).thenThrow(new SQLException("ERROR: no x"));

    assertThat(stopping.feed("select x; select 1;")).isFalse();

    verify(statement, never()).execute("select 1");
    assertThat(output.toString()).isEqualTo("ERROR: no x\n");
  }

  @Test
  void aStatementWithoutSemicolon_waitsUntilTheScriptEnds() throws SQLException {
    repl.feed("delete from t");
    verify(statement, never()).execute(anyString());

    repl.finish();

    verify(statement).execute("delete from t");
  }

  @Test
  void metaCommands() {
    assertThat(repl.isComplete("\\conninfo")).isTrue();
    assertThat(repl.isComplete("select 1")).isFalse();
    assertThat(repl.feed("\\nope")).isTrue();
    assertThat(output.toString()).startsWith("invalid command \\nope");
    assertThat(repl.feed("\\q")).isFalse();
  }
}
