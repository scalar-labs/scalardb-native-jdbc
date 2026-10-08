package com.scalar.db.nativejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
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
  void connect_reportsAnUnsupportedSubProtocolAsAConnectionFailure() {
    assertThatThrownBy(
            () -> new NativeJdbcDriver().connect("jdbc:scalardb-native:sqlite:x", new Properties()))
        .isInstanceOf(SQLException.class)
        .extracting("SQLState")
        .isEqualTo("08001");
  }
}
