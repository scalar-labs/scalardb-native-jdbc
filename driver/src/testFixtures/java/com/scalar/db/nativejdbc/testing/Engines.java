package com.scalar.db.nativejdbc.testing;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One database container per engine per JVM, started on first use and reaped by Testcontainers when
 * the JVM ends. Select engines with {@code -Dnativejdbc.engines=postgresql,oracle}; the default,
 * {@code all}, runs the five. The connection is always the administrative one, which the fixtures
 * and ScalarDB's admin both need.
 */
public enum Engines {
  POSTGRESQL(Engine.POSTGRESQL) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new PostgreSQLContainer("postgres:16");
    }
  },
  MYSQL(Engine.MYSQL) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new MySQLContainer("mysql:8.4").withUsername("root").withPassword("test");
    }
  },
  /**
   * MySQL with {@code lower_case_table_names=1}, the Windows default and common on managed
   * services: table names are stored in lower case and compared without regard to case, so the
   * spelling ScalarDB records differs from the one the engine stores.
   */
  MYSQL_LOWER_CASE(Engine.MYSQL) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new MySQLContainer("mysql:8.4")
          .withUsername("root")
          .withPassword("test")
          .withCommand("--lower-case-table-names=1");
    }

    @Override
    public String storedTable(String unquoted) {
      return unquoted.toLowerCase(java.util.Locale.ROOT);
    }
  },
  MARIADB(Engine.MARIADB) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new MariaDBContainer("mariadb:11.4").withUsername("root").withPassword("test");
    }
  },
  SQLSERVER(Engine.SQLSERVER) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
    }

    @Override
    String url(JdbcDatabaseContainer<?> c) throws SQLException {
      // ScalarDB works inside one database; master is not the place for application schemas
      try (Connection conn =
              DriverManager.getConnection(c.getJdbcUrl(), c.getUsername(), c.getPassword());
          Statement st = conn.createStatement()) {
        st.execute("CREATE DATABASE appdb");
        // the driver refuses a database without it (design section 1)
        st.execute("ALTER DATABASE appdb SET READ_COMMITTED_SNAPSHOT ON");
      }
      return c.getJdbcUrl() + ";databaseName=appdb";
    }
  },
  ORACLE(Engine.ORACLE) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new OracleContainer("gvenzl/oracle-free:23-slim-faststart");
    }

    @Override
    String url(JdbcDatabaseContainer<?> c) throws SQLException {
      // Each logon writes the user's last login time into SYS.USER$, and a hard parse inside a
      // SERIALIZABLE transaction reads USER$ at the transaction's snapshot: with the pools' logons
      // that read fails now and then with ORA-08177, and in ScalarDB's record commit, which ignores
      // the failure, the rows stay prepared. Only SYSDBA may switch the logon write off.
      Properties p = new Properties();
      p.setProperty("user", "sys");
      p.setProperty("password", c.getPassword());
      p.setProperty("internal_logon", "sysdba");
      try (Connection conn = DriverManager.getConnection(c.getJdbcUrl(), p);
          Statement st = conn.createStatement()) {
        st.execute(
            "ALTER SYSTEM SET \"_disable_last_successful_login_time\" = TRUE SCOPE = MEMORY");
      }
      return c.getJdbcUrl();
    }

    @Override
    String username(JdbcDatabaseContainer<?> c) {
      return "system"; // ScalarDB creates a user per namespace, which needs DBA rights
    }

    @Override
    String isolation() {
      return "SERIALIZABLE"; // Oracle has no REPEATABLE READ
    }
  };

  private static final Map<Engines, Started> STARTED = new EnumMap<>(Engines.class);

  private static final class Started {
    final String url;
    final String username;
    final String password;

    Started(String url, String username, String password) {
      this.url = url;
      this.username = username;
      this.password = password;
    }
  }

  private final Engine engine;

  Engines(Engine engine) {
    this.engine = engine;
  }

  abstract JdbcDatabaseContainer<?> create();

  String url(JdbcDatabaseContainer<?> c) throws SQLException {
    return c.getJdbcUrl();
  }

  String username(JdbcDatabaseContainer<?> c) {
    return c.getUsername();
  }

  String isolation() {
    return "REPEATABLE_READ";
  }

  /** The engines selected for this run. */
  public static List<Engines> selected() {
    String property = System.getProperty("nativejdbc.engines", "all").trim();
    if (property.isEmpty() || property.equalsIgnoreCase("all")) {
      return List.of(values());
    }
    List<Engines> out = new ArrayList<>();
    for (String name : property.split(",", -1)) {
      out.add(valueOf(name.trim().toUpperCase(Locale.ROOT)));
    }
    return out;
  }

  public Engine engine() {
    return engine;
  }

  /** Whether the driver writes ScalarDB tables on this engine yet (milestone 8). */
  public boolean writes() {
    return engine == Engine.POSTGRESQL || engine == Engine.MYSQL || engine == Engine.MARIADB;
  }

  private Started started() throws SQLException {
    synchronized (STARTED) {
      Started s = STARTED.get(this);
      if (s == null) {
        JdbcDatabaseContainer<?> c = create();
        c.start();
        s = new Started(url(c), username(c), c.getPassword());
        STARTED.put(this, s);
      }
      return s;
    }
  }

  public String url() throws SQLException {
    return started().url;
  }

  public String username() throws SQLException {
    return started().username;
  }

  public String password() throws SQLException {
    return started().password;
  }

  /** Opens an administrative connection, in autocommit mode. */
  public Connection open() throws SQLException {
    Started s = started();
    return DriverManager.getConnection(s.url, s.username, s.password);
  }

  /** ScalarDB's admin on this database. */
  public DistributedTransactionAdmin admin() throws Exception {
    return scalarDb().getTransactionAdmin();
  }

  /** ScalarDB's Consensus Commit on this database, for its transaction managers. */
  public TransactionFactory scalarDb() throws SQLException {
    return scalarDb(new Properties());
  }

  /**
   * ScalarDB's Consensus Commit on this database with extra properties.
   *
   * @param extra the properties added to those of {@link #scalarDb()}, such as Consensus Commit's
   *     isolation level or the storage's
   * @return the factory
   * @throws SQLException if the database does not start
   */
  public TransactionFactory scalarDb(Properties extra) throws SQLException {
    Started s = started();
    Properties p = new Properties();
    p.putAll(extra);
    p.setProperty("scalar.db.storage", "jdbc");
    p.setProperty("scalar.db.contact_points", s.url);
    p.setProperty("scalar.db.username", s.username);
    p.setProperty("scalar.db.password", s.password);
    p.setProperty("scalar.db.transaction_manager", "consensus-commit");
    p.putIfAbsent("scalar.db.jdbc.isolation_level", isolation());
    // a few idle connections per pool, not ScalarDB's 20, so that the managers the test classes
    // open stay within PostgreSQL's 100 connections
    p.setProperty("scalar.db.jdbc.connection_pool.min_idle", "2");
    p.setProperty("scalar.db.jdbc.table_metadata.connection_pool.min_idle", "1");
    p.setProperty("scalar.db.jdbc.admin.connection_pool.min_idle", "1");
    return TransactionFactory.create(p);
  }

  /** Opens a connection through the driver, with a catalog of its own read afresh per lookup. */
  public Connection openDriver() throws SQLException {
    return openDriver(new Properties());
  }

  /**
   * Opens a connection through the driver with extra connect properties.
   *
   * @param extra the properties added to the user and password of {@link #openDriver()}, a catalog
   *     TTL among them replacing its 0
   * @return the connection
   * @throws SQLException if the driver refuses it
   */
  public Connection openDriver(Properties extra) throws SQLException {
    Started s = started();
    Properties p = new Properties();
    p.setProperty("scalardbnative.catalogTtlMs", "0");
    p.putAll(extra);
    p.setProperty("user", s.username);
    p.setProperty("password", s.password);
    return DriverManager.getConnection(
        "jdbc:scalardb-native:" + s.url.substring("jdbc:".length()), p);
  }

  /** The stored spelling of a table name written unquoted in DDL on this engine. */
  public String storedTable(String unquoted) {
    return engine.resolveIdentifier(unquoted);
  }

  /** The stored spelling of a column name written unquoted in DDL on this engine. */
  public String storedColumn(String unquoted) {
    return engine.resolveIdentifier(unquoted);
  }

  /** The stored spelling of a schema name written unquoted in DDL on this engine. */
  public String storedSchema(String unquoted) {
    return storedTable(unquoted);
  }

  /** An integer column type ScalarDB can import on this engine. */
  public String intType() {
    return this == ORACLE ? "NUMBER(10)" : "INT";
  }

  /** The DDL that creates a schema for application tables; on Oracle a schema is a user. */
  public String createSchema(String name) {
    return switch (this) {
      case MYSQL, MARIADB -> "CREATE DATABASE " + name;
      case ORACLE -> "CREATE USER " + name + " IDENTIFIED BY " + name + " QUOTA UNLIMITED ON USERS";
      default -> "CREATE SCHEMA " + name;
    };
  }

  /** The statement that makes {@code schema} the session's first schema for unqualified names. */
  public String useSchema(String schema) {
    return switch (this) {
      case POSTGRESQL -> "SET search_path TO " + schema + ", public";
      case MYSQL, MARIADB -> "USE " + schema;
      case ORACLE -> "ALTER SESSION SET CURRENT_SCHEMA = " + schema;
      default ->
          throw new UnsupportedOperationException(
              "SQL Server resolves through the user's default schema");
    };
  }

  /** A FROM item of the integers {@code from} to {@code to}, column {@code g}, alias {@code g}. */
  public String series(int from, int to) {
    if (engine == Engine.POSTGRESQL) {
      return "generate_series(" + from + ", " + to + ") g";
    }
    if (engine != Engine.MYSQL && engine != Engine.MARIADB) {
      throw new UnsupportedOperationException(engine + " (milestone 8)");
    }
    // a digit per power of ten, cross joined: MySQL limits a recursive CTE to 1,000 rows
    String digits =
        "(SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3"
            + " UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7"
            + " UNION ALL SELECT 8 UNION ALL SELECT 9)";
    List<String> joins = new ArrayList<>(List.of(digits + " d0"));
    List<String> terms = new ArrayList<>(List.of("d0.d"));
    for (long span = 10; span <= to - from; span *= 10) {
      joins.add(digits + " d" + joins.size());
      terms.add(span + " * d" + terms.size() + ".d");
    }
    return "(SELECT g FROM (SELECT "
        + from
        + " + "
        + String.join(" + ", terms)
        + " AS g FROM "
        + String.join(" CROSS JOIN ", joins)
        + ") s WHERE g <= "
        + to
        + ") g";
  }

  /** A binary column type. */
  public String binaryType() {
    return engine == Engine.POSTGRESQL ? "BYTEA" : "BLOB";
  }

  /**
   * The DDL of a function {@code name()} that sleeps {@code seconds} and returns true, once per
   * call: a window inside a running statement.
   */
  public String pauseFunction(String name, int seconds) {
    return engine == Engine.POSTGRESQL
        ? "CREATE FUNCTION "
            + name
            + "() RETURNS BOOLEAN AS 'SELECT pg_sleep("
            + seconds
            + ") IS NOT NULL' LANGUAGE sql VOLATILE"
        : "CREATE FUNCTION "
            + name
            + "() RETURNS BOOLEAN NOT DETERMINISTIC NO SQL BEGIN DO SLEEP("
            + seconds
            + "); RETURN TRUE; END";
  }

  /** A query with a row while some session sleeps in a function of {@link #pauseFunction}. */
  public String sleeping() {
    return engine == Engine.POSTGRESQL
        ? "SELECT 1 FROM pg_stat_activity WHERE wait_event = 'PgSleep'"
        : "SELECT 1 FROM information_schema.processlist WHERE state = 'User sleep'";
  }

  /** Runs fixture DDL as given. */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "test fixture DDL written in the test sources, never data")
  public void execute(Connection c, String... sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      for (String s : sql) {
        st.execute(s);
      }
    }
  }
}
