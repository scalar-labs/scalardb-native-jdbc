package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Layout;
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
import java.util.function.BiFunction;
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

  /**
   * At most this many statements are joined, 16, four inner tables; a statement with more is read
   * through rc_T.
   */
  static final int MAX_COPIES = 16;

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
    LEFT_JOIN,
    /**
     * {@code NOT EXISTS} seeking the in-flight index row by row, {@code FORCESEEK}: SQL Server's
     * committed copy of a table with rows in flight. Compiled then, a plan estimates the index's
     * rows at that moment, whatever its statistics, and scans it per row through the ghost records
     * the writers leave (EC2, sixteen writers: 13-26 reads a second, against 189-271 seeking);
     * forced always, it costs a large read 3x (docs/benchmarks/inflight-check.md).
     */
    SEEK,
    /**
     * The row's state read by a scalar subquery, a condition on the row itself: where a locking
     * read locks each row as it reads it, MySQL and MariaDB release the lock of a row in flight it
     * rejects, which they keep for a row a join rejects.
     */
    SCALAR,
    /**
     * None: every row as it is, those in flight with their after images, which then carry their
     * state and abort the read (SNAPSHOT and SERIALIZABLE).
     */
    NONE,
    /**
     * The row's own state, a condition on the row itself: a table in the inline layout, whose
     * metadata is in the row (todo 329).
     */
    OWN
  }

  /** The label of a copy's own version column {@code i} (todo 329). */
  static final String OWN = "scalardb_native_v";

  private SplitRead() {}

  /**
   * Emits the split read of a statement, or returns null when it is not split.
   *
   * @param engine the engine
   * @param ast the statement
   * @param split its references split, from {@link SqlAst#split}
   * @param scalarDb its ScalarDB table references, each with its table
   * @param views the read view of each ScalarDB reference, already quoted, for those not split
   * @param perRow the references whose committed copies exclude their rows in flight row by row, on
   *     MySQL and MariaDB by a left join on the key, on SQL Server by a forced seek
   * @param anchored the references whose rows carry their versions, which read their keys too
   * @param extras what each copy appends to the select list for each of {@code anchored}, given its
   *     piece there
   * @param committedOnly whether to emit the committed copy alone, as a locking read takes it
   * @param raw whether the committed copy keeps the rows in flight, as at SNAPSHOT and
   *     SERIALIZABLE, where the states {@code extras} read abort on them
   * @param lock the locking clause the committed copy carries itself, or {@code null}: MySQL and
   *     MariaDB lock no row a derived table reads for a clause outside it
   * @return the split read, or {@code null} to read through the views
   */
  static SqlAst.@Nullable SplitText emit(
      Engine engine,
      SqlAst ast,
      SqlAst.Split split,
      Map<SqlAst.TableRef, TableInfo> scalarDb,
      Map<SqlAst.TableRef, String> views,
      Set<SqlAst.TableRef> perRow,
      List<SqlAst.TableRef> anchored,
      BiFunction<SqlAst.TableRef, SqlAst.Piece, List<String>> extras,
      boolean committedOnly,
      boolean raw,
      @Nullable String lock) {
    return emit(
        engine,
        ast,
        split,
        scalarDb,
        views,
        perRow,
        anchored,
        extras,
        committedOnly,
        raw,
        lock,
        Set.of(),
        false);
  }

  /**
   * As {@link #emit(Engine, SqlAst, SqlAst.Split, Map, Map, Set, List, BiFunction, boolean,
   * boolean, String)}, with the copies of {@code own} carrying the version of each row as columns
   * of their own, {@code scalardb_native_v0} and {@code v1}, the committed copy's {@code tx_id} and
   * {@code tx_version}, the in-flight copy's before image's, and with {@code state} the row's own
   * state as {@code v2}, in place of lookups per row (todo 329). A table in the inline layout reads
   * them from the row, and its committed copy keeps the rows whose own state is not in flight; a
   * table in the decoupled layout reads them by a left join to its metadata table, kept likewise. A
   * {@code *} over such a copy is spelled out as the table's columns, which it would otherwise
   * show.
   */
  static SqlAst.@Nullable SplitText emit(
      Engine engine,
      SqlAst ast,
      SqlAst.Split split,
      Map<SqlAst.TableRef, TableInfo> scalarDb,
      Map<SqlAst.TableRef, String> views,
      Set<SqlAst.TableRef> perRow,
      List<SqlAst.TableRef> anchored,
      BiFunction<SqlAst.TableRef, SqlAst.Piece, List<String>> extras,
      boolean committedOnly,
      boolean raw,
      @Nullable String lock,
      Set<SqlAst.TableRef> own,
      boolean state) {
    Map<SqlAst.TableRef, SqlAst.Place> places = split.places();
    List<SqlAst.TableRef> refs = new ArrayList<>();
    List<List<SqlAst.Piece>> pieces = new ArrayList<>();
    int count = 1;
    for (Map.Entry<SqlAst.TableRef, SqlAst.Place> e : places.entrySet()) {
      List<SqlAst.Piece> of;
      switch (e.getValue()) {
        case NULLABLE:
          of = List.of(SqlAst.Piece.COMMITTED, SqlAst.Piece.IN_FLIGHT, SqlAst.Piece.UNMATCHED);
          break;
        case IN_EXPRESSION:
          continue; // read over both pieces inside every copy
        default:
          of = List.of(SqlAst.Piece.COMMITTED, SqlAst.Piece.IN_FLIGHT);
      }
      if (committedOnly) {
        of = List.of(SqlAst.Piece.COMMITTED);
      }
      refs.add(e.getKey());
      pieces.add(of);
      count *= of.size();
      if (count > MAX_COPIES) {
        return null;
      }
    }
    if (places.isEmpty()) {
      return null;
    }
    Map<SqlAst.TableRef, List<String>> read = new IdentityHashMap<>();
    for (SqlAst.TableRef r : places.keySet()) {
      TableInfo t = Objects.requireNonNull(scalarDb.get(r));
      List<String> cols = columnsRead(ast, r, t);
      if (anchored.contains(r)) {
        // the key, which the copies' versions are read by and the anchors keyed on
        List<String> withKey = new ArrayList<>();
        for (String c : t.dataColumns()) {
          if (cols.contains(c) || t.keyColumns().contains(c)) {
            withKey.add(c);
          }
        }
        cols = withKey;
      }
      read.put(r, cols);
    }
    // MySQL builds the DISTINCT set, and NOT EXISTS's anti-join, whole before the first row, so
    // a copy that stops after a few rows has to check its rows one by one (todo 089)
    // when it reads up to a thousand rows; past that, row by row loses to the set (todo 112)
    boolean rowByRowUnderLimit = engine == Engine.MYSQL && ast.limitsEachCopyTo(1000);
    List<SqlAst.Copy> copies = new ArrayList<>();
    for (int n = 0; n < count; n++) {
      Map<SqlAst.TableRef, SqlAst.Piece> of = new IdentityHashMap<>();
      boolean anyInFlight = false;
      for (int i = 0, rest = n; i < refs.size(); i++) {
        int size = pieces.get(i).size();
        SqlAst.Piece p = pieces.get(i).get(rest % size);
        rest /= size;
        of.put(refs.get(i), p);
        anyInFlight |= p != SqlAst.Piece.COMMITTED;
      }
      Check inCopy = check(engine, places.size(), anyInFlight);
      copies.add(
          new SqlAst.Copy() {
            @Override
            public @Nullable String replacementFor(SqlAst.TableRef ref) {
              return places.containsKey(ref)
                  ? fragment(ref, of.getOrDefault(ref, SqlAst.Piece.COMMITTED))
                  : views.get(ref);
            }

            @Override
            public SqlAst.Piece piece(SqlAst.TableRef ref) {
              return of.getOrDefault(ref, SqlAst.Piece.COMMITTED);
            }

            @Override
            public List<String> selectExtra() {
              List<String> out = new ArrayList<>();
              for (SqlAst.TableRef r : anchored) {
                out.addAll(extras.apply(r, of.getOrDefault(r, SqlAst.Piece.COMMITTED)));
              }
              return out;
            }

            @Override
            public boolean exposesColumns() {
              return !own.isEmpty();
            }

            @Override
            public String fragment(SqlAst.TableRef ref, SqlAst.Piece piece) {
              TableInfo t = Objects.requireNonNull(scalarDb.get(ref));
              List<String> cols = Objects.requireNonNull(read.get(ref));
              // the copy's own version columns (todo 329): none, or the row's, or its before
              // image's, and the row's own state with them
              Versions v =
                  own.contains(ref) ? new Versions(t.layout() == Layout.INLINE, state) : null;
              switch (piece) {
                case IN_FLIGHT:
                  return inFlight(engine, t, cols, false, v);
                case UNMATCHED:
                  return none(engine, t, cols, v);
                default:
                  return committed(
                      engine,
                      t,
                      cols,
                      raw
                          ? Check.NONE
                          : v != null
                              ? v.inline ? Check.OWN : Check.LEFT_JOIN
                              : lock != null
                                  ? Check.SCALAR
                                  : perRow.contains(ref) || rowByRowUnderLimit
                                      ? engine != Engine.SQLSERVER
                                          ? Check.LEFT_JOIN
                                          : inCopy == Check.LEFT_JOIN ? inCopy : Check.SEEK
                                      : engine == Engine.POSTGRESQL && t.layout() == Layout.INLINE
                                          ? Check.OWN // the row's own state, no probe per row
                                          : inCopy,
                      lock,
                      v);
              }
            }
          });
    }
    Map<SqlAst.TableRef, List<String>> columns = new IdentityHashMap<>();
    for (Map.Entry<SqlAst.TableRef, TableInfo> e : scalarDb.entrySet()) {
      columns.put(e.getKey(), e.getValue().dataColumns());
    }
    return ast.emitSplit(split, copies, columns);
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

  private static Check check(Engine engine, int tables, boolean anyInFlight) {
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
        if (anyInFlight) {
          return Check.LEFT_JOIN;
        }
        return tables <= 2 ? Check.NOT_EXISTS : Check.DISTINCT_SET;
    }
    throw new AssertionError(engine);
  }

  /**
   * A copy's own version columns (todo 329): whether the table is inline, whose row holds them, and
   * whether the row's own state comes as a third.
   */
  private static final class Versions {
    final boolean inline;
    final boolean state;

    Versions(boolean inline, boolean state) {
      this.inline = inline;
      this.state = state;
    }

    /**
     * The columns as select items, after a comma: those of the row of {@code alias}, or of its
     * before image with {@code before}.
     */
    String items(Engine engine, String alias, boolean before) {
      String prefix = before ? "before_" : "";
      String out =
          ", "
              + alias
              + "."
              + engine.quote(prefix + "tx_id")
              + " AS "
              + engine.quote(OWN + 0)
              + ", "
              + alias
              + "."
              + engine.quote(prefix + "tx_version")
              + " AS "
              + engine.quote(OWN + 1);
      return state
          ? out + ", " + alias + "." + engine.quote("tx_state") + " AS " + engine.quote(OWN + 2)
          : out;
    }
  }

  /** The table's rows not in flight, with the columns {@code read} of its own. */
  private static String committed(
      Engine engine,
      TableInfo t,
      List<String> read,
      Check check,
      @Nullable String lock,
      @Nullable Versions v) {
    String txState = engine.quote("tx_state");
    StringBuilder sql = new StringBuilder("(SELECT ");
    List<String> cols = new ArrayList<>();
    for (String c : read) {
      cols.add("t." + engine.quote(c));
    }
    sql.append(String.join(", ", cols));
    if (v != null) {
      // the inline row's own, else the metadata row's, which the left join below exposes as m
      sql.append(v.items(engine, v.inline ? "t" : "m", false));
    }
    sql.append(" FROM ").append(data(engine, t)).append(" t");
    if (lock != null && engine == Engine.SQLSERVER) {
      sql.append(" ").append(lock); // a table hint there
    }
    switch (check) {
      case NOT_EXISTS:
      case SEEK:
        sql.append(" WHERE NOT EXISTS (SELECT 1 FROM ")
            .append(meta(engine, t))
            .append(check == Check.SEEK ? " q WITH (FORCESEEK) WHERE " : " q WHERE ")
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
        // with the copy's own versions, every state but in flight, as NOT EXISTS keeps them
        sql.append(" LEFT JOIN ")
            .append(meta(engine, t))
            .append(" m ON ")
            .append(keyJoin(engine, t, "m"))
            .append(" WHERE COALESCE(m.")
            .append(txState)
            .append(v != null ? ", 3) NOT IN (1, 2)" : ", 3) = 3");
        break;
      case SCALAR:
        sql.append(" WHERE COALESCE((SELECT q.")
            .append(txState)
            .append(" FROM ")
            .append(meta(engine, t))
            .append(" q WHERE ")
            .append(keyJoin(engine, t, "q"))
            .append("), 3) NOT IN (1, 2)");
        break;
      case OWN:
        sql.append(" WHERE COALESCE(t.").append(txState).append(", 3) NOT IN (1, 2)");
        break;
      case NONE:
        if (v != null && !v.inline) {
          // the versions of a decoupled table's rows as they are, every row kept
          sql.append(" LEFT JOIN ")
              .append(meta(engine, t))
              .append(" m ON ")
              .append(keyJoin(engine, t, "m"));
        }
        break;
    }
    if (lock != null && engine != Engine.SQLSERVER) {
      sql.append(" ").append(lock);
    }
    return sql.append(")").toString();
  }

  /** No row, with the columns {@code read} of the table and their types. */
  private static String none(Engine engine, TableInfo t, List<String> read, @Nullable Versions v) {
    String from = data(engine, t) + " t";
    if (v != null && !v.inline) {
      from += " LEFT JOIN " + meta(engine, t) + " m ON " + keyJoin(engine, t, "m");
    }
    return "(SELECT "
        + columns(engine, read)
        + (v == null ? "" : v.items(engine, v.inline ? "t" : "m", false))
        + " FROM "
        + from
        + " WHERE 1 = 0)";
  }

  /** The columns {@code read} of the data table, aliased {@code t}. */
  private static String columns(Engine engine, List<String> read) {
    List<String> cols = new ArrayList<>();
    for (String c : read) {
      cols.add("t." + engine.quote(c));
    }
    return String.join(", ", cols);
  }

  /**
   * The before image of each of the table's rows in flight that has one, columns {@code read}: the
   * metadata table alone, which holds the key too, so a predicate on the image can start from its
   * index (joined with the data table, Oracle 19c without statistics probed it once per row).
   */
  static String inFlight(Engine engine, TableInfo t, List<String> read) {
    return inFlight(engine, t, read, false);
  }

  /**
   * As {@link #inFlight(Engine, TableInfo, List)}; with {@code hinted} on Oracle and SQL Server,
   * the metadata table read through the in-flight index. Joined with another table's data table,
   * Oracle 19c costs probing the before image's index once per row of that table as free, the index
   * holding next to nothing, and probes it 10,000 times; SQL Server plans from statistics taken
   * with few rows in flight and scans the metadata table (design 4.2, the in-flight pairs). A
   * forced seek fails where no index gives one (error 8622).
   */
  static String inFlight(Engine engine, TableInfo t, List<String> read, boolean hinted) {
    return inFlight(engine, t, read, hinted, null);
  }

  /** As {@link #inFlight(Engine, TableInfo, List, boolean)}, with the copy's own versions. */
  private static String inFlight(
      Engine engine, TableInfo t, List<String> read, boolean hinted, @Nullable Versions v) {
    List<String> cols = new ArrayList<>();
    List<String> keys = t.keyColumns();
    for (String c : read) {
      String q = engine.quote(c);
      cols.add(keys.contains(c) ? "m." + q : t.beforeImage(engine, "m", c) + " AS " + q);
    }
    return "(SELECT "
        + inflightHint(engine, t, hinted)
        + String.join(", ", cols)
        + (v == null ? "" : v.items(engine, "m", true))
        + " FROM "
        + meta(engine, t)
        + " m"
        + tableHint(engine, t, hinted)
        + " WHERE m."
        + engine.quote("tx_state")
        + " IN (1, 2) AND (m."
        + engine.quote("before_tx_id")
        + " IS NOT NULL OR m."
        + engine.quote("before_tx_version")
        + " IS NOT NULL))";
  }

  /** The after image of each of the table's rows in flight, columns {@code read}. */
  static String afterImages(Engine engine, TableInfo t, List<String> read) {
    return afterImages(engine, t, read, false);
  }

  /**
   * As {@link #afterImages(Engine, TableInfo, List)}, with {@code hinted} as for {@link #inFlight}.
   */
  static String afterImages(Engine engine, TableInfo t, List<String> read, boolean hinted) {
    return "(SELECT "
        + inflightHint(engine, t, hinted)
        + columns(engine, read)
        + " FROM "
        + data(engine, t)
        + " t JOIN "
        + meta(engine, t)
        + " m"
        + tableHint(engine, t, hinted)
        + " ON "
        + keyJoin(engine, t, "m")
        + " WHERE m."
        + engine.quote("tx_state")
        + " IN (1, 2))";
  }

  private static String tableHint(Engine engine, TableInfo t, boolean hinted) {
    return hinted && engine == Engine.SQLSERVER
        ? " WITH (INDEX(" + engine.quote(t.inflightIndex(engine)) + "))"
        : "";
  }

  private static String inflightHint(Engine engine, TableInfo t, boolean hinted) {
    return hinted && engine == Engine.ORACLE
        ? "/*+ INDEX(m " + engine.quote(t.inflightIndex(engine)) + ") */ "
        : "";
  }

  private static String data(Engine engine, TableInfo t) {
    return engine.quote(t.namespace()) + "." + engine.quote(t.dataTable());
  }

  static String meta(Engine engine, TableInfo t) {
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
