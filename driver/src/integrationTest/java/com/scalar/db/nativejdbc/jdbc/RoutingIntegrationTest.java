package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.testing.Engines;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Milestone 1 on PostgreSQL: every statement goes through the router; plain tables work as they do
 * without the driver, and nothing reaches a ScalarDB table unrewritten.
 */
class RoutingIntegrationTest {

  private static final Engines PG = Engines.POSTGRESQL;
  private static String url;
  private static Properties props;

  @BeforeAll
  static void setUp() throws Exception {
    Assumptions.assumeTrue(Engines.selected().contains(PG), "PostgreSQL is not selected");
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "CREATE SCHEMA IF NOT EXISTS m1",
          "CREATE SCHEMA IF NOT EXISTS m1plain",
          "CREATE TABLE IF NOT EXISTS m1.p (id SERIAL PRIMARY KEY, x TEXT)",
          "CREATE TABLE IF NOT EXISTS m1.s (id INT PRIMARY KEY, x TEXT)",
          "CREATE TABLE IF NOT EXISTS m1plain.s (id INT PRIMARY KEY, x TEXT)");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("m1", true);
      if (!admin.tableExists("m1", "s")) {
        admin.importTable("m1", "s", Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
    }
    url = "jdbc:scalardb-native:" + PG.url().substring("jdbc:".length());
    props = new Properties();
    try (Connection c = PG.open()) {
      props.setProperty("user", c.getMetaData().getUserName());
    }
    props.setProperty("password", PG.password());
    // a catalog of its own, loaded after the import above
    props.setProperty("ApplicationName", "routing-integration-test");
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(url, props);
  }

  private static void assertRefused(ThrowingCallable call, String sqlState, String message) {
    assertThatThrownBy(call)
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(message)
        .extracting("SQLState")
        .isEqualTo(sqlState);
  }

  @Test
  void plainTables_workAsWithoutTheDriver() throws Exception {
    try (Connection c = connect()) {
      try (Statement st = c.createStatement()) {
        assertThat(st.executeUpdate("DELETE FROM m1.p")).isGreaterThanOrEqualTo(0);
        assertThat(
                st.executeUpdate(
                    "INSERT INTO m1.p (x) VALUES ('a')", Statement.RETURN_GENERATED_KEYS))
            .isEqualTo(1);
        try (ResultSet keys = st.getGeneratedKeys()) {
          assertThat(keys.next()).isTrue();
          assertThat(keys.getStatement()).isSameAs(st);
        }
        st.addBatch("INSERT INTO m1.p (x) VALUES ('b')");
        st.addBatch("INSERT INTO m1.p (x) VALUES ('c')");
        assertThat(st.executeBatch()).containsExactly(1, 1);
        assertThat(st.getConnection()).isSameAs(c);
      }
      try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM m1.p WHERE x <> ?")) {
        ps.setString(1, "z");
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isEqualTo(3);
          assertThat(rs.getStatement()).isSameAs(ps);
        }
      }
      assertThat(c.getMetaData().getConnection()).isSameAs(c);
      assertThat(c.unwrap(Class.forName("org.postgresql.PGConnection"))).isNotNull();
    }
  }

  @Test
  void statementsOnAScalarDbTable_areRefused_everyWayTheyCanArrive() throws SQLException {
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      assertRefused(() -> st.executeQuery("SELECT * FROM m1.s"), "0A000", "m1.s");
      assertRefused(
          () -> st.executeUpdate("UPDATE m1.s SET x = 'y' WHERE id = 1"), "0A000", "m1.s");
      assertRefused(() -> st.execute("DELETE FROM m1.s"), "0A000", "m1.s");
      assertRefused(() -> st.addBatch("INSERT INTO m1.s VALUES (1, 'a')"), "0A000", "m1.s");
      assertRefused(() -> c.prepareStatement("SELECT * FROM m1.s WHERE id = ?"), "0A000", "m1.s");
      assertRefused(
          () -> st.executeQuery("SELECT * FROM m1.p JOIN m1.s ON m1.p.id = m1.s.id"),
          "0A000",
          "m1.s");
      assertRefused(
          () -> st.executeQuery("SELECT * FROM m1.p WHERE id IN (SELECT id FROM m1.s)"),
          "0A000",
          "m1.s");
      assertRefused(() -> st.executeQuery("SELECT * FROM m1.s_scalardb"), "42000", "virtual table");
      assertRefused(() -> st.executeQuery("SELECT * FROM m1.s_tx_metadata"), "42000", "metadata");
      // a subquery in a SET is a raw read too
      assertRefused(() -> st.execute("SET x.y = (SELECT x FROM m1.s LIMIT 1)"), "0A000", "m1.s");
    }
  }

  @Test
  void statementsTheParserCannotRead_areRefused_andPassthroughRunsThemInAutocommit()
      throws SQLException {
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      assertRefused(() -> st.execute("VACUUM m1.p"), "0A000", "cannot be parsed");
      assertRefused(() -> c.prepareCall("{call pg_sleep(0)}"), "0A000", "cannot be parsed");
      c.setClientInfo(Proxies.PASSTHROUGH, "true");
      assertThat(c.getClientInfo(Proxies.PASSTHROUGH)).isEqualTo("true");
      st.execute("VACUUM m1.p");
      c.setAutoCommit(false);
      assertRefused(() -> st.execute("VACUUM m1.p"), "0A000", "autocommit");
      c.setAutoCommit(true);
      c.setClientInfo(Proxies.PASSTHROUGH, "false");
      assertRefused(() -> st.execute("VACUUM m1.p"), "0A000", "cannot be parsed");
    }
  }

  @Test
  void anUnqualifiedName_resolvesTheWayTheSessionDoes_afterSetAndSetSchema() throws SQLException {
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("SET search_path = m1plain");
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM s")) {
        assertThat(rs.next()).isTrue(); // the plain m1plain.s
      }
      st.execute("SET search_path = m1");
      assertRefused(() -> st.executeQuery("SELECT count(*) FROM s"), "0A000", "m1.s");
      c.setSchema("m1plain");
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM s")) {
        assertThat(rs.next()).isTrue();
      }
      // a SET inside a transaction that is rolled back is undone, and so is the driver's view
      c.setAutoCommit(false);
      st.execute("SET LOCAL search_path = m1");
      assertRefused(() -> st.executeQuery("SELECT count(*) FROM s"), "0A000", "m1.s");
      c.rollback();
      c.setAutoCommit(true);
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM s")) {
        assertThat(rs.next()).isTrue();
      }
    }
  }

  @Test
  void readUncommitted_isRefused_andTheDefaultIsReadCommitted() throws SQLException {
    try (Connection c = connect()) {
      assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
      assertRefused(
          () -> c.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED),
          "0A000",
          "READ UNCOMMITTED");
    }
  }

  @Test
  void aPoolOverTheDriver_behavesTheSame() throws SQLException {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setDataSourceProperties(props);
    config.setMaximumPoolSize(2);
    try (HikariDataSource pool = new HikariDataSource(config)) {
      for (int i = 0; i < 3; i++) {
        try (Connection c = pool.getConnection();
            Statement st = c.createStatement()) {
          st.execute("SET search_path = m1plain");
          try (ResultSet rs = st.executeQuery("SELECT 1 FROM m1.p LIMIT 1")) {
            rs.next();
          }
          assertRefused(() -> st.executeQuery("SELECT * FROM m1.s"), "0A000", "m1.s");
        }
      }
    }
  }

  // Review findings 055-058: each must end in a refusal. The helper reports which table a statement
  // actually read when the driver let it through: "scalardb" is the raw ScalarDB table m1.s.

  private static void seedDistinctRows() throws SQLException {
    try (Connection c = PG.open()) {
      PG.execute(
          c,
          "INSERT INTO m1.s VALUES (99, 'scalardb') ON CONFLICT DO NOTHING",
          "INSERT INTO m1plain.s VALUES (1, 'plain') ON CONFLICT DO NOTHING");
    }
  }

  private interface Query {
    ResultSet run() throws SQLException;
  }

  private static String outcome(Query query) {
    try (ResultSet rs = query.run()) {
      return rs.next() ? "read " + rs.getString(1) : "read nothing";
    } catch (SQLException e) {
      return "refused " + e.getSQLState();
    }
  }

  @Test
  void finding055_aBatchedSet_isSeenByTheNextLookup() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("SET search_path = m1plain");
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read plain");
      st.addBatch("SET search_path = m1");
      st.executeBatch();
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("refused 0A000");
    }
  }

  @Test
  void finding056_aPreparedStatement_isCheckedAgainAfterTheSessionChanges() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      c.setSchema("m1plain");
      try (PreparedStatement ps = c.prepareStatement("SELECT x FROM s")) {
        assertThat(outcome(ps::executeQuery)).isEqualTo("read plain");
        st.execute("SET search_path = m1");
        assertThat(outcome(ps::executeQuery)).isEqualTo("refused 0A000");
      }
    }
  }

  @Test
  void finding056_aStatementPreparedInPassthrough_isCheckedAgainWhenPassthroughEnds()
      throws SQLException {
    try (Connection c = connect()) {
      c.setClientInfo(Proxies.PASSTHROUGH, "true");
      try (PreparedStatement ps = c.prepareStatement("VACUUM m1.p")) {
        ps.execute();
        c.setClientInfo(Proxies.PASSTHROUGH, "false");
        assertRefused(ps::execute, "0A000", "cannot be parsed");
        c.setClientInfo(Proxies.PASSTHROUGH, "true");
        c.setAutoCommit(false);
        assertRefused(ps::execute, "0A000", "autocommit");
        c.setAutoCommit(true);
      }
    }
  }

  @Test
  void finding057_setAutoCommitTrue_endsTheTransaction_forTheSessionToo() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("SET search_path = m1");
      c.setAutoCommit(false);
      st.execute("SET LOCAL search_path = m1plain");
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read plain");
      c.setAutoCommit(true); // commits; PostgreSQL reverts the path to m1
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("refused 0A000");
    }
  }

  @Test
  void finding057_rollbackToASavepoint_doesNotEndTheTransaction() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("SET search_path = m1");
      c.setAutoCommit(false);
      st.execute("SET LOCAL search_path = m1plain");
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read plain");
      c.rollback(c.setSavepoint());
      // the path is still m1plain until the transaction ends
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read plain");
      c.commit(); // reverts the path to m1
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("refused 0A000");
      c.setAutoCommit(true);
    }
  }

  @Test
  void finding058_setConfig_isSeenByTheNextLookup() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("SET search_path = m1plain");
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read plain");
      st.executeQuery("SELECT set_config('search_path', 'm1', false)").close();
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("refused 0A000");
    }
  }

  @Test
  void finding058_dynamicSqlInAFunction_isRefused() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      assertThat(
              outcome(
                  () ->
                      st.executeQuery(
                          "SELECT query_to_xml('SELECT x FROM m1.s', true, false, '')")))
          .isEqualTo("refused 0A000");
    }
  }
}
