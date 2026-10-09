package com.scalar.db.nativejdbc;

import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The driver's own properties, {@code scalardbnative.*}, read from the URL's parameters and from
 * the connect {@link Properties}; a URL parameter wins over a property of the same name, as the
 * vendor drivers do. They are removed before the rest is handed to the vendor driver (design
 * section 1, "URL scheme").
 */
final class DriverProperties {

  /** The prefix of every property of this driver. */
  static final String PREFIX = "scalardbnative.";

  static final String METADATA_SCHEMA = PREFIX + "metadataSchema";
  static final String CATALOG_TTL_MS = PREFIX + "catalogTtlMs";
  static final String ALLOW_NO_CATALOG = PREFIX + "allowNoCatalog";
  static final String INFLIGHT_WAIT_MS = PREFIX + "inflightWaitMs";

  private static final String SQLSTATE_UNABLE_TO_CONNECT = "08001";

  final String metadataSchema;
  final Duration catalogTtl;
  final boolean allowNoCatalog;
  final Duration inflightWait;

  private DriverProperties(
      String metadataSchema, Duration catalogTtl, boolean allowNoCatalog, Duration inflightWait) {
    this.metadataSchema = metadataSchema;
    this.catalogTtl = catalogTtl;
    this.allowNoCatalog = allowNoCatalog;
    this.inflightWait = inflightWait;
  }

  /**
   * Reads the driver's properties.
   *
   * @param urlParameters the driver's parameters found in the URL
   * @param info the connect properties, or {@code null}
   * @return the properties, defaults filled in
   * @throws SQLException with SQLSTATE 08001 if a value is malformed or a key is unknown
   */
  static DriverProperties read(Map<String, String> urlParameters, @Nullable Properties info)
      throws SQLException {
    Properties all = new Properties();
    if (info != null) {
      for (String key : info.stringPropertyNames()) {
        if (key.startsWith(PREFIX)) {
          all.setProperty(key, info.getProperty(key));
        }
      }
    }
    urlParameters.forEach(all::setProperty);
    for (String key : all.stringPropertyNames()) {
      if (!key.equals(METADATA_SCHEMA)
          && !key.equals(CATALOG_TTL_MS)
          && !key.equals(ALLOW_NO_CATALOG)
          && !key.equals(INFLIGHT_WAIT_MS)) {
        // passthrough is deliberately not a connect property: it turns the protection off, so it
        // is set on a connection through setClientInfo, never from a URL
        throw new SQLException("Unknown driver property " + key, SQLSTATE_UNABLE_TO_CONNECT);
      }
    }
    return new DriverProperties(
        all.getProperty(METADATA_SCHEMA, "scalardb"),
        Duration.ofMillis(nonNegative(all, CATALOG_TTL_MS, 60_000)),
        bool(all, ALLOW_NO_CATALOG),
        Duration.ofMillis(nonNegative(all, INFLIGHT_WAIT_MS, 0)));
  }

  /** The connect properties without this driver's keys, for the vendor driver. */
  static Properties vendorProperties(@Nullable Properties info) {
    Properties out = new Properties();
    if (info != null) {
      for (String key : info.stringPropertyNames()) {
        if (!key.startsWith(PREFIX)) {
          out.setProperty(key, info.getProperty(key));
        }
      }
    }
    return out;
  }

  /** The descriptions {@link java.sql.Driver#getPropertyInfo} lists ahead of the vendor's. */
  static DriverPropertyInfo[] describe(DriverProperties current) {
    return new DriverPropertyInfo[] {
      info(
          METADATA_SCHEMA,
          current.metadataSchema,
          "ScalarDB's system namespace, where virtual_tables and its table metadata live"),
      info(
          CATALOG_TTL_MS,
          Long.toString(current.catalogTtl.toMillis()),
          "How long ScalarDB's catalog and a resolved name are trusted, in milliseconds; a table"
              + " registered with ScalarDB is protected after at most this long"),
      choice(
          ALLOW_NO_CATALOG,
          current.allowNoCatalog,
          "Accept a database without ScalarDB's catalog, every table then plain; otherwise such a"
              + " database is refused at connect"),
      info(
          INFLIGHT_WAIT_MS,
          Long.toString(current.inflightWait.toMillis()),
          "Under READ COMMITTED, how long a write waits for a row a ScalarDB transaction holds;"
              + " 0 aborts at once"),
    };
  }

  private static DriverPropertyInfo info(String name, String value, String description) {
    DriverPropertyInfo i = new DriverPropertyInfo(name, value);
    i.description = description;
    return i;
  }

  private static DriverPropertyInfo choice(String name, boolean value, String description) {
    DriverPropertyInfo i = info(name, Boolean.toString(value), description);
    i.choices = new String[] {"true", "false"};
    return i;
  }

  private static long nonNegative(Properties p, String key, long defaultValue) throws SQLException {
    String v = p.getProperty(key);
    if (v == null) {
      return defaultValue;
    }
    try {
      long n = Long.parseLong(v.strip());
      if (n < 0) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new SQLException(
          key + " must be a non-negative number of milliseconds", SQLSTATE_UNABLE_TO_CONNECT);
    }
  }

  private static boolean bool(Properties p, String key) throws SQLException {
    String v = p.getProperty(key);
    if (v == null) {
      return false;
    }
    switch (v.strip().toLowerCase(java.util.Locale.ROOT)) {
      case "true":
        return true;
      case "false":
        return false;
      default:
        throw new SQLException(key + " must be true or false", SQLSTATE_UNABLE_TO_CONNECT);
    }
  }
}
