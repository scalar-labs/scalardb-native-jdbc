package com.scalar.db.nativejdbc.setup;

import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

  /**
   * Whether the table's setup indexes follow the data table's key order: the in-flight index, where
   * there is one, built over it, and the metadata table reaching its rows by it. A setup before
   * todo 334's (C) built the in-flight index in the catalog's order; {@code check} reports such a
   * table as not set up, and the setup rebuilds it.
   */
  static boolean inOrder(Connection c, Engine engine, TableInfo t, String meta)
      throws SQLException {
    List<String> key = primaryKey(c, engine, t.namespace(), t.dataTable(), t.keyColumns());
    return inflightInOrder(c, engine, t, meta, key)
        && (t.layout() == Layout.INLINE || keyIndexInPlace(c, engine, t, meta, key));
  }

  /** Whether the in-flight index, where there is one, has the columns the setup builds over key. */
  static boolean inflightInOrder(
      Connection c, Engine engine, TableInfo t, String meta, List<String> key) throws SQLException {
    List<String> found = indexColumns(c, engine, t.namespace(), meta, t.inflightIndex(engine));
    return found == null || same(engine, found, inflightColumns(engine, t, key));
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
