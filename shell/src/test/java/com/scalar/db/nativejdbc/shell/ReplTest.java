package com.scalar.db.nativejdbc.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.scalar.db.nativejdbc.NativeJdbcDriver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  void quit_endsTheSession_andSaysSo() {
    assertThat(repl.quit()).isFalse();
    assertThat(repl.feed("\\q")).isFalse();
    assertThat(repl.quit()).isTrue();
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

  @Test
  void toggles() {
    repl.feed("\\x");
    repl.feed("\\x off");
    repl.feed("\\timing on");
    repl.feed("\\x maybe");

    assertThat(output.toString())
        .startsWith(
            "Expanded display is on.\nExpanded display is off.\nTiming is on.\n"
                + "\\x: unrecognized value");
  }

  @Test
  void timing_followsEachStatement() {
    repl.feed("\\timing");
    repl.feed("delete from t;");

    assertThat(output.toString()).containsPattern("DELETE 1\nTime: \\d+\\.\\d{3} ms\n$");
  }

  @Test
  void passthrough_setsTheDriversSetting_andIsRefusedInATransaction() throws SQLException {
    repl.feed("\\passthrough on");
    verify(connection).setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
    when(connection.getClientInfo(NativeJdbcDriver.PASSTHROUGH)).thenReturn("true");
    assertThat(repl.prompt(false)).isEqualTo("shop=!> ");

    repl.feed("\\passthrough off");
    verify(connection).setClientInfo(NativeJdbcDriver.PASSTHROUGH, "false");
    when(connection.getClientInfo(NativeJdbcDriver.PASSTHROUGH)).thenReturn("false");
    repl.feed("begin;");
    output.getBuffer().setLength(0);
    repl.feed("\\passthrough on");

    verify(connection, times(1)).setClientInfo(eq(NativeJdbcDriver.PASSTHROUGH), eq("true"));
    assertThat(output.toString()).startsWith("ERROR:  \\passthrough applies in autocommit");
  }

  @Test
  void include_runsAFileInTheSession(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("a.sql");
    Files.writeString(file, "begin;\ninsert into t values (1);\ncommit", StandardCharsets.UTF_8);

    assertThat(repl.feed("\\i " + file)).isTrue();
    assertThat(repl.feed("\\i " + dir.resolve("missing.sql"))).isTrue();

    InOrder order = inOrder(connection, statement);
    order.verify(connection).setAutoCommit(false);
    order.verify(statement).execute("insert into t values (1)");
    order.verify(connection).commit();
    assertThat(output.toString()).endsWith("missing.sql: No such file or directory\n");
  }

  @Test
  void connect_replacesTheConnection_onlyOnceTheNewOneIsOpen() throws SQLException {
    Connection other = mock(Connection.class);
    DatabaseMetaData md = mock(DatabaseMetaData.class);
    when(other.getCatalog()).thenReturn("other");
    when(other.getMetaData()).thenReturn(md);
    when(md.getUserName()).thenReturn("alice");
    Repl.Connector connector =
        target -> {
          if ("nope".equals(target)) {
            throw new SQLException("FATAL: database \"nope\" does not exist");
          }
          return new Repl.Connected(other, StatementSplitter.Dialect.POSTGRESQL, "url");
        };
    Repl r =
        new Repl(
            new Repl.Connected(connection, StatementSplitter.Dialect.POSTGRESQL, "url"),
            new PrintWriter(output, true),
            false,
            connector);
    r.feed("begin;");

    r.feed("\\c nope");
    assertThat(output.toString()).endsWith("Previous connection kept\n");
    verify(connection, never()).close();

    r.feed("\\c other");
    verify(connection).rollback();
    verify(connection).close();
    assertThat(output.toString())
        .endsWith("You are now connected to database \"other\" as user \"alice\".\n");
    assertThat(r.prompt(false)).isEqualTo("other=> ");
  }
}
