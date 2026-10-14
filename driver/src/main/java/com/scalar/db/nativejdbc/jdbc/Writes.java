package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.StatementShape;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Collections;
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

  /** Binds the application's value of one of its placeholders to a driver statement. */
  interface Binder {
    void bind(PreparedStatement ps, int position, int placeholder) throws SQLException;
  }

  /** A value the driver binds itself, by role. */
  private enum Slot {
    TX_ID,
    NOW
  }

  /** A driver statement: its text, and per placeholder an application placeholder or a slot. */
  private static final class Step {
    final String sql;
    final List<Object> binds;

    Step(String sql, List<Object> binds) {
      this.sql = sql;
      this.binds = List.copyOf(binds);
    }
  }

  /** The statements of one write, and what its run needs to know of the table. */
  static final class Plan {
    private final StatementShape.Kind kind;
    private final Engine engine;
    private final TableInfo table;
    // UPDATE and DELETE: the keys with their metadata; the user's statement for one key, the key's
    // values bound after the application's placeholders; whether it re-applies a predicate
    private final @Nullable Step keys;
    private final @Nullable Step dml;
    private final boolean predicate;
    // INSERT: the user's statement, the metadata rows, the take-over of orphans, the in-flight test
    private final @Nullable Step insert;
    private final @Nullable Step metaInsert;
    private final @Nullable Step takeOver;
    private final @Nullable Step inFlight;
    private final int rows;

    private Plan(
        StatementShape.Kind kind,
        Engine engine,
        TableInfo table,
        @Nullable Step keys,
        @Nullable Step dml,
        boolean predicate,
        @Nullable Step insert,
        @Nullable Step metaInsert,
        @Nullable Step takeOver,
        @Nullable Step inFlight,
        int rows) {
      this.kind = kind;
      this.engine = engine;
      this.table = table;
      this.keys = keys;
      this.dml = dml;
      this.predicate = predicate;
      this.insert = insert;
      this.metaInsert = metaInsert;
      this.takeOver = takeOver;
      this.inFlight = inFlight;
      this.rows = rows;
    }

    /** The texts of the statements the plan runs, the CAS aside, which is chosen per key. */
    List<String> sql() {
      List<String> out = new ArrayList<>();
      for (Step s : new Step[] {keys, dml, insert, metaInsert, takeOver, inFlight}) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }
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
    if (shape.kind() == StatementShape.Kind.INSERT) {
      return insertPlan(engine, shape, t, n, all, tables);
    }
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
    if ((where != null && (span == null || inWhere == null)) || end < 0) {
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
    // the keys and their metadata: the committed images, as a read at this level sees them
    StringBuilder keys = new StringBuilder("SELECT ");
    for (String k : t.keyColumns()) {
      keys.append("d.").append(engine.quote(k)).append(", ");
    }
    keys.append("m.")
        .append(n.txId)
        .append(", m.")
        .append(n.txVersion)
        .append(", m.")
        .append(n.txState)
        .append(", m.")
        .append(engine.quote(t.keyColumns().get(0)))
        .append(" FROM (SELECT ");
    List<String> picked = new ArrayList<>();
    for (String k : t.keyColumns()) {
      picked.add(exposed + "." + engine.quote(k) + " AS " + engine.quote(k));
    }
    keys.append(String.join(", ", picked))
        .append(" FROM ")
        .append(n.view)
        .append(" ")
        .append(exposed);
    if (span != null) {
      keys.append(" WHERE ").append(source, span[0], span[1]);
    }
    keys.append(") d LEFT JOIN ").append(n.meta).append(" m ON ").append(n.keyJoin("m", "d"));
    // the user's statement for one key: the predicate re-applied, the key appended
    String keyed = n.keyEquals("");
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
    return new Plan(
        shape.kind(),
        engine,
        t,
        new Step(keys.toString(), new ArrayList<>(Objects.requireNonNull(inWhere))),
        new Step(dml, new ArrayList<>(all)),
        where != null,
        null,
        null,
        null,
        null,
        0);
  }

  private static Plan insertPlan(
      Engine engine, StatementShape shape, TableInfo t, Names n, List<Integer> all, String tables)
      throws SQLException {
    SqlAst ast = shape.ast();
    String source = ast.source();
    List<List<Expression>> rows = shape.insertKeyValues(t.keyColumns());
    if (rows == null) {
      throw refused(
          tables,
          "an INSERT into a ScalarDB table must list its columns and give every key column as a"
              + " value or a placeholder; a key the database generates is not supported yet");
    }
    StringBuilder meta =
        new StringBuilder("INSERT INTO ")
            .append(n.meta)
            .append(" (")
            .append(n.keyList)
            .append(", ")
            .append(n.txId)
            .append(", ")
            .append(n.txState)
            .append(", ")
            .append(n.txVersion)
            .append(", ")
            .append(n.preparedAt)
            .append(", ")
            .append(n.committedAt)
            .append(") VALUES ");
    List<Object> metaBinds = new ArrayList<>();
    StringBuilder match = new StringBuilder();
    List<Object> matchBinds = new ArrayList<>();
    for (int r = 0; r < rows.size(); r++) {
      List<String> values = new ArrayList<>();
      List<String> terms = new ArrayList<>();
      for (int i = 0; i < rows.get(r).size(); i++) {
        Expression e = rows.get(r).get(i);
        int[] span = ast.span(e);
        List<Integer> inside = span == null ? null : ast.placeholdersIn(span[0], span[1]);
        if (span == null || inside == null) {
          throw refused(tables, "the statement's text cannot be rewritten");
        }
        String text = source.substring(span[0], span[1]);
        values.add(text);
        metaBinds.addAll(inside);
        terms.add(engine.quote(t.keyColumns().get(i)) + " = " + text);
        matchBinds.addAll(inside);
      }
      meta.append(r == 0 ? "(" : ", (")
          .append(String.join(", ", values))
          .append(", ?, 3, 1, ?, ?)");
      metaBinds.add(Slot.TX_ID);
      metaBinds.add(Slot.NOW);
      metaBinds.add(Slot.NOW);
      match.append(r == 0 ? "(" : " OR (").append(String.join(" AND ", terms)).append(")");
    }
    meta.append(" ON CONFLICT DO NOTHING");
    // a metadata row with no data row is an orphan, a tombstone: taken over in place (4.5)
    String takeOver =
        "UPDATE "
            + n.meta
            + " SET "
            + n.txId
            + " = ?, "
            + n.txState
            + " = 3, "
            + n.txVersion
            + " = COALESCE("
            + n.txVersion
            + ", 0) + 1, "
            + n.preparedAt
            + " = ?, "
            + n.committedAt
            + " = ? WHERE ("
            + match
            + ") AND "
            + n.txId
            + " IS DISTINCT FROM ? AND COALESCE("
            + n.txState
            + ", 3) = 3";
    List<Object> takeOverBinds = new ArrayList<>(List.of(Slot.TX_ID, Slot.NOW, Slot.NOW));
    takeOverBinds.addAll(matchBinds);
    takeOverBinds.add(Slot.TX_ID);
    String inFlight =
        "SELECT COUNT(*) FROM " + n.meta + " WHERE (" + match + ") AND " + n.txState + " IN (1, 2)";
    String insert = onDataTable(engine, ast, t, source);
    if (insert == null) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    return new Plan(
        StatementShape.Kind.INSERT,
        engine,
        t,
        null,
        null,
        false,
        new Step(insert, new ArrayList<>(all)),
        new Step(meta.toString(), metaBinds),
        new Step(takeOver, takeOverBinds),
        new Step(inFlight, matchBinds),
        rows.size());
  }

  /**
   * Runs a write: in a transaction of its own in autocommit mode (4.4), otherwise in the
   * application's, which a driver-decided abort marks rollback-only.
   *
   * @param vendor the vendor connection
   * @param plan the plan
   * @param binder binds the application's values
   * @param rollbackOnly marks the application's transaction rollback-only
   * @return the update count
   * @throws SQLException a database error, or {@code 40001} for an abort the driver decides
   */
  static long run(Connection vendor, Plan plan, Binder binder, Runnable rollbackOnly)
      throws SQLException {
    boolean auto = vendor.getAutoCommit();
    if (auto) {
      vendor.setAutoCommit(false);
    }
    try {
      long count =
          plan.kind == StatementShape.Kind.INSERT
              ? insert(vendor, plan, binder)
              : updateOrDelete(vendor, plan, binder);
      if (auto) {
        vendor.commit();
      }
      return count;
    } catch (SQLException | RuntimeException e) {
      if (auto) {
        try {
          vendor.rollback();
        } catch (SQLException r) {
          e.addSuppressed(r);
        }
      } else if (e instanceof Aborted) {
        rollbackOnly.run();
      }
      throw e;
    } finally {
      if (auto) {
        vendor.setAutoCommit(true);
      }
    }
  }

  /** An abort the driver decides (4.4). */
  static final class Aborted extends SQLTransactionRollbackException {
    private static final long serialVersionUID = 1L;

    Aborted(String reason) {
      super(reason + "; roll the transaction back and retry it", "40001");
    }
  }

  private static long updateOrDelete(Connection v, Plan plan, Binder binder) throws SQLException {
    Names n = new Names(plan.engine, plan.table);
    int width = plan.table.keyColumns().size();
    List<Object[]> keys = new ArrayList<>();
    List<Object[]> anchors = new ArrayList<>(); // tx_id, tx_version, tx_state, has a metadata row
    Step select = Objects.requireNonNull(plan.keys);
    try (PreparedStatement ps = prepare(v, select, binder, null);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        Object[] key = new Object[width];
        for (int i = 0; i < width; i++) {
          key[i] = rs.getObject(i + 1);
        }
        Object state = rs.getObject(width + 3);
        if (state != null
            && (((Number) state).intValue() == 1 || ((Number) state).intValue() == 2)) {
          throw new Aborted(
              "a row the statement writes is being written by a ScalarDB transaction");
        }
        keys.add(key);
        anchors.add(
            new Object[] {
              rs.getString(width + 1),
              rs.getObject(width + 2),
              state,
              rs.getObject(width + 4) != null
            });
      }
    }
    String txId = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    long total = 0;
    Step dml = Objects.requireNonNull(plan.dml);
    for (int r = 0; r < keys.size(); r++) {
      Object[] key = keys.get(r);
      int written;
      try (PreparedStatement ps = prepare(v, dml, binder, key)) {
        written = ps.executeUpdate();
      }
      if (written == 0) {
        skipOrAbort(v, n, key, plan.predicate);
        continue;
      }
      if (written != 1) {
        throw new Aborted("a key matched " + written + " rows");
      }
      if (cas(v, n, plan.kind, key, anchors.get(r), txId, now) != 1) {
        throw new Aborted("a row the statement writes changed after it was read");
      }
      total++;
    }
    return total;
  }

  /** Step 2's zero-row rule: a row that stopped matching is skipped, anything else aborts. */
  private static void skipOrAbort(Connection v, Names n, Object[] key, boolean predicate)
      throws SQLException {
    String sql =
        "SELECT (SELECT COUNT(*) FROM "
            + n.data
            + " WHERE "
            + n.keyEquals("")
            + "), (SELECT "
            + n.txState
            + " FROM "
            + n.meta
            + " WHERE "
            + n.keyEquals("")
            + ")";
    long rows;
    Object state;
    try (PreparedStatement ps = v.prepareStatement(sql)) {
      bindKey(ps, 1, key);
      bindKey(ps, key.length + 1, key);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        rows = rs.getLong(1);
        state = rs.getObject(2);
      }
    }
    if (state != null && (((Number) state).intValue() == 1 || ((Number) state).intValue() == 2)) {
      throw new Aborted("a row the statement writes went in flight in a ScalarDB transaction");
    }
    if (rows == 0) {
      throw new Aborted("a row the statement writes was deleted after it was read");
    }
    if (!predicate) {
      throw new Aborted("a row the statement writes did not take the write");
    }
  }

  /** Step 3: the CAS on the key's metadata, or the stamp of a row without (4.4, 4.5). */
  private static int cas(
      Connection v,
      Names n,
      StatementShape.Kind kind,
      Object[] key,
      Object[] anchor,
      String txId,
      long now)
      throws SQLException {
    String seenId = (String) anchor[0];
    Object seenVersion = anchor[1];
    boolean hasMeta = (Boolean) anchor[3];
    if (seenId != null) {
      String versionTerm = seenVersion == null ? " IS NULL" : " = ?";
      String sql =
          kind == StatementShape.Kind.DELETE
              ? "DELETE FROM "
                  + n.meta
                  + " WHERE "
                  + n.keyEquals("")
                  + " AND "
                  + n.txId
                  + " = ? AND "
                  + n.txVersion
                  + versionTerm
                  + " AND "
                  + n.txState
                  + " = 3"
              : "UPDATE "
                  + n.meta
                  + " SET "
                  + n.txId
                  + " = ?, "
                  + n.txVersion
                  + " = COALESCE("
                  + n.txVersion
                  + ", 0) + 1, "
                  + n.txState
                  + " = 3, "
                  + n.preparedAt
                  + " = ?, "
                  + n.committedAt
                  + " = ? WHERE "
                  + n.keyEquals("")
                  + " AND "
                  + n.txId
                  + " = ? AND "
                  + n.txVersion
                  + versionTerm
                  + " AND "
                  + n.txState
                  + " = 3";
      try (PreparedStatement ps = v.prepareStatement(sql)) {
        int i = 1;
        if (kind != StatementShape.Kind.DELETE) {
          ps.setString(i++, txId);
          ps.setLong(i++, now);
          ps.setLong(i++, now);
        }
        i = bindKey(ps, i, key);
        ps.setString(i++, seenId);
        if (seenVersion != null) {
          ps.setObject(i, seenVersion);
        }
        return ps.executeUpdate();
      }
    }
    // NULL or no metadata: the stamp of I5 at version 1, kept as the tombstone after a DELETE
    String sql =
        hasMeta
            ? "UPDATE "
                + n.meta
                + " SET "
                + n.txId
                + " = ?, "
                + n.txVersion
                + " = 1, "
                + n.txState
                + " = 3, "
                + n.preparedAt
                + " = ?, "
                + n.committedAt
                + " = ? WHERE "
                + n.keyEquals("")
                + " AND "
                + n.txId
                + " IS NULL AND COALESCE("
                + n.txState
                + ", 3) = 3"
            : "INSERT INTO "
                + n.meta
                + " ("
                + n.txId
                + ", "
                + n.preparedAt
                + ", "
                + n.committedAt
                + ", "
                + n.keyList
                + ", "
                + n.txVersion
                + ", "
                + n.txState
                + ") VALUES (?, ?, ?, "
                + String.join(", ", Collections.nCopies(key.length, "?"))
                + ", 1, 3) ON CONFLICT DO NOTHING";
    try (PreparedStatement ps = v.prepareStatement(sql)) {
      ps.setString(1, txId);
      ps.setLong(2, now);
      ps.setLong(3, now);
      bindKey(ps, 4, key);
      return ps.executeUpdate();
    }
  }

  private static long insert(Connection v, Plan plan, Binder binder) throws SQLException {
    long inserted;
    Savepoint sp = v.setSavepoint();
    try (PreparedStatement ps = prepare(v, Objects.requireNonNull(plan.insert), binder, null)) {
      inserted = ps.executeUpdate();
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
      // the transaction goes on, as on the engines where a failed statement does not end it
      v.rollback(sp);
      try (PreparedStatement ps = prepare(v, Objects.requireNonNull(plan.inFlight), binder, null);
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
    v.releaseSavepoint(sp);
    String txId = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    int stamped;
    try (PreparedStatement ps =
        prepare(v, Objects.requireNonNull(plan.metaInsert), binder, null, txId, now)) {
      stamped = ps.executeUpdate();
    }
    if (stamped < plan.rows) {
      int taken;
      try (PreparedStatement ps =
          prepare(v, Objects.requireNonNull(plan.takeOver), binder, null, txId, now)) {
        taken = ps.executeUpdate();
      }
      if (stamped + taken != plan.rows) {
        throw new Aborted("a key the statement inserts has metadata in flight");
      }
    }
    return inserted;
  }

  private static PreparedStatement prepare(
      Connection v, Step step, Binder binder, Object @Nullable [] key) throws SQLException {
    return prepare(v, step, binder, key, "", 0);
  }

  /** The step prepared and bound: its binds, then the key's values, if any. */
  private static PreparedStatement prepare(
      Connection v, Step step, Binder binder, Object @Nullable [] key, String txId, long now)
      throws SQLException {
    PreparedStatement ps = v.prepareStatement(step.sql);
    try {
      int i = 1;
      for (Object b : step.binds) {
        if (b == Slot.TX_ID) {
          ps.setString(i, txId);
        } else if (b == Slot.NOW) {
          ps.setLong(i, now);
        } else {
          binder.bind(ps, i, (Integer) b);
        }
        i++;
      }
      if (key != null) {
        bindKey(ps, i, key);
      }
      return ps;
    } catch (SQLException | RuntimeException e) {
      ps.close();
      throw e;
    }
  }

  private static int bindKey(PreparedStatement ps, int from, Object[] key) throws SQLException {
    int i = from;
    for (Object k : key) {
      ps.setObject(i++, k);
    }
    return i;
  }

  /** The table's names, quoted, and the metadata columns. */
  private static final class Names {
    final String data;
    final String meta;
    final String view;
    final String keyList;
    final String txId;
    final String txState;
    final String txVersion;
    final String preparedAt;
    final String committedAt;
    private final Engine engine;
    private final List<String> keyColumns;

    Names(Engine engine, TableInfo t) {
      this.engine = engine;
      this.keyColumns = t.keyColumns();
      String ns = engine.quote(t.namespace()) + ".";
      this.data = ns + engine.quote(t.dataTable());
      this.meta = ns + engine.quote(Objects.requireNonNull(t.metadataTable(), "metadata table"));
      this.view = ns + engine.quote(t.readView(engine));
      List<String> quoted = new ArrayList<>();
      for (String k : keyColumns) {
        quoted.add(engine.quote(k));
      }
      this.keyList = String.join(", ", quoted);
      this.txId = engine.quote("tx_id");
      this.txState = engine.quote("tx_state");
      this.txVersion = engine.quote("tx_version");
      this.preparedAt = engine.quote("tx_prepared_at");
      this.committedAt = engine.quote("tx_committed_at");
    }

    /** {@code k1 = ? AND k2 = ?}, each column qualified by {@code q} when it is not empty. */
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
  }
}
