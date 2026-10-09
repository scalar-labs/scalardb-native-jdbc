package com.scalar.db.nativejdbc;

import com.scalar.db.nativejdbc.catalog.Catalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
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

  /** How many catalogs, and so how many catalog sessions, the driver keeps at most. */
  private static final int MAX_CATALOGS = 64;

  /**
   * One catalog per database and identity, keyed by a digest (see {@link #catalog}), least recently
   * used first. A catalog pushed out releases its database session; a connection still holding it
   * keeps working and reopens one when it needs to. Guarded by itself.
   */
  private static final Map<String, Catalog> CATALOGS =
      new LinkedHashMap<String, Catalog>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Catalog> eldest) {
          if (size() <= MAX_CATALOGS) {
            return false;
          }
          eldest.getValue().releaseConnection();
          return true;
        }
      };

  private static volatile boolean deregistered;

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
    deregistered = true; // a connect racing with this one fails rather than leaves a catalog open
    DriverManager.deregisterDriver(INSTANCE);
    synchronized (CATALOGS) {
      for (Catalog c : CATALOGS.values()) {
        c.close();
      }
      CATALOGS.clear();
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
    if (deregistered) {
      throw new SQLException("The driver has been deregistered", SQLSTATE_UNABLE_TO_CONNECT);
    }
    NativeJdbcUrl parsed = parse(url);
    DriverProperties settings = DriverProperties.read(parsed.driverParameters(), info);
    Properties vendorProperties = DriverProperties.vendorProperties(info);
    Driver vendor = vendorDriver(parsed);
    // the user's own connection first: a caller who cannot log in leaves nothing behind
    Connection connection = vendor.connect(parsed.vendorUrl(), vendorProperties);
    if (connection == null) {
      throw new SQLException(
          "The " + parsed.subProtocol() + " driver did not accept the URL",
          SQLSTATE_UNABLE_TO_CONNECT);
    }
    try {
      // then the catalog: a database without ScalarDB's catalog, or a user who cannot read it, is
      // refused here rather than at the first statement
      catalog(parsed, settings, vendor, vendorProperties).verify();
    } catch (SQLException | RuntimeException e) {
      try {
        connection.close();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
    // The connection proxy of docs/design.md ("Architecture") wraps this connection next; until
    // then the vendor connection is returned as is.
    return connection;
  }

  /**
   * The catalog for this database and identity. The key is a digest of the vendor URL, every vendor
   * property, password and other secrets included, and the catalog settings: callers whose identity
   * differs in any way never share a catalog, a rotated password gets a new one, and no credential
   * is kept in a key. Only callers that have just logged in with these properties get here.
   */
  private static Catalog catalog(
      NativeJdbcUrl parsed, DriverProperties settings, Driver vendor, Properties vendorProperties) {
    StringBuilder identity = new StringBuilder(parsed.vendorUrl()).append('\u0000');
    for (String name : new TreeSet<>(vendorProperties.stringPropertyNames())) {
      identity.append(name).append('=').append(vendorProperties.getProperty(name)).append('\u0001');
    }
    identity
        .append('\u0000')
        .append(settings.metadataSchema)
        .append('\u0000')
        .append(settings.catalogTtl.toMillis())
        .append('\u0000')
        .append(settings.allowNoCatalog);
    String key = sha256(identity.toString());
    synchronized (CATALOGS) {
      Catalog c = CATALOGS.get(key);
      if (c == null) {
        c =
            new Catalog(
                parsed.engine(),
                settings.metadataSchema,
                () -> {
                  Connection conn = vendor.connect(parsed.vendorUrl(), vendorProperties);
                  if (conn == null) {
                    throw new SQLException(
                        "The " + parsed.subProtocol() + " driver did not accept the URL",
                        SQLSTATE_UNABLE_TO_CONNECT);
                  }
                  return conn;
                },
                settings.catalogTtl,
                Clock.systemUTC(),
                settings.allowNoCatalog);
        CATALOGS.put(key, c);
      }
      return c;
    }
  }

  private static String sha256(String s) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }

  /** How many catalogs the driver holds; for tests. */
  static int catalogCount() {
    synchronized (CATALOGS) {
      return CATALOGS.size();
    }
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
