package com.scalar.db.nativejdbc.catalog;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a user table name to a {@link TableInfo} from ScalarDB's two catalog tables, {@code
 * virtual_tables} and {@code metadata} in ScalarDB's system schema, and caches the answer.
 *
 * <p>The lookup mirrors ScalarDB's own tests, in this order:
 *
 * <ol>
 *   <li>a {@code virtual_tables} row whose virtual table name is the user's name: a created
 *       decoupled table (INNER join);
 *   <li>a row whose left source is the user's name with join type LEFT_OUTER: an imported decoupled
 *       table; a left-source match with join type INNER is the {@code _data} table of a created
 *       table, which a user statement must not name;
 *   <li>a {@code metadata} entry carrying the transaction metadata columns: a classic-layout table;
 *   <li>otherwise a plain table.
 * </ol>
 *
 * Nothing is inferred from naming conventions. The catalog tables are read with plain statements
 * and never written.
 */
@SuppressFBWarnings(
    value = "SQL_INJECTION_JDBC",
    justification =
        "the only text concatenated into the catalog queries is the metadata schema name, validated"
            + " against IDENTIFIER and quoted with the engine's quote; every value is bound")
public final class Catalog {

  /** ScalarDB's default system namespace, where its catalog tables live. */
  public static final String DEFAULT_METADATA_SCHEMA = "scalardb";

  private static final Set<String> TRANSACTION_COLUMNS =
      Set.of(
          "tx_id",
          "tx_state",
          "tx_version",
          "tx_prepared_at",
          "tx_committed_at",
          "before_tx_id",
          "before_tx_state",
          "before_tx_version",
          "before_tx_prepared_at",
          "before_tx_committed_at");

  private static final String BEFORE_PREFIX = "before_";

  /** What a schema name given to this class may look like: a plain SQL identifier. */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final String metadataSchema;
  private final Map<String, TableInfo> cache = new ConcurrentHashMap<>();

  /**
   * Creates a catalog reading ScalarDB's system schema.
   *
   * @param metadataSchema the schema that holds {@code virtual_tables} and {@code metadata},
   *     {@value #DEFAULT_METADATA_SCHEMA} unless ScalarDB is configured otherwise
   */
  public Catalog(String metadataSchema) {
    if (!IDENTIFIER.matcher(metadataSchema).matches()) {
      throw new IllegalArgumentException("Not a plain identifier: " + metadataSchema);
    }
    this.metadataSchema = metadataSchema;
  }

  /**
   * Resolves a table name, from the cache or from the catalog tables.
   *
   * @param connection a connection to the database
   * @param namespace the schema the statement names, or {@code null} for the connection's current
   *     schema
   * @param table the table name as the statement resolves it
   * @return what the driver knows about the table
   * @throws SQLException if the catalog cannot be read, or if the name is one a user statement must
   *     not use: a {@code _data} table or a virtual table of a decoupled layout
   */
  public TableInfo lookup(Connection connection, @Nullable String namespace, String table)
      throws SQLException {
    String ns = namespace != null ? namespace : currentSchema(connection);
    String key = ns + "." + table;
    TableInfo cached = cache.get(key);
    if (cached != null) {
      return cached;
    }
    TableInfo info = read(connection, ns, table);
    cache.put(key, info);
    return info;
  }

  /** Forgets every cached entry, so the next lookup reads the catalog again. */
  public void invalidate() {
    cache.clear();
  }

  private TableInfo read(Connection c, String ns, String table) throws SQLException {
    String full = ns + "." + table;
    String q = c.getMetaData().getIdentifierQuoteString().strip();
    String virtualTables = quote(q, metadataSchema) + "." + quote(q, "virtual_tables");
    String metadata = quote(q, metadataSchema) + "." + quote(q, "metadata");

    // 1. the user's name is a virtual table
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT left_source_table_full_table_name, right_source_table_full_table_name,"
                + " join_type FROM "
                + virtualTables
                + " WHERE full_table_name = ?")) {
      ps.setString(1, full);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String joinType = rs.getString(3).toUpperCase(Locale.ROOT);
          if (!joinType.equals("INNER")) {
            throw new SQLException(
                full + " is ScalarDB's virtual table of an imported table; name the table itself",
                "42000");
          }
          // ScalarDB registers metadata for the source tables, not for the virtual table: the
          // keys and the user's columns are the data table's entry
          Columns columns = columns(c, metadata, rs.getString(1));
          return new TableInfo(
              ns,
              table,
              Layout.DECOUPLED_CREATED,
              unqualified(rs.getString(1)),
              unqualified(rs.getString(2)),
              table,
              columns.partitionKey,
              columns.clusteringKey,
              columns.dataColumns);
        }
      }
    }

    // 2. the user's name is the data table of a virtual table
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT full_table_name, right_source_table_full_table_name, join_type FROM "
                + virtualTables
                + " WHERE left_source_table_full_table_name = ?")) {
      ps.setString(1, full);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          String joinType = rs.getString(3).toUpperCase(Locale.ROOT);
          if (!joinType.equals("LEFT_OUTER")) {
            throw new SQLException(
                full + " is the data table of a ScalarDB table; name the ScalarDB table instead",
                "42000");
          }
          String virtual = rs.getString(1);
          Columns columns = columns(c, metadata, full); // the user's name is the data table
          return new TableInfo(
              ns,
              table,
              Layout.DECOUPLED_IMPORTED,
              table,
              unqualified(rs.getString(2)),
              unqualified(virtual),
              columns.partitionKey,
              columns.clusteringKey,
              columns.dataColumns);
        }
      }
    }

    // 3. a classic-layout table, or 4. a plain one
    Columns columns = columns(c, metadata, full);
    if (columns.transactional) {
      return new TableInfo(
          ns,
          table,
          Layout.CLASSIC,
          table,
          null,
          null,
          columns.partitionKey,
          columns.clusteringKey,
          columns.dataColumns);
    }
    return new TableInfo(
        ns, table, Layout.PLAIN, table, null, null, List.of(), List.of(), List.of());
  }

  private static String currentSchema(Connection c) throws SQLException {
    String schema = c.getSchema();
    if (schema == null || schema.isEmpty()) {
      schema = c.getCatalog(); // MySQL and MariaDB report the schema as the catalog
    }
    if (schema == null || schema.isEmpty()) {
      throw new SQLException(
          "The connection has no current schema; qualify the table name", "3F000");
    }
    return schema;
  }

  private static String quote(String q, String identifier) {
    return q.isEmpty() ? identifier : q + identifier + q;
  }

  private static String unqualified(String fullTableName) {
    int dot = fullTableName.indexOf('.');
    return dot < 0 ? fullTableName : fullTableName.substring(dot + 1);
  }

  /** The columns of one entry of ScalarDB's {@code metadata} table. */
  private static final class Columns {
    final List<String> partitionKey = new ArrayList<>();
    final List<String> clusteringKey = new ArrayList<>();
    final List<String> dataColumns = new ArrayList<>();
    boolean transactional;
  }

  private static Columns columns(Connection c, String metadata, String fullTableName)
      throws SQLException {
    Columns out = new Columns();
    Set<String> names = new java.util.LinkedHashSet<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT column_name, key_type FROM "
                + metadata
                + " WHERE full_table_name = ? ORDER BY ordinal_position")) {
      ps.setString(1, fullTableName);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1);
          String keyType = rs.getString(2);
          names.add(name);
          if (keyType != null) {
            String k = keyType.toUpperCase(Locale.ROOT);
            if (k.equals("PARTITION")) {
              out.partitionKey.add(name);
            } else if (k.equals("CLUSTERING")) {
              out.clusteringKey.add(name);
            }
          }
        }
      }
    }
    out.transactional = names.containsAll(TRANSACTION_COLUMNS);
    for (String name : names) {
      if (TRANSACTION_COLUMNS.contains(name)) {
        continue;
      }
      if (out.transactional
          && name.startsWith(BEFORE_PREFIX)
          && names.contains(name.substring(BEFORE_PREFIX.length()))) {
        continue; // the before image of a data column
      }
      out.dataColumns.add(name);
    }
    return out;
  }
}
