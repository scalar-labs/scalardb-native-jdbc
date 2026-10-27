package com.scalar.db.nativejdbc.jdbc;

import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.N;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.id;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.intColumn;
import static com.scalar.db.nativejdbc.jdbc.ScalarDbNames.intType;
import static com.scalar.db.nativejdbc.testing.Rows.rows;
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
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLTransactionRollbackException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * With {@code scalar.db.native_jdbc.predicate_checks}, a read of one ScalarDB table that takes the
 * per-table check at SNAPSHOT, and at SERIALIZABLE on PostgreSQL, aborts on a row in flight only
 * where its predicate matches the row's before image or its after image (design 4.2): a prepared
 * update, one that moves a row out of the predicate, an insert, a delete, a NULL; a placeholder's
 * value; a predicate it cannot evaluate alike in two statements, which aborts as before; and the
 * table's check not recorded as passed. Rows 1 to 10 hold x = 'a', 11 to 20 x = 'b', v = id.
 */
class PredicateCheckIntegrationTest {

  private static final String T = "pc.t";

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void predicateChecks(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("pc"), true);
      admin.createTable(
          N("pc"),
          N("t"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("x"), DataType.TEXT)
              .addColumn(N("v"), intType())
              .addPartitionKey(N("id"))
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
    }
    TransactionFactory factory = e.scalarDb();
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 20; i++) {
        tx.insert(insert(i, i <= 10 ? "a" : "b"));
      }
      tx.commit();
    }
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals(N("pc")),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    boolean pg = e.engine() == Engine.POSTGRESQL;
    try (TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      scenarios(e, tpc, Connection.TRANSACTION_REPEATABLE_READ, "SNAPSHOT");
      if (pg) {
        scenarios(e, tpc, Connection.TRANSACTION_SERIALIZABLE, "SERIALIZABLE");
      } else if (e.engine() != Engine.ORACLE) {
        // the coarse path's locking check holds off every prepare: no predicate there
        TwoPhaseCommitTransaction held = prepared(tpc, update(15, "b"));
        try (Connection d = driver(e, true)) {
          d.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
          d.setAutoCommit(false);
          assertAborts(d, grouped("a"), "SERIALIZABLE, coarse: a row in flight elsewhere");
          d.rollback();
        }
        held.rollback();
      }
    }
  }

  private static void scenarios(
      Engines e, TwoPhaseCommitTransactionManager tpc, int level, String at) throws Exception {
    try (Connection d = driver(e, true);
        Connection off = driver(e, false)) {
      for (Connection c : List.of(d, off)) {
        c.setTransactionIsolation(level);
        c.setAutoCommit(false);
      }

      // an update in flight of a row the predicate does not match by either image
      TwoPhaseCommitTransaction held = prepared(tpc, update(15, "b"));
      assertAborts(off, grouped("a"), at + ": without the setting, the table's check aborts");
      off.rollback();
      assertThat(rows(d, grouped("a"))).as("%s: x = 'a' clear", at).containsExactly("a/10/55");
      assertAborts(d, grouped("b"), at + ": x = 'b' matches it");
      d.rollback();
      // the table's check was not recorded as passed: a later read of the row still aborts
      assertThat(rows(d, grouped("a"))).as("%s: clear again", at).containsExactly("a/10/55");
      assertAborts(d, "SELECT COUNT(*) FROM " + T + " WHERE v > 12", at + ": then v > 12");
      d.rollback();
      // other shapes of the per-table check: DISTINCT, an OFFSET
      assertThat(
              rows(
                  d,
                  e.sql(
                      "SELECT DISTINCT v FROM "
                          + T
                          + " WHERE x = 'a' AND v > 0 ORDER BY v LIMIT 2 OFFSET 7")))
          .as("%s: DISTINCT and OFFSET", at)
          .containsExactly("8", "9");
      // a placeholder's value decides it
      try (PreparedStatement ps =
          d.prepareStatement("SELECT x, COUNT(*), SUM(v) FROM " + T + " WHERE x = ? GROUP BY x")) {
        ps.setString(1, "a");
        assertThat(rows(ps)).as("%s: ? = 'a'", at).containsExactly("a/10/55");
        ps.setString(1, "b");
        assertThatThrownBy(() -> rows(ps))
            .as("%s: ? = 'b'", at)
            .isInstanceOf(SQLTransactionRollbackException.class);
      }
      d.rollback();
      // a LOB, which may be read once, is the read's own: the table's check aborts, as before (a
      // stream the split read refuses when it is bound)
      if (e.engine() != Engine.POSTGRESQL) { // pgjdbc has no createClob
        try (PreparedStatement ps =
            d.prepareStatement("SELECT COUNT(*) FROM " + T + " WHERE x = ?")) {
          Clob clob = d.createClob();
          clob.setString(1, "a");
          ps.setClob(1, clob);
          assertThatThrownBy(() -> rows(ps))
              .as("%s: a CLOB", at)
              .isInstanceOf(SQLTransactionRollbackException.class);
        }
        d.rollback();
      }
      // a predicate two statements may evaluate apart aborts on the table's check, as before
      assertAborts(
          d, "SELECT COUNT(*) FROM " + T + " WHERE UPPER(x) = 'A'", at + ": a function call");
      d.rollback();
      d.commit();
      // in autocommit too
      d.setAutoCommit(true);
      assertThat(rows(d, grouped("a"))).as("%s: autocommit", at).containsExactly("a/10/55");
      d.setAutoCommit(false);
      held.rollback();

      // an update in flight moving row 3 out of x = 'a': its before image matches
      held = prepared(tpc, update(3, "c"));
      assertAborts(d, grouped("a"), at + ": the before image matches");
      d.rollback();
      assertAborts(d, grouped("c"), at + ": the after image matches");
      d.rollback();
      assertThat(rows(d, grouped("b"))).as("%s: x = 'b' clear", at).containsExactly("b/10/155");
      d.commit();
      held.rollback();

      // an insert in flight, no before image
      held = prepared(tpc, insert(50, "n"));
      assertAborts(d, grouped("n"), at + ": a prepared insert");
      d.rollback();
      assertThat(rows(d, grouped("a"))).as("%s: beside an insert", at).containsExactly("a/10/55");
      d.commit();
      held.rollback();

      // a delete in flight
      held = prepared(tpc, delete(12));
      assertAborts(d, grouped("b"), at + ": a prepared delete");
      d.rollback();
      assertThat(rows(d, grouped("a"))).as("%s: beside a delete", at).containsExactly("a/10/55");
      d.commit();
      held.rollback();

      // an update in flight to NULL: IS NULL matches the after image; <> 'a' neither image
      held = prepared(tpc, update(5, null));
      assertAborts(
          d, "SELECT COUNT(*) FROM " + T + " WHERE x IS NULL", at + ": IS NULL, the after image");
      d.rollback();
      assertThat(rows(d, "SELECT COUNT(*) FROM " + T + " WHERE x <> 'a'"))
          .as("%s: <> 'a', UNKNOWN on NULL", at)
          .containsExactly("10");
      d.commit();
      held.rollback();
    }
  }

  private static Connection driver(Engines e, boolean predicateChecks) throws Exception {
    Properties p = new Properties();
    p.setProperty("scalar.db.native_jdbc.predicate_checks", Boolean.toString(predicateChecks));
    return e.openDriver(p);
  }

  private static String grouped(String x) {
    return "SELECT x, COUNT(*), SUM(v) FROM " + T + " WHERE x = '" + x + "' GROUP BY x";
  }

  private static void assertAborts(Connection d, String sql, String what) {
    assertThatThrownBy(() -> rows(d, sql))
        .as(what)
        .isInstanceOf(SQLTransactionRollbackException.class);
  }

  private static TwoPhaseCommitTransaction prepared(
      TwoPhaseCommitTransactionManager tpc, Object operation) throws Exception {
    TwoPhaseCommitTransaction tx = tpc.begin();
    if (operation instanceof Insert i) {
      tx.insert(i);
    } else if (operation instanceof Update u) {
      tx.update(u);
    } else {
      tx.delete((Delete) operation);
    }
    tx.prepare();
    return tx;
  }

  private static Insert insert(int id, String x) {
    return Insert.newBuilder()
        .namespace(N("pc"))
        .table(N("t"))
        .partitionKey(id(id))
        .textValue(N("x"), x)
        .value(intColumn("v", id))
        .build();
  }

  private static Update update(int id, @Nullable String x) {
    return Update.newBuilder()
        .namespace(N("pc"))
        .table(N("t"))
        .partitionKey(id(id))
        .textValue(N("x"), x)
        .build();
  }

  private static Delete delete(int id) {
    return Delete.newBuilder().namespace(N("pc")).table(N("t")).partitionKey(id(id)).build();
  }
}
