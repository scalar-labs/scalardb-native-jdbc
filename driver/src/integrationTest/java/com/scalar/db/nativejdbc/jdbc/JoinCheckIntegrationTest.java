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
import com.scalar.db.api.Operation;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLTransactionRollbackException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * With {@code scalardbnative.predicateChecks}, a join or a semi-join of ScalarDB tables that takes
 * the per-table check at SNAPSHOT, and at SERIALIZABLE on PostgreSQL, aborts on a row in flight
 * only where a combination of the tables' pieces with that row, by its before image or its after
 * image, gives the statement a row (design 4.2, the in-flight pairs), on every engine but MySQL and
 * SQL Server: a row in flight on either side, on both, moved out of the predicate, inserted,
 * deleted; a placeholder's value; comma and CROSS joins, a star. An outer join, an anti-join,
 * USING, an IN under an OR and a WITH clause keep the table-wide check, and so do MySQL and SQL
 * Server. Orders 1 to 10 are in region 'a', 11 to 20 in 'b', order i of customer i, amount i;
 * customer i of tier i % 2.
 */
class JoinCheckIntegrationTest {

  private static final String O = "jc.o";
  private static final String C = "jc.c";

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void joinChecks(Engines e) throws Exception {
    ScalarDbNames.on(e);
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(N("jc"), true);
      admin.createTable(
          N("jc"),
          N("o"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("cid"), intType())
              .addColumn(N("region"), DataType.TEXT)
              .addColumn(N("amount"), intType())
              .addPartitionKey(N("id"))
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
      admin.createTable(
          N("jc"),
          N("c"),
          TableMetadata.newBuilder()
              .addColumn(N("id"), intType())
              .addColumn(N("tier"), intType())
              .addPartitionKey(N("id"))
              .build(),
          Map.of("transaction-metadata-decoupling", "true"));
    }
    TransactionFactory factory = e.scalarDb();
    try (DistributedTransactionManager tm = factory.getTransactionManager()) {
      DistributedTransaction tx = tm.begin();
      for (int i = 1; i <= 20; i++) {
        tx.insert(order(i, i, i <= 10 ? "a" : "b"));
        tx.insert(customer(i, i % 2));
      }
      tx.commit();
    }
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals(N("jc")),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "succeeded");
    }
    boolean pairs = e.engine() != Engine.MYSQL && e.engine() != Engine.SQLSERVER;
    try (TwoPhaseCommitTransactionManager tpc = factory.getTwoPhaseCommitTransactionManager()) {
      if (pairs) {
        scenarios(e, tpc, Connection.TRANSACTION_REPEATABLE_READ, "SNAPSHOT");
        if (e.engine() == Engine.POSTGRESQL) {
          scenarios(e, tpc, Connection.TRANSACTION_SERIALIZABLE, "SERIALIZABLE");
        }
      } else {
        // MySQL and SQL Server keep the table-wide check for a join: a customer elsewhere aborts it
        TwoPhaseCommitTransaction held = prepared(tpc, customerUpdate(15, 0));
        try (Connection d = driver(e, true)) {
          d.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
          d.setAutoCommit(false);
          assertAborts(d, joined("a"), e.engine() + ": the table-wide check");
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

      // a customer in flight whose only order is in region 'b'
      TwoPhaseCommitTransaction held = prepared(tpc, customerUpdate(15, 0));
      assertAborts(off, joined("a"), at + ": without the setting, the table-wide check aborts");
      off.rollback();
      assertThat(rows(d, joined("a")))
          .as("%s: c 15, region 'a' clear", at)
          .containsExactly("a/10/55");
      assertAborts(d, joined("b"), at + ": c 15 joins region 'b'");
      d.rollback();
      // the same customer through a semi-join
      assertThat(rows(d, semi("a"))).as("%s: c 15, EXISTS 'a' clear", at).containsExactly("5");
      assertAborts(d, semi("b"), at + ": c 15, EXISTS 'b'");
      d.rollback();
      // three references, the orders twice: the pieces of each
      assertThat(
              rows(
                  d,
                  "SELECT COUNT(*) FROM "
                      + O
                      + " o JOIN "
                      + C
                      + " c ON c.id = o.cid JOIN "
                      + O
                      + " o2 ON o2.cid = c.id WHERE o.region = 'a' AND o2.amount > 0"))
          .as("%s: three references, 'a' clear", at)
          .containsExactly("10");
      assertAborts(
          d,
          "SELECT COUNT(*) FROM "
              + O
              + " o JOIN "
              + C
              + " c ON c.id = o.cid JOIN "
              + O
              + " o2 ON o2.cid = c.id WHERE o.region = 'b'",
          at + ": three references, 'b'");
      d.rollback();
      // an IN subquery, and the tables named without aliases
      assertThat(
              rows(
                  d,
                  "SELECT COUNT(*) FROM "
                      + O
                      + " o WHERE o.region = 'a' AND o.cid IN (SELECT c.id FROM "
                      + C
                      + " c WHERE c.tier = 1)"))
          .as("%s: IN, 'a' clear", at)
          .containsExactly("5");
      assertThat(
              rows(
                  d,
                  "SELECT COUNT(*) FROM "
                      + O
                      + " JOIN "
                      + C
                      + " ON c.id = o.cid WHERE o.region = 'a'"))
          .as("%s: no aliases, 'a' clear", at)
          .containsExactly("10");
      assertAborts(
          d,
          "SELECT COUNT(*) FROM " + O + " JOIN " + C + " ON c.id = o.cid WHERE o.region = 'b'",
          at + ": no aliases, 'b'");
      d.rollback();
      // a comma join, a CROSS JOIN and a star: the pairs check too
      assertThat(
              rows(
                  d,
                  "SELECT COUNT(*) FROM "
                      + O
                      + " o, "
                      + C
                      + " c WHERE c.id = o.cid AND o.region = 'a'"))
          .as("%s: a comma join, 'a' clear", at)
          .containsExactly("10");
      assertThat(
              rows(
                  d,
                  "SELECT COUNT(*) FROM "
                      + O
                      + " o CROSS JOIN "
                      + C
                      + " c WHERE c.id = o.cid AND o.region = 'a'"))
          .as("%s: a CROSS JOIN, 'a' clear", at)
          .containsExactly("10");
      assertThat(
              rows(
                  d,
                  "SELECT * FROM "
                      + O
                      + " o JOIN "
                      + C
                      + " c ON c.id = o.cid WHERE o.region = 'a'"))
          .as("%s: a star, 'a' clear", at)
          .hasSize(10);
      assertThat(
              rows(
                  d,
                  "SELECT o.*, c.tier FROM "
                      + O
                      + " o JOIN "
                      + C
                      + " c ON c.id = o.cid WHERE o.region = 'a'"))
          .as("%s: a table's star, 'a' clear", at)
          .hasSize(10);
      assertAborts(
          d,
          "SELECT * FROM " + O + " o JOIN " + C + " c ON c.id = o.cid WHERE o.region = 'b'",
          at + ": a star, 'b'");
      d.rollback();
      // USING, an IN under an OR and a WITH clause keep the table-wide check
      assertAborts(
          d,
          "SELECT COUNT(*) FROM " + O + " o JOIN " + C + " c USING (id) WHERE o.region = 'a'",
          at + ": USING");
      d.rollback();
      assertAborts(
          d,
          "SELECT COUNT(*) FROM "
              + O
              + " o WHERE o.region = 'a' AND (o.amount < 0 OR o.cid IN (SELECT c.id FROM "
              + C
              + " c WHERE c.tier = 1))",
          at + ": an IN under an OR");
      d.rollback();
      assertAborts(
          d,
          "WITH k AS (SELECT 1 AS one) SELECT COUNT(*) FROM "
              + O
              + " o JOIN "
              + C
              + " c ON c.id = o.cid WHERE o.region = 'a'",
          at + ": a WITH clause");
      d.rollback();
      // an outer join and an anti-join keep the table-wide check
      assertAborts(
          d,
          e.sql(
              "SELECT COUNT(*) FROM "
                  + O
                  + " o LEFT JOIN "
                  + C
                  + " c ON c.id = o.cid WHERE o.region = 'a'"),
          at + ": a LEFT JOIN");
      d.rollback();
      assertAborts(
          d,
          e.sql(
              "SELECT COUNT(*) FROM "
                  + O
                  + " o WHERE o.region = 'a' AND NOT EXISTS (SELECT 1 FROM "
                  + C
                  + " c WHERE c.id = o.cid AND c.tier = 0)"),
          at + ": NOT EXISTS");
      d.rollback();
      // a placeholder's value decides it
      try (PreparedStatement ps =
          d.prepareStatement(
              "SELECT o.region, COUNT(*), SUM(o.amount) FROM "
                  + O
                  + " o JOIN "
                  + C
                  + " c ON c.id = o.cid WHERE o.region = ? GROUP BY o.region")) {
        ps.setString(1, "a");
        assertThat(rows(ps)).as("%s: ? = 'a'", at).containsExactly("a/10/55");
        ps.setString(1, "b");
        assertThatThrownBy(() -> rows(ps))
            .as("%s: ? = 'b'", at)
            .isInstanceOf(SQLTransactionRollbackException.class);
      }
      d.rollback();
      // an order in flight too, in region 'b': region 'a' still clear with both tables in flight
      TwoPhaseCommitTransaction order = prepared(tpc, orderUpdate(16, "b"));
      assertThat(rows(d, joined("a"))).as("%s: both in flight", at).containsExactly("a/10/55");
      d.commit();
      order.rollback();
      held.rollback();

      // an order in flight moved out of region 'a': its before image matches 'a', its after 'c'
      held = prepared(tpc, orderUpdate(3, "c"));
      assertAborts(d, joined("a"), at + ": the order's before image");
      d.rollback();
      assertAborts(d, joined("c"), at + ": the order's after image");
      d.rollback();
      assertThat(rows(d, joined("b"))).as("%s: region 'b' clear", at).containsExactly("b/10/155");
      d.commit();
      held.rollback();

      // a customer inserted in flight that no order refers to; one an order in flight refers to
      held = prepared(tpc, customer(50, 1));
      assertThat(rows(d, joined("a"))).as("%s: beside an insert", at).containsExactly("a/10/55");
      d.commit();
      TwoPhaseCommitTransaction placed = prepared(tpc, order(60, 50, "n"));
      assertAborts(d, joined("n"), at + ": an inserted order and customer");
      d.rollback();
      placed.rollback();
      held.rollback();

      // a customer deleted in flight: its before image joins order 12
      held = prepared(tpc, customerDelete(12));
      assertAborts(d, joined("b"), at + ": a deleted customer");
      d.rollback();
      assertThat(rows(d, joined("a"))).as("%s: beside a delete", at).containsExactly("a/10/55");
      d.commit();
      held.rollback();
    }
  }

  private static String joined(String region) {
    return "SELECT o.region, COUNT(*), SUM(o.amount) FROM "
        + O
        + " o JOIN "
        + C
        + " c ON c.id = o.cid WHERE o.region = '"
        + region
        + "' GROUP BY o.region";
  }

  private static String semi(String region) {
    return "SELECT COUNT(*) FROM "
        + O
        + " o WHERE o.region = '"
        + region
        + "' AND EXISTS (SELECT 1 FROM "
        + C
        + " c WHERE c.id = o.cid AND c.tier = 1)";
  }

  private static Connection driver(Engines e, boolean predicateChecks) throws Exception {
    Properties p = new Properties();
    p.setProperty("scalardbnative.predicateChecks", Boolean.toString(predicateChecks));
    return e.openDriver(p);
  }

  private static void assertAborts(Connection d, String sql, String what) {
    assertThatThrownBy(() -> rows(d, sql))
        .as(what)
        .isInstanceOf(SQLTransactionRollbackException.class);
  }

  private static TwoPhaseCommitTransaction prepared(
      TwoPhaseCommitTransactionManager tpc, Operation operation) throws Exception {
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

  private static Insert order(int id, int cid, String region) {
    return Insert.newBuilder()
        .namespace(N("jc"))
        .table(N("o"))
        .partitionKey(id(id))
        .value(intColumn("cid", cid))
        .textValue(N("region"), region)
        .value(intColumn("amount", id))
        .build();
  }

  private static Insert customer(int id, int tier) {
    return Insert.newBuilder()
        .namespace(N("jc"))
        .table(N("c"))
        .partitionKey(id(id))
        .value(intColumn("tier", tier))
        .build();
  }

  private static Update orderUpdate(int id, String region) {
    return Update.newBuilder()
        .namespace(N("jc"))
        .table(N("o"))
        .partitionKey(id(id))
        .textValue(N("region"), region)
        .build();
  }

  private static Update customerUpdate(int id, int tier) {
    return Update.newBuilder()
        .namespace(N("jc"))
        .table(N("c"))
        .partitionKey(id(id))
        .value(intColumn("tier", tier))
        .build();
  }

  private static Delete customerDelete(int id) {
    return Delete.newBuilder().namespace(N("jc")).table(N("c")).partitionKey(id(id)).build();
  }
}
