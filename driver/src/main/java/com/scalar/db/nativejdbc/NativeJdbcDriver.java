package com.scalar.db.nativejdbc;

import com.scalar.db.nativejdbc.catalog.Catalog;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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

  private static final NativeJdbcDriver INSTANCE = new NativeJdbcDriver();

  /**
   * One catalog per database and identity: the vendor URL, every vendor property except the
   * password, and the catalog settings. Which tables a name resolves to depends on who asks (the
   * user, an Oracle proxy user, a PostgreSQL role set through the properties), so callers that
   * differ in any of these never share a catalog.
   */
  private static final Map<String, Catalog> CATALOGS = new ConcurrentHashMap<>();

  static {
    try {
      DriverManager.registerDriver(INSTANCE);
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * Deregisters the driver and closes the connections its catalogs hold, for an application server
   * that unloads the application; connections already open keep working.
   *
   * @throws SQLException if {@link DriverManager} refuses
   */
  public static void deregister() throws SQLException {
    DriverManager.deregisterDriver(INSTANCE);
    for (Catalog c : CATALOGS.values()) {
      c.close();
    }
    CATALOGS.clear();
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
    DriverProperties settings = DriverProperties.read(parsed.driverParameters(), info);
    Properties vendorProperties = DriverProperties.vendorProperties(info);
    Driver vendor = vendorDriver(parsed);
    // the catalog first: a database without ScalarDB's catalog, or a user who cannot read it, is
    // refused here rather than at the first statement
    catalog(parsed, settings, vendor, vendorProperties).verify();
    Connection connection = vendor.connect(parsed.vendorUrl(), vendorProperties);
    if (connection == null) {
      throw new SQLException(
          "The " + parsed.subProtocol() + " driver did not accept the URL",
          SQLSTATE_UNABLE_TO_CONNECT);
    }
    // The connection proxy of docs/design.md ("Architecture") wraps this connection next; until
    // then the vendor connection is returned as is.
    return connection;
  }

  private static Catalog catalog(
      NativeJdbcUrl parsed, DriverProperties settings, Driver vendor, Properties vendorProperties) {
    StringBuilder identity = new StringBuilder();
    for (String name : new TreeSet<>(vendorProperties.stringPropertyNames())) {
      if (!name.equalsIgnoreCase("password")) {
        identity
            .append(name)
            .append('=')
            .append(vendorProperties.getProperty(name))
            .append('\u0001');
      }
    }
    String key =
        String.join(
            "\u0000",
            parsed.vendorUrl(),
            identity,
            settings.metadataSchema,
            Long.toString(settings.catalogTtl.toMillis()),
            Boolean.toString(settings.allowNoCatalog));
    return CATALOGS.computeIfAbsent(
        key,
        k ->
            new Catalog(
                parsed.engine(),
                settings.metadataSchema,
                () -> {
                  Connection c = vendor.connect(parsed.vendorUrl(), vendorProperties);
                  if (c == null) {
                    throw new SQLException(
                        "The " + parsed.subProtocol() + " driver did not accept the URL",
                        SQLSTATE_UNABLE_TO_CONNECT);
                  }
                  return c;
                },
                settings.catalogTtl,
                Clock.systemUTC(),
                settings.allowNoCatalog));
  }

  /** How many catalogs the driver holds; for tests. */
  static int catalogCount() {
    return CATALOGS.size();
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
    DriverPropertyInfo[] own =
        DriverProperties.describe(DriverProperties.read(parsed.driverParameters(), info));
    DriverPropertyInfo[] vendors =
        vendorDriver(parsed)
            .getPropertyInfo(parsed.vendorUrl(), DriverProperties.vendorProperties(info));
    DriverPropertyInfo[] out = Arrays.copyOf(own, own.length + vendors.length);
    System.arraycopy(vendors, 0, out, own.length, vendors.length);
    return out;
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
