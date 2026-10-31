package com.scalar.db.nativejdbc.testing;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.service.TransactionFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One database container per engine per JVM, the selected engines' started together on first use
 * and reaped by Testcontainers when the JVM ends. Select engines with {@code
 * -Dnativejdbc.engines=postgresql,oracle}; the default, {@code all}, runs the five. The connection
 * is always the administrative one, which the fixtures and ScalarDB's admin both need.
 *
 * <p>{@code -Dnativejdbc.<engine>.url}, with {@code .username} and {@code .password}, points an
 * engine at an existing database instead of a container, {@code <engine>} as in {@code
 * nativejdbc.engines}. The user must be able to create schemas and, on Oracle, users (ScalarDB
 * creates one per namespace). On SQL Server the URL names an application database with {@code
 * READ_COMMITTED_SNAPSHOT} and {@code ALLOW_SNAPSHOT_ISOLATION} on; on Oracle, SYSDBA has set
 * {@code "_disable_last_successful_login_time"} (see {@code ORACLE}), or SERIALIZABLE tests fail
 * now and then.
 *
 * <p>{@code -Dnativejdbc.<engine>.image} starts another image of the same database, such as {@code
 * postgres:18}, for the server versions the weekly run tests
 * (.github/workflows/vendor-drivers.yml).
 */
public enum Engines {
  POSTGRESQL(Engine.POSTGRESQL) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new PostgreSQLContainer(image("postgres:16"));
    }
  },
  MYSQL(Engine.MYSQL) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new MySQLContainer(image("mysql:8.4")).withUsername("root").withPassword("test");
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
      return new MySQLContainer(image("mysql:8.4"))
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
      return new MariaDBContainer(image("mariadb:11.4")).withUsername("root").withPassword("test");
    }
  },
  SQLSERVER(Engine.SQLSERVER) {
    @Override
    JdbcDatabaseContainer<?> create() {
      return new MSSQLServerContainer(image("mcr.microsoft.com/mssql/server:2022-latest"))
          .acceptLicense();
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
        // REPEATABLE READ runs at SNAPSHOT there (design 5)
        st.execute("ALTER DATABASE appdb SET ALLOW_SNAPSHOT_ISOLATION ON");
      }
      return c.getJdbcUrl() + ";databaseName=appdb";
    }
  },
  ORACLE(Engine.ORACLE) {
    @Override
    JdbcDatabaseContainer<?> create() {
      // about a minute alone, several when the other engines start beside it
      return new OracleContainer(image("gvenzl/oracle-free:23-slim-faststart"))
          .withStartupTimeout(Duration.ofMinutes(10));
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

  /** The image CI tests, or the one {@code -Dnativejdbc.<engine>.image} names. */
  String image(String tested) {
    return System.getProperty("nativejdbc." + name().toLowerCase(Locale.ROOT) + ".image", tested);
  }

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

  private Started started() throws SQLException {
    synchronized (STARTED) {
      if (STARTED.isEmpty()) {
        start(selected());
      }
      if (!STARTED.containsKey(this)) {
        start(List.of(this)); // a test that names an engine outside the selection
      }
      return Objects.requireNonNull(STARTED.get(this));
    }
  }

  private static void start(List<Engines> engines) throws SQLException {
    Map<Engines, JdbcDatabaseContainer<?>> containers = new EnumMap<>(Engines.class);
    for (Engines e : engines) {
      String prefix = "nativejdbc." + e.name().toLowerCase(Locale.ROOT) + ".";
      String url = System.getProperty(prefix + "url");
      if (url != null) {
        String username = System.getProperty(prefix + "username");
        if (username == null) {
          throw new IllegalStateException(prefix + "url is set without " + prefix + "username");
        }
        STARTED.put(e, new Started(url, username, System.getProperty(prefix + "password", "")));
      } else {
        containers.put(e, e.create());
      }
    }
    Startables.deepStart(containers.values()).join();
    for (Map.Entry<Engines, JdbcDatabaseContainer<?>> entry : containers.entrySet()) {
      Engines e = entry.getKey();
      JdbcDatabaseContainer<?> c = entry.getValue();
      STARTED.put(e, new Started(e.url(c), e.username(c), c.getPassword()));
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
    p.setProperty("scalar.db.contact_points", extra.getProperty("scalar.db.contact_points", s.url));
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

  /**
   * Opens a connection through the driver at READ COMMITTED, with a catalog of its own read afresh
   * per lookup.
   */
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
    p.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "0");
    p.putAll(extra);
    p.setProperty("user", s.username);
    p.setProperty("password", s.password);
    Connection c =
        DriverManager.getConnection("jdbc:scalardb-native:" + s.url.substring("jdbc:".length()), p);
    // the level the suites are written at; a connection starts at SNAPSHOT, which a suite sets
    // where it means it, and IsolationIntegrationTest checks
    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    return c;
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
      case MYSQL, MYSQL_LOWER_CASE, MARIADB -> "CREATE DATABASE " + name;
      case ORACLE -> "CREATE USER " + name + " IDENTIFIED BY " + name + " QUOTA UNLIMITED ON USERS";
      default -> "CREATE SCHEMA " + name;
    };
  }

  /**
   * Drops the schemas given by their stored names, with what they hold, where they exist: what an
   * external database keeps from an earlier run. Oracle 19c has no DROP USER IF EXISTS: ORA-01918
   * is ignored.
   */
  public void dropSchemas(Connection c, String... stored) throws SQLException {
    for (String s : stored) {
      String schema = engine.quote(s);
      execute(
          c,
          switch (this) {
            case POSTGRESQL -> "DROP SCHEMA IF EXISTS " + schema + " CASCADE";
            case MYSQL, MYSQL_LOWER_CASE, MARIADB -> "DROP DATABASE IF EXISTS " + schema;
            case ORACLE ->
                "BEGIN EXECUTE IMMEDIATE 'DROP USER "
                    + schema
                    + " CASCADE';"
                    + " EXCEPTION WHEN OTHERS THEN IF SQLCODE != -1918 THEN RAISE; END IF; END;";
            // a schema drops only when empty: its views, foreign keys and tables first
            case SQLSERVER -> {
              String name = "N'" + s.replace("'", "''") + "'";
              yield "DECLARE @d nvarchar(max) = N'';"
                  + " SELECT @d += N'DROP VIEW ' + QUOTENAME(SCHEMA_NAME(schema_id)) + N'.'"
                  + " + QUOTENAME(name) + N';' FROM sys.views WHERE SCHEMA_NAME(schema_id) = "
                  + name
                  + "; SELECT @d += N'ALTER TABLE ' + QUOTENAME(SCHEMA_NAME(schema_id)) + N'.'"
                  + " + QUOTENAME(OBJECT_NAME(parent_object_id)) + N' DROP CONSTRAINT '"
                  + " + QUOTENAME(name) + N';' FROM sys.foreign_keys"
                  + " WHERE SCHEMA_NAME(schema_id) = "
                  + name
                  + "; SELECT @d += N'DROP TABLE ' + QUOTENAME(SCHEMA_NAME(schema_id)) + N'.'"
                  + " + QUOTENAME(name) + N';' FROM sys.tables WHERE SCHEMA_NAME(schema_id) = "
                  + name
                  + "; EXEC sp_executesql @d; DROP SCHEMA IF EXISTS "
                  + schema;
            }
            default -> throw new UnsupportedOperationException("dropSchemas on " + this);
          });
    }
  }

  /** The statement that makes {@code schema} the session's first schema for unqualified names. */
  public String useSchema(String schema) {
    return switch (this) {
      case POSTGRESQL -> "SET search_path TO " + schema + ", public";
      case MYSQL, MYSQL_LOWER_CASE, MARIADB -> "USE " + schema;
      case ORACLE -> "ALTER SESSION SET CURRENT_SCHEMA = " + schema;
      default ->
          throw new UnsupportedOperationException(
              "SQL Server resolves through the user's default schema");
    };
  }

  /**
   * The URL with a lock wait of about a second, for ScalarDB's storage connections where a test
   * runs both sides in one thread: a write that waits on the driver's lock fails rather than hangs.
   *
   * @return the URL
   * @throws SQLException if the URL cannot be read
   */
  public String shortLockWait() throws SQLException {
    String url = url();
    return engine == Engine.SQLSERVER
        ? url + ";lockTimeout=1000"
        : url + (url.contains("?") ? "&" : "?") + "sessionVariables=innodb_lock_wait_timeout=1";
  }

  /** A FROM item of the integers {@code from} to {@code to}, column {@code g}, alias {@code g}. */
  public String series(int from, int to) {
    if (engine == Engine.POSTGRESQL) {
      return "generate_series(" + from + ", " + to + ") g";
    }
    if (engine == Engine.ORACLE) {
      return "(SELECT LEVEL + "
          + (from - 1)
          + " AS g FROM dual CONNECT BY LEVEL <= "
          + (to - from + 1)
          + ") g";
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

  /** A test's statement in the engine's dialect: T-SQL's hints and paging, Oracle's paging. */
  public String sql(String statement) {
    return switch (engine) {
      case SQLSERVER -> Rows.tsql(statement);
      case ORACLE -> Rows.oracle(statement);
      default -> statement;
    };
  }

  /** A binary column type. */
  public String binaryType() {
    return switch (engine) {
      case POSTGRESQL -> "BYTEA";
      case SQLSERVER -> "VARBINARY(MAX)";
      default -> "BLOB";
    };
  }

  /** A text column type that compares with {@code =}, which SQL Server's TEXT does not. */
  public String textType() {
    return switch (engine) {
      case SQLSERVER -> "VARCHAR(400)";
      case ORACLE -> "VARCHAR2(400)";
      default -> "TEXT";
    };
  }

  /** A predicate that calls a function of {@link #pauseFunction}. */
  public String paused(String name) {
    return engine == Engine.SQLSERVER || engine == Engine.ORACLE
        ? name + "() = 1"
        : "(SELECT " + name + "())";
  }

  /**
   * The DDL of a function {@code name()} that sleeps {@code seconds} and returns true, once per
   * call: a window inside a running statement.
   */
  public String pauseFunction(String name, int seconds) {
    if (engine == Engine.ORACLE) {
      return "CREATE OR REPLACE FUNCTION "
          + name
          + " RETURN NUMBER IS BEGIN DBMS_SESSION.SLEEP("
          + seconds
          + "); RETURN 1; END;";
    }
    if (engine == Engine.SQLSERVER) {
      // a function cannot WAITFOR there: it spins
      return "CREATE FUNCTION "
          + name
          + "() RETURNS BIT AS BEGIN DECLARE @t DATETIME2 = SYSDATETIME();"
          + " WHILE DATEDIFF(millisecond, @t, SYSDATETIME()) < "
          + seconds * 1000
          + " SET @t = @t; RETURN 1; END";
    }
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
    if (engine == Engine.ORACLE) {
      return "SELECT 1 FROM v$session WHERE event = 'PL/SQL lock timer'"
          + " AND sid <> SYS_CONTEXT('USERENV', 'SID')";
    }
    if (engine == Engine.SQLSERVER) {
      return "SELECT 1 FROM sys.dm_exec_requests r CROSS APPLY sys.dm_exec_sql_text(r.sql_handle) t"
          + " WHERE r.session_id <> @@SPID AND t.text LIKE '%pause()%'"
          + " AND t.text NOT LIKE '%dm_exec_requests%'";
    }
    return engine == Engine.POSTGRESQL
        ? "SELECT 1 FROM pg_stat_activity WHERE wait_event = 'PgSleep'"
        : "SELECT 1 FROM information_schema.processlist WHERE state = 'User sleep'";
  }

  /**
   * Runs fixture DDL as given, but for a multi-row {@code INSERT ... VALUES} on Oracle before 23,
   * which has none: there it runs as an {@code INSERT ALL} of the same rows.
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "test fixture DDL written in the test sources, never data")
  public void execute(Connection c, String... sql) throws SQLException {
    boolean singleRowValues =
        engine == Engine.ORACLE && c.getMetaData().getDatabaseMajorVersion() < 23;
    try (Statement st = c.createStatement()) {
      for (String s : sql) {
        st.execute(singleRowValues ? insertAll(s) : s);
      }
    }
  }

  private static final Pattern MULTI_ROW_INSERT =
      Pattern.compile("(?is)INSERT INTO (\\S+(?: \\([^)]*\\))?) VALUES (\\(.*\\))");

  /** {@code INSERT INTO t VALUES (a), (b)} as {@code INSERT ALL INTO t VALUES (a) INTO t ...}. */
  static String insertAll(String sql) {
    Matcher m = MULTI_ROW_INSERT.matcher(sql.trim());
    if (!m.matches()) {
      return sql;
    }
    List<String> rows = new ArrayList<>();
    String values = m.group(2);
    int depth = 0;
    int start = 0;
    boolean quoted = false;
    for (int i = 0; i < values.length(); i++) {
      char ch = values.charAt(i);
      if (ch == '\'') {
        quoted = !quoted;
      } else if (!quoted && ch == '(') {
        if (depth++ == 0) {
          start = i;
        }
      } else if (!quoted && ch == ')' && --depth == 0) {
        rows.add(values.substring(start, i + 1));
      } else if (!quoted && depth == 0 && ch != ',' && !Character.isWhitespace(ch)) {
        return sql; // not a plain list of rows
      }
    }
    if (rows.size() < 2) {
      return sql;
    }
    StringBuilder all = new StringBuilder("INSERT ALL");
    for (String row : rows) {
      all.append(" INTO ").append(m.group(1)).append(" VALUES ").append(row);
    }
    return all.append(" SELECT 1 FROM dual").toString();
  }
}
