package com.scalar.db.nativejdbc.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * snsql against PostgreSQL through the driver. Writes to ScalarDB tables are not implemented by the
 * driver yet (docs/plans/proxy-step.md, milestone 4), so the transactions run on a plain table, in
 * a database without ScalarDB's catalog.
 */
class ShellIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void createTable() throws SQLException {
    try (Connection c = direct();
        Statement s = c.createStatement()) {
      s.execute("DROP TABLE IF EXISTS t");
      s.execute("CREATE TABLE t (id INT PRIMARY KEY, v VARCHAR(20))");
    }
  }

  @Test
  void aScript_rollsBackAndCommits(@TempDir Path dir) throws Exception {
    Path script = dir.resolve("script.sql");
    Files.writeString(
        script,
        """
        BEGIN;
        INSERT INTO t VALUES (1, 'rolled back');
        ROLLBACK;
        begin isolation level serializable;
        INSERT INTO t
          VALUES (2, 'committed');
        COMMIT;
        SELECT id, v FROM t ORDER BY id""",
        StandardCharsets.UTF_8);

    Run run = snsql("-f", script.toString());

    assertThat(run.exit).as(run.err).isZero();
    assertThat(run.out)
        .isEqualTo(
            """
            BEGIN
            INSERT 1
            ROLLBACK
            BEGIN
            INSERT 1
            COMMIT
             id |     v
            ----+-----------
              2 | committed
            (1 row)

            """);
    assertThat(rows()).containsExactly("2:committed");
  }

  @Test
  void onErrorStop_stopsAtTheFirstError() throws Exception {
    Run run =
        snsql(
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            "SELECT * FROM missing",
            "-c",
            "INSERT INTO t VALUES (3, 'not run')");

    assertThat(run.exit).as(run.err).isEqualTo(3);
    assertThat(run.out).contains("ERROR: relation \"missing\" does not exist");
    assertThat(rows()).isEmpty();
  }

  @Test
  void withoutOnErrorStop_theScriptGoesOn() throws Exception {
    Run run = snsql("-c", "SELECT * FROM missing; INSERT INTO t VALUES (4, 'run')");

    assertThat(run.exit).as(run.err).isZero();
    assertThat(rows()).containsExactly("4:run");
  }

  /** Why snsql translates BEGIN: the driver refuses it as SQL. */
  @Test
  void theDriver_refusesBeginAsSql() throws Exception {
    try (Connection c = DriverManager.getConnection(url(), properties());
        Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("BEGIN"))
          .isInstanceOf(SQLException.class)
          .extracting(e -> ((SQLException) e).getSQLState())
          .isEqualTo("0AN01");
    }
  }

  @Test
  void aMissingVendorDriver_isNamed() {
    Run run = snsql("jdbc:scalardb-native:sqlserver://localhost:1433");

    assertThat(run.exit).isEqualTo(2);
    assertThat(run.err).contains("no JDBC driver for jdbc:sqlserver:").contains("mssql-jdbc");
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

  private static Run snsql(String... args) {
    List<String> all = new ArrayList<>(Arrays.asList(args));
    if (all.stream().noneMatch(a -> a.startsWith("jdbc:"))) {
      all.addAll(
          List.of(
              url(),
              "-U",
              POSTGRES.getUsername(),
              "--property",
              "password=" + POSTGRES.getPassword(),
              "--property",
              "scalardbnative.allowNoCatalog=true"));
    }
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int exit =
        Main.run(
            new PrintWriter(out, true), new PrintWriter(err, true), all.toArray(new String[0]));
    return new Run(exit, out.toString(), err.toString());
  }

  private static String url() {
    return "jdbc:scalardb-native:" + POSTGRES.getJdbcUrl().substring("jdbc:".length());
  }

  private static Properties properties() {
    Properties p = new Properties();
    p.setProperty("user", POSTGRES.getUsername());
    p.setProperty("password", POSTGRES.getPassword());
    p.setProperty("scalardbnative.allowNoCatalog", "true");
    return p;
  }

  private static Connection direct() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static List<String> rows() throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection c = direct();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT id, v FROM t ORDER BY id")) {
      while (rs.next()) {
        rows.add(rs.getInt(1) + ":" + rs.getString(2));
      }
    }
    return rows;
  }
}
