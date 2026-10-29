package com.scalar.db.nativejdbc.setup;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.engine.SetupObjects;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Sets up decoupled ScalarDB tables for the driver (design section 3), and takes the setup down
 * again. Per table, in this order: the in-flight index on the metadata table, built without
 * blocking writes where the engine can; the backfill of section 1 where it runs (by default on an
 * imported decoupled table only, "The backfill as an option", todo 361); last the read view {@code
 * rc_T}, and a grant of SELECT on it to each application user. Every step is create-if-absent or
 * re-runnable, so running the setup again changes nothing. It needs DDL privileges and is run by an
 * operator, never at connect; the driver checks at catalog load that the index and the view exist,
 * and other drivers see a new setup after their catalog TTL. The view existing says nothing about
 * the backfill, which {@link #backfill(Catalog, Connection, Predicate, Duration)} also runs alone.
 * The backfill writes every row of the table and belongs in a quiet window (design section 1).
 */
@SuppressFBWarnings(
    value = {
      "SQL_INJECTION_JDBC",
      "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING",
      "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE"
    },
    justification =
        "the DDL and backfill text holds only names from ScalarDB's catalog, each quoted by"
            + " Engine.quote, which doubles an embedded closing quote; values are bound")
public final class Setup {

  /** The {@code tx_id} the backfill writes on a row ScalarDB deems committed. */
  static final String BACKFILL_TX_ID = "scalardb-native-backfill";

  private static final Duration DEFAULT_BACKFILL_WAIT = Duration.ofMinutes(1);

  /** The outcome for one table. */
  public static final class Result {
    private final String table;
    private final List<String> actions;
    private final long rowsStamped;
    private final @Nullable SQLException error;
    private final boolean createdReadView;

    Result(
        String table,
        List<String> actions,
        long rowsStamped,
        @Nullable SQLException error,
        boolean createdReadView) {
      this.table = table;
      this.actions = List.copyOf(actions);
      this.rowsStamped = rowsStamped;
      this.error = error;
      this.createdReadView = createdReadView;
    }

    /**
     * Returns whether this run created the table's read view, where none was: the table is set up
     * for the first time, even when a later step failed, and running drivers learn of it only after
     * their catalog TTL (todo 298).
     *
     * @return whether the read view was created
     */
    public boolean createdReadView() {
      return createdReadView;
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
     * Returns what was done, in order; empty when everything already existed.
     *
     * @return the actions
     */
    public List<String> actions() {
      return actions;
    }

    /**
     * Returns the number of metadata rows the backfill wrote or stamped.
     *
     * @return the number of rows
     */
    public long rowsStamped() {
      return rowsStamped;
    }

    /**
     * Returns the error that stopped this table, if any; the steps before it stay done.
     *
     * @return the error, or null
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "an exception is handed out as is")
    public @Nullable SQLException error() {
      return error;
    }

    /**
     * Returns whether the table is now set up (or, for a teardown, taken down).
     *
     * @return whether it succeeded
     */
    public boolean succeeded() {
      return error == null;
    }

    @Override
    public String toString() {
      return table
          + ": "
          + (actions.isEmpty() ? "nothing to do" : String.join(", ", actions))
          + (error == null ? "" : "; failed: " + error.getMessage());
    }
  }

  private final Engine engine;
  private final Connection connection;
  private final Duration backfillWait;
  // --backfill or --no-backfill, null for the default by layout
  private final @Nullable Boolean backfill;
  private final List<String> grantees;
  private final String txId;
  private final String txState;
  private final String txVersion;
  // in a dry run, the statements a table's setup or teardown would run, in place of running them
  private final @Nullable List<String> planned;

  private Setup(
      Engine engine,
      Connection connection,
      Duration backfillWait,
      @Nullable Boolean backfill,
      List<String> grantees,
      boolean dryRun) {
    this.planned = dryRun ? new ArrayList<>() : null;
    this.engine = engine;
    this.connection = connection;
    this.backfillWait = backfillWait;
    this.backfill = backfill;
    this.grantees = List.copyOf(grantees);
    this.txId = engine.quote("tx_id");
    this.txState = engine.quote("tx_state");
    this.txVersion = engine.quote("tx_version");
  }

  /**
   * Sets up every decoupled table of the catalog, waiting up to a minute per table for the
   * backfill, and grants nothing: the read view is readable by its owner only.
   *
   * @param catalog the catalog of the database
   * @param connection a connection to the same database, in autocommit, with DDL privileges
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> run(Catalog catalog, Connection connection) throws SQLException {
    return run(catalog, connection, t -> true, DEFAULT_BACKFILL_WAIT, List.of());
  }

  /**
   * Sets up the chosen decoupled tables of the catalog, the backfill by default by layout. A table
   * that fails does not stop the others.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit, with DDL privileges
   * @param which the tables to set up
   * @param backfillWait how long to wait per table for in-flight rows the backfill must outlast
   * @param grantees the application users or roles granted SELECT on each read view, spelled as the
   *     engine stores them; on MySQL and MariaDB {@code user} or {@code user@host}
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> run(
      Catalog catalog,
      Connection connection,
      Predicate<TableInfo> which,
      Duration backfillWait,
      List<String> grantees)
      throws SQLException {
    return run(catalog, connection, which, backfillWait, grantees, null);
  }

  /**
   * Sets up the chosen decoupled tables of the catalog. A table that fails does not stop the
   * others.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit, with DDL privileges
   * @param which the tables to set up
   * @param backfillWait how long to wait per table for in-flight rows the backfill must outlast
   * @param grantees as for {@link #run(Catalog, Connection, Predicate, Duration, List)}
   * @param backfill whether to backfill each imported or inline table; null for the default, an
   *     imported decoupled table only (design section 3, todo 361)
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> run(
      Catalog catalog,
      Connection connection,
      Predicate<TableInfo> which,
      Duration backfillWait,
      List<String> grantees,
      @Nullable Boolean backfill)
      throws SQLException {
    Setup setup = new Setup(catalog.engine(), connection, backfillWait, backfill, grantees, false);
    List<TableInfo> tables = setup.tables(catalog, which);
    SetupObjects found = setup.list(tables);
    List<Result> results = new ArrayList<>();
    int isolation = connection.getTransactionIsolation();
    // the backfill's scans take no shared locks that stall ScalarDB and the application
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    try {
      for (TableInfo t : tables) {
        results.add(setup.up(t, found));
      }
    } finally {
      connection.setTransactionIsolation(isolation);
      catalog.invalidate(); // the next lookup sees what was created
    }
    return results;
  }

  /**
   * Takes the setup down for the chosen tables: drops the read view and the in-flight index, as
   * ScalarDB's dropTable and dropNamespace need on PostgreSQL. A view of the same name the setup
   * did not create is left alone.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit, with DDL privileges
   * @param which the tables to take down
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> teardown(
      Catalog catalog, Connection connection, Predicate<TableInfo> which) throws SQLException {
    return teardown(catalog, connection, which, false);
  }

  /**
   * A dry run of {@link #run(Catalog, Connection, Predicate, Duration, List)}: reads what exists
   * and changes nothing. Each result's actions are the statements the setup would run, in order,
   * and a comment line with the number of rows the backfill would stamp now.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit
   * @param which the tables to set up
   * @param grantees as for {@link #run(Catalog, Connection, Predicate, Duration, List)}
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> plan(
      Catalog catalog, Connection connection, Predicate<TableInfo> which, List<String> grantees)
      throws SQLException {
    return plan(catalog, connection, which, grantees, null);
  }

  /**
   * A dry run of {@link #run(Catalog, Connection, Predicate, Duration, List, Boolean)}.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit
   * @param which the tables to set up
   * @param grantees as for {@link #run(Catalog, Connection, Predicate, Duration, List)}
   * @param backfill as for {@link #run(Catalog, Connection, Predicate, Duration, List, Boolean)}
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> plan(
      Catalog catalog,
      Connection connection,
      Predicate<TableInfo> which,
      List<String> grantees,
      @Nullable Boolean backfill)
      throws SQLException {
    Setup setup =
        new Setup(catalog.engine(), connection, DEFAULT_BACKFILL_WAIT, backfill, grantees, true);
    List<TableInfo> tables = setup.tables(catalog, which);
    SetupObjects found = setup.list(tables);
    List<Result> results = new ArrayList<>();
    for (TableInfo t : tables) {
      results.add(setup.up(t, found));
    }
    return results;
  }

  /**
   * A dry run of {@link #teardown(Catalog, Connection, Predicate)}: each result's actions are the
   * statements it would run.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit
   * @param which the tables to take down
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> planTeardown(
      Catalog catalog, Connection connection, Predicate<TableInfo> which) throws SQLException {
    return teardown(catalog, connection, which, true);
  }

  private static List<Result> teardown(
      Catalog catalog, Connection connection, Predicate<TableInfo> which, boolean dryRun)
      throws SQLException {
    Setup setup =
        new Setup(catalog.engine(), connection, DEFAULT_BACKFILL_WAIT, null, List.of(), dryRun);
    List<TableInfo> tables = setup.tables(catalog, which);
    SetupObjects found = setup.list(tables);
    List<Result> results = new ArrayList<>();
    try {
      for (TableInfo t : tables) {
        results.add(setup.down(t, found));
      }
    } finally {
      catalog.invalidate();
    }
    return results;
  }

  /**
   * The backfill alone on the chosen imported decoupled tables that are set up (design section 3,
   * "The backfill as an option", todo 361), with the setup's statements, wait and retries: DML on
   * the metadata table, no DDL, at any time and repeatable. An inline table, which never needs it,
   * is left to {@code setup --backfill}. It also repairs a row ScalarDB's rollback over a row
   * without metadata left with NULL metadata (section 1), giving it the sentinel stamp. On Oracle a
   * decoupled table's metadata table then gets the setup's fixed statistics again.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit
   * @param which the tables to backfill
   * @param backfillWait how long to wait per table for in-flight rows the backfill must outlast
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> backfill(
      Catalog catalog, Connection connection, Predicate<TableInfo> which, Duration backfillWait)
      throws SQLException {
    return backfill(catalog, connection, which, backfillWait, false);
  }

  /**
   * A dry run of {@link #backfill(Catalog, Connection, Predicate, Duration)}: the statements, and
   * the number of rows it would stamp now.
   *
   * @param catalog the catalog of the database, read afresh first
   * @param connection a connection to the same database, in autocommit
   * @param which the tables to backfill
   * @return one result per table
   * @throws SQLException if the connection is not in autocommit or the catalogs cannot be read
   */
  public static List<Result> planBackfill(
      Catalog catalog, Connection connection, Predicate<TableInfo> which) throws SQLException {
    return backfill(catalog, connection, which, DEFAULT_BACKFILL_WAIT, true);
  }

  private static List<Result> backfill(
      Catalog catalog,
      Connection connection,
      Predicate<TableInfo> which,
      Duration backfillWait,
      boolean dryRun)
      throws SQLException {
    Setup setup = new Setup(catalog.engine(), connection, backfillWait, null, List.of(), dryRun);
    List<TableInfo> tables = setup.tables(catalog, which);
    List<Result> results = new ArrayList<>();
    int isolation = connection.getTransactionIsolation();
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    try {
      for (TableInfo t : tables) {
        results.add(setup.fill(t));
      }
    } finally {
      connection.setTransactionIsolation(isolation);
    }
    return results;
  }

  private Result fill(TableInfo t) {
    String name = t.namespace() + "." + t.table();
    List<String> actions = planned != null ? planned : new ArrayList<>();
    actions.clear();
    long stamped = 0;
    try {
      if (!t.isSetUp()) {
        throw new SQLNonTransientException(
            "the table is not set up: run setup, which backfills it too with --backfill", "55000");
      }
      if (!backfills(t)) {
        done(
            actions,
            t.layout() == Layout.INLINE
                ? "not backfilled: an inline table needs none (setup --backfill runs it)"
                : "no backfill needed: ScalarDB created every row's metadata");
        return new Result(name, actions, 0, null, false);
      }
      stamped = backfill(t);
      done(actions, "backfilled " + stamped + " rows");
      if (recount(t)) {
        done(actions, "analyzed " + metadataTable(t));
      }
      if (engine == Engine.ORACLE && t.layout() != Layout.INLINE && gatherHistogram(t, false)) {
        done(actions, "set and locked fixed statistics on " + metadataTable(t) + ".tx_state");
      }
      return new Result(name, actions, stamped, null, false);
    } catch (SQLException e) {
      return new Result(name, actions, stamped, e, false);
    }
  }

  /**
   * Whether the backfill runs on this table: on an imported decoupled or an inline table, as {@code
   * --backfill} or {@code --no-backfill} says, else on an imported decoupled table only, while
   * ScalarDB's defect of section 1 stands. A table ScalarDB created decoupled has every metadata
   * row.
   */
  private boolean backfills(TableInfo t) {
    if (t.layout() != Layout.DECOUPLED_IMPORTED && t.layout() != Layout.INLINE) {
      return false;
    }
    return backfill != null ? backfill : t.layout() == Layout.DECOUPLED_IMPORTED;
  }

  private List<TableInfo> tables(Catalog catalog, Predicate<TableInfo> which) throws SQLException {
    if (!connection.getAutoCommit()) {
      throw new SQLNonTransientException("the setup runs in autocommit mode", "55000");
    }
    catalog.invalidate(); // tables ScalarDB registered since the last load included
    List<TableInfo> out = new ArrayList<>();
    for (TableInfo t : catalog.scalarDbTables()) {
      if (which.test(t)) {
        out.add(t);
      }
    }
    return out;
  }

  private SetupObjects list(List<TableInfo> tables) throws SQLException {
    Set<String> schemas = new LinkedHashSet<>();
    Set<String> views = new LinkedHashSet<>();
    Set<String> indexes = new LinkedHashSet<>();
    for (TableInfo t : tables) {
      schemas.add(t.namespace());
      views.add(t.readView(engine));
      indexes.add(t.inflightIndex(engine));
    }
    return engine.setupObjects(
        connection, schemas, views, indexes, engine.tableNamesCaseInsensitive(connection));
  }

  private Result up(TableInfo t, SetupObjects found) {
    String name = t.namespace() + "." + t.table();
    List<String> actions = planned != null ? planned : new ArrayList<>();
    actions.clear();
    long stamped = 0;
    boolean created = false;
    try {
      String ns = t.namespace();
      String view = t.readView(engine);
      String index = t.inflightIndex(engine);
      String keyIndex = t.keyIndex(engine);
      String meta = metadataTable(t);
      for (String n : List.of(view, index, keyIndex)) {
        if (!engine.fitsIdentifier(n)) {
          throw new SQLSyntaxErrorException(
              "the name " + n + " the setup needs is longer than the engine allows", "42622");
        }
      }
      if (engine == Engine.SQLSERVER && t.layout() == Layout.DECOUPLED_IMPORTED) {
        refuseDatetimeKey(t);
      }
      // a read view in the key-first order of a setup before todo 316 is the setup's own
      boolean reorders = found.viewReorders(ns, view, t.dataColumns());
      if (found.hasView(ns, view) && !reorders && !found.viewMatches(ns, view, t.dataColumns())) {
        throw new SQLNonTransientException(
            "a view "
                + ns
                + "."
                + view
                + " exists whose columns differ from the table's; the setup does not replace a"
                + " view it did not create: drop or rename it",
            "55000");
      }
      // the key in the data table's order, the engine's constraint's: an imported composite key's
      // metadata table is keyed by column name (design section 3)
      List<String> key = KeyOrder.primaryKey(connection, engine, ns, t.dataTable(), t.keyColumns());
      if (!found.hasIndex(ns, meta, index)) {
        createIndex(t, index, key);
        done(actions, "created in-flight index " + index);
      } else if (!KeyOrder.inflightInOrder(connection, engine, t, meta, key)) {
        if (engine != Engine.POSTGRESQL) {
          execute(dropIndex(t, index)); // PostgreSQL's build drops the old one itself
        }
        createIndex(t, index, key);
        done(actions, "rebuilt in-flight index " + index + " in the data table's key order");
      }
      if (t.layout() != Layout.INLINE
          && !KeyOrder.keyIndexInPlace(connection, engine, t, meta, key)) {
        createKeyIndex(t, keyIndex, key);
        done(actions, "created key index " + keyIndex + " in the data table's key order");
      }
      // an inline table imported into ScalarDB holds its rows with NULL metadata, as an imported
      // decoupled one does without metadata rows; one ScalarDB created has none, and costs a count
      if (backfills(t)) {
        stamped = backfill(t);
        done(actions, "backfilled " + stamped + " rows");
        if (recount(t)) {
          done(actions, "analyzed " + meta);
        }
      } else if (t.layout() == Layout.DECOUPLED_IMPORTED || t.layout() == Layout.INLINE) {
        done(actions, "backfill skipped (--backfill runs it)");
      }
      if (reorders) {
        execute("DROP VIEW " + name(ns, view)); // CREATE OR REPLACE cannot reorder columns
      }
      replaceView(t, view, found.hasView(ns, view) && !reorders);
      created = planned == null && !found.hasView(ns, view);
      done(actions, (found.hasView(ns, view) ? "replaced" : "created") + " read view " + view);
      // a metadata table not backfilled is empty or nearly: on Oracle its locked statistics are
      // set as if it held a row per data row (todo 361)
      boolean asIfBackfilled =
          engine == Engine.ORACLE && t.layout() == Layout.DECOUPLED_IMPORTED && !backfills(t);
      if (gatherHistogram(t, asIfBackfilled)) {
        done(
            actions,
            engine == Engine.POSTGRESQL
                ? "analyzed " + meta
                : engine != Engine.ORACLE
                    ? "gathered a histogram on " + meta + ".tx_state"
                    : t.layout() == Layout.INLINE
                        ? "set fixed statistics on " + meta + ".tx_state"
                        : asIfBackfilled
                            ? "set and locked fixed statistics on "
                                + meta
                                + ".tx_state, with "
                                + t.dataTable()
                                + "'s row count and key statistics"
                            : "set and locked fixed statistics on " + meta + ".tx_state");
      }
      for (String g : grantees) {
        execute("GRANT SELECT ON " + name(ns, view) + " TO " + grantee(g));
        done(actions, "granted SELECT on " + view + " to " + g);
      }
      return new Result(name, actions, stamped, null, created);
    } catch (SQLException e) {
      return new Result(name, actions, stamped, e, created);
    }
  }

  private Result down(TableInfo t, SetupObjects found) {
    String name = t.namespace() + "." + t.table();
    List<String> actions = planned != null ? planned : new ArrayList<>();
    actions.clear();
    try {
      String ns = t.namespace();
      String view = t.readView(engine);
      String index = t.inflightIndex(engine);
      if (found.viewMatches(ns, view, t.dataColumns())) {
        execute("DROP VIEW " + name(ns, view));
        done(actions, "dropped read view " + view);
      }
      if (engine == Engine.POSTGRESQL) {
        // also an invalid index a failed concurrent build left, which the listing omits
        execute("DROP INDEX CONCURRENTLY IF EXISTS " + name(ns, index));
        done(actions, "dropped in-flight index " + index);
      } else if (found.hasIndex(ns, metadataTable(t), index)) {
        execute(dropIndex(t, index));
        done(actions, "dropped in-flight index " + index);
      }
      String keyIndex = t.keyIndex(engine);
      if (t.layout() != Layout.INLINE
          && KeyOrder.indexColumns(connection, engine, ns, metadataTable(t), keyIndex) != null) {
        execute(
            engine == Engine.POSTGRESQL
                ? "DROP INDEX CONCURRENTLY IF EXISTS " + name(ns, keyIndex)
                : dropIndex(t, keyIndex));
        done(actions, "dropped key index " + keyIndex);
      }
      if (engine == Engine.ORACLE) {
        // the fixed histogram goes too: it would mislead the optimizer once nothing keeps it true
        String meta = metadataTable(t);
        String args = oracleLiteral('"' + ns + '"') + ", " + oracleLiteral('"' + meta + '"');
        boolean locked = t.layout() != Layout.INLINE;
        execute(
            "BEGIN "
                + (locked ? "DBMS_STATS.UNLOCK_TABLE_STATS(" + args + "); " : "")
                + "DBMS_STATS.DELETE_COLUMN_STATS("
                + args
                + ", "
                + oracleLiteral(txState)
                + ", col_stat_type => 'HISTOGRAM'); END;");
        done(
            actions,
            (locked ? "unlocked the statistics of " + meta + " and " : "")
                + "deleted the histogram on "
                + meta
                + ".tx_state");
      }
      return new Result(name, actions, 0, null, false);
    } catch (SQLException e) {
      return new Result(name, actions, 0, e, false);
    }
  }

  /**
   * The rows in flight Oracle's statistics on {@code tx_state} assume, at most half the table: a
   * histogram gathered while few are in flight makes a burst of them cost up to 1650x, one assuming
   * 10,000 costs little when there are 100 (docs/benchmarks/read-overhead.md, 2026-10-20).
   */
  static final int ORACLE_ASSUMED_INFLIGHT = 10_000;

  /**
   * The table's statistics with fixed ones on {@code tx_state}, then, unless the last bind is 0,
   * locked so that neither the nightly job nor a manual gather replaces them; {@code force} past an
   * earlier run's lock. Every column's: a key column without them makes each hard parse of a keyed
   * statement sample the table through the in-flight index, 0.3 s a text at 3.2M rows (todo 334);
   * {@code SIZE REPEAT} keeps an inline table's own histograms. Binds the owner and table quoted,
   * so that DBMS_STATS takes them as stored, the column, the rows in flight to assume, and whether
   * to lock.
   */
  private static final String ORACLE_FIXED_STATISTICS =
      "DECLARE o VARCHAR2(260) := ?; t VARCHAR2(260) := ?; c VARCHAR2(260) := ?;"
          + " n NUMBER; b NUMBER; l NUMBER; f NUMBER; r DBMS_STATS.STATREC;"
          + " BEGIN"
          + " DBMS_STATS.GATHER_TABLE_STATS(ownname => o, tabname => t,"
          + " method_opt => 'FOR ALL COLUMNS SIZE REPEAT', force => TRUE);"
          + " DBMS_STATS.GET_TABLE_STATS(o, t, numrows => n, numblks => b, avgrlen => l);"
          + " f := LEAST(?, GREATEST(1, FLOOR(n / 2)));"
          + " r.epc := 3; r.bkvals := DBMS_STATS.NUMARRAY(f, 1, GREATEST(1, n - f - 1));"
          + " DBMS_STATS.PREPARE_COLUMN_VALUES(r, DBMS_STATS.NUMARRAY(1, 2, 3));"
          + " DBMS_STATS.SET_COLUMN_STATS(ownname => o, tabname => t, colname => c, distcnt => 3,"
          + " density => 0.5 / GREATEST(1, n), nullcnt => 0, srec => r, avgclen => 3,"
          + " force => TRUE);"
          + " IF ? = 1 THEN DBMS_STATS.LOCK_TABLE_STATS(o, t); END IF;"
          + " END;";

  /**
   * {@link #ORACLE_FIXED_STATISTICS} for a decoupled metadata table the setup did not backfill
   * (design section 3, todo 361), set as if it had been: where the data table holds more rows than
   * the metadata table, the data table's rows and blocks from its statistics (its count where it
   * has none), and each key column's statistics copied from the data table's (a distinct count of
   * the rows where it has none), before the {@code tx_state} histogram over those rows and the
   * lock. Locked at the few rows an empty table has, every plan joining it would take it as empty:
   * a scan of the in-flight index per data row, 3.7 s and 550,000 gets a 1,000-key read on the
   * container.
   */
  private String oracleStatistics(TableInfo t, boolean asIfBackfilled) {
    if (!asIfBackfilled) {
      return ORACLE_FIXED_STATISTICS;
    }
    String d = oracleLiteral('"' + t.dataTable() + '"');
    StringBuilder keys = new StringBuilder();
    for (String k : t.keyColumns()) {
      String c = oracleLiteral(engine.quote(k));
      keys.append(" BEGIN DBMS_STATS.GET_COLUMN_STATS(o, ")
          .append(d)
          .append(", ")
          .append(c)
          .append(", distcnt => kd, density => ky, nullcnt => kn, srec => kr, avgclen => ka);")
          .append(" DBMS_STATS.SET_COLUMN_STATS(ownname => o, tabname => t, colname => ")
          .append(c)
          .append(", distcnt => kd, density => ky, nullcnt => kn, srec => kr, avgclen => ka,")
          .append(" force => TRUE); EXCEPTION WHEN OTHERS THEN")
          .append(" DBMS_STATS.SET_COLUMN_STATS(ownname => o, tabname => t, colname => ")
          .append(c)
          .append(", distcnt => n, density => 1 / n, nullcnt => 0, force => TRUE); END;");
    }
    String read = " DBMS_STATS.GET_TABLE_STATS(o, t, numrows => n, numblks => b, avgrlen => l);";
    return ORACLE_FIXED_STATISTICS
        .replace(
            " r DBMS_STATS.STATREC;",
            " r DBMS_STATS.STATREC; dn NUMBER; db NUMBER; dl NUMBER; kd NUMBER; ky NUMBER;"
                + " kn NUMBER; ka NUMBER; kr DBMS_STATS.STATREC;")
        .replace(
            read,
            read
                + " BEGIN DBMS_STATS.GET_TABLE_STATS(o, "
                + d
                + ", numrows => dn, numblks => db, avgrlen => dl); EXCEPTION WHEN OTHERS THEN"
                + " dn := NULL; END; IF dn IS NULL THEN EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM '"
                + " || o || '.' || "
                + d
                + " INTO dn; db := GREATEST(1, CEIL(dn / 100)); dl := 0; END IF;"
                + " IF dn > n THEN n := dn; b := GREATEST(b, db);"
                + " DBMS_STATS.SET_TABLE_STATS(ownname => o, tabname => t, numrows => n,"
                + " numblks => b, avgrlen => CASE WHEN l > 0 THEN l ELSE GREATEST(dl, 1) END,"
                + " force => TRUE);"
                + keys
                + " END IF;");
  }

  /**
   * On MySQL, an {@code ANALYZE TABLE} of the metadata table the backfill has just filled (todo
   * 362): until InnoDB recounts it on its own, its persistent statistics still say the rows it had
   * before, none on a new import, and reads scan it, 26.8 ms and 300,007 handler reads a point read
   * of a 300,000-row table against 1.2 ms and 7. The histogram on {@code tx_state} does not update
   * them. MariaDB's statistics lag the same way, but its plans did not suffer (measured, todo 362).
   *
   * @return whether it ran
   */
  private boolean recount(TableInfo t) throws SQLException {
    if (engine != Engine.MYSQL) {
      return false;
    }
    String sql = "ANALYZE TABLE " + name(t.namespace(), metadataTable(t));
    if (planned != null) {
      planned.add(sql);
      return true;
    }
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      // a failure comes back as a row, not an exception
      while (rs.next()) {
        if (rs.getString("Msg_type").equalsIgnoreCase("error")) {
          throw new SQLException(
              "the statistics of "
                  + metadataTable(t)
                  + " were not updated: "
                  + rs.getString("Msg_text"));
        }
      }
    }
    return true;
  }

  /**
   * On Oracle and MySQL, a histogram on the metadata table's {@code tx_state}: without one both
   * estimate {@code tx_state IN (1, 2)} as most of the table and scan it where the split read
   * probes the in-flight index (design section 3). On Oracle a fixed one assuming {@link
   * #ORACLE_ASSUMED_INFLIGHT} rows in flight, locked: a gathered one records whatever is in flight
   * at that moment, usually nothing. Not locked on an inline table, whose lock would freeze the
   * application's own statistics; the nightly job may replace it there. MySQL never regathers one
   * by itself, and its plans did not depend on the moment (todo 094). On PostgreSQL an {@code
   * ANALYZE} of the metadata table the backfill has just filled: until autoanalyze reaches it the
   * planner estimates {@code tx_state IN (1, 2)} by defaults (todo 337).
   *
   * @param asIfBackfilled on Oracle, set the statistics as {@link #oracleStatistics} says
   * @return whether one was set or gathered
   */
  private boolean gatherHistogram(TableInfo t, boolean asIfBackfilled) throws SQLException {
    if (engine == Engine.POSTGRESQL) {
      execute("ANALYZE " + name(t.namespace(), metadataTable(t)));
      return true;
    }
    if (planned != null && (engine == Engine.ORACLE || engine == Engine.MYSQL)) {
      planned.add(
          engine == Engine.ORACLE
              ? bound(
                  oracleStatistics(t, asIfBackfilled),
                  oracleLiteral('"' + t.namespace() + '"'),
                  oracleLiteral('"' + metadataTable(t) + '"'),
                  oracleLiteral(txState),
                  String.valueOf(ORACLE_ASSUMED_INFLIGHT),
                  t.layout() == Layout.INLINE ? "0" : "1")
              : "ANALYZE TABLE "
                  + name(t.namespace(), metadataTable(t))
                  + " UPDATE HISTOGRAM ON "
                  + txState
                  + " WITH 16 BUCKETS");
      return true;
    }
    switch (engine) {
      case ORACLE:
        try (PreparedStatement ps =
            connection.prepareStatement(oracleStatistics(t, asIfBackfilled))) {
          ps.setString(1, '"' + t.namespace() + '"');
          ps.setString(2, '"' + metadataTable(t) + '"');
          ps.setString(3, txState);
          ps.setInt(4, ORACLE_ASSUMED_INFLIGHT);
          ps.setInt(5, t.layout() == Layout.INLINE ? 0 : 1);
          ps.execute();
        }
        return true;
      case MYSQL:
        try (Statement st = connection.createStatement();
            ResultSet rs =
                st.executeQuery(
                    "ANALYZE TABLE "
                        + name(t.namespace(), metadataTable(t))
                        + " UPDATE HISTOGRAM ON "
                        + txState
                        + " WITH 16 BUCKETS")) {
          // a failure comes back as a row, not an exception
          while (rs.next()) {
            if (rs.getString("Msg_type").equalsIgnoreCase("error")) {
              throw new SQLException(
                  "the histogram on " + txState + " was not created: " + rs.getString("Msg_text"));
            }
          }
        }
        return true;
      default:
        return false;
    }
  }

  private static String oracleLiteral(String s) {
    return "'" + s.replace("'", "''") + "'";
  }

  /** A statement with its placeholders replaced in order, for the plan of a dry run. */
  private static String bound(String sql, String... values) {
    StringBuilder out = new StringBuilder();
    int from = 0;
    for (String v : values) {
      int at = sql.indexOf('?', from);
      out.append(sql, from, at).append(v);
      from = at + 1;
    }
    return out.append(sql.substring(from)).toString();
  }

  private String name(String schema, String object) {
    return engine.quote(schema) + "." + engine.quote(object);
  }

  private static String metadataTable(TableInfo t) {
    String m = t.metadataTable();
    if (m == null) {
      throw new IllegalStateException(t + " has no metadata table");
    }
    return m;
  }

  /** The key columns, quoted, each with a prefix such as {@code t.}. */
  private String keys(TableInfo t, String prefix) {
    List<String> out = new ArrayList<>();
    for (String k : t.keyColumns()) {
      out.add(prefix + engine.quote(k));
    }
    return String.join(", ", out);
  }

  private String keyJoin(TableInfo t) {
    List<String> on = new ArrayList<>();
    for (String k : t.keyColumns()) {
      on.add("m." + engine.quote(k) + " = t." + engine.quote(k));
    }
    return String.join(" AND ", on);
  }

  /**
   * The committed image of each row (design section 3), as two arms: rows not in flight with their
   * own columns, so a predicate or join on {@code T}'s columns uses {@code T}'s indexes, and the
   * before image of each row in flight that has one, found through the in-flight index. The view
   * runs with the reader's rights where the engine has that: PostgreSQL 15 and later, MySQL and
   * MariaDB.
   */
  private String viewDdl(TableInfo t, String view) throws SQLException {
    List<String> committed = new ArrayList<>();
    List<String> before = new ArrayList<>();
    List<String> keys = t.keyColumns();
    for (String c : t.dataColumns()) {
      String q = engine.quote(c);
      committed.add("t." + q);
      before.add(keys.contains(c) ? "t." + q : t.beforeImage(engine, "m", c) + " AS " + q);
    }
    String data = name(t.namespace(), t.dataTable());
    String meta = name(t.namespace(), metadataTable(t));
    return String.format(
        "%s AS SELECT %s FROM %s t LEFT JOIN %s m ON %s WHERE COALESCE(m.%s, 3) = 3"
            + " UNION ALL SELECT %s FROM %s t JOIN %s m ON %s"
            + " WHERE m.%s IN (1, 2) AND (m.%s IS NOT NULL OR m.%s IS NOT NULL)",
        createView(name(t.namespace(), view)),
        String.join(", ", committed),
        data,
        meta,
        keyJoin(t),
        txState,
        String.join(", ", before),
        data,
        meta,
        keyJoin(t),
        txState,
        engine.quote("before_tx_id"),
        engine.quote("before_tx_version"));
  }

  /**
   * Creates or replaces the read view. PostgreSQL's {@code CREATE OR REPLACE VIEW} keeps each
   * column's type, and a read view of a setup before todo 356 reads a {@code SMALLINT} column as
   * {@code INTEGER}: such a view is dropped and created again in one transaction, which readers of
   * it wait for.
   */
  private void replaceView(TableInfo t, String view, boolean exists) throws SQLException {
    String ddl = viewDdl(t, view);
    if (engine != Engine.POSTGRESQL || !exists || planned != null) {
      execute(ddl);
      return;
    }
    connection.setAutoCommit(false);
    SQLException failed = null;
    try {
      try {
        execute("SAVEPOINT scalardb_native_view");
        execute(ddl);
      } catch (SQLException e) {
        if (!"42P16".equals(e.getSQLState())) { // cannot change a view column's type
          throw e;
        }
        execute("ROLLBACK TO SAVEPOINT scalardb_native_view");
        try {
          execute("DROP VIEW " + name(t.namespace(), view));
        } catch (SQLException d) {
          SQLException out =
              new SQLNonTransientException(
                  "the read view "
                      + t.namespace()
                      + "."
                      + view
                      + " reads a column in another type than the setup now makes it, and"
                      + " replacing it needs it dropped: "
                      + d.getMessage(),
                  d.getSQLState(),
                  d);
          out.addSuppressed(e);
          throw out;
        }
        execute(ddl);
      }
      connection.commit();
    } catch (SQLException e) {
      failed = e;
      try {
        connection.rollback();
      } catch (SQLException r) {
        e.addSuppressed(r);
      }
      throw e;
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException a) {
        if (failed == null) {
          throw a;
        }
        failed.addSuppressed(a);
      }
    }
  }

  private String createView(String view) throws SQLException {
    switch (engine) {
      case POSTGRESQL:
        return "CREATE OR REPLACE VIEW "
            + view
            + (connection.getMetaData().getDatabaseMajorVersion() >= 15
                ? " WITH (security_invoker = true)"
                : "");
      case MYSQL:
      case MARIADB:
        return "CREATE OR REPLACE SQL SECURITY INVOKER VIEW " + view;
      case SQLSERVER:
        return "CREATE OR ALTER VIEW " + view;
      case ORACLE:
        return "CREATE OR REPLACE VIEW " + view;
    }
    throw new AssertionError(engine);
  }

  /** A user or role, quoted; on MySQL and MariaDB an account, {@code user@host} or {@code user}. */
  private String grantee(String g) {
    if (engine == Engine.MYSQL || engine == Engine.MARIADB) {
      int at = g.lastIndexOf('@');
      return at < 0
          ? engine.quote(g)
          : engine.quote(g.substring(0, at)) + "@" + engine.quote(g.substring(at + 1));
    }
    return engine.quote(g);
  }

  /**
   * The index over in-flight rows (design section 3, Indexes), in the engine's form that does not
   * block writes to the metadata table: partial and concurrent on PostgreSQL, filtered and online
   * on SQL Server editions that have online builds, {@code (tx_state, key)} in place on MySQL and
   * MariaDB, online on Oracle where the edition allows.
   */
  private void createIndex(TableInfo t, String index, List<String> key) throws SQLException {
    String on = name(t.namespace(), metadataTable(t));
    String keys = quoted(key);
    switch (engine) {
      case POSTGRESQL:
        // a failed concurrent build leaves an invalid index of the name; the listing omits it
        execute("DROP INDEX CONCURRENTLY IF EXISTS " + name(t.namespace(), index));
        execute(
            String.format(
                "CREATE INDEX CONCURRENTLY %s ON %s (%s) WHERE %s IN (1, 2)",
                engine.quote(index), on, keys, txState));
        return;
      case SQLSERVER:
        // filtered on a metadata table; unfiltered on an inline one, the application's own table,
        // where a filtered index would fail every write from a session without the SET options it
        // needs, QUOTED_IDENTIFIER among them (error 1934, todo 289)
        execute(
            String.format(
                t.layout() == Layout.INLINE
                    ? "CREATE INDEX %1$s ON %2$s (%4$s, %3$s)%5$s"
                    : "CREATE INDEX %1$s ON %2$s (%3$s) WHERE %4$s IN (1, 2)%5$s",
                engine.quote(index),
                on,
                keys,
                txState,
                sqlServerHasOnlineIndexBuilds() ? " WITH (ONLINE = ON)" : ""));
        return;
      case MYSQL:
      case MARIADB:
        execute(
            String.format(
                "CREATE INDEX %s ON %s (%s, %s) ALGORITHM=INPLACE LOCK=NONE",
                engine.quote(index), on, txState, keys));
        return;
      case ORACLE:
        String ddl =
            String.format(
                "CREATE INDEX %s ON %s (%s, %s)", name(t.namespace(), index), on, txState, keys);
        try {
          execute(ddl + " ONLINE");
        } catch (SQLException e) {
          if (e.getErrorCode() != 439) { // ORA-00439: feature not enabled in this edition
            throw e;
          }
          execute(ddl);
        }
        return;
    }
    throw new AssertionError(engine);
  }

  /**
   * Creates the key index on the metadata table, built as the in-flight index is, without blocking
   * writes where the engine can; a stamp changes no key column, so only inserts and deletes
   * maintain it.
   */
  private void createKeyIndex(TableInfo t, String index, List<String> key) throws SQLException {
    String on = name(t.namespace(), metadataTable(t));
    String keys = quoted(key);
    switch (engine) {
      case POSTGRESQL:
        execute("DROP INDEX CONCURRENTLY IF EXISTS " + name(t.namespace(), index));
        execute(
            String.format(
                "CREATE INDEX CONCURRENTLY %s ON %s (%s)", engine.quote(index), on, keys));
        return;
      case SQLSERVER:
        execute(
            String.format(
                "CREATE INDEX %s ON %s (%s)%s",
                engine.quote(index),
                on,
                keys,
                sqlServerHasOnlineIndexBuilds() ? " WITH (ONLINE = ON)" : ""));
        return;
      case MYSQL:
      case MARIADB:
        execute(
            String.format(
                "CREATE INDEX %s ON %s (%s) ALGORITHM=INPLACE LOCK=NONE",
                engine.quote(index), on, keys));
        return;
      case ORACLE:
        String ddl =
            String.format("CREATE INDEX %s ON %s (%s)", name(t.namespace(), index), on, keys);
        try {
          execute(ddl + " ONLINE");
        } catch (SQLException e) {
          if (e.getErrorCode() != 439) { // ORA-00439: feature not enabled in this edition
            throw e;
          }
          execute(ddl);
        }
        return;
    }
    throw new AssertionError(engine);
  }

  private String quoted(List<String> columns) {
    List<String> out = new ArrayList<>();
    for (String c : columns) {
      out.add(engine.quote(c));
    }
    return String.join(", ", out);
  }

  private String dropIndex(TableInfo t, String index) {
    switch (engine) {
      case POSTGRESQL:
      case ORACLE:
        return "DROP INDEX " + name(t.namespace(), index);
      case MYSQL:
      case MARIADB:
      case SQLSERVER:
        return "DROP INDEX " + engine.quote(index) + " ON " + name(t.namespace(), metadataTable(t));
    }
    throw new AssertionError(engine);
  }

  /** Enterprise, Developer and Evaluation (3), Azure SQL Database (5) and Managed Instance (8). */
  private boolean sqlServerHasOnlineIndexBuilds() throws SQLException {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery("SELECT CAST(SERVERPROPERTY('EngineEdition') AS INT)")) {
      if (!rs.next()) {
        return false;
      }
      int edition = rs.getInt(1);
      return edition == 3 || edition == 5 || edition == 8;
    }
  }

  /**
   * The backfill of section 1 on an imported table: a metadata row for every data row without one,
   * and the sentinel stamp on every row whose {@code tx_id} is NULL, repeated until neither remains
   * and no row is in flight over a deemed-committed row. That last kind is ScalarDB's prepare over
   * a row with NULL metadata, which writes {@code before_tx_id} NULL and {@code before_tx_version}
   * 0 ({@code PrepareMutationComposer}); an in-flight insert, with both NULL, is not waited for.
   *
   * @return the number of rows stamped
   */
  // ponytail: one statement per step over the whole table, which on an inline table write-locks the
  // application's own rows until it ends (todo 290); batch by key range for very large tables
  private long backfill(TableInfo t) throws SQLException {
    String data = name(t.namespace(), t.dataTable());
    String meta = name(t.namespace(), metadataTable(t));
    String preparedAt = engine.quote("tx_prepared_at");
    String committedAt = engine.quote("tx_committed_at");
    String missing =
        String.format(
            " FROM %s t WHERE NOT EXISTS (SELECT 1 FROM %s m WHERE %s)", data, meta, keyJoin(t));
    String insert =
        String.format(
            "INSERT INTO %s (%s, %s, %s, %s, %s, %s) SELECT %s, ?, 3, 1, ?, ?%s",
            meta,
            keys(t, ""),
            txId,
            txState,
            txVersion,
            preparedAt,
            committedAt,
            keys(t, "t."),
            missing);
    String update =
        String.format(
            "UPDATE %s SET %s = ?, %s = 3, %s = 1, %s = ?, %s = ? WHERE %s IS NULL",
            meta, txId, txState, txVersion, preparedAt, committedAt, txId);
    String remaining =
        String.format(
            "SELECT (SELECT COUNT(*)%s) + (SELECT COUNT(*) FROM %s WHERE %s IS NULL)"
                + " + (SELECT COUNT(*) FROM %s WHERE %s IN (1, 2) AND %s IS NULL AND %s IS NOT"
                + " NULL)%s",
            missing,
            meta,
            txId,
            meta,
            txState,
            engine.quote("before_tx_id"),
            engine.quote("before_tx_version"),
            engine == Engine.ORACLE ? " FROM dual" : "");
    String inFlight =
        String.format(
            "SELECT COUNT(*) FROM %s WHERE %s IN (1, 2) AND %s IS NULL AND %s IS NOT NULL",
            meta, txState, engine.quote("before_tx_id"), engine.quote("before_tx_version"));
    if (planned != null) {
      long rows = count(remaining);
      planned.add("-- the backfill: " + rows + " rows to stamp now, until none is left");
      if (t.layout() != Layout.INLINE) {
        planned.add(insert);
      }
      planned.add(update);
      return rows;
    }
    long stamped = 0;
    long deadline = System.nanoTime() + backfillWait.toNanos();
    while (true) {
      long now = System.currentTimeMillis();
      // an inline table is its own metadata table: no row lacks one, and SQL Server refuses the
      // insert of an IDENTITY key even for no rows (milestone 9c)
      if (t.layout() != Layout.INLINE) {
        stamped += stamp(insert, now);
      }
      stamped += stamp(update, now);
      long left = count(remaining);
      if (left == 0) {
        return stamped;
      }
      // wait on the in-flight index alone, then one more full pass
      do {
        if (System.nanoTime() > deadline) {
          throw new SQLTransientException(
              "the backfill is not complete: "
                  + left
                  + " rows still have no committed metadata or are in flight over such a row;"
                  + " run the setup again once ScalarDB's transactions on the table have finished",
              "55000");
        }
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new SQLTransientException("the backfill was interrupted", "57014", e);
        }
        left = count(inFlight);
      } while (left > 0);
    }
  }

  /**
   * Runs one backfill step; a row ScalarDB inserted first, or a lock conflict with a transaction,
   * counts as nothing done, and the next pass sees the rest.
   */
  private int stamp(String sql, long now) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, BACKFILL_TX_ID);
      ps.setLong(2, now);
      ps.setLong(3, now);
      return ps.executeUpdate();
    } catch (SQLException e) {
      if (isUniqueViolation(e) || isLockConflict(e)) {
        return 0;
      }
      throw e;
    }
  }

  private boolean isUniqueViolation(SQLException e) {
    switch (engine) {
      case POSTGRESQL:
        return "23505".equals(e.getSQLState());
      case MYSQL:
      case MARIADB:
        return e.getErrorCode() == 1062;
      case SQLSERVER:
        return e.getErrorCode() == 2627 || e.getErrorCode() == 2601;
      case ORACLE:
        return e.getErrorCode() == 1; // ORA-00001
    }
    throw new AssertionError(engine);
  }

  /** A deadlock or lock timeout: serialization failures and each engine's lock wait codes. */
  private boolean isLockConflict(SQLException e) {
    String state = e.getSQLState();
    if ("40001".equals(state) || "40P01".equals(state) || "55P03".equals(state)) {
      return true;
    }
    switch (engine) {
      case MYSQL:
      case MARIADB:
        return e.getErrorCode() == 1205 || e.getErrorCode() == 1213;
      case SQLSERVER:
        return e.getErrorCode() == 1205 || e.getErrorCode() == 1222;
      case ORACLE:
        return e.getErrorCode() == 60; // ORA-00060
      case POSTGRESQL:
        return false;
    }
    throw new AssertionError(engine);
  }

  /**
   * Refuses an imported table keyed by a {@code datetime} (253): ScalarDB's metadata table holds
   * the key as {@code datetime2(3)}, which SQL Server does not equal to a {@code datetime} ending
   * in .003 or .007, so no join of the two finds such a row.
   */
  private void refuseDatetimeKey(TableInfo t) throws SQLException {
    String sql =
        "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND"
            + " table_name = ? AND data_type = 'datetime'";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, t.namespace());
      ps.setString(2, t.dataTable());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String c = rs.getString(1);
          if (t.keyColumns().stream().anyMatch(c::equalsIgnoreCase)) {
            throw new SQLFeatureNotSupportedException(
                "the key column "
                    + c
                    + " is a datetime, which ScalarDB's metadata holds as datetime2(3) and SQL"
                    + " Server does not compare equal; make it datetime2(3)",
                "0A000");
          }
        }
      }
    }
  }

  private long count(String sql) throws SQLException {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** Records what a step did; a dry run has recorded its statements instead. */
  private void done(List<String> actions, String action) {
    if (planned == null) {
      actions.add(action);
    }
  }

  private void execute(String sql) throws SQLException {
    if (planned != null) {
      planned.add(sql);
      return;
    }
    try (Statement st = connection.createStatement()) {
      st.execute(sql);
    }
  }
}
