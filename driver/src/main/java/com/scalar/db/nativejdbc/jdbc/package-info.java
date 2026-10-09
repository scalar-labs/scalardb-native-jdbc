/**
 * The JDBC objects the driver hands out: proxies over the vendor driver's connection, statements,
 * result sets and metadata, which route every statement through the parser and the catalog before
 * the vendor driver sees it (docs/design.md, section 1 "Architecture" and section 4.1).
 */
@NullMarked
package com.scalar.db.nativejdbc.jdbc;

import org.jspecify.annotations.NullMarked;
