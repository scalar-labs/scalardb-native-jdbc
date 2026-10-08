package com.scalar.db.nativejdbc.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.service.TransactionFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The catalog against a real PostgreSQL with tables created and imported by ScalarDB itself: one of
 * each layout, plus the names a user statement must not use.
 */
class CatalogIntegrationTest {

  private static final String NS = "ns";
  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  private static PostgreSQLContainer postgres;
  private static Connection connection;

  @BeforeAll
  static void setUp() throws Exception {
    postgres = new PostgreSQLContainer("postgres:16");
    postgres.start();

    connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    try (Statement st = connection.createStatement()) {
      st.execute("CREATE SCHEMA " + NS);
      st.execute("CREATE TABLE " + NS + ".imported (id INT PRIMARY KEY, x TEXT, y INT)");
      st.execute("CREATE TABLE " + NS + ".plain (id INT PRIMARY KEY, x TEXT)");
      st.execute("CREATE TABLE " + NS + ".imported_classic (id INT PRIMARY KEY, x TEXT)");
    }

    Properties props = new Properties();
    props.setProperty("scalar.db.storage", "jdbc");
    props.setProperty("scalar.db.contact_points", postgres.getJdbcUrl());
    props.setProperty("scalar.db.username", postgres.getUsername());
    props.setProperty("scalar.db.password", postgres.getPassword());
    props.setProperty("scalar.db.transaction_manager", "consensus-commit");
    // decoupled tables require at least REPEATABLE READ on ScalarDB's own connections
    props.setProperty("scalar.db.jdbc.isolation_level", "REPEATABLE_READ");
    DistributedTransactionAdmin admin = TransactionFactory.create(props).getTransactionAdmin();
    try {
      admin.createNamespace(NS, true);
      TableMetadata created =
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("k", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .addClusteringKey("k")
              .build();
      admin.createTable(NS, "created", created, DECOUPLED);
      admin.createTable(
          NS,
          "classic",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .build());
      admin.importTable(NS, "imported", DECOUPLED, Map.of());
      admin.importTable(NS, "imported_classic", Map.of(), Map.of());
    } finally {
      admin.close();
    }
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (connection != null) {
      connection.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @Test
  void createdDecoupledTable() throws SQLException {
    TableInfo t = new Catalog(Catalog.DEFAULT_METADATA_SCHEMA).lookup(connection, NS, "created");

    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_CREATED);
    assertThat(t.dataTable()).isEqualTo("created_data");
    assertThat(t.metadataTable()).isEqualTo("created_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo("created");
    assertThat(t.partitionKey()).containsExactly("id");
    assertThat(t.clusteringKey()).containsExactly("k");
    assertThat(t.keyColumns()).containsExactly("id", "k");
    assertThat(t.dataColumns()).containsExactly("id", "k", "x");
    assertThat(t.nonKeyColumns()).containsExactly("x");
  }

  @Test
  void importedDecoupledTable() throws SQLException {
    TableInfo t = new Catalog(Catalog.DEFAULT_METADATA_SCHEMA).lookup(connection, NS, "imported");

    assertThat(t.layout()).isEqualTo(Layout.DECOUPLED_IMPORTED);
    assertThat(t.dataTable()).isEqualTo("imported");
    assertThat(t.metadataTable()).isEqualTo("imported_tx_metadata");
    assertThat(t.virtualTable()).isEqualTo("imported_scalardb");
    assertThat(t.keyColumns()).containsExactly("id");
    assertThat(t.dataColumns()).containsExactly("id", "x", "y");
  }

  @Test
  void classicTable() throws SQLException {
    TableInfo t = new Catalog(Catalog.DEFAULT_METADATA_SCHEMA).lookup(connection, NS, "classic");

    assertThat(t.layout()).isEqualTo(Layout.CLASSIC);
    assertThat(t.dataTable()).isEqualTo("classic");
    assertThat(t.metadataTable()).isNull();
    assertThat(t.keyColumns()).containsExactly("id");
    assertThat(t.dataColumns()).containsExactly("id", "x");
  }

  @Test
  void importedClassicTable_hasTheMetadataColumnsAddedToTheTableItself() throws SQLException {
    TableInfo t =
        new Catalog(Catalog.DEFAULT_METADATA_SCHEMA).lookup(connection, NS, "imported_classic");

    assertThat(t.layout()).isEqualTo(Layout.CLASSIC);
    assertThat(t.dataTable()).isEqualTo("imported_classic");
    assertThat(t.metadataTable()).isNull();
    assertThat(t.keyColumns()).containsExactly("id");
    assertThat(t.dataColumns()).containsExactly("id", "x");
  }

  @Test
  void plainTable_andUnknownTable() throws SQLException {
    Catalog catalog = new Catalog(Catalog.DEFAULT_METADATA_SCHEMA);
    assertThat(catalog.lookup(connection, NS, "plain").layout()).isEqualTo(Layout.PLAIN);
    assertThat(catalog.lookup(connection, NS, "nope").layout()).isEqualTo(Layout.PLAIN);
  }

  @Test
  void namesAUserStatementMustNotUseAreRefused() {
    Catalog catalog = new Catalog(Catalog.DEFAULT_METADATA_SCHEMA);
    assertThatThrownBy(() -> catalog.lookup(connection, NS, "created_data"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("data table");
    assertThatThrownBy(() -> catalog.lookup(connection, NS, "imported_scalardb"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("virtual table");
  }

  @Test
  void lookupIsCachedAndInvalidatable() throws SQLException {
    Catalog catalog = new Catalog(Catalog.DEFAULT_METADATA_SCHEMA);
    TableInfo first = catalog.lookup(connection, NS, "created");
    assertThat(catalog.lookup(connection, NS, "created")).isSameAs(first);
    catalog.invalidate();
    assertThat(catalog.lookup(connection, NS, "created")).isNotSameAs(first);
  }
}
