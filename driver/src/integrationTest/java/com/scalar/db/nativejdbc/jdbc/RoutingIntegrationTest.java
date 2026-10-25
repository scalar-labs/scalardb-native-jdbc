package com.scalar.db.nativejdbc.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.NativeJdbcDriver;
import com.scalar.db.nativejdbc.ScalarDbTables;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Milestones 1 and 3 on PostgreSQL: every statement goes through the router; plain tables work as
 * they do without the driver, and nothing reaches a ScalarDB table unrewritten. The probe {@code
 * SELECT x FROM s} reads "plain" from m1plain.s and "scalardb" from m1.s, through its read view.
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
          "CREATE TABLE IF NOT EXISTS m1.counted (id SERIAL PRIMARY KEY, x TEXT)",
          "CREATE TABLE IF NOT EXISTS m1.s (id INT PRIMARY KEY, x TEXT)",
          "CREATE TABLE IF NOT EXISTS m1plain.s (id INT PRIMARY KEY, x TEXT)");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      admin.createNamespace("m1", true);
      if (!admin.tableExists("m1", "s")) {
        admin.importTable("m1", "s", Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
    }
    try (Connection c = PG.open()) {
      Setup.run(
          new Catalog(PG.engine(), Catalog.DEFAULT_METADATA_SCHEMA, PG::open),
          c,
          t -> t.namespace().equals("m1"),
          Duration.ofMinutes(1),
          List.of());
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
        assertThat(st.executeUpdate("DELETE FROM m1.counted")).isGreaterThanOrEqualTo(0);
        assertThat(
                st.executeUpdate(
                    "INSERT INTO m1.counted (x) VALUES ('a')", Statement.RETURN_GENERATED_KEYS))
            .isEqualTo(1);
        try (ResultSet keys = st.getGeneratedKeys()) {
          assertThat(keys.next()).isTrue();
          assertThat(keys.getStatement()).isSameAs(st);
        }
        st.addBatch("INSERT INTO m1.counted (x) VALUES ('b')");
        st.addBatch("INSERT INTO m1.counted (x) VALUES ('c')");
        assertThat(st.executeBatch()).containsExactly(1, 1);
        assertThat(st.getConnection()).isSameAs(c);
      }
      try (PreparedStatement ps =
          c.prepareStatement("SELECT count(*) FROM m1.counted WHERE x <> ?")) {
        ps.setString(1, "z");
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getInt(1)).isEqualTo(3);
          assertThat(rs.getStatement()).isSameAs(ps);
        }
      }
      assertThat(c.getMetaData().getConnection()).isSameAs(c);
      // a metadata result set hands back no vendor statement, so no way around the router (059)
      try (ResultSet tables = c.getMetaData().getTables(null, "m1", "%", null)) {
        assertThat(tables.getStatement()).isNull();
        assertThat(tables.isWrapperFor(ResultSet.class)).isTrue();
      }
      assertThat(c.unwrap(Class.forName("org.postgresql.PGConnection"))).isNotNull();
    }
  }

  @Test
  void scalarDbTables_classifiesByStoredName() throws Exception {
    try (Connection c = connect()) {
      assertThat(c.isWrapperFor(ScalarDbTables.class)).isTrue();
      ScalarDbTables tables = c.unwrap(ScalarDbTables.class);
      assertThat(tables.kind("m1", "s")).isEqualTo(ScalarDbTables.Kind.SCALARDB);
      assertThat(tables.kind("m1plain", "s")).isEqualTo(ScalarDbTables.Kind.PLAIN);
      assertThat(tables.kind("m1", "s_tx_metadata")).isEqualTo(ScalarDbTables.Kind.REFUSED);
      assertThat(tables.kind("m1", "s_scalardb")).isEqualTo(ScalarDbTables.Kind.REFUSED);
      assertThat(tables.kind("m1", "no_such_table")).isEqualTo(ScalarDbTables.Kind.PLAIN);
      // no schema: the session's, as for an unqualified name (todo 115)
      c.setSchema("m1");
      assertThat(tables.kind(null, "s")).isEqualTo(ScalarDbTables.Kind.SCALARDB);
    }
  }

  @Test
  void readsOfAScalarDbTable_goThroughTheReadView_andEverythingElseIsRefused() throws Exception {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM m1.s"))).isEqualTo("read scalardb");
      assertThat(
              outcome(
                  () ->
                      st.executeQuery(
                          "SELECT count(*) FROM m1.p WHERE id IN (SELECT id FROM m1.s)")))
          .startsWith("read");
      try (PreparedStatement ps = c.prepareStatement("SELECT s.x FROM m1.s WHERE s.id = ?")) {
        ps.setInt(1, 99);
        assertThat(outcome(ps::executeQuery)).isEqualTo("read scalardb");
      }
      // the writes the driver does not run (WriteIntegrationTest has the ones it does)
      assertRefused(
          () -> st.executeUpdate("UPDATE m1.s SET id = 2 WHERE id = 1"), "0AN01", "key column");
      assertRefused(() -> st.execute("DELETE FROM m1.s RETURNING x"), "0AN01", "RETURNING");
      assertRefused(
          () -> st.executeUpdate("INSERT INTO m1.s (id, x) VALUES (1, 'a')", new int[] {1}),
          "0AN01",
          "column index");
      assertRefused(() -> st.executeQuery("SELECT * FROM m1.s_scalardb"), "42000", "virtual table");
      assertRefused(() -> st.executeQuery("SELECT * FROM m1.s_tx_metadata"), "42000", "metadata");
      // a subquery in a SET is a read whose result changes the session: not a plain read
      assertRefused(() -> st.execute("SET x.y = (SELECT x FROM m1.s LIMIT 1)"), "0AN01", "m1.s");
      // a schema change goes through ScalarDB's admin, then the setup
      assertRefused(() -> st.execute("ALTER TABLE m1.s ADD COLUMN y INT"), "0AN01", "admin");
      assertRefused(() -> st.execute("CREATE INDEX s_x ON m1.s (x)"), "0AN01", "admin");
      assertRefused(() -> st.execute("TRUNCATE m1.s"), "0AN01", "admin");
      assertRefused(
          () ->
              st.executeQuery(
                  "SELECT s.x FROM m1.s s JOIN m1.p p ON p.id = s.id WHERE s.id = 99 FOR UPDATE"),
          "0AN01",
          "locking");
      assertRefused(
          () -> st.executeQuery("SELECT x FROM m1.s TABLESAMPLE SYSTEM (100)"), "0AN01", "hint");
    }
  }

  @Test
  void aReadOfAScalarDbTable_isServedAtTheSnapshotLevels_preparedOnesToo() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement();
        PreparedStatement ps = c.prepareStatement("SELECT x FROM m1.s")) {
      c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM m1.s"))).isEqualTo("read scalardb");
      // routed again for the level: its rows carry their states (milestone 7)
      assertThat(outcome(ps::executeQuery)).isEqualTo("read scalardb");
      c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      assertThat(outcome(ps::executeQuery)).isEqualTo("read scalardb");
    }
  }

  @Test
  void statementsTheParserCannotRead_areRefused_andPassthroughRunsThemInAutocommit()
      throws SQLException {
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      assertRefused(() -> st.execute("VACUUM m1.p"), "0AN01", "cannot be parsed");
      assertRefused(() -> c.prepareCall("{call pg_sleep(0)}"), "0AN01", "cannot be parsed");
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
      assertThat(c.getClientInfo(NativeJdbcDriver.PASSTHROUGH)).isEqualTo("true");
      st.execute("VACUUM m1.p");
      c.setAutoCommit(false);
      // leaving autocommit turned passthrough off (060)
      assertThat(c.getClientInfo(NativeJdbcDriver.PASSTHROUGH)).isEqualTo("false");
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
      assertRefused(() -> st.execute("VACUUM m1.p"), "0AN01", "autocommit");
      c.setAutoCommit(true);
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "false");
      assertRefused(() -> st.execute("VACUUM m1.p"), "0AN01", "cannot be parsed");
      // a set of client info without the key clears it, as JDBC says
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
      c.setClientInfo(new Properties());
      assertThat(c.getClientInfo(NativeJdbcDriver.PASSTHROUGH)).isEqualTo("false");
    }
  }

  @Test
  void passthrough_refusesAStatementThatNamesAScalarDbTable_parsedOrNot() throws SQLException {
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
      // sent as written, each would bypass ScalarDB's protocol on m1.s (todo 323)
      assertRefused(() -> st.execute("VACUUM m1.s"), "0AN01", "names a ScalarDB table");
      assertRefused(
          () -> st.execute("UPDATE m1.s SET x = 'y' WHERE id = 1"),
          "0AN01",
          "names a ScalarDB table");
      assertRefused(
          () -> st.execute("DO $$ BEGIN EXECUTE 'DELETE FROM m1.s'; END $$"),
          "0AN01",
          "names a ScalarDB table");
      // ScalarDB's own schema, its catalog tables
      assertRefused(
          () -> st.execute("DELETE FROM scalardb.metadata WHERE false"),
          "0AN01",
          "names a ScalarDB table");
      // a plain table of the same name in another schema too: the words are checked, not the
      // tables they resolve to
      assertRefused(() -> st.execute("VACUUM m1plain.s"), "0AN01", "names a ScalarDB table");
      // nor in a comment, which an engine may read otherwise than the driver (todo 330)
      assertRefused(() -> st.execute("VACUUM m1.p -- not m1.s"), "0AN01", "names a ScalarDB table");
      // a statement that names none still runs
      st.execute("VACUUM m1.p -- a note");
      assertThat(c.getClientInfo(NativeJdbcDriver.PASSTHROUGH)).isEqualTo("true");
    }
  }

  @Test
  void anUnqualifiedName_resolvesTheWayTheSessionDoes_afterSetAndSetSchema() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("SET search_path = m1plain");
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM s")) {
        assertThat(rs.next()).isTrue(); // the plain m1plain.s
      }
      st.execute("SET search_path TO m1"); // the spelling with TO too (todo 139)
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
      c.setSchema("m1plain");
      try (ResultSet rs = st.executeQuery("SELECT count(*) FROM s")) {
        assertThat(rs.next()).isTrue();
      }
      // a SET inside a transaction that is rolled back is undone, and so is the driver's view
      c.setAutoCommit(false);
      st.execute("SET LOCAL search_path TO m1");
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
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
          "0AN01",
          "READ UNCOMMITTED");
    }
  }

  @Test
  void aPoolOverTheDriver_keepsTheDriversViewOfEachSession_acrossBorrows() throws SQLException {
    seedDistinctRows();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setDataSourceProperties(props);
    config.setMaximumPoolSize(1); // every borrow gets the same connection
    config.setSchema("m1"); // set at creation, and restored on return when a borrower changed it
    try (HikariDataSource pool = new HikariDataSource(config)) {
      try (Connection c = pool.getConnection();
          Statement st = c.createStatement()) {
        assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
        c.setSchema("m1plain");
        assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read plain");
      }
      try (Connection c = pool.getConnection();
          Statement st = c.createStatement()) {
        // the pool put m1 back through setSchema, and the driver followed
        assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
        st.execute("SET search_path = m1plain");
      }
      try (Connection c = pool.getConnection();
          Statement st = c.createStatement()) {
        // a SET is invisible to the pool and survives the return; the driver still knows it
        assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read plain");
      }
    }
  }

  // Review findings 055-058. The helper reports which table a statement actually read: "scalardb"
  // is the ScalarDB table m1.s, which a read now reaches through its view.

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
      // only passthrough may batch a SET (backgroundReview_aSetInsideABatch_isRefused); its effect
      // must still reach the next lookup
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
      st.addBatch("SET search_path = m1");
      st.executeBatch();
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "false");
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
    }
  }

  private static String rawRow(int id) throws SQLException {
    try (Connection c = PG.open();
        PreparedStatement ps = c.prepareStatement("SELECT x FROM m1.s WHERE id = ?")) {
      ps.setInt(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? "written into m1.s" : "not in m1.s";
      }
    }
  }

  private static String batchOutcome(Statement st) {
    try {
      st.executeBatch();
      return "ran";
    } catch (SQLException e) {
      return "refused " + e.getSQLState();
    }
  }

  @Test
  void backgroundReview_aSetInsideABatch_isRefused() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("SET search_path = m1plain");
      // the INSERT after it would have been routed under m1plain and run under m1
      assertRefused(() -> st.addBatch("SET search_path = m1"), "0AN01", "cannot be batched");
      assertRefused(
          () -> st.addBatch("CREATE TABLE m1plain.t2 (id INT)"), "0AN01", "cannot be batched");
    }
  }

  @Test
  void backgroundReview_setSchemaBetweenAddBatchAndExecuteBatch() throws SQLException {
    seedDistinctRows();
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      c.setSchema("m1plain");
      st.addBatch("INSERT INTO s VALUES (102, 'raw')");
      c.setSchema("m1");
      // run as what the text now names, a write of the ScalarDB table, under its protocol
      String outcome = batchOutcome(st);
      assertThat(outcome + ", " + rawRow(102)).isEqualTo("ran, written into m1.s");
    }
  }

  @Test
  void aStatementText_isRoutedOnce_untilWhatItWasDecidedUnderMoves() throws SQLException {
    Catalog catalog = new Catalog(PG.engine(), Catalog.DEFAULT_METADATA_SCHEMA, PG::open);
    Session session = new Session(PG.engine());
    Router router = new Router(PG.engine(), catalog, session);
    String sql = "SELECT x FROM m1.s WHERE id = ?";
    try (Connection vendor = PG.open()) {
      Router.Route first = router.route(vendor, sql);
      assertThat(router.route(vendor, sql)).isSameAs(first);
      catalog.invalidate();
      Router.Route second = router.route(vendor, sql);
      assertThat(second).isNotSameAs(first);
      assertThat(second.sql).isEqualTo(first.sql);
      session.changed(vendor);
      assertThat(router.route(vendor, sql)).isNotSameAs(second);
    }
  }

  @Test
  void backgroundReview_aPreparedRoute_expiresWithTheCatalogTtl() throws Exception {
    try (Connection c = PG.open()) {
      PG.execute(c, "CREATE TABLE IF NOT EXISTS m1.late (id INT PRIMARY KEY, x TEXT)");
    }
    try (DistributedTransactionAdmin admin = PG.admin()) {
      if (admin.tableExists("m1", "late")) {
        try (Connection c = PG.open()) {
          // the setup's view depends on the table
          Setup.teardown(
              new Catalog(PG.engine(), Catalog.DEFAULT_METADATA_SCHEMA, PG::open),
              c,
              t -> t.table().equals("late"));
        }
        admin.dropTable("m1", "late"); // a previous run imported it
        try (Connection c = PG.open()) {
          PG.execute(c, "CREATE TABLE IF NOT EXISTS m1.late (id INT PRIMARY KEY, x TEXT)");
        }
      }
    }
    Properties shortTtl = (Properties) props.clone();
    shortTtl.setProperty("scalardbnative.catalogTtlMs", "300");
    try (Connection c = DriverManager.getConnection(url, shortTtl);
        PreparedStatement ps = c.prepareStatement("SELECT x FROM m1.late")) {
      assertThat(outcome(ps::executeQuery)).isEqualTo("read nothing"); // a plain table
      try (DistributedTransactionAdmin admin = PG.admin()) {
        admin.importTable(
            "m1", "late", Map.of("transaction-metadata-decoupling", "true"), Map.of());
      }
      Thread.sleep(600); // past the TTL, with no lookup on this catalog in between
      // routed again: now a ScalarDB table, and one not set up yet
      assertThat(outcome(ps::executeQuery)).isEqualTo("refused 55000");
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
        assertThat(outcome(ps::executeQuery)).isEqualTo("refused 0AN01");
      }
    }
  }

  @Test
  void finding056_aStatementPreparedInPassthrough_isCheckedAgainWhenPassthroughEnds()
      throws SQLException {
    try (Connection c = connect()) {
      c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
      try (PreparedStatement ps = c.prepareStatement("VACUUM m1.p")) {
        ps.execute();
        c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "false");
        assertRefused(ps::execute, "0AN01", "cannot be parsed");
        c.setAutoCommit(false);
        c.setClientInfo(NativeJdbcDriver.PASSTHROUGH, "true");
        assertRefused(ps::execute, "0AN01", "autocommit");
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
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
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
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
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
      assertThat(outcome(() -> st.executeQuery("SELECT x FROM s"))).isEqualTo("read scalardb");
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
          .isEqualTo("refused 0AN01");
    }
  }
}
