package com.scalar.db.nativejdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The ScalarDB Native JDBC driver, registered with {@link DriverManager} through the {@code
 * META-INF/services/java.sql.Driver} entry and by the static initializer.
 *
 * <p>A URL of the form {@code jdbc:scalardb-native:<vendor URL without jdbc:>} is accepted; the
 * vendor driver is found through {@link DriverManager} for the stripped URL, so the application
 * supplies its own vendor driver. See {@link NativeJdbcUrl}.
 */
public final class NativeJdbcDriver implements Driver {

  /**
   * The ScalarDB line this driver is certified against, as the driver version's major and minor.
   */
  private static final int MAJOR_VERSION = 3;

  private static final int MINOR_VERSION = 19;

  private static final String SQLSTATE_UNABLE_TO_CONNECT = "08001";

  static {
    try {
      DriverManager.registerDriver(new NativeJdbcDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Creates a driver instance; {@link DriverManager} does this through the service registration.
   */
  public NativeJdbcDriver() {}

  @Override
  public @Nullable Connection connect(String url, @Nullable Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      // The JDBC contract: a driver returns null for a URL that is not its own, so that
      // DriverManager can try the next registered driver.
      return null;
    }
    NativeJdbcUrl parsed = parse(url);
    Connection connection =
        vendorDriver(parsed).connect(parsed.vendorUrl(), info == null ? new Properties() : info);
    if (connection == null) {
      throw new SQLException(
          "The " + parsed.subProtocol() + " driver did not accept the URL",
          SQLSTATE_UNABLE_TO_CONNECT);
    }
    // The connection proxy of docs/design.md ("Architecture") wraps this connection next; until
    // then the vendor connection is returned as is.
    return connection;
  }

  /** Parses the URL; the error never repeats the URL, which may carry credentials. */
  private static NativeJdbcUrl parse(String url) throws SQLException {
    try {
      return NativeJdbcUrl.parse(url);
    } catch (IllegalArgumentException e) {
      throw new SQLException(e.getMessage(), SQLSTATE_UNABLE_TO_CONNECT, e);
    }
  }

  /** The vendor's registered driver; the error names what is missing, never the URL. */
  private static Driver vendorDriver(NativeJdbcUrl parsed) throws SQLException {
    try {
      return DriverManager.getDriver(parsed.vendorUrl());
    } catch (SQLException e) {
      // DriverManager's message is "No suitable driver"; its cause may hold the URL, so drop it
      throw new SQLException(
          "No "
              + parsed.subProtocol()
              + " JDBC driver is registered: add the vendor's driver to the class path",
          SQLSTATE_UNABLE_TO_CONNECT);
    }
  }

  @Override
  public boolean acceptsURL(String url) {
    return NativeJdbcUrl.accepts(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, @Nullable Properties info)
      throws SQLException {
    if (!acceptsURL(url)) {
      return new DriverPropertyInfo[0];
    }
    NativeJdbcUrl parsed = parse(url);
    return vendorDriver(parsed)
        .getPropertyInfo(parsed.vendorUrl(), info == null ? new Properties() : info);
  }

  @Override
  public int getMajorVersion() {
    return MAJOR_VERSION;
  }

  @Override
  public int getMinorVersion() {
    return MINOR_VERSION;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("The driver logs through SLF4J");
  }
}
