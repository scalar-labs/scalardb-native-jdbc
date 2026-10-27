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
    p.setProperty("scalar.db.native_jdbc.metadata_schema", "nosuch");
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
    Properties allowed = (Properties) p.clone();
    allowed.setProperty("scalar.db.native_jdbc.allow_no_catalog", "true");
    try (Connection c = DriverManager.getConnection(url, allowed)) {
      assertThat(c.isValid(5)).isTrue();
    }
    // neither setting may come from the URL: either could turn the protection off
    assertThatThrownBy(
            () -> {
              try (Connection c =
                  DriverManager.getConnection(
                      url
                          + "?scalar.db.native_jdbc.metadata_schema=nosuch&scalar.db.native_jdbc.allow_no_catalog=true",
                      credentials)) {
                c.isValid(1);
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("cannot be set in the URL")
        .extracting("SQLState")
        .isEqualTo("08001");
  }

  @Test
  void aWrongPassword_leavesNothingBehind_andTheRightOneStillWorks() throws SQLException {
    Properties wrong = (Properties) credentials.clone();
    wrong.setProperty("password", "WRONG");
    wrong.setProperty("ApplicationName", "wrong-password-test");
    int before = NativeJdbcDriver.catalogCount();
    assertThatThrownBy(
            () -> {
              try (Connection c = DriverManager.getConnection(url, wrong)) {
                c.isValid(1);
              }
            })
        .isInstanceOf(SQLException.class);
    assertThat(NativeJdbcDriver.catalogCount()).isEqualTo(before);
    Properties right = (Properties) credentials.clone();
    right.setProperty("ApplicationName", "wrong-password-test");
    try (Connection c = DriverManager.getConnection(url, right)) {
      assertThat(c.isValid(5)).isTrue();
    }
  }

  @Test
  void theCatalogRegistryIsBounded() throws SQLException {
    for (int i = 0; i < 80; i++) {
      Properties p = (Properties) credentials.clone();
      p.setProperty(
          "ApplicationName", "bound-" + i); // a property per connection, as a pool may set
      try (Connection c = DriverManager.getConnection(url, p)) {
        c.isValid(1);
      }
    }
    assertThat(NativeJdbcDriver.catalogCount()).isLessThanOrEqualTo(64);
  }

  @Test
  void connectionsToOneDatabaseAsOneUser_shareOneCatalog() throws SQLException {
    Properties p = (Properties) credentials.clone();
    p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "12345"); // a key no other test uses
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
    assertThat(infos[0].name).isEqualTo("scalar.db.native_jdbc.metadata_schema");
    assertThat(infos).extracting(i -> i.name).contains("user"); // pgjdbc's own follow
  }
}
