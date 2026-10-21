package com.scalar.db.nativejdbc.catalog;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** What the driver knows about one user table name. */
public final class TableInfo {

  private final String namespace;
  private final String table;
  private final Layout layout;
  private final String dataTable;
  private final @Nullable String metadataTable;
  private final @Nullable String virtualTable;
  private final List<String> partitionKey;
  private final List<String> clusteringKey;
  private final List<String> dataColumns;
  private final List<String> indexedColumns;
  private final List<String> floatColumns;
  private final Map<String, String> beforeTypes;
  private final boolean setUp;

  TableInfo(
      String namespace,
      String table,
      Layout layout,
      String dataTable,
      @Nullable String metadataTable,
      @Nullable String virtualTable,
      List<String> partitionKey,
      List<String> clusteringKey,
      List<String> dataColumns,
      List<String> indexedColumns,
      boolean setUp) {
    this(
        namespace,
        table,
        layout,
        dataTable,
        metadataTable,
        virtualTable,
        partitionKey,
        clusteringKey,
        dataColumns,
        indexedColumns,
        List.of(),
        Map.of(),
        setUp);
  }

  TableInfo(
      String namespace,
      String table,
      Layout layout,
      String dataTable,
      @Nullable String metadataTable,
      @Nullable String virtualTable,
      List<String> partitionKey,
      List<String> clusteringKey,
      List<String> dataColumns,
      List<String> indexedColumns,
      List<String> floatColumns,
      Map<String, String> beforeTypes,
      boolean setUp) {
    this.namespace = namespace;
    this.table = table;
    this.layout = layout;
    this.dataTable = dataTable;
    this.metadataTable = metadataTable;
    this.virtualTable = virtualTable;
    this.partitionKey = List.copyOf(partitionKey);
    this.clusteringKey = List.copyOf(clusteringKey);
    this.dataColumns = List.copyOf(dataColumns);
    this.indexedColumns = List.copyOf(indexedColumns);
    this.floatColumns = List.copyOf(floatColumns);
    this.beforeTypes = Map.copyOf(beforeTypes);
    this.setUp = setUp;
  }

  /**
   * Returns whether the driver's setup has been run for this table: its in-flight index exists and
   * its read view exists with the table's columns, as seen when the catalog was loaded. Always true
   * for a table the setup does not concern yet: a plain table, and an inline one until inline
   * support adds its setup.
   *
   * @return whether the table is set up
   */
  public boolean isSetUp() {
    return setUp;
  }

  /**
   * Returns the before image of a column of a row in flight, as a select-list expression with the
   * column's own type: cast where ScalarDB keeps it in a wider type (todo 303).
   *
   * @param engine the engine
   * @param alias the metadata table's alias
   * @param column the data column
   * @return the expression
   */
  public String beforeImage(Engine engine, String alias, String column) {
    String b = alias + "." + engine.quote("before_" + column);
    String type = beforeTypes.get(column);
    return type == null ? b : "CAST(" + b + " AS " + type;
  }

  /**
   * Returns the name of the read view the setup creates in the table's schema, {@code rc_T} (design
   * section 3).
   *
   * @param engine the engine, which spells the affix
   * @return the view's name
   */
  public String readView(Engine engine) {
    return engine.readViewName(table);
  }

  /**
   * Returns the name of the in-flight index the setup creates on the metadata table.
   *
   * @param engine the engine, which spells the affix
   * @return the index's name
   */
  public String inflightIndex(Engine engine) {
    return engine.inflightIndexName(table);
  }

  /**
   * Returns the namespace, the schema the user's statement names or the connection's current one.
   *
   * @return the namespace
   */
  public String namespace() {
    return namespace;
  }

  /**
   * Returns the table name as the user writes it.
   *
   * @return the user-facing table name
   */
  public String table() {
    return table;
  }

  /**
   * Returns the layout.
   *
   * @return the layout
   */
  public Layout layout() {
    return layout;
  }

  /**
   * Returns whether ScalarDB manages the table, that is, whether the driver rewrites statements on
   * it.
   *
   * @return whether it is a ScalarDB table
   */
  public boolean isScalarDb() {
    return layout != Layout.PLAIN;
  }

  /**
   * Returns the physical table that holds the user's columns: the table itself for the plain,
   * inline and imported layouts, {@code <name>_data} for a created decoupled table.
   *
   * @return the data table name, unqualified
   */
  public String dataTable() {
    return dataTable;
  }

  /**
   * Returns the physical table that holds the transaction metadata for a decoupled layout, or
   * {@code null} for the plain and inline layouts.
   *
   * @return the metadata table name, unqualified, or {@code null}
   */
  public @Nullable String metadataTable() {
    return metadataTable;
  }

  /**
   * Returns ScalarDB's virtual table for a decoupled layout, or {@code null} otherwise.
   *
   * @return the virtual table name, unqualified, or {@code null}
   */
  public @Nullable String virtualTable() {
    return virtualTable;
  }

  /**
   * Returns the partition key columns, in order.
   *
   * @return the partition key
   */
  public List<String> partitionKey() {
    return partitionKey;
  }

  /**
   * Returns the clustering key columns, in order; empty when there are none.
   *
   * @return the clustering key
   */
  public List<String> clusteringKey() {
    return clusteringKey;
  }

  /**
   * Returns the primary key: the partition key followed by the clustering key.
   *
   * @return the key columns
   */
  public List<String> keyColumns() {
    List<String> out = new ArrayList<>(partitionKey);
    out.addAll(clusteringKey);
    return out;
  }

  /**
   * Returns the user's columns, keys included, in ordinal order; empty for a plain table, whose
   * columns the driver never needs.
   *
   * @return the data columns
   */
  public List<String> dataColumns() {
    return dataColumns;
  }

  /**
   * Returns the columns of ScalarDB's FLOAT type, single precision.
   *
   * @return the column names
   */
  public List<String> floatColumns() {
    return floatColumns;
  }

  /**
   * Returns the columns with a ScalarDB secondary index, in ordinal order.
   *
   * @return the indexed columns
   */
  public List<String> indexedColumns() {
    return indexedColumns;
  }

  /**
   * Returns the user's non-key columns, those that have a {@code before_} twin in the metadata.
   *
   * @return the non-key data columns
   */
  public List<String> nonKeyColumns() {
    List<String> keys = keyColumns();
    List<String> out = new ArrayList<>();
    for (String c : dataColumns) {
      if (!keys.contains(c)) {
        out.add(c);
      }
    }
    return out;
  }

  @Override
  public String toString() {
    return namespace + "." + table + " [" + layout + "]";
  }
}
