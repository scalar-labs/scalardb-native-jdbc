package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.io.DataType;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Todo 369 on PostgreSQL: a read past the split at READ COMMITTED, six tables, in a transaction and
 * in autocommit, runs as written over the data tables behind whole-table gates on a lateral row of
 * their own; with a row in flight it runs through {@code rc_T} and returns the committed rows; an
 * empty result is empty; a {@code *} over an inline table keeps {@code rc_T} (design 4.2 "The read
 * past the split at READ COMMITTED behind whole-table gates"). The text the server ran is the
 * read's own {@code current_query()}.
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
    justification = "statements written in the test sources")
class ReadCommittedPastTheSplitIntegrationTest {

  private static final Engines PG = Engines.POSTGRESQL;
  private static final String NS = "rps";
  private static TransactionFactory factory;

  @BeforeAll
  static void setUp() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Engines.selected().contains(PG), "PostgreSQL is not selected");
    ScalarDbNames.on(PG);
    factory = PG.scalarDb();
    try (DistributedTransactionAdmin admin = factory.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(NS, true);
      for (String table : List.of("d", "i")) {
        if (admin.tableExists(NS, table)) {
          admin.dropTable(NS, table);
        }
        admin.createTable(
            NS,
            table,
            TableMetadata.newBuilder()
                .addColumn("id", DataType.INT)
                .addColumn("x", DataType.TEXT)
                .addColumn("v", DataType.INT)
                .addPartitionKey("id")
                .build(),
            table.equals("d") ? Map.of("transaction-metadata-decoupling", "true") : Map.of());
      }
    }
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (String table : List.of("d", "i")) {
        for (int i = 1; i <= 20; i++) {
          tx.insert(
              Insert.newBuilder()
                  .namespace(NS)
                  .table(table)
                  .partitionKey(id(i))
                  .textValue("x", "a")
                  .intValue("v", i)
                  .build());
        }
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(Engine.POSTGRESQL, Catalog.DEFAULT_METADATA_SCHEMA, PG::open);
    try (Connection c = PG.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals(NS),
                  Duration.ofMinutes(1),
                  List.of(),
                  true))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
  }

  /** Six references of the table joined by key, past the split's 16 pieces. */
  private static String sixWay(String table, String select, String where) {
    StringBuilder sql = new StringBuilder("SELECT " + select + " FROM " + NS + "." + table + " t1");
    for (int i = 2; i <= 6; i++) {
      sql.append(" JOIN ").append(NS).append(".").append(table).append(" t").append(i);
      sql.append(" ON t").append(i).append(".id = t1.id");
    }
    return sql.append(" WHERE ").append(where).append(" ORDER BY t1.id").toString();
  }

  @Test
  void readsPastTheSplitRunAsWrittenBehindGates() throws Exception {
    TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager();
    try (Connection d = PG.openDriver()) {
      assertThat(d.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      for (String table : List.of("d", "i")) {
        for (boolean tx : List.of(true, false)) {
          d.setAutoCommit(!tx);
          String at = table + (tx ? ", in a transaction" : ", in autocommit");
          String sql = sixWay(table, "t1.id, t1.x, current_query()", "t1.id <= ?");

          // every table clear: as written, behind the lateral gates
          Read r = read(d, sql, 3);
          assertThat(r.rows).as(at).containsExactly("1/a", "2/a", "3/a");
          assertThat(r.text)
              .as(at + ": the text run")
              .contains("LEFT JOIN LATERAL", "scalardb_native_g0", "scalardb_native_g5")
              .doesNotContain("rc_");

          // an empty result is empty: the one row its gates ride on is not the application's
          assertThat(read(d, sql.replace("t1.id <= ?", "t1.id > ?"), 100).rows)
              .as(at + ": no row")
              .isEmpty();

          // a row in flight, its after image 'b': the committed rows through rc_T, no abort
          TwoPhaseCommitTransaction held = tpc.begin();
          held.update(
              Update.newBuilder()
                  .namespace(NS)
                  .table(table)
                  .partitionKey(id(2))
                  .textValue("x", "b")
                  .build());
          held.prepare();
          try {
            Read inFlight = read(d, sql, 3);
            assertThat(inFlight.rows)
                .as(at + ", a row in flight")
                .containsExactly("1/a", "2/a", "3/a");
            assertThat(inFlight.text).as(at + ", a row in flight: the text run").contains("rc_");
          } finally {
            held.rollback();
          }

          // the statement's own error, as the vendor reports it, not a transaction aborted by the
          // gated statement's failure under a fallback
          String failing = sixWay(table, "t1.id, 1 / (t1.v - t1.v)", "t1.id <= ?");
          assertThatThrownBy(() -> read(d, failing, 3))
              .as(at + ": a division by zero")
              .isInstanceOfSatisfying(
                  SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("22012"));
          if (tx) {
            d.rollback();
          }

          if (table.equals("i")) {
            // a * over an inline table would show its metadata columns: rc_T
            Read star = read(d, sixWay(table, "t1.*, current_query()", "t1.id <= ?"), 3);
            assertThat(star.text).as(at + ", t1.*: the text run").contains("rc_");
          }
          if (tx) {
            d.commit();
          }
        }
      }
      d.setAutoCommit(true);
    }
  }

  /** A read's rows, its first two columns joined by '/', and the text the server ran. */
  private record Read(List<String> rows, String text) {}

  private static Read read(Connection d, String sql, int bound) throws SQLException {
    List<String> rows = new ArrayList<>();
    String text = "";
    try (PreparedStatement ps = d.prepareStatement(sql)) {
      ps.setInt(1, bound);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.getMetaData().getColumnCount())
            .as("the application's columns only")
            .isEqualTo(sql.contains("t1.*") ? 4 : 3);
        while (rs.next()) {
          rows.add(rs.getString(1) + "/" + rs.getString(2));
          text = rs.getString(sql.contains("t1.*") ? 4 : 3);
        }
      }
    }
    return new Read(rows, text);
  }
}
