package com.scalar.db.nativejdbc.setup;

import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.engine.SetupObjects;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 2 on every engine: a decoupled table is refused until the setup has run; the setup
 * creates the in-flight index, backfills an imported table and creates the read view, and changes
 * nothing when run again; the view shows the committed image of each row; the teardown removes the
 * setup. Every run is limited to this test's schema.
 */
class SetupIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void setup_makesTablesUsable_backfills_isIdempotent_andTearsDown(Engines e) throws Exception {
    Engine engine = e.engine();
    String su = e.storedSchema("su");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("su"),
          "CREATE TABLE su.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO su.imp VALUES (1, 'a')",
          "INSERT INTO su.imp VALUES (2, 'b')",
          "INSERT INTO su.imp VALUES (3, 'c')",
          "CREATE TABLE su.konly (id " + e.intType() + " PRIMARY KEY)",
          "INSERT INTO su.konly VALUES (1)");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createNamespace(su, true);
      admin.importTable(su, e.storedTable("imp"), DECOUPLED, Map.of());
      admin.importTable(su, e.storedTable("konly"), DECOUPLED, Map.of());
      admin.createTable(
          su,
          "comp",
          TableMetadata.newBuilder()
              .addColumn("a", DataType.INT)
              .addColumn("b", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("b")
              .addClusteringKey("a")
              .build(),
          DECOUPLED);
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    TableInfo imp = catalog.lookup(List.of(), su, e.storedTable("imp"));
    String meta = name(engine, su, Objects.requireNonNull(imp.metadataTable()));
    String data = name(engine, su, imp.dataTable());
    String view = name(engine, su, imp.readView(engine));
    String id = engine.quote(imp.keyColumns().get(0));
    String x = engine.quote(imp.nonKeyColumns().get(0));
    try (Connection c = e.open()) {
      // ScalarDB's rollback over a row without metadata leaves an all-NULL metadata row (fact 2)
      e.execute(c, "INSERT INTO " + meta + " (" + id + ") VALUES (3)");
    }
    assertThat(imp.isSetUp()).isFalse();
    assertThat(viaDriver(e, "SELECT * FROM su.imp")).startsWith("refused 55000");

    List<Setup.Result> first = setup(e, catalog, Duration.ofMinutes(1));
    assertThat(first).hasSize(3).allMatch(Setup.Result::succeeded, "succeeded");
    assertThat(result(first, "imp").rowsStamped()).isEqualTo(3); // two missing rows, one NULL
    assertThat(result(first, "konly").rowsStamped()).isEqualTo(1);
    assertThat(result(first, "comp").rowsStamped()).isZero(); // created, not imported
    assertThat(viaDriver(e, "SELECT x FROM su.imp WHERE id = 1")).isEqualTo("read a"); // set up
    for (String table :
        List.of(e.storedTable("imp"), e.storedTable("konly"), engine.quote("comp"))) {
      TableInfo t = catalog.lookup(List.of(), su, table);
      assertThat(t.isSetUp()).as(table).isTrue();
      assertThat(
              objects(e, t)
                  .hasIndex(su, Objects.requireNonNull(t.metadataTable()), t.inflightIndex(engine)))
          .as(table)
          .isTrue();
    }
    try (Connection c = e.open()) {
      assertThat(
              rows(
                  c,
                  "SELECT "
                      + id
                      + " FROM "
                      + meta
                      + " WHERE "
                      + engine.quote("tx_id")
                      + " IS NULL"))
          .isEmpty();
      assertThat(rows(c, "SELECT " + id + " FROM " + meta)).hasSize(3);
      assertThat(rows(c, "SELECT " + x + " FROM " + view + " ORDER BY " + id))
          .containsExactly("a", "b", "c");
      TableInfo konly = catalog.lookup(List.of(), su, e.storedTable("konly"));
      assertThat(rows(c, "SELECT * FROM " + name(engine, su, konly.readView(engine)))).hasSize(1);
      TableInfo comp = catalog.lookup(List.of(), su, engine.quote("comp"));
      assertThat(rows(c, "SELECT * FROM " + name(engine, su, comp.readView(engine)))).isEmpty();
    }

    if (engine == Engine.MYSQL) {
      // recounted after the backfill, not left at the empty table's 0 (todo 362)
      try (Connection c = e.open()) {
        assertThat(
                rows(
                    c,
                    "SELECT n_rows FROM mysql.innodb_table_stats WHERE database_name = '"
                        + su
                        + "' AND table_name = '"
                        + imp.metadataTable()
                        + "'"))
            .containsExactly("3");
      }
      assertThat(result(first, "imp").actions()).contains("analyzed " + imp.metadataTable());
    }
    if (engine == Engine.ORACLE) {
      // fixed statistics assuming rows in flight, locked (todo 094): of 3 rows, 1 prepared (half,
      // rounded down), 1 deleted and 1 committed, as cumulative endpoints
      assertThat(oracleStatistics(e, su, Objects.requireNonNull(imp.metadataTable())))
          .isEqualTo("ALL FREQUENCY 1:1 2:2 3:3");
    }

    // again: nothing to create, nothing to backfill
    List<Setup.Result> again = setup(e, catalog, Duration.ofMinutes(1));
    assertThat(again).allMatch(Setup.Result::succeeded, "succeeded");
    assertThat(result(again, "imp").rowsStamped()).isZero();
    assertThat(result(again, "imp").actions()).noneMatch(a -> a.startsWith("created"));

    try (Connection c = e.open()) {
      String state = engine.quote("tx_state");
      String beforeId = engine.quote("before_tx_id");
      String beforeVersion = engine.quote("before_tx_version");
      String beforeX = engine.quote("before_" + imp.nonKeyColumns().get(0));
      e.execute(
          c,
          // 1: prepared by ScalarDB over a committed row: the before image shows
          "UPDATE " + data + " SET " + x + " = 'new' WHERE " + id + " = 1",
          "UPDATE "
              + meta
              + " SET "
              + state
              + " = 1, "
              + beforeId
              + " = 'b', "
              + beforeVersion
              + " = 1, "
              + beforeX
              + " = 'a' WHERE "
              + id
              + " = 1",
          // 2: committed by ScalarDB but not yet cleaned up (state 2): still the before image
          "UPDATE " + data + " SET " + x + " = 'new2' WHERE " + id + " = 2",
          "UPDATE "
              + meta
              + " SET "
              + state
              + " = 2, "
              + beforeId
              + " = 'b2', "
              + beforeVersion
              + " = 1, "
              + beforeX
              + " = 'b' WHERE "
              + id
              + " = 2",
          // 4: an in-flight insert: no committed image, hidden
          "INSERT INTO " + data + " VALUES (4, 'd')",
          "INSERT INTO "
              + meta
              + " ("
              + id
              + ", "
              + engine.quote("tx_id")
              + ", "
              + state
              + ", "
              + engine.quote("tx_version")
              + ") VALUES (4, 'i', 1, 1)",
          // 5: prepared over a row with NULL metadata: before_tx_version 0 marks its before image
          "INSERT INTO " + data + " VALUES (5, 'new5')",
          "INSERT INTO "
              + meta
              + " ("
              + id
              + ", "
              + engine.quote("tx_id")
              + ", "
              + state
              + ", "
              + engine.quote("tx_version")
              + ", "
              + beforeVersion
              + ", "
              + beforeX
              + ") VALUES (5, 'p', 1, 1, 0, 'e')");
      assertThat(rows(c, "SELECT " + x + " FROM " + view + " ORDER BY " + id))
          .containsExactly("a", "b", "c", "e");
    }
    // the backfill waits for row 5, which ScalarDB will settle, but not for the insert of row 4
    Setup.Result waiting = result(setup(e, catalog, Duration.ofSeconds(1)), "imp");
    assertThat((Throwable) waiting.error())
        .isInstanceOf(SQLTransientException.class)
        .hasMessageContaining(" 1 rows");

    List<Setup.Result> down;
    try (Connection c = e.open()) {
      down = Setup.teardown(catalog, c, t -> t.namespace().equals(su));
      e.execute(c, "DELETE FROM " + meta + " WHERE " + engine.quote("tx_state") + " IN (1, 2)");
    }
    assertThat(down).hasSize(3).allMatch(Setup.Result::succeeded, "succeeded");
    assertThat(catalog.lookup(List.of(), su, e.storedTable("imp")).isSetUp()).isFalse();
    if (engine == Engine.ORACLE) {
      assertThat(oracleStatistics(e, su, Objects.requireNonNull(imp.metadataTable())))
          .startsWith("null NONE");
    }
  }

  /**
   * The backfill as an option (design section 3, todo 361): an imported decoupled table set up
   * without it, its rows without a metadata row read and written through the driver at every level
   * (SERIALIZABLE where the engine has it, not Oracle), by key, by predicate and in a batch; each
   * key written gets its metadata row, a DELETE's a tombstone, and the rows not written have none.
   */
  @ParameterizedTest
  @MethodSource("engines")
  @SuppressFBWarnings(
      value = {
        "SQL_INJECTION_JDBC",
        "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
        "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"
      },
      justification = "statements written in the test, its own names and integers")
  void withoutTheBackfill_rowsWithoutMetadataAreReadAndWrittenThroughTheDriver(Engines e)
      throws Exception {
    Engine engine = e.engine();
    // a new namespace each run: ScalarDB's catalog outlives the schema a run drops
    String name = "nb" + System.currentTimeMillis() % 1_000_000;
    String ns = e.storedSchema(name);
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema(name),
          "CREATE TABLE "
              + name
              + ".imp (id "
              + e.intType()
              + " PRIMARY KEY, x "
              + e.intType()
              + ")",
          "INSERT INTO " + name + ".imp SELECT g, 0 FROM " + e.series(1, 30));
      if (!c.getAutoCommit()) {
        c.commit();
      }
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      admin.importTable(ns, e.storedTable("imp"), DECOUPLED, Map.of());
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    String table = name + ".imp";
    try {
      List<Setup.Result> results;
      try (Connection c = e.open()) {
        results =
            Setup.run(
                catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(1), List.of(), false);
      }
      assertThat(results).hasSize(1).allMatch(Setup.Result::succeeded, "succeeded");
      assertThat(results.get(0).actions()).contains("backfill skipped (--backfill runs it)");
      assertThat(results.get(0).rowsStamped()).isZero();
      TableInfo imp = catalog.lookup(List.of(), ns, e.storedTable("imp"));
      String meta = name(engine, ns, Objects.requireNonNull(imp.metadataTable()));
      String id = engine.quote(imp.keyColumns().get(0));
      try (Connection c = e.open()) {
        assertThat(rows(c, "SELECT COUNT(*) FROM " + meta)).containsExactly("0");
        if (engine == Engine.ORACLE) {
          // locked as if backfilled: the data table's row count, not the empty table's 0
          assertThat(
                  rows(
                      c,
                      "SELECT num_rows || ' ' || stattype_locked FROM all_tab_statistics WHERE"
                          + " owner = '"
                          + ns
                          + "' AND table_name = '"
                          + imp.metadataTable()
                          + "'"))
              .containsExactly("30 ALL");
          assertThat(
                  rows(
                      c,
                      "SELECT num_distinct FROM all_tab_col_statistics WHERE owner = '"
                          + ns
                          + "' AND table_name = '"
                          + imp.metadataTable()
                          + "' AND column_name = '"
                          + imp.keyColumns().get(0)
                          + "'"))
              .containsExactly("30");
        }
      }
      List<Integer> levels =
          new java.util.ArrayList<>(
              List.of(
                  Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ));
      if (engine != Engine.ORACLE) {
        levels.add(Connection.TRANSACTION_SERIALIZABLE);
      }
      Properties ttl = new Properties();
      ttl.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "59361");
      try (Connection d = e.openDriver(ttl);
          Connection v = e.open()) {
        for (int l = 0; l < levels.size(); l++) {
          int b = 1 + 10 * l; // the level's ten keys, none with a metadata row
          String at = "level " + levels.get(l);
          d.setTransactionIsolation(levels.get(l));
          d.setAutoCommit(false);
          assertThat(rows(d, "SELECT x FROM " + table + " WHERE id = " + b))
              .as(at)
              .containsExactly("0");
          assertThat(
                  rows(
                      d,
                      "SELECT COUNT(*) FROM "
                          + table
                          + " WHERE id BETWEEN "
                          + b
                          + " AND "
                          + (b + 9)))
              .as(at)
              .containsExactly("10");
          try (Statement st = d.createStatement()) {
            assertThat(st.executeUpdate("UPDATE " + table + " SET x = 1 WHERE id = " + b))
                .as(at)
                .isEqualTo(1);
            assertThat(
                    st.executeUpdate(
                        "UPDATE "
                            + table
                            + " SET x = 2 WHERE id BETWEEN "
                            + (b + 1)
                            + " AND "
                            + (b + 3)))
                .as(at)
                .isEqualTo(3);
          }
          try (java.sql.PreparedStatement ps =
              d.prepareStatement("UPDATE " + table + " SET x = 3 WHERE id = ?")) {
            for (int k = b + 4; k <= b + 6; k++) {
              ps.setInt(1, k);
              ps.addBatch();
            }
            assertThat(ps.executeBatch()).as(at).containsExactly(1, 1, 1);
          }
          try (Statement st = d.createStatement()) {
            assertThat(st.executeUpdate("DELETE FROM " + table + " WHERE id = " + (b + 7)))
                .as(at)
                .isEqualTo(1);
          }
          // its own writes, read back in the transaction
          assertThat(
                  rows(
                      d,
                      "SELECT SUM(x) FROM " + table + " WHERE id BETWEEN " + b + " AND " + (b + 9)))
              .as(at)
              .containsExactly("16");
          d.commit();
          d.setAutoCommit(true);
          assertThat(
                  rows(
                      v,
                      "SELECT COUNT(*) FROM "
                          + meta
                          + " WHERE "
                          + id
                          + " BETWEEN "
                          + b
                          + " AND "
                          + (b + 9)
                          + " AND "
                          + engine.quote("tx_state")
                          + " = 3"))
              .as(at + ": the keys written, the DELETE's tombstone among them")
              .containsExactly("8");
          assertThat(
                  rows(
                      v,
                      "SELECT COUNT(*) FROM "
                          + table
                          + " WHERE id BETWEEN "
                          + b
                          + " AND "
                          + (b + 9)))
              .as(at)
              .containsExactly("9");
        }
      }
    } finally {
      try (Connection c = e.open()) {
        Setup.teardown(catalog, c, t -> t.namespace().equals(ns));
        e.dropSchemas(c, ns);
      }
    }
  }

  /**
   * Oracle: whether the table's statistics are locked, the histogram type of {@code tx_state}, and
   * its endpoints as value:cumulative rows.
   */
  private static String oracleStatistics(Engines e, String owner, String table)
      throws SQLException {
    try (Connection c = e.open()) {
      String locked =
          rows(
                  c,
                  "SELECT stattype_locked FROM all_tab_statistics WHERE owner = '"
                      + owner
                      + "' AND table_name = '"
                      + table
                      + "' AND object_type = 'TABLE'")
              .get(0);
      String histogram =
          rows(
                  c,
                  "SELECT histogram FROM all_tab_col_statistics WHERE owner = '"
                      + owner
                      + "' AND table_name = '"
                      + table
                      + "' AND column_name = 'tx_state'")
              .get(0);
      List<String> endpoints =
          rows(
              c,
              "SELECT endpoint_value || ':' || endpoint_number FROM all_tab_histograms"
                  + " WHERE owner = '"
                  + owner
                  + "' AND table_name = '"
                  + table
                  + "' AND column_name = 'tx_state' ORDER BY endpoint_number");
      return locked + " " + histogram + " " + String.join(" ", endpoints);
    }
  }

  /**
   * An imported table whose primary key is declared out of name order (design section 3, todo 334):
   * ScalarDB keys its metadata table by column name, (a, b) for (b, a); the setup builds the
   * in-flight index over the data table's order and a key index on the metadata table in that
   * order, and teardown drops both; an in-flight index found in the old order makes {@code check}
   * report the table not set up, and the setup rebuilds it. On Oracle a read of the metadata rows
   * by the data key's prefix then reaches them by a range scan, not a skip scan of the primary key.
   */
  @ParameterizedTest
  @MethodSource("engines")
  void anImportedKeyOutOfNameOrder_isIndexedInTheDataTablesOrder(Engines e) throws Exception {
    Engine engine = e.engine();
    String sk = e.storedSchema("sk");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("sk"),
          "CREATE TABLE sk.ord (b "
              + e.intType()
              + " NOT NULL, a "
              + e.intType()
              + " NOT NULL, x VARCHAR(10), PRIMARY KEY (b, a))",
          "INSERT INTO sk.ord VALUES (1, 2, 'p')",
          "INSERT INTO sk.ord VALUES (1, 3, 'q')",
          "INSERT INTO sk.ord VALUES (2, 1, 'r')");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createNamespace(sk, true);
      admin.importTable(sk, e.storedTable("ord"), DECOUPLED, Map.of());
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    TableInfo t = catalog.lookup(List.of(), sk, e.storedTable("ord"));
    String meta = Objects.requireNonNull(t.metadataTable());
    String a = e.storedColumn("a");
    String b = e.storedColumn("b");
    List<String> inflight =
        engine == Engine.POSTGRESQL || engine == Engine.SQLSERVER
            ? List.of(b, a) // filtered to the rows in flight
            : List.of("tx_state", b, a);
    // where ScalarDB keyed the metadata table by name, (a, b), the key index; where it kept the
    // data table's order, none
    boolean byName;
    try (Connection c = e.open()) {
      assertThat(primaryKey(engine, c, sk, t.dataTable())).containsExactly(b, a);
      List<String> metaKey = primaryKey(engine, c, sk, meta);
      assertThat(metaKey).containsExactlyInAnyOrder(a, b);
      byName = metaKey.equals(List.of(a, b));
    }
    System.out.println("[334] " + engine + ": the imported metadata table keyed by name " + byName);
    String keyIndexCreated =
        "created key index " + t.keyIndex(engine) + " in the data table's key order";
    List<Setup.Result> up = setup(e, catalog, sk);
    assertThat(up).allMatch(Setup.Result::succeeded, "succeeded");
    if (byName) {
      assertThat(up.get(0).actions()).contains(keyIndexCreated);
    } else {
      assertThat(up.get(0).actions()).doesNotContain(keyIndexCreated);
    }
    try (Connection c = e.open()) {
      assertThat(indexColumns(engine, c, sk, meta, t.inflightIndex(engine)))
          .containsExactlyElementsOf(inflight);
      assertThat(indexColumns(engine, c, sk, meta, t.keyIndex(engine)))
          .isEqualTo(byName ? List.of(b, a) : List.of());
      assertThat(Check.tables(catalog, c, x -> x.namespace().equals(sk), Check.DEFAULT_LIFETIME))
          .allMatch(Check.Result::setUp, "set up");
      if (engine == Engine.ORACLE) {
        String plan =
            oraclePlan(
                c,
                "SELECT /*+ INDEX(m) */ * FROM "
                    + name(engine, sk, meta)
                    + " m WHERE m."
                    + engine.quote(b)
                    + " = 1");
        assertThat(plan).contains("INDEX RANGE SCAN").doesNotContain("SKIP SCAN");
      }
      // again: nothing to create
      assertThat(setup(e, catalog, sk).get(0).actions()).noneMatch(x -> x.contains("index"));
      // an in-flight index in the catalog's order, as a setup before the key order left it
      String index =
          engine == Engine.ORACLE || engine == Engine.POSTGRESQL
              ? name(engine, sk, t.inflightIndex(engine))
              : engine.quote(t.inflightIndex(engine));
      String on = name(engine, sk, meta);
      String state = engine.quote("tx_state");
      String old = engine.quote(a) + ", " + engine.quote(b);
      e.execute(
          c,
          engine == Engine.ORACLE || engine == Engine.POSTGRESQL
              ? "DROP INDEX " + index
              : "DROP INDEX " + index + " ON " + on,
          engine == Engine.POSTGRESQL || engine == Engine.SQLSERVER
              ? "CREATE INDEX "
                  + (engine == Engine.POSTGRESQL ? engine.quote(t.inflightIndex(engine)) : index)
                  + " ON "
                  + on
                  + " ("
                  + old
                  + ") WHERE "
                  + state
                  + " IN (1, 2)"
              : "CREATE INDEX " + index + " ON " + on + " (" + state + ", " + old + ")");
      assertThat(Check.tables(catalog, c, x -> x.namespace().equals(sk), Check.DEFAULT_LIFETIME))
          .as("an in-flight index in the old order")
          .noneMatch(Check.Result::setUp);
    }
    List<Setup.Result> rebuilt = setup(e, catalog, sk);
    assertThat(rebuilt).allMatch(Setup.Result::succeeded, "succeeded");
    assertThat(rebuilt.get(0).actions())
        .contains(
            "rebuilt in-flight index "
                + t.inflightIndex(engine)
                + " in the data table's key order");
    try (Connection c = e.open()) {
      assertThat(indexColumns(engine, c, sk, meta, t.inflightIndex(engine)))
          .containsExactlyElementsOf(inflight);
      assertThat(Check.tables(catalog, c, x -> x.namespace().equals(sk), Check.DEFAULT_LIFETIME))
          .allMatch(Check.Result::setUp, "set up");
      List<Setup.Result> down = Setup.teardown(catalog, c, x -> x.namespace().equals(sk));
      assertThat(down).allMatch(Setup.Result::succeeded, "succeeded");
      if (byName) {
        assertThat(down.get(0).actions()).contains("dropped key index " + t.keyIndex(engine));
      }
      assertThat(indexColumns(engine, c, sk, meta, t.inflightIndex(engine))).isEmpty();
      assertThat(indexColumns(engine, c, sk, meta, t.keyIndex(engine))).isEmpty();
    }
  }

  private static List<Setup.Result> setup(Engines e, Catalog catalog, String schema)
      throws SQLException {
    try (Connection c = e.open()) {
      return Setup.run(
          catalog, c, t -> t.namespace().equals(schema), Duration.ofMinutes(1), List.of(), true);
    }
  }

  /** The table's primary key columns by KEY_SEQ, through the vendor's DatabaseMetaData. */
  private static List<String> primaryKey(Engine engine, Connection c, String schema, String table)
      throws SQLException {
    java.util.TreeMap<Integer, String> out = new java.util.TreeMap<>();
    boolean catalogs = engine == Engine.MYSQL || engine == Engine.MARIADB;
    try (ResultSet rs = c.getMetaData().getPrimaryKeys(catalogs ? schema : null, schema, table)) {
      while (rs.next()) {
        out.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
      }
    }
    return List.copyOf(out.values());
  }

  /** An index's columns in order, through the vendor's getIndexInfo; empty where it is absent. */
  private static List<String> indexColumns(
      Engine engine, Connection c, String schema, String table, String index) throws SQLException {
    java.util.TreeMap<Integer, String> out = new java.util.TreeMap<>();
    boolean catalogs = engine == Engine.MYSQL || engine == Engine.MARIADB;
    try (ResultSet rs =
        c.getMetaData().getIndexInfo(catalogs ? schema : null, schema, table, false, true)) {
      while (rs.next()) {
        if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
          out.put((int) rs.getShort("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"));
        }
      }
    }
    return List.copyOf(out.values());
  }

  /** Oracle's plan for a query, as DBMS_XPLAN prints it. */
  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC", justification = "test queries from constants")
  private static String oraclePlan(Connection c, String sql) throws SQLException {
    StringBuilder out = new StringBuilder();
    try (Statement st = c.createStatement()) {
      st.execute("EXPLAIN PLAN FOR " + sql);
      try (ResultSet rs =
          st.executeQuery("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
        while (rs.next()) {
          out.append(rs.getString(1)).append('\n');
        }
      }
    }
    return out.toString();
  }

  private static List<Setup.Result> setup(Engines e, Catalog catalog, Duration wait)
      throws SQLException {
    String su = e.storedSchema("su");
    try (Connection c = e.open()) {
      return Setup.run(catalog, c, t -> t.namespace().equals(su), wait, List.of(), true);
    }
  }

  private static Setup.Result result(List<Setup.Result> results, String table) {
    return results.stream()
        .filter(r -> r.table().toLowerCase(java.util.Locale.ROOT).endsWith("." + table))
        .findFirst()
        .orElseThrow();
  }

  private static SetupObjects objects(Engines e, TableInfo t) throws SQLException {
    Engine engine = e.engine();
    try (Connection c = e.open()) {
      return engine.setupObjects(
          c,
          List.of(t.namespace()),
          List.of(t.readView(engine)),
          List.of(t.inflightIndex(engine)),
          engine.tableNamesCaseInsensitive(c));
    }
  }

  private static String name(Engine engine, String schema, String table) {
    return engine.quote(schema) + "." + engine.quote(table);
  }

  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC", justification = "test queries from constants")

  /** What the driver does with a statement: its first value, or the SQLState it refused with. */
  private static String viaDriver(Engines e, String sql) throws SQLException {
    Properties p = new Properties();
    try (Connection direct = e.open()) {
      p.setProperty("user", direct.getMetaData().getUserName().split("@", 2)[0]);
    }
    p.setProperty("password", e.password());
    // a catalog of its own, read afresh on every lookup
    p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "0");
    String url = "jdbc:scalardb-native:" + e.url().substring("jdbc:".length());
    try (Connection c = DriverManager.getConnection(url, p);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? "read " + rs.getString(1) : "read nothing";
    } catch (SQLException ex) {
      return "refused " + ex.getSQLState() + " " + ex.getMessage();
    }
  }
}
