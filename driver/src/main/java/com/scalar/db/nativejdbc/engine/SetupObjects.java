package com.scalar.db.nativejdbc.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The read views and in-flight indexes the setup creates, as the engine's catalog lists them: the
 * one place that decides whether a table is set up, for the catalog and for the setup alike. Names
 * compare exactly, except where the database compares table names without regard to case.
 */
public final class SetupObjects {

  private final Engine engine;
  private final boolean caseInsensitive;
  private final Map<String, List<String>> viewColumns = new HashMap<>();
  private final Map<String, List<String>> tableColumns = new HashMap<>();
  private final Set<String> indexes = new HashSet<>();
  private final Map<String, Map<String, String>> beforeTypes = new HashMap<>();

  SetupObjects(Engine engine, boolean caseInsensitive) {
    this.engine = engine;
    this.caseInsensitive = caseInsensitive;
  }

  /**
   * Returns an empty listing: nothing is set up.
   *
   * @param engine the engine
   * @return the empty listing
   */
  public static SetupObjects none(Engine engine) {
    return new SetupObjects(engine, false);
  }

  void addViewColumn(String schema, String view, String column) {
    viewColumns.computeIfAbsent(key(schema, view), k -> new ArrayList<>()).add(column);
  }

  void addTableColumn(String schema, String table, String column) {
    tableColumns.computeIfAbsent(key(schema, table), k -> new ArrayList<>()).add(column);
  }

  /**
   * Orders {@code columns} as the table holds them, which {@code SELECT *} returns; a column the
   * listing lacks goes last.
   *
   * @param schema the table's schema
   * @param table the name statements use for the table
   * @param columns the columns, reordered in place
   */
  public void order(String schema, String table, List<String> columns) {
    List<String> found = tableColumns.getOrDefault(key(schema, table), List.of());
    boolean anyCase = engine != Engine.POSTGRESQL && engine != Engine.ORACLE;
    List<String> keys = new ArrayList<>();
    for (String c : found) {
      keys.add(anyCase ? c.toLowerCase(Locale.ROOT) : c);
    }
    columns.sort(
        Comparator.comparingInt(
            c -> {
              int i = keys.indexOf(anyCase ? c.toLowerCase(Locale.ROOT) : c);
              return i < 0 ? Integer.MAX_VALUE : i;
            }));
  }

  /**
   * Returns whether a view of this name exists with these columns in another order: the read view a
   * setup before todo 316 created, in the key-first order.
   *
   * @param schema the schema
   * @param view the view's name
   * @param columns the expected columns
   * @return whether it holds them in another order
   */
  public boolean viewReorders(String schema, String view, List<String> columns) {
    List<String> found = viewColumns.get(key(schema, view));
    if (found == null || viewMatches(schema, view, columns)) {
      return false;
    }
    List<String> a = new ArrayList<>(found);
    List<String> b = new ArrayList<>(columns);
    boolean anyCase = engine != Engine.POSTGRESQL && engine != Engine.ORACLE;
    if (anyCase) {
      a.replaceAll(c -> c.toLowerCase(Locale.ROOT));
      b.replaceAll(c -> c.toLowerCase(Locale.ROOT));
    }
    a.sort(null);
    b.sort(null);
    return a.equals(b);
  }

  void addIndex(String schema, String table, String index) {
    indexes.add(key(schema, table, index));
  }

  void addBeforeType(String schema, String table, String column, String type) {
    beforeTypes.computeIfAbsent(key(schema, table), k -> new HashMap<>()).put(column, type);
  }

  /**
   * Returns the type to cast each column's before image to, where ScalarDB keeps it in a wider type
   * than the column's own, which a {@code UNION} of the two would report: on MySQL and MariaDB a
   * {@code CHAR} or {@code VARCHAR} column, whose before image is a {@code LONGTEXT}, and a {@code
   * DATETIME} or {@code TIMESTAMP} column, whose before image has three fractional digits; on
   * PostgreSQL and SQL Server a {@code SMALLINT} or {@code TINYINT} column, whose before image and
   * metadata-table key are {@code INT} (todo 356).
   *
   * @param schema the schema
   * @param dataTable the data table
   * @return the cast's {@code AS} part and collation, by column; empty on Oracle
   */
  public Map<String, String> beforeTypes(String schema, String dataTable) {
    return beforeTypes.getOrDefault(key(schema, dataTable), Map.of());
  }

  /**
   * Returns whether a table is set up: its in-flight index exists and its read view exists with
   * exactly the table's columns, in order. The view is created last, so it also marks a backfill
   * that completed.
   *
   * @param schema the table's schema
   * @param table the name statements use for the table
   * @param metadataTable the table's transaction metadata table
   * @param columns the table's columns, in order
   * @return whether the table is set up
   */
  public boolean isSetUp(String schema, String table, String metadataTable, List<String> columns) {
    return hasIndex(schema, metadataTable, engine.inflightIndexName(table))
        && viewMatches(schema, engine.readViewName(table), columns);
  }

  /**
   * Returns whether a view of this name exists, whatever its columns.
   *
   * @param schema the schema
   * @param view the view's name
   * @return whether it exists
   */
  public boolean hasView(String schema, String view) {
    return viewColumns.containsKey(key(schema, view));
  }

  /**
   * Returns whether a view of this name exists with exactly these columns, in order.
   *
   * @param schema the schema
   * @param view the view's name
   * @param columns the expected columns
   * @return whether it matches
   */
  public boolean viewMatches(String schema, String view, List<String> columns) {
    List<String> found = viewColumns.get(key(schema, view));
    if (found == null || found.size() != columns.size()) {
      return false;
    }
    boolean anyCase = engine != Engine.POSTGRESQL && engine != Engine.ORACLE;
    for (int i = 0; i < columns.size(); i++) {
      String a = found.get(i);
      String b = columns.get(i);
      if (anyCase ? !a.equalsIgnoreCase(b) : !a.equals(b)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether a usable index of this name exists on the table.
   *
   * @param schema the schema
   * @param table the indexed table
   * @param index the index's name
   * @return whether it exists
   */
  public boolean hasIndex(String schema, String table, String index) {
    return indexes.contains(key(schema, table, index));
  }

  private String key(String... parts) {
    String k = String.join("\u0000", parts);
    return caseInsensitive ? k.toLowerCase(Locale.ROOT) : k;
  }
}
