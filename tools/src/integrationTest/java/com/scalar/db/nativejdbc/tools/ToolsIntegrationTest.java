package com.scalar.db.nativejdbc.tools;

import static com.scalar.db.nativejdbc.testing.Rows.rows;
import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.api.Update;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.testing.Engines;
import com.scalar.db.service.TransactionFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The tool on every engine, with the drivers its zip bundles: MySQL through MariaDB Connector/J.
 */
class ToolsIntegrationTest {

  static List<Engines> engines() {
    return Engines.selected();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void setsUpChecksAndTakesDown(Engines e) throws Exception {
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace("tl", true);
      for (String table : List.of("t", "u")) {
        admin.createTable(
            "tl",
            table,
            TableMetadata.newBuilder()
                .addColumn("id", DataType.INT)
                .addColumn("v", DataType.INT)
                .addPartitionKey("id")
                .build(),
            true,
            Map.of("transaction-metadata-decoupling", "true"));
      }
      // and one in the inline layout, its metadata in the row
      admin.createTable(
          "tl",
          "i",
          TableMetadata.newBuilder()
              .addColumn("id", DataType.INT)
              .addColumn("v", DataType.INT)
              .addPartitionKey("id")
              .build(),
          true);
    }
    String[] both = {"--table", "tl.t", "--table", "tl.u", "--table", "tl.i"};

    Run check = tool(e, "check", both);
    assertThat(check.exit).as(check.all()).isEqualTo(Main.EXIT_FOUND);
    assertThat(check.out).contains("tl.t: not set up", "tl.u: not set up", "tl.i: not set up");

    // a dry run prints the statements and changes nothing
    Run plan = tool(e, "setup", "--dry-run", "--table", "tl.t");
    assertThat(plan.exit).as(plan.all()).isZero();
    assertThat(plan.out).contains("-- tl.t").containsIgnoringCase("CREATE");
    assertThat(tool(e, "check", both).out).contains("tl.t: not set up");

    // new tables: the setup waits out the applications' catalog TTL, here 1 second (todo 298)
    Run setup = tool(e, "setup", concat(both, "--catalog-ttl", "1"));
    assertThat(setup.exit).as(setup.all()).isZero();
    assertThat(setup.out)
        .contains("tl.t: ", "created read view")
        .contains("New ScalarDB tables: ")
        .contains("Waiting 1 seconds for running applications' drivers to learn about them")
        .containsPattern(
            "Done: ScalarDB transactions may write tl\\.\\w, tl\\.\\w, tl\\.\\w now\\.");
    // run again, nothing is new: no wait
    Run again = tool(e, "setup", both);
    assertThat(again.exit).as(again.all()).isZero();
    assertThat(again.out).contains("replaced read view").doesNotContain("New ScalarDB");
    check = tool(e, "check", both);
    assertThat(check.exit).as(check.all()).isZero();
    assertThat(check.out).contains("tl.t: set up, no rows stuck", "tl.i: set up, no rows stuck");

    // a row ScalarDB left prepared counts as stuck once it is older than the lifetime
    try (TwoPhaseCommitTransactionManager tpc =
        e.scalarDb().getTwoPhaseCommitTransactionManager()) {
      TwoPhaseCommitTransaction held = tpc.begin();
      try {
        held.insert(
            Insert.newBuilder()
                .namespace("tl")
                .table("u")
                .partitionKey(Key.ofInt("id", 1))
                .intValue("v", 1)
                .build());
        held.prepare();
        Thread.sleep(1100);
        check = tool(e, "check", "--lifetime", "1", "--json", "--table", "tl.u");
        assertThat(check.exit).as(check.all()).isEqualTo(Main.EXIT_FOUND);
        assertThat(check.out)
            .containsPattern(
                "\"table\":\"tl.u\",\"setUp\":true,\"reason\":null,\"stuck\":1,\"oldestSeconds\":[1-9]");
        // not past the default lifetime yet
        assertThat(tool(e, "check", "--table", "tl.u").exit).isZero();
      } finally {
        held.rollback();
      }
    }

    // an unknown table is an error
    Run unknown = tool(e, "check", "--table", "tl.nope");
    assertThat(unknown.exit).as(unknown.all()).isEqualTo(Main.EXIT_ERROR);
    assertThat(unknown.err).contains("no ScalarDB table tl.nope");

    Run down = tool(e, "teardown", both);
    assertThat(down.exit).as(down.all()).isZero();
    assertThat(down.out).contains("dropped read view");
    assertThat(tool(e, "check", both).out)
        .contains("tl.t: not set up", "tl.u: not set up", "tl.i: not set up");

    // --no-wait says from when; --json carries it for a script
    Run noWait = tool(e, "setup", "--table", "tl.t", "--no-wait");
    assertThat(noWait.exit).as(noWait.all()).isZero();
    assertThat(noWait.out)
        .contains("New ScalarDB table: tl.t.")
        .containsPattern(
            "ScalarDB transactions may write tl\\.t from \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d \\S+,"
                + " or after every application using the driver on this database has restarted\\.")
        .doesNotContain("Waiting");
    assertThat(tool(e, "teardown", "--table", "tl.t").exit).isZero();
    Run json = tool(e, "setup", "--table", "tl.t", "--json");
    assertThat(json.exit).as(json.all()).isZero();
    assertThat(json.out)
        .containsPattern("\"newTables\":\\[\"tl\\.t\"\\],\"writableAt\":\"\\d{4}-[^\"]+Z\"")
        .doesNotContain("Waiting");
    assertThat(tool(e, "teardown", "--table", "tl.t").exit).isZero();

    // a run that fails still waits for the table it set up first: a rerun no longer sees it new
    Run partly = tool(e, "setup", "--table", "tl.t", "--table", "tl.nope", "--catalog-ttl", "1");
    assertThat(partly.exit).as(partly.all()).isEqualTo(Main.EXIT_ERROR);
    assertThat(partly.out)
        .contains("New ScalarDB table: tl.t.", "Done: ScalarDB transactions may write tl.t now.");
    assertThat(tool(e, "teardown", "--table", "tl.t").exit).isZero();

    // a TTL the driver would refuse
    Run badTtl = tool(e, "setup", "--table", "tl.t", "--catalog-ttl", "-1");
    assertThat(badTtl.exit).as(badTtl.all()).isEqualTo(Main.EXIT_ERROR);
    assertThat(badTtl.err).contains("--catalog-ttl is between 0 and 3600");
    assertThat(tool(e, "check", "--table", "tl.t").out).contains("tl.t: not set up");
  }

  /**
   * The backfill as an option, off by default (design section 3, todo 361): {@code setup} without
   * {@code --backfill} on an imported decoupled table, then ScalarDB's defect reproduced as {@code
   * ScalarDbNullMetadataIntegrationTest} does it, which {@code check} counts and fails on and
   * {@code backfill} repairs, ScalarDB's next update then committing; and an inline imported table,
   * which the setup does not backfill by default.
   */
  @ParameterizedTest
  @MethodSource("engines")
  void theBackfillIsAnOption_andRepairsWhatScalarDbLeft(Engines e) throws Exception {
    Engine engine = e.engine();
    // a new namespace each run: ScalarDB's catalog outlives the schema a run drops
    String name = "tb" + System.currentTimeMillis() % 1_000_000;
    String ns = e.storedSchema(name);
    String imp = e.storedTable("imp");
    String inl = e.storedTable("inl");
    String id = e.storedColumn("id");
    String x = e.storedColumn("x");
    try (Connection c = e.open()) {
      e.execute(
          c,
          e.createSchema(name),
          "CREATE TABLE " + name + ".imp (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO " + name + ".imp VALUES (1, 'a')",
          "INSERT INTO " + name + ".imp VALUES (2, 'b')",
          "CREATE TABLE " + name + ".inl (id " + e.intType() + " PRIMARY KEY, x VARCHAR(10))",
          "INSERT INTO " + name + ".inl VALUES (1, 'a')");
      if (!c.getAutoCommit()) {
        c.commit();
      }
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
      admin.importTable(ns, imp, Map.of("transaction-metadata-decoupling", "true"), Map.of());
      admin.importTable(ns, inl, Map.of(), Map.of());
    }
    String meta = engine.quote(ns) + "." + engine.quote(imp + "_tx_metadata");
    String impName = ns + "." + imp;
    String inlName = ns + "." + inl;
    try {
      // --no-backfill is accepted and changes nothing
      Run inline = tool(e, "setup", "--table", inlName, "--no-backfill", "--catalog-ttl", "0");
      assertThat(inline.exit).as(inline.all()).isZero();
      assertThat(inline.out).contains(inlName + ": ", "backfill skipped (--backfill runs it)");
      // without a flag the setup backfills no table, an imported decoupled one included
      Run setup = tool(e, "setup", "--table", impName, "--catalog-ttl", "0");
      assertThat(setup.exit).as(setup.all()).isZero();
      assertThat(setup.out).contains("backfill skipped (--backfill runs it)");
      try (Connection c = e.open()) {
        assertThat(rows(c, "SELECT COUNT(*) FROM " + meta)).containsExactly("0");
      }
      Run check = tool(e, "check", "--table", impName);
      assertThat(check.exit).as(check.all()).isZero();

      // ScalarDB's rollback over row 1, which has no metadata row, leaves one all NULL
      Key one = engine == Engine.ORACLE ? Key.ofBigInt(id, 1) : Key.ofInt(id, 1);
      String virtual = imp + "_scalardb";
      TransactionFactory scalarDb = e.scalarDb();
      try (TwoPhaseCommitTransactionManager tpc = scalarDb.getTwoPhaseCommitTransactionManager()) {
        TwoPhaseCommitTransaction t1 = tpc.begin();
        t1.get(Get.newBuilder().namespace(ns).table(virtual).partitionKey(one).build());
        t1.update(
            Update.newBuilder()
                .namespace(ns)
                .table(virtual)
                .partitionKey(one)
                .textValue(x, "t1")
                .build());
        t1.prepare();
        t1.rollback();
      }
      check = tool(e, "check", "--table", impName);
      assertThat(check.exit).as(check.all()).isEqualTo(Main.EXIT_FOUND);
      assertThat(check.out)
          .contains(
              impName
                  + ": set up, no rows stuck, 1 rows with NULL metadata, which ScalarDB cannot"
                  + " write again: run backfill");
      Run json = tool(e, "check", "--json", "--table", impName);
      assertThat(json.out).contains("\"nullMetadata\":1");

      // the dry run says how many, and changes nothing
      Run plan = tool(e, "backfill", "--dry-run", "--table", impName);
      assertThat(plan.exit).as(plan.all()).isZero();
      assertThat(plan.out).contains("-- the backfill: 2 rows to stamp now");
      if (engine == Engine.MYSQL) {
        // the recount after the backfill (todo 362)
        assertThat(plan.out).contains("ANALYZE TABLE `" + ns + "`.`" + imp + "_tx_metadata`;");
      }
      assertThat(tool(e, "check", "--table", impName).exit).isEqualTo(Main.EXIT_FOUND);

      Run backfill = tool(e, "backfill", "--table", impName, "--table", inlName);
      assertThat(backfill.exit).as(backfill.all()).isZero();
      assertThat(backfill.out)
          .contains(impName + ": backfilled 2 rows")
          .contains("not backfilled: an inline table needs none (setup --backfill runs it)");
      check = tool(e, "check", "--table", impName);
      assertThat(check.exit).as(check.all()).isZero();
      try (Connection c = e.open()) {
        assertThat(
                rows(
                    c,
                    "SELECT COUNT(*) FROM " + meta + " WHERE " + engine.quote("tx_state") + " = 3"))
            .containsExactly("2");
      }
      // ScalarDB writes the row again
      try (DistributedTransactionManager tm = scalarDb.getTransactionManager()) {
        DistributedTransaction tx = tm.begin();
        tx.get(Get.newBuilder().namespace(ns).table(virtual).partitionKey(one).build());
        tx.update(
            Update.newBuilder()
                .namespace(ns)
                .table(virtual)
                .partitionKey(one)
                .textValue(x, "t2")
                .build());
        tx.commit();
      }
      try (Connection c = e.open()) {
        assertThat(rows(c, "SELECT x FROM " + name + ".imp WHERE id = 1")).containsExactly("t2");
        // the inline table's row: its metadata as the import left it, NULL
        assertThat(
                rows(
                    c,
                    "SELECT COUNT(*) FROM "
                        + name
                        + ".inl WHERE "
                        + engine.quote("tx_id")
                        + " IS NULL"))
            .containsExactly("1");
      }
      assertThat(tool(e, "teardown", "--table", impName, "--table", inlName).exit).isZero();
    } finally {
      try (Connection c = e.open()) {
        e.dropSchemas(c, ns);
      }
    }
  }

  private static String[] concat(String[] a, String... b) {
    String[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static Run tool(Engines e, String action, String... args) throws SQLException {
    List<String> all = new ArrayList<>();
    all.add(action);
    all.add("jdbc:scalardb-native:" + e.url().substring("jdbc:".length()));
    all.addAll(Arrays.asList(args));
    all.addAll(List.of("-U", e.username(), "--property", "password=" + e.password()));
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int exit =
        Main.run(
            new PrintWriter(out, true), new PrintWriter(err, true), all.toArray(new String[0]));
    return new Run(exit, out.toString(), err.toString());
  }

  private static final class Run {
    final int exit;
    final String out;
    final String err;

    Run(int exit, String out, String err) {
      this.exit = exit;
      this.out = out;
      this.err = err;
    }

    String all() {
      return "exit " + exit + "\n" + out + err;
    }
  }
}
