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
      value = {
        "SQL_INJECTION_JDBC",
        "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
        "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"
      },
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
    // the created table's names are lower case, which Oracle reads only quoted
    String madeTable = engine == Engine.ORACLE ? "\"made\"" : "made";
    String made = "rd." + madeTable;
    String mid = engine == Engine.ORACLE ? "\"id\"" : "id";
    String mx = engine == Engine.ORACLE ? "\"x\"" : "x";
    try (Connection c = e.open()) {
      // a plain table holding made's committed image while rows are in flight, to compare with
      e.execute(
          c,
          "CREATE TABLE rd.pl ("
              + mid
              + " "
              + e.intType()
              + " PRIMARY KEY, "
              + mx
              + " VARCHAR(10))",
          "INSERT INTO rd.pl VALUES (1, 'a')",
          "INSERT INTO rd.pl VALUES (2, 'b')",
          "INSERT INTO rd.pl VALUES (3, 'c')");
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
    assertSettled(e, catalog, rd, madeTable);
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
        try (Connection vendor = e.open()) {
          groupedReads(c, vendor, made, mid, mx);
        }
        // a trailing line comment ends every copy, not only the first
        assertThat(rows(c, "SELECT " + mx + " FROM " + made + " -- note"))
            .containsExactlyInAnyOrder("a", "b", "c");
        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT " + mx + " FROM " + made + " WHERE " + mid + " = ? -- note")) {
          ps.setInt(1, 2);
          assertThat(rows(ps)).containsExactly("b");
        }
        if (engine == Engine.ORACLE) {
          // an old-style outer join reads its nullable side whole
          assertThat(
                  rows(
                      c,
                      "SELECT a."
                          + mx
                          + ", b."
                          + mx
                          + " FROM "
                          + made
                          + " a, "
                          + made
                          + " b WHERE a."
                          + mid
                          + " = b."
                          + mid
                          + "(+)"))
              .containsExactlyInAnyOrder("a/a", "b/b", "c/c");
        }
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
        assertThat(router.route(vendor, "SELECT COUNT(*) FROM " + made).sql)
            .contains("UNION ALL")
            .doesNotContainIgnoringCase("rc_");
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

    // many rows in flight: on MySQL and MariaDB a statement prepared before them is prepared again
    // in the per-row form once its count is a second old, its parameter and settings replayed
    String byKey = "SELECT " + mx + " FROM " + made + " WHERE " + mid + " = ?";
    try (Connection c = e.openDriver();
        PreparedStatement ps = c.prepareStatement(byKey);
        TwoPhaseCommitTransactionManager tm = scalarDb.getTwoPhaseCommitTransactionManager()) {
      ps.setFetchSize(7);
      ps.setInt(1, 2);
      assertThat(rows(ps)).containsExactly("b2");
      String before = ps.toString();
      TwoPhaseCommitTransaction tx = tm.begin();
      for (int i = 0; i <= Router.PER_ROW_ABOVE; i++) {
        tx.insert(
            Insert.newBuilder()
                .namespace(rd)
                .table("made")
                .partitionKey(Key.ofInt("id", 1000 + i))
                .textValue("x", "n")
                .build());
      }
      tx.prepare();
      try {
        Thread.sleep(1100);
        assertThat(rows(ps)).containsExactly("b2");
        assertThat(ps.getFetchSize()).isEqualTo(7);
        boolean perRow = engine == Engine.MYSQL || engine == Engine.MARIADB;
        assertThat(ps.toString().equals(before)).isEqualTo(!perRow);
        try (Connection vendor = e.open()) {
          String sql = new Router(engine, catalog, new Session(engine)).route(vendor, byKey).sql;
          assertThat(sql.contains("COALESCE")).isEqualTo(perRow);
        }
      } finally {
        tx.rollback();
      }
    }
    assertSettled(e, catalog, rd, madeTable);
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
   * Grouping reads while {@code made} has rows in flight: the rows and labels of the same statement
   * over {@code pl}, a plain table holding {@code made}'s committed image.
   */
  @SuppressFBWarnings(
      value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
      justification = "test queries written in the test sources")
  private static void groupedReads(
      Connection c, Connection vendor, String made, String mid, String mx) throws SQLException {
    String[][] statements = {
      {"SELECT COUNT(*) FROM {T}"},
      {"SELECT count( * ) + 1, Max(m.{ID}) * 2 FROM {T} m"},
      {"SELECT count( m.{X} ), MAX(m.{X}), MIN(m.{ID}) AS lo, AVG(m.{ID}), SUM(m.{ID}) FROM {T} m"},
      {"SELECT m.{X}, COUNT(*) AS n FROM {T} m GROUP BY m.{X} ORDER BY m.{X}"},
      {"SELECT COUNT(DISTINCT m.{X}) FROM {T} m"},
      {"SELECT COUNT(*), MAX(m.{X}) FROM {T} m WHERE m.{ID} > 10"},
      {
        "SELECT a.{X}, COUNT(*) FROM {T} a JOIN {T} b ON a.{ID} <= b.{ID} GROUP BY a.{X} ORDER BY a.{X}"
      },
      {
        "SELECT m.{X}, SUM(m.{ID}) FROM {T} m WHERE m.{ID} < ? GROUP BY m.{X} HAVING COUNT(*) > ?"
            + " ORDER BY 2 DESC",
        "3",
        "0"
      },
    };
    for (String[] st : statements) {
      String sql = st[0].replace("{X}", mx).replace("{ID}", mid);
      List<String> expected = new ArrayList<>();
      List<String> actual = new ArrayList<>();
      for (int side = 0; side < 2; side++) {
        Connection conn = side == 0 ? vendor : c;
        List<String> out = side == 0 ? expected : actual;
        try (PreparedStatement ps =
            conn.prepareStatement(sql.replace("{T}", side == 0 ? "rd.pl" : made))) {
          for (int i = 1; i < st.length; i++) {
            ps.setInt(i, Integer.parseInt(st[i]));
          }
          try (ResultSet rs = ps.executeQuery()) {
            List<String> labels = new ArrayList<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
              labels.add(rs.getMetaData().getColumnLabel(i));
            }
            out.add(String.join("|", labels));
            out.addAll(rows(rs));
          }
        }
      }
      assertThat(actual).as(sql).isEqualTo(expected);
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
