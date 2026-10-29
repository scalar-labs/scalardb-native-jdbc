package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * SQL Server: a database must have READ_COMMITTED_SNAPSHOT on, and a connection stays in the
 * database it was opened in, for which the catalog and that check hold (design section 1).
 */
class SqlServerDatabaseIntegrationTest {

  private static final Engines MS = Engines.SQLSERVER;
  private static String appdb;
  private static String nosnap;
  private static Properties props;

  @BeforeAll
  static void setUp() throws SQLException {
    Assumptions.assumeTrue(Engines.selected().contains(MS), "SQL Server is not selected");
    try (Connection c = MS.open()) {
      MS.execute(
          c,
          "IF DB_ID('nosnap') IS NULL CREATE DATABASE nosnap",
          "IF DB_ID('nosi') IS NULL CREATE DATABASE nosi",
          "ALTER DATABASE nosi SET READ_COMMITTED_SNAPSHOT ON");
    }
    appdb = "jdbc:scalardb-native:" + MS.url().substring("jdbc:".length());
    nosnap = appdb.replace("databaseName=appdb", "databaseName=nosnap");
    props = new Properties();
    props.setProperty("user", MS.username());
    props.setProperty("password", MS.password());
    // the catalog is not what these tests are about
    props.setProperty("scalar.db.native_jdbc.allow_no_catalog", "true");
  }

  @Test
  void aDatabaseWithoutReadCommittedSnapshot_isRefusedAtConnect() {
    assertThatThrownBy(
            () -> {
              try (Connection c = DriverManager.getConnection(nosnap, props)) {
                c.isValid(1);
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("READ_COMMITTED_SNAPSHOT")
        .extracting("SQLState")
        .isEqualTo("08004");
  }

  @Test
  void aDatabaseWithoutSnapshotIsolation_isRefusedAtConnect() throws SQLException {
    // a connection starts at SNAPSHOT, which SQL Server runs only where the database allows it
    assertThatThrownBy(
            () -> {
              try (Connection c =
                  DriverManager.getConnection(
                      appdb.replace("databaseName=appdb", "databaseName=nosi"), props)) {
                c.isValid(1);
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ALLOW_SNAPSHOT_ISOLATION")
        .extracting("SQLState")
        .isEqualTo("55000");
    try (Connection c = DriverManager.getConnection(appdb, props)) {
      assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT transaction_isolation_level FROM sys.dm_exec_sessions"
                      + " WHERE session_id = @@SPID")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).as("SNAPSHOT").isEqualTo(5);
      }
    }
  }

  @Test
  void aSwitchToAnotherDatabase_isRefused() throws SQLException {
    try (Connection c = DriverManager.getConnection(appdb, props);
        Statement st = c.createStatement()) {
      assertThatThrownBy(() -> st.execute("USE nosnap"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("0AN01");
      assertThatThrownBy(() -> c.setCatalog("nosnap"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("0AN01");
      // the database it is in
      st.execute("USE appdb");
      st.execute("USE [appdb]");
      c.setCatalog("appdb");
      assertThat(one(st)).isEqualTo("appdb");
    }
  }

  @Test
  void aSwitchThroughThePassthroughSetting_stopsTheNextStatement() throws SQLException {
    try (Connection c = DriverManager.getConnection(appdb, props);
        Statement st = c.createStatement()) {
      c.setClientInfo("scalar.db.native_jdbc.passthrough", "true");
      st.execute("USE nosnap");
      c.setClientInfo("scalar.db.native_jdbc.passthrough", "false");
      assertThatThrownBy(() -> one(st))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("opened in appdb")
          .extracting("SQLState")
          .isEqualTo("0AN01");
      c.setClientInfo("scalar.db.native_jdbc.passthrough", "true");
      st.execute("USE appdb");
      c.setClientInfo("scalar.db.native_jdbc.passthrough", "false");
      assertThat(one(st)).isEqualTo("appdb");
    }
  }

  private static String one(Statement st) throws SQLException {
    try (ResultSet rs = st.executeQuery("SELECT DB_NAME()")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
