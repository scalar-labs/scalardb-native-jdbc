package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.StatementShape;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.sf.jsqlparser.expression.Expression;
import org.jspecify.annotations.Nullable;

/**
 * A write on one ScalarDB table at READ COMMITTED (design 4.3 to 4.5, milestone 4): the plan,
 * decided when the statement is routed, and its run with the application's bound values. A key's
 * anchor is the version the transaction first read of it, or the one the write itself reads.
 */
@SuppressFBWarnings(
    value = {"SQL_INJECTION_JDBC", "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING"},
    justification =
        "the application's own statement, and names from ScalarDB's catalog quoted by"
            + " the engine's rules")
final class Writes {

  private Writes() {}

  /** What a run needs from the statement that runs it. */
  interface Context {
    /** Binds the application's value of one of its placeholders to a driver statement. */
    void bind(PreparedStatement ps, int position, int placeholder) throws SQLException;

    /** The statement's query timeout in seconds, 0 for none. */
    int queryTimeout() throws SQLException;

    /** How long the key read waits for a row in flight to settle, zero to abort at once (6). */
    Duration inflightWait();

    /** Whether the application cancelled the statement, which a wait between statements checks. */
    boolean cancelled();

    /** The driver statement now running, which {@code cancel()} reaches; null when done. */
    void running(@Nullable Statement s);

    /** The versions the transaction read, the anchors of its writes (4.2). */
    Anchors anchors();

    /** Records the version a locking read found under its lock, as a read in the transaction. */
    void anchor(String table, Object[] key, Anchors.Version version);

    /** Records the keys an INSERT wrote, for {@link #returned}. */
    void inserted(Keys keys);

    /**
     * Hands over the statement's rows, their statement closed with them, the last {@code hidden}
     * columns the driver's own.
     */
    void rows(ResultSet rows, int hidden);
  }

  /** An abort the driver decides (4.4). */
  static final class Aborted extends SQLTransactionRollbackException {
    private static final long serialVersionUID = 1L;

    Aborted(String reason) {
      super(reason + "; roll the transaction back and retry it", "40001");
    }
  }

  /** A driver statement: its text, and per placeholder the application's placeholder. */
  static final class Step {
    final String sql;
    final List<Integer> binds;

    Step(String sql, List<Integer> binds) {
      this.sql = sql;
      this.binds = List.copyOf(binds);
    }
  }

  /** The statements of one write. */
  abstract static class Plan {
    final Names n;

    Plan(Names n) {
      this.n = n;
    }

    /** The items of the statement's own {@code RETURNING} clause, or null without one. */
    @Nullable String returning() {
      return null;
    }

    /** Whether the statement's result is rows: its own {@code RETURNING}, or a locking read's. */
    boolean returnsRows() {
      return returning() != null;
    }

    abstract long apply(Run run) throws SQLException;

    /** The texts of the statements planned ahead, for tests; the CAS is chosen per key. */
    abstract List<String> sql();
  }

  /**
   * Plans a write statement, or says why the driver cannot run it yet.
   *
   * @param engine the engine
   * @param shape the statement's single-table shape
   * @param t the ScalarDB table it writes
   * @param tables the tables the statement names, for the refusal's message
   * @return the plan
   * @throws SQLException 0AN01 for a write the driver cannot run yet
   */
  static Plan plan(Engine engine, StatementShape shape, TableInfo t, String tables)
      throws SQLException {
    SqlAst ast = shape.ast();
    String source = ast.source();
    List<Integer> all = ast.placeholdersIn(0, source.length());
    if (all == null) {
      throw refused(tables, "only ? placeholders are supported in a write of a ScalarDB table");
    }
    if (shape.returning() && shape.kind() != StatementShape.Kind.INSERT) {
      throw refused(
          tables, "RETURNING on an UPDATE or DELETE of a ScalarDB table is not supported");
    }
    Names n = new Names(engine, t);
    return shape.kind() == StatementShape.Kind.INSERT
        ? insertPlan(shape, t, n, all, tables)
        : keyedPlan(shape, t, n, all, tables);
  }

  private static Plan keyedPlan(
      StatementShape shape, TableInfo t, Names n, List<Integer> all, String tables)
      throws SQLException {
    Engine engine = n.engine;
    SqlAst ast = shape.ast();
    String source = ast.source();
    for (String c : shape.setColumns()) {
      for (String k : t.keyColumns()) {
        if (engine.columnKey(c).equals(engine.columnKey(k))) {
          throw refused(tables, "an UPDATE of a key column of a ScalarDB table is not supported");
        }
      }
    }
    Expression where = shape.where();
    int[] span = where == null ? null : ast.span(where);
    int end = ast.end();
    List<Integer> inWhere = span == null ? List.of() : ast.placeholdersIn(span[0], span[1]);
    if ((where != null && span == null) || inWhere == null || end < 0) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    for (SqlAst.ColumnRef c : ast.columnRefs()) {
      SqlAst.TableRef q = c.qualifier();
      if (q != null && q.schema() != null) {
        throw refused(tables, "a column qualified by a schema in a write of a ScalarDB table");
      }
    }
    SqlAst.TableRef ref = shape.table();
    String exposed = Router.exposed(ref);
    // the keys and their metadata, the committed images as a read at this level sees them: the
    // data table's key, the metadata table's own (CHAR(n) is varchar there), and the anchor
    List<String> picked = new ArrayList<>();
    for (String k : t.keyColumns()) {
      picked.add(exposed + "." + engine.quote(k) + " AS " + engine.quote(k));
    }
    String keys =
        "SELECT "
            + n.columns("d")
            + ", "
            + n.columns("m")
            + ", m."
            + n.txId
            + ", m."
            + n.txVersion
            + ", m."
            + n.txState
            + " FROM (SELECT "
            + String.join(", ", picked)
            + " FROM "
            + n.view
            + " "
            + exposed
            + (span == null ? "" : " WHERE " + source.substring(span[0], span[1]))
            + ") d LEFT JOIN "
            + n.meta
            + " m ON "
            + n.keyJoin("m", "d");
    // the user's statement for one key: the predicate re-applied, the key appended, both right
    // after the clause they extend, never at the end of the text where a comment may run on
    String keyed = n.keyEquals();
    String dml =
        span == null
            ? source.substring(0, end) + " WHERE " + keyed + source.substring(end)
            : source.substring(0, span[0])
                + "("
                + source.substring(span[0], span[1])
                + ") AND ("
                + keyed
                + ")"
                + source.substring(span[1]);
    dml = onDataTable(engine, ast, t, dml);
    if (dml == null) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    return new KeyedPlan(
        n,
        shape.kind() == StatementShape.Kind.DELETE,
        t.layout() == Layout.DECOUPLED_IMPORTED,
        new Step(keys, inWhere),
        new Step(dml, all),
        where != null);
  }

  private static Plan insertPlan(
      StatementShape shape, TableInfo t, Names n, List<Integer> all, String tables)
      throws SQLException {
    Engine engine = n.engine;
    SqlAst ast = shape.ast();
    String source = ast.source();
    int end = ast.end();
    // the user's statement returns the keys it wrote, after its own RETURNING items if any: a key
    // the database generates is known only from it
    int items = shape.returning() ? ast.afterLast("RETURNING") : end;
    if (end < 0 || items < 0) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    String returning = shape.returning() ? source.substring(items, end).trim() : null;
    String insert =
        onDataTable(
            engine,
            ast,
            t,
            source.substring(0, end)
                + (returning == null ? " RETURNING " : ", ")
                + n.columns("")
                + source.substring(end));
    if (insert == null) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    // the keys as written, when every row gives them: a duplicate is then told from a key in
    // flight (4.3); a generated one is the application's duplicate
    Step inFlight = null;
    List<List<Expression>> rows = shape.insertKeyValues(t.keyColumns());
    if (rows != null) {
      List<String> match = new ArrayList<>();
      List<Integer> matchBinds = new ArrayList<>();
      for (List<Expression> row : rows) {
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
          int[] span = ast.span(row.get(i));
          List<Integer> inside = span == null ? null : ast.placeholdersIn(span[0], span[1]);
          if (span == null || inside == null) {
            throw refused(tables, "the statement's text cannot be rewritten");
          }
          terms.add(
              engine.quote(t.keyColumns().get(i)) + " = " + source.substring(span[0], span[1]));
          matchBinds.addAll(inside);
        }
        match.add("(" + String.join(" AND ", terms) + ")");
      }
      inFlight =
          new Step(
              "SELECT COUNT(*) FROM "
                  + n.meta
                  + " WHERE ("
                  + String.join(" OR ", match)
                  + ") AND "
                  + n.txState
                  + " IN (1, 2)",
              matchBinds);
    }
    SqlAst.TableRef ref = shape.table();
    return new InsertPlan(n, new Step(insert, all), inFlight, returning, Router.exposed(ref));
  }

  /**
   * The user's text with the table named by its data table, which a created table's name, a view,
   * is not: the name as written stays as the alias, so that qualifiers keep working. {@code text}
   * is the source with edits only after the name.
   *
   * @return the text, or {@code null} when the name cannot be placed in it
   */
  private static @Nullable String onDataTable(Engine engine, SqlAst ast, TableInfo t, String text) {
    if (t.dataTable().equals(t.table())) {
      return text;
    }
    SqlAst.TableRef ref = ast.tableRefs().get(0);
    int[] span = ast.nameSpan(ref);
    if (span == null) {
      return null;
    }
    String data = engine.quote(t.namespace()) + "." + engine.quote(t.dataTable());
    String alias = ref.hasAlias() ? "" : " AS " + ref.name();
    return text.substring(0, span[0]) + data + alias + text.substring(span[1]);
  }

  private static SQLException refused(String tables, String reason) {
    return Errors.unsupported(reason + tables);
  }

  /** Work run in a write's transaction. */
  interface Work {
    void run() throws SQLException;
  }

  /** Applies a plan in the transaction {@link #within} keeps. */
  static long apply(Connection vendor, Plan plan, Context context) throws SQLException {
    try {
      return plan.apply(new Run(vendor, context));
    } finally {
      context.running(null);
    }
  }

  /**
   * Runs writes, one or a batch: in a transaction of their own in autocommit mode (4.4), all or
   * none; otherwise in the application's, which a driver-decided abort, a timeout or a cancel marks
   * rollback-only.
   *
   * @param vendor the vendor connection
   * @param rollbackOnly marks the application's transaction rollback-only
   * @param work the writes
   * @throws SQLException a database error, a timeout, or {@code 40001} for an abort the driver
   *     decides
   */
  // ponytail: autocommit is off on the vendor connection while the write runs; another thread
  // sharing the connection then joins its transaction, as with any JDBC transaction
  static void within(Connection vendor, Runnable rollbackOnly, Work work) throws SQLException {
    boolean auto = vendor.getAutoCommit();
    if (auto) {
      vendor.setAutoCommit(false);
    }
    try {
      work.run();
      if (auto) {
        vendor.commit();
      }
    } catch (SQLException | RuntimeException e) {
      if (auto) {
        try {
          vendor.rollback();
          vendor.setAutoCommit(true);
        } catch (SQLException r) {
          e.addSuppressed(r); // the write's own failure is the one to report
        }
      } else if (e instanceof Aborted
          || e instanceof RuntimeException
          || "57014".equals(((SQLException) e).getSQLState())) {
        // a timeout or a cancel the driver raises itself can come between a key's DML and its
        // stamp, in a transaction the vendor would still commit (todo 157)
        // ponytail: a vendor error part way relies on PostgreSQL ending the transaction; a
        // savepoint per write on the engines where a failed statement does not
        rollbackOnly.run();
      }
      throw e;
    }
    if (auto) {
      vendor.setAutoCommit(true);
    }
  }

  /** One run of a plan: its stamp, its deadline, and the driver statements it prepares. */
  static final class Run {
    final Connection v;
    final Context context;
    final String txId = UUID.randomUUID().toString();
    final long now = System.currentTimeMillis();
    final long deadline; // System.nanoTime(), or 0 without a timeout

    Run(Connection v, Context context) throws SQLException {
      this.v = v;
      this.context = context;
      int timeout = context.queryTimeout();
      this.deadline = timeout <= 0 ? 0 : System.nanoTime() + timeout * 1_000_000_000L;
    }

    /** A driver statement with what is left of the timeout, reachable by {@code cancel()}. */
    PreparedStatement prepare(String sql) throws SQLException {
      return prepare(sql, false);
    }

    /** Ends the statement when the application cancelled it or its timeout passed. */
    void checkCancelled() throws SQLException {
      if (context.cancelled()) {
        throw new SQLException("the statement was cancelled", "57014");
      }
      if (deadline != 0 && deadline - System.nanoTime() <= 0) {
        throw new SQLTimeoutException("the statement's query timeout passed", "57014");
      }
    }

    /**
     * Waits the poll interval of the bounded wait (section 6), or aborts once {@code until} has
     * passed.
     */
    void sleep(long until, String reason) throws SQLException {
      if (System.nanoTime() - until >= 0) {
        throw new Aborted(reason);
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Aborted("the wait for a row in flight was interrupted");
      }
      checkCancelled();
    }

    /** As {@link #prepare(String)}; a scrollable one's rows can be read twice. */
    PreparedStatement prepare(String sql, boolean scrollable) throws SQLException {
      checkCancelled();
      PreparedStatement ps =
          scrollable
              ? v.prepareStatement(
                  sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
              : v.prepareStatement(sql);
      try {
        if (deadline != 0) {
          long left = deadline - System.nanoTime();
          if (left <= 0) {
            throw new SQLTimeoutException("the statement's query timeout passed", "57014");
          }
          ps.setQueryTimeout((int) Math.max(1, (left + 999_999_999L) / 1_000_000_000L));
        }
        context.running(ps);
        return ps;
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
    }

    /** A step prepared and bound: its binds, then the key's values, if any. */
    PreparedStatement prepare(Step step, Object @Nullable [] key) throws SQLException {
      return prepare(step, key, false);
    }

    PreparedStatement prepare(Step step, Object @Nullable [] key, boolean scrollable)
        throws SQLException {
      PreparedStatement ps = prepare(step.sql, scrollable);
      try {
        int i = 1;
        for (int b : step.binds) {
          context.bind(ps, i++, b);
        }
        if (key != null) {
          bind(ps, i, key);
        }
        return ps;
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
    }

    /** Sets the stamp of I5: the transaction id and twice the time, from {@code at}. */
    int stamp(PreparedStatement ps, int at) throws SQLException {
      ps.setString(at, txId);
      ps.setLong(at + 1, now);
      ps.setLong(at + 2, now);
      return at + 3;
    }
  }

  private static int bind(PreparedStatement ps, int from, Object[] key) throws SQLException {
    int i = from;
    for (Object k : key) {
      ps.setObject(i++, k);
    }
    return i;
  }

  private static int bindAll(PreparedStatement ps, int from, List<Object[]> keys)
      throws SQLException {
    int i = from;
    for (Object[] key : keys) {
      i = bind(ps, i, key);
    }
    return i;
  }

  /** At most this many keys are bound in one statement, under PostgreSQL's 65,535 parameters. */
  private static final int CHUNK = 1000;

  /** Keys read from a result set, and the types of their columns to cast them back to (150). */
  static final class Keys {
    final List<Object[]> rows = new ArrayList<>();
    final List<String> types = new ArrayList<>();

    /** Reads the key starting at column {@code from}, its types with the first. */
    void read(ResultSet rs, int from, int width) throws SQLException {
      if (types.isEmpty()) {
        ResultSetMetaData md = rs.getMetaData();
        for (int i = 0; i < width; i++) {
          types.add(castable(md.getColumnTypeName(from + i)));
        }
      }
      rows.add(Objects.requireNonNull(readKey(rs, from, width)));
    }

    /** A type name PostgreSQL's driver reports, as a cast takes it: serial is int4's default. */
    private static String castable(String type) {
      switch (type) {
        case "serial":
          return "int4";
        case "bigserial":
          return "int8";
        case "smallserial":
          return "int2";
        default:
          return type;
      }
    }

    void addAll(Keys other) {
      if (types.isEmpty()) {
        types.addAll(other.types);
      }
      rows.addAll(other.rows);
    }
  }

  /** One key of an UPDATE or DELETE, and the metadata step 1 read for it. */
  private static final class Row {
    final Object[] key; // the data table's
    final Object @Nullable [] metaKey; // the metadata table's, null without a metadata row
    final @Nullable String txId;
    final @Nullable Object version;

    Row(
        Object[] key,
        Object @Nullable [] metaKey,
        @Nullable String txId,
        @Nullable Object version) {
      this.key = key;
      this.metaKey = metaKey;
      this.txId = txId;
      this.version = version;
    }

    /** Whether the metadata read again is still the anchor: the committed value did not move. */
    boolean sameAs(Object @Nullable [] metaKey, @Nullable String txId, @Nullable Object version) {
      return (this.metaKey == null) == (metaKey == null)
          && Objects.equals(this.txId, txId)
          && Objects.equals(
              this.version == null ? null : ((Number) this.version).longValue(),
              version == null ? null : ((Number) version).longValue());
    }
  }

  /** Reads a key value so that it binds back unchanged: a local time is not shifted by the zone. */
  static Object @Nullable [] readKey(ResultSet rs, int from, int width) throws SQLException {
    Object[] key = new Object[width];
    ResultSetMetaData md = rs.getMetaData();
    for (int i = 0; i < width; i++) {
      int c = from + i;
      // PostgreSQL's driver reports timestamptz as TIMESTAMP and timetz as TIME (todo 148)
      String name = md.getColumnTypeName(c);
      if ("timestamptz".equalsIgnoreCase(name)) {
        key[i] = rs.getObject(c, OffsetDateTime.class);
        continue;
      }
      if ("timetz".equalsIgnoreCase(name)) {
        key[i] = rs.getObject(c, OffsetTime.class);
        continue;
      }
      switch (md.getColumnType(c)) {
        case Types.TIMESTAMP:
          key[i] = rs.getObject(c, LocalDateTime.class);
          break;
        case Types.TIME:
          key[i] = rs.getObject(c, LocalTime.class);
          break;
        case Types.TIMESTAMP_WITH_TIMEZONE:
          key[i] = rs.getObject(c, OffsetDateTime.class);
          break;
        default:
          key[i] = rs.getObject(c);
      }
    }
    return key[0] == null ? null : key;
  }

  private static boolean inFlight(@Nullable Object state) {
    return state != null && (((Number) state).intValue() == 1 || ((Number) state).intValue() == 2);
  }

  /** UPDATE and DELETE (4.3): per key, the user's statement first, the CAS second. */
  private static final class KeyedPlan extends Plan {
    final boolean delete;
    final boolean imported;
    final Step keys;
    final Step dml;
    final boolean predicate;

    KeyedPlan(Names n, boolean delete, boolean imported, Step keys, Step dml, boolean predicate) {
      super(n);
      this.delete = delete;
      this.imported = imported;
      this.keys = keys;
      this.dml = dml;
      this.predicate = predicate;
    }

    @Override
    List<String> sql() {
      return List.of(keys.sql, dml.sql);
    }

    @Override
    long apply(Run run) throws SQLException {
      List<String> types = new ArrayList<>();
      List<Row> rows = settledKeys(run, types);
      // first-updater-wins (4.3 step 1): a key this transaction read must still be at that version
      Anchors anchors = run.context.anchors();
      for (Row row : rows) {
        if (!anchors.unchanged(n.table, row.key, row.txId, row.version)) {
          throw new Aborted("a row this transaction read was changed by another one since");
        }
      }
      long total = 0;
      for (Row row : rows) {
        if (delete && row.metaKey == null) {
          // the tombstone needs the key as the metadata table spells it, from the data row
          // ponytail: written before the DELETE, metadata before data; a ScalarDB prepare of this
          // never-backfilled row at the same moment can deadlock, which the database breaks
          if (stampFromData(run, n, one(row.key, types)) != 1) {
            throw new Aborted("a row the statement writes was given metadata after it was read");
          }
          anchors.wrote(run.txId); // the stamp stays if the DELETE then matches nothing
        }
        int written;
        try (PreparedStatement ps = run.prepare(dml, row.key)) {
          written = ps.executeUpdate();
        }
        if (written == 0) {
          skipOrAbort(run, row);
          continue;
        }
        if (written != 1) {
          throw new Aborted("a key matched " + written + " rows");
        }
        if (!(delete && row.metaKey == null) && cas(run, row, types) != 1) {
          throw new Aborted("a row the statement writes changed after it was read");
        }
        anchors.wrote(run.txId);
        total++;
      }
      return total;
    }

    /**
     * Step 1: the keys and their metadata, read again until no row is in flight or the bounded wait
     * of section 6 runs out, then an abort. Nothing is locked yet, so nothing is held while
     * waiting; the statement's query timeout bounds the wait too.
     */
    // ponytail: polls the whole key read every 20 ms; a per-key poll of T_meta if a wait on a
    // large predicate measures heavy
    private List<Row> settledKeys(Run run, List<String> types) throws SQLException {
      int width = n.keyColumns.size();
      long until = System.nanoTime() + run.context.inflightWait().toNanos();
      while (true) {
        List<Row> rows = new ArrayList<>();
        boolean held = false;
        try (PreparedStatement ps = run.prepare(keys, null);
            ResultSet rs = ps.executeQuery()) {
          if (types.isEmpty()) {
            for (int i = 1; i <= width; i++) {
              types.add(Keys.castable(rs.getMetaData().getColumnTypeName(i)));
            }
          }
          while (rs.next()) {
            held |= inFlight(rs.getObject(2 * width + 3));
            rows.add(
                new Row(
                    Objects.requireNonNull(readKey(rs, 1, width)),
                    readKey(rs, width + 1, width),
                    rs.getString(2 * width + 1),
                    rs.getObject(2 * width + 2)));
          }
        }
        if (!held) {
          return rows;
        }
        run.sleep(until, "a row the statement writes is being written by a ScalarDB transaction");
      }
    }

    /**
     * Step 2's zero-row rule: a row whose committed value moved stopped matching and is skipped;
     * one still at the anchor matched, so the statement saw an image in flight, and aborts.
     */
    private void skipOrAbort(Run run, Row row) throws SQLException {
      int width = n.keyColumns.size();
      try (PreparedStatement ps = run.prepare(n.withMeta("m", n.keyEquals("d")))) {
        bind(ps, 1, row.key);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            throw new Aborted("a row the statement writes was deleted after it was read");
          }
          if (inFlight(rs.getObject(width + 3))) {
            throw new Aborted(
                "a row the statement writes went in flight in a ScalarDB transaction");
          }
          if (!predicate
              || row.sameAs(
                  readKey(rs, 1, width), rs.getString(width + 1), rs.getObject(width + 2))) {
            throw new Aborted("a row the statement writes showed a value in flight");
          }
        }
      }
    }

    /**
     * Step 3: the CAS on the key's metadata (4.3), the stamp of a row without (4.4); on an imported
     * table a DELETE keeps the metadata row as a tombstone (4.5).
     */
    private int cas(Run run, Row row, List<String> types) throws SQLException {
      Object[] metaKey = row.metaKey;
      if (metaKey == null) {
        return stampFromData(run, n, one(row.key, types));
      }
      String keyed = n.keyEquals("") + " AND " + n.committed();
      if (row.txId == null) {
        try (PreparedStatement ps =
            run.prepare(n.stamp("1") + keyed + " AND " + n.txId + " IS NULL")) {
          bind(ps, run.stamp(ps, 1), metaKey);
          return ps.executeUpdate();
        }
      }
      String anchored =
          keyed
              + " AND "
              + n.txId
              + " = ? AND "
              + n.txVersion
              + (row.version == null ? " IS NULL" : " = ?");
      boolean remove = delete && !imported;
      try (PreparedStatement ps =
          run.prepare(
              remove
                  ? "DELETE FROM " + n.meta + " WHERE " + anchored
                  : n.stamp("COALESCE(" + n.txVersion + ", 0) + 1") + anchored)) {
        int i = bind(ps, remove ? 1 : run.stamp(ps, 1), metaKey);
        ps.setString(i++, row.txId);
        if (row.version != null) {
          ps.setObject(i, row.version);
        }
        return ps.executeUpdate();
      }
    }
  }

  /** One key as {@link Keys}. */
  private static Keys one(Object[] key, List<String> types) {
    Keys k = new Keys();
    k.types.addAll(types);
    k.rows.add(key);
    return k;
  }

  /**
   * The stamp of keys without a metadata row, each key copied from its data row: the metadata table
   * spells some types otherwise (CHAR(n) is varchar there).
   */
  private static int stampFromData(Run run, Names n, Keys keys) throws SQLException {
    int stamped = 0;
    for (int from = 0; from < keys.rows.size(); from += CHUNK) {
      List<Object[]> chunk = keys.rows.subList(from, Math.min(keys.rows.size(), from + CHUNK));
      try (PreparedStatement ps =
          run.prepare(
              n.insertStamp(
                  "SELECT "
                      + n.columns("d")
                      + ", ?, 3, 1, ?, ? FROM "
                      + n.data
                      + " d WHERE "
                      + n.keyIn("d", keys.types, chunk.size())))) {
        bindAll(ps, run.stamp(ps, 1), chunk);
        stamped += ps.executeUpdate();
      }
    }
    return stamped;
  }

  /**
   * INSERT (4.3): the user's statement, which returns the keys it wrote, then their metadata rows,
   * taking over orphans (4.5). With its own {@code RETURNING}, its rows are the statement's result,
   * the appended keys hidden.
   */
  private static final class InsertPlan extends Plan {
    final Step insert;
    final @Nullable Step inFlight;
    final @Nullable String returning;
    final String exposed;

    InsertPlan(
        Names n, Step insert, @Nullable Step inFlight, @Nullable String returning, String exposed) {
      super(n);
      this.insert = insert;
      this.inFlight = inFlight;
      this.returning = returning;
      this.exposed = exposed;
    }

    @Override
    @Nullable String returning() {
      return returning;
    }

    @Override
    List<String> sql() {
      return inFlight == null ? List.of(insert.sql) : List.of(insert.sql, inFlight.sql);
    }

    @SuppressFBWarnings(
        value = "OBL_UNSATISFIED_OBLIGATION",
        justification = "the rows are handed to the application, closed with their statement")
    @Override
    long apply(Run run) throws SQLException {
      int width = n.keyColumns.size();
      Keys keys = new Keys();
      PreparedStatement ps = null;
      ResultSet rs;
      Savepoint sp = run.v.setSavepoint();
      try {
        ps = run.prepare(insert, null, returning != null);
        rs = ps.executeQuery();
        int from = rs.getMetaData().getColumnCount() - width + 1;
        while (rs.next()) {
          keys.read(rs, from, width);
        }
      } catch (SQLException e) {
        if (ps != null) {
          ps.close();
        }
        if (!"23505".equals(e.getSQLState()) || inFlight == null) {
          throw e;
        }
        // the transaction goes on, as on the engines where a failed statement does not end it
        run.v.rollback(sp);
        try (PreparedStatement check = run.prepare(inFlight, null);
            ResultSet r = check.executeQuery()) {
          r.next();
          if (r.getLong(1) > 0) {
            Aborted a = new Aborted("a key the statement inserts is being written by ScalarDB");
            a.addSuppressed(e);
            throw a;
          }
        }
        throw e; // the application's duplicate
      } finally {
        try {
          run.v.releaseSavepoint(sp);
        } catch (SQLException ignored) {
          // after another failure the transaction is aborted, and its end releases it
        }
      }
      try {
        stamp(run, keys);
        run.context.anchors().wrote(run.txId);
        run.context.inserted(keys);
        if (returning != null) {
          rs.beforeFirst();
          ps.closeOnCompletion();
          run.context.rows(rs, width);
        } else {
          ps.close();
        }
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
      return keys.rows.size();
    }

    private void stamp(Run run, Keys keys) throws SQLException {
      int stamped = stampFromData(run, n, keys);
      if (stamped == keys.rows.size()) {
        return;
      }
      // a metadata row with no data row is an orphan, a tombstone: taken over in place (4.5)
      int taken = 0;
      for (int from = 0; from < keys.rows.size(); from += CHUNK) {
        List<Object[]> chunk = keys.rows.subList(from, Math.min(keys.rows.size(), from + CHUNK));
        try (PreparedStatement ps =
            run.prepare(
                n.stamp("COALESCE(" + n.txVersion + ", 0) + 1")
                    + "EXISTS (SELECT 1 FROM "
                    + n.data
                    + " d WHERE "
                    + n.keyJoin(n.meta, "d")
                    + " AND "
                    + n.keyIn("d", keys.types, chunk.size())
                    + ") AND "
                    + n.txId
                    + " IS DISTINCT FROM ? AND "
                    + n.committed())) {
          ps.setString(bindAll(ps, run.stamp(ps, 1), chunk), run.txId);
          taken += ps.executeUpdate();
        }
      }
      if (stamped + taken != keys.rows.size()) {
        throw new Aborted("a key the statement inserts has metadata in flight");
      }
    }
  }

  /**
   * The select list of the generated keys an application asks for, as PostgreSQL's driver returns
   * them: every column for {@code RETURN_GENERATED_KEYS}, the named ones quoted.
   *
   * @param plan the write
   * @param request the argument after the statement's text: a flag, column names or indexes
   * @return the items, or null when none are asked for
   * @throws SQLException 0AN01 for keys by column index or from anything but an INSERT
   */
  static @Nullable String keysAsked(Plan plan, Object request) throws SQLException {
    String items;
    if (request instanceof String[]) {
      List<String> names = new ArrayList<>();
      for (String c : (String[]) request) {
        names.add(plan.n.engine.quote(c));
      }
      items = names.isEmpty() ? null : String.join(", ", names);
    } else if (request instanceof int[]) {
      if (((int[]) request).length > 0) {
        throw Errors.unsupported("generated keys by column index are not supported");
      }
      items = null;
    } else {
      items = Integer.valueOf(Statement.RETURN_GENERATED_KEYS).equals(request) ? "*" : null;
    }
    if (items != null && !(plan instanceof InsertPlan)) {
      throw Errors.unsupported("generated keys are returned by an INSERT of a ScalarDB table only");
    }
    return items;
  }

  /**
   * The generated keys INSERTs wrote, read back in the order they were written, as a batch's are.
   *
   * @param vendor the vendor connection, in the writes' transaction
   * @param plan an INSERT's plan
   * @param items the select list: {@code *}, or column names
   * @param keys the keys written, in order
   * @return the rows, its statement closed with it
   * @throws SQLException a database error
   */
  // ponytail: one placeholder per key column and row; a batch of more than 65,535 rows fails
  static ResultSet returned(Connection vendor, Plan plan, String items, Keys keys)
      throws SQLException {
    Names n = plan.n;
    String q = ((InsertPlan) plan).exposed;
    String sql =
        keys.rows.isEmpty()
            ? "SELECT "
                + (items.equals("*") ? q + ".*" : items)
                + " FROM "
                + n.data
                + " AS "
                + q
                + " WHERE FALSE"
            : "SELECT "
                + (items.equals("*") ? q + ".*" : items)
                + " FROM "
                + n.data
                + " AS "
                + q
                + " JOIN "
                + n.keyValues(keys.types, keys.rows.size())
                + " ON "
                + n.keyJoinValues(q)
                + " ORDER BY u."
                + n.engine.quote(Names.ORDINAL);
    PreparedStatement ps = vendor.prepareStatement(sql);
    try {
      bindAll(ps, 1, keys.rows);
      ResultSet rs = ps.executeQuery();
      ps.closeOnCompletion();
      return rs;
    } catch (SQLException | RuntimeException e) {
      ps.close();
      throw e;
    }
  }

  /**
   * A user's {@code FOR UPDATE} or {@code FOR SHARE} of one ScalarDB table (design 4.2): the
   * committed rows locked, a row in flight treated as one another transaction holds. Unless the
   * clause skips locked rows, a row in flight that the predicate matches fails a {@code NOWAIT}
   * with {@code 55P03}, and otherwise waits the bounded wait of section 6, then aborts; the check
   * is made again after the lock, for a row that went in flight before the lock statement began
   * (todo 149). Each locked row's metadata is read again under the lock: PostgreSQL re-checks a row
   * updated since the statement began against the metadata table as the statement began, so a row a
   * ScalarDB prepare wrote meanwhile would pass as committed. Each attempt runs under a savepoint,
   * whose rollback releases its locks before the next.
   */
  static final class LockPlan extends Plan {
    final Step lock;
    final @Nullable Step inFlight;
    final boolean noWait;

    LockPlan(Names n, Step lock, @Nullable Step inFlight, boolean noWait) {
      super(n);
      this.lock = lock;
      this.inFlight = inFlight;
      this.noWait = noWait;
    }

    @Override
    boolean returnsRows() {
      return true;
    }

    @Override
    List<String> sql() {
      return inFlight == null ? List.of(lock.sql) : List.of(inFlight.sql, lock.sql);
    }

    @SuppressFBWarnings(
        value = "OBL_UNSATISFIED_OBLIGATION",
        justification = "the rows are handed to the application, closed with their statement")
    @Override
    long apply(Run run) throws SQLException {
      int width = n.keyColumns.size();
      long until = System.nanoTime() + run.context.inflightWait().toNanos();
      String held = "a row the statement locks is being written by a ScalarDB transaction";
      for (int attempt = 0; ; attempt++) {
        while (inFlight != null && matchesInFlight(run)) {
          if (noWait) {
            throw new SQLException("could not lock a row: " + held, "55P03");
          }
          run.sleep(until, held);
        }
        Savepoint sp = run.v.setSavepoint();
        PreparedStatement ps = run.prepare(lock, null, true);
        try {
          ResultSet rs = ps.executeQuery();
          // the key, then the two version lookups the anchored read appends
          int from = rs.getMetaData().getColumnCount() - width - 1;
          Keys keys = new Keys();
          while (rs.next()) {
            keys.read(rs, from, width);
          }
          List<Anchors.Version> versions = new ArrayList<>();
          boolean settled =
              (inFlight == null || !matchesInFlight(run)) && lockedVersions(run, keys, versions);
          if (settled) {
            run.v.releaseSavepoint(sp);
            for (int i = 0; i < keys.rows.size(); i++) {
              run.context.anchor(n.table, keys.rows.get(i), versions.get(i));
            }
            rs.beforeFirst();
            ps.closeOnCompletion();
            run.context.rows(rs, width + 2);
            return keys.rows.size();
          }
        } catch (SQLException | RuntimeException e) {
          ps.close();
          throw e;
        }
        // a row went in flight around the lock: release this attempt's locks and read again,
        // a skipping read at once, since its rows in flight are now left out (todo 151)
        ps.close();
        run.v.rollback(sp);
        if (noWait) {
          throw new SQLException("could not lock a row: " + held, "55P03");
        }
        if (attempt >= 10) {
          throw new Aborted(held); // rows keep going in flight around the lock: bounded retries
        }
      }
    }

    private boolean matchesInFlight(Run run) throws SQLException {
      try (PreparedStatement ps = run.prepare(Objects.requireNonNull(inFlight), null);
          ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }

    /**
     * The version of each locked key, read under the lock, in the keys' order; false when one is in
     * flight.
     */
    private boolean lockedVersions(Run run, Keys keys, List<Anchors.Version> versions)
        throws SQLException {
      int width = n.keyColumns.size();
      Map<List<Object>, Anchors.Version> found = new HashMap<>();
      for (int from = 0; from < keys.rows.size(); from += CHUNK) {
        List<Object[]> chunk = keys.rows.subList(from, Math.min(keys.rows.size(), from + CHUNK));
        try (PreparedStatement ps =
            run.prepare(n.withMeta("d", n.keyIn("d", keys.types, chunk.size())))) {
          bindAll(ps, 1, chunk);
          try (ResultSet m = ps.executeQuery()) {
            while (m.next()) {
              if (inFlight(m.getObject(width + 3))) {
                return false;
              }
              found.put(
                  Anchors.key(Objects.requireNonNull(readKey(m, 1, width))),
                  new Anchors.Version(m.getString(width + 1), m.getObject(width + 2)));
            }
          }
        }
      }
      for (Object[] key : keys.rows) {
        versions.add(found.getOrDefault(Anchors.key(key), new Anchors.Version(null, null)));
      }
      return true;
    }
  }

  /** The table's names, quoted, and the clauses built from them. */
  static final class Names {
    final Engine engine;
    final String table; // as Router.Anchored names it

    final List<String> keyColumns;
    final String data;
    final String meta;
    final String view;
    final String txId;
    final String txState;
    final String txVersion;
    final String preparedAt;
    final String committedAt;

    Names(Engine engine, TableInfo t) {
      this.engine = engine;
      this.table = t.namespace() + "." + t.table();
      this.keyColumns = t.keyColumns();
      String ns = engine.quote(t.namespace()) + ".";
      this.data = ns + engine.quote(t.dataTable());
      this.meta = ns + engine.quote(Objects.requireNonNull(t.metadataTable(), "metadata table"));
      this.view = ns + engine.quote(t.readView(engine));
      this.txId = engine.quote("tx_id");
      this.txState = engine.quote("tx_state");
      this.txVersion = engine.quote("tx_version");
      this.preparedAt = engine.quote("tx_prepared_at");
      this.committedAt = engine.quote("tx_committed_at");
    }

    /** The key columns, each qualified by {@code q} when it is not empty. */
    String columns(String q) {
      List<String> out = new ArrayList<>();
      for (String k : keyColumns) {
        out.add((q.isEmpty() ? "" : q + ".") + engine.quote(k));
      }
      return String.join(", ", out);
    }

    /** {@code k1 = ? AND k2 = ?}. */
    String keyEquals() {
      return keyEquals("");
    }

    /** {@code q.k1 = ? AND q.k2 = ?}, unqualified when {@code q} is empty. */
    String keyEquals(String q) {
      List<String> terms = new ArrayList<>();
      for (String k : keyColumns) {
        terms.add((q.isEmpty() ? "" : q + ".") + engine.quote(k) + " = ?");
      }
      return String.join(" AND ", terms);
    }

    /** The ordinal column of {@link #keyValues}. */
    static final String ORDINAL = "scalardb_native_n";

    /**
     * {@code count} keys as a table {@code u}, each value cast to its column's type, so that the
     * key compares as the column does (a CHAR(n) key with its padding): {@code (VALUES (0, CAST(?
     * AS t)), ...) u(scalardb_native_n, scalardb_native_k0, ...)}.
     */
    String keyValues(List<String> types, int count) {
      List<String> casts = new ArrayList<>();
      List<String> names = new ArrayList<>(List.of(engine.quote(ORDINAL)));
      for (int i = 0; i < types.size(); i++) {
        casts.add("CAST(? AS " + types.get(i) + ")");
        names.add(engine.quote("scalardb_native_k" + i));
      }
      List<String> rows = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        rows.add("(" + i + ", " + String.join(", ", casts) + ")");
      }
      return "(VALUES " + String.join(", ", rows) + ") u(" + String.join(", ", names) + ")";
    }

    /** The key columns of {@code q} equal to those of {@link #keyValues}'s {@code u}. */
    String keyJoinValues(String q) {
      List<String> terms = new ArrayList<>();
      for (int i = 0; i < keyColumns.size(); i++) {
        terms.add(
            q
                + "."
                + engine.quote(keyColumns.get(i))
                + " = u."
                + engine.quote("scalardb_native_k" + i));
      }
      return String.join(" AND ", terms);
    }

    /** {@code (q.k1, q.k2) IN (SELECT ... FROM <keyValues>)}, the keys bound in order. */
    String keyIn(String q, List<String> types, int count) {
      List<String> cols = new ArrayList<>();
      for (int i = 0; i < types.size(); i++) {
        cols.add("u." + engine.quote("scalardb_native_k" + i));
      }
      return "("
          + columns(q)
          + ") IN (SELECT "
          + String.join(", ", cols)
          + " FROM "
          + keyValues(types, count)
          + ")";
    }

    /**
     * {@code SELECT keys, m.tx_id, m.tx_version, m.tx_state FROM data d LEFT JOIN meta m ... WHERE
     * where}: a key, the data table's or the metadata table's ({@code keyQualifier} d or m), and
     * its metadata.
     */
    String withMeta(String keyQualifier, String where) {
      return "SELECT "
          + columns(keyQualifier)
          + ", m."
          + txId
          + ", m."
          + txVersion
          + ", m."
          + txState
          + " FROM "
          + data
          + " d LEFT JOIN "
          + meta
          + " m ON "
          + keyJoin("m", "d")
          + " WHERE "
          + where;
    }

    /** The key columns of {@code a} equal to those of {@code b}. */
    String keyJoin(String a, String b) {
      List<String> terms = new ArrayList<>();
      for (String k : keyColumns) {
        terms.add(a + "." + engine.quote(k) + " = " + b + "." + engine.quote(k));
      }
      return String.join(" AND ", terms);
    }

    /** A committed metadata row, NULL state included (the deemed-committed rule). */
    String committed() {
      return "COALESCE(" + txState + ", 3) = 3";
    }

    /** The stamp of I5 at {@code version}, its three values bound first, up to the WHERE. */
    String stamp(String version) {
      return "UPDATE "
          + meta
          + " SET "
          + txId
          + " = ?, "
          + preparedAt
          + " = ?, "
          + committedAt
          + " = ?, "
          + txState
          + " = 3, "
          + txVersion
          + " = "
          + version
          + " WHERE ";
    }

    /** A metadata row per key from {@code rows}: the key, then the stamp's three values. */
    String insertStamp(String rows) {
      return "INSERT INTO "
          + meta
          + " ("
          + columns("")
          + ", "
          + txId
          + ", "
          + txState
          + ", "
          + txVersion
          + ", "
          + preparedAt
          + ", "
          + committedAt
          + ") "
          + rows
          + " ON CONFLICT DO NOTHING";
    }
  }
}
