package com.scalar.db.nativejdbc.jdbc;

import java.sql.SQLFeatureNotSupportedException;

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
}
