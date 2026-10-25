package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.N;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.intType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A statement the parser cannot read runs as written when its words name no ScalarDB table, in a
 * transaction too, and is refused when they do (todo 314).
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "OBL_UNSATISFIED_OBLIGATION"},
    justification = "statements written in the test sources, closed with their connection")
class UnparsedIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  /** A read of {@code table} the parser cannot read, on each engine. */
  private static String read(Engines e, String table) {
    return switch (e.engine()) {
      case POSTGRESQL -> "SELECT x FROM " + table + " ORDER BY x USING <";
      case MYSQL, MARIADB ->
          "SELECT x FROM " + table + " FORCE INDEX FOR ORDER BY (PRIMARY) ORDER BY id";
      case SQLSERVER -> "SELECT x FROM " + table + " TABLESAMPLE (100 PERCENT)";
      case ORACLE -> "SELECT x FROM " + table + " MODEL DIMENSION BY (id) MEASURES (x) RULES ()";
    };
  }

  /** A write of {@code table} the parser cannot read: rows copied with their ids moved by 10. */
  private static String write(Engines e, String table, int by) {
    String copy = "SELECT id + " + by + ", x FROM " + table;
    return switch (e.engine()) {
      case POSTGRESQL -> "INSERT INTO " + table + " " + copy + " ORDER BY id USING <";
      case MYSQL, MARIADB ->
          "INSERT INTO "
              + table
              + " SELECT id + "
              + by
              + ", x FROM "
              + table
              + " FORCE INDEX FOR ORDER BY (PRIMARY) ORDER BY id";
      case SQLSERVER -> "INSERT TOP (1) INTO " + table + " " + copy;
      case ORACLE ->
          "INSERT INTO " + table + " " + copy + " MODEL DIMENSION BY (id) MEASURES (x) RULES ()";
    };
  }

  @ParameterizedTest
  @MethodSource("engines")
  void anUnparsedStatement_runsAsWrittenOnPlainTables_andIsRefusedOnScalarDbOnes(Engines e)
      throws Exception {
    ScalarDbNames.on(e);
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("up"), true);
      admin.dropTable(N("up"), N("s"), true);
      admin.createTable(
          N("up"),
          N("s"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("x"), intType())
              .addPartitionKey(N("id"))
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
    }
    try (Connection c = e.open()) {
      if (e.engine() == Engine.MYSQL || e.engine() == Engine.MARIADB) {
        e.execute(c, "DROP DATABASE IF EXISTS upp"); // the fixture's own misses lower-case MySQL
      } else {
        e.dropSchemas(c, e.storedSchema("upp"));
      }
      e.execute(
          c,
          e.createSchema("upp"),
          "CREATE TABLE upp.p (id " + e.intType() + " PRIMARY KEY, x " + e.intType() + ")",
          "INSERT INTO upp.p VALUES (1, 1)",
          "INSERT INTO upp.p VALUES (2, 2)");
      if (e.engine() == Engine.ORACLE) {
        e.execute(c, "CREATE SYNONYM upp.syn_s FOR up.s", "CREATE SYNONYM upp.syn_p FOR upp.p");
      }
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals(N("up")),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    try (Connection d = e.openDriver();
        Statement st = d.createStatement()) {
      assertThat(st.executeUpdate("INSERT INTO up.s (id, x) VALUES (1, 1)")).isEqualTo(1);
      if (e.engine() == Engine.SQLSERVER) {
        // T-SQL needs no ; between statements: nothing unparsed is sent there
        assertRefused(() -> st.executeQuery(read(e, "upp.p")), "0AN01", "without a ;");
        assertRefused(() -> st.executeUpdate(write(e, "upp.p", 10)), "0AN01", "without a ;");
        return;
      }
      // in autocommit
      assertThat(count(st, read(e, "upp.p"))).isEqualTo(2);
      // in a transaction, with a write of a ScalarDB table: rolled back, then committed, together
      d.setAutoCommit(false);
      assertThat(st.executeUpdate(write(e, "upp.p", 10))).isPositive();
      assertThat(st.executeUpdate("UPDATE up.s SET x = 2 WHERE id = 1")).isEqualTo(1);
      d.rollback();
      assertThat(count(st, "SELECT x FROM upp.p")).isEqualTo(2);
      int n = st.executeUpdate(write(e, "upp.p", 10));
      assertThat(st.executeUpdate("UPDATE up.s SET x = 2 WHERE id = 1")).isEqualTo(1);
      d.commit();
      assertThat(count(st, "SELECT x FROM upp.p")).isEqualTo(2 + n);
      assertThat(count(st, "SELECT x FROM up.s WHERE x = 2")).isEqualTo(1);
      d.commit();

      // its words name a ScalarDB table
      assertRefused(() -> st.executeQuery(read(e, "up.s")), "0AN01", "which ScalarDB manages");
      assertRefused(() -> st.executeUpdate(write(e, "up.s", 10)), "0AN01", "ScalarDB manages");
      // nor its namespace, nor the setup's read view of it
      assertRefused(
          () -> st.execute(read(e, "upp.p") + " -- " + N("up")), "0AN01", "ScalarDB manages");
      assertRefused(
          () -> st.execute(read(e, "upp.p") + " -- " + e.engine().readViewName(N("s"))),
          "0AN01",
          "ScalarDB manages");
      // transaction control the parser cannot read is not sent
      if (e.engine() == Engine.POSTGRESQL) {
        assertRefused(() -> st.execute("COMMIT AND CHAIN"), "0AN01", "only a query, DML or DDL");
      }
      d.rollback();

      // a prepared one is decided again where the level changes what it must do
      try (PreparedStatement ps = d.prepareStatement(read(e, "upp.p"))) {
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).isTrue();
        }
        d.commit();
        if (e.engine() == Engine.MYSQL
            || e.engine() == Engine.MARIADB
            || e.engine() == Engine.SQLSERVER) {
          // at SERIALIZABLE the driver locks every read, which it cannot do unparsed
          d.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
          assertRefused(ps::executeQuery, "0AN01", "locks every read");
          assertRefused(() -> st.executeQuery(read(e, "upp.p")), "0AN01", "locks every read");
          d.rollback();
          d.setReadOnly(true);
          assertRefused(() -> st.executeQuery(read(e, "upp.p")), "25006", "");
          d.rollback();
          d.setReadOnly(false);
          d.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
      }

      if (e.engine() == Engine.ORACLE) {
        // a synonym of a ScalarDB table is refused, as a parsed statement refuses it (todo 282);
        // one of a plain table is not
        assertRefused(
            () -> st.executeQuery(read(e, "upp.syn_s")), "0AN01", "SYN_S, which ScalarDB manages");
        assertThat(count(st, read(e, "upp.syn_p"))).isEqualTo(2 + n);
      }
    }
  }

  private static int count(Statement st, String sql) throws SQLException {
    int n = 0;
    try (ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        n++;
      }
    }
    return n;
  }

  private interface Call {
    void run() throws SQLException;
  }

  private static void assertRefused(Call call, String state, String message) {
    assertThatThrownBy(call::run)
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(message)
        .extracting(t -> ((SQLException) t).getSQLState())
        .isEqualTo(state);
  }
}
