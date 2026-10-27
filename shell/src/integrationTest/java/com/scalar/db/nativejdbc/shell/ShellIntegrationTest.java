package com.scalar.db.nativejdbc.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * snsql through the driver on every engine, with the drivers the zip bundles: MySQL goes through
 * MariaDB Connector/J. Each database holds schema {@code sh} with a plain table, {@code plain}, and
 * a table imported into ScalarDB and set up for the driver, {@code item}. Writes to ScalarDB tables
 * are not implemented by the driver yet (docs/plans/proxy-step.md, milestone 4), so the
 * transactions run on the plain table.
 */
class ShellIntegrationTest {

  private static final Map<String, String> DECOUPLED =
      Map.of("transaction-metadata-decoupling", "true");
  private static final Set<Engines> READY =
      Collections.synchronizedSet(EnumSet.noneOf(Engines.class));

  /** Every engine but MySQL with lower-case table names, which changes nothing in the shell. */
  static List<Engines> engines() {
    List<Engines> engines = new ArrayList<>(Engines.selected());
    engines.remove(Engines.MYSQL_LOWER_CASE);
    return engines;
  }

  @ParameterizedTest
  @MethodSource("engines")
  void aScript_rollsBackAndCommits(Engines e, @TempDir Path dir) throws Exception {
    prepare(e);
    Path script = dir.resolve("script.sql");
    Files.writeString(
        script,
        """
        BEGIN;
        INSERT INTO sh.plain VALUES (1, 'rolled back');
        ROLLBACK;
        begin isolation level serializable;
        INSERT INTO sh.plain
          VALUES (2, 'committed');
        COMMIT;
        SELECT v FROM sh.plain ORDER BY id""",
        StandardCharsets.UTF_8);

    Run run = snsql(e, "-f", script.toString());

    assertThat(run.exit).as(run.err).isZero();
    assertThat(run.out)
        .startsWith("BEGIN\nINSERT 1\nROLLBACK\nBEGIN\nINSERT 1\nCOMMIT\n")
        .contains(" committed\n(1 row)\n");
    assertThat(rows(e)).containsExactly("2:committed");
  }

  @ParameterizedTest
  @MethodSource("engines")
  void onErrorStop_stopsAtTheFirstError(Engines e) throws Exception {
    prepare(e);
    Run run =
        snsql(
            e,
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            "SELECT * FROM missing",
            "-c",
            "INSERT INTO sh.plain VALUES (3, 'not run')");

    assertThat(run.exit).as(run.err).isEqualTo(3);
    assertThat(run.out).startsWith("ERROR:");
    assertThat(rows(e)).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("engines")
  void catalogCommands_markScalarDbTables(Engines e) throws Exception {
    prepare(e);
    Run run =
        snsql(
            e,
            "-c",
            "\\dt sh.*",
            "-c",
            "\\d sh.item",
            "-c",
            "\\di sh.*",
            "-c",
            "\\dn",
            "-c",
            "\\x",
            "-c",
            "SELECT name FROM sh.item WHERE id = 1");

    assertThat(run.exit).as(run.err).isZero();
    String out = run.out;
    assertThat(out)
        // the ScalarDB table, ScalarDB's own tables, and the plain one
        .containsPattern("(?im)^ sh +\\| item +\\| table +\\| yes$")
        .containsPattern("(?im)^ sh +\\| item_tx_metadata +\\| table +\\| internal$")
        .containsPattern("(?im)^ sh +\\| plain +\\| table +\\|$")
        // \d: columns, the primary key, and how the driver treats the table
        .containsPattern("(?i)Table \"sh\\.item\"")
        .containsPattern("(?im)^ id +\\| [^|]+\\| not null +\\|")
        .contains("PRIMARY KEY")
        .contains("ScalarDB table: statements on it go through the driver's rewrite")
        // \di lists the ScalarDB table's indexes, \dn the schema
        .containsPattern("(?im)^ sh +\\| \\S+ +\\| index +\\| item$")
        .containsPattern("(?im)^ sh$")
        // the read of the ScalarDB table, expanded
        .containsPattern("(?i)-\\[ RECORD 1 \\]-*\nname \\| first\n");
  }

  @ParameterizedTest
  @MethodSource("engines")
  void passthrough_runsWhatTheDriverRefuses(Engines e) throws Exception {
    prepare(e);
    // ScalarDB names it after the table with a lower-case suffix, which Oracle reads only quoted
    String sh = e.storedSchema("sh");
    String name =
        new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, () -> admin(e))
            .lookup(List.of(), sh, e.storedTable("item"))
            .metadataTable();
    String metadata =
        "SELECT count(*) FROM sh." + e.engine().quote(java.util.Objects.requireNonNull(name));

    // maintenance of a plain table, which the driver cannot parse and refuses outside passthrough
    String maintenance =
        switch (e.engine()) {
          case POSTGRESQL -> "VACUUM sh.plain";
          case MYSQL, MARIADB -> "CHECK TABLE sh.plain";
          case SQLSERVER -> "UPDATE STATISTICS sh.plain";
          case ORACLE -> "ANALYZE TABLE sh.plain COMPUTE STATISTICS";
        };

    Run refused = snsql(e, "-c", metadata, "-c", maintenance);
    Run passed = snsql(e, "-c", "\\passthrough on", "-c", maintenance, "-c", metadata);

    assertThat(refused.out)
        .startsWith("ERROR:")
        .contains("transaction metadata table")
        .containsPattern("(?s)transaction metadata table.*\nERROR:");
    // passthrough runs the maintenance, but not a statement naming ScalarDB's tables, which would
    // read
    // prepared records as committed (todo 323)
    assertThat(passed.out)
        .startsWith("Passthrough is on")
        .containsOnlyOnce("ERROR:")
        .contains("names a ScalarDB table");
  }

  @ParameterizedTest
  @MethodSource("engines")
  void cancel_stopsTheRunningStatement(Engines e) throws Exception {
    prepare(e);
    // the driver does not read WAITFOR, so it runs under passthrough
    String sleep =
        switch (e.engine()) {
          case POSTGRESQL -> "SELECT pg_sleep(60);";
          case MYSQL, MARIADB -> "SELECT SLEEP(60);";
          case SQLSERVER -> "WAITFOR DELAY '00:01:00';";
          // a cross join that runs for minutes; Oracle has no sleep a query can call
          case ORACLE -> "SELECT count(*) FROM all_objects a, all_objects b, all_objects c;";
        };
    boolean passthrough = e.engine() == Engine.SQLSERVER;
    StringWriter output = new StringWriter();
    try (Connection c = DriverManager.getConnection(connectUrl(e), properties(e))) {
      Repl repl =
          new Repl(
              c,
              StatementSplitter.Dialect.of(e.engine().subProtocol()),
              "url",
              new PrintWriter(output, true),
              false);
      if (passthrough) {
        repl.feed("\\passthrough on");
      }
      long start = System.nanoTime();
      Thread running = new Thread(() -> repl.feed(sleep));
      running.start();
      Thread.sleep(3000);
      repl.cancel();
      running.join(Duration.ofSeconds(30).toMillis());

      assertThat(running.isAlive()).as(output.toString()).isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void completion_offersTheCurrentSchemasNames(Engines e) throws Exception {
    prepare(e);
    StringWriter output = new StringWriter();
    try (Connection c = DriverManager.getConnection(connectUrl(e), properties(e))) {
      Repl repl =
          new Repl(
              c,
              StatementSplitter.Dialect.of(e.engine().subProtocol()),
              "url",
              new PrintWriter(output, true),
              false);
      if (e == Engines.POSTGRESQL) {
        repl.feed("SET search_path = sh;"); // the driver refuses the TO form (todo 104)
      } else if (e != Engines.SQLSERVER) {
        // SQL Server resolves through the user's default schema, which a session cannot change
        repl.feed(e.useSchema(e.storedSchema("sh")) + ";");
      }

      assertThat(repl.completions()).as(output.toString()).contains(e.storedSchema("sh"));
      if (e != Engines.SQLSERVER) {
        assertThat(repl.completions())
            .as(output.toString())
            .contains(e.storedTable("item"), e.storedTable("plain"), e.storedColumn("name"));
      }
    }
  }

  @ParameterizedTest
  @MethodSource("engines")
  void mysql_goesThroughMariaDbConnectorJ(Engines e) throws Exception {
    Assumptions.assumeTrue(e == Engines.MYSQL, "MySQL only");
    prepare(e);

    Run run = snsql(e, "-c", "\\conninfo");

    assertThat(run.exit).as(run.err).isZero();
    assertThat(run.out).contains("MySQL").contains("MariaDB Connector/J");
  }

  @ParameterizedTest
  @MethodSource("engines")
  void connect_switchesDatabases_orKeepsTheConnection(Engines e) throws Exception {
    Assumptions.assumeTrue(e == Engines.POSTGRESQL, "PostgreSQL has a second database to hand");
    prepare(e);

    Run run =
        snsql(
            e,
            "--property",
            "scalar.db.native_jdbc.allow_no_catalog=true",
            "-c",
            "\\c nope",
            "-c",
            "\\c postgres",
            "-c",
            "SELECT current_database()");

    assertThat(run.exit).as(run.err).isZero();
    assertThat(run.out)
        .contains("Previous connection kept")
        .contains("You are now connected to database \"postgres\"")
        .contains(" postgres\n(1 row)");
  }

  /** Why snsql translates BEGIN: the driver refuses it as SQL. */
  @ParameterizedTest
  @MethodSource("engines")
  void theDriver_refusesBeginAsSql(Engines e) throws Exception {
    Assumptions.assumeTrue(e == Engines.POSTGRESQL, "the same refusal on every engine");
    prepare(e);
    try (Connection c = DriverManager.getConnection(connectUrl(e), properties(e));
        Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("BEGIN"))
          .isInstanceOf(SQLException.class)
          .extracting(x -> ((SQLException) x).getSQLState())
          .isEqualTo("0AN01");
    }
  }

  /** The schema, the two tables and the ScalarDB import, once per engine; the plain table empty. */
  private static void prepare(Engines e) throws Exception {
    synchronized (READY) {
      if (!READY.contains(e)) {
        create(e);
        READY.add(e);
      }
    }
    try (Connection c = admin(e)) {
      e.execute(c, "DELETE FROM sh.plain");
    }
  }

  private static void create(Engines e) throws Exception {
    e.url(); // starts the container
    if (e == Engines.MYSQL) {
      // Testcontainers needed Connector/J to wait for the server; from here on, as in the zip,
      // only MariaDB Connector/J serves jdbc:mysql:
      for (Driver d : Collections.list(DriverManager.getDrivers())) {
        if (d.getClass().getName().startsWith("com.mysql.")) {
          DriverManager.deregisterDriver(d);
        }
      }
    }
    String sh = e.storedSchema("sh");
    try (Connection c = admin(e)) {
      e.execute(
          c,
          e.createSchema("sh"),
          "CREATE TABLE sh.plain (id " + e.intType() + " PRIMARY KEY, v VARCHAR(20))",
          "CREATE TABLE sh.item (id " + e.intType() + " PRIMARY KEY, name VARCHAR(20))",
          "INSERT INTO sh.item VALUES (1, 'first')");
    }
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(sh, true);
      admin.importTable(sh, e.storedTable("item"), DECOUPLED, Map.of());
    }
    try (Connection c = admin(e)) {
      List<Setup.Result> results =
          Setup.run(
              new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, () -> admin(e)),
              c,
              t -> t.namespace().equals(sh),
              Duration.ofMinutes(1),
              List.of());
      assertThat(results).allMatch(Setup.Result::succeeded, "succeeded");
    }
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
  }

  private static Run snsql(Engines e, String... args) throws SQLException {
    List<String> all = new ArrayList<>(Arrays.asList(args));
    all.addAll(
        List.of(
            url(e),
            "-U",
            e.username(),
            "--property",
            "password=" + e.password(),
            "--property",
            "scalar.db.native_jdbc.catalog_ttl_millis=0"));
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int exit =
        Main.run(
            new PrintWriter(out, true), new PrintWriter(err, true), all.toArray(new String[0]));
    return new Run(exit, out.toString(), err.toString());
  }

  /**
   * The driver's URL, as a user gives it: for MySQL without permitMysqlScheme, which snsql adds.
   */
  private static String url(Engines e) throws SQLException {
    return "jdbc:scalardb-native:" + e.url().substring("jdbc:".length());
  }

  /** The URL as snsql connects with it: for MySQL with permitMysqlScheme, for MariaDB's driver. */
  private static String connectUrl(Engines e) throws SQLException {
    String url = url(e);
    return e == Engines.MYSQL
        ? url + (url.contains("?") ? "&" : "?") + "permitMysqlScheme=true"
        : url;
  }

  private static Properties properties(Engines e) throws SQLException {
    Properties p = new Properties();
    p.setProperty("user", e.username());
    p.setProperty("password", e.password());
    p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "0");
    return p;
  }

  /** A connection around the driver, through MariaDB Connector/J for MySQL. */
  private static Connection admin(Engines e) throws SQLException {
    String url = e.url();
    if (e == Engines.MYSQL) {
      url += (url.contains("?") ? "&" : "?") + "permitMysqlScheme=true";
    }
    return DriverManager.getConnection(url, e.username(), e.password());
  }

  private static List<String> rows(Engines e) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection c = admin(e);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, v FROM sh.plain ORDER BY id")) {
      while (rs.next()) {
        rows.add(rs.getInt(1) + ":" + rs.getString(2));
      }
    }
    return rows;
  }
}
