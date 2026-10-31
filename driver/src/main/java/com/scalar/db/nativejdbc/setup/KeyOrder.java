package com.scalar.db.nativejdbc.setup;

import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The key order the setup's indexes follow (design section 3, todo 334): the data table's primary
 * key as the engine's constraint orders it, which ScalarDB's import does not keep for an imported
 * composite key, whose metadata table it keys by column name; and the columns of an index as the
 * engine lists them. Read through {@link DatabaseMetaData}, on every engine.
 */
final class KeyOrder {

  private KeyOrder() {}

  /**
   * The table's primary key columns by {@code KEY_SEQ}; {@code fallback} where the engine lists
   * none.
   */
  static List<String> primaryKey(
      Connection c, Engine engine, String schema, String table, List<String> fallback)
      throws SQLException {
    Map<Integer, String> bySeq = new TreeMap<>();
    DatabaseMetaData md = c.getMetaData();
    try (ResultSet rs = md.getPrimaryKeys(catalog(engine, schema), schema, table)) {
      while (rs.next()) {
        bySeq.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
      }
    }
    return bySeq.isEmpty() ? fallback : List.copyOf(bySeq.values());
  }

  /** The index's columns in order, or null where the table has no index of that name. */
  static @Nullable List<String> indexColumns(
      Connection c, Engine engine, String schema, String table, String index) throws SQLException {
    Map<Integer, String> byPosition = new TreeMap<>();
    DatabaseMetaData md = c.getMetaData();
    // approximate: on Oracle an exact listing analyzes the table, over the setup's locked
    // statistics
    try (ResultSet rs = md.getIndexInfo(catalog(engine, schema), schema, table, false, true)) {
      while (rs.next()) {
        String name = rs.getString("INDEX_NAME");
        String column = rs.getString("COLUMN_NAME");
        if (name != null && column != null && name.equalsIgnoreCase(index)) {
          byPosition.put((int) rs.getShort("ORDINAL_POSITION"), column);
        }
      }
    }
    return byPosition.isEmpty() ? null : List.copyOf(byPosition.values());
  }

  /**
   * The in-flight index's columns as the setup builds them over {@code key}: the key alone where
   * the index is filtered to the rows in flight (PostgreSQL, SQL Server on a metadata table),
   * {@code tx_state} first otherwise.
   */
  static List<String> inflightColumns(Engine engine, TableInfo t, List<String> key) {
    boolean filtered =
        engine == Engine.POSTGRESQL || (engine == Engine.SQLSERVER && t.layout() != Layout.INLINE);
    List<String> out = new ArrayList<>();
    if (!filtered) {
      out.add("tx_state");
    }
    out.addAll(key);
    return out;
  }

  /** What {@link #inflightMismatch} reports of an Oracle in-flight index in the plain form. */
  static final String PLAIN_FORM = "in-flight index in the plain form";

  /** What it reports of an index over other columns or in another order. */
  static final String OTHER_ORDER = "in-flight index in another column order";

  /**
   * Why the table's setup indexes are not as the setup builds them, or null where they are: the
   * in-flight index, where there is one, built over the data table's key order, in the
   * function-based form on Oracle, and the metadata table reaching its rows by that order. A setup
   * before todo 334's (C) built the in-flight index in the catalog's order, and one before todo 364
   * Oracle's in the plain form; {@code check} reports such a table as not set up, and the setup
   * rebuilds it.
   */
  static @Nullable String mismatch(Connection c, Engine engine, TableInfo t, String meta)
      throws SQLException {
    List<String> key = primaryKey(c, engine, t.namespace(), t.dataTable(), t.keyColumns());
    String inflight = inflightMismatch(c, engine, t, meta, key);
    if (inflight != null) {
      return inflight;
    }
    return t.layout() == Layout.INLINE || keyIndexInPlace(c, engine, t, meta, key)
        ? null
        : "key index not in the data table's key order";
  }

  /**
   * Why the in-flight index, where there is one, is not the one the setup builds over key, or null
   * where it is: {@link #PLAIN_FORM} for Oracle's plain {@code (tx_state, key)} index, {@link
   * #OTHER_ORDER} for other columns or another order.
   */
  static @Nullable String inflightMismatch(
      Connection c, Engine engine, TableInfo t, String meta, List<String> key) throws SQLException {
    String index = t.inflightIndex(engine);
    if (engine == Engine.ORACLE) {
      Expressions found = oracleExpressions(c, t.namespace(), meta, index);
      if (found == null) {
        return null;
      }
      if (found.plain) {
        return PLAIN_FORM;
      }
      return same(engine, found.columns, inflightColumns(engine, t, key)) ? null : OTHER_ORDER;
    }
    List<String> found = indexColumns(c, engine, t.namespace(), meta, index);
    return found == null || same(engine, found, inflightColumns(engine, t, key))
        ? null
        : OTHER_ORDER;
  }

  /**
   * The in-flight index's columns in order, or null where the table has no index of that name: on
   * Oracle each expression as the column it reads in flight and a plain column as itself.
   */
  static @Nullable List<String> inflightIndexColumns(
      Connection c, Engine engine, String schema, String table, String index) throws SQLException {
    if (engine != Engine.ORACLE) {
      return indexColumns(c, engine, schema, table, index);
    }
    Expressions found = oracleExpressions(c, schema, table, index);
    return found == null ? null : found.columns;
  }

  /** An Oracle index's columns, each expression as the column it reads in flight. */
  private static final class Expressions {
    final List<String> columns;
    // a column of the index is a plain column, not an expression
    final boolean plain;

    Expressions(List<String> columns, boolean plain) {
      this.columns = columns;
      this.plain = plain;
    }
  }

  // the in-flight index's expression as Oracle stores it in ALL_IND_EXPRESSIONS, each name quoted
  private static final Pattern IN_FLIGHT =
      Pattern.compile(
          "CASE \"((?:[^\"]|\"\")+)\" WHEN 1 THEN \"((?:[^\"]|\"\")+)\""
              + " WHEN 2 THEN \"((?:[^\"]|\"\")+)\" END");

  /**
   * An Oracle index's columns in order, or null where the table has no index of that name: each
   * expression {@code CASE tx_state WHEN 1 THEN c WHEN 2 THEN c END} as {@code c}, anything else as
   * an empty name that matches no column; {@code getIndexInfo} lists an expression by its hidden
   * column's name, {@code SYS_NCnnnnn$} (todo 364).
   */
  private static @Nullable Expressions oracleExpressions(
      Connection c, String schema, String table, String index) throws SQLException {
    Map<Integer, String> byPosition = new TreeMap<>();
    boolean plain = false;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT c.COLUMN_POSITION, e.COLUMN_EXPRESSION, c.COLUMN_NAME FROM ALL_IND_COLUMNS c"
                + " LEFT JOIN ALL_IND_EXPRESSIONS e ON e.INDEX_OWNER = c.INDEX_OWNER"
                + " AND e.INDEX_NAME = c.INDEX_NAME AND e.COLUMN_POSITION = c.COLUMN_POSITION"
                + " WHERE c.TABLE_OWNER = ? AND c.TABLE_NAME = ? AND c.INDEX_NAME = ?")) {
      ps.setString(1, schema);
      ps.setString(2, table);
      ps.setString(3, index);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          int position = rs.getInt(1);
          String expression = rs.getString(2); // a LONG, read before the next column
          String column = rs.getString(3);
          if (expression == null) {
            plain = true;
            byPosition.put(position, column == null ? "" : column);
            continue;
          }
          Matcher m = IN_FLIGHT.matcher(expression.strip());
          byPosition.put(
              position,
              m.matches() && m.group(1).equals("tx_state") && m.group(2).equals(m.group(3))
                  ? m.group(2).replace("\"\"", "\"")
                  : "");
        }
      }
    }
    return byPosition.isEmpty() ? null : new Expressions(List.copyOf(byPosition.values()), plain);
  }

  /**
   * Whether the metadata table reaches its rows by {@code key} in order: its primary key in that
   * order, or the setup's key index over it.
   */
  static boolean keyIndexInPlace(
      Connection c, Engine engine, TableInfo t, String meta, List<String> key) throws SQLException {
    if (same(engine, primaryKey(c, engine, t.namespace(), meta, key), key)) {
      return true;
    }
    List<String> found = indexColumns(c, engine, t.namespace(), meta, t.keyIndex(engine));
    return found != null && same(engine, found, key);
  }

  /** Whether two column lists name the same columns in the same order, as the engine compares. */
  static boolean same(Engine engine, List<String> a, List<String> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!engine.columnKey(a.get(i)).equals(engine.columnKey(b.get(i)))) {
        return false;
      }
    }
    return true;
  }

  // MySQL and MariaDB name a database by the catalog; the others by the schema, the catalog the
  // connection's own
  private static @Nullable String catalog(Engine engine, String schema) {
    return engine == Engine.MYSQL || engine == Engine.MARIADB ? schema : null;
  }
}
