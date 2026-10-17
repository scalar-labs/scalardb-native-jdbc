package com.scalar.db.nativejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class NativeJdbcDriverTest {

  @Test
  void driverManager_findsTheDriverForItsPrefix() throws SQLException {
    Driver driver = DriverManager.getDriver("jdbc:scalardb-native:postgresql://localhost/db");

    assertThat(driver).isInstanceOf(NativeJdbcDriver.class);
  }

  @Test
  void connect_returnsNullForAForeignUrl() throws SQLException {
    assertThat(new NativeJdbcDriver().connect("jdbc:postgresql://localhost/db", new Properties()))
        .isNull();
  }

  @Test
  void getPropertyInfo_reportsAnUnsupportedSubProtocolAsAnSqlException() {
    assertThatThrownBy(
            () ->
                new NativeJdbcDriver()
                    .getPropertyInfo("jdbc:scalardb-native:sqlite://u:s3cret@host/db", null))
        .isInstanceOf(SQLException.class)
        .hasMessageNotContaining("s3cret")
        .extracting("SQLState")
        .isEqualTo("08001");
  }

  @Test
  void useAffectedRows_isRefusedOnMySqlAndMariaDb_inTheUrlOrTheProperties() {
    Properties on = new Properties();
    on.setProperty("useaffectedrows", "TRUE");
    for (String url :
        List.of(
            "jdbc:scalardb-native:mysql://h/db?a=1&useAffectedRows=true",
            "jdbc:scalardb-native:mariadb://h/db?useAffectedRows=1")) {
      assertThatThrownBy(() -> new NativeJdbcDriver().connect(url, new Properties()))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("08004");
    }
    assertThatThrownBy(
            () -> new NativeJdbcDriver().connect("jdbc:scalardb-native:mysql://h/db", on))
        .isInstanceOf(SQLException.class)
        .extracting("SQLState")
        .isEqualTo("08004");
  }

  @Test
  void aMissingVendorDriver_isNamed_withoutTheUrl() {
    // the unit-test class path has no driver for jdbc:mysql:
    Assumptions.assumeTrue(
        DriverManager.drivers().noneMatch(d -> acceptsQuietly(d, "jdbc:mysql://h/db")),
        "a MySQL driver is on the class path");
    assertThatThrownBy(
            () ->
                new NativeJdbcDriver()
                    .connect(
                        "jdbc:scalardb-native:mysql://u:s3cret@host/db?password=s3cret",
                        new Properties()))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("No mysql JDBC driver")
        .hasMessageNotContaining("s3cret")
        .extracting("SQLState")
        .isEqualTo("08001");
  }

  private static boolean acceptsQuietly(Driver d, String url) {
    try {
      return d.acceptsURL(url);
    } catch (SQLException e) {
      return false;
    }
  }

  @Test
  void connect_reportsAnUnsupportedSubProtocolAsAConnectionFailure() {
    assertThatThrownBy(
            () -> new NativeJdbcDriver().connect("jdbc:scalardb-native:sqlite:x", new Properties()))
        .isInstanceOf(SQLException.class)
        .extracting("SQLState")
        .isEqualTo("08001");
  }
}
