package com.scalar.db.nativejdbc;

import java.sql.SQLException;

/**
 * What the driver makes of a table, for tools that list tables, such as {@code snsql}'s {@code
 * \dt}. A connection of this driver implements it; reach it with {@code
 * connection.unwrap(ScalarDbTables.class)}. The answer is the catalog's, the one the driver routes
 * statements by, so a tool need not repeat ScalarDB's naming rules.
 */
public interface ScalarDbTables {

  /** How the driver treats a table. */
  enum Kind {
    /** An ordinary table, or a name the database knows no table for: statements pass unchanged. */
    PLAIN,
    /** A ScalarDB table: the driver rewrites statements on it. */
    SCALARDB,
    /**
     * A ScalarDB table the driver's setup has not been run for: statements on it are refused until
     * it is.
     */
    NOT_SET_UP,
    /**
     * A name the driver refuses in statements: the data, transaction metadata or virtual table that
     * ScalarDB keeps for a ScalarDB table, or an Oracle synonym.
     */
    REFUSED
  }

  /**
   * Classifies a table by its stored name, as {@link java.sql.DatabaseMetaData#getTables} reports
   * it.
   *
   * @param schema the schema; on MySQL and MariaDB, the database
   * @param table the table
   * @return how the driver treats the table
   * @throws SQLException if a catalog cannot be read
   */
  Kind kind(String schema, String table) throws SQLException;
}
