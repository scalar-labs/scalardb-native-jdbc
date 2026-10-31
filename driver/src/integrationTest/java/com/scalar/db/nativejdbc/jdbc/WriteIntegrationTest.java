package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.M;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.N;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.Q;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.id;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.intType;
import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.Result;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.io.DataType;
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
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Milestone 4a on PostgreSQL and both decoupled layouts: single-statement writes of a ScalarDB
 * table at READ COMMITTED (design 4.3 to 4.5).
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "OBL_UNSATISFIED_OBLIGATION", "ODR_OPEN_DATABASE_RESOURCE"},
    justification = "statements written in the test sources, closed with their connection")
class WriteIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");

  static List<Engines> engines() {
    return Engines.selected();
  }

  /** One ScalarDB table as the test reaches it: by SQL, and by ScalarDB's own name. */
  private static final class Table {
    final String sql;
    final String meta;
    final String scalarDbName;

    Table(String sql, String meta, String scalarDbName) {
      this.sql = sql;
      this.meta = meta;
      this.scalarDbName = scalarDbName;
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void writes_onBothLayouts(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("wr"),
          "CREATE TABLE wr.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO wr.imp VALUES (1, 'a'), (2, 'b'), (3, 'c')",
          // and in the inline layout, the metadata in the row (milestone 10b)
          "CREATE TABLE wr.impi (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO wr.impi VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("wr"), true);
      admin.importTable(N("wr"), N("imp"), DECOUPLED, Map.of());
      admin.importTable(N("wr"), N("impi"), Map.of(), Map.of());
      for (String made : List.of("made", "madei")) {
        admin.createTable(
            N("wr"),
            N(made),
            TableMetadata.newBuilder()
                .addColumn(N("id"), intType())
                .addColumn(N("x"), DataType.TEXT)
                .addPartitionKey(N("id"))
                .build(),
            made.equals("made") ? DECOUPLED : Map.of());
      }
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (String made : List.of("made", "madei")) {
        for (int i = 1; i <= 3; i++) {
          tx.insert(
              Insert.newBuilder()
                  .namespace(N("wr"))
                  .table(N(made))
                  .partitionKey(id(i))
                  .textValue(N("x"), String.valueOf((char) ('a' + i - 1)))
                  .build());
        }
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals(N("wr")),
                  Duration.ofMinutes(1),
                  List.of(),
                  true))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo imp = catalog.lookup(List.of(), N("wr"), N("imp"));
    TableInfo made = catalog.lookup(List.of(), N("wr"), N("made"));
    TableInfo madei = catalog.lookup(List.of(), N("wr"), N("madei"));
    List<Table> tables =
        List.of(
            new Table(
                "wr.imp",
                "wr." + Q(imp.metadataTable()),
                Objects.requireNonNull(imp.virtualTable())),
            new Table("wr.made", "wr." + Q(made.metadataTable()), N("made")),
            // inline: the table is its own metadata table, and ScalarDB's name for itself
            new Table("wr.impi", "wr.impi", N("impi")),
            new Table("wr.madei", "wr." + Q(madei.metadataTable()), N("madei")));
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager()) {
      for (Table t : tables) {
        eachWriteKind(e, tm, t);
        inFlightRowsAbort(e, tpc, t);
        duplicates(e, tpc, t);
      }
      metadataLessRows(e, tm, tables.get(0));
    }
  }

  /** Makes {@code schema} the session's for unqualified names: a database on MySQL. */
  private static void setSchema(Engines e, Connection d, String schema) throws SQLException {
    if (e.engine() == Engine.POSTGRESQL || e.engine() == Engine.ORACLE) {
      d.setSchema(N(schema));
    } else {
      d.setCatalog(schema);
    }
  }

  private static void eachWriteKind(Engines e, DistributedTransactionManager tm, Table t)
      throws Exception {
    try (Connection d = e.openDriver()) {
      // autocommit: each statement its own transaction
      assertThat(
              d.createStatement()
                  .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (10, 'p')"))
          .isEqualTo(1);
      if (e.engine() != Engine.ORACLE || d.getMetaData().getDatabaseMajorVersion() >= 23) {
        try (PreparedStatement ps =
            d.prepareStatement("INSERT INTO " + t.sql + " (x, id) VALUES (?, ?), ('r', 12)")) {
          ps.setString(1, "q");
          ps.setInt(2, 11);
          assertThat(ps.executeUpdate()).isEqualTo(2);
        }
      } else {
        // Oracle before 23 has no multi-row VALUES
        try (PreparedStatement ps =
            d.prepareStatement("INSERT INTO " + t.sql + " (x, id) VALUES (?, ?)")) {
          for (Object[] row : List.of(new Object[] {"q", 11}, new Object[] {"r", 12})) {
            ps.setString(1, (String) row[0]);
            ps.setInt(2, (Integer) row[1]);
            assertThat(ps.executeUpdate()).isEqualTo(1);
          }
        }
      }
      try (PreparedStatement ps =
          d.prepareStatement("UPDATE " + t.sql + " SET x = ? WHERE id = ?")) {
        ps.setString(1, "P");
        ps.setInt(2, 10);
        assertThat(ps.executeUpdate()).isEqualTo(1);
        ps.setInt(2, 99);
        assertThat(ps.executeUpdate()).as("no such key").isZero();
      }
      // by predicate, with a line comment after it
      assertThat(
              d.createStatement()
                  .executeUpdate(
                      "UPDATE " + t.sql + " SET x = CONCAT(x, '!') WHERE id IN (11, 12) -- two"))
          .isEqualTo(2);
      // execute() answers with an update count
      Statement st = d.createStatement();
      assertThat(st.execute("DELETE FROM " + t.sql + " WHERE id = 12")).isFalse();
      assertThat(st.getUpdateCount()).isEqualTo(1);
      assertThat(st.getMoreResults()).isFalse();
      assertThat(st.getUpdateCount()).isEqualTo(-1);
      // in an explicit transaction, rolled back: nothing stays
      d.setAutoCommit(false);
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'gone' WHERE id = 10");
      d.rollback();
      d.setAutoCommit(true);
      assertThat(rows(d, "SELECT id, x FROM " + t.sql + " WHERE id >= 10 ORDER BY id"))
          .containsExactly("10/P", "11/q!");
    }
    // ScalarDB reads the driver's writes as committed values
    try (Connection v = e.open()) {
      assertThat(rows(v, M("SELECT tx_state, tx_version FROM ") + t.meta + " WHERE id = 10"))
          .containsExactly("3/2");
      // a DELETE on an imported table keeps the metadata row as a tombstone (4.5, todo 132)
      assertThat(rows(v, "SELECT COUNT(*) FROM " + t.meta + " WHERE id = 12"))
          .containsExactly(t.sql.equals("wr.imp") ? "1" : "0");
    }
    assertThat(scalarDbX(tm, t, 10)).contains("P");
    assertThat(scalarDbX(tm, t, 12)).isEmpty();
    // a ScalarDB update that read the row before the driver wrote it cannot commit
    DistributedTransaction tx = tm.begin();
    tx.get(Get.newBuilder().namespace(N("wr")).table(t.scalarDbName).partitionKey(id(11)).build());
    try (Connection d = e.openDriver()) {
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'driver' WHERE id = 11");
    }
    tx.update(
        Update.newBuilder()
            .namespace(N("wr"))
            .table(t.scalarDbName)
            .partitionKey(id(11))
            .textValue(N("x"), "scalardb")
            .build());
    assertThatThrownBy(tx::commit).isInstanceOf(TransactionException.class);
    assertThat(scalarDbX(tm, t, 11)).contains("driver");
  }

  private static void inFlightRowsAbort(Engines e, TwoPhaseCommitTransactionManager tpc, Table t)
      throws Exception {
    TwoPhaseCommitTransaction tx = tpc.begin();
    tx.update(
        Update.newBuilder()
            .namespace(N("wr"))
            .table(t.scalarDbName)
            .partitionKey(id(1))
            .textValue(N("x"), "held")
            .build());
    tx.prepare();
    try (Connection d = e.openDriver()) {
      // autocommit: the abort ends the statement's own transaction
      assertThatThrownBy(
              () ->
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'w' WHERE id = 1"))
          .isInstanceOf(SQLTransactionRollbackException.class)
          .extracting("SQLState")
          .isEqualTo("40001");
      // by predicate, in a transaction: rollback-only until the application rolls back
      d.setAutoCommit(false);
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'w2' WHERE id = 2");
      assertThatThrownBy(
              () -> d.createStatement().executeUpdate("DELETE FROM " + t.sql + " WHERE id <= 2"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      assertThatThrownBy(() -> rows(d, "SELECT 1"))
          .isInstanceOf(SQLTransactionRollbackException.class);
      assertThatThrownBy(d::commit).isInstanceOf(SQLTransactionRollbackException.class);
      d.rollback();
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id IN (1, 2) ORDER BY id"))
          .containsExactly("a", "b");
      d.setAutoCommit(true);
    } finally {
      tx.rollback();
    }
    // the row as ScalarDB left it
    try (Connection v = e.open()) {
      assertThat(rows(v, "SELECT x FROM " + t.sql + " WHERE id = 1")).containsExactly("a");
    }
  }

  private static void duplicates(Engines e, TwoPhaseCommitTransactionManager tpc, Table t)
      throws Exception {
    try (Connection d = e.openDriver()) {
      d.setAutoCommit(false);
      // the application's duplicate, as the database raised it; the transaction goes on
      assertThatThrownBy(
              () ->
                  d.createStatement()
                      .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (3, 'd')"))
          .isInstanceOf(SQLException.class)
          .satisfies(ex -> assertThat(e.engine().isDuplicateKey((SQLException) ex)).isTrue());
      d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'C' WHERE id = 3");
      d.commit();
      d.setAutoCommit(true);
      assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 3")).containsExactly("C");
      // a key a ScalarDB transaction is inserting: in flight, not the application's duplicate
      TwoPhaseCommitTransaction tx = tpc.begin();
      tx.insert(
          Insert.newBuilder()
              .namespace(N("wr"))
              .table(t.scalarDbName)
              .partitionKey(id(20))
              .textValue(N("x"), "s")
              .build());
      tx.prepare();
      try {
        assertThatThrownBy(
                () ->
                    d.createStatement()
                        .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (20, 'd')"))
            .isInstanceOf(SQLTransactionRollbackException.class);
      } finally {
        tx.rollback();
      }
    }
  }

  /** Rows of an imported table without metadata, or with NULL metadata, before a backfill. */
  private static void metadataLessRows(Engines e, DistributedTransactionManager tm, Table t)
      throws Exception {
    try (Connection v = e.open()) {
      e.execute(
          v,
          "INSERT INTO " + t.sql + " VALUES (30, 'n'), (31, 'n'), (32, 'n')",
          "INSERT INTO " + t.meta + " (id) VALUES (31)");
    }
    try (Connection d = e.openDriver()) {
      // no metadata row, and a NULL one: stamped at version 1
      assertThat(
              d.createStatement()
                  .executeUpdate("UPDATE " + t.sql + " SET x = 'u' WHERE id IN (30, 31)"))
          .isEqualTo(2);
    }
    try (Connection v = e.open()) {
      assertThat(
              rows(
                  v,
                  M("SELECT id, tx_state, tx_version FROM ")
                      + t.meta
                      + " WHERE id IN (30, 31) ORDER BY id"))
          .containsExactly("30/3/1", "31/3/1");
    }
    // a ScalarDB update that read row 32 with no metadata: the driver's DELETE leaves a tombstone,
    // and the update cannot bring the row back (4.5)
    DistributedTransaction tx = tm.begin();
    assertThat(
            tx.get(
                Get.newBuilder()
                    .namespace(N("wr"))
                    .table(t.scalarDbName)
                    .partitionKey(id(32))
                    .build()))
        .isPresent();
    try (Connection d = e.openDriver()) {
      assertThat(d.createStatement().executeUpdate("DELETE FROM " + t.sql + " WHERE id = 32"))
          .isEqualTo(1);
    }
    try (Connection v = e.open()) {
      assertThat(rows(v, M("SELECT tx_state, tx_version FROM ") + t.meta + " WHERE id = 32"))
          .containsExactly("3/1");
    }
    tx.update(
        Update.newBuilder()
            .namespace(N("wr"))
            .table(t.scalarDbName)
            .partitionKey(id(32))
            .textValue(N("x"), "back")
            .build());
    assertThatThrownBy(tx::commit).isInstanceOf(TransactionException.class);
    assertThat(scalarDbX(tm, t, 32)).isEmpty();
    // the driver's INSERT takes the tombstone over
    try (Connection d = e.openDriver()) {
      assertThat(
              d.createStatement()
                  .executeUpdate("INSERT INTO " + t.sql + " (id, x) VALUES (32, 'again')"))
          .isEqualTo(1);
    }
    try (Connection v = e.open()) {
      assertThat(rows(v, M("SELECT tx_state, tx_version FROM ") + t.meta + " WHERE id = 32"))
          .containsExactly("3/2");
    }
    assertThat(scalarDbX(tm, t, 32)).contains("again");
  }

  /** The findings of the 4a review (todos 130-138), each asserting the fixed behaviour. */
  @ParameterizedTest
  @MethodSource("engines")
  void reviewFindings(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("rpl"),
          e.createSchema("rsd"),
          e.createSchema("rf"),
          "CREATE TABLE rpl.t (id " + e.intType() + " PRIMARY KEY, x " + e.textType() + ")",
          "INSERT INTO rpl.t VALUES (1, 'plain')",
          "CREATE TABLE rsd.t (id " + e.intType() + " PRIMARY KEY, x " + e.textType() + ")",
          "INSERT INTO rsd.t VALUES (1, 'scalardb')",
          "CREATE TABLE rf.imp (id "
              + e.intType()
              + " PRIMARY KEY, x "
              + e.textType()
              + ", b "
              + e.binaryType()
              + ")",
          "INSERT INTO rf.imp VALUES (60, 'a', NULL), (70, 'a', NULL), (80, 'a', NULL),"
              + " (85, 'a', NULL)",
          "CREATE TABLE rf.ch (k CHAR(4) PRIMARY KEY, x " + e.textType() + ")",
          "INSERT INTO rf.ch VALUES ('ab', 'a')",
          // a local date-time: DATETIME on MySQL, whose TIMESTAMP is converted by time zone; SQL
          // Server's datetime key is refused (253)
          "CREATE TABLE rf.ts (k "
              + switch (e.engine()) {
                case POSTGRESQL -> "TIMESTAMP";
                case SQLSERVER -> "DATETIME2(3)";
                case ORACLE -> "TIMESTAMP(3)";
                default -> "DATETIME";
              }
              + " PRIMARY KEY, x "
              + e.textType()
              + ")",
          "INSERT INTO rf.ts VALUES ("
              + (e.engine() == Engine.ORACLE ? "TIMESTAMP " : "")
              + "'2020-03-08 02:30:00', 'a')",
          switch (e.engine()) {
            case POSTGRESQL ->
                "CREATE FUNCTION rf.gate(v TEXT) RETURNS BOOLEAN AS $$ BEGIN PERFORM pg_sleep(3);"
                    + " RETURN v = 'a'; END $$ LANGUAGE plpgsql VOLATILE";
            // a function cannot WAITFOR there: it spins
            case SQLSERVER ->
                "CREATE FUNCTION rf.gate(@v VARCHAR(400)) RETURNS BIT AS BEGIN"
                    + " DECLARE @t DATETIME2 = SYSDATETIME();"
                    + " WHILE DATEDIFF(millisecond, @t, SYSDATETIME()) < 3000 SET @t = @t;"
                    + " RETURN CASE WHEN @v = 'a' THEN 1 ELSE 0 END; END";
            case ORACLE ->
                "CREATE OR REPLACE FUNCTION rf.gate(v VARCHAR2) RETURN NUMBER IS BEGIN"
                    + " DBMS_SESSION.SLEEP(3); RETURN CASE WHEN v = 'a' THEN 1 ELSE 0 END; END;";
            default ->
                "CREATE FUNCTION rf.gate(v TEXT) RETURNS BOOLEAN NOT DETERMINISTIC NO SQL"
                    + " BEGIN DO SLEEP(3); RETURN v = 'a'; END";
          });
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("rsd"), true);
      admin.createNamespace(N("rf"), true);
      admin.importTable(N("rsd"), N("t"), DECOUPLED, Map.of());
      admin.importTable(N("rf"), N("imp"), DECOUPLED, Map.of());
      admin.importTable(N("rf"), N("ch"), DECOUPLED, Map.of());
      admin.importTable(N("rf"), N("ts"), DECOUPLED, Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals(N("rsd")) || t.namespace().equals(N("rf")),
                  Duration.ofMinutes(1),
                  List.of(),
                  true))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo imp = catalog.lookup(List.of(), N("rf"), N("imp"));
    Table t =
        new Table(
            "rf.imp", "rf." + Q(imp.metadataTable()), Objects.requireNonNull(imp.virtualTable()));
    String sdMeta = "rsd." + Q(catalog.lookup(List.of(), N("rsd"), N("t")).metadataTable());
    String chMeta = "rf." + Q(catalog.lookup(List.of(), N("rf"), N("ch")).metadataTable());

    // 130: a prepared plain write that comes to name a ScalarDB table is refused, not run as text;
    // SQL Server resolves names through the user's default schema, which a session cannot change
    if (e.engine() != Engine.SQLSERVER) {
      try (Connection d = e.openDriver();
          Connection v = e.open()) {
        setSchema(e, d, "rpl");
        PreparedStatement ps = d.prepareStatement("UPDATE t SET x = ? WHERE id = ?");
        ps.setString(1, "p");
        ps.setInt(2, 1);
        ps.executeUpdate();
        setSchema(e, d, "rsd");
        ps.setString(1, "raw");
        assertThatThrownBy(ps::executeUpdate).hasMessageContaining("prepare it again");
        assertThat(rows(v, "SELECT x FROM rsd.t WHERE id = 1")).containsExactly("scalardb");
        assertThat(rows(v, M("SELECT tx_version FROM ") + sdMeta + " WHERE id = 1"))
            .containsExactly("1");
      }
    }
    // 131: streams are read once and bound whole into every statement of the write
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      PreparedStatement ps = d.prepareStatement("INSERT INTO rf.imp (id, x, b) VALUES (?, ?, ?)");
      ps.setInt(1, 90);
      ps.setCharacterStream(2, new java.io.StringReader("hello"));
      ps.setBinaryStream(3, new java.io.ByteArrayInputStream(new byte[] {1, 2, 3}));
      assertThat(ps.executeUpdate()).isEqualTo(1);
      ps = d.prepareStatement("UPDATE rf.imp SET x = ? WHERE id IN (60, 90)");
      ps.setCharacterStream(1, new java.io.StringReader("streamed-and-more"), 8);
      assertThat(ps.executeUpdate()).isEqualTo(2);
      assertThat(
              rows(
                  v,
                  "SELECT id, x, "
                      + (e.engine() == Engine.SQLSERVER ? "datalength(b)" : "length(b)")
                      + " FROM rf.imp WHERE id IN (60, 90) ORDER BY id"))
          .containsExactly("60/streamed/null", "90/streamed/3");
      e.execute(v, "UPDATE rf.imp SET x = 'a' WHERE id = 60");
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      // 132: an UPDATE then a DELETE of a row without metadata keep a tombstone (imported table)
      try (Connection v = e.open()) {
        e.execute(v, "INSERT INTO rf.imp (id, x) VALUES (50, 'n')");
      }
      DistributedTransaction z = tm.begin();
      z.get(Get.newBuilder().namespace(N("rf")).table(t.scalarDbName).partitionKey(id(50)).build());
      try (Connection d = e.openDriver()) {
        d.createStatement().executeUpdate("UPDATE rf.imp SET x = 'u' WHERE id = 50");
        d.createStatement().executeUpdate("DELETE FROM rf.imp WHERE id = 50");
      }
      z.update(
          Update.newBuilder()
              .namespace(N("rf"))
              .table(t.scalarDbName)
              .partitionKey(id(50))
              .textValue(N("x"), "back")
              .build());
      assertThatThrownBy(z::commit).isInstanceOf(TransactionException.class);
      assertThat(scalarDbX(tm, t, 50)).isEmpty();
      try (Connection v = e.open()) {
        assertThat(rows(v, M("SELECT tx_state, tx_version FROM ") + t.meta + " WHERE id = 50"))
            .containsExactly("3/2");
      }
    }
    // 133: executeBatch on a prepared write is an empty batch
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      PreparedStatement ps = d.prepareStatement("UPDATE rf.imp SET x = ? WHERE id = ?");
      ps.setString(1, "batch");
      ps.setInt(2, 80);
      assertThat(ps.executeBatch()).isEmpty();
      assertThat(rows(v, "SELECT x FROM rf.imp WHERE id = 80")).containsExactly("a");
    }
    // 135: a CHAR(n) key is matched in the metadata table as it is stored there
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      assertThat(d.createStatement().executeUpdate("UPDATE rf.ch SET x = 'z' WHERE x = 'a'"))
          .isEqualTo(1);
      assertThat(rows(v, M("SELECT tx_version FROM ") + chMeta)).containsExactly("2");
    }
    // 136: the query timeout bounds the write's own statements; not on Oracle here, whose
    // containerised server takes no cancel at all: ojdbc's break never reaches it, and a plain
    // DBMS_SESSION.SLEEP under a one-second timeout runs its ten seconds
    if (e.engine() != Engine.ORACLE) {
      try (Connection lock = e.open();
          Connection d = e.openDriver()) {
        lock.setAutoCommit(false);
        rows(lock, "SELECT x FROM rf.imp WHERE id = 70 FOR UPDATE");
        Statement st = d.createStatement();
        st.setQueryTimeout(1);
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> st.executeUpdate("UPDATE rf.imp SET x = 't' WHERE id = 70"))
            .isInstanceOf(SQLException.class);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(3000);
        lock.rollback();
        assertThat(d.getAutoCommit()).isTrue();
      }
    }
    // 137: SQL ROLLBACK ends rollback-only; a write closes the statement's earlier result set
    try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
        Connection d = e.openDriver()) {
      TwoPhaseCommitTransaction held = tpc.begin();
      held.update(
          Update.newBuilder()
              .namespace(N("rf"))
              .table(t.scalarDbName)
              .partitionKey(id(85))
              .textValue(N("x"), "held")
              .build());
      held.prepare();
      try {
        d.setAutoCommit(false);
        assertThatThrownBy(
                () -> d.createStatement().executeUpdate("UPDATE rf.imp SET x = 'w' WHERE id = 85"))
            .isInstanceOf(SQLTransactionRollbackException.class);
        d.createStatement().execute("ROLLBACK");
        assertThat(rows(d, "SELECT x FROM rf.imp WHERE id = 80")).containsExactly("a");
        d.setAutoCommit(true);
      } finally {
        held.rollback();
      }
      Statement st = d.createStatement();
      ResultSet rs = st.executeQuery("SELECT x FROM rf.imp WHERE id = 80");
      st.executeUpdate("UPDATE rf.imp SET x = 'a' WHERE id = 80");
      assertThat(rs.isClosed()).isTrue();
    }
    // 138: a metadata row with a tx_id and a NULL state is committed
    try (Connection d = e.openDriver();
        Connection v = e.open()) {
      e.execute(v, "UPDATE " + t.meta + M(" SET tx_state = NULL WHERE id = 80"));
      assertThat(d.createStatement().executeUpdate("UPDATE rf.imp SET x = 'n' WHERE id = 80"))
          .isEqualTo(1);
    }
    // 138: a TIMESTAMP key in the JVM zone's spring-forward gap binds back unchanged
    java.util.TimeZone zone = java.util.TimeZone.getDefault();
    java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"));
    try (Connection d = e.openDriver()) {
      assertThat(d.createStatement().executeUpdate("UPDATE rf.ts SET x = 'z' WHERE x = 'a'"))
          .isEqualTo(1);
    } finally {
      java.util.TimeZone.setDefault(zone);
    }
    // 134: a prepare and rollback around the per-key UPDATE abort it, never skip the row
    try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
        Connection d = e.openDriver();
        Connection v = e.open()) {
      java.util.concurrent.CompletableFuture<Object> write =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return d.createStatement()
                      .executeUpdate(
                          "UPDATE rf.imp SET x = 'drv' WHERE id = 60 AND "
                              + (e.engine() == Engine.SQLSERVER || e.engine() == Engine.ORACLE
                                  ? "rf.gate(x) = 1"
                                  : "rf.gate(x)"));
                } catch (SQLException ex) {
                  return ex;
                }
              });
      Thread.sleep(1000); // the keys are read, the gate sleeping
      TwoPhaseCommitTransaction tx = tpc.begin();
      tx.update(
          Update.newBuilder()
              .namespace(N("rf"))
              .table(t.scalarDbName)
              .partitionKey(id(60))
              .textValue(N("x"), "held")
              .build());
      tx.prepare();
      Thread.sleep(3000); // the per-key UPDATE reads the image in flight
      tx.rollback();
      if (e.engine() == Engine.POSTGRESQL || e.engine() == Engine.ORACLE) {
        // one statement there (todo 328; on Oracle the statement first, todo 334): its UPDATE
        // rechecks the row the rollback restored, committed and matching, and writes it, at the
        // version the keys were read at
        assertThat(write.join()).isEqualTo(1);
        assertThat(rows(v, "SELECT x FROM rf.imp WHERE id = 60")).containsExactly("drv");
      } else {
        assertThat(write.join()).isInstanceOf(SQLTransactionRollbackException.class);
        assertThat(rows(v, "SELECT x FROM rf.imp WHERE id = 60")).containsExactly("a");
      }
    }
  }

  /** Milestone 4b: a read in a transaction anchors the writes that follow it (design 4.2, 4.3). */
  @ParameterizedTest
  @MethodSource("engines")
  void anchorsFromReads(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("an"),
          "CREATE TABLE an.imp (id " + e.intType() + " PRIMARY KEY, x " + e.textType() + ")",
          "INSERT INTO an.imp SELECT g, 'a' FROM " + e.series(1, 11));
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("an"), true);
      admin.importTable(N("an"), N("imp"), DECOUPLED, Map.of());
      admin.createTable(
          N("an"),
          N("made"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("x"), DataType.TEXT)
              .addPartitionKey(N("id"))
              .build(),
          DECOUPLED);
    }
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 11; i++) {
        tx.insert(
            Insert.newBuilder()
                .namespace(N("an"))
                .table(N("made"))
                .partitionKey(id(i))
                .textValue(N("x"), "a")
                .build());
      }
      tx.commit();
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals(N("an")),
                  Duration.ofMinutes(1),
                  List.of(),
                  true))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    TableInfo imp = catalog.lookup(List.of(), N("an"), N("imp"));
    List<Table> tables =
        List.of(
            new Table(
                "an.imp",
                "an." + Q(imp.metadataTable()),
                Objects.requireNonNull(imp.virtualTable())),
            new Table(
                "an.made",
                "an." + Q(catalog.lookup(List.of(), N("an"), N("made")).metadataTable()),
                N("made")));
    try (DistributedTransactionManager tm = scalarDb.getTransactionManager();
        TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager()) {
      for (Table t : tables) {
        try (Connection d = e.openDriver();
            Connection other = e.openDriver()) {
          // the application's columns only
          try (PreparedStatement ps =
              d.prepareStatement("SELECT x AS label FROM " + t.sql + " WHERE id = ?")) {
            ps.setInt(1, 1);
            assertThat(ps.getMetaData().getColumnCount()).isEqualTo(1);
            try (ResultSet rs = ps.executeQuery()) {
              assertThat(rs.getMetaData().getColumnCount()).isEqualTo(1);
              assertThat(rs.next()).isTrue();
              assertThat(rs.getString("label")).isEqualTo("a");
              assertThatThrownBy(() -> rs.getObject(2)).isInstanceOf(SQLException.class);
            }
          }
          // SELECT * returns the table's own columns, in its order
          try (ResultSet rs =
              d.createStatement().executeQuery("SELECT * FROM " + t.sql + " WHERE id = 1")) {
            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
            assertThat(rs.getMetaData().getColumnLabel(1)).isEqualTo(N("id"));
            assertThat(rs.getMetaData().getColumnLabel(2)).isEqualTo(N("x"));
          }
          d.setAutoCommit(false);
          // SELECT * and a row-wise function anchor too, each in a transaction of its own
          String[][] reads = {
            {"10", "SELECT * FROM %s WHERE id = 10", "10/a"},
            {"11", "SELECT upper(x) FROM %s WHERE id = 11", "A"}
          };
          for (String[] r : reads) {
            assertThat(rows(d, String.format(r[1], t.sql))).as(r[1]).containsExactly(r[2]);
            scalarDbSet(tm, t, Integer.parseInt(r[0]), "scalardb");
            assertThatThrownBy(
                    () ->
                        d.createStatement()
                            .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = " + r[0]))
                .as(r[1])
                .isInstanceOf(SQLTransactionRollbackException.class);
            d.rollback();
          }
          // read, then a ScalarDB commit of the row: the write aborts, no lost update
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 1")).containsExactly("a");
          scalarDbSet(tm, t, 1, "scalardb");
          assertThatThrownBy(
                  () ->
                      d.createStatement()
                          .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 1"))
              .isInstanceOf(SQLTransactionRollbackException.class);
          d.rollback();
          // read by predicate, then another driver transaction writes one of the rows
          assertThat(rows(d, "SELECT id FROM " + t.sql + " WHERE id IN (2, 3) ORDER BY id"))
              .containsExactly("2", "3");
          other.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'o' WHERE id = 3");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 2"))
              .isEqualTo(1);
          assertThatThrownBy(
                  () ->
                      d.createStatement()
                          .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 3"))
              .isInstanceOf(SQLTransactionRollbackException.class);
          d.rollback();
          // a key the transaction wrote is written again; its read after the write anchors nothing
          // new
          d.createStatement().executeUpdate("UPDATE " + t.sql + " SET x = 'w1' WHERE id = 4");
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 4")).containsExactly("w1");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'w2' WHERE id = 4"))
              .isEqualTo(1);
          d.commit();
          // a rolled-back transaction's reads anchor nothing in the next one
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 5")).containsExactly("a");
          d.rollback();
          scalarDbSet(tm, t, 5, "scalardb");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 5"))
              .isEqualTo(1);
          d.commit();
          // a row read in flight is anchored on its before image: a ScalarDB rollback keeps it
          // valid,
          // a commit does not
          for (boolean commit : new boolean[] {false, true}) {
            int id = commit ? 7 : 6;
            TwoPhaseCommitTransaction held = tpc.begin();
            held.update(
                Update.newBuilder()
                    .namespace(N("an"))
                    .table(t.scalarDbName)
                    .partitionKey(id(id))
                    .textValue(N("x"), "held")
                    .build());
            held.prepare();
            assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = " + id))
                .containsExactly("a");
            if (commit) {
              held.validate();
              held.commit();
            } else {
              held.rollback();
            }
            String update = "UPDATE " + t.sql + " SET x = 'd' WHERE id = " + id;
            if (commit) {
              assertThatThrownBy(() -> d.createStatement().executeUpdate(update))
                  .isInstanceOf(SQLTransactionRollbackException.class);
              d.rollback();
            } else {
              assertThat(d.createStatement().executeUpdate(update)).isEqualTo(1);
              d.commit();
            }
          }
          d.setAutoCommit(true);
          // in autocommit a read anchors nothing
          assertThat(rows(d, "SELECT x FROM " + t.sql + " WHERE id = 8")).containsExactly("a");
          scalarDbSet(tm, t, 8, "scalardb");
          assertThat(
                  d.createStatement()
                      .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = 8"))
              .isEqualTo(1);
        }
      }
    }
  }

  /**
   * A read of a join anchors each table it returns rows of, a LEFT JOIN's nullable side included
   * (design 4.2).
   */
  @ParameterizedTest
  @MethodSource("engines")
  void anchorsFromJoins(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("aj"),
          "CREATE TABLE aj.p (id " + e.intType() + " PRIMARY KEY, x " + e.textType() + ")",
          "INSERT INTO aj.p SELECT g, 'a' FROM " + e.series(1, 9));
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("aj"), true);
      admin.importTable(N("aj"), N("p"), DECOUPLED, Map.of());
      admin.createTable(
          N("aj"),
          N("c"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("pid"), DataType.INT)
              .addColumn(N("x"), DataType.TEXT)
              .addPartitionKey(N("id"))
              .build(),
          DECOUPLED);
    }
    // the ScalarDB tables dropped after: their names, p among them, would otherwise count as
    // ScalarDB's for the tests after this one, whose plain tables may be named so too
    try {
      try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
        DistributedTransaction tx = tm.begin();
        for (int i = 1; i <= 9; i++) {
          if (i != 4) { // parent 4 has no child
            tx.insert(
                Insert.newBuilder()
                    .namespace(N("aj"))
                    .table(N("c"))
                    .partitionKey(id(i))
                    .intValue(N("pid"), i)
                    .textValue(N("x"), "c")
                    .build());
          }
        }
        tx.commit();
      }
      Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
      try (Connection c = e.open()) {
        assertThat(
                Setup.run(
                    catalog,
                    c,
                    t -> t.namespace().equals(N("aj")),
                    Duration.ofMinutes(1),
                    List.of(),
                    true))
            .allMatch(Setup.Result::succeeded, "succeeded");
      }
      TableInfo pInfo = catalog.lookup(List.of(), N("aj"), N("p"));
      Table p =
          new Table(
              "aj.p",
              "aj." + Q(pInfo.metadataTable()),
              Objects.requireNonNull(pInfo.virtualTable()));
      Table ch =
          new Table(
              "aj.c",
              "aj." + Q(catalog.lookup(List.of(), N("aj"), N("c")).metadataTable()),
              N("c"));
      String join = "SELECT p.x, c.x FROM aj.p p JOIN aj.c c ON c.pid = p.id WHERE p.id = ";
      String leftJoin =
          "SELECT p.id, c.id FROM aj.p p LEFT JOIN aj.c c ON c.pid = p.id WHERE p.id = ";
      try (DistributedTransactionManager tm = scalarDb.getTransactionManager();
          TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
          Connection d = e.openDriver();
          Connection other = e.openDriver()) {
        // versions are read only where they are recorded: not in autocommit, nor read-only
        assertThat(d.nativeSQL(join + "1"))
            .doesNotContain("scalardb_native_meta")
            .doesNotContain("scalardb_native_v0");
        // prepared in autocommit, run in a transaction: prepared again, it anchors: on PostgreSQL
        // and Oracle by each copy's own version columns (todos 329 and 334)
        try (PreparedStatement ps = d.prepareStatement(join + "?")) {
          d.setAutoCommit(false);
          assertThat(d.nativeSQL(join + "1"))
              .contains(
                  e.engine() == Engine.POSTGRESQL || e.engine() == Engine.ORACLE
                      ? "scalardb_native_v0"
                      : "scalardb_native_meta");
          ps.setInt(1, 6);
          try (ResultSet rs = ps.executeQuery()) {
            assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
            assertThat(rs.next()).isTrue();
          }
          scalarDbSet(tm, ch, 6, "scalardb");
          assertAborts(d, "UPDATE aj.c SET x = 'd' WHERE id = 6");
          // tracked by the driver, SQL Server's ignoring it (todo 300)
          d.setReadOnly(true);
          assertThat(d.nativeSQL(join + "1")).doesNotContain("scalardb_native_meta");
          d.setReadOnly(false);
        }
        // one that cannot be prepared again, since the application unwrapped it, is refused rather
        // than read without anchors
        d.setAutoCommit(true);
        try (PreparedStatement ps = d.prepareStatement(join + "?")) {
          ps.unwrap(
              Class.forName(
                  switch (e.engine()) {
                    case POSTGRESQL -> "org.postgresql.PGStatement";
                    case MARIADB -> "org.mariadb.jdbc.Statement";
                    case SQLSERVER -> "com.microsoft.sqlserver.jdbc.ISQLServerStatement";
                    case ORACLE -> "oracle.jdbc.OracleStatement";
                    default -> "com.mysql.cj.jdbc.JdbcStatement";
                  })); // the vendor statement
          d.setAutoCommit(false);
          ps.setInt(1, 7);
          assertThatThrownBy(ps::executeQuery)
              .isInstanceOf(SQLException.class)
              .hasMessageContaining("prepare it again");
          d.rollback();
        }
        // the application's columns only
        try (ResultSet rs = d.createStatement().executeQuery(join + "1")) {
          assertThat(rs.getMetaData().getColumnCount()).isEqualTo(2);
          assertThat(rs.next()).isTrue();
          assertThatThrownBy(() -> rs.getObject(3)).isInstanceOf(SQLException.class);
        }
        d.rollback();
        // both tables of an inner join are anchored
        for (Table t : List.of(p, ch)) {
          int id = t == p ? 1 : 3;
          assertThat(rows(d, join + id)).containsExactly("a/c");
          scalarDbSet(tm, t, id, "scalardb");
          assertThatThrownBy(
                  () ->
                      d.createStatement()
                          .executeUpdate("UPDATE " + t.sql + " SET x = 'd' WHERE id = " + id))
              .as(t.sql)
              .isInstanceOf(SQLTransactionRollbackException.class);
          d.rollback();
        }
        // a LEFT JOIN: the preserved row of an unmatched parent, and the nullable side's rows
        assertThat(rows(d, leftJoin + "4")).containsExactly("4/null");
        scalarDbSet(tm, p, 4, "scalardb");
        assertAborts(d, "UPDATE aj.p SET x = 'd' WHERE id = 4");
        assertThat(rows(d, leftJoin + "2")).containsExactly("2/2");
        other.createStatement().executeUpdate("UPDATE aj.c SET x = 'o' WHERE id = 2");
        assertAborts(d, "UPDATE aj.c SET x = 'd' WHERE id = 2");
        // ORDER BY and LIMIT on top of the copies
        assertThat(
                rows(
                    d,
                    "SELECT c.id FROM aj.p p JOIN aj.c c ON c.pid = p.id WHERE p.id IN (3, 5)"
                        + (e.engine() == Engine.SQLSERVER
                            ? " ORDER BY p.id DESC OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY"
                            : " ORDER BY p.id DESC LIMIT 1")))
            .containsExactly("5");
        scalarDbSet(tm, ch, 5, "scalardb");
        assertAborts(d, "UPDATE aj.c SET x = 'd' WHERE id = 5");
        // a self-join anchors the rows of both references
        assertThat(rows(d, "SELECT b.id FROM aj.p a JOIN aj.p b ON b.id = a.id + 1 WHERE a.id = 6"))
            .containsExactly("7");
        scalarDbSet(tm, p, 7, "scalardb");
        assertAborts(d, "UPDATE aj.p SET x = 'd' WHERE id = 7");
        // an IN subquery over the key: the outer table is anchored, the subquery's returns no row
        assertThat(rows(d, "SELECT x FROM aj.p WHERE id IN (SELECT id FROM aj.c WHERE id = 2)"))
            .containsExactly("a");
        scalarDbSet(tm, p, 2, "scalardb");
        assertAborts(d, "UPDATE aj.p SET x = 'd' WHERE id = 2");
        // a nullable row read in flight is anchored on its before image: a ScalarDB rollback keeps
        // it valid, a commit does not
        for (boolean commit : new boolean[] {false, true}) {
          int id = commit ? 9 : 8;
          TwoPhaseCommitTransaction held = tpc.begin();
          held.update(
              Update.newBuilder()
                  .namespace(N("aj"))
                  .table(N("c"))
                  .partitionKey(id(id))
                  .textValue(N("x"), "held")
                  .build());
          held.prepare();
          assertThat(
                  rows(
                      d,
                      "SELECT c.x FROM aj.p p LEFT JOIN aj.c c ON c.pid = p.id WHERE p.id = " + id))
              .containsExactly("c");
          if (commit) {
            held.validate();
            held.commit();
          } else {
            held.rollback();
          }
          String update = "UPDATE aj.c SET x = 'd' WHERE id = " + id;
          if (commit) {
            assertAborts(d, update);
          } else {
            assertThat(d.createStatement().executeUpdate(update)).isEqualTo(1);
            d.commit();
          }
        }
      }
    } finally {
      // the driver's objects first, which ScalarDB's drop would otherwise stop at
      try (Connection c = e.open()) {
        Setup.teardown(
            new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
            c,
            t -> t.namespace().equals(N("aj")));
      }
      try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
        admin.dropTable(N("aj"), N("c"), true);
        admin.dropTable(N("aj"), N("p"), true);
      }
    }
  }

  /** The update aborts on an anchor, and the transaction is rolled back for the next case. */
  private static void assertAborts(Connection d, String update) throws SQLException {
    assertThatThrownBy(() -> d.createStatement().executeUpdate(update))
        .isInstanceOf(SQLTransactionRollbackException.class);
    d.rollback();
  }

  /** The findings of the 4b review (todos 141-145), each asserting the fixed behaviour. */
  @ParameterizedTest
  @MethodSource("engines")
  void anchorReviewFindings(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("af"),
          "CREATE TABLE af.imp (id " + e.intType() + " PRIMARY KEY, x " + e.textType() + ")",
          "INSERT INTO af.imp SELECT g, 'a' FROM " + e.series(1, 9));
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("af"), true);
      admin.importTable(N("af"), N("imp"), DECOUPLED, Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals(N("af")),
                  Duration.ofMinutes(1),
                  List.of(),
                  true))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    String sdb =
        Objects.requireNonNull(catalog.lookup(List.of(), N("af"), N("imp")).virtualTable());
    try (Connection d = e.openDriver();
        Connection other = e.openDriver()) {
      d.setAutoCommit(false);
      // 141: a result set iterated after its transaction ended anchors nothing in the next one
      ResultSet stale = d.createStatement().executeQuery("SELECT x FROM af.imp WHERE id = 1");
      d.commit();
      other.createStatement().executeUpdate("UPDATE af.imp SET x = 'o' WHERE id = 1");
      assertThat(stale.next()).isTrue();
      assertThat(rows(d, "SELECT x FROM af.imp WHERE id = 1")).containsExactly("o");
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'd' WHERE id = 1"))
          .isEqualTo(1);
      d.commit();
      // 142: a rollback to a savepoint keeps the transaction's own writes its own
      rows(d, "SELECT x FROM af.imp WHERE id = 2");
      d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w1' WHERE id = 2");
      d.rollback(d.setSavepoint());
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w2' WHERE id = 2"))
          .isEqualTo(1);
      if (e.engine() == Engine.SQLSERVER) {
        // T-SQL's transaction statements do not parse: refused, the JDBC methods carry them
        assertThatThrownBy(() -> d.createStatement().execute("SAVE TRANSACTION s"))
            .isInstanceOf(SQLFeatureNotSupportedException.class);
        Savepoint s = d.setSavepoint();
        d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w3' WHERE id = 2");
        d.rollback(s);
      } else {
        d.createStatement().execute("SAVEPOINT s");
        d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w3' WHERE id = 2");
        d.createStatement().execute("ROLLBACK TO SAVEPOINT s");
      }
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'w4' WHERE id = 2"))
          .isEqualTo(1);
      d.commit();
      // 143: an aggregate in ORDER BY only, which MySQL refuses itself
      if (e.engine() == Engine.POSTGRESQL) {
        assertThat(rows(d, "SELECT 1 FROM af.imp ORDER BY count(*)")).containsExactly("1");
      }
      // 144: hidden columns by label, generated keys, the lookups' alias, a position past the list,
      // an updatable result set
      try (Statement st = d.createStatement();
          ResultSet rs = st.executeQuery("SELECT x FROM af.imp WHERE id = 3")) {
        assertThat(rs.next()).isTrue();
        assertThatThrownBy(() -> rs.getString("scalardb_native_x0"))
            .isInstanceOf(SQLException.class);
        if (e.engine() == Engine.POSTGRESQL) { // Connector/J refuses it after a read
          assertThat(st.getGeneratedKeys().getMetaData().getColumnCount()).isZero();
        }
      }
      assertThat(rows(d, "SELECT x FROM af.imp scalardb_native_meta WHERE id = 3"))
          .containsExactly("a");
      assertThatThrownBy(() -> rows(d, "SELECT x FROM af.imp ORDER BY 2"))
          .isInstanceOf(SQLException.class);
      d.rollback();
      assertThatThrownBy(
              () ->
                  d.createStatement(
                          // SQL Server updates through a forward-only or sensitive cursor only
                          e.engine() == Engine.SQLSERVER
                              ? ResultSet.TYPE_FORWARD_ONLY
                              : ResultSet.TYPE_SCROLL_INSENSITIVE,
                          ResultSet.CONCUR_UPDATABLE)
                      .executeQuery("SELECT id, x FROM af.imp WHERE id = 3"))
          .isInstanceOf(SQLException.class)
          .extracting("SQLState")
          .isEqualTo("0AN01");
      d.rollback();
    }
    // 145: a row without metadata read in flight, then rolled back by ScalarDB, is unchanged
    try (Connection v = e.open()) {
      e.execute(v, "INSERT INTO af.imp VALUES (20, 'n')");
    }
    try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager();
        Connection d = e.openDriver()) {
      TwoPhaseCommitTransaction held = tpc.begin();
      held.get(Get.newBuilder().namespace(N("af")).table(sdb).partitionKey(id(20)).build());
      held.update(
          Update.newBuilder()
              .namespace(N("af"))
              .table(sdb)
              .partitionKey(id(20))
              .textValue(N("x"), "held")
              .build());
      held.prepare();
      d.setAutoCommit(false);
      assertThat(rows(d, "SELECT x FROM af.imp WHERE id = 20")).containsExactly("n");
      held.rollback();
      assertThat(d.createStatement().executeUpdate("UPDATE af.imp SET x = 'd' WHERE id = 20"))
          .isEqualTo(1);
      d.commit();
    }
  }

  private static void scalarDbSet(DistributedTransactionManager tm, Table t, int id, String x)
      throws TransactionException {
    String namespace = t.sql.substring(0, t.sql.indexOf('.'));
    DistributedTransaction tx = tm.begin();
    tx.get(
        Get.newBuilder()
            .namespace(N(namespace))
            .table(t.scalarDbName)
            .partitionKey(id(id))
            .build());
    tx.update(
        Update.newBuilder()
            .namespace(N(namespace))
            .table(t.scalarDbName)
            .partitionKey(id(id))
            .textValue(N("x"), x)
            .build());
    tx.commit();
  }

  private static Optional<String> scalarDbX(DistributedTransactionManager tm, Table t, int id)
      throws TransactionException {
    DistributedTransaction tx = tm.begin();
    Optional<Result> r =
        tx.get(
            Get.newBuilder().namespace(N("wr")).table(t.scalarDbName).partitionKey(id(id)).build());
    tx.commit();
    return r.map(x -> x.getText(N("x")));
  }

  /**
   * A transaction reads its own writes before its commit, at each level and on both layouts (design
   * 4.2): a write is a statement of the native transaction, stamped committed, so every later read
   * of the transaction returns it, by the key, by a predicate, under a lock, through a join, and
   * after a batch that writes one key twice; a row it deleted is gone; and another connection sees
   * none of it until the commit.
   */
  @ParameterizedTest
  @MethodSource("engines")
  void readsItsOwnWrites_withinTheTransaction(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema("ryw"),
          "CREATE TABLE ryw.imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "CREATE TABLE ryw.impi (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))");
    }
    TransactionFactory scalarDb = e.scalarDb();
    try (DistributedTransactionAdmin admin = scalarDb.getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("ryw"), true);
      admin.importTable(N("ryw"), N("imp"), DECOUPLED, Map.of());
      admin.importTable(N("ryw"), N("impi"), Map.of(), Map.of());
    }
    Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  catalog,
                  c,
                  t -> t.namespace().equals(N("ryw")),
                  Duration.ofMinutes(1),
                  List.of(),
                  true))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    // every level the driver runs on the engine (README, "Isolation levels"): Oracle refuses
    // SERIALIZABLE; SQL Server's test database allows SNAPSHOT, which its higher levels run at
    List<Integer> levels =
        e.engine() == Engine.ORACLE
            ? List.of(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ)
            : List.of(
                Connection.TRANSACTION_READ_COMMITTED,
                Connection.TRANSACTION_REPEATABLE_READ,
                Connection.TRANSACTION_SERIALIZABLE);
    try (Connection d = e.openDriver();
        Connection other = e.openDriver()) {
      for (String table : List.of("ryw.imp", "ryw.impi")) {
        for (int level : levels) {
          readsItsOwnWrites(d, other, table, level);
        }
      }
    }
  }

  private static void readsItsOwnWrites(Connection d, Connection other, String t, int level)
      throws SQLException {
    // the rows as the transaction starts: 1 a, 2 b, 3 c
    d.setAutoCommit(true);
    d.createStatement().executeUpdate("DELETE FROM " + t + " WHERE id IN (1, 2, 3, 4)");
    // a row a statement, as Oracle before 23 has no multi-row VALUES
    for (String row : List.of("(1, 'a')", "(2, 'b')", "(3, 'c')")) {
      d.createStatement().executeUpdate("INSERT INTO " + t + " (id, x) VALUES " + row);
    }
    d.setTransactionIsolation(level);
    d.setAutoCommit(false);
    try (Statement st = d.createStatement()) {
      assertThat(st.executeUpdate("UPDATE " + t + " SET x = 'a1' WHERE id = 1")).isEqualTo(1);
      assertThat(st.executeUpdate("INSERT INTO " + t + " (id, x) VALUES (4, 'd')")).isEqualTo(1);
      // by the key, by a predicate, under a lock, through a join
      assertThat(rows(d, "SELECT x FROM " + t + " WHERE id = 1")).containsExactly("a1");
      assertThat(rows(d, "SELECT x FROM " + t + " WHERE id = 4")).containsExactly("d");
      assertThat(rows(d, "SELECT id FROM " + t + " WHERE x IN ('a1', 'd') ORDER BY id"))
          .containsExactly("1", "4");
      assertThat(rows(d, "SELECT x FROM " + t + " WHERE id = 1 FOR UPDATE")).containsExactly("a1");
      assertThat(
              rows(
                  d,
                  "SELECT a.x, b.x FROM " + t + " a JOIN " + t + " b ON b.id = 4 WHERE a.id = 1"))
          .containsExactly("a1/d");
      // a write after the transaction's own write and read of the row: accepted, and read back
      assertThat(st.executeUpdate("UPDATE " + t + " SET x = 'a2' WHERE id = 1")).isEqualTo(1);
      assertThat(rows(d, "SELECT x FROM " + t + " WHERE id = 1")).containsExactly("a2");
      // a batch writing one key twice: the second entry sees the first's write
      try (PreparedStatement ps = d.prepareStatement("UPDATE " + t + " SET x = ? WHERE id = ?")) {
        for (String x : List.of("b1", "b2")) {
          ps.setString(1, x);
          ps.setInt(2, 2);
          ps.addBatch();
        }
        assertThat(ps.executeBatch()).containsExactly(1, 1);
      }
      assertThat(rows(d, "SELECT x FROM " + t + " WHERE id = 2")).containsExactly("b2");
      // a row the transaction deleted is gone from its reads
      assertThat(st.executeUpdate("DELETE FROM " + t + " WHERE id = 3")).isEqualTo(1);
      assertThat(rows(d, "SELECT x FROM " + t + " WHERE id = 3")).isEmpty();
      assertThat(rows(d, "SELECT id FROM " + t + " WHERE id <= 4 ORDER BY id"))
          .containsExactly("1", "2", "4");
      // nothing of it is visible elsewhere before the commit
      assertThat(rows(other, "SELECT x FROM " + t + " WHERE id = 1")).containsExactly("a");
      assertThat(rows(other, "SELECT x FROM " + t + " WHERE id = 4")).isEmpty();
    }
    d.commit();
    assertThat(rows(other, "SELECT id, x FROM " + t + " WHERE id <= 4 ORDER BY id"))
        .containsExactly("1/a2", "2/b2", "4/d");
    d.setAutoCommit(true);
  }
}
