package com.scalar.db.nativejdbc.setup;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlWords;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What an operator checks without an application connection (design section 3 and 4.4): the
 * database settings the driver needs, whether each table is set up, and the rows ScalarDB left
 * prepared past the transaction lifetime, which the driver cannot settle (design section 6).
 * Read-only: it never writes and never reads the coordinator.
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
    justification = "names from ScalarDB's catalog, quoted by Engine.quote; values are bound")
public final class Check {

  /** ScalarDB's transaction lifetime, after which a prepared record may be aborted. */
  public static final Duration DEFAULT_LIFETIME = Duration.ofSeconds(15);

  /** One table's state. */
  public static final class Result {
    private final String table;
    private final boolean setUp;
    private final long stuck;
    private final @Nullable Duration oldest;
    private final long nullMetadata;
    private final @Nullable SQLException error;

    Result(
        String table,
        boolean setUp,
        long stuck,
        @Nullable Duration oldest,
        long nullMetadata,
        @Nullable SQLException error) {
      this.table = table;
      this.setUp = setUp;
      this.stuck = stuck;
      this.oldest = oldest;
      this.nullMetadata = nullMetadata;
      this.error = error;
    }

    /**
     * Returns the table, {@code namespace.table}.
     *
     * @return the table
     */
    public String table() {
      return table;
    }

    /**
     * Returns whether the setup has been run for the table, its indexes in the data table's key
     * order: one set up before they were is not, until the setup runs again.
     *
     * @return whether it is set up
     */
    public boolean setUp() {
      return setUp;
    }

    /**
     * Returns the number of rows prepared longer ago than the lifetime.
     *
     * @return the number of stuck rows
     */
    public long stuck() {
      return stuck;
    }

    /**
     * Returns how long ago the oldest stuck row was prepared, or null when none is.
     *
     * @return the age, or null
     */
    public @Nullable Duration oldest() {
      return oldest;
    }

    /**
     * Returns the number of metadata rows with a NULL {@code tx_state} on an imported decoupled
     * table: only ScalarDB's rollback over a row that had no metadata row writes one, and ScalarDB
     * cannot write such a row again until {@code backfill} stamps it (design section 1 and 3, todo
     * 361). Always 0 in another layout.
     *
     * @return the number of such rows
     */
    public long nullMetadata() {
      return nullMetadata;
    }

    /**
     * Returns the error that stopped the check of this table, if any.
     *
     * @return the error, or null
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "an exception is handed out as is")
    public @Nullable SQLException error() {
      return error;
    }

    /**
     * Returns whether the table is set up, has no stuck rows nor rows with NULL metadata, and was
     * checked.
     *
     * @return whether all is well
     */
    public boolean ok() {
      return setUp && stuck == 0 && nullMetadata == 0 && error == null;
    }

    @Override
    public String toString() {
      if (error != null) {
        return table + ": failed: " + error.getMessage();
      }
      return table
          + ": "
          + (setUp ? "set up" : "not set up")
          + (stuck == 0
              ? ", no rows stuck"
              : ", "
                  + stuck
                  + " rows prepared past the lifetime, the oldest "
                  + Objects.requireNonNull(oldest).toSeconds()
                  + " s ago")
          + (nullMetadata == 0
              ? ""
              : ", "
                  + nullMetadata
                  + " rows with NULL metadata, which ScalarDB cannot write again: run backfill");
    }
  }

  private Check() {}

  /**
   * The settings of the database the driver needs, each one that is wrong as a sentence saying how
   * to fix it: on SQL Server {@code READ_COMMITTED_SNAPSHOT} on, {@code XACT_ABORT} not among the
   * server's user options, and {@code ALLOW_SNAPSHOT_ISOLATION} on for REPEATABLE READ and
   * SERIALIZABLE; nothing elsewhere.
   *
   * @param engine the engine
   * @param connection a connection to the database
   * @return the problems, empty when there are none
   * @throws SQLException if the settings cannot be read
   */
  public static List<String> database(Engine engine, Connection connection) throws SQLException {
    List<String> out = new ArrayList<>();
    if (engine != Engine.SQLSERVER) {
      return out;
    }
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT name, is_read_committed_snapshot_on, snapshot_isolation_state FROM"
                    + " sys.databases WHERE database_id = DB_ID()")) {
      if (rs.next()) {
        String name = rs.getString(1);
        if (!rs.getBoolean(2)) {
          out.add(
              "database "
                  + name
                  + " has READ_COMMITTED_SNAPSHOT off, which the driver needs: ALTER DATABASE "
                  + name
                  + " SET READ_COMMITTED_SNAPSHOT ON");
        }
        if (rs.getInt(3) != 1) {
          out.add(
              "database "
                  + name
                  + " does not allow snapshot isolation, which REPEATABLE READ and SERIALIZABLE run"
                  + " at: ALTER DATABASE "
                  + name
                  + " SET ALLOW_SNAPSHOT_ISOLATION ON");
        }
      }
    }
    try (Statement st = connection.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT CAST(value_in_use AS INT) & 16384 FROM sys.configurations"
                    + " WHERE name = 'user options'")) {
      if (rs.next() && rs.getInt(1) != 0) {
        out.add(
            "XACT_ABORT is among the server's user options, so every session starts with it on,"
                + " which the driver refuses: take it out of 'user options'");
      }
    }
    return out;
  }

  /**
   * Checks the chosen decoupled tables of the catalog: whether each is set up, and its rows in
   * flight since longer ago than {@code lifetime}, counted through the in-flight index. ScalarDB
   * settles such a row when one of its reads meets it; until then the driver shows its before image
   * and waits on it. On an imported decoupled table, also its metadata rows with a NULL {@code
   * tx_state}, which ScalarDB's defect leaves and {@code backfill} repairs (todo 361).
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database
   * @param which the tables to check
   * @param lifetime how long a row may be prepared before it counts as stuck
   * @return one result per table
   * @throws SQLException if the catalogs cannot be read
   */
  public static List<Result> tables(
      Catalog catalog, Connection connection, Predicate<TableInfo> which, Duration lifetime)
      throws SQLException {
    Engine engine = catalog.engine();
    catalog.invalidate();
    List<Result> out = new ArrayList<>();
    for (TableInfo t : catalog.scalarDbTables()) {
      if (!which.test(t)) {
        continue;
      }
      String name = t.namespace() + "." + t.table();
      String meta =
          engine.quote(t.namespace())
              + "."
              + engine.quote(Objects.requireNonNull(t.metadataTable(), "metadata table"));
      long now = System.currentTimeMillis();
      try (PreparedStatement ps =
          connection.prepareStatement(
              "SELECT COUNT(*), MIN("
                  + engine.quote("tx_prepared_at")
                  + ") FROM "
                  + meta
                  + " WHERE "
                  + engine.quote("tx_state")
                  + " IN (1, 2) AND "
                  + engine.quote("tx_prepared_at")
                  + " < ?")) {
        ps.setLong(1, now - lifetime.toMillis());
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          long stuck = rs.getLong(1);
          long min = rs.getLong(2);
          long nullMetadata = 0;
          if (t.layout() == Layout.DECOUPLED_IMPORTED) {
            // the in-flight index's first column, except SQL Server's filtered one: a scan there
            try (Statement st = connection.createStatement();
                ResultSet n =
                    st.executeQuery(
                        "SELECT COUNT(*) FROM "
                            + meta
                            + " WHERE "
                            + engine.quote("tx_state")
                            + " IS NULL")) {
              n.next();
              nullMetadata = n.getLong(1);
            }
          }
          out.add(
              new Result(
                  name,
                  t.isSetUp()
                      && KeyOrder.inOrder(
                          connection,
                          engine,
                          t,
                          Objects.requireNonNull(t.metadataTable(), "metadata table")),
                  stuck,
                  stuck == 0 ? null : Duration.ofMillis(now - min),
                  nullMetadata,
                  null));
        }
      } catch (SQLException e) {
        out.add(new Result(name, t.isSetUp(), 0, null, 0, e));
      }
    }
    return out;
  }

  /**
   * The database's own objects that reach a ScalarDB table without ScalarDB's protocol (todo 323),
   * each as a sentence: a view, trigger, procedure or function whose definition names one (on
   * Oracle, that depends on one), a trigger on one, and a foreign key on one whose referenced row's
   * delete or update changes its rows. A statement through such an object, or the database itself,
   * writes or reads the table as a plain one. The driver's read views and ScalarDB's virtual tables
   * do not count. Names are matched by their words, in any case, so an object that names a plain
   * table of the same name in another schema counts too; SQL built at run time is not seen.
   *
   * @param catalog the catalog of the database
   * @param connection a connection to the same database
   * @return the objects, empty when there are none
   * @throws SQLException if the catalogs cannot be read
   */
  public static List<String> objects(Catalog catalog, Connection connection) throws SQLException {
    Engine engine = catalog.engine();
    // namespace.name, lower case, of every table that holds a ScalarDB table's rows or shows them
    Set<String> tables = new HashSet<>();
    Set<String> names = new LinkedHashSet<>();
    Set<String> own = new HashSet<>();
    for (TableInfo t : catalog.scalarDbTables()) {
      List<String> physical = new ArrayList<>(List.of(t.table(), t.dataTable()));
      if (t.metadataTable() != null) {
        physical.add(t.metadataTable());
      }
      if (t.virtualTable() != null) {
        physical.add(t.virtualTable());
        own.add(key(t.namespace(), t.virtualTable()));
      }
      own.add(key(t.namespace(), t.readView(engine)));
      for (String p : physical) {
        tables.add(key(t.namespace(), p));
        names.add(p);
      }
    }
    List<String> out = new ArrayList<>();
    if (tables.isEmpty()) {
      return out;
    }
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(objectsQuery(engine))) {
      while (rs.next()) {
        String kind = rs.getString(1);
        String name = rs.getString(2) + "." + rs.getString(3);
        String on = rs.getString(4) == null ? null : rs.getString(4) + "." + rs.getString(5);
        String body = rs.getString(6);
        if (own.contains(key(rs.getString(2), rs.getString(3)))) {
          continue;
        }
        if (on != null && tables.contains(key(rs.getString(4), rs.getString(5)))) {
          out.add(
              kind
                  + " "
                  + name
                  + (engine == Engine.ORACLE ? " depends on" : " is on")
                  + " ScalarDB table "
                  + on
                  + BYPASSES);
          continue;
        }
        String named = body == null ? null : SqlWords.find(body, engine, names);
        if (named != null) {
          out.add(kind + " " + name + " names ScalarDB table " + named + BYPASSES);
        }
      }
    }
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(foreignKeysQuery(engine))) {
      while (rs.next()) {
        String table = rs.getString(1) + "." + rs.getString(3);
        if (!tables.contains(key(rs.getString(1), rs.getString(3)))) {
          continue;
        }
        for (int i = 4; i <= 5; i++) {
          String rule = rs.getString(i);
          if (rule != null && CHANGING_RULES.contains(rule.toUpperCase(Locale.ROOT))) {
            out.add(
                "foreign key "
                    + rs.getString(2)
                    + " on ScalarDB table "
                    + table
                    + " is ON "
                    + (i == 4 ? "DELETE " : "UPDATE ")
                    + rule.toUpperCase(Locale.ROOT)
                    + ": the database changes the table's rows itself, without ScalarDB's"
                    + " protocol; make it NO ACTION");
          }
        }
      }
    }
    return out;
  }

  private static final String BYPASSES =
      ": a statement through it reaches the table without ScalarDB's protocol; drop it, or keep it"
          + " out of use while ScalarDB's transactions run";

  private static final Set<String> CHANGING_RULES = Set.of("CASCADE", "SET NULL", "SET DEFAULT");

  private static String key(String schema, String name) {
    return (schema + "." + name).toLowerCase(Locale.ROOT);
  }

  /**
   * Kind, schema, name, the table it is on or depends on (schema and name, or nulls), and its
   * definition.
   */
  private static String objectsQuery(Engine engine) {
    switch (engine) {
      case POSTGRESQL:
        return "SELECT 'view', n.nspname, c.relname, NULL, NULL, pg_get_viewdef(c.oid)"
            + " FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE c.relkind IN ('v', 'm') AND n.nspname NOT IN ('pg_catalog',"
            + " 'information_schema')"
            + " UNION ALL SELECT 'trigger', n.nspname, t.tgname, n.nspname, c.relname, p.prosrc"
            + " FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_proc p ON p.oid = t.tgfoid"
            + " WHERE NOT t.tgisinternal"
            + " UNION ALL SELECT 'function', n.nspname, p.proname, NULL, NULL, p.prosrc"
            + " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
            + " JOIN pg_language l ON l.oid = p.prolang"
            + " WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')"
            + " AND l.lanname NOT IN ('c', 'internal')";
      case MYSQL:
      case MARIADB:
        return "SELECT 'view', TABLE_SCHEMA, TABLE_NAME, NULL, NULL, VIEW_DEFINITION"
            + " FROM information_schema.VIEWS WHERE TABLE_SCHEMA NOT IN "
            + MYSQL_SYSTEM
            + " UNION ALL SELECT 'trigger', TRIGGER_SCHEMA, TRIGGER_NAME, EVENT_OBJECT_SCHEMA,"
            + " EVENT_OBJECT_TABLE, ACTION_STATEMENT FROM information_schema.TRIGGERS"
            + " WHERE TRIGGER_SCHEMA NOT IN "
            + MYSQL_SYSTEM
            + " UNION ALL SELECT LOWER(ROUTINE_TYPE), ROUTINE_SCHEMA, ROUTINE_NAME, NULL, NULL,"
            + " ROUTINE_DEFINITION FROM information_schema.ROUTINES"
            + " WHERE ROUTINE_SCHEMA NOT IN "
            + MYSQL_SYSTEM;
      case SQLSERVER:
        return "SELECT CASE o.type WHEN 'V' THEN 'view' WHEN 'TR' THEN 'trigger'"
            + " WHEN 'P' THEN 'procedure' ELSE 'function' END, SCHEMA_NAME(o.schema_id), o.name,"
            + " OBJECT_SCHEMA_NAME(NULLIF(o.parent_object_id, 0)),"
            + " OBJECT_NAME(NULLIF(o.parent_object_id, 0)), m.definition"
            + " FROM sys.sql_modules m JOIN sys.objects o ON o.object_id = m.object_id"
            + " WHERE o.is_ms_shipped = 0";
      case ORACLE:
        // the definitions are LONG or in lines: the static dependencies instead
        return "SELECT LOWER(d.TYPE), d.OWNER, d.NAME, d.REFERENCED_OWNER, d.REFERENCED_NAME, NULL"
            + " FROM ALL_DEPENDENCIES d JOIN ALL_USERS u ON u.USERNAME = d.OWNER"
            + " WHERE u.ORACLE_MAINTAINED = 'N' AND d.REFERENCED_TYPE IN ('TABLE', 'VIEW')"
            + " AND d.TYPE IN ('VIEW', 'MATERIALIZED VIEW', 'TRIGGER', 'PROCEDURE', 'FUNCTION',"
            + " 'PACKAGE BODY', 'TYPE BODY')";
    }
    throw new AssertionError(engine);
  }

  private static final String MYSQL_SYSTEM =
      "('mysql', 'sys', 'performance_schema', 'information_schema')";

  /** Schema, name, the table it is on, and its delete and update rules. */
  private static String foreignKeysQuery(Engine engine) {
    switch (engine) {
      case POSTGRESQL:
        return "SELECT n.nspname, con.conname, c.relname,"
            + " CASE con.confdeltype WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL'"
            + " WHEN 'd' THEN 'SET DEFAULT' END,"
            + " CASE con.confupdtype WHEN 'c' THEN 'CASCADE' WHEN 'n' THEN 'SET NULL'"
            + " WHEN 'd' THEN 'SET DEFAULT' END"
            + " FROM pg_constraint con JOIN pg_class c ON c.oid = con.conrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE con.contype = 'f'";
      case MYSQL:
      case MARIADB:
        return "SELECT CONSTRAINT_SCHEMA, CONSTRAINT_NAME, TABLE_NAME, DELETE_RULE, UPDATE_RULE"
            + " FROM information_schema.REFERENTIAL_CONSTRAINTS";
      case SQLSERVER:
        return "SELECT SCHEMA_NAME(f.schema_id), f.name, OBJECT_NAME(f.parent_object_id),"
            + " REPLACE(f.delete_referential_action_desc, '_', ' '),"
            + " REPLACE(f.update_referential_action_desc, '_', ' ') FROM sys.foreign_keys f";
      case ORACLE:
        return "SELECT c.OWNER, c.CONSTRAINT_NAME, c.TABLE_NAME, c.DELETE_RULE, NULL"
            + " FROM ALL_CONSTRAINTS c JOIN ALL_USERS u ON u.USERNAME = c.OWNER"
            + " WHERE u.ORACLE_MAINTAINED = 'N' AND c.CONSTRAINT_TYPE = 'R'";
    }
    throw new AssertionError(engine);
  }
}
