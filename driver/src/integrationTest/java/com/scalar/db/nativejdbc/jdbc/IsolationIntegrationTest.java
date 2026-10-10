package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.nativejdbc.testing.Engines;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every engine starts at READ COMMITTED through the driver, including MySQL and MariaDB, whose
 * drivers start at REPEATABLE READ.
 */
class IsolationIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aConnection_startsAtReadCommitted(Engines e) throws SQLException {
    Properties p = new Properties();
    try (Connection direct = e.open()) {
      // MySQL and MariaDB report user@host
      p.setProperty("user", direct.getMetaData().getUserName().split("@", 2)[0]);
    }
    p.setProperty("password", e.password());
    p.setProperty("scalardbnative.allowNoCatalog", "true");
    String url = "jdbc:scalardb-native:" + e.url().substring("jdbc:".length());
    try (Connection c = DriverManager.getConnection(url, p)) {
      // the vendor drivers read the level from the server, not from a cached value
      assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
    }
  }
}
