package com.scalar.db.nativejdbc;

import com.scalar.db.nativejdbc.engine.Engine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  private final Map<String, String> driverParameters;

  private NativeJdbcUrl(
      String subProtocol, String vendorUrl, Map<String, String> driverParameters) {
    this.subProtocol = subProtocol;
    this.vendorUrl = vendorUrl;
    this.driverParameters = driverParameters;
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
    String vendor = "jdbc:" + subProtocol + remainder.substring(colon);
    Map<String, String> driverParameters = new LinkedHashMap<>();
    vendor =
        subProtocol.equals("sqlserver")
            ? extractSemicolonParameters(vendor, driverParameters)
            : extractQueryParameters(vendor, driverParameters);
    return new NativeJdbcUrl(subProtocol, vendor, Map.copyOf(driverParameters));
  }

  /** {@code ?a=b&scalardbnative.x=y}: removes the driver's keys, keeps the vendor's as written. */
  private static String extractQueryParameters(String url, Map<String, String> into) {
    int q = url.indexOf('?');
    if (q < 0) {
      return url;
    }
    List<String> kept = new ArrayList<>();
    for (String pair : url.substring(q + 1).split("&", -1)) {
      int eq = pair.indexOf('=');
      String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      if (key.startsWith(DriverProperties.PREFIX)) {
        into.put(
            key, eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      } else {
        kept.add(pair);
      }
    }
    return kept.isEmpty() ? url.substring(0, q) : url.substring(0, q + 1) + String.join("&", kept);
  }

  /** SQL Server's {@code ;a=b;scalardbnative.x=y}: removes the driver's keys. */
  private static String extractSemicolonParameters(String url, Map<String, String> into) {
    String[] segments = url.split(";", -1);
    StringBuilder out = new StringBuilder(segments[0]);
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i];
      int eq = segment.indexOf('=');
      String key = (eq < 0 ? segment : segment.substring(0, eq)).strip();
      if (key.startsWith(DriverProperties.PREFIX)) {
        into.put(key, eq < 0 ? "" : segment.substring(eq + 1).strip());
      } else {
        out.append(';').append(segment);
      }
    }
    return out.toString();
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
   * Returns the engine the sub-protocol selects.
   *
   * @return the engine
   */
  public Engine engine() {
    return Engine.of(subProtocol);
  }

  /**
   * Returns the vendor driver's URL: the given URL with this driver's prefix replaced by {@code
   * jdbc:} and this driver's own {@code scalardbnative.*} parameters removed.
   *
   * @return the vendor URL
   */
  public String vendorUrl() {
    return vendorUrl;
  }

  /**
   * Returns this driver's own parameters found in the URL, {@code scalardbnative.*} keys in the
   * vendor's parameter syntax ({@code ?a=b&c=d}, or {@code ;a=b} for SQL Server).
   *
   * @return the parameters, keys with their prefix
   */
  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "an immutable Map.copyOf")
  public Map<String, String> driverParameters() {
    return driverParameters;
  }
}
