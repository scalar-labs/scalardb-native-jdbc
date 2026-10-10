package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.engine.Engine;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * What the engine needs to know about one connection's session to resolve a name and read a
 * statement as the engine does: the schemas an unqualified name resolves through, and whether
 * {@code "x"} quotes an identifier. Read at connect and again, lazily, before the next statement
 * after anything that may have changed it (design section 1, "Session state").
 */
final class Session {
  private final Engine engine;
  private List<String> schemas = List.of();
  private boolean doubleQuotesQuoteIdentifiers;
  private boolean stale = true;
  private boolean changedInTransaction;
  private long generation;

  Session(Engine engine) {
    this.engine = engine;
  }

  /** The schemas, read again first if they may have changed. */
  List<String> schemas(Connection vendor) throws SQLException {
    refreshIfStale(vendor);
    return schemas;
  }

  /** The double-quote setting, read again first if it may have changed. */
  boolean doubleQuotesQuoteIdentifiers(Connection vendor) throws SQLException {
    refreshIfStale(vendor);
    return doubleQuotesQuoteIdentifiers;
  }

  /**
   * Marks the session as changed by a statement or a call. Inside a transaction a change may also
   * be undone at its end ({@code SET LOCAL}, a rolled-back {@code SET}), so the end marks it again.
   */
  void changed(Connection vendor) {
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
  }

  /** At commit or rollback: a change made inside the transaction may have been undone. */
  void transactionEnded() {
    if (changedInTransaction) {
      markStale();
      changedInTransaction = false;
    }
  }

  /**
   * At a rollback to a savepoint: a change made after the savepoint is undone, one made before it
   * holds until the transaction ends, so the transaction's end must still mark the session.
   */
  void rolledBackToSavepoint() {
    if (changedInTransaction) {
      markStale();
    }
  }

  /** Moves on whenever the session state may have changed; a route records the one it saw. */
  long generation() {
    return generation;
  }

  private void markStale() {
    stale = true;
    generation++;
  }

  private void refreshIfStale(Connection vendor) throws SQLException {
    if (stale) {
      schemas = engine.sessionSchemas(vendor);
      doubleQuotesQuoteIdentifiers = engine.doubleQuotesQuoteIdentifiers(vendor);
      stale = false;
    }
  }
}
