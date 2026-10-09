/**
 * What differs between the database engines the driver speaks to: how an identifier folds, compares
 * and is quoted, how the engine resolves a table name, and how its errors read. Pure {@code
 * java.sql}; nothing here knows about ScalarDB or the SQL parser.
 */
@NullMarked
package com.scalar.db.nativejdbc.engine;

import org.jspecify.annotations.NullMarked;
