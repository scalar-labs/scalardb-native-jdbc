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
      MS.execute(c, "IF DB_ID('nosnap') IS NULL CREATE DATABASE nosnap");
    }
    appdb = "jdbc:scalardb-native:" + MS.url().substring("jdbc:".length());
    nosnap = appdb.replace("databaseName=appdb", "databaseName=nosnap");
    props = new Properties();
    props.setProperty("user", MS.username());
    props.setProperty("password", MS.password());
    // the catalog is not what these tests are about
    props.setProperty("scalardbnative.allowNoCatalog", "true");
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
      c.setClientInfo("scalardbnative.passthrough", "true");
      st.execute("USE nosnap");
      c.setClientInfo("scalardbnative.passthrough", "false");
      assertThatThrownBy(() -> one(st))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("opened in appdb")
          .extracting("SQLState")
          .isEqualTo("0AN01");
      c.setClientInfo("scalardbnative.passthrough", "true");
      st.execute("USE appdb");
      c.setClientInfo("scalardbnative.passthrough", "false");
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
