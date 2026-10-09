package com.scalar.db.nativejdbc;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A ScalarDB Native JDBC URL: the prefix {@value #PREFIX} followed by the vendor's own URL without
 * its {@code jdbc:} prefix, for example {@code jdbc:scalardb-native:postgresql://host:5432/db}.
 *
 * <p>The driver strips its prefix and hands the remainder, {@code jdbc:postgresql://host:5432/db}
 * here, to the vendor driver found through {@link java.sql.DriverManager}. The sub-protocol token,
 * {@code postgresql} here, selects the per-engine strategy. Accepted sub-protocols are the engines
 * ScalarDB itself runs on: {@code postgresql}, {@code mysql}, {@code mariadb}, {@code sqlserver}
 * and {@code oracle}. Anything else is refused rather than guessed at.
 */
public final class NativeJdbcUrl {

  /** The prefix every URL of this driver starts with. */
  public static final String PREFIX = "jdbc:scalardb-native:";

  private static final Set<String> SUB_PROTOCOLS = subProtocols();

  private static Set<String> subProtocols() {
    Set<String> out = new java.util.HashSet<>();
    for (Engine e : Engine.values()) {
      out.add(e.subProtocol());
    }
    return Set.copyOf(out);
  }

  private final String subProtocol;
  private final String vendorUrl;

  private NativeJdbcUrl(String subProtocol, String vendorUrl) {
    this.subProtocol = subProtocol;
    this.vendorUrl = vendorUrl;
  }

  /**
   * Returns whether the URL starts with this driver's prefix. The comparison ignores case in the
   * prefix, as {@link java.sql.Driver#acceptsURL} callers expect; the vendor part is left as given.
   *
   * @param url a JDBC URL
   * @return whether this driver should handle the URL
   */
  public static boolean accepts(@Nullable String url) {
    return url != null && url.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
  }

  /**
   * Parses a URL of this driver.
   *
   * @param url a URL that {@link #accepts} returns {@code true} for
   * @return the parsed URL
   * @throws IllegalArgumentException if the URL does not carry the prefix, has no sub-protocol, or
   *     names a sub-protocol the driver does not support. The message never contains the URL, which
   *     may carry credentials, only the prefix and the sub-protocol.
   */
  public static NativeJdbcUrl parse(String url) {
    if (!accepts(url)) {
      throw new IllegalArgumentException(
          "Not a ScalarDB Native JDBC URL: expected the prefix '" + PREFIX + "'");
    }
    String remainder = url.substring(PREFIX.length());
    int colon = remainder.indexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException(
          "No sub-protocol after '" + PREFIX + "': expected one of " + sorted());
    }
    String subProtocol = remainder.substring(0, colon).toLowerCase(Locale.ROOT);
    if (!SUB_PROTOCOLS.contains(subProtocol)) {
      throw new IllegalArgumentException(
          "Unsupported database '" + subProtocol + "': expected one of " + sorted());
    }
    // the vendor drivers match their prefix case-sensitively, so the token is passed lower-cased
    return new NativeJdbcUrl(subProtocol, "jdbc:" + subProtocol + remainder.substring(colon));
  }

  private static String sorted() {
    return String.join(", ", new java.util.TreeSet<>(SUB_PROTOCOLS));
  }

  /**
   * Returns the sub-protocol token in lower case, for example {@code postgresql}.
   *
   * @return the sub-protocol
   */
  public String subProtocol() {
    return subProtocol;
  }

  /**
   * Returns the vendor driver's URL, the given URL with this driver's prefix replaced by {@code
   * jdbc:}.
   *
   * @return the vendor URL
   */
  public String vendorUrl() {
    return vendorUrl;
  }
}
