package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.catalog.Catalog;
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
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The findings of the 2026-10-13 review (todos 106-111), each asserting the behaviour the driver
 * must have: a check fails while its finding stands. Collected, so one run shows them all.
 */
class ReviewFindingsIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  @SuppressFBWarnings(
      value = {
        "SQL_INJECTION_JDBC",
        "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
        "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
        "OBL_UNSATISFIED_OBLIGATION",
        "ODR_OPEN_DATABASE_RESOURCE"
      },
      justification = "fixture DDL and queries written in the test sources")
  void findings(Engines e) throws Exception {
    Engine engine = e.engine();
    String rv = e.storedSchema("rv");
    String t = engine == Engine.ORACLE ? "rv.\"made\"" : "rv.made";
    String id = engine == Engine.ORACLE ? "\"id\"" : "id";
    String x = engine == Engine.ORACLE ? "\"x\"" : "x";
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("rv"),
          "CREATE TABLE rv.pl (" + id + " " + e.intType() + " PRIMARY KEY, " + x + " VARCHAR(10))",
          "INSERT INTO rv.pl VALUES (1, 'a')",
          "INSERT INTO rv.pl VALUES (2, 'b')",
          "INSERT INTO rv.pl VALUES (3, 'c')",
          "CREATE TABLE rv.lg (n " + e.intType() + ")");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(rv, true);
      admin.createTable(
          rv,
          "made",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("x", DataType.TEXT)
              .addPartitionKey("id")
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 3; i++) {
        tx.insert(
            Insert.newBuilder()
                .namespace(rv)
                .table("made")
                .partitionKey(Key.ofInt("id", i))
                .textValue("x", String.valueOf((char) ('a' + i - 1)))
                .build());
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(engine, Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      Setup.run(catalog, c, i -> i.namespace().equals(rv), Duration.ofMinutes(1), List.of());
    }
    SoftAssertions soft = new SoftAssertions();

    if (engine == Engine.POSTGRESQL) {
      // 106: a statement EXPLAIN (GENERIC_PLAN) cannot plan must not abort the transaction
      try (Connection c = e.openDriver()) {
        c.setAutoCommit(false);
        c.createStatement().executeUpdate("INSERT INTO rv.lg VALUES (1)");
        try (PreparedStatement ps =
            c.prepareStatement("SELECT x FROM " + t + " WHERE ? IS NULL OR id = ?")) {
          ps.setNull(1, Types.INTEGER);
          ps.setInt(2, 1);
          soft.assertThat(rows(ps)).as("106 untyped parameter in a transaction").hasSize(3);
          c.commit();
        } catch (SQLException ex) {
          soft.fail("106 untyped parameter in a transaction: " + ex.getMessage());
          c.rollback();
        }
        c.setAutoCommit(true);
        soft.assertThat(rows(c, "SELECT COUNT(*) FROM rv.lg"))
            .as("106 the transaction's insert committed")
            .containsExactly("1");
      }
      // 106: SQL ROLLBACK after a failure, with the driver's SET pending
      try (Connection c = e.openDriver()) {
        c.setAutoCommit(false);
        rows(c, "SELECT x FROM " + t);
        try {
          c.createStatement().execute("SELECT 1 / 0 FROM rv.pl");
        } catch (SQLException expected) {
          // the transaction is aborted now
        }
        try {
          c.createStatement().execute("ROLLBACK");
          soft.assertThat(rows(c, "SELECT x FROM " + t + " WHERE id = 1"))
              .as("106 after SQL ROLLBACK")
              .containsExactly("a");
        } catch (SQLException ex) {
          soft.fail("106 SQL ROLLBACK in an aborted transaction: " + ex.getMessage());
        }
        c.rollback();
      }
      // 107: a SET after a split read must not lose the session's workers value
      try (Connection c = e.openDriver()) {
        c.createStatement().execute("SET max_parallel_workers_per_gather = 2");
        rows(c, "SELECT x FROM " + t);
        c.createStatement().execute("SET search_path = rv, public");
        soft.assertThat(rows(c, "SHOW max_parallel_workers_per_gather"))
            .as("107 session's workers value after a SET")
            .containsExactly("2");
      }
    }

    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      // 108: large row limits
      String big =
          switch (engine) {
            case MYSQL, MARIADB -> " LIMIT 1, 18446744073709551615";
            case ORACLE -> " OFFSET 1 ROWS FETCH NEXT 9223372036854775807 ROWS ONLY";
            case POSTGRESQL -> " LIMIT 9223372036854775807 OFFSET 1";
            default -> null;
          };
      if (big != null) {
        same(
            soft,
            "108 large limit",
            d,
            v,
            "SELECT m." + x + " FROM {T} m ORDER BY m." + id + big,
            t);
      }
      // 109: a label inside a grouped ORDER BY expression; count(t.*)
      if (engine != Engine.MYSQL && engine != Engine.MARIADB) {
        same(
            soft,
            "109 label in ORDER BY expression",
            d,
            v,
            "SELECT m."
                + x
                + ", COUNT(*) FROM {T} m GROUP BY m."
                + x
                + " ORDER BY LOWER("
                + x
                + ")",
            t);
      }
      if (engine == Engine.POSTGRESQL) {
        same(soft, "109 count(m.*)", d, v, "SELECT COUNT(m.*) FROM {T} m", t);
        // 110: a whole-row reference
        same(soft, "110 whole-row reference", d, v, "SELECT m FROM {T} m WHERE m.id = 1", t);
      }
      if (engine == Engine.MYSQL || engine == Engine.MARIADB) {
        // 109: a placeholder inside an unaliased item's label
        String sql = "SELECT SUM(CASE WHEN m.x = ? THEN 1 ELSE 0 END) FROM {T} m";
        soft.assertThat(labelsAndRows(d, sql.replace("{T}", t), "a"))
            .as("109 placeholder in a label")
            .isEqualTo(labelsAndRows(v, sql.replace("{T}", "rv.pl"), "a"));
      }
    }

    if (engine == Engine.MYSQL || engine == Engine.MARIADB) {
      String byKey = "SELECT x FROM " + t + " WHERE id = ?";
      try (Connection c = e.openDriver();
          TwoPhaseCommitTransactionManager tm = scalarDb.getTwoPhaseCommitTransactionManager()) {
        PreparedStatement closed = c.prepareStatement(byKey);
        closed.setInt(1, 2);
        rows(closed);
        closed.close();
        PreparedStatement order = c.prepareStatement(byKey);
        order.setLargeMaxRows(100);
        order.setMaxRows(5);
        order.setLargeMaxRows(7);
        order.setInt(1, 2);
        rows(order);
        PreparedStatement unwrapped = c.prepareStatement(byKey);
        unwrapped.setInt(1, 2);
        rows(unwrapped);
        Statement inner =
            engine == Engine.MYSQL
                ? (Statement) unwrapped.unwrap(Class.forName("com.mysql.cj.jdbc.JdbcStatement"))
                : null;
        TwoPhaseCommitTransaction tx = tm.begin();
        for (int i = 0; i <= Router.PER_ROW_ABOVE; i++) {
          tx.insert(
              Insert.newBuilder()
                  .namespace(rv)
                  .table("made")
                  .partitionKey(Key.ofInt("id", 1000 + i))
                  .textValue("x", "n")
                  .build());
        }
        tx.prepare();
        try {
          Thread.sleep(1100);
          // 111: a closed statement stays closed
          try {
            rows(closed);
            soft.fail("111 a closed statement ran after the count changed its form");
          } catch (SQLException expected) {
            // closed
          }
          // 111: settings replay in the order they were made
          rows(order);
          soft.assertThat(order.getLargeMaxRows()).as("111 replay order").isEqualTo(7);
          // 111: an unwrapped vendor statement stays usable
          rows(unwrapped);
          if (inner != null) {
            soft.assertThat(inner.isClosed()).as("111 unwrapped statement closed").isFalse();
          }
          // 106: the in-flight count with a streaming result open (Connector/J)
          if (engine == Engine.MYSQL) {
            try (Connection s = e.openDriver();
                Statement st =
                    s.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
              st.setFetchSize(Integer.MIN_VALUE);
              try (ResultSet open = st.executeQuery("SELECT x FROM rv.pl")) {
                open.next();
                s.prepareStatement(byKey).close();
              } catch (SQLException ex) {
                soft.fail("106 prepare with a streaming result open: " + ex.getMessage());
              }
            }
          }
        } finally {
          tx.rollback();
          order.close();
          unwrapped.close();
        }
      }
    }
    soft.assertAll();
  }

  private static void same(
      SoftAssertions soft, String what, Connection d, Connection v, String sql, String t) {
    try {
      soft.assertThat(labelsAndRows(d, sql.replace("{T}", t)))
          .as(what)
          .isEqualTo(labelsAndRows(v, sql.replace("{T}", "rv.pl")));
    } catch (SQLException | RuntimeException ex) {
      soft.fail(what + ": " + ex);
    }
  }

  private static List<String> labelsAndRows(Connection c, String sql, String... params)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setString(i + 1, params[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        List<String> out = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
          labels.add(rs.getMetaData().getColumnLabel(i));
        }
        out.add(String.join("|", labels));
        out.addAll(rows(rs));
        return out;
      }
    }
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
