package com.scalar.db.nativejdbc.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.Insert;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.TwoPhaseCommitTransaction;
import com.scalar.db.api.TwoPhaseCommitTransactionManager;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.testing.Engines;
import java.io.PrintWriter;
import java.io.StringWriter;
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
                "\"table\":\"tl.u\",\"setUp\":true,\"stuck\":1,\"oldestSeconds\":[1-9]");
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
            "ScalarDB transactions may write tl\\.t from \\d\\d:\\d\\d:\\d\\d, or after every"
                + " application using the driver on this database has restarted\\.")
        .doesNotContain("Waiting");
    assertThat(tool(e, "teardown", "--table", "tl.t").exit).isZero();
    Run json = tool(e, "setup", "--table", "tl.t", "--json");
    assertThat(json.exit).as(json.all()).isZero();
    assertThat(json.out)
        .containsPattern("\"newTables\":\\[\"tl\\.t\"\\],\"writableAt\":\"\\d{4}-[^\"]+Z\"")
        .doesNotContain("Waiting");
    assertThat(tool(e, "teardown", "--table", "tl.t").exit).isZero();
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
