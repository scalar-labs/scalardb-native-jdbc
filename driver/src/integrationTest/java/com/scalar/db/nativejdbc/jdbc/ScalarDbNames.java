package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.api.Result;
import com.scalar.db.io.BigIntColumn;
import com.scalar.db.io.Column;
import com.scalar.db.io.DataType;
import com.scalar.db.io.IntColumn;
import com.scalar.db.io.Key;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.testing.Engines;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Names and integers as the write tests give them to ScalarDB, on the engine of the test running.
 * On Oracle the tests' unquoted SQL names fold to upper case, so ScalarDB is given them so; the
 * names ScalarDB adds itself, a metadata table's suffix and its {@code tx_} columns, stay lower
 * case and are quoted in SQL; and ScalarDB imports an integer {@code NUMBER} as BIGINT only, so the
 * integers of imported tables, and of the created tables the tests use alike, are BIGINT there.
 */
final class ScalarDbNames {

  private static boolean oracle;

  private ScalarDbNames() {}

  /** Sets the engine of the test running. */
  static void on(Engines e) {
    oracle = e.engine() == Engine.ORACLE;
  }

  /** A name as ScalarDB stores it for the unquoted name the test's SQL writes. */
  static String N(String name) {
    return oracle ? name.toUpperCase(Locale.ROOT) : name;
  }

  /** A name ScalarDB stored as given, quoted on Oracle, where the SQL would fold it. */
  static String Q(@Nullable String name) {
    return oracle ? "\"" + name + "\"" : String.valueOf(name);
  }

  /** SQL naming ScalarDB's metadata columns, which it stores lower case, quoted on Oracle. */
  static String M(String sql) {
    return oracle ? sql.replaceAll("\\b((?:before_)?tx_\\w+)", "\"$1\"") : sql;
  }

  /** The key {@code id}. */
  static Key id(int id) {
    return key("id", id);
  }

  /** An integer key of column {@code column}. */
  static Key key(String column, int value) {
    return oracle ? Key.ofBigInt(N(column), value) : Key.ofInt(column, value);
  }

  /** An integer column's value. */
  static Column<?> intColumn(String column, int value) {
    return oracle ? BigIntColumn.of(N(column), value) : IntColumn.of(column, value);
  }

  /** An integer column's value in a result. */
  static int intOf(Result r, String column) {
    return oracle ? (int) r.getBigInt(N(column)) : r.getInt(column);
  }

  /** The type of an integer column of a table ScalarDB creates, alike with an imported one's. */
  static DataType intType() {
    return oracle ? DataType.BIGINT : DataType.INT;
  }
}
