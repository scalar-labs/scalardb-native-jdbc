package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.engine.Engine;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * What the engine needs to know about one connection's session to resolve a name and read a
 * statement as the engine does: the schemas an unqualified name resolves through, and whether
 * {@code "x"} quotes an identifier. Read at connect and again, lazily, before the next statement
 * after anything that may have changed it (design section 1, "Session state").
 */
final class Session {
  private final Engine engine;
  private final ReentrantLock lock = new ReentrantLock();
  private List<String> schemas = List.of();
  private boolean doubleQuotesQuoteIdentifiers;
  // SQL Server: the database the connection was opened in, the only one it may use
  private @Nullable String database;
  private boolean stale = true;
  private boolean changedInTransaction;
  private long generation;

  Session(Engine engine) {
    this.engine = engine;
  }

  /** The schemas, read again first if they may have changed. */
  List<String> schemas(Connection vendor) throws SQLException {
    lock.lock();
    try {
      refreshIfStale(vendor);
      return schemas;
    } finally {
      lock.unlock();
    }
  }

  /** The double-quote setting, read again first if it may have changed. */
  boolean doubleQuotesQuoteIdentifiers(Connection vendor) throws SQLException {
    lock.lock();
    try {
      refreshIfStale(vendor);
      return doubleQuotesQuoteIdentifiers;
    } finally {
      lock.unlock();
    }
  }

  /** The double-quote setting as last read, which a statement since may have changed. */
  boolean doubleQuotesQuoteIdentifiersAsLastRead() {
    lock.lock();
    try {
      return doubleQuotesQuoteIdentifiers;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Marks the session as changed by a statement or a call. Inside a transaction a change may also
   * be undone at its end ({@code SET LOCAL}, a rolled-back {@code SET}), so the end marks it again.
   */
  void changed(Connection vendor) {
    lock.lock();
    try {
      markStale();
      boolean autoCommit;
      try {
        autoCommit = vendor.getAutoCommit();
      } catch (SQLException e) {
        autoCommit = false; // unknown: assume a transaction, so its end marks the session again
      }
      if (!autoCommit) {
        changedInTransaction = true;
      }
    } finally {
      lock.unlock();
    }
  }

  /** At commit or rollback: a change made inside the transaction may have been undone. */
  void transactionEnded() {
    lock.lock();
    try {
      if (changedInTransaction) {
        markStale();
        changedInTransaction = false;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * At a rollback to a savepoint: a change made after the savepoint is undone, one made before it
   * holds until the transaction ends, so the transaction's end must still mark the session.
   */
  void rolledBackToSavepoint() {
    lock.lock();
    try {
      if (changedInTransaction) {
        markStale();
      }
    } finally {
      lock.unlock();
    }
  }

  /** Moves on whenever the session state may have changed; a route records the one it saw. */
  long generation() {
    lock.lock();
    try {
      return generation;
    } finally {
      lock.unlock();
    }
  }

  private void markStale() {
    stale = true;
    generation++;
  }

  /**
   * On SQL Server, as SNAPSHOT is set: the database must allow snapshot isolation, or the first
   * statement fails (design 5).
   *
   * @throws SQLException 55000 if it does not
   */
  void requireSnapshotIsolation(Connection vendor) throws SQLException {
    try (Statement st = vendor.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT name, snapshot_isolation_state FROM sys.databases"
                    + " WHERE database_id = DB_ID()")) {
      if (rs.next() && rs.getInt(2) != 1) {
        throw new SQLNonTransientException(
            "database "
                + rs.getString(1)
                + " does not allow snapshot isolation, which REPEATABLE READ runs at on SQL"
                + " Server: ALTER DATABASE "
                + rs.getString(1)
                + " SET ALLOW_SNAPSHOT_ISOLATION ON",
            "55000");
      }
    }
  }

  /**
   * On SQL Server, at connect: the database must have {@code READ_COMMITTED_SNAPSHOT} on, without
   * which a read at READ COMMITTED takes no statement snapshot and can pair a new data row with an
   * old metadata row (design section 1). The connection then stays in that database: the catalog
   * and this check are for it alone. {@code XACT_ABORT} must be off, as a session starts.
   *
   * @throws SQLException 08004 if the option is off, or XACT_ABORT on
   */
  void requireReadCommittedSnapshot(Connection vendor) throws SQLException {
    lock.lock();
    try {
      try (Statement st = vendor.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT name, is_read_committed_snapshot_on FROM sys.databases"
                      + " WHERE database_id = DB_ID()")) {
        if (!rs.next()) {
          throw new SQLNonTransientConnectionException(
              "the current database is not visible in sys.databases", "08004");
        }
        String name = rs.getString(1);
        if (!rs.getBoolean(2)) {
          throw new SQLNonTransientConnectionException(
              "database "
                  + name
                  + " has READ_COMMITTED_SNAPSHOT off, which the driver needs for consistent reads of"
                  + " ScalarDB tables: ALTER DATABASE "
                  + name
                  + " SET READ_COMMITTED_SNAPSHOT ON",
              "08004");
        }
        database = name;
      }
      // a failed statement would end the transaction, which the driver's writes undo to their
      // start and go on from (design section 1)
      try (Statement st = vendor.createStatement();
          ResultSet rs = st.executeQuery("SELECT @@OPTIONS & 16384")) {
        if (rs.next() && rs.getInt(1) != 0) {
          throw new SQLNonTransientConnectionException(
              "XACT_ABORT is on for the session, which the driver needs off: SET XACT_ABORT OFF,"
                  + " or take it out of the server's user options",
              "08004");
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The database a SQL Server connection must stay in, or {@code null} on other engines.
   *
   * @return the database
   */
  @Nullable String database() {
    lock.lock();
    try {
      return database;
    } finally {
      lock.unlock();
    }
  }

  private void refreshIfStale(Connection vendor) throws SQLException {
    if (stale) {
      if (database != null) {
        // a switch through the passthrough setting, which the driver does not read
        try (Statement st = vendor.createStatement();
            ResultSet rs = st.executeQuery("SELECT DB_NAME()")) {
          String current = rs.next() ? rs.getString(1) : null;
          if (!database.equals(current)) {
            throw Errors.unsupported(
                "the connection is in database "
                    + current
                    + " but was opened in "
                    + database
                    + "; switch back with USE "
                    + database
                    + ", or open a connection to that database");
          }
        }
      }
      schemas = engine.sessionSchemas(vendor);
      doubleQuotesQuoteIdentifiers = engine.doubleQuotesQuoteIdentifiers(vendor);
      stale = false;
    }
  }
}
