package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.StatementShape;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.BatchUpdateException;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
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
    /**
     * Binds the application's value of one of its placeholders to a driver statement; {@code
     * asStored} binds a float as the exact double a stored float compares as (todos 205, 208).
     */
    void bind(PreparedStatement ps, int position, int placeholder, boolean asStored)
        throws SQLException;

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
    // the placeholders that give a FLOAT key alone, matched by the driver against stored keys
    final Set<Integer> floatKeys;

    Step(String sql, List<Integer> binds) {
      this(sql, binds, Set.of());
    }

    Step(String sql, List<Integer> binds, Set<Integer> floatKeys) {
      this.sql = sql;
      this.binds = List.copyOf(binds);
      this.floatKeys = Set.copyOf(floatKeys);
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

    /** Whether the plan writes rows; a locking read only reads and locks them. */
    boolean writes() {
      return true;
    }

    abstract long apply(Run run) throws SQLException;

    /**
     * The write as one statement, which a batch sends with its other entries' (todo 329); null when
     * it has none.
     */
    @Nullable Step together() {
      return null;
    }

    /**
     * What {@link #together()}'s statement returned, read without recording anything; null when the
     * write needs more than that statement, and the entries are undone and run one by one.
     */
    @Nullable Together together(Run run, ResultSet rs) throws SQLException {
      throw new IllegalStateException("no statement to send together");
    }

    /**
     * The write over many entries of a batch at once, each a key's values (todo 329); null when the
     * plan has none.
     */
    @Nullable Merge merge() {
      return null;
    }

    /**
     * What {@link #merge()}'s statement returned for {@code entries} entries: each one's count,
     * recorded; null when one needs more than that statement, and the entries are undone and run
     * one by one.
     */
    long @Nullable [] merged(Run run, ResultSet rs, int entries) throws SQLException {
      throw new IllegalStateException("no merged statement");
    }

    /** The texts of the statements planned ahead, for tests; the CAS is chosen per key. */
    abstract List<String> sql();
  }

  /**
   * A write over many entries of a batch as one statement (todo 329), so that what a statement
   * costs around its execution is paid once: each entry's placeholders bound in turn, then the
   * stamp's.
   */
  static final class Merge {
    private final IntFunction<String> text;
    final int placeholders;
    private final Map<Integer, String> texts = new ConcurrentHashMap<>();

    Merge(IntFunction<String> text, int placeholders) {
      this.text = text;
      this.placeholders = placeholders;
    }

    /** The statement for {@code entries} entries. */
    String sql(int entries) {
      return texts.computeIfAbsent(entries, text::apply);
    }
  }

  /** At most this many entries of a batch are merged, which bounds their texts and parameters. */
  static final int MERGE_MAX = 64;

  /**
   * Plans a write statement, or says why the driver cannot run it yet.
   *
   * @param engine the engine
   * @param shape the statement's single-table shape
   * @param t the ScalarDB table it writes
   * @param tables the tables the statement names, for the refusal's message
   * @param snapshot whether the connection runs at SNAPSHOT or SERIALIZABLE, where a row in flight
   *     the predicate matches by its after image aborts too (design 4.2)
   * @param vendor the vendor connection, which an INSERT that lists no columns reads the data
   *     table's column order with, or {@code null} to leave its keys unknown
   * @return the plan
   * @throws SQLException 0AN01 for a write the driver cannot run yet
   */
  static Plan plan(
      Engine engine,
      StatementShape shape,
      TableInfo t,
      String tables,
      boolean snapshot,
      boolean locks,
      @Nullable Connection vendor)
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
    if (t.layout() == Layout.INLINE) {
      inlineRefusals(engine, shape, t, tables);
    }
    if (vendor != null && shape.kind() == StatementShape.Kind.INSERT) {
      String why = otherKeys(vendor, n);
      if (why != null) {
        throw refused(tables, why);
      }
    }
    return shape.kind() == StatementShape.Kind.INSERT
        ? insertPlan(shape, t, n, all, tables, snapshot, vendor == null ? null : columns(vendor, n))
        : keyedPlan(shape, t, n, all, tables, snapshot, locks);
  }

  /**
   * The writes an inline table refuses (milestone 10b): one naming a metadata column, which only
   * ScalarDB and the driver write, and an INSERT without a column list, whose values would fall to
   * the table's columns, the metadata ones among them.
   */
  private static void inlineRefusals(
      Engine engine, StatementShape shape, TableInfo t, String tables) throws SQLException {
    if (shape.kind() == StatementShape.Kind.INSERT && shape.insertColumns().isEmpty()) {
      throw refused(
          tables,
          "an INSERT into a table in ScalarDB's inline layout names its columns, as the table"
              + " holds ScalarDB's metadata columns too");
    }
    Set<String> data = new HashSet<>();
    for (String c : t.dataColumns()) {
      data.add(engine.columnKey(c));
    }
    // every column the statement names: its lists, and every reference, in a SET value or a
    // RETURNING item too (todo 293)
    List<String> named = new ArrayList<>(shape.insertColumns());
    named.addAll(shape.setColumns());
    named.addAll(shape.whereColumns());
    for (SqlAst.ColumnRef c : shape.ast().columnRefs()) {
      if (!c.isAllColumnsOfTable()) {
        named.add(unquoted(c.name()));
      }
    }
    for (String c : named) {
      if (metadataColumn(engine, data, c)) {
        throw refused(
            tables, "a write naming ScalarDB's metadata column " + c + " is not supported");
      }
    }
  }

  /** Whether {@code c}, unquoted, is one of an inline table's metadata columns, not a data one. */
  private static boolean metadataColumn(Engine engine, Set<String> data, String c) {
    String lower = c.toLowerCase(Locale.ROOT);
    return !data.contains(engine.columnKey(c))
        && (lower.startsWith("tx_") || lower.startsWith("before_"));
  }

  /** A name without the quotes around it, if any: {@code "x"}, {@code [x]} or {@code `x`}. */
  private static String unquoted(String name) {
    if (name.length() >= 2
        && "\"[`".indexOf(name.charAt(0)) >= 0
        && "\"]`".indexOf(name.charAt(name.length() - 1)) >= 0) {
      return name.substring(1, name.length() - 1);
    }
    return name;
  }

  /** Why the engine may store other key values than an INSERT gives; null when it cannot. */
  private static @Nullable String otherKeys(Connection vendor, Names n) throws SQLException {
    switch (n.engine) {
      case MYSQL:
        return storesOtherKeys(vendor, n);
      case ORACLE:
        return oracleTrigger(vendor, n);
      default:
        return null;
    }
  }

  /**
   * Why MySQL may store other key values than an INSERT gives, which the read-back by those values
   * would then find in an older row (todos 201, 214): a non-strict sql_mode, which truncates, or a
   * BEFORE INSERT trigger, which may set them; null when neither.
   */
  private static @Nullable String storesOtherKeys(Connection vendor, Names n) throws SQLException {
    try (PreparedStatement ps =
        vendor.prepareStatement(
            "SELECT @@SESSION.sql_mode, EXISTS (SELECT 1 FROM information_schema.triggers WHERE"
                + " event_object_schema = ? AND event_object_table = ? AND event_manipulation ="
                + " 'INSERT' AND action_timing = 'BEFORE')")) {
      ps.setString(1, n.namespace);
      ps.setString(2, n.dataTable);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        String mode = rs.getString(1).toUpperCase(Locale.ROOT);
        if (!mode.contains("STRICT_TRANS_TABLES") && !mode.contains("STRICT_ALL_TABLES")) {
          return "an INSERT of a ScalarDB table needs a strict sql_mode on MySQL";
        }
        if (rs.getBoolean(2)) {
          return "an INSERT of a ScalarDB table whose data table has a BEFORE INSERT trigger is not"
              + " supported on MySQL";
        }
        return null;
      }
    }
  }

  /**
   * Why Oracle may store other key values than an INSERT gives, as MySQL may (todo 201): a row
   * trigger on INSERT that runs before the row is stored, BEFORE EACH ROW or a COMPOUND trigger's
   * section (todo 275); null when there is none.
   */
  private static @Nullable String oracleTrigger(Connection vendor, Names n) throws SQLException {
    try (PreparedStatement ps =
        vendor.prepareStatement(
            "SELECT 1 FROM all_triggers WHERE table_owner = ? AND table_name = ? AND"
                + " triggering_event LIKE '%INSERT%' AND trigger_type IN ('BEFORE EACH ROW',"
                + " 'COMPOUND')")) {
      ps.setString(1, n.namespace);
      ps.setString(2, n.dataTable);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? "an INSERT of a ScalarDB table whose data table has a BEFORE INSERT or COMPOUND"
                + " trigger is not supported on Oracle"
            : null;
      }
    }
  }

  /**
   * The data table's columns in their own order with their types (todos 203, 208), a generated
   * one's (AUTO_INCREMENT, IDENTITY) followed by {@code " generated"} (todo 218).
   */
  private static Map<String, String> columns(Connection vendor, Names n) throws SQLException {
    Map<String, String> out = new LinkedHashMap<>();
    try (PreparedStatement ps =
        vendor.prepareStatement(
            n.engine == Engine.ORACLE
                // the full type, as a cast takes it, its length included (todo 277)
                ? "SELECT column_name, CASE WHEN data_type IN ('CHAR', 'VARCHAR2') THEN data_type"
                    + " || '(' || char_length || ' CHAR)' WHEN data_type IN ('NCHAR', 'NVARCHAR2')"
                    + " THEN data_type || '(' || char_length || ')' WHEN data_type = 'RAW' THEN"
                    + " 'RAW(' || data_length || ')' ELSE data_type END, CASE identity_column WHEN"
                    + " 'YES' THEN 1 ELSE 0 END FROM all_tab_columns WHERE owner = ? AND"
                    + " table_name = ? ORDER BY column_id"
                : "SELECT column_name, data_type, "
                    + (n.engine == Engine.POSTGRESQL
                        ? "FALSE"
                        : n.engine == Engine.SQLSERVER
                            ? "COLUMNPROPERTY(OBJECT_ID(QUOTENAME(table_schema) + '.'"
                                + " + QUOTENAME(table_name)), column_name, 'IsIdentity')"
                            : "extra LIKE '%auto_increment%'")
                    + " FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER"
                    + " BY ordinal_position")) {
      ps.setString(1, n.namespace);
      ps.setString(2, n.dataTable);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.put(
              rs.getString(1),
              rs.getString(2).toLowerCase(Locale.ROOT) + (rs.getBoolean(3) ? " generated" : ""));
        }
      }
    }
    return out;
  }

  private static Plan keyedPlan(
      StatementShape shape,
      TableInfo t,
      Names n,
      List<Integer> all,
      String tables,
      boolean snapshot,
      boolean locks)
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
            + n.readColumns("d")
            + ", "
            + n.readColumns("m")
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
    // SQL Server's UPDATE names the table in a FROM clause before the WHERE, which the key joins
    int from = span == null ? end : ast.startOfLastBefore("WHERE", span[0]);
    dml = onDataTable(engine, ast, t, true, dml, from);
    if (dml == null) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    // the same statement over every key the first step reads, gated on its checks, for the write
    // in one statement on PostgreSQL (todo 328): the text up to its end, a trailing comment left
    // out, which would run on over what follows it inside the CTE
    String everyKey =
        "("
            + n.columns("")
            + ") IN (SELECT "
            + Names.numbered("", "scalardb_native_d", n.keyColumns.size())
            + " FROM scalardb_native_keys) AND (SELECT scalardb_native_go FROM"
            + " scalardb_native_gate)";
    String dmlSet =
        engine != Engine.POSTGRESQL || t.layout() == Layout.INLINE || locks
            ? null
            : onDataTable(
                engine,
                ast,
                t,
                true,
                span == null
                    ? source.substring(0, end) + " WHERE " + everyKey
                    : source.substring(0, span[0])
                        + "("
                        + source.substring(span[0], span[1])
                        + ") AND ("
                        + everyKey
                        + ")"
                        + source.substring(span[1], end),
                from);
    // the keys read the committed images, whose rows in flight abort the write; at SNAPSHOT and
    // SERIALIZABLE a row in flight only its after image matches aborts it too
    Step afterImages =
        snapshot
            ? new Step(
                anyRow(
                    engine,
                    "SELECT 1 FROM "
                        + SplitRead.afterImages(engine, t, t.dataColumns())
                        + " "
                        + exposed
                        + (span == null ? "" : " WHERE " + source.substring(span[0], span[1]))),
                inWhere)
            : null;
    // at SERIALIZABLE on MySQL, MariaDB and SQL Server the predicate is read again on the data
    // table under a shared lock, whose next-key or key-range locks hold off a ScalarDB insert into
    // its range, and must return what the snapshot does (design 4.2)
    boolean hint = engine == Engine.SQLSERVER;
    Step locked =
        locks
            ? new Step(
                "SELECT "
                    + n.readColumns(exposed)
                    + " FROM "
                    + n.data
                    + " "
                    + exposed
                    + (hint ? " WITH (HOLDLOCK)" : "")
                    + (span == null ? "" : " WHERE " + source.substring(span[0], span[1]))
                    + (hint ? "" : " LOCK IN SHARE MODE"),
                inWhere)
            : null;
    // an inline DELETE is the anchored delete alone, which never re-applies the predicate: a row
    // it misses because a writer changed it is checked against the predicate as it is now, and
    // aborts the statement if it still matches, as the decoupled DML would (todo 292)
    Step matches =
        t.layout() == Layout.INLINE && shape.kind() == StatementShape.Kind.DELETE && span != null
            ? new Step(
                "SELECT 1 FROM "
                    + n.data
                    + " "
                    + exposed
                    + " WHERE ("
                    + source.substring(span[0], span[1])
                    + ") AND ("
                    + n.keyEquals(exposed)
                    + ")",
                inWhere)
            : null;
    boolean delete = shape.kind() == StatementShape.Kind.DELETE;
    boolean imported = t.layout() == Layout.DECOUPLED_IMPORTED;
    Merge merge =
        dmlSet == null || span == null || ast.tableRefs().size() != 1 || !fullKey(engine, where, t)
            ? null
            : mergedKeyed(n, ast, t, exposed, span, picked, snapshot, delete, imported, from, end);
    return new KeyedPlan(
        n,
        delete,
        imported,
        t.layout() == Layout.INLINE,
        afterImages,
        locked,
        new Step(keys, inWhere),
        new Step(dml, all),
        matches,
        where != null,
        dmlSet == null
            ? null
            : fusedKeyed(n, new Step(keys, inWhere), afterImages, dmlSet, all, delete, imported),
        merge);
  }

  /**
   * Whether {@code where} is the table's key alone: each key column, unqualified, equal to a
   * placeholder or a literal, once, and nothing else, so that an entry writes at most its own row.
   */
  private static boolean fullKey(Engine engine, @Nullable Expression where, TableInfo t) {
    List<Expression> conjuncts = new ArrayList<>();
    conjuncts(where, conjuncts);
    if (conjuncts.size() != t.keyColumns().size()) {
      return false;
    }
    Set<String> seen = new HashSet<>();
    for (Expression e : conjuncts) {
      if (!(e instanceof EqualsTo)) {
        return false;
      }
      Expression column = ((EqualsTo) e).getLeftExpression();
      Expression value = ((EqualsTo) e).getRightExpression();
      if (!(column instanceof Column)) {
        Expression other = column;
        column = value;
        value = other;
      }
      if (!(column instanceof Column)
          || ((Column) column).getTable() != null
          || !(value instanceof JdbcParameter
              || value instanceof LongValue
              || value instanceof StringValue)) {
        return false;
      }
      String name = engine.columnKey(engine.resolveIdentifier(((Column) column).getColumnName()));
      if (t.keyColumns().stream().noneMatch(k -> engine.columnKey(k).equals(name))
          || !seen.add(name)) {
        return false;
      }
    }
    return true;
  }

  private static void conjuncts(@Nullable Expression e, List<Expression> out) {
    if (e instanceof AndExpression) {
      conjuncts(((AndExpression) e).getLeftExpression(), out);
      conjuncts(((AndExpression) e).getRightExpression(), out);
    } else if (e instanceof ParenthesedExpressionList
        && ((ParenthesedExpressionList<?>) e).size() == 1) {
      conjuncts(((ParenthesedExpressionList<?>) e).get(0), out);
    } else if (e != null) {
      out.add(e);
    }
  }

  /** {@code source}'s characters {@code [from, to)}, each placeholder in them {@code p}'s. */
  private static String filled(
      String source, int from, int to, List<int[]> holes, IntFunction<String> p) {
    StringBuilder out = new StringBuilder();
    int at = from;
    for (int i = 0; i < holes.size(); i++) {
      int[] h = holes.get(i);
      if (h[0] >= from && h[1] <= to) {
        out.append(source, at, h[0]).append(p.apply(i + 1));
        at = h[1];
      }
    }
    return out.append(source, at, to).toString();
  }

  /**
   * The fused statement of {@link #fusedKeyed} over the entries of a batch (todo 329), each a row
   * of {@code scalardb_native_e}: the keys read per entry, which a lateral subquery keeps on the
   * key's index; the gate closed also when two entries reach one key, as one by one the second
   * would see the first's write; the DML joined to the entries, each placeholder its entry's
   * column; and per entry its key, whether the DML wrote it and the CAS stamped it, the gate, and
   * at SNAPSHOT and SERIALIZABLE whether an after image matched. Null when the text cannot be
   * rewritten.
   */
  private static @Nullable Merge mergedKeyed(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      List<String> picked,
      boolean snapshot,
      boolean delete,
      boolean imported,
      int from,
      int end) {
    List<int[]> holes = ast.placeholderSpans();
    if (holes == null || holes.isEmpty() || from < 0) {
      return null;
    }
    String source = ast.source();
    String e = "scalardb_native_e";
    IntFunction<String> p = j -> e + ".scalardb_native_p" + j;
    String predicate = filled(source, span[0], span[1], holes, p);
    int width = n.keyColumns.size();
    String d = "scalardb_native_d";
    String keys =
        "SELECT "
            + e
            + ".scalardb_native_n, "
            + n.readColumns("d")
            + ", "
            + n.readColumns("m")
            + ", m."
            + n.txId
            + ", m."
            + n.txVersion
            + ", m."
            + n.txState
            + " FROM "
            + e
            + " CROSS JOIN LATERAL (SELECT "
            + String.join(", ", picked)
            + " FROM "
            + n.view
            + " "
            + exposed
            + " WHERE "
            + predicate
            + " OFFSET 0) d LEFT JOIN "
            + n.meta
            + " m ON "
            + n.keyJoin("m", "d");
    String everyKey =
        "("
            + n.columns("")
            + ") IN (SELECT "
            + Names.numbered("", d, width)
            + " FROM scalardb_native_keys) AND (SELECT scalardb_native_go FROM"
            + " scalardb_native_gate)";
    String before = filled(source, 0, from, holes, p);
    String dml =
        onDataTable(
            n.engine,
            ast,
            t,
            true,
            before
                + (before.isEmpty() || Character.isWhitespace(before.charAt(before.length() - 1))
                    ? ""
                    : " ")
                + (delete ? "USING " : "FROM ")
                + e
                + " "
                + filled(source, from, span[0], holes, p)
                + "("
                + predicate
                + ") AND ("
                + everyKey
                + ")"
                + filled(source, span[1], end, holes, p),
            from);
    if (dml == null) {
      return null;
    }
    StringBuilder head = new StringBuilder("WITH ").append(e).append(" (scalardb_native_n");
    for (int j = 1; j <= holes.size(); j++) {
      head.append(", scalardb_native_p").append(j);
    }
    head.append(") AS (VALUES ");
    StringBuilder sql =
        new StringBuilder("), scalardb_native_keys (scalardb_native_n, ")
            .append(Names.numbered("", d, width))
            .append(", ")
            .append(Names.numbered("", "scalardb_native_m", width))
            .append(", scalardb_native_id, scalardb_native_version, scalardb_native_state) AS (")
            .append(keys)
            .append(")");
    if (snapshot) {
      sql.append(", scalardb_native_after AS (SELECT 1 FROM ")
          .append(e)
          .append(" CROSS JOIN LATERAL (SELECT 1 FROM ")
          .append(SplitRead.afterImages(n.engine, t, t.dataColumns()))
          .append(" ")
          .append(exposed)
          .append(" WHERE ")
          .append(predicate)
          .append(" OFFSET 0) scalardb_native_x LIMIT 1)");
    }
    sql.append(
            ", scalardb_native_gate (scalardb_native_go) AS (SELECT NOT EXISTS (SELECT 1 FROM"
                + " scalardb_native_keys WHERE scalardb_native_state IN (1, 2) OR"
                + " scalardb_native_m0 IS NULL)")
        .append(snapshot ? " AND NOT EXISTS (SELECT 1 FROM scalardb_native_after)" : "")
        .append(" AND NOT EXISTS (SELECT 1 FROM scalardb_native_keys GROUP BY ")
        .append(Names.numbered("", d, width))
        .append(" HAVING count(*) > 1)), scalardb_native_written AS (")
        .append(dml)
        .append(" RETURNING ")
        .append(e)
        .append(".scalardb_native_n AS scalardb_native_wn, ")
        .append(n.aliased("", "scalardb_native_w"))
        .append(")");
    List<String> metaKey = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      metaKey.add(n.engine.quote(n.keyColumns.get(i)) + " = k.scalardb_native_m" + i);
    }
    String cas =
        String.join(" AND ", metaKey)
            + " AND ("
            + Names.numbered("k", d, width)
            + ") IN (SELECT "
            + Names.numbered("", "scalardb_native_w", width)
            + " FROM scalardb_native_written) AND "
            + n.committed()
            + " AND "
            + n.txId
            + " IS NOT DISTINCT FROM k.scalardb_native_id";
    boolean remove = delete && !imported;
    String stamp =
        n.stamp(
            "CASE WHEN k.scalardb_native_id IS NULL THEN 1 ELSE COALESCE("
                + n.txVersion
                + ", 0) + 1 END");
    sql.append(", scalardb_native_stamped AS (")
        .append(stamp, 0, stamp.length() - "WHERE ".length())
        .append("FROM scalardb_native_keys k WHERE ")
        .append(cas)
        .append(" AND (k.scalardb_native_id IS NULL OR ")
        .append(n.txVersion)
        .append(" IS NOT DISTINCT FROM k.scalardb_native_version)")
        .append(remove ? " AND k.scalardb_native_id IS NULL" : "")
        .append(" RETURNING k.scalardb_native_n)");
    if (remove) {
      sql.append(", scalardb_native_removed AS (DELETE FROM ")
          .append(n.meta)
          .append(" USING scalardb_native_keys k WHERE ")
          .append(cas)
          .append(" AND k.scalardb_native_id IS NOT NULL AND ")
          .append(n.txVersion)
          .append(" IS NOT DISTINCT FROM k.scalardb_native_version RETURNING k.scalardb_native_n)");
    }
    sql.append(" SELECT ")
        .append(e)
        .append(".scalardb_native_n, ")
        .append(Names.numbered("k", d, width))
        .append(", ")
        .append(Names.numbered("k", "scalardb_native_m", width))
        .append(", k.scalardb_native_id, k.scalardb_native_version, k.scalardb_native_state,")
        .append(" EXISTS (SELECT 1 FROM scalardb_native_written w WHERE w.scalardb_native_wn = ")
        .append(e)
        .append(".scalardb_native_n), EXISTS (SELECT 1 FROM scalardb_native_stamped c WHERE")
        .append(" c.scalardb_native_n = ")
        .append(e)
        .append(".scalardb_native_n)")
        .append(
            remove
                ? " OR EXISTS (SELECT 1 FROM scalardb_native_removed c WHERE c.scalardb_native_n = "
                    + e
                    + ".scalardb_native_n)"
                : "")
        .append(", (SELECT scalardb_native_go FROM scalardb_native_gate)")
        .append(snapshot ? ", EXISTS (SELECT 1 FROM scalardb_native_after)" : "")
        .append(" FROM ")
        .append(e)
        .append(" LEFT JOIN scalardb_native_keys k ON k.scalardb_native_n = ")
        .append(e)
        .append(".scalardb_native_n ORDER BY 1");
    String rest = sql.toString();
    String row = ", ?".repeat(holes.size());
    return new Merge(
        k -> {
          StringBuilder out = new StringBuilder(head);
          for (int i = 1; i <= k; i++) {
            out.append(i == 1 ? "(" : ", (").append(i).append(row).append(')');
          }
          return out.append(rest).toString();
        },
        holes.size());
  }

  /**
   * The fused INSERT of {@link #fusedInsert} over the entries of a batch (todo 329): the one row of
   * the statement's VALUES once per entry, which holds every placeholder of it. Null for any other
   * INSERT.
   */
  private static @Nullable Merge mergedInsert(
      Names n, SqlAst ast, TableInfo t, @Nullable Expression row, int end, String keysReturned) {
    if (!(row instanceof ParenthesedExpressionList)
        || ((ParenthesedExpressionList<?>) row).isEmpty()) {
      return null;
    }
    ParenthesedExpressionList<?> items = (ParenthesedExpressionList<?>) row;
    int[] first = ast.span((Expression) items.get(0));
    int[] last = ast.span((Expression) items.get(items.size() - 1));
    String source = ast.source();
    if (first == null || last == null) {
      return null;
    }
    // the row's parentheses, with nothing but blanks between them and its items
    int open = first[0] - 1;
    while (open >= 0 && Character.isWhitespace(source.charAt(open))) {
      open--;
    }
    int close = last[1];
    while (close < source.length() && Character.isWhitespace(source.charAt(close))) {
      close++;
    }
    if (open < 0
        || close >= source.length()
        || source.charAt(open) != '('
        || source.charAt(close) != ')') {
      return null;
    }
    List<Integer> inside = ast.placeholdersIn(open, close + 1);
    if (inside == null
        || inside.isEmpty()
        || inside.size() != ast.parameterCount()
        || !source.substring(close + 1, end).isBlank()) {
      return null;
    }
    String values = ", " + source.substring(open, close + 1);
    String upToRow = source.substring(0, close + 1);
    IntFunction<@Nullable String> text =
        k ->
            onDataTable(
                n.engine,
                ast,
                t,
                true,
                upToRow + values.repeat(k - 1) + " RETURNING " + keysReturned,
                -1);
    if (text.apply(2) == null) {
      return null;
    }
    return new Merge(
        k -> fusedInsert(n, Objects.requireNonNull(text.apply(k)), false), inside.size());
  }

  /**
   * An UPDATE or DELETE as one statement on PostgreSQL (todo 328), its data-modifying CTEs the
   * steps of 4.3: the keys and their metadata as the first step reads them; at SNAPSHOT and
   * SERIALIZABLE, a row in flight only its after image matches; the gate, open when no key is in
   * flight or without a metadata row and no such after image matches; the user's DML over the keys,
   * under the gate; and the CAS of each key it wrote, which reads the DML's rows and so locks the
   * metadata row after the data row, as ScalarDB's prepare does. Its one row per key, or one of
   * NULLs without a key, is the key read, whether the DML wrote it and the CAS stamped it, the
   * gate, and at those levels whether an after image matched. The stamp's three values are bound
   * after the step's binds.
   */
  private static Step fusedKeyed(
      Names n,
      Step keys,
      @Nullable Step afterImages,
      String dml,
      List<Integer> all,
      boolean delete,
      boolean imported) {
    int width = n.keyColumns.size();
    String d = "scalardb_native_d";
    String keyColumns =
        Names.numbered("", d, width)
            + ", "
            + Names.numbered("", "scalardb_native_m", width)
            + ", scalardb_native_id, scalardb_native_version, scalardb_native_state";
    List<Integer> binds = new ArrayList<>(keys.binds);
    StringBuilder sql =
        new StringBuilder("WITH scalardb_native_keys (")
            .append(keyColumns)
            .append(") AS (")
            .append(keys.sql)
            .append(")");
    if (afterImages != null) {
      sql.append(", scalardb_native_after AS (").append(afterImages.sql).append(")");
      binds.addAll(afterImages.binds);
    }
    sql.append(
            ", scalardb_native_gate (scalardb_native_go) AS (SELECT NOT EXISTS (SELECT 1 FROM"
                + " scalardb_native_keys WHERE scalardb_native_state IN (1, 2) OR"
                + " scalardb_native_m0 IS NULL)")
        .append(afterImages == null ? "" : " AND NOT EXISTS (SELECT 1 FROM scalardb_native_after)")
        .append("), scalardb_native_written AS (")
        .append(dml)
        .append(" RETURNING ")
        .append(n.aliased("", "scalardb_native_w"))
        .append(")");
    binds.addAll(all);
    // the CAS of 4.3 step 3 on the key's metadata row, at the version read; a row with a NULL
    // tx_id is stamped at version 1 (4.4)
    List<String> metaKey = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      metaKey.add(n.engine.quote(n.keyColumns.get(i)) + " = k.scalardb_native_m" + i);
    }
    String cas =
        String.join(" AND ", metaKey)
            + " AND ("
            + Names.numbered("k", d, width)
            + ") IN (SELECT "
            + Names.numbered("", "scalardb_native_w", width)
            + " FROM scalardb_native_written) AND "
            + n.committed()
            + " AND "
            + n.txId
            + " IS NOT DISTINCT FROM k.scalardb_native_id";
    String returning = " RETURNING " + Names.numbered("k", d, width);
    // a DELETE of a created table removes the metadata row, but for a NULL tx_id, which is
    // stamped and kept (4.4); an imported table's keeps a tombstone (4.5)
    boolean remove = delete && !imported;
    String stamp =
        n.stamp(
            "CASE WHEN k.scalardb_native_id IS NULL THEN 1 ELSE COALESCE("
                + n.txVersion
                + ", 0) + 1 END");
    sql.append(", scalardb_native_stamped AS (")
        .append(stamp, 0, stamp.length() - "WHERE ".length())
        .append("FROM scalardb_native_keys k WHERE ")
        .append(cas)
        .append(" AND (k.scalardb_native_id IS NULL OR ")
        .append(n.txVersion)
        .append(" IS NOT DISTINCT FROM k.scalardb_native_version)")
        .append(remove ? " AND k.scalardb_native_id IS NULL" : "")
        .append(returning)
        .append(")");
    if (remove) {
      sql.append(", scalardb_native_removed AS (DELETE FROM ")
          .append(n.meta)
          .append(" USING scalardb_native_keys k WHERE ")
          .append(cas)
          .append(" AND k.scalardb_native_id IS NOT NULL AND ")
          .append(n.txVersion)
          .append(" IS NOT DISTINCT FROM k.scalardb_native_version")
          .append(returning)
          .append(")");
    }
    String written = n.keyJoinNumbered("w", "scalardb_native_w", "k", d);
    String stamped = n.keyJoinNumbered("c", d, "k", d);
    sql.append(" SELECT k.*, EXISTS (SELECT 1 FROM scalardb_native_written w WHERE ")
        .append(written)
        .append("), EXISTS (SELECT 1 FROM scalardb_native_stamped c WHERE ")
        .append(stamped)
        .append(")")
        .append(
            remove
                ? " OR EXISTS (SELECT 1 FROM scalardb_native_removed c WHERE " + stamped + ")"
                : "")
        .append(", (SELECT scalardb_native_go FROM scalardb_native_gate)")
        .append(afterImages == null ? "" : ", EXISTS (SELECT 1 FROM scalardb_native_after)")
        .append(" FROM (SELECT 1) scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE");
    return new Step(sql.toString(), binds);
  }

  private static Plan insertPlan(
      StatementShape shape,
      TableInfo t,
      Names n,
      List<Integer> all,
      String tables,
      boolean snapshot,
      @Nullable Map<String, String> tableColumns)
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
    if (t.layout() == Layout.INLINE
        && returning != null
        && !returning.equals("*")
        && returning.contains("*")) {
      throw refused(
          tables,
          "RETURNING with a * other than all of it on a table in ScalarDB's inline layout, whose"
              + " metadata columns it would return; name the columns");
    }
    // MySQL has no RETURNING: the keys are read back by the literals and placeholders the
    // statement gives them in its column list, one row each, or are the AUTO_INCREMENT values of
    // the one key column it leaves out, which the vendor driver reports (todos 186, 187)
    // SQL Server returns them by OUTPUT, before the rows the statement inserts
    boolean outputs = engine == Engine.SQLSERVER;
    boolean returns = engine == Engine.POSTGRESQL || engine == Engine.MARIADB || outputs;
    int output = outputs ? rowsStart(ast) : -1;
    if (outputs && output < 0) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    List<List<Expression>> rows =
        shape.insertKeyValues(
            t.keyColumns(), tableColumns == null ? null : List.copyOf(tableColumns.keySet()));
    if (returning != null && (!returns || outputs)) {
      throw refused(tables, "RETURNING is not supported on this engine");
    }
    if (returning != null && engine == Engine.MARIADB && !t.dataTable().equals(t.table())) {
      for (SqlAst.ColumnRef c : ast.columnRefs()) {
        if (c.qualifier() != null) {
          // MariaDB's INSERT takes no alias that would keep the table's name
          throw refused(tables, "a qualified column in RETURNING is not supported on MariaDB");
        }
      }
    }
    boolean generated = !returns && rows == null;
    if (generated && !leavesOutTheKey(engine, shape, t)) {
      throw refused(
          tables,
          "an INSERT of a ScalarDB table lists its columns and gives each key column a literal or"
              + " a placeholder, or leaves out an AUTO_INCREMENT key, on this engine");
    }
    String returnsKeys =
        returns && !outputs
            ? (n.inline && "*".equals(returning)
                    // the data columns, not the metadata ones beside them (milestone 10b)
                    ? source.substring(0, items) + " " + n.star("")
                    : source.substring(0, end))
                + (returning == null ? " RETURNING " : ", ")
            : "";
    String insert =
        onDataTable(
            engine,
            ast,
            t,
            engine == Engine.POSTGRESQL, // MySQL's and MariaDB's INSERT take no alias
            outputs
                ? source.substring(0, output)
                    + "OUTPUT "
                    + n.readColumns("INSERTED")
                    + " "
                    + source.substring(output)
                : returns ? returnsKeys + n.readColumns("") + source.substring(end) : source,
            -1);
    if (insert == null) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
    // on PostgreSQL the INSERT and its metadata rows are one statement (todo 328): the keys named
    // apart from the statement's own items, a trailing comment left out, which would run on over
    // what follows it inside the CTE
    String fused =
        engine == Engine.POSTGRESQL && !n.inline
            ? onDataTable(
                engine, ast, t, true, returnsKeys + n.aliased("", "scalardb_native_k"), -1)
            : null;
    // the keys as written, when every row gives them: a duplicate is then told from a key in
    // flight (4.3); a generated one is the application's duplicate
    Step inFlight = null;
    Step committed = null;
    Step repeats = null;
    List<Step> readBack = new ArrayList<>();
    if (rows != null) {
      List<String> match = new ArrayList<>();
      List<String> matchData = new ArrayList<>();
      List<String> tuples = new ArrayList<>();
      List<Integer> matchBinds = new ArrayList<>();
      Set<Integer> floatKeys = new HashSet<>();
      for (List<Expression> row : rows) {
        List<String> terms = new ArrayList<>();
        List<String> termsData = new ArrayList<>();
        List<String> values = new ArrayList<>();
        StringBuilder readBackOnly = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
          int[] span = ast.span(row.get(i));
          List<Integer> inside = span == null ? null : ast.placeholdersIn(span[0], span[1]);
          if (span == null || inside == null) {
            throw refused(tables, "the statement's text cannot be rewritten");
          }
          if (inside.size() == 1
              && row.get(i) instanceof JdbcParameter
              && isFloat(engine, tableColumns, t.keyColumns().get(i))) {
            floatKeys.add(inside.get(0));
          }
          String k = engine.quote(t.keyColumns().get(i));
          String value = source.substring(span[0], span[1]);
          String full = type(engine, tableColumns, t.keyColumns().get(i)).replace(" generated", "");
          if (engine == Engine.ORACLE && !full.isEmpty()) {
            // as the column holds it: a VARCHAR2 bind does not equal a blank-padded CHAR key, and
            // a number bound for a text key would convert every key to a number (todo 277)
            value = "CAST(" + value + " AS " + full + ")";
          }
          terms.add(k + " = " + value);
          termsData.add("d." + k + " = " + value);
          if (!returns
              && (engine == Engine.MYSQL || engine == Engine.MARIADB)
              && type(engine, tableColumns, t.keyColumns().get(i)).endsWith(" generated")) {
            // MySQL generates a key given as 0, and the read-back by 0 would find an older row
            // with key 0 (todo 218): read back no row, which refuses the write
            readBackOnly.append(" AND d.").append(k).append(" <> 0");
          }
          values.add(value);
          matchBinds.addAll(inside);
        }
        match.add("(" + String.join(" AND ", terms) + ")");
        matchData.add("(" + String.join(" AND ", termsData) + ")");
        tuples.add("(" + String.join(", ", values) + ")");
        if (!returns) {
          readBack.add(
              new Step(
                  "SELECT "
                      + n.readColumns("d")
                      + " FROM "
                      + n.data
                      + " d WHERE "
                      + String.join(" AND ", termsData)
                      + readBackOnly,
                  matchBinds.subList(matchBinds.size() - rowBinds(ast, row), matchBinds.size()),
                  floatKeys));
        }
      }
      if (snapshot && rows.size() > 1) {
        // a key the statement itself repeats is the application's duplicate (todo 178)
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= t.keyColumns().size(); i++) {
          columns.add("column" + i);
        }
        repeats =
            new Step(
                "SELECT 1 FROM (VALUES "
                    + String.join(", ", tuples)
                    + ") v GROUP BY "
                    + String.join(", ", columns)
                    + " HAVING COUNT(*) > 1",
                matchBinds,
                floatKeys);
      }
      if (snapshot) {
        // the snapshot cannot see a key written after it, which the duplicate came from: a key
        // committed in the snapshot is the application's duplicate, any other a conflict (4.3)
        committed =
            new Step(
                "SELECT COUNT(*) FROM "
                    + n.data
                    + " d LEFT JOIN "
                    + n.meta
                    + " m ON "
                    + n.keyJoin("m", "d")
                    + " WHERE ("
                    + String.join(" OR ", matchData)
                    + ") AND (m."
                    + n.txState
                    + " IS NULL OR m."
                    + n.txState
                    + " = 3)",
                matchBinds,
                floatKeys);
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
              matchBinds,
              floatKeys);
    }
    SqlAst.TableRef ref = shape.table();
    Merge merge =
        fused == null || returning != null
            ? null
            : mergedInsert(n, ast, t, shape.insertRow(), end, n.aliased("", "scalardb_native_k"));
    return new InsertPlan(
        n,
        merge,
        new Step(insert, all),
        fused == null ? null : new Step(fusedInsert(n, fused, inFlight != null), all),
        readBack,
        inFlight,
        committed,
        repeats,
        returning,
        Router.exposed(ref),
        type(engine, tableColumns, t.keyColumns().get(0)).endsWith(" generated"));
  }

  /** The savepoint a duplicate is told under on PostgreSQL, set in the INSERT's own round trip. */
  private static final String SAVEPOINT = "scalardb_native_write";

  /**
   * An INSERT as one round trip on PostgreSQL (todo 328): the user's INSERT, which returns the keys
   * it wrote, and their metadata rows inserted from them, a key with one already skipped, as
   * data-modifying CTEs of one statement; its rows are the INSERT's, then how many keys were
   * stamped. Where a duplicate is told from a key in flight, it runs under a savepoint set and
   * released in the same round trip, which pgjdbc sends with the statement. The stamp's three
   * values are bound after the user's.
   */
  private static String fusedInsert(Names n, String insert, boolean savepoint) {
    String sql =
        "WITH scalardb_native_inserted AS ("
            + insert
            + "), scalardb_native_stamped AS ("
            + n.insertStamp(
                "SELECT "
                    + Names.numbered("", "scalardb_native_k", n.keyColumns.size())
                    + ", ?, 3, 1, ?, ? FROM scalardb_native_inserted")
            + " RETURNING 1) SELECT scalardb_native_inserted.*, (SELECT COUNT(*) FROM"
            + " scalardb_native_stamped) FROM scalardb_native_inserted";
    return savepoint
        ? "SAVEPOINT " + SAVEPOINT + "; " + sql + "; RELEASE SAVEPOINT " + SAVEPOINT
        : sql;
  }

  /**
   * Whether a column is single precision: FLOAT on MySQL and MariaDB, REAL on PostgreSQL and SQL
   * Server, where FLOAT is double, BINARY_FLOAT on Oracle.
   */
  private static boolean isFloat(
      Engine engine, @Nullable Map<String, String> columns, String column) {
    String type = type(engine, columns, column).replace(" generated", "");
    return type.equals("real")
        || type.equals("binary_float")
        || (type.equals("float") && (engine == Engine.MYSQL || engine == Engine.MARIADB));
  }

  /** A column's entry in {@link #columns}, or "" when unknown. */
  private static String type(Engine engine, @Nullable Map<String, String> columns, String column) {
    if (columns != null) {
      for (Map.Entry<String, String> c : columns.entrySet()) {
        if (engine.columnKey(c.getKey()).equals(engine.columnKey(column))) {
          return c.getValue();
        }
      }
    }
    return "";
  }

  /**
   * Where an INSERT's rows start, its VALUES, SELECT or DEFAULT VALUES after the table's name and
   * column list: SQL Server's OUTPUT goes there. -1 when that cannot be told.
   */
  private static int rowsStart(SqlAst ast) {
    int[] name = ast.nameSpan(ast.tableRefs().get(0));
    if (name == null) {
      return -1;
    }
    int at = -1;
    for (String word : List.of("VALUES", "SELECT", "DEFAULT")) {
      int found = ast.startOfFirstAfter(word, name[1]);
      if (found >= 0 && (at < 0 || found < at)) {
        at = found;
      }
    }
    return at;
  }

  /** The placeholders in one row's key values. */
  private static int rowBinds(SqlAst ast, List<Expression> row) {
    int count = 0;
    for (Expression e : row) {
      int[] span = Objects.requireNonNull(ast.span(e));
      count += Objects.requireNonNull(ast.placeholdersIn(span[0], span[1])).size();
    }
    return count;
  }

  /** Whether the INSERT lists its columns and leaves out the table's one key column. */
  private static boolean leavesOutTheKey(Engine engine, StatementShape shape, TableInfo t) {
    if (t.keyColumns().size() != 1 || shape.insertColumns().isEmpty()) {
      return false;
    }
    String key = engine.columnKey(t.keyColumns().get(0));
    return shape.insertColumns().stream().noneMatch(c -> engine.columnKey(c).equals(key));
  }

  /**
   * The user's text with the table named by its data table, which a created table's name, a view,
   * is not: the name as written stays as the alias, where {@code alias}, so that qualifiers keep
   * working. {@code text} is the source with edits only after the name; {@code where}, where an
   * UPDATE's WHERE starts in the source, or -1, is where SQL Server's FROM clause goes.
   *
   * @return the text, or {@code null} when the name cannot be placed in it
   */
  private static @Nullable String onDataTable(
      Engine engine, SqlAst ast, TableInfo t, boolean alias, String text, int where) {
    if (t.dataTable().equals(t.table())) {
      return text;
    }
    SqlAst.TableRef ref = ast.tableRefs().get(0);
    int[] span = ast.nameSpan(ref);
    if (span == null) {
      return null;
    }
    String data = engine.quote(t.namespace()) + "." + engine.quote(t.dataTable());
    // a table alias: PostgreSQL's INSERT takes it with AS only, Oracle takes no AS
    String as =
        ref.hasAlias() || !alias ? "" : (engine == Engine.ORACLE ? " " : " AS ") + ref.name();
    String before = text.substring(0, span[0]);
    if (engine == Engine.SQLSERVER && ast.statement() instanceof Update) {
      // SQL Server's UPDATE takes no alias: UPDATE alias SET ... FROM table AS alias WHERE ...
      if (ref.hasAlias() || where < span[1]) {
        return null;
      }
      return before
          + ref.name()
          + text.substring(span[1], where)
          + " FROM "
          + data
          + " AS "
          + ref.name()
          + " "
          + text.substring(where);
    }
    if ((engine == Engine.MARIADB || engine == Engine.SQLSERVER)
        && !as.isEmpty()
        && ast.statement() instanceof Delete) {
      // MariaDB's and SQL Server's DELETE of one table take no alias: DELETE alias FROM table AS
      // alias
      int from = ast.startOfLastBefore("FROM", span[0]);
      if (from < 0) {
        return null;
      }
      before = before.substring(0, from) + ref.name() + " " + before.substring(from);
    }
    return before + data + as + text.substring(span[1]);
  }

  private static SQLException refused(String tables, String reason) {
    return Errors.unsupported(reason + tables);
  }

  /**
   * Whether a vendor error ended the transaction, or left it fit only for a rollback, as Oracle's
   * serialization failures do: one of class 40, except a MySQL or MariaDB lock wait timeout, which
   * undoes the statement only unless innodb_rollback_on_timeout is on, and which MariaDB's driver
   * reports as a plain error (todos 204, 211); and MariaDB's 1020, a locking read of a row changed
   * since the snapshot under innodb_snapshot_isolation, which ends it too (todo 248). On SQL
   * Server, whatever the error, whether a transaction is left.
   */
  static boolean endsTransaction(Connection vendor, SQLException e) {
    try {
      int code = e.getErrorCode();
      String product = vendor.getMetaData().getDatabaseProductName();
      if (product.equals("Microsoft SQL Server")) {
        // a deadlock, and many another error there, a conversion's among them, ends the
        // transaction: what remains of it says so
        try (Statement s = vendor.createStatement();
            ResultSet rs = s.executeQuery("SELECT @@TRANCOUNT")) {
          return e instanceof SQLTransactionRollbackException || !rs.next() || rs.getInt(1) == 0;
        }
      }
      if (oracleSerialization(e)) {
        // the transaction goes on, but its later writes fail as well, rows unchanged included
        return true;
      }
      if (code == 1020) {
        return product.equals("MariaDB") || e instanceof SQLTransactionRollbackException;
      }
      if (code == 1205 && (product.equals("MySQL") || product.equals("MariaDB"))) {
        // 1205 is a deadlock elsewhere
        try (Statement s = vendor.createStatement();
            ResultSet rs = s.executeQuery("SELECT @@innodb_rollback_on_timeout")) {
          return !rs.next() || rs.getBoolean(1);
        }
      }
    } catch (SQLException r) {
      e.addSuppressed(r);
      return true;
    }
    return e instanceof SQLTransactionRollbackException;
  }

  /**
   * SQL Server's update conflict at SNAPSHOT, error 3960, which its driver reports under a plain
   * SQLState, and Oracle's serialization failures, under 72000, as the retryable conflicts they
   * are: first-committer-wins (design 5). A batch's keeps its update counts (todo 285).
   */
  static SQLException asRetryable(SQLException e) {
    if (!(e.getErrorCode() == 3960 && String.valueOf(e.getSQLState()).startsWith("S0"))
        && !oracleSerialization(e)) {
      return e;
    }
    if (e instanceof BatchUpdateException) {
      return new BatchUpdateException(
          e.getMessage(),
          "40001",
          e.getErrorCode(),
          ((BatchUpdateException) e).getLargeUpdateCounts(),
          e);
    }
    return new SQLTransactionRollbackException(e.getMessage(), "40001", e.getErrorCode(), e);
  }

  /**
   * Oracle's serialization failures at its SERIALIZABLE, the driver's SNAPSHOT: ORA-08177, a row
   * changed since the snapshot, and ORA-08176, a consistent read that fails; ScalarDB retries both.
   */
  private static boolean oracleSerialization(SQLException e) {
    return (e.getErrorCode() == 8177 || e.getErrorCode() == 8176)
        && "72000".equals(e.getSQLState());
  }

  /** An Oracle constraint as its messages name it, {@code (OWNER.NAME)}. */
  private static final Pattern ORA_CONSTRAINT =
      Pattern.compile("\\(([A-Za-z0-9_$#]+)\\.([A-Za-z0-9_$#]+)\\)");

  /**
   * The engine's shared row lock: the clause after a query, or SQL Server's table hints (design
   * 4.2).
   */
  static String shareLock(Engine engine) {
    switch (engine) {
      case MARIADB:
        return "LOCK IN SHARE MODE";
      case SQLSERVER:
        return "REPEATABLEREAD, ROWLOCK";
      default:
        return "FOR SHARE";
    }
  }

  /** A query with a row when {@code select} has one, and with none when it has none. */
  static String anyRow(Engine engine, String select) {
    switch (engine) {
      case SQLSERVER:
        return "SELECT 1 WHERE EXISTS (" + select + ")";
      case ORACLE:
        return "SELECT 1 FROM dual WHERE EXISTS (" + select + ")";
      default:
        return select + " LIMIT 1";
    }
  }

  /**
   * Releases a savepoint; SQL Server's and Oracle's drivers cannot, and the transaction's end does.
   */
  static void release(Engine engine, Connection vendor, Savepoint sp) throws SQLException {
    if (engine != Engine.SQLSERVER && engine != Engine.ORACLE) {
      vendor.releaseSavepoint(sp);
    }
  }

  /** The savepoint a batch's entries sent together are undone to (todo 329). */
  private static final String TOGETHER = "scalardb_native_batch";

  /** At most this many entries of a batch are sent together, which bounds their texts. */
  static final int TOGETHER_MAX = 16;

  /** One entry's outcome of {@link #applyTogether}, recorded once all of them are known. */
  static final class Together {
    final Run run;
    final long count;
    final @Nullable Keys inserted;

    /**
     * {@code wrote}: the entry's stamp is the transaction's own at once, as the later entries'
     * checks of anchors take it one by one; undone, it is on no row.
     */
    Together(Run run, long count, @Nullable Keys inserted, boolean wrote) {
      this.run = run;
      this.count = count;
      this.inserted = inserted;
      if (wrote) {
        run.context.anchors().wrote(run.txId);
      }
    }

    void record() {
      if (inserted != null) {
        run.context.inserted(inserted);
      }
    }
  }

  /**
   * Applies entries of a batch in one round trip on PostgreSQL (todo 329): their statements of
   * {@link Plan#together()} as one text, under a savepoint set in it. Null when one needs more than
   * its statement (a row in flight, a tombstone, a key the DML skipped, a conflict, an error): the
   * entries are undone to the savepoint, nothing recorded, and the caller runs them one by one,
   * which tells the outcome as before. {@code release} releases first the savepoint an earlier text
   * left set, which otherwise stays to the next such text or the transaction's end.
   */
  static long @Nullable [] applyTogether(
      Connection vendor, List<Plan> plans, List<Context> contexts, boolean release)
      throws SQLException {
    List<Run> runs = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(release ? "RELEASE SAVEPOINT " + TOGETHER + "; " : "")
            .append("SAVEPOINT ")
            .append(TOGETHER);
    for (int i = 0; i < plans.size(); i++) {
      runs.add(new Run(vendor, contexts.get(i)));
      sql.append("; ").append(Objects.requireNonNull(plans.get(i).together()).sql);
    }
    Run lead = runs.get(0);
    List<Together> outcomes = new ArrayList<>();
    try (PreparedStatement ps = lead.prepare(sql.toString())) {
      int at = 1;
      for (int i = 0; i < plans.size(); i++) {
        Step step = Objects.requireNonNull(plans.get(i).together());
        for (int b : step.binds) {
          contexts.get(i).bind(ps, at++, b, step.floatKeys.contains(b));
        }
        at = runs.get(i).stamp(ps, at);
      }
      try {
        boolean rows = ps.execute();
        for (int i = 0; i < plans.size(); i++) {
          while (!rows) {
            if (ps.getUpdateCount() == -1) {
              throw new SQLException("a batch entry returned no rows", "XX000");
            }
            rows = ps.getMoreResults();
          }
          Together t;
          try (ResultSet rs = ps.getResultSet()) {
            t = plans.get(i).together(runs.get(i), rs);
          }
          if (t == null) {
            undoTogether(vendor, null);
            return null;
          }
          outcomes.add(t);
          rows = ps.getMoreResults();
        }
      } catch (SQLException e) {
        if (!undoable(e)) {
          throw e;
        }
        undoTogether(vendor, e);
        return null;
      }
    } finally {
      lead.context.running(null);
    }
    long[] counts = new long[outcomes.size()];
    for (int i = 0; i < counts.length; i++) {
      outcomes.get(i).record();
      counts[i] = outcomes.get(i).count;
    }
    return counts;
  }

  /**
   * Applies entries of a batch of one plan as its {@link Plan#merge()} statement (todo 329), under
   * the savepoint of entries sent together, released first as {@link #applyTogether} does. Null
   * when one needs more than the statement: the entries are undone to the savepoint, nothing
   * recorded, and the caller runs them one by one. They share one stamp, as one statement's keys
   * do.
   */
  static long @Nullable [] applyMerged(
      Connection vendor, Plan plan, List<Context> contexts, boolean release) throws SQLException {
    Merge m = Objects.requireNonNull(plan.merge());
    Run run = new Run(vendor, contexts.get(0));
    String sql =
        (release ? "RELEASE SAVEPOINT " + TOGETHER + "; " : "")
            + "SAVEPOINT "
            + TOGETHER
            + "; "
            + m.sql(contexts.size());
    try (PreparedStatement ps = run.prepare(sql)) {
      int at = 1;
      for (Context c : contexts) {
        for (int j = 1; j <= m.placeholders; j++) {
          c.bind(ps, at++, j, false);
        }
      }
      run.stamp(ps, at);
      try {
        boolean rows = ps.execute();
        while (!rows) {
          if (ps.getUpdateCount() == -1) {
            throw new SQLException("a merged batch returned no rows", "XX000");
          }
          rows = ps.getMoreResults();
        }
        long[] counts;
        try (ResultSet rs = ps.getResultSet()) {
          counts = plan.merged(run, rs, contexts.size());
        }
        if (counts == null) {
          undoTogether(vendor, null);
        }
        return counts;
      } catch (SQLException e) {
        if (!undoable(e)) {
          throw e;
        }
        undoTogether(vendor, e);
        return null;
      }
    } finally {
      run.context.running(null);
    }
  }

  /**
   * Whether a failure of entries sent together is one of their statements', after the text's
   * savepoint was set, and so undone to it: a data error, a constraint, a conflict, a name. Any
   * other may have come before it (a transaction already failed, a timeout or a cancel, a lost
   * connection, the server's resources, the driver's own), where the savepoint undone to would be
   * the one an earlier text left set: the batch ends as it would at an entry, without the replay.
   */
  private static boolean undoable(SQLException e) {
    String state = e.getSQLState();
    return state != null
        && (state.startsWith("22")
            || state.startsWith("23")
            || state.startsWith("40")
            || state.startsWith("42")
            || state.startsWith("P0"));
  }

  /** Undoes a batch's entries sent together; {@code e}, the failure that undoes them, if any. */
  private static void undoTogether(Connection vendor, @Nullable SQLException e)
      throws SQLException {
    try (Statement s = vendor.createStatement()) {
      s.execute("ROLLBACK TO SAVEPOINT " + TOGETHER);
    } catch (SQLException r) {
      if (e == null) {
        throw r;
      }
      e.addSuppressed(r); // the connection is gone, or the savepoint with the transaction
      throw e;
    }
  }

  /** Work run in a write's transaction. */
  interface Work {
    void run() throws SQLException;
  }

  /** Applies a plan in the transaction {@link #within} keeps. */
  static long apply(Connection vendor, Plan plan, Context context) throws SQLException {
    // where a failed statement leaves the transaction open, a write that fails part way is undone
    // to its start, as the engine undoes a statement of its own
    // ponytail: two round trips per write on MySQL and MariaDB
    Savepoint sp = plan.n.engine == Engine.POSTGRESQL ? null : vendor.setSavepoint();
    try {
      long count = plan.apply(new Run(vendor, context));
      if (sp != null) {
        release(plan.n.engine, vendor, sp);
      }
      return count;
    } catch (SQLException | RuntimeException e) {
      if (sp != null) {
        try {
          vendor.rollback(sp);
        } catch (SQLException r) {
          e.addSuppressed(r); // a deadlock already ended the transaction
        }
      }
      throw e;
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
   * @param readOnly whether the application set the connection read-only
   * @param rollbackOnly marks the application's transaction rollback-only
   * @param writes whether the work writes rows, which a read-only connection refuses
   * @param work the writes
   * @throws SQLException a database error, a timeout, or {@code 40001} for an abort the driver
   *     decides
   */
  // ponytail: autocommit is off on the vendor connection while the write runs; another thread
  // sharing the connection then joins its transaction, as with any JDBC transaction
  static void within(
      Connection vendor, boolean readOnly, Runnable rollbackOnly, boolean writes, Work work)
      throws SQLException {
    if (readOnly && !writes && "MySQL".equals(vendor.getMetaData().getDatabaseProductName())) {
      // Connector/J refuses a savepoint, which every plan runs under, on a read-only connection
      // (it sends SAVEPOINT through executeUpdate); MariaDB's driver does not (todo 301)
      throw new SQLException(
          "a locking read of a ScalarDB table, as every read at SERIALIZABLE on MySQL is, is not"
              + " supported in a read-only transaction on MySQL: MySQL Connector/J refuses the"
              + " savepoints it runs under; leave the transaction read-write",
          "25006");
    }
    if (writes && readOnly) {
      // a read-only transaction records no anchors, so its writes would be lost updates where the
      // vendor lets them through, as pgjdbc's readOnlyMode=ignore does (todo 160)
      throw new SQLException("a read-only connection cannot write a ScalarDB table", "25006");
    }
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
      } else if (e instanceof RuntimeException
          || "57014".equals(((SQLException) e).getSQLState())
          || endsTransaction(vendor, (SQLException) e)) {
        // a timeout or a cancel the driver raises itself can come between a key's DML and its
        // stamp, in a transaction the vendor would still commit (todo 157); a vendor error part
        // way ends the transaction on PostgreSQL, and is undone to the write's start elsewhere,
        // but a deadlock there ends it, which the application must see too (todo 199)
        rollbackOnly.run();
      }
      if (e instanceof SQLException) {
        throw asRetryable((SQLException) e);
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
      return prepare(sql, scrollable, null);
    }

    /**
     * As {@link #prepare(String, boolean)}, reporting the keys the database generates: those of
     * {@code generated}'s columns, or the vendor's choice when it names none, as MySQL reports its
     * AUTO_INCREMENT and Oracle a ROWID unless asked for columns.
     */
    private PreparedStatement prepare(String sql, boolean scrollable, String @Nullable [] generated)
        throws SQLException {
      checkCancelled();
      PreparedStatement ps =
          scrollable
              ? v.prepareStatement(
                  sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
              : generated == null
                  ? v.prepareStatement(sql)
                  : generated.length == 0
                      ? v.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                      : v.prepareStatement(sql, generated);
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
      return prepare(step, key, scrollable, null);
    }

    PreparedStatement prepare(
        Step step, Object @Nullable [] key, boolean scrollable, String @Nullable [] generated)
        throws SQLException {
      PreparedStatement ps = prepare(step.sql, scrollable, generated);
      try {
        int i = 1;
        for (int b : step.binds) {
          context.bind(ps, i++, b, step.floatKeys.contains(b));
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
      if (k instanceof Float) {
        // as the stored float's exact double: MySQL and MariaDB compare an uncast 0.1 as a double,
        // which no stored float equals (todo 192)
        ps.setDouble(i++, ((Float) k).doubleValue());
      } else {
        ps.setObject(i++, k);
      }
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

  /** How many keys of {@code n}'s table one statement binds: SQL Server takes 2,100 parameters. */
  private static int chunk(Names n) {
    return n.engine == Engine.SQLSERVER ? Math.max(1, 2000 / n.keyColumns.size()) : CHUNK;
  }

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
      if ("time".equals(name) && onSqlServer(rs)) {
        // mssql-jdbc binds a LocalTime as datetime, which a time cannot be compared with (254)
        key[i] = rs.getString(c);
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

  private static boolean onSqlServer(ResultSet rs) throws SQLException {
    Statement st = rs.getStatement();
    return st != null
        && "Microsoft SQL Server".equals(st.getConnection().getMetaData().getDatabaseProductName());
  }

  static boolean inFlight(@Nullable Object state) {
    return state != null && (((Number) state).intValue() == 1 || ((Number) state).intValue() == 2);
  }

  /** UPDATE and DELETE (4.3): per key, the user's statement first, the CAS second. */
  private static final class KeyedPlan extends Plan {
    final boolean delete;
    final boolean imported;
    // the metadata in the row: a DELETE is the conditional delete of the row itself (milestone 10b)
    final boolean inline;
    final @Nullable Step afterImages;
    final @Nullable Step locked;
    final Step keys;
    final Step dml;
    // an inline DELETE's predicate over one key, as the row is now
    final @Nullable Step matches;
    final boolean predicate;
    // the steps as one statement, on PostgreSQL (todo 328); the steps one by one when a key has no
    // metadata row
    final @Nullable Step fused;
    // a batch's entries of this plan as one statement, for a predicate that is the key (todo 329)
    final @Nullable Merge merge;

    KeyedPlan(
        Names n,
        boolean delete,
        boolean imported,
        boolean inline,
        @Nullable Step afterImages,
        @Nullable Step locked,
        Step keys,
        Step dml,
        @Nullable Step matches,
        boolean predicate,
        @Nullable Step fused,
        @Nullable Merge merge) {
      super(n);
      this.delete = delete;
      this.imported = imported;
      this.inline = inline;
      this.afterImages = afterImages;
      this.locked = locked;
      this.keys = keys;
      this.dml = dml;
      this.matches = matches;
      this.predicate = predicate;
      this.fused = fused;
      this.merge = merge;
    }

    @Override
    List<String> sql() {
      List<String> out = new ArrayList<>();
      for (Step s : Arrays.asList(afterImages, locked, keys, dml, fused)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }

    @Override
    long apply(Run run) throws SQLException {
      if (fused != null) {
        Long written = applyFused(run, fused);
        if (written != null) {
          return written;
        }
      }
      return applySteps(run);
    }

    /**
     * The write as one statement, then the checks of 4.3 on what it returns: at every level each is
     * an abort, which undoes the writes with the transaction, but for a row in flight, which the
     * gate keeps the statement from writing, so that READ COMMITTED's bounded wait holds no lock
     * (section 6), and a key the DML skips, which step 2's zero-row rule tells. Null, nothing
     * written, when a key has no metadata row: the steps one by one stamp it.
     */
    private @Nullable Long applyFused(Run run, Step step) throws SQLException {
      long until = System.nanoTime() + run.context.inflightWait().toNanos();
      while (true) {
        Fused f;
        try (PreparedStatement ps = run.prepare(step, null)) {
          run.stamp(ps, step.binds.size() + 1);
          try (ResultSet rs = ps.executeQuery()) {
            f = fused(rs);
          }
        }
        if (f.after) {
          throw new Aborted(
              "a row the statement writes is being written by a ScalarDB transaction");
        }
        if (!f.open) {
          if (!f.held) {
            return null;
          }
          run.sleep(until, "a row the statement writes is being written by a ScalarDB transaction");
          continue;
        }
        Anchors anchors = run.context.anchors();
        for (Row row : f.rows) {
          if (!anchors.unchanged(n.table, row.key, row.txId, row.version)) {
            throw new Aborted("a row this transaction read was changed by another one since");
          }
        }
        long total = 0;
        for (int i = 0; i < f.rows.size(); i++) {
          if (!f.written.get(i)) {
            skipOrAbort(run, f.rows.get(i));
            continue;
          }
          if (!f.stamped.get(i)) {
            throw new Aborted("a row the statement writes changed after it was read");
          }
          anchors.wrote(run.txId);
          total++;
        }
        return total;
      }
    }

    @Override
    @Nullable Step together() {
      return fused;
    }

    /** As {@link #applyFused}, null for anything but every key written and stamped at once. */
    @Override
    @Nullable Together together(Run run, ResultSet rs) throws SQLException {
      Fused f = fused(rs);
      if (f.after || !f.open || f.written.contains(false) || f.stamped.contains(false)) {
        return null;
      }
      for (Row row : f.rows) {
        if (!run.context.anchors().unchanged(n.table, row.key, row.txId, row.version)) {
          return null;
        }
      }
      return new Together(run, f.rows.size(), null, !f.rows.isEmpty());
    }

    @Override
    @Nullable Merge merge() {
      return merge;
    }

    /**
     * As {@link #together(Run, ResultSet)} for each entry, its count the key it wrote, if any; null
     * for anything but every key found written and stamped, with the gate open.
     */
    @Override
    long @Nullable [] merged(Run run, ResultSet rs, int entries) throws SQLException {
      int width = n.keyColumns.size();
      int flags = 2 * width + 5; // written, stamped, the gate, and an after image matched
      long[] counts = new long[entries];
      Anchors anchors = run.context.anchors();
      boolean wrote = false;
      while (rs.next()) {
        if (!rs.getBoolean(flags + 2) || (afterImages != null && rs.getBoolean(flags + 3))) {
          return null;
        }
        Object[] key = readKey(rs, 2, width);
        if (key == null) {
          continue; // no row for the entry's key: its count is 0, as the statement alone would say
        }
        if (!rs.getBoolean(flags)
            || !rs.getBoolean(flags + 1)
            || !anchors.unchanged(
                n.table, key, rs.getString(2 * width + 2), rs.getObject(2 * width + 3))) {
          return null;
        }
        counts[rs.getInt(1) - 1]++;
        wrote = true;
      }
      if (wrote) {
        anchors.wrote(run.txId);
      }
      return counts;
    }

    /** What the fused statement returned: per key its row, and whether it was written, stamped. */
    private static final class Fused {
      final List<Row> rows = new ArrayList<>();
      final List<Boolean> written = new ArrayList<>();
      final List<Boolean> stamped = new ArrayList<>();
      boolean open;
      boolean after;
      boolean held;
    }

    private Fused fused(ResultSet rs) throws SQLException {
      int width = n.keyColumns.size();
      int flags = 2 * width + 4; // written, stamped, the gate, and an after image matched
      Fused f = new Fused();
      while (rs.next()) {
        f.open = rs.getBoolean(flags + 2);
        f.after = afterImages != null && rs.getBoolean(flags + 3);
        Object[] key = readKey(rs, 1, width);
        if (key == null) {
          continue; // no key: the row of NULLs
        }
        f.held |= inFlight(rs.getObject(2 * width + 3));
        f.rows.add(
            new Row(
                key,
                readKey(rs, width + 1, width),
                rs.getString(2 * width + 1),
                rs.getObject(2 * width + 2)));
        f.written.add(rs.getBoolean(flags));
        f.stamped.add(rs.getBoolean(flags + 1));
      }
      return f;
    }

    /** The write step by step, a statement each (4.3). */
    private long applySteps(Run run) throws SQLException {
      // the lock first: a locking read takes no snapshot, so when the write is the transaction's
      // first statement the plain reads below take it under the lock (todo 236)
      Map<List<Object>, Anchors.Version> now = locked == null ? null : lockedRows(run);
      if (afterImages != null) {
        try (PreparedStatement ps = run.prepare(afterImages, null);
            ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            throw new Aborted(
                "a row the statement writes is being written by a ScalarDB transaction");
          }
        }
      }
      List<String> types = new ArrayList<>();
      List<Row> rows = settledKeys(run, types);
      if (now != null) {
        for (Row row : rows) {
          Anchors.Version latest = now.remove(Anchors.key(row.key));
          if (latest == null || !latest.is(row.txId, row.version)) {
            throw new Aborted("a row the statement's predicate matches changed after the snapshot");
          }
        }
        if (!now.isEmpty()) {
          throw new Aborted("a row the statement's predicate matches was added after the snapshot");
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
          if (stampFromData(run, n, one(row.key, types)) != 1) {
            throw new Aborted("a row the statement writes was given metadata after it was read");
          }
          anchors.wrote(run.txId); // the stamp stays if the DELETE then matches nothing
        }
        // an inline row is deleted with its metadata, by the anchored delete alone
        boolean rowDelete = inline && delete;
        int written;
        if (rowDelete) {
          written = cas(run, row, types);
        } else {
          try (PreparedStatement ps = run.prepare(dml, row.key)) {
            written = ps.executeUpdate();
          }
        }
        if (written == 0) {
          skipOrAbort(run, row);
          continue;
        }
        if (written != 1) {
          throw new Aborted("a key matched " + written + " rows");
        }
        if (!rowDelete && !(delete && row.metaKey == null) && cas(run, row, types) != 1) {
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
     * The versions of the rows the predicate matches now, they and their metadata rows under a
     * shared lock; an abort for one in flight.
     */
    private Map<List<Object>, Anchors.Version> lockedRows(Run run) throws SQLException {
      Keys keys = new Keys();
      try (PreparedStatement ps = run.prepare(Objects.requireNonNull(locked), null);
          ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          keys.read(rs, 1, n.keyColumns.size());
        }
      }
      Map<List<Object>, Anchors.Version> out = versions(run, n, keys, shareLock(n.engine));
      if (out == null) {
        throw new Aborted(
            "a row the statement's predicate matches is being written by a ScalarDB transaction");
      }
      return out;
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
      if (matches != null) {
        try (PreparedStatement ps = run.prepare(matches, row.key);
            ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            throw new Aborted("a row the statement deletes changed after it was read");
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
        if (inline && delete) {
          try (PreparedStatement ps =
              run.prepare(
                  "DELETE FROM " + n.meta + " WHERE " + keyed + " AND " + n.txId + " IS NULL")) {
            bind(ps, 1, metaKey);
            return ps.executeUpdate();
          }
        }
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
    int size = chunk(n);
    for (int from = 0; from < keys.rows.size(); from += size) {
      List<Object[]> chunk = keys.rows.subList(from, Math.min(keys.rows.size(), from + size));
      try (PreparedStatement ps =
          run.prepare(
              n.insertStamp(
                  "SELECT "
                      + n.columns("d")
                      + ", ?, 3, 1, ?, ? FROM "
                      + n.dataOf("d", keys.types, chunk.size())
                      + " WHERE "
                      + n.keysOf(keys.types, chunk.size())
                      + n.unstamped()))) {
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
    // the INSERT and its metadata rows in one round trip, on PostgreSQL (todo 328)
    final @Nullable Step fused;
    // where there is no RETURNING, each row's key read back by its values; none for a generated
    // key (AUTO_INCREMENT, IDENTITY) the statement leaves out, which the vendor driver reports
    final List<Step> readBack;
    // whether that key is generated
    private final boolean generatedKey;
    final @Nullable Step inFlight;
    // at SNAPSHOT and SERIALIZABLE, the keys committed in the snapshot
    final @Nullable Step committed;
    // at those levels, for several rows: whether the statement repeats a key
    final @Nullable Step repeats;
    final @Nullable String returning;
    final String exposed;
    // a batch's single-row entries of this plan as one INSERT (todo 329)
    private final @Nullable Merge merge;

    InsertPlan(
        Names n,
        @Nullable Merge merge,
        Step insert,
        @Nullable Step fused,
        List<Step> readBack,
        @Nullable Step inFlight,
        @Nullable Step committed,
        @Nullable Step repeats,
        @Nullable String returning,
        String exposed,
        boolean generatedKey) {
      super(n);
      this.merge = merge;
      this.generatedKey = generatedKey;
      this.insert = insert;
      this.fused = fused;
      this.readBack = List.copyOf(readBack);
      this.inFlight = inFlight;
      this.committed = committed;
      this.repeats = repeats;
      this.returning = returning;
      this.exposed = exposed;
    }

    @Override
    @Nullable String returning() {
      return returning;
    }

    @Override
    List<String> sql() {
      List<String> out = new ArrayList<>(List.of(insert.sql));
      for (Step s : readBack) {
        out.add(s.sql);
      }
      for (Step s : Arrays.asList(inFlight, committed, repeats, fused)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }

    @Override
    long apply(Run run) throws SQLException {
      return fused != null ? applyFused(run, fused) : applySteps(run);
    }

    /**
     * The INSERT and its metadata rows in one round trip; a tombstone, which the stamp skips, is
     * taken over after it (4.5), and a duplicate told after the savepoint is rolled back to.
     */
    private long applyFused(Run run, Step step) throws SQLException {
      int width = n.keyColumns.size();
      Keys keys = new Keys();
      PreparedStatement ps = run.prepare(step, null, returning != null);
      try {
        run.stamp(ps, step.binds.size() + 1);
        ResultSet rs;
        try {
          // the savepoint's count first, if any
          boolean rows = ps.execute();
          while (!rows && ps.getUpdateCount() != -1) {
            rows = ps.getMoreResults();
          }
          rs = ps.getResultSet();
          if (rs == null) {
            throw new SQLException("the INSERT returned no rows", "XX000");
          }
        } catch (SQLException e) {
          if (!n.engine.isDuplicateKey(e) || inFlight == null) {
            throw e;
          }
          // the transaction goes on, as on the engines where a failed statement does not end it
          try (Statement s = run.v.createStatement()) {
            s.execute("ROLLBACK TO SAVEPOINT " + SAVEPOINT + "; RELEASE SAVEPOINT " + SAVEPOINT);
          }
          throw duplicate(run, e);
        }
        takeOver(run, keys, (int) inserted(rs, keys));
        run.context.anchors().wrote(run.txId);
        run.context.inserted(keys);
        if (returning != null) {
          rs.beforeFirst();
          ps.closeOnCompletion();
          run.context.rows(rs, width + 1);
        } else {
          ps.close();
        }
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
      return keys.rows.size();
    }

    /** Reads the keys the fused INSERT wrote into {@code keys}; how many of them it stamped. */
    private long inserted(ResultSet rs, Keys keys) throws SQLException {
      int width = n.keyColumns.size();
      long stamped = 0;
      int from = rs.getMetaData().getColumnCount() - width;
      while (rs.next()) {
        keys.read(rs, from, width);
        stamped = rs.getLong(from + width);
      }
      return stamped;
    }

    @Override
    @Nullable Step together() {
      return returning == null ? fused : null;
    }

    @Override
    @Nullable Merge merge() {
      return merge;
    }

    /** Each entry's one row, recorded; null when a row was not stamped, as a tombstone is not. */
    @Override
    long @Nullable [] merged(Run run, ResultSet rs, int entries) throws SQLException {
      Keys keys = new Keys();
      if (inserted(rs, keys) != entries || keys.rows.size() != entries) {
        return null;
      }
      run.context.anchors().wrote(run.txId);
      run.context.inserted(keys);
      long[] counts = new long[entries];
      Arrays.fill(counts, 1);
      return counts;
    }

    /** As {@link #applyFused}, null when a tombstone is to be taken over. */
    @Override
    @Nullable Together together(Run run, ResultSet rs) throws SQLException {
      Keys keys = new Keys();
      return inserted(rs, keys) == keys.rows.size()
          ? new Together(run, keys.rows.size(), keys, true)
          : null;
    }

    /**
     * What a duplicate of the INSERT is (4.3), the transaction still open: the application's own,
     * {@code e}, or a conflict with a key in flight or written after the snapshot.
     */
    private SQLException duplicate(Run run, SQLException e) throws SQLException {
      if (!keyViolated(run, e)) {
        return e; // another unique constraint: the application's duplicate (todo 178)
      }
      try (PreparedStatement check = run.prepare(Objects.requireNonNull(inFlight), null);
          ResultSet r = check.executeQuery()) {
        r.next();
        if (r.getLong(1) > 0) {
          Aborted a = new Aborted("a key the statement inserts is being written by ScalarDB");
          a.addSuppressed(e);
          return a;
        }
      }
      if (committed != null && !repeatsKey(run)) {
        try (PreparedStatement check = run.prepare(committed, null);
            ResultSet r = check.executeQuery()) {
          r.next();
          if (r.getLong(1) == 0) {
            Aborted a = new Aborted("a key the statement inserts was written after the snapshot");
            a.addSuppressed(e);
            return a;
          }
        }
      }
      return e; // the application's duplicate
    }

    @SuppressFBWarnings(
        value = "OBL_UNSATISFIED_OBLIGATION",
        justification = "the rows are handed to the application, closed with their statement")
    private long applySteps(Run run) throws SQLException {
      int width = n.keyColumns.size();
      Keys keys = new Keys();
      PreparedStatement ps = null;
      ResultSet rs = null;
      Savepoint sp = run.v.setSavepoint();
      try {
        if (generated()) {
          if (!generatedKey) {
            throw Errors.unsupported(
                "an INSERT of a ScalarDB table that leaves out its key needs an AUTO_INCREMENT"
                    + " or IDENTITY key on this engine");
          }
          // ponytail: the vendor driver numbers the rows from the first value by the increment it
          // read at connect; a session that changes auto_increment_increment gets wrong keys
          // on Oracle the ROWID, by which the key is read below: ojdbc asked for the key columns by
          // name describes the table, which it cannot find under a quoted name such as Spring Data
          // JDBC writes (ORA-04043, milestone 9c)
          ps = run.prepare(insert, null, false, new String[0]);
          long count;
          try {
            count = ps.executeLargeUpdate();
          } catch (SQLException e) {
            if (e.getErrorCode() == 63809) {
              throw Errors.unsupported(
                  "an INSERT of several rows that leaves out the IDENTITY key of a ScalarDB table"
                      + " on Oracle, which returns no keys for it: insert the rows one by one");
            }
            throw e;
          }
          try (ResultSet k = ps.getGeneratedKeys()) {
            while (k.next()) {
              if (n.engine == Engine.ORACLE) {
                keysByRowid(run, k.getObject(1), keys);
              } else {
                keys.read(k, 1, width);
              }
            }
          }
          if (keys.rows.size() != count) {
            throw new SQLException(
                "the database reported " + keys.rows.size() + " keys for " + count + " rows",
                "XX000");
          }
        } else if (readBack.isEmpty()) {
          ps = run.prepare(insert, null, returning != null);
          rs = ps.executeQuery();
          int from = rs.getMetaData().getColumnCount() - width + 1;
          while (rs.next()) {
            keys.read(rs, from, width);
          }
        } else {
          ps = run.prepare(insert, null);
          ps.executeUpdate();
        }
      } catch (SQLException e) {
        if (ps != null) {
          ps.close();
        }
        if (!n.engine.isDuplicateKey(e) || inFlight == null) {
          throw e;
        }
        // the transaction goes on, as on the engines where a failed statement does not end it
        run.v.rollback(sp);
        throw duplicate(run, e);
      } finally {
        try {
          release(n.engine, run.v, sp);
        } catch (SQLException ignored) {
          // after another failure the transaction is aborted, and its end releases it
        }
      }
      try {
        for (int row = 0; row < readBack.size(); row++) {
          // each row's key as stored: MySQL may have stored another value than the one given, or
          // generated one for a NULL or 0, and a value compared after conversion may match other
          // rows; exactly one match each, or the write is undone
          int before = keys.rows.size();
          try (PreparedStatement r = run.prepare(readBack.get(row), null);
              ResultSet k = r.executeQuery()) {
            while (k.next()) {
              keys.read(k, 1, width);
            }
          }
          if (keys.rows.size() - before != 1) {
            throw Errors.unsupported(
                "the key of row "
                    + (row + 1)
                    + " of the INSERT reads back as "
                    + (keys.rows.size() - before)
                    + " rows: bind each key with its column's type, and leave an AUTO_INCREMENT"
                    + " key out of the column list rather than give it NULL or 0");
          }
        }
        stamp(run, keys);
        run.context.anchors().wrote(run.txId);
        run.context.inserted(keys);
        if (returning != null) {
          Objects.requireNonNull(rs).beforeFirst();
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

    /** Reads the key of the row at {@code rowid}, an Oracle ROWID, into {@code keys}. */
    private void keysByRowid(Run run, Object rowid, Keys keys) throws SQLException {
      try (PreparedStatement ps =
          run.prepare("SELECT " + n.readColumns("") + " FROM " + n.data + " WHERE ROWID = ?")) {
        ps.setObject(1, rowid);
        try (ResultSet r = ps.executeQuery()) {
          if (!r.next()) {
            throw new SQLException("the row inserted is not found by its ROWID", "XX000");
          }
          keys.read(r, 1, n.keyColumns.size());
        }
      }
    }

    /** Whether the database generates the key: MySQL or Oracle, keys not read back. */
    private boolean generated() {
      return (n.engine == Engine.MYSQL || n.engine == Engine.ORACLE) && readBack.isEmpty();
    }

    /**
     * Whether the duplicate is of the data table's primary key: the constraint pgjdbc reports, read
     * without depending on pgjdbc, the key MySQL's message names, SQL Server's error code and
     * message, or Oracle's constraint looked up by name; true when it cannot be told.
     */
    private boolean keyViolated(Run run, SQLException e) throws SQLException {
      if (n.engine == Engine.SQLSERVER) {
        // 2627 "Violation of PRIMARY KEY constraint ..." or "... UNIQUE KEY ..."; 2601 is a
        // unique index's
        String m = e.getMessage();
        return e.getErrorCode() == 2627
            && (m == null || m.contains("PRIMARY KEY") || !m.contains("UNIQUE KEY"));
      }
      if (n.engine == Engine.ORACLE) {
        // "ORA-00001: unique constraint (OWNER.NAME) violated ...", in the session's language:
        // the constraint by its (OWNER.NAME) alone (todo 276), and its type
        Matcher m = ORA_CONSTRAINT.matcher(String.valueOf(e.getMessage()));
        if (!m.find()) {
          return true;
        }
        try (PreparedStatement ps =
            run.v.prepareStatement(
                "SELECT constraint_type FROM all_constraints WHERE owner = ? AND"
                    + " constraint_name = ?")) {
          ps.setString(1, m.group(1));
          ps.setString(2, m.group(2));
          try (ResultSet rs = ps.executeQuery()) {
            return !rs.next() || "P".equals(rs.getString(1));
          }
        }
      }
      if (n.engine == Engine.MYSQL || n.engine == Engine.MARIADB) {
        // "Duplicate entry '1' for key 'PRIMARY'", the key qualified by the table on MySQL
        String m = e.getMessage();
        int at = m == null ? -1 : m.lastIndexOf("for key '");
        if (m == null || at < 0) {
          return true;
        }
        String key = m.substring(at + "for key '".length());
        return key.startsWith("PRIMARY'") || key.contains(".PRIMARY'");
      }
      String constraint;
      try {
        Object message = e.getClass().getMethod("getServerErrorMessage").invoke(e);
        constraint =
            message == null
                ? null
                : (String) message.getClass().getMethod("getConstraint").invoke(message);
      } catch (ReflectiveOperationException | ClassCastException ex) {
        constraint = null;
      }
      if (constraint == null) {
        return true;
      }
      try (PreparedStatement ps =
          run.v.prepareStatement(
              "SELECT 1 FROM pg_constraint WHERE conrelid = to_regclass(?) AND contype = 'p'"
                  + " AND conname = ?")) {
        ps.setString(1, n.data);
        ps.setString(2, constraint);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next();
        }
      }
    }

    /**
     * Whether the statement repeats a key; true when that cannot be told, so that a duplicate is
     * never retried forever.
     */
    private boolean repeatsKey(Run run) throws SQLException {
      if (repeats == null) {
        return false;
      }
      // the values' types are inferred without the table's: a mismatch fails, under a savepoint
      Savepoint sp = run.v.setSavepoint();
      try (PreparedStatement ps = run.prepare(repeats, null);
          ResultSet rs = ps.executeQuery()) {
        return rs.next();
      } catch (SQLException e) {
        run.v.rollback(sp);
        return true;
      } finally {
        release(n.engine, run.v, sp);
      }
    }

    private void stamp(Run run, Keys keys) throws SQLException {
      // an inline row is its own metadata row: stamped where it stands, below
      takeOver(run, keys, n.inline ? 0 : stampFromData(run, n, keys));
    }

    /**
     * Stamps the keys {@code stamped} of which have their metadata rows already: a metadata row
     * with no data row is an orphan, a tombstone, taken over in place (4.5); an inline row, where
     * it stands.
     */
    private void takeOver(Run run, Keys keys, int stamped) throws SQLException {
      if (stamped == keys.rows.size()) {
        return;
      }
      // a metadata row with no data row is an orphan, a tombstone: taken over in place (4.5)
      int taken = 0;
      int size = chunk(n);
      for (int from = 0; from < keys.rows.size(); from += size) {
        List<Object[]> chunk = keys.rows.subList(from, Math.min(keys.rows.size(), from + size));
        // on MySQL and MariaDB the keys drive the update, as dataOf (todo 220), and are bound first
        boolean keysFirst = n.lookups();
        try (PreparedStatement ps =
            run.prepare(
                n.stamp(
                        keysFirst
                            ? n.keyValues(keys.types, chunk.size())
                                + " STRAIGHT_JOIN "
                                + n.meta
                                + " ON "
                                + n.keyJoinValues(n.meta)
                            : n.meta,
                        "COALESCE(" + n.txVersion + ", 0) + 1")
                    + (n.inline
                        // the rows the statement just inserted, their metadata NULL; no
                        // subquery on the table itself, which MySQL refuses in an UPDATE of it
                        ? (keysFirst ? "" : n.keyIn(n.meta, keys.types, chunk.size()) + " AND ")
                            + n.txId
                            + " IS NULL"
                        : "EXISTS (SELECT 1 FROM "
                            + n.data
                            + " d WHERE "
                            + n.keyJoin(n.meta, "d")
                            + (keysFirst ? "" : " AND " + n.keyIn("d", keys.types, chunk.size()))
                            + ") AND "
                            + n.distinctFrom(n.txId)
                            + " AND "
                            + n.committed()))) {
          int i =
              keysFirst
                  ? run.stamp(ps, bindAll(ps, 1, chunk))
                  : bindAll(ps, run.stamp(ps, 1), chunk);
          if (!n.inline) {
            ps.setString(i, run.txId);
          }
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
      Set<String> data = new HashSet<>();
      for (String c : plan.n.dataColumns) {
        data.add(plan.n.engine.columnKey(c));
      }
      for (String asked : (String[]) request) {
        // a name the caller quoted, as Spring Data JDBC does, is taken as written (milestone 9c)
        String c = unquoted(asked);
        boolean quoted = !c.equals(asked);
        if (plan.n.inline && metadataColumn(plan.n.engine, data, c)) {
          // an inline table's metadata columns are ScalarDB's and the driver's (todo 293)
          throw Errors.unsupported("generated keys of ScalarDB's metadata column " + c);
        }
        // ojdbc reads an unquoted name as Oracle reads it, folded to upper case
        boolean folds =
            plan.n.engine == Engine.ORACLE && !quoted && c.matches("[A-Za-z][A-Za-z0-9_$#]*");
        names.add(plan.n.engine.quote(folds ? c.toUpperCase(Locale.ROOT) : c));
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
  // ponytail: one placeholder per key column and row, refused past the engine's limit; read in
  // chunks into rows held in memory if a batch needs more
  static ResultSet returned(Connection vendor, Plan plan, String items, Keys keys)
      throws SQLException {
    Names n = plan.n;
    int limit = n.engine == Engine.SQLSERVER ? 2100 : 65535;
    if (keys.rows.size() * n.keyColumns.size() > limit) {
      // refused before the commit, so that autocommit writes nothing (todo 256)
      throw Errors.unsupported(
          "generated keys for "
              + keys.rows.size()
              + " rows of one statement or batch; at most "
              + limit / n.keyColumns.size()
              + " on this database");
    }
    String q = ((InsertPlan) plan).exposed;
    String sql =
        keys.rows.isEmpty()
            ? "SELECT "
                + (items.equals("*") ? n.star(q) : items)
                + " FROM "
                + n.data
                + " "
                + q
                + " WHERE 1 = 0"
            : "SELECT "
                + (items.equals("*") ? n.star(q) : items)
                + " FROM "
                + n.data
                + " " // a table alias without AS, which Oracle has not
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
   * with the engine's lock-not-available error, and otherwise waits the bounded wait of section 6,
   * then aborts; the check is made again after the lock, for a row that went in flight before the
   * lock statement began (todo 149). Each locked row's metadata is read again under the lock:
   * PostgreSQL re-checks a row updated since the statement began against the metadata table as the
   * statement began, so a row a ScalarDB prepare wrote meanwhile would pass as committed. Each
   * attempt runs under a savepoint, whose rollback releases its locks before the next.
   */
  static final class LockPlan extends Plan {
    final Step lock;
    final @Nullable Step inFlight;
    final boolean noWait;
    // the locking clause the versions read under the lock takes too, or null: on MySQL and
    // MariaDB a shared lock read through a covering index locks the index entry only, not the row
    final @Nullable String relock;
    // at REPEATABLE READ on MySQL and MariaDB, the same read without the lock: a locking read there
    // returns the rows committed now, which must be the snapshot's
    final @Nullable Step snapshot;
    // at SNAPSHOT and SERIALIZABLE on PostgreSQL, the in-flight check and the lock in one round
    // trip (todo 328)
    final @Nullable Step fused;

    LockPlan(
        Names n,
        Step lock,
        @Nullable Step inFlight,
        boolean noWait,
        @Nullable String relock,
        @Nullable Step snapshot,
        boolean fuse) {
      super(n);
      this.lock = lock;
      this.inFlight = inFlight;
      this.noWait = noWait;
      this.relock = relock;
      this.snapshot = snapshot;
      List<Integer> binds = new ArrayList<>();
      if (fuse && inFlight != null) {
        binds.addAll(inFlight.binds);
        binds.addAll(lock.binds);
      }
      this.fused =
          fuse && inFlight != null ? new Step(inFlight.sql + "; " + lock.sql, binds) : null;
    }

    @Override
    boolean returnsRows() {
      return true;
    }

    /**
     * A locking read writes nothing, so a read-only transaction may run it, as SERIALIZABLE does
     * every read on MySQL and MariaDB, which take a shared lock in a READ ONLY transaction (todo
     * 301); FOR UPDATE there, the database refuses itself (1792, 25006).
     */
    @Override
    boolean writes() {
      return false;
    }

    @Override
    List<String> sql() {
      List<String> out = new ArrayList<>();
      for (Step s : Arrays.asList(inFlight, lock, snapshot, fused)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }

    @Override
    long apply(Run run) throws SQLException {
      return fused != null ? applyFused(run, fused) : applySteps(run);
    }

    /**
     * The in-flight check and the lock as two statements sent in one round trip: at REPEATABLE READ
     * on PostgreSQL every statement of the transaction reads its one snapshot, so the check after
     * the lock and the versions read under it would see what these two do, and a row in flight
     * aborts at once (section 6), its locks released with the transaction. A row a ScalarDB prepare
     * wrote after the snapshot fails the lock itself with PostgreSQL's serialization failure. The
     * lock reads no versions, as no read does there (todo 329).
     */
    private long applyFused(Run run, Step step) throws SQLException {
      PreparedStatement ps = run.prepare(step, null, true);
      try {
        ps.execute();
        try (ResultSet held = ps.getResultSet()) {
          if (held != null && held.next()) {
            throw new Aborted(
                "a row the statement locks is being written by a ScalarDB transaction");
          }
        }
        if (!ps.getMoreResults()) {
          throw new SQLException("the locking read returned no rows", "XX000");
        }
        ResultSet rs = ps.getResultSet();
        // nothing appended: a later write reads its version in the same snapshot (todo 329)
        int rows = 0;
        while (rs.next()) {
          rows++;
        }
        rs.beforeFirst();
        ps.closeOnCompletion();
        run.context.rows(rs, 0);
        return rows;
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
    }

    /** The locking read step by step, each attempt under a savepoint. */
    @SuppressFBWarnings(
        value = "OBL_UNSATISFIED_OBLIGATION",
        justification = "the rows are handed to the application, closed with their statement")
    private long applySteps(Run run) throws SQLException {
      int width = n.keyColumns.size();
      long until = System.nanoTime() + run.context.inflightWait().toNanos();
      String held = "a row the statement locks is being written by a ScalarDB transaction";
      for (int attempt = 0; ; attempt++) {
        while (inFlight != null && matchesInFlight(run)) {
          if (noWait) {
            throw notAvailable(held);
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
          List<Anchors.Version> read = new ArrayList<>();
          while (rs.next()) {
            keys.read(rs, from, width);
            read.add(
                new Anchors.Version(rs.getString(from + width), rs.getObject(from + width + 1)));
          }
          // SQL Server keeps the lock of a row the statement read and rejected, a row in flight
          // that READPAST did not skip, which holds off its ScalarDB commit: every lock goes with
          // the savepoint, and the rows returned are locked again by key, at the versions returned
          boolean relocks = inFlight == null && n.engine == Engine.SQLSERVER;
          if (relocks) {
            run.v.rollback(sp);
          }
          List<Anchors.Version> versions = new ArrayList<>();
          boolean settled =
              (inFlight == null || !matchesInFlight(run))
                  && (relocks
                      ? relockedAsRead(run, keys, versions, read)
                      : lockedVersions(run, keys, versions));
          if (settled) {
            if (snapshot != null && !asInSnapshot(run, keys, versions)) {
              throw new Aborted(
                  "a row the statement locks changed after the transaction's snapshot");
            }
            release(n.engine, run.v, sp);
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
        release(n.engine, run.v, sp); // or each attempt leaves a subtransaction open (todo 161)
        if (noWait) {
          throw notAvailable(held);
        }
        if (attempt >= 10) {
          throw new Aborted(held); // rows keep going in flight around the lock: bounded retries
        }
      }
    }

    /**
     * Whether the rows locked, at the versions read under the lock, are those the snapshot shows:
     * none changed, added or gone since it was taken (design 5, SNAPSHOT on MySQL and MariaDB).
     */
    private boolean asInSnapshot(Run run, Keys keys, List<Anchors.Version> versions)
        throws SQLException {
      if (inFlight == null) {
        // SKIP LOCKED: under the statement's ORDER BY and LIMIT the snapshot's rows would be those
        // it skipped (todo 221); each row locked is then the snapshot's, at the version locked
        Map<List<Object>, Anchors.Version> then = versions(run, n, keys, null);
        if (then == null) {
          return false;
        }
        for (int i = 0; i < keys.rows.size(); i++) {
          Anchors.Version v = then.get(Anchors.key(keys.rows.get(i)));
          if (v == null || !versions.get(i).is(v.txId, v.version)) {
            return false;
          }
        }
        return true;
      }
      int width = n.keyColumns.size();
      Map<List<Object>, Anchors.Version> locked = new HashMap<>();
      for (int i = 0; i < keys.rows.size(); i++) {
        locked.put(Anchors.key(keys.rows.get(i)), versions.get(i));
      }
      int seen = 0;
      try (PreparedStatement ps = run.prepare(Objects.requireNonNull(snapshot), null);
          ResultSet rs = ps.executeQuery()) {
        int from = rs.getMetaData().getColumnCount() - width - 1;
        while (rs.next()) {
          Anchors.Version now =
              locked.get(Anchors.key(Objects.requireNonNull(readKey(rs, from, width))));
          if (now == null) {
            return false;
          }
          if (!now.is(rs.getString(from + width), rs.getObject(from + width + 1))) {
            return false;
          }
          seen++;
        }
      }
      return seen == locked.size();
    }

    /** {@code NOWAIT}'s failure as the engine reports its own: 55P03, MySQL 3572, MariaDB 1205. */
    private SQLException notAvailable(String reason) {
      String message = "could not lock a row: " + reason;
      switch (n.engine) {
        case MYSQL:
          return new SQLException(message, "HY000", 3572);
        case MARIADB:
          return new SQLException(message, "HY000", 1205);
        case SQLSERVER:
          return new SQLException(message, "S0005", 1222);
        case ORACLE:
          return new SQLException(message, "61000", 54);
        default:
          return new SQLException(message, "55P03");
      }
    }

    private boolean matchesInFlight(Run run) throws SQLException {
      try (PreparedStatement ps = run.prepare(Objects.requireNonNull(inFlight), null);
          ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }

    /**
     * As {@link #lockedVersions}, the keys locked now: false too when one is no longer at the
     * version {@code read} returned, or another transaction locked it in between, which READPAST
     * would have skipped.
     */
    private boolean relockedAsRead(
        Run run, Keys keys, List<Anchors.Version> versions, List<Anchors.Version> read)
        throws SQLException {
      try {
        if (!lockedVersions(run, keys, versions)) {
          return false;
        }
      } catch (SQLException e) {
        if (e.getErrorCode() == 1222) { // NOWAIT's
          return false;
        }
        throw e;
      }
      for (int i = 0; i < read.size(); i++) {
        Anchors.Version r = read.get(i);
        if (!versions.get(i).is(r.txId, r.version)) {
          return false;
        }
      }
      return true;
    }

    /**
     * The version of each locked key, read under the lock, in the keys' order; false when one is in
     * flight.
     */
    private boolean lockedVersions(Run run, Keys keys, List<Anchors.Version> versions)
        throws SQLException {
      Map<List<Object>, Anchors.Version> found = versions(run, n, keys, relock);
      if (found == null) {
        return false;
      }
      for (Object[] key : keys.rows) {
        versions.add(found.getOrDefault(Anchors.key(key), new Anchors.Version(null, null)));
      }
      return true;
    }
  }

  /**
   * The version of each key with a data row, read under {@code lock} if any; null when one is in
   * flight.
   */
  private static @Nullable Map<List<Object>, Anchors.Version> versions(
      Run run, Names n, Keys keys, @Nullable String lock) throws SQLException {
    int width = n.keyColumns.size();
    Map<List<Object>, Anchors.Version> found = new HashMap<>();
    int size = chunk(n);
    for (int from = 0; from < keys.rows.size(); from += size) {
      List<Object[]> chunk = keys.rows.subList(from, Math.min(keys.rows.size(), from + size));
      // the relock goes through the primary key, which a covering index would otherwise stand
      // in for
      boolean hint = lock != null && n.engine == Engine.SQLSERVER;
      // and the metadata row under the lock too, at every level on SQL Server (NOWAIT under
      // READPAST too): SNAPSHOT would read it in its snapshot
      String read =
          n.withMeta(
                  n.dataOf(
                      lock == null
                          ? "d"
                          : hint
                              ? "d WITH (" + lock + ", INDEX(" + n.primaryIndex(run.v) + "))"
                              : "d FORCE INDEX (PRIMARY)",
                      keys.types,
                      chunk.size()),
                  hint ? " WITH (" + lock + ")" : "",
                  "d",
                  n.keysOf(keys.types, chunk.size()))
              + (lock == null || hint ? "" : " " + lock);
      try (PreparedStatement ps = run.prepare(read)) {
        bindAll(ps, 1, chunk);
        try (ResultSet m = ps.executeQuery()) {
          while (m.next()) {
            if (inFlight(m.getObject(width + 3))) {
              return null;
            }
            found.put(
                Anchors.key(Objects.requireNonNull(readKey(m, 1, width))),
                new Anchors.Version(m.getString(width + 1), m.getObject(width + 2)));
          }
        }
      }
    }
    return found;
  }

  /** The table's names, quoted, and the clauses built from them. */
  static final class Names {
    final Engine engine;
    final String table; // as Router.Anchored names it
    final String namespace; // stored spellings, unquoted
    final String dataTable;

    final List<String> keyColumns;
    private final List<String> floatKeys;
    final String data;
    final String meta;
    final String view;
    final String txId;
    final String txState;
    final String txVersion;
    final String preparedAt;
    final String committedAt;
    // the metadata in the row: the table is its own metadata table (milestone 10)
    final boolean inline;
    final List<String> dataColumns;

    Names(Engine engine, TableInfo t) {
      this.engine = engine;
      this.table = t.namespace() + "." + t.table();
      this.namespace = t.namespace();
      this.dataTable = t.dataTable();
      this.keyColumns = t.keyColumns();
      this.floatKeys = t.floatColumns();
      String ns = engine.quote(t.namespace()) + ".";
      this.data = ns + engine.quote(t.dataTable());
      this.meta = ns + engine.quote(Objects.requireNonNull(t.metadataTable(), "metadata table"));
      this.view = ns + engine.quote(t.readView(engine));
      this.txId = engine.quote("tx_id");
      this.txState = engine.quote("tx_state");
      this.txVersion = engine.quote("tx_version");
      this.preparedAt = engine.quote("tx_prepared_at");
      this.committedAt = engine.quote("tx_committed_at");
      this.inline = t.layout() == Layout.INLINE;
      this.dataColumns = t.dataColumns();
    }

    // SQL Server: the data table's primary key index, quoted, read once
    private volatile @Nullable String primaryIndex;

    /** The data table's primary key index on SQL Server, quoted, for an INDEX hint. */
    String primaryIndex(Connection vendor) throws SQLException {
      String known = primaryIndex;
      if (known == null) {
        try (PreparedStatement ps =
            vendor.prepareStatement(
                "SELECT i.name FROM sys.indexes i WHERE i.object_id = OBJECT_ID(?)"
                    + " AND i.is_primary_key = 1")) {
          ps.setString(1, data);
          try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
              throw Errors.unsupported("the data table " + data + " has no primary key");
            }
            known = engine.quote(rs.getString(1));
          }
        }
        primaryIndex = known;
      }
      return known;
    }

    /** The key columns, each qualified by {@code q} when it is not empty. */
    String columns(String q) {
      List<String> out = new ArrayList<>();
      for (String k : keyColumns) {
        out.add((q.isEmpty() ? "" : q + ".") + engine.quote(k));
      }
      return String.join(", ", out);
    }

    /**
     * Every data column, as {@code SELECT q.*} would give them but for an inline table's metadata
     * columns, which only ScalarDB and the driver see.
     */
    String star(String q) {
      if (!inline) {
        return (q.isEmpty() ? "" : q + ".") + "*";
      }
      List<String> out = new ArrayList<>();
      for (String c : dataColumns) {
        out.add((q.isEmpty() ? "" : q + ".") + engine.quote(c));
      }
      return String.join(", ", out);
    }

    /**
     * {@code q.prefix0, q.prefix1, ...}, {@code count} driver-named columns, unqualified for "".
     */
    static String numbered(String q, String prefix, int count) {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        out.add((q.isEmpty() ? "" : q + ".") + prefix + i);
      }
      return String.join(", ", out);
    }

    /** The key columns of {@code q} named as {@link #numbered} names them. */
    String aliased(String q, String prefix) {
      List<String> out = new ArrayList<>();
      for (int i = 0; i < keyColumns.size(); i++) {
        out.add(key(q, keyColumns.get(i)) + " AS " + prefix + i);
      }
      return String.join(", ", out);
    }

    /** The {@link #numbered} key columns of {@code a} equal to those of {@code b}. */
    String keyJoinNumbered(String a, String prefixA, String b, String prefixB) {
      List<String> terms = new ArrayList<>();
      for (int i = 0; i < keyColumns.size(); i++) {
        terms.add(a + "." + prefixA + i + " = " + b + "." + prefixB + i);
      }
      return String.join(" AND ", terms);
    }

    /** The key columns as a select list the driver reads keys from, as {@link #key} reads them. */
    String readColumns(String q) {
      List<String> out = new ArrayList<>();
      for (String k : keyColumns) {
        out.add(key(q, k));
      }
      return String.join(", ", out);
    }

    /**
     * A key column as the driver reads it back: a FLOAT key on MySQL and MariaDB as a double, which
     * they send with all its digits, where a float's six can name another float (todo 215).
     */
    String key(String q, String k) {
      String c = (q.isEmpty() ? "" : q + ".") + engine.quote(k);
      if (engine == Engine.POSTGRESQL || !floatKeys.contains(k)) {
        return c;
      }
      return engine == Engine.ORACLE ? "TO_BINARY_DOUBLE(" + c + ")" : "(" + c + " + 0E0)";
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
      if (engine != Engine.POSTGRESQL) {
        // a select per key, the first naming the columns: MySQL's VALUES names none; the types
        // are PostgreSQL's
        StringBuilder first = new StringBuilder("SELECT 0 AS " + engine.quote(ORDINAL));
        StringBuilder rest = new StringBuilder();
        for (int j = 0; j < types.size(); j++) {
          first.append(", ? AS ").append(engine.quote("scalardb_native_k" + j));
          rest.append(", ?");
        }
        if (engine == Engine.ORACLE) {
          first.append(" FROM dual");
          rest.append(" FROM dual");
        }
        List<String> rows = new ArrayList<>(List.of(first.toString()));
        for (int i = 1; i < count; i++) {
          rows.add("SELECT " + i + rest);
        }
        return "(" + String.join(" UNION ALL ", rows) + ") u";
      }
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

    /**
     * The data table as {@code d} ({@code dataRef}: its alias and an index hint if any), its rows
     * of {@code count} keys alone with {@link #keysOf}'s condition: on MySQL and MariaDB behind a
     * table of the keys, {@code <keyValues> STRAIGHT_JOIN data d ON ...}, each key a primary-key
     * lookup; a semi-join, or an OR of the keys, may scan the whole table, which REPEATABLE READ
     * locks (todo 220).
     */
    String dataOf(String dataRef, List<String> types, int count) {
      return !lookups()
          ? data + " " + dataRef
          : keyValues(types, count)
              + " STRAIGHT_JOIN "
              + data
              + " "
              + dataRef
              + " ON "
              + keyJoinValues("d");
    }

    /** The condition on {@link #dataOf}'s rows that selects the keys, bound after it. */
    String keysOf(List<String> types, int count) {
      return !lookups() ? keyIn("d", types, count) : "TRUE";
    }

    /** Whether keys are looked up one by one behind a table of them, on MySQL and MariaDB. */
    boolean lookups() {
      return engine == Engine.MYSQL || engine == Engine.MARIADB;
    }

    /** {@code (q.k1, q.k2) IN (SELECT ... FROM <keyValues>)}, the keys bound in order. */
    String keyIn(String q, List<String> types, int count) {
      if (engine == Engine.SQLSERVER) {
        // no row value there
        return "EXISTS (SELECT 1 FROM "
            + keyValues(types, count)
            + " WHERE "
            + keyJoinValues(q)
            + ")";
      }
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
      return withMeta(data + " d", "", keyQualifier, where);
    }

    /**
     * As {@link #withMeta(String, String)}, the data table as d in {@code source}, the metadata
     * table under {@code hint}.
     */
    String withMeta(String source, String hint, String keyQualifier, String where) {
      return "SELECT "
          + readColumns(keyQualifier)
          + ", m."
          + txId
          + ", m."
          + txVersion
          + ", m."
          + txState
          + " FROM "
          + source
          + " LEFT JOIN "
          + meta
          + " m"
          + hint
          + " ON "
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

    /** {@code column} differs from the value bound, NULL included. */
    String distinctFrom(String column) {
      switch (engine) {
        case POSTGRESQL:
          return column + " IS DISTINCT FROM ?";
        case SQLSERVER:
          // IS DISTINCT FROM is SQL Server 2022's
          return "EXISTS (SELECT " + column + " EXCEPT SELECT ?)";
        case ORACLE:
          // DECODE takes two NULLs as equal; Oracle has no IS DISTINCT FROM
          return "DECODE(" + column + ", ?, 0, 1) = 1";
        default:
          return "NOT (" + column + " <=> ?)";
      }
    }

    /** A committed metadata row, NULL state included (the deemed-committed rule). */
    String committed() {
      return "COALESCE(" + txState + ", 3) = 3";
    }

    /** The stamp of I5 at {@code version}, its three values bound first, up to the WHERE. */
    String stamp(String version) {
      return stamp(meta, version);
    }

    /** As {@link #stamp(String)}, of the metadata table in {@code target}. */
    String stamp(String target, String version) {
      return "UPDATE "
          + target
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

    /**
     * A metadata row per key from {@code rows}: the key, then the stamp's three values; a key that
     * has one already is skipped.
     */
    String insertStamp(String rows) {
      boolean pg = engine == Engine.POSTGRESQL;
      boolean oracle = engine == Engine.ORACLE;
      // MySQL's IGNORE also turns data errors into warnings, which rows the driver builds do not
      // have; Oracle's hint skips a key that has a row, waiting on one being inserted, as IGNORE
      // does; SQL Server's rows leave such keys out (unstamped)
      return (pg || engine == Engine.SQLSERVER
              ? "INSERT INTO "
              : oracle
                  ? "INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(x (" + columns("") + ")) */ INTO "
                  : "INSERT IGNORE INTO ")
          + meta
          + (oracle ? " x" : "")
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
          + (pg ? " ON CONFLICT DO NOTHING" : "");
    }

    /**
     * On SQL Server, which has no insert that skips a key, the condition on data rows {@code d}
     * that leaves out a key with a metadata row, or one being inserted, its range held until the
     * end; elsewhere none.
     */
    String unstamped() {
      if (engine != Engine.SQLSERVER) {
        return "";
      }
      return " AND NOT EXISTS (SELECT 1 FROM "
          + meta
          + " x WITH (UPDLOCK, HOLDLOCK) WHERE "
          + keyJoin("x", "d")
          + ")";
    }
  }
}
