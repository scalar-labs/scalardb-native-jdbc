package com.scalar.db.nativejdbc.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.nativejdbc.catalog.Catalog.MetadataColumn;
import com.scalar.db.nativejdbc.catalog.Catalog.Snapshot;
import com.scalar.db.nativejdbc.catalog.Catalog.VirtualTable;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.engine.Engine.QualifiedName;
import com.scalar.db.nativejdbc.engine.SetupObjects;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The classification of a stored name from ScalarDB's catalog rows, with no database. */
class CatalogTest {

  private static final List<String> TX =
      List.of(
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

  private static List<MetadataColumn> columns(String... namesWithKeys) {
    List<MetadataColumn> out = new ArrayList<>();
    for (String n : namesWithKeys) {
      int colon = n.indexOf(':');
      @Nullable String key = colon < 0 ? null : n.substring(colon + 1);
      out.add(new MetadataColumn(colon < 0 ? n : n.substring(0, colon), key, false, null));
    }
    return out;
  }

  private static Snapshot snapshot() {
    return snapshot(false);
  }

  private static Snapshot snapshot(boolean caseInsensitive) {
    Map<String, List<MetadataColumn>> metadata = new HashMap<>();
    metadata.put("ns.Orders_data", columns("Id:PARTITION", "K:CLUSTERING", "Amount"));
    List<MetadataColumn> ordersMeta = columns("Id:PARTITION", "K:CLUSTERING", "before_Amount");
    for (String t : TX) {
      ordersMeta.add(new MetadataColumn(t, null, false, null));
    }
    metadata.put("ns.Orders_tx_metadata", ordersMeta);
    metadata.put("app.orders", columns("id:PARTITION", "x", "y"));
    List<MetadataColumn> inline = columns("id:PARTITION", "x", "before_x");
    for (String t : TX) {
      inline.add(new MetadataColumn(t, null, false, null));
    }
    metadata.put("ns.inline", inline);
    metadata.put("app.storage_only", columns("id:PARTITION", "x"));
    return new Snapshot(
        true,
        caseInsensitive,
        List.of(
            new VirtualTable("ns.Orders", "ns.Orders_data", "ns.Orders_tx_metadata", "INNER"),
            new VirtualTable(
                "app.orders_scalardb", "app.orders", "app.orders_tx_metadata", "LEFT_OUTER")),
        metadata,
        SetupObjects.none(Engine.POSTGRESQL),
        Instant.EPOCH);
  }

  @Test
  void withoutTheVirtualTables_aMetadataTableIsNoInlineTable() throws SQLException {
    // Oracle reports virtual_tables it may not read as missing (todo 287)
    Map<String, List<MetadataColumn>> metadata = new HashMap<>();
    List<MetadataColumn> meta = columns("Id:PARTITION", "before_Amount");
    List<MetadataColumn> inline = columns("id:PARTITION", "x", "before_x", "before_y");
    // before_y is a data column of its own, with its before image
    inline.addAll(columns("before_before_y"));
    for (String t : TX) {
      meta.add(new MetadataColumn(t, null, false, null));
      inline.add(new MetadataColumn(t, null, false, null));
    }
    metadata.put("ns.Orders_tx_metadata", meta);
    metadata.put("ns.inline", inline);
    Snapshot s =
        new Snapshot(
            true, false, List.of(), metadata, SetupObjects.none(Engine.POSTGRESQL), Instant.EPOCH);
    assertThat(s.inlineTables()).containsExactly("ns.inline");
    assertThat(s.classify(new QualifiedName("ns", "Orders_tx_metadata")).layout())
        .isEqualTo(Layout.PLAIN);
    TableInfo t = s.classify(new QualifiedName("ns", "inline"));
    assertThat(t.layout()).isEqualTo(Layout.INLINE);
    assertThat(t.dataColumns()).containsExactly("id", "x", "before_y");
  }

  @Test
  void createdDecoupledTable_isTheVirtualName_withTheDataTablesColumns() throws SQLException {
    TableInfo t = snapshot().classify(new QualifiedName("ns", "Orders"));
    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_CREATED);
    assertThat(t.namespace()).isEqualTo("ns");
    assertThat(t.table()).isEqualTo("Orders");
    assertThat(t.dataTable()).isEqualTo("Orders_data");
    assertThat(t.metadataTable()).isEqualTo("Orders_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo("Orders");
    assertThat(t.keyColumns()).containsExactly("Id", "K");
    assertThat(t.dataColumns()).containsExactly("Id", "K", "Amount");
    assertThat(t.nonKeyColumns()).containsExactly("Amount");
  }

  @Test
  void importedDecoupledTable_isTheLeftSource() throws SQLException {
    TableInfo t = snapshot().classify(new QualifiedName("app", "orders"));
    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_IMPORTED);
    assertThat(t.dataTable()).isEqualTo("orders");
    assertThat(t.metadataTable()).isEqualTo("orders_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo("orders_scalardb");
    assertThat(t.keyColumns()).containsExactly("id");
    assertThat(t.dataColumns()).containsExactly("id", "x", "y");
  }

  @Test
  void inlineTable_carriesTheTransactionColumns_whichAreNotDataColumns() throws SQLException {
    TableInfo t = snapshot().classify(new QualifiedName("ns", "inline"));
    assertThat(t.layout()).isEqualTo(Layout.INLINE);
    assertThat(t.metadataTable()).isEqualTo("inline"); // the metadata is in the row
    assertThat(t.dataColumns()).containsExactly("id", "x");
  }

  @Test
  void plainTables_includeAScalarDbStorageOnlyTable_andAnythingUnknown() throws SQLException {
    assertThat(snapshot().classify(new QualifiedName("app", "storage_only")).layout())
        .isEqualTo(Layout.PLAIN);
    assertThat(snapshot().classify(new QualifiedName("app", "nope")).layout())
        .isEqualTo(Layout.PLAIN);
  }

  @Test
  void theThreeNamesAStatementMustNotUse_areRefused() {
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("ns", "Orders_data")))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("data table")
        .extracting("SQLState")
        .isEqualTo("42000");
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("ns", "Orders_tx_metadata")))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("transaction metadata table");
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("app", "orders_tx_metadata")))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("transaction metadata table");
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("app", "orders_scalardb")))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("virtual table");
  }

  @Test
  void storedSpellingIsExact_theEngineHasAlreadyResolvedIt() throws SQLException {
    assertThat(snapshot().classify(new QualifiedName("ns", "orders")).layout())
        .isEqualTo(Layout.PLAIN);
    assertThat(snapshot().classify(new QualifiedName("APP", "orders")).layout())
        .isEqualTo(Layout.PLAIN);
  }

  @Test
  void whereTheDatabaseIgnoresCase_theRecordedSpellingIsMatchedThatWay() throws SQLException {
    // SQL Server, or MySQL with lower_case_table_names=1: ScalarDB recorded app.orders and
    // ns.Orders; the engine stores and resolves Orders and ns.orders
    Snapshot ci = snapshot(true);
    TableInfo imported = ci.classify(new QualifiedName("APP", "Orders"));
    assertThat(imported.layout()).isEqualTo(Layout.DECOUPLED_IMPORTED);
    assertThat(imported.table()).isEqualTo("Orders"); // the engine's spelling for the user's table
    assertThat(imported.metadataTable()).isEqualTo("orders_tx_metadata"); // ScalarDB's for its own
    assertThat(ci.classify(new QualifiedName("ns", "orders")).layout())
        .isEqualTo(Layout.DECOUPLED_CREATED);
    assertThat(ci.classify(new QualifiedName("NS", "INLINE")).layout()).isEqualTo(Layout.INLINE);
  }

  @Test
  void aNullJoinType_isReadAsTheOuterJoinOfAnImportedTable() throws SQLException {
    Map<String, List<MetadataColumn>> metadata = new HashMap<>();
    metadata.put("app.t", columns("id:PARTITION", "x"));
    Snapshot s =
        new Snapshot(
            true,
            false,
            List.of(new VirtualTable("app.t_scalardb", "app.t", "app.t_tx_metadata", null)),
            metadata,
            SetupObjects.none(Engine.POSTGRESQL),
            Instant.EPOCH);
    assertThat(s.classify(new QualifiedName("app", "t")).layout())
        .isEqualTo(Layout.DECOUPLED_IMPORTED);
  }

  @Test
  void aScalarDbTableWithoutAKey_isAnError_notAKeylessTable() {
    Snapshot s =
        new Snapshot(
            true,
            false,
            List.of(new VirtualTable("ns.T", "ns.T_data", "ns.T_tx_metadata", "INNER")),
            new HashMap<>(), // the data table's metadata entry is missing
            SetupObjects.none(Engine.POSTGRESQL),
            Instant.EPOCH);
    assertThatThrownBy(() -> s.classify(new QualifiedName("ns", "T")))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("no key for ns.T_data");
  }

  @Test
  void aSynonym_isRefused_whateverItStandsFor() {
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("app", "orders_syn", true)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("synonym")
        .extracting("SQLState")
        .isEqualTo("42000");
  }

  @Test
  void refusals_nameTheTableToUseInstead() {
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("ns", "Orders_data")))
        .hasMessageContaining("ns.Orders");
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("app", "orders_tx_metadata")))
        .hasMessageContaining("app.orders");
    assertThatThrownBy(() -> snapshot().classify(new QualifiedName("app", "orders_scalardb")))
        .hasMessageContaining("app.orders");
  }
}
