package com.scalar.db.nativejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The driver's connect path on PostgreSQL: the catalog check, properties, the catalog registry. */
class DriverIntegrationTest {

  private static final Engines PG = Engines.POSTGRESQL;
  private static String url;
  private static Properties credentials;

  @BeforeAll
  static void setUp() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Engines.selected().contains(PG), "PostgreSQL is not selected");
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE SCHEMA IF NOT EXISTS drv",
          "CREATE TABLE IF NOT EXISTS drv.t (id INT PRIMARY KEY)");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("drv", true);
      if (!admin.tableExists("drv", "t")) {
        admin.importTable("drv", "t", Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
    }
    url = "jdbc:scalardb-native:" + PG.url().substring("jdbc:".length());
    credentials = new Properties();
    try (Connection c = PG.open()) {
      credentials.setProperty("user", c.getMetaData().getUserName());
    }
    credentials.setProperty("password", PG.password());
  }

  @Test
  void connect_checksTheCatalog_andReturnsAWorkingConnection() throws SQLException {
    try (Connection c = DriverManager.getConnection(url, credentials);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT 1")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void aDatabaseWithoutTheCatalog_isRefusedAtConnect_unlessAllowed() throws SQLException {
    Properties p = (Properties) credentials.clone();
    p.setProperty("scalardbnative.metadataSchema", "nosuch");
    assertThatThrownBy(
            () -> {
              try (Connection c = DriverManager.getConnection(url, p)) {
                c.isValid(1);
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("nosuch")
        .extracting("SQLState")
        .isEqualTo("08004");
    try (Connection c =
        DriverManager.getConnection(
            url + "?scalardbnative.metadataSchema=nosuch&scalardbnative.allowNoCatalog=true",
            credentials)) {
      assertThat(c.isValid(5)).isTrue();
    }
  }

  @Test
  void connectionsToOneDatabaseAsOneUser_shareOneCatalog() throws SQLException {
    Properties p = (Properties) credentials.clone();
    p.setProperty("scalardbnative.catalogTtlMs", "12345"); // a key no other test uses
    try (Connection a = DriverManager.getConnection(url, p)) {
      int after = NativeJdbcDriver.catalogCount();
      try (Connection b = DriverManager.getConnection(url, p)) {
        assertThat(NativeJdbcDriver.catalogCount()).isEqualTo(after);
      }
    }
  }

  @Test
  void getPropertyInfo_listsTheDriversPropertiesFirst() throws SQLException {
    DriverPropertyInfo[] infos = DriverManager.getDriver(url).getPropertyInfo(url, credentials);
    assertThat(infos[0].name).isEqualTo("scalardbnative.metadataSchema");
    assertThat(infos).extracting(i -> i.name).contains("user"); // pgjdbc's own follow
  }
}
