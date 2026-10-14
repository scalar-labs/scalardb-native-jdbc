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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** The driver statement now running, which {@code cancel()} reaches; null when done. */
    void running(@Nullable Statement s);

    /** Marks the application's transaction rollback-only after an abort (4.4). */
    void rollbackOnly();

    /** The versions the transaction read, the anchors of its writes (4.2). */
    Anchors anchors();

    /** Records a key an INSERT wrote, in its data table's spelling, for {@link #returned}. */
    void inserted(Object[] key);
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

    /** Whether it is an INSERT, whose rows {@link #returned} reads back. */
    boolean inserts() {
      return false;
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
    String exposed = ref.hasAlias() ? Objects.requireNonNull(ref.alias()) : ref.name();
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
    return new InsertPlan(
        n,
        new Step(insert, all),
        inFlight,
        returning,
        ref.hasAlias() ? Objects.requireNonNull(ref.alias()) : ref.name());
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

  /**
   * Runs a write: in a transaction of its own in autocommit mode (4.4), otherwise in the
   * application's, which a driver-decided abort marks rollback-only.
   *
   * @param vendor the vendor connection
   * @param plan the plan
   * @param context the statement that runs it
   * @return the update count
   * @throws SQLException a database error, a timeout, or {@code 40001} for an abort the driver
   *     decides
   */
  static long run(Connection vendor, Plan plan, Context context) throws SQLException {
    long[] count = {0};
    within(vendor, context, () -> count[0] = apply(vendor, plan, context));
    return count[0];
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
   * Runs writes, a batch of them or one, as {@link #run} runs one: in a transaction of their own in
   * autocommit mode, all or none.
   */
  // ponytail: autocommit is off on the vendor connection while the write runs; another thread
  // sharing the connection then joins its transaction, as with any JDBC transaction
  static void within(Connection vendor, Context context, Work work) throws SQLException {
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
      } else if (e instanceof Aborted) {
        context.rollbackOnly();
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
      PreparedStatement ps = v.prepareStatement(sql);
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
      PreparedStatement ps = prepare(step.sql);
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
    for (int i = 0; i < width; i++) {
      int c = from + i;
      switch (rs.getMetaData().getColumnType(c)) {
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
      List<Row> rows = settledKeys(run);
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
          if (stampFromData(run, n, List.<Object[]>of(row.key)) != 1) {
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
        if (!(delete && row.metaKey == null) && cas(run, row) != 1) {
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
    private List<Row> settledKeys(Run run) throws SQLException {
      int width = n.keyColumns.size();
      long until = System.nanoTime() + run.context.inflightWait().toNanos();
      while (true) {
        List<Row> rows = new ArrayList<>();
        boolean held = false;
        try (PreparedStatement ps = run.prepare(keys, null);
            ResultSet rs = ps.executeQuery()) {
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
        if (System.nanoTime() - until >= 0) {
          throw new Aborted(
              "a row the statement writes is being written by a ScalarDB transaction");
        }
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new Aborted("the wait for a row in flight was interrupted");
        }
      }
    }

    /**
     * Step 2's zero-row rule: a row whose committed value moved stopped matching and is skipped;
     * one still at the anchor matched, so the statement saw an image in flight, and aborts.
     */
    private void skipOrAbort(Run run, Row row) throws SQLException {
      int width = n.keyColumns.size();
      try (PreparedStatement ps =
          run.prepare(
              "SELECT "
                  + n.columns("m")
                  + ", m."
                  + n.txId
                  + ", m."
                  + n.txVersion
                  + ", m."
                  + n.txState
                  + " FROM "
                  + n.data
                  + " d LEFT JOIN "
                  + n.meta
                  + " m ON "
                  + n.keyJoin("m", "d")
                  + " WHERE "
                  + n.keyEquals("d"))) {
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
    private int cas(Run run, Row row) throws SQLException {
      Object[] metaKey = row.metaKey;
      if (metaKey == null) {
        return stampFromData(run, n, List.<Object[]>of(row.key));
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

  /**
   * The stamp of keys without a metadata row, each key copied from its data row: the metadata table
   * spells some types otherwise (CHAR(n) is varchar there).
   */
  private static int stampFromData(Run run, Names n, List<Object[]> keys) throws SQLException {
    try (PreparedStatement ps =
        run.prepare(
            n.insertStamp(
                "SELECT "
                    + n.columns("d")
                    + ", ?, 3, 1, ?, ? FROM "
                    + n.data
                    + " d WHERE "
                    + n.anyKey("d", keys.size())))) {
      int i = run.stamp(ps, 1);
      for (Object[] key : keys) {
        i = bind(ps, i, key);
      }
      return ps.executeUpdate();
    }
  }

  /**
   * INSERT (4.3): the user's statement, which returns the keys it wrote, then their metadata rows,
   * taking over orphans (4.5).
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
    boolean inserts() {
      return true;
    }

    @Override
    List<String> sql() {
      return inFlight == null ? List.of(insert.sql) : List.of(insert.sql, inFlight.sql);
    }

    @Override
    long apply(Run run) throws SQLException {
      int width = n.keyColumns.size();
      List<Object[]> keys = new ArrayList<>();
      Savepoint sp = run.v.setSavepoint();
      try {
        try (PreparedStatement ps = run.prepare(insert, null);
            ResultSet rs = ps.executeQuery()) {
          int from = rs.getMetaData().getColumnCount() - width + 1;
          while (rs.next()) {
            keys.add(Objects.requireNonNull(readKey(rs, from, width)));
          }
        } catch (SQLException e) {
          if (!"23505".equals(e.getSQLState()) || inFlight == null) {
            throw e;
          }
          // the transaction goes on, as on the engines where a failed statement does not end it
          run.v.rollback(sp);
          try (PreparedStatement ps = run.prepare(inFlight, null);
              ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getLong(1) > 0) {
              Aborted a = new Aborted("a key the statement inserts is being written by ScalarDB");
              a.addSuppressed(e);
              throw a;
            }
          }
          throw e; // the application's duplicate
        }
      } finally {
        try {
          run.v.releaseSavepoint(sp);
        } catch (SQLException ignored) {
          // after another failure the transaction is aborted, and its end releases it
        }
      }
      if (keys.isEmpty()) {
        return 0;
      }
      int stamped = stampFromData(run, n, keys);
      if (stamped < keys.size()) {
        // a metadata row with no data row is an orphan, a tombstone: taken over in place (4.5)
        int taken;
        try (PreparedStatement ps =
            run.prepare(
                n.stamp("COALESCE(" + n.txVersion + ", 0) + 1")
                    + "EXISTS (SELECT 1 FROM "
                    + n.data
                    + " d WHERE "
                    + n.keyJoin(n.meta, "d")
                    + " AND ("
                    + n.anyKey("d", keys.size())
                    + ")) AND "
                    + n.txId
                    + " IS DISTINCT FROM ? AND "
                    + n.committed())) {
          int i = run.stamp(ps, 1);
          for (Object[] key : keys) {
            i = bind(ps, i, key);
          }
          ps.setString(i, run.txId);
          taken = ps.executeUpdate();
        }
        if (stamped + taken != keys.size()) {
          throw new Aborted("a key the statement inserts has metadata in flight");
        }
      }
      run.context.anchors().wrote(run.txId);
      for (Object[] key : keys) {
        run.context.inserted(key);
      }
      return keys.size();
    }
  }

  /**
   * The select list of the generated keys an application asks for, as PostgreSQL's driver returns
   * them: every column for {@code RETURN_GENERATED_KEYS}, the named ones quoted.
   *
   * @param plan the write
   * @param request the argument after the statement's text: a flag, column names or indexes
   * @return the items, or null when none are asked for
   * @throws SQLException 0AN01 for keys by column index or from an UPDATE or DELETE
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
    if (items != null && !plan.inserts()) {
      throw Errors.unsupported(
          "generated keys from an UPDATE or DELETE of a ScalarDB table are not supported");
    }
    return items;
  }

  /**
   * The rows INSERTs wrote, read back in the order they were written: the result of the statement's
   * own {@code RETURNING}, or the generated keys the application asked for.
   *
   * @param vendor the vendor connection, in the writes' transaction
   * @param plan an INSERT's plan
   * @param items the select list: the {@code RETURNING} items, {@code *}, or column names
   * @param keys the keys written, in order
   * @return the rows, its statement closed with it
   * @throws SQLException a database error
   */
  // ponytail: two placeholders per key column and row; a batch of tens of thousands of rows
  // passes PostgreSQL's limit of 65,535
  static ResultSet returned(Connection vendor, Plan plan, String items, List<Object[]> keys)
      throws SQLException {
    Names n = plan.n;
    String q = ((InsertPlan) plan).exposed;
    StringBuilder order = new StringBuilder("CASE");
    for (int i = 0; i < keys.size(); i++) {
      order.append(" WHEN ").append(n.keyEquals(q)).append(" THEN ").append(i);
    }
    String sql =
        "SELECT "
            + items
            + " FROM "
            + n.data
            + " AS "
            + q
            + (keys.isEmpty() ? " WHERE FALSE" : " WHERE " + n.anyKey(q, keys.size()))
            + (keys.size() < 2 ? "" : " ORDER BY " + order + " END");
    PreparedStatement ps = vendor.prepareStatement(sql);
    try {
      int i = 1;
      for (Object[] key : keys) {
        i = bind(ps, i, key);
      }
      if (keys.size() >= 2) {
        for (Object[] key : keys) {
          i = bind(ps, i, key);
        }
      }
      ResultSet rs = ps.executeQuery();
      ps.closeOnCompletion();
      return rs;
    } catch (SQLException | RuntimeException e) {
      ps.close();
      throw e;
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

    /** {@code (q.k1 = ? AND q.k2 = ?) OR ...}, {@code count} times. */
    String anyKey(String q, int count) {
      return String.join(" OR ", Collections.nCopies(count, "(" + keyEquals(q) + ")"));
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
