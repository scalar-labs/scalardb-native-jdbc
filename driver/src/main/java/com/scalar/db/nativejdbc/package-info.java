/**
 * ScalarDB Native JDBC: a JDBC driver for a database that holds both ordinary tables and
 * ScalarDB-managed tables.
 *
 * <p>The driver wraps the database's own JDBC driver. Statements on ordinary tables pass through
 * unchanged; statements on ScalarDB-managed tables are rewritten so that they read committed data
 * and leave behind the row metadata that ScalarDB's own transactions check. The design is in {@code
 * docs/design.md}.
 */
@NullMarked
package com.scalar.db.nativejdbc;

import org.jspecify.annotations.NullMarked;
