package com.scalar.db.nativejdbc.catalog;

import java.util.ArrayList;
import java.util.List;
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

  TableInfo(
      String namespace,
      String table,
      Layout layout,
      String dataTable,
      @Nullable String metadataTable,
      @Nullable String virtualTable,
      List<String> partitionKey,
      List<String> clusteringKey,
      List<String> dataColumns) {
    this.namespace = namespace;
    this.table = table;
    this.layout = layout;
    this.dataTable = dataTable;
    this.metadataTable = metadataTable;
    this.virtualTable = virtualTable;
    this.partitionKey = List.copyOf(partitionKey);
    this.clusteringKey = List.copyOf(clusteringKey);
    this.dataColumns = List.copyOf(dataColumns);
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
   * classic and imported layouts, {@code <name>_data} for a created decoupled table.
   *
   * @return the data table name, unqualified
   */
  public String dataTable() {
    return dataTable;
  }

  /**
   * Returns the physical table that holds the transaction metadata for a decoupled layout, or
   * {@code null} for the plain and classic layouts.
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
