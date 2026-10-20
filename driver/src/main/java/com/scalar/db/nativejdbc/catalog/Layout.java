package com.scalar.db.nativejdbc.catalog;

/** How a table is stored, which decides whether and how the driver rewrites statements on it. */
public enum Layout {
  /** Not a ScalarDB table: statements pass through unchanged. */
  PLAIN,
  /** A ScalarDB table with the transaction metadata columns in the same row. */
  INLINE,
  /**
   * A table created through ScalarDB with transaction metadata decoupling: the user's name is the
   * INNER-join virtual table, the data lives in {@code <name>_data} and the metadata in {@code
   * <name>_tx_metadata}.
   */
  DECOUPLED_CREATED,
  /**
   * An existing table imported into ScalarDB with transaction metadata decoupling: the user's name
   * is the data table, the metadata lives in {@code <name>_tx_metadata} and the LEFT-OUTER virtual
   * table is {@code <name>_scalardb}.
   */
  DECOUPLED_IMPORTED
}
