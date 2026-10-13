package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The split read of design section 4.2: a read of ScalarDB tables repeated once per combination of
 * their copies, committed or in flight, each table of a copy replaced by a derived table that holds
 * only that copy's rows, and the copies joined by UNION ALL. The forms are the ones the
 * read-overhead benchmark chose per engine ({@code docs/benchmarks/read-overhead.md}).
 */
// table references are keyed by identity, as SqlAst keys them
@SuppressWarnings("IdentityHashMapUsage")
final class SplitRead {

  /** At most this many tables are split, 16 copies; a statement with more is read through rc_T. */
  static final int MAX_TABLES = 4;

  /** How a committed copy of a table excludes its rows in flight. */
  private enum Check {
    /** {@code NOT EXISTS} on the in-flight index: PostgreSQL, Oracle. */
    NOT_EXISTS,
    /**
     * A left join to the DISTINCT keys in flight, built once instead of probed per row: MySQL,
     * MariaDB, which also materializes a derived table holding {@code NOT EXISTS}.
     */
    DISTINCT_SET,
    /**
     * A left join on the metadata table's key: SQL Server's copies with a table in flight, and on
     * MySQL and MariaDB a table reached through an index with many rows in flight.
     */
    LEFT_JOIN
  }

  private SplitRead() {}

  /**
   * Emits the split read of a statement, or returns null when it is not split.
   *
   * @param engine the engine
   * @param ast the statement
   * @param scalarDb its ScalarDB table references, each with its table
   * @param views the read view of each ScalarDB reference, already quoted, for those not split
   * @param perRow the references whose committed copies exclude their rows in flight row by row, on
   *     MySQL and MariaDB
   * @return the split read, or {@code null} to read through the views
   */
  static SqlAst.@Nullable SplitText emit(
      Engine engine,
      SqlAst ast,
      Map<SqlAst.TableRef, TableInfo> scalarDb,
      Map<SqlAst.TableRef, String> views,
      Set<SqlAst.TableRef> perRow) {
    List<SqlAst.TableRef> split = new ArrayList<>();
    for (SqlAst.TableRef r : ast.splittableRefs()) {
      if (scalarDb.containsKey(r)) {
        split.add(r);
      }
    }
    if (split.isEmpty() || split.size() > MAX_TABLES) {
      return null;
    }
    List<List<String>> read = new ArrayList<>();
    for (SqlAst.TableRef r : split) {
      read.add(columnsRead(ast, r, Objects.requireNonNull(scalarDb.get(r))));
    }
    // MySQL builds the DISTINCT set, and NOT EXISTS's anti-join, whole before the first row, so
    // a copy that stops after a few rows has to check its rows one by one (todo 089)
    // when it reads up to a thousand rows; past that, row by row loses to the set (todo 112)
    boolean rowByRowUnderLimit = engine == Engine.MYSQL && ast.limitsEachCopyTo(1000);
    List<SqlAst.Naming> copies = new ArrayList<>();
    for (int bits = 0; bits < 1 << split.size(); bits++) {
      Map<SqlAst.TableRef, String> naming = new IdentityHashMap<>(views);
      for (int i = 0; i < split.size(); i++) {
        SqlAst.TableRef r = split.get(i);
        TableInfo t = Objects.requireNonNull(scalarDb.get(r));
        List<String> cols = read.get(i);
        boolean inFlight = (bits & 1 << i) != 0;
        naming.put(
            r,
            inFlight
                ? inFlight(engine, t, cols)
                : committed(
                    engine,
                    t,
                    cols,
                    perRow.contains(r) || rowByRowUnderLimit
                        ? Check.LEFT_JOIN
                        : check(engine, split.size(), bits)));
      }
      copies.add(naming::get);
    }
    Map<SqlAst.TableRef, List<String>> columns = new IdentityHashMap<>();
    for (Map.Entry<SqlAst.TableRef, TableInfo> e : scalarDb.entrySet()) {
      columns.put(e.getKey(), e.getValue().dataColumns());
    }
    return ast.emitSplit(copies, columns);
  }

  /**
   * The table's columns the statement may read, in the table's order; its first key column when it
   * reads none, since a derived table needs one.
   */
  private static List<String> columnsRead(SqlAst ast, SqlAst.TableRef r, TableInfo t) {
    Set<String> names = ast.columnsReadFrom(r);
    if (names == null) {
      return t.dataColumns();
    }
    List<String> out = new ArrayList<>();
    for (String c : t.dataColumns()) {
      if (names.contains(c.toLowerCase(Locale.ROOT))) {
        out.add(c);
      }
    }
    return out.isEmpty() ? List.of(t.keyColumns().get(0)) : out;
  }

  private static Check check(Engine engine, int tables, int bits) {
    switch (engine) {
      case POSTGRESQL:
      case ORACLE:
        return Check.NOT_EXISTS;
      case MYSQL:
      case MARIADB:
        return Check.DISTINCT_SET;
      case SQLSERVER:
        // in a copy with a table in flight SQL Server plans NOT EXISTS as if that table were
        // empty and probes the in-flight index once per row
        if (bits != 0) {
          return Check.LEFT_JOIN;
        }
        return tables <= 2 ? Check.NOT_EXISTS : Check.DISTINCT_SET;
    }
    throw new AssertionError(engine);
  }

  /** The table's rows not in flight, with the columns {@code read} of its own. */
  private static String committed(Engine engine, TableInfo t, List<String> read, Check check) {
    String txState = engine.quote("tx_state");
    StringBuilder sql = new StringBuilder("(SELECT ");
    List<String> cols = new ArrayList<>();
    for (String c : read) {
      cols.add("t." + engine.quote(c));
    }
    sql.append(String.join(", ", cols)).append(" FROM ").append(data(engine, t)).append(" t");
    switch (check) {
      case NOT_EXISTS:
        sql.append(" WHERE NOT EXISTS (SELECT 1 FROM ")
            .append(meta(engine, t))
            .append(" q WHERE ")
            .append(keyJoin(engine, t, "q"))
            .append(" AND q.")
            .append(txState)
            .append(" IN (1, 2))");
        break;
      case DISTINCT_SET:
        {
          List<String> keys = new ArrayList<>();
          for (String k : t.keyColumns()) {
            keys.add("q." + engine.quote(k));
          }
          sql.append(" LEFT JOIN (SELECT DISTINCT ")
              .append(String.join(", ", keys))
              .append(" FROM ")
              .append(meta(engine, t))
              .append(" q WHERE q.")
              .append(txState)
              .append(" IN (1, 2)) f ON ")
              .append(keyJoin(engine, t, "f"))
              .append(" WHERE f.")
              .append(engine.quote(t.keyColumns().get(0)))
              .append(" IS NULL");
          break;
        }
      case LEFT_JOIN:
        sql.append(" LEFT JOIN ")
            .append(meta(engine, t))
            .append(" m ON ")
            .append(keyJoin(engine, t, "m"))
            .append(" WHERE COALESCE(m.")
            .append(txState)
            .append(", 3) = 3");
        break;
    }
    return sql.append(")").toString();
  }

  /** The before image of each of the table's rows in flight that has one, columns {@code read}. */
  private static String inFlight(Engine engine, TableInfo t, List<String> read) {
    List<String> cols = new ArrayList<>();
    List<String> keys = t.keyColumns();
    for (String c : read) {
      String q = engine.quote(c);
      cols.add(keys.contains(c) ? "t." + q : "m." + engine.quote("before_" + c) + " AS " + q);
    }
    return "(SELECT "
        + String.join(", ", cols)
        + " FROM "
        + data(engine, t)
        + " t JOIN "
        + meta(engine, t)
        + " m ON "
        + keyJoin(engine, t, "m")
        + " WHERE m."
        + engine.quote("tx_state")
        + " IN (1, 2) AND (m."
        + engine.quote("before_tx_id")
        + " IS NOT NULL OR m."
        + engine.quote("before_tx_version")
        + " IS NOT NULL))";
  }

  private static String data(Engine engine, TableInfo t) {
    return engine.quote(t.namespace()) + "." + engine.quote(t.dataTable());
  }

  private static String meta(Engine engine, TableInfo t) {
    return engine.quote(t.namespace())
        + "."
        + engine.quote(Objects.requireNonNull(t.metadataTable(), "metadata table"));
  }

  private static String keyJoin(Engine engine, TableInfo t, String alias) {
    List<String> on = new ArrayList<>();
    for (String k : t.keyColumns()) {
      String q = engine.quote(k);
      on.add(alias + "." + q + " = t." + q);
    }
    return String.join(" AND ", on);
  }
}
