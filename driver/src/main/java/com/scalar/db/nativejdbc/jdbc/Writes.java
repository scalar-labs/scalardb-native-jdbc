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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.sf.jsqlparser.expression.Expression;
import org.jspecify.annotations.Nullable;

/**
 * A write on one ScalarDB table at READ COMMITTED (design 4.3 to 4.5, milestone 4a): the plan,
 * decided when the statement is routed, and its run with the application's bound values. The anchor
 * of each key is taken by the write itself; reads take none yet.
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

    /** The driver statement now running, which {@code cancel()} reaches; null when done. */
    void running(@Nullable Statement s);

    /** Marks the application's transaction rollback-only after an abort (4.4). */
    void rollbackOnly();

    /** The versions the transaction read, the anchors of its writes (4.2). */
    Anchors anchors();
  }

  /** An abort the driver decides (4.4). */
  static final class Aborted extends SQLTransactionRollbackException {
    private static final long serialVersionUID = 1L;

    Aborted(String reason) {
      super(reason + "; roll the transaction back and retry it", "40001");
    }
  }

  /** A value the driver binds itself, by role. */
  private enum Slot {
    TX_ID,
    NOW
  }

  /** A driver statement: its text, and per placeholder an application placeholder or a slot. */
  static final class Step {
    final String sql;
    final List<?> binds;

    Step(String sql, List<?> binds) {
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
    if (shape.returning()) {
      throw refused(tables, "RETURNING on a ScalarDB table is not supported yet");
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
    List<List<Expression>> rows = shape.insertKeyValues(t.keyColumns());
    if (rows == null) {
      throw refused(
          tables,
          "an INSERT into a ScalarDB table must list its columns and give every key column as a"
              + " value or a placeholder; a key the database generates is not supported yet");
    }
    List<String> values = new ArrayList<>();
    List<Object> valueBinds = new ArrayList<>();
    List<String> match = new ArrayList<>();
    List<Object> matchBinds = new ArrayList<>();
    for (List<Expression> row : rows) {
      List<String> keyValues = new ArrayList<>();
      List<String> terms = new ArrayList<>();
      for (int i = 0; i < row.size(); i++) {
        int[] span = ast.span(row.get(i));
        List<Integer> inside = span == null ? null : ast.placeholdersIn(span[0], span[1]);
        if (span == null || inside == null) {
          throw refused(tables, "the statement's text cannot be rewritten");
        }
        String text = source.substring(span[0], span[1]);
        keyValues.add(text);
        valueBinds.addAll(inside);
        terms.add(engine.quote(t.keyColumns().get(i)) + " = " + text);
        matchBinds.addAll(inside);
      }
      values.add("(" + String.join(", ", keyValues) + ", ?, 3, 1, ?, ?)");
      valueBinds.addAll(List.of(Slot.TX_ID, Slot.NOW, Slot.NOW));
      match.add("(" + String.join(" AND ", terms) + ")");
    }
    String keyMatch = "(" + String.join(" OR ", match) + ")";
    String meta = n.insertStamp("VALUES " + String.join(", ", values));
    // a metadata row with no data row is an orphan, a tombstone: taken over in place (4.5)
    String takeOver =
        n.stamp("COALESCE(" + n.txVersion + ", 0) + 1")
            + keyMatch
            + " AND "
            + n.txId
            + " IS DISTINCT FROM ? AND "
            + n.committed();
    List<Object> takeOverBinds = new ArrayList<>(List.of(Slot.TX_ID, Slot.NOW, Slot.NOW));
    takeOverBinds.addAll(matchBinds);
    takeOverBinds.add(Slot.TX_ID);
    String inFlight =
        "SELECT COUNT(*) FROM "
            + n.meta
            + " WHERE "
            + keyMatch
            + " AND "
            + n.txState
            + " IN (1, 2)";
    String insert = onDataTable(engine, ast, t, source);
    if (insert == null) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    return new InsertPlan(
        n,
        new Step(insert, all),
        new Step(meta, valueBinds),
        new Step(takeOver, takeOverBinds),
        new Step(inFlight, matchBinds),
        rows.size());
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
  // ponytail: autocommit is off on the vendor connection while the write runs; another thread
  // sharing the connection then joins its transaction, as with any JDBC transaction
  static long run(Connection vendor, Plan plan, Context context) throws SQLException {
    boolean auto = vendor.getAutoCommit();
    if (auto) {
      vendor.setAutoCommit(false);
    }
    long count;
    try {
      count = plan.apply(new Run(vendor, context));
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
    } finally {
      context.running(null);
    }
    if (auto) {
      vendor.setAutoCommit(true);
    }
    return count;
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
        for (Object b : step.binds) {
          if (b == Slot.TX_ID) {
            ps.setString(i, txId);
          } else if (b == Slot.NOW) {
            ps.setLong(i, now);
          } else {
            context.bind(ps, i, (Integer) b);
          }
          i++;
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
      int width = n.keyColumns.size();
      List<Row> rows = new ArrayList<>();
      try (PreparedStatement ps = run.prepare(keys, null);
          ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          if (inFlight(rs.getObject(2 * width + 3))) {
            throw new Aborted(
                "a row the statement writes is being written by a ScalarDB transaction");
          }
          rows.add(
              new Row(
                  Objects.requireNonNull(readKey(rs, 1, width)),
                  readKey(rs, width + 1, width),
                  rs.getString(2 * width + 1),
                  rs.getObject(2 * width + 2)));
        }
      }
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
          if (insertStamp(run, row) != 1) {
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
        return insertStamp(run, row);
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

    /** The stamp of a row without a metadata row, the key copied from its data row. */
    private int insertStamp(Run run, Row row) throws SQLException {
      try (PreparedStatement ps =
          run.prepare(
              n.insertStamp(
                  "SELECT "
                      + n.columns("d")
                      + ", ?, 3, 1, ?, ? FROM "
                      + n.data
                      + " d WHERE "
                      + n.keyEquals("d")))) {
        bind(ps, run.stamp(ps, 1), row.key);
        return ps.executeUpdate();
      }
    }
  }

  /** INSERT (4.3): the user's statement, then the metadata rows, taking over orphans (4.5). */
  private static final class InsertPlan extends Plan {
    final Step insert;
    final Step metaInsert;
    final Step takeOver;
    final Step inFlight;
    final int rows;

    InsertPlan(Names n, Step insert, Step metaInsert, Step takeOver, Step inFlight, int rows) {
      super(n);
      this.insert = insert;
      this.metaInsert = metaInsert;
      this.takeOver = takeOver;
      this.inFlight = inFlight;
      this.rows = rows;
    }

    @Override
    List<String> sql() {
      return List.of(insert.sql, metaInsert.sql, takeOver.sql, inFlight.sql);
    }

    @Override
    long apply(Run run) throws SQLException {
      long inserted;
      Savepoint sp = run.v.setSavepoint();
      try {
        try (PreparedStatement ps = run.prepare(insert, null)) {
          inserted = ps.executeUpdate();
        } catch (SQLException e) {
          if (!"23505".equals(e.getSQLState())) {
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
      int stamped;
      try (PreparedStatement ps = run.prepare(metaInsert, null)) {
        stamped = ps.executeUpdate();
      }
      if (stamped < rows) {
        int taken;
        try (PreparedStatement ps = run.prepare(takeOver, null)) {
          taken = ps.executeUpdate();
        }
        if (stamped + taken != rows) {
          throw new Aborted("a key the statement inserts has metadata in flight");
        }
      }
      run.context.anchors().wrote(run.txId);
      return inserted;
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
