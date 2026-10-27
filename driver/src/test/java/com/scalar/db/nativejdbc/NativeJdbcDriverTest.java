package com.scalar.db.nativejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
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
  void majorAndMinorVersion_areTheBuildVersionsLine() {
    // 3.20.0-beta.1 is certified against ScalarDB 3.20: the version's line is the driver's
    String version = System.getProperty("nativejdbc.version");
    Assumptions.assumeTrue(version != null, "run outside Gradle");
    NativeJdbcDriver driver = new NativeJdbcDriver();

    assertThat(driver.getMajorVersion() + "." + driver.getMinorVersion() + ".")
        .isEqualTo(version.substring(0, version.indexOf('.', version.indexOf('.') + 1) + 1));
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
