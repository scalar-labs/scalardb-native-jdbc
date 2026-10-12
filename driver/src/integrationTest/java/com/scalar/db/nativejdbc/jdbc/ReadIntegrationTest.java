package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 3 on every engine and both decoupled layouts: a read of a ScalarDB table at READ
 * COMMITTED returns the committed image of each row, through the read view, while a ScalarDB
 * transaction holds rows prepared, and the new values once it commits.
 */
class ReadIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  @SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"},
      justification = "fixture DDL and queries written in the test sources")
  void reads_seeTheCommittedImage_whileScalarDbHoldsRowsPrepared(Engines e) throws Exception {
    Engine engine = e.engine();
    String rd = e.storedSchema("rd");
    String id = e.storedColumn("id");
    String x = e.storedColumn("x");
    String imp = e.storedTable("imp");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("rd"),
          "CREATE TABLE rd.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO rd.imp VALUES (1, 'a')",
          "INSERT INTO rd.imp VALUES (2, 'b')",
          "INSERT INTO rd.imp VALUES (3, 'c')");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true); // the commit state of every transaction
      admin.createNamespace(rd, true);
      admin.importTable(rd, imp, DECOUPLED, Map.of());
      admin.createTable(
          rd,
          "made",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .build(),
          DECOUPLED);
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 3; i++) {
        tx.insert(
            Insert.newBuilder()
                .namespace(rd)
                .table("made")
                .partitionKey(Key.ofInt("id", i))
                .textValue("x", String.valueOf((char) ('a' + i - 1)))
                .build());
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    // ScalarDB addresses an imported table by its virtual table, imp_scalardb
    String impScalarDb = Objects.requireNonNull(catalog.lookup(List.of(), rd, imp).virtualTable());
    try (Connection c = e.open()) {
      List<Setup.Result> results =
          Setup.run(catalog, c, t -> t.namespace().equals(rd), Duration.ofMinutes(1), List.of());
      assertThat(results).allMatch(Setup.Result::succeeded, "succeeded");
    }
    // the created table's names are lower case, which Oracle reads only quoted
    String madeTable = engine == Engine.ORACLE ? "\"made\"" : "made";
    String made = "rd." + madeTable;
    assertSettled(e, catalog, rd, madeTable);
    String mid = engine == Engine.ORACLE ? "\"id\"" : "id";
    String mx = engine == Engine.ORACLE ? "\"x\"" : "x";
    String limit2 =
        engine == Engine.SQLSERVER || engine == Engine.ORACLE
            ? " OFFSET 0 ROWS FETCH NEXT 2 ROWS ONLY"
            : " LIMIT 2";

    try (Connection c = e.openDriver()) {
      assertThat(rows(c, "SELECT " + mx + " FROM " + made + " ORDER BY " + mid))
          .containsExactly("a", "b", "c");
      assertThat(rows(c, "SELECT x FROM rd.imp ORDER BY id")).containsExactly("a", "b", "c");
    }

    try (TwoPhaseCommitTransactionManager tm = scalarDb.getTwoPhaseCommitTransactionManager()) {
      TwoPhaseCommitTransaction tx = tm.begin();
      tx.update(set(rd, "made", Key.ofInt("id", 2), "x", "b2"));
      tx.insert(
          Insert.newBuilder()
              .namespace(rd)
              .table("made")
              .partitionKey(Key.ofInt("id", 4))
              .textValue("x", "d")
              .build());
      tx.delete(
          Delete.newBuilder().namespace(rd).table("made").partitionKey(Key.ofInt("id", 3)).build());
      // Oracle's NUMBER(10) imports as BIGINT
      Key one = engine == Engine.ORACLE ? Key.ofBigInt(id, 1) : Key.ofInt(id, 1);
      tx.update(set(rd, impScalarDb, one, x, "A"));
      tx.prepare();
      tx.validate();

      try (Connection c = e.openDriver()) {
        // prepared: the before images, and no in-flight insert
        assertThat(rows(c, "SELECT " + mx + " FROM " + made + " ORDER BY " + mid))
            .containsExactly("a", "b", "c");
        assertThat(rows(c, "SELECT x FROM rd.imp ORDER BY id")).containsExactly("a", "b", "c");
        try (PreparedStatement ps =
            c.prepareStatement("SELECT " + mx + " FROM " + made + " WHERE " + mid + " = ?")) {
          ps.setInt(1, 2);
          assertThat(rows(ps)).containsExactly("b");
          ps.setInt(1, 4);
          assertThat(rows(ps)).isEmpty();
        }
        // a predicate on a non-key column matches the before image, not the prepared value
        assertThat(rows(c, "SELECT " + mid + " FROM " + made + " WHERE " + mx + " = 'b'"))
            .containsExactly("2");
        assertThat(rows(c, "SELECT " + mid + " FROM " + made + " WHERE " + mx + " = 'b2'"))
            .isEmpty();
        assertThat(rows(c, "SELECT COUNT(*) FROM " + made)).containsExactly("3");
        assertThat(rows(c, "SELECT " + mx + " FROM " + made + " ORDER BY " + mx + " DESC" + limit2))
            .containsExactly("c", "b");
        // a table name as a qualifier, and a join of both layouts
        assertThat(
                rows(
                    c,
                    "SELECT " + made + "." + mx + " FROM " + made + " WHERE " + made + "." + mid
                        + " = 2"))
            .containsExactly("b");
        assertThat(
                rows(
                    c,
                    "SELECT m."
                        + mx
                        + ", i.x FROM "
                        + made
                        + " m JOIN rd.imp i ON m."
                        + mid
                        + " = i.id ORDER BY i.id"))
            .containsExactly("a/a", "b/b", "c/c");
        splitReads(c, made, mid, mx);
      }
      try (Connection vendor = e.open()) {
        // the reads above ran split, not through the read views
        Router router = new Router(engine, catalog, new Session(engine));
        String join =
            "SELECT m." + mx + " FROM " + made + " m JOIN rd.imp i ON m." + mid + " = i.id";
        assertThat(router.route(vendor, join).sql)
            .contains("UNION ALL")
            .doesNotContainIgnoringCase("rc_");
        assertThat(router.route(vendor, join + " ORDER BY i.x").sql).contains("UNION ALL");
        // a subquery's table is read through its view
        assertThat(
                router.route(
                        vendor,
                        "SELECT x FROM rd.imp WHERE id IN (SELECT " + mid + " FROM " + made + ")")
                    .sql)
            .contains("UNION ALL")
            .containsIgnoringCase("rc_");
      }
      tx.commit();
    }
    assertSettled(e, catalog, rd, madeTable, imp);

    try (Connection c = e.openDriver()) {
      assertThat(rows(c, "SELECT " + mx + " FROM " + made + " ORDER BY " + mid))
          .containsExactly("a", "b2", "d");
      assertThat(rows(c, "SELECT x FROM rd.imp ORDER BY id")).containsExactly("A", "b", "c");
    }
  }

  /**
   * Split reads while {@code made} has rows 2 (b, updated), 3 (c, deleted) and 4 (inserted) in
   * flight and {@code imp} row 1 (a, updated): every copy's rows, none twice.
   */
  @SuppressFBWarnings(
      value = {
        "SQL_INJECTION_JDBC",
        "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
        "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"
      },
      justification = "test queries written in the test sources")
  private static void splitReads(Connection c, String made, String mid, String mx)
      throws SQLException {
    String join = " FROM " + made + " m JOIN rd.imp i ON m." + mid + " = i.id";
    assertThat(rows(c, "SELECT m." + mid + ", m." + mx + ", i.x" + join))
        .containsExactlyInAnyOrder("1/a/a", "2/b/b", "3/c/c");
    // three references, eight copies
    assertThat(
            rows(
                c,
                "SELECT a."
                    + mx
                    + ", b.x, z."
                    + mx
                    + " FROM "
                    + made
                    + " a JOIN rd.imp b ON a."
                    + mid
                    + " = b.id JOIN "
                    + made
                    + " z ON z."
                    + mid
                    + " = b.id WHERE b.id < 3"))
        .containsExactlyInAnyOrder("a/a/a", "b/b/b");
    // the subquery's table is read whole; the outer row comes out once
    assertThat(rows(c, "SELECT x FROM rd.imp WHERE id IN (SELECT " + mid + " FROM " + made + ")"))
        .containsExactlyInAnyOrder("a", "b", "c");
    // the nullable side of a LEFT JOIN is read whole
    assertThat(
            rows(
                c,
                "SELECT i.x, m."
                    + mx
                    + " FROM rd.imp i LEFT JOIN "
                    + made
                    + " m ON m."
                    + mid
                    + " = i.id + 2"))
        .containsExactlyInAnyOrder("a/c", "b/null", "c/null");
    assertThat(rows(c, "SELECT * FROM " + made + " ORDER BY " + mid))
        .containsExactly("1/a", "2/b", "3/c");
    try (PreparedStatement ps =
        c.prepareStatement("SELECT m." + mx + join + " WHERE i.id >= ? ORDER BY i.x DESC")) {
      assertThat(ps.getParameterMetaData().getParameterCount()).isEqualTo(1);
      ps.setInt(1, 2);
      assertThat(rows(ps)).containsExactly("c", "b");
      ps.setInt(1, 1);
      assertThat(rows(ps)).containsExactly("c", "b", "a");
      assertThatThrownBy(() -> ps.setInt(2, 1))
          .isInstanceOf(SQLException.class)
          .extracting(t -> ((SQLException) t).getSQLState())
          .isEqualTo("07009");
    }
  }

  /**
   * Fails when a committed ScalarDB transaction left records prepared. ScalarDB writes the records'
   * commit after the coordinator's on a best-effort basis: a failure is logged as "Committing
   * records failed" and left to lazy recovery, and until then the driver reads the before images
   * (design section 7), which a later assertion would report only as wrong rows.
   */
  private static void assertSettled(Engines e, Catalog catalog, String rd, String... tables)
      throws SQLException {
    Engine engine = e.engine();
    try (Connection v = e.open()) {
      for (String t : tables) {
        TableInfo info = catalog.lookup(List.of(), rd, t);
        String meta =
            engine.quote(info.namespace())
                + "."
                + engine.quote(Objects.requireNonNull(info.metadataTable()));
        assertThat(
                rows(
                    v,
                    "SELECT COUNT(*) FROM "
                        + meta
                        + " WHERE "
                        + engine.quote("tx_state")
                        + " <> 3"))
            .as("records of %s that ScalarDB's record commit left prepared", t)
            .containsExactly("0");
      }
    }
  }

  private static Update set(String ns, String table, Key key, String col, String v) {
    return Update.newBuilder()
        .namespace(ns)
        .table(table)
        .partitionKey(key)
        .textValue(col, v)
        .build();
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "test queries written in the test sources")
  private static List<String> rows(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rows(rs);
    }
  }

  private static List<String> rows(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      return rows(rs);
    }
  }

  private static List<String> rows(ResultSet rs) throws SQLException {
    int n = rs.getMetaData().getColumnCount();
    List<String> out = new ArrayList<>();
    while (rs.next()) {
      List<String> row = new ArrayList<>();
      for (int i = 1; i <= n; i++) {
        row.add(rs.getString(i));
      }
      out.add(String.join("/", row));
    }
    return out;
  }
}
