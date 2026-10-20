package com.scalar.db.nativejdbc.setup;

import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final @Nullable SQLException error;

    Result(
        String table,
        boolean setUp,
        long stuck,
        @Nullable Duration oldest,
        @Nullable SQLException error) {
      this.table = table;
      this.setUp = setUp;
      this.stuck = stuck;
      this.oldest = oldest;
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
     * Returns whether the setup has been run for the table.
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
     * Returns the error that stopped the check of this table, if any.
     *
     * @return the error, or null
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "an exception is handed out as is")
    public @Nullable SQLException error() {
      return error;
    }

    /**
     * Returns whether the table is set up, has no stuck rows, and was checked.
     *
     * @return whether all is well
     */
    public boolean ok() {
      return setUp && stuck == 0 && error == null;
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
                  + " s ago");
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
   * and waits on it.
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
          out.add(
              new Result(
                  name,
                  t.isSetUp(),
                  stuck,
                  stuck == 0 ? null : Duration.ofMillis(now - min),
                  null));
        }
      } catch (SQLException e) {
        out.add(new Result(name, t.isSetUp(), 0, null, e));
      }
    }
    return out;
  }
}
