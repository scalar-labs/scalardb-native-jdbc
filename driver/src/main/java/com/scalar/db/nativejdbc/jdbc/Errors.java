package com.scalar.db.nativejdbc.jdbc;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientException;

/** The driver's own errors, as design section 1, "Errors", fixes them. */
final class Errors {

  private Errors() {}

  /**
   * A statement or setting the driver does not handle: 0AN01, never retried. Not 0A000, which
   * HikariCP takes for a broken connection and evicts.
   */
  static SQLFeatureNotSupportedException unsupported(String reason) {
    return new SQLFeatureNotSupportedException(reason, "0AN01");
  }

  /**
   * A ScalarDB table the driver's setup has not been run for: 55000, object not in prerequisite
   * state. Not 08004: it is raised when a statement names the table, and an 08 state there makes a
   * pool discard a working connection.
   */
  static SQLNonTransientException notSetUp(String table) {
    return new SQLNonTransientException(
        table
            + " is not set up for the driver: its read view or in-flight index is missing or not"
            + " visible to this user; run the driver's setup for this database, granting this user"
            + " SELECT on the read view",
        "55000");
  }
}
