package com.scalar.db.nativejdbc.jdbc;

import com.scalar.db.nativejdbc.catalog.Layout;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.sql.SqlAst;
import com.scalar.db.nativejdbc.sql.StatementShape;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.BatchUpdateException;
import java.sql.CallableStatement;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
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
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  /**
   * {@code oracle.jdbc.OracleTypes.CURSOR}, the JDBC type of a {@code SYS_REFCURSOR} OUT bind,
   * written out since the vendor's driver is the application's, not a dependency (todo 334).
   */
  static final int ORACLE_CURSOR = -10;

  /** What a run needs from the statement that runs it. */
  interface Context {
    /**
     * Binds the application's value of one of its placeholders to a driver statement; {@code
     * asStored} binds a float as the exact double a stored float compares as (todos 205, 208).
     */
    void bind(PreparedStatement ps, int position, int placeholder, boolean asStored)
        throws SQLException;

    /**
     * The PL/SQL type of a collection element that holds the application's value of a placeholder
     * as ojdbc binds the scalar (design 4.4), null for a value bound any other way.
     */
    @Nullable String plsqlType(int placeholder);

    /**
     * The application's value of a placeholder as a one-element array, its element null for a NULL;
     * null where it was bound any other way than as one value (a stream, a calendar's time).
     */
    Object @Nullable [] value(int placeholder);

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

    /**
     * Whether the open transaction found no row in flight by the coarse check {@code check} of a
     * table, in its snapshot (todo 329); false outside a transaction.
     */
    boolean passed(String check);

    /** Records that the open transaction found none by {@code check}; nothing outside one. */
    void pass(String check);

    /** Records the keys an INSERT wrote, for {@link #returned}. */
    void inserted(Keys keys);

    /**
     * Whether the application asked for the generated keys, which {@link #returned} reads back by
     * the keys recorded: a write that learns its keys only at a cost reads them then alone.
     */
    boolean wantsKeys();

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
    // the stamp's three values bound before the binds, not after: a statement on a table in the
    // inline layout whose stamp is in its SET list, ahead of its predicate's placeholders
    final boolean stampFirst;
    // how many of the binds the stamp's three values come after in the text, -1 for a stamp bound
    // after them all by the plan itself, or none: an Oracle block binds the stamp between its read
    // and its DML (todo 334); 0 is stampFirst
    final int stampAfter;

    Step(String sql, List<Integer> binds) {
      this(sql, binds, Set.of());
    }

    Step(String sql, List<Integer> binds, Set<Integer> floatKeys) {
      this(sql, binds, floatKeys, false);
    }

    Step(String sql, List<Integer> binds, Set<Integer> floatKeys, boolean stampFirst) {
      this(sql, binds, floatKeys, stampFirst ? 0 : -1);
    }

    Step(String sql, List<Integer> binds, Set<Integer> floatKeys, int stampAfter) {
      this(sql, binds, floatKeys, stampAfter, List.of(), false);
    }

    Step(
        String sql,
        List<Integer> binds,
        Set<Integer> floatKeys,
        int stampAfter,
        List<Integer> outTypes,
        boolean cursor) {
      this.sql = sql;
      this.binds = List.copyOf(binds);
      this.floatKeys = Set.copyOf(floatKeys);
      this.stampAfter = stampAfter;
      this.stampFirst = stampAfter == 0;
      this.outTypes = List.copyOf(outTypes);
      this.cursor = cursor;
    }

    // an Oracle block's row as OUT binds after the binds above, their JDBC types in order, and
    // whether a ref cursor follows them (design 4.3 on Oracle, todo 334)
    final List<Integer> outTypes;
    final boolean cursor;

    /** The placeholders the step binds itself: its binds and, where it has one, the stamp. */
    int parameters() {
      return binds.size() + (stampAfter >= 0 ? 3 : 0);
    }

    /** The position of the first OUT bind. */
    int outAt() {
      return parameters() + 1;
    }

    /** The position of the ref cursor, after the OUT binds. */
    int cursorAt() {
      return parameters() + outTypes.size() + 1;
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

    /**
     * Whether the plan runs under a savepoint of its own: where a failed statement leaves the
     * transaction open, a write that fails part way is undone to its start, as the engine undoes a
     * statement of its own; not on PostgreSQL, where a failed statement aborts the transaction, and
     * not for a write that is one statement, an Oracle block included, which the engine undoes
     * whole (todo 334).
     */
    boolean savepointed() {
      return n.engine != Engine.POSTGRESQL;
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

    /** On Oracle, the write's block over a batch's entries (design 4.4, todo 334), or null. */
    @Nullable Entries entries() {
      return null;
    }

    /** On Oracle at SNAPSHOT, the write's bulk block over a batch's entries (4.4), or null. */
    @Nullable Bulk bulk() {
      return null;
    }

    /** On Oracle, the write's block that returns a row with the key (todo 334), or null. */
    @Nullable Block oracleBlock() {
      return null;
    }

    /**
     * What one entry's row of {@link #entries()} means, from column {@code from}: its count when
     * final, recorded; null when its outcome is the single form's to make, which the caller runs
     * for that entry; an abort thrown where the row says so.
     */
    @Nullable Long entry(Run run, ResultSet rs, int from) throws SQLException {
      throw new IllegalStateException("no block of entries");
    }

    /** As {@link #entry(Run, ResultSet, int)}, the entry's row from its OUT binds at {@code at}. */
    @Nullable Long entry(Run run, CallableStatement cs, int at, List<Integer> keyTypes)
        throws SQLException {
      throw new IllegalStateException("no block of entries");
    }

    /** The key of an entry's row from the cursor, for the first-use check. */
    Object @Nullable [] entryKey(ResultSet rs, int from) throws SQLException {
      throw new IllegalStateException("no block of entries");
    }

    /** The key of an entry's row from its OUT binds, for the first-use check. */
    Object @Nullable [] entryKey(CallableStatement cs, int at, List<Integer> keyTypes)
        throws SQLException {
      throw new IllegalStateException("no block of entries");
    }

    /**
     * On Oracle, the one plain statement a batch's entries of this write go as through the vendor's
     * own batching (design 4.4, todo 334), or null.
     */
    @Nullable Step vendorBatch() {
      return null;
    }

    /**
     * The entries as the vendor's statement with the stamp, at SNAPSHOT and SERIALIZABLE on
     * PostgreSQL (4.3, todo 329), gated or not as the transaction found the table; null where
     * {@link #merge()} is the form to send.
     */
    @Nullable Merge fastMerge(Context context) {
      return null;
    }

    /** That form, gated or not, for tests. */
    @Nullable Merge fastMerge(boolean gated) {
      return null;
    }

    /**
     * What {@link #fastMerge}'s statement {@code m} returned for {@code entries} entries: each
     * one's count, recorded; null when an entry wrote nothing, which the entries one by one tell
     * apart (a key without a row, two entries on one key, the gate closed).
     */
    long @Nullable [] mergedFast(Run run, ResultSet rs, int entries, Merge m) throws SQLException {
      throw new IllegalStateException("no fast merged statement");
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
    // the stamp's three values bound before the entries', not after: the inline layout's INSERT,
    // whose rows each name them
    final boolean stampFirst;
    // whether the text binds a stamp at all: an inline DELETE, and a created table's, stamp none
    final boolean stamps;
    private final Map<Integer, String> texts = new ConcurrentHashMap<>();

    Merge(IntFunction<String> text, int placeholders) {
      this(text, placeholders, false);
    }

    Merge(IntFunction<String> text, int placeholders, boolean stampFirst) {
      this(text, placeholders, stampFirst, true);
    }

    Merge(IntFunction<String> text, int placeholders, boolean stampFirst, boolean stamps) {
      this.text = text;
      this.placeholders = placeholders;
      this.stampFirst = stampFirst;
      this.stamps = stamps;
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
    // the data table's columns with their types: an INSERT's for its casts and its generated
    // key, and on Oracle every write's for the key's OUT binds (todo 334)
    Map<String, String> columns =
        vendor != null && (shape.kind() == StatementShape.Kind.INSERT || engine == Engine.ORACLE)
            ? columns(vendor, new Names(engine, t))
            : null;
    return plan(engine, shape, t, tables, snapshot, locks, vendor, columns);
  }

  /**
   * As {@link #plan(Engine, StatementShape, TableInfo, String, boolean, boolean, Connection)}, with
   * the data table's columns and their types given.
   */
  static Plan plan(
      Engine engine,
      StatementShape shape,
      TableInfo t,
      String tables,
      boolean snapshot,
      boolean locks,
      @Nullable Connection vendor,
      @Nullable Map<String, String> columns)
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
      if (vendor != null
          && shape.kind() == StatementShape.Kind.INSERT
          && shape.insertColumns().isEmpty()) {
        StatementShape listed = listingColumns(shape, n, t, vendor);
        if (listed != null) {
          return plan(engine, listed, t, tables, snapshot, locks, vendor);
        }
      }
      inlineRefusals(engine, shape, t, tables);
    }
    if (vendor != null && shape.kind() == StatementShape.Kind.INSERT) {
      String why = otherKeys(vendor, n);
      if (why != null) {
        throw refused(tables, why);
      }
    }
    return shape.kind() == StatementShape.Kind.INSERT
        ? insertPlan(shape, t, n, all, tables, snapshot, columns)
        : keyedPlan(shape, t, n, all, tables, snapshot, locks, columns);
  }

  /**
   * An INSERT into an inline table that lists no columns, with the columns its values fall to
   * listed: the data table's first ones in its own order, as many as a row has values, when they
   * are all the user's (todo 329; ScalarDB adds its metadata columns after them). {@code null} when
   * a metadata column is among them or the text cannot be rewritten.
   */
  private static @Nullable StatementShape listingColumns(
      StatementShape shape, Names n, TableInfo t, Connection vendor) throws SQLException {
    SqlAst ast = shape.ast();
    ExpressionList<?> top = ((Values) ((Insert) ast.statement()).getSelect()).getExpressions();
    int width =
        ((ExpressionList<?>) (top instanceof ParenthesedExpressionList<?> ? top : top.get(0)))
            .size();
    Set<String> data = new HashSet<>();
    for (String c : t.dataColumns()) {
      data.add(n.engine.columnKey(c));
    }
    List<String> order = List.copyOf(columns(vendor, n).keySet());
    if (width > order.size()) {
      return null;
    }
    StringJoiner list = new StringJoiner(", ", "(", ") ");
    for (String c : order.subList(0, width)) {
      if (!data.contains(n.engine.columnKey(c))) {
        return null;
      }
      list.add(n.engine.quote(c));
    }
    int at = rowsStart(ast);
    if (at < 0) {
      return null;
    }
    String source = ast.source();
    SqlAst listed = ast.reparse(source.substring(0, at) + list + source.substring(at));
    return listed == null ? null : StatementShape.of(listed).orElse(null);
  }

  /**
   * The writes an inline table refuses (milestone 10b): one naming a metadata column, which only
   * ScalarDB and the driver write, and an INSERT without a column list whose values would fall to a
   * metadata column.
   */
  private static void inlineRefusals(
      Engine engine, StatementShape shape, TableInfo t, String tables) throws SQLException {
    if (shape.kind() == StatementShape.Kind.INSERT && shape.insertColumns().isEmpty()) {
      throw refused(
          tables,
          "an INSERT that lists no columns into a table in ScalarDB's inline layout runs only"
              + " when its values fall to the table's user columns, not ScalarDB's metadata"
              + " columns");
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
      boolean locks,
      @Nullable Map<String, String> tableColumns)
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
    boolean byKey =
        span != null && from >= 0 && ast.tableRefs().size() == 1 && fullKey(engine, where, t);
    // on PostgreSQL the inline layout's write by key is the DML with the stamp in it (todo 329)
    boolean inlineByKey =
        byKey && engine == Engine.POSTGRESQL && t.layout() == Layout.INLINE && !locks;
    Merge merge =
        span == null || !byKey
            ? null
            : dmlSet != null
                ? mergedKeyed(
                    n, ast, t, exposed, span, picked, snapshot, delete, imported, from, end)
                : inlineByKey
                    ? mergedKeyedInline(n, ast, t, exposed, span, delete, from, end, snapshot)
                    : null;
    Step slim =
        merge == null || span == null
            ? null
            : inlineByKey
                ? slimKeyedInline(n, ast, t, exposed, span, inWhere, delete, from, end)
                : slimKeyed(n, ast, t, exposed, span, inWhere, all, delete, imported, from, end);
    // at SNAPSHOT and SERIALIZABLE on PostgreSQL, the vendor's statement with the stamp, for a
    // table the snapshot holds no row in flight of (todo 329); on Oracle at SNAPSHOT in the inline
    // layout, the same statement with the gate hinted (todo 334; the decoupled layout's is a block
    // of its own, a later form)
    boolean plain =
        (engine == Engine.POSTGRESQL || engine == Engine.ORACLE)
            && snapshot
            && !locks
            && from >= 0
            && ast.tableRefs().size() == 1
            && !shape.returning();
    // on Oracle a write by the key as one PL/SQL block (design 4.3 "The keyed write on Oracle",
    // todo 334), and a write beyond the key as the same block over the predicate's keys ("The
    // write beyond the key on Oracle"), for a transaction without recorded anchors of the table
    boolean oracle =
        engine == Engine.ORACLE
            && !locks
            && from >= 0
            && ast.tableRefs().size() == 1
            && !shape.returning();
    List<Integer> keyTypes = engine == Engine.ORACLE ? oracleKeyTypes(tableColumns, n) : null;
    Block block =
        oracle && byKey && span != null
            ? oracleKeyedBlock(
                n, ast, t, exposed, span, inWhere, all, delete, imported, from, end, keyTypes)
            : null;
    // at READ COMMITTED, the write by the key as one statement first, the block on a miss (design
    // 4.3 "The keyed write at READ COMMITTED on Oracle", todo 334)
    RcFirst rcFirst =
        block == null || span == null || snapshot
            ? null
            : oracleRcFirst(n, ast, t, exposed, span, where, all, delete, imported, from, end);
    Entries entries =
        block == null || span == null
            ? null
            : oracleKeyedEntries(
                n, ast, t, exposed, span, inWhere, all, delete, imported, from, end, keyTypes);
    Step predicateBlock =
        oracle && !byKey
            ? oraclePredicateBlock(
                n, ast, t, exposed, span, inWhere, all, delete, imported, snapshot, from, end)
            : null;
    Step fast = plain ? fastKeyed(n, ast, t, span, all, delete, imported, from, end, true) : null;
    Step fastClear =
        fast == null ? null : fastKeyed(n, ast, t, span, all, delete, imported, from, end, false);
    Merge fastMerge =
        fast != null && merge != null && span != null
            ? fastMerged(n, ast, t, span, delete, imported, from, end, true)
            : null;
    Merge fastMergeClear =
        fastMerge == null || span == null
            ? null
            : fastMerged(n, ast, t, span, delete, imported, from, end, false);
    // at READ COMMITTED on PostgreSQL, a decoupled table, without a wait: the clear-table
    // statement with the CAS in its stamp, by the key and over a batch's entries, and beyond the
    // key for a transaction that recorded no anchor of the table (design 4.3, todo 336)
    boolean rc =
        engine == Engine.POSTGRESQL
            && t.layout() != Layout.INLINE
            && !snapshot
            && !locks
            && from >= 0
            && ast.tableRefs().size() == 1
            && !shape.returning();
    Step clearRc =
        rc && byKey && span != null
            ? clearKeyedRc(n, ast, t, exposed, span, inWhere, all, delete, imported, from, end)
            : null;
    Merge clearMergeRc =
        clearRc != null && merge != null && span != null
            ? clearMergedRc(n, ast, t, exposed, span, delete, imported, from, end)
            : null;
    Step predicateRc =
        rc && !byKey && shape.wherePure() && keyColumnsOnly(engine, t, shape.whereColumns())
            ? clearPredicateRc(n, ast, t, exposed, span, inWhere, all, delete, imported, from, end)
            : null;
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
        dmlSet != null
            ? fusedKeyed(n, new Step(keys, inWhere), afterImages, dmlSet, all, delete, imported)
            : engine == Engine.POSTGRESQL && t.layout() == Layout.INLINE && !locks
                ? fusedKeyedInline(
                    n, ast, t, new Step(keys, inWhere), afterImages, span, all, delete, from, end)
                : null,
        merge,
        slim,
        fast,
        fastClear,
        fastMerge,
        fastMergeClear,
        block,
        predicateBlock,
        entries,
        clearRc,
        clearMergeRc,
        predicateRc,
        entries != null && fast != null && span != null
            ? Bulk.keyed(n, ast, t, span, delete, imported, from, end, inFlightCheck(n))
            : null,
        rcFirst);
  }

  /**
   * The write beyond the key on Oracle as one block (design 4.3 "The write beyond the key on
   * Oracle", todo 334): the keys the predicate matches by its committed image, with their metadata
   * (the read view joined to the metadata table, or in the inline layout the committed rows and the
   * before images in flight), the gate on none in flight, none without metadata and, at SNAPSHOT,
   * no after image matching; past it the user's DML once per key by {@code FORALL}, step 2's
   * zero-row rule for a key it skipped (codes 1 to 5), and the CAS once per key written, {@code
   * FORALL i IN VALUES OF} the keys written. The inline UPDATE folds its CAS into the version it
   * writes, -1 where the row moved, a stamp short; the inline DELETE keeps its CAS in its WHERE
   * clause and re-reads the predicate on a skipped row (code 4). The stamp's three values are bound
   * first into locals, so that one text serves every statement inside. One cursor row of counts
   * comes back: {@code found, held, missing, after, written, stamped, skipped, abort}. The binds,
   * in text order: the stamp; the predicate's placeholders in the keys read; at SNAPSHOT the
   * predicate's again in the after-image check; the user's SET placeholders and the predicate's in
   * step 2; the predicate's again in an inline DELETE's zero-row rule; the cursor last. Null when
   * the text cannot be rewritten.
   */
  private static @Nullable Step oraclePredicateBlock(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int @Nullable [] span,
      List<Integer> inWhere,
      List<Integer> all,
      boolean delete,
      boolean imported,
      boolean snapshot,
      int from,
      int end) {
    Engine engine = n.engine;
    String source = ast.source();
    String predicate = span == null ? null : source.substring(span[0], span[1]);
    String where = predicate == null ? "" : " WHERE " + predicate;
    boolean inline = t.layout() == Layout.INLINE;
    int width = n.keyColumns.size();
    String x = exposed;
    String metaTable = inline ? n.data : n.meta;
    StringBuilder b = new StringBuilder("DECLARE l_i ");
    b.append(metaTable)
        .append(".")
        .append(n.txId)
        .append("%TYPE; l_p ")
        .append(metaTable)
        .append(".")
        .append(n.preparedAt)
        .append("%TYPE; l_c ")
        .append(metaTable)
        .append(".")
        .append(n.committedAt)
        .append("%TYPE;");
    List<String> keyLocals = new ArrayList<>();
    List<String> metaLocals = new ArrayList<>();
    List<String> byKeyNow = new ArrayList<>(); // t.k = l_k(i), the key as the data row holds it
    List<String> byMetaKey = new ArrayList<>(); // k = l_m(i), as the metadata row holds it
    for (int i = 0; i < width; i++) {
      String k = engine.quote(n.keyColumns.get(i));
      b.append(" TYPE t_k")
          .append(i)
          .append(" IS TABLE OF ")
          .append(n.data)
          .append(".")
          .append(k)
          .append("%TYPE; l_k")
          .append(i)
          .append(" t_k")
          .append(i)
          .append(";");
      keyLocals.add("l_k" + i);
      byKeyNow.add(x + "." + k + " = l_k" + i + "(i)");
      if (!inline) {
        b.append(" TYPE t_m")
            .append(i)
            .append(" IS TABLE OF ")
            .append(n.meta)
            .append(".")
            .append(k)
            .append("%TYPE; l_m")
            .append(i)
            .append(" t_m")
            .append(i)
            .append(";");
        metaLocals.add("l_m" + i);
        byMetaKey.add(k + " = l_m" + i + "(i)");
      }
    }
    b.append(" TYPE t_n IS TABLE OF NUMBER; l_nometa t_n; l_version t_n; l_state t_n;")
        .append(inline && !delete ? " l_v t_n;" : "")
        .append(" TYPE t_ix IS TABLE OF PLS_INTEGER INDEX BY PLS_INTEGER; l_cnt t_ix; l_do t_ix;")
        .append(" TYPE t_id IS TABLE OF ")
        .append(metaTable)
        .append(".")
        .append(n.txId)
        .append("%TYPE; l_id t_id; l_found NUMBER := 0; l_held NUMBER := 0; l_missing NUMBER := 0;")
        .append(" l_after NUMBER := 0; l_written NUMBER := 0; l_stamped NUMBER := 0;")
        .append(" l_skipped NUMBER := 0; l_abort NUMBER := 0; n_id ")
        .append(metaTable)
        .append(".")
        .append(n.txId)
        .append("%TYPE; n_version NUMBER; n_state NUMBER; n_nometa NUMBER; n_matches NUMBER;")
        .append(" BEGIN l_i := ?; l_p := ?; l_c := ?;");
    // step 1, the keys read
    List<Integer> binds = new ArrayList<>(inWhere);
    if (inline) {
      b.append(" SELECT ")
          .append(n.columns(x))
          .append(", 0, ")
          .append(x)
          .append(".")
          .append(n.txId)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txVersion)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txState)
          .append(" BULK COLLECT INTO ")
          .append(String.join(", ", keyLocals))
          .append(", l_nometa, l_id, l_version, l_state FROM (SELECT ")
          .append(n.columns(x))
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txId)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txVersion)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txState)
          .append(" FROM ")
          .append(n.data)
          .append(" ")
          .append(x)
          .append(" WHERE ")
          .append(predicate == null ? "" : "(" + predicate + ") AND ")
          .append("COALESCE(")
          .append(x)
          .append(".")
          .append(n.txState)
          .append(", 3) = 3 UNION ALL SELECT ")
          .append(n.columns(x))
          .append(", NULL, NULL, 1 FROM ")
          .append(SplitRead.inFlight(engine, t, t.dataColumns(), true))
          .append(" ")
          .append(x)
          .append(where)
          .append(") ")
          .append(x)
          .append(";");
      binds.addAll(inWhere);
    } else {
      List<String> picked = new ArrayList<>();
      for (String k : n.keyColumns) {
        picked.add(x + "." + engine.quote(k) + " AS " + engine.quote(k));
      }
      b.append(" SELECT ")
          .append(n.columns("d"))
          .append(", CASE WHEN m.")
          .append(engine.quote(n.keyColumns.get(0)))
          .append(" IS NULL THEN 1 ELSE 0 END, m.")
          .append(n.txId)
          .append(", m.")
          .append(n.txVersion)
          .append(", m.")
          .append(n.txState)
          .append(", ")
          .append(n.columns("m"))
          .append(" BULK COLLECT INTO ")
          .append(String.join(", ", keyLocals))
          .append(", l_nometa, l_id, l_version, l_state, ")
          .append(String.join(", ", metaLocals))
          .append(" FROM (SELECT ")
          .append(String.join(", ", picked))
          .append(" FROM ")
          .append(n.view)
          .append(" ")
          .append(x)
          .append(where)
          .append(") d LEFT JOIN ")
          .append(n.meta)
          .append(" m ON ")
          .append(n.keyJoin("m", "d"))
          .append(";");
    }
    b.append(" l_found := l_k0.COUNT; FOR i IN 1 .. l_found LOOP IF l_state(i) IN (1, 2) THEN")
        .append(
            " l_held := l_held + 1; END IF; IF l_nometa(i) = 1 THEN l_missing := l_missing + 1;")
        .append(" END IF; END LOOP;");
    if (snapshot) {
      // a row in flight that only its after image matches
      b.append(" SELECT COUNT(*) INTO l_after FROM dual WHERE EXISTS (SELECT 1 FROM ")
          .append(SplitRead.afterImages(engine, t, t.dataColumns(), true))
          .append(" ")
          .append(x)
          .append(where)
          .append(");");
      binds.addAll(inWhere);
    }
    b.append(" IF l_held = 0 AND l_missing = 0 AND l_after = 0 AND l_found > 0 THEN");
    // step 2, the user's DML once per key
    String keyNow = String.join(" AND ", byKeyNow);
    String committedNow = "COALESCE(" + x + "." + n.txState + ", 3) = 3";
    String sameVersion =
        "DECODE("
            + x
            + "."
            + n.txId
            + ", l_id(i), 1, 0) = 1 AND COALESCE("
            + x
            + "."
            + n.txVersion
            + ", 0) = COALESCE(l_version(i), 0)";
    String text;
    if (inline && !delete) {
      int set = ast.afterFirst("SET");
      if (set < 0 || (span != null && set > span[0]) || set > from) {
        return null;
      }
      String stamp =
          n.txId
              + " = l_i, "
              + n.preparedAt
              + " = l_p, "
              + n.committedAt
              + " = l_c, "
              + n.txState
              + " = 3, "
              + n.txVersion
              + " = CASE WHEN "
              + sameVersion
              + " THEN CASE WHEN "
              + x
              + "."
              + n.txId
              + " IS NULL THEN 1 ELSE COALESCE("
              + x
              + "."
              + n.txVersion
              + ", 0) + 1 END ELSE -1 END";
      text =
          source.substring(0, set)
              + " "
              + stamp
              + ","
              + source.substring(set, span == null ? from : span[0])
              + (span == null ? " WHERE " : "(" + predicate + ") AND ")
              + keyNow
              + " AND "
              + committedNow
              + (span == null ? "" : source.substring(span[1], end))
              + " RETURNING "
              + x
              + "."
              + n.txVersion
              + " BULK COLLECT INTO l_v";
    } else if (inline) {
      text =
          source.substring(0, span == null ? from : span[0])
              + (span == null ? " WHERE " : "(" + predicate + ") AND ")
              + keyNow
              + " AND "
              + committedNow
              + " AND "
              + sameVersion
              + (span == null ? "" : source.substring(span[1], end));
    } else {
      text =
          source.substring(0, span == null ? from : span[0])
              + (span == null ? " WHERE " : "(" + predicate + ") AND ")
              + keyNow
              + (span == null ? "" : source.substring(span[1], end));
    }
    String dml = onDataTable(engine, ast, t, true, text, from);
    if (dml == null) {
      return null;
    }
    binds.addAll(all);
    b.append(" FORALL i IN 1 .. l_found ")
        .append(dml)
        .append("; FOR i IN 1 .. l_found LOOP l_cnt(i) := SQL%BULK_ROWCOUNT(i); END LOOP;")
        .append(" FOR i IN 1 .. l_found LOOP IF l_cnt(i) = 1 THEN l_written := l_written + 1;")
        .append(
            " l_do(l_do.COUNT + 1) := i; ELSIF l_cnt(i) > 1 THEN l_abort := 5; EXIT; ELSE BEGIN");
    // step 2's zero-row rule: the row read again by its key, as it is now
    if (inline) {
      b.append(" SELECT 0, ")
          .append(x)
          .append(".")
          .append(n.txId)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txVersion)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txState)
          .append(" INTO n_nometa, n_id, n_version, n_state FROM ")
          .append(n.data)
          .append(" ")
          .append(x)
          .append(" WHERE ")
          .append(keyNow)
          .append(";");
    } else {
      b.append(" SELECT CASE WHEN m.")
          .append(engine.quote(n.keyColumns.get(0)))
          .append(" IS NULL THEN 1 ELSE 0 END, m.")
          .append(n.txId)
          .append(", m.")
          .append(n.txVersion)
          .append(", m.")
          .append(n.txState)
          .append(" INTO n_nometa, n_id, n_version, n_state FROM ")
          .append(n.data)
          .append(" ")
          .append(x)
          .append(" LEFT JOIN ")
          .append(n.meta)
          .append(" m ON ")
          .append(n.keyJoin("m", x))
          .append(" WHERE ")
          .append(keyNow)
          .append(";");
    }
    b.append(" EXCEPTION WHEN NO_DATA_FOUND THEN l_abort := 1; EXIT; END;")
        .append(" IF n_state IN (1, 2) THEN l_abort := 2; EXIT; END IF;")
        .append(
            " IF n_nometa = l_nometa(i) AND ((n_id IS NULL AND l_id(i) IS NULL) OR n_id = l_id(i))")
        .append(" AND COALESCE(n_version, 0) = COALESCE(l_version(i), 0) THEN l_abort := 3; EXIT;")
        .append(" END IF;");
    if (inline && delete && predicate != null) {
      // a row that moved and still matches: the DELETE's CAS in its WHERE clause missed it
      b.append(" SELECT COUNT(*) INTO n_matches FROM ")
          .append(n.data)
          .append(" ")
          .append(x)
          .append(" WHERE (")
          .append(predicate)
          .append(") AND ")
          .append(keyNow)
          .append("; IF n_matches > 0 THEN l_abort := 4; EXIT; END IF;");
      binds.addAll(inWhere);
    }
    b.append(" l_skipped := l_skipped + 1; END IF; END LOOP;");
    // step 3, the CAS once per key written
    if (inline && !delete) {
      b.append(
              " IF l_abort = 0 THEN FOR i IN 1 .. l_v.COUNT LOOP IF l_v(i) <> -1 THEN l_stamped :=")
          .append(" l_stamped + 1; END IF; END LOOP; END IF;");
    } else if (inline) {
      b.append(" IF l_abort = 0 THEN l_stamped := l_written; END IF;");
    } else {
      String metaCas =
          String.join(" AND ", byMetaKey)
              + " AND COALESCE("
              + n.txState
              + ", 3) = 3 AND DECODE("
              + n.txId
              + ", l_id(i), 1, 0) = 1 AND COALESCE("
              + n.txVersion
              + ", 0) = COALESCE(l_version(i), 0)";
      b.append(" IF l_abort = 0 AND l_do.COUNT > 0 THEN FORALL i IN VALUES OF l_do ")
          .append(
              delete && !imported
                  ? "DELETE FROM " + n.meta + " WHERE " + metaCas
                  : "UPDATE "
                      + n.meta
                      + " SET "
                      + n.txId
                      + " = l_i, "
                      + n.preparedAt
                      + " = l_p, "
                      + n.committedAt
                      + " = l_c, "
                      + n.txState
                      + " = 3, "
                      + n.txVersion
                      + " = CASE WHEN "
                      + n.txId
                      + " IS NULL THEN 1 ELSE COALESCE("
                      + n.txVersion
                      + ", 0) + 1 END WHERE "
                      + metaCas)
          .append("; l_stamped := SQL%ROWCOUNT; END IF;");
    }
    b.append(" END IF; ? := l_found; ? := l_held; ? := l_missing; ? := l_after; ? := l_written;")
        .append(" ? := l_stamped; ? := l_skipped; ? := l_abort; END;");
    return new Step(
        hinted(b.toString(), "p"),
        binds,
        Set.of(),
        0,
        Collections.nCopies(8, Types.NUMERIC),
        false);
  }

  /**
   * The decoupled layout's clear-table form on Oracle (design 4.3, todo 334): the user's DML with
   * the gate, returning the keys it wrote, and the stamp over them as a {@code FORALL}, a created
   * table's DELETE removing the metadata rows, an imported table's stamp a {@code MERGE} per key,
   * which also writes the row a key had none of yet and a DELETE's tombstone; two counts back. The
   * stamp's three values are the block's first binds.
   */
  private static Step oracleClearTableBlock(
      Names n, String dml, List<Integer> all, boolean delete, boolean imported) {
    Engine engine = n.engine;
    int width = n.keyColumns.size();
    StringBuilder b =
        new StringBuilder("DECLARE l_i ")
            .append(n.meta)
            .append(".")
            .append(n.txId)
            .append("%TYPE; l_p ")
            .append(n.meta)
            .append(".")
            .append(n.preparedAt)
            .append("%TYPE; l_c ")
            .append(n.meta)
            .append(".")
            .append(n.committedAt)
            .append("%TYPE;");
    List<String> returned = new ArrayList<>();
    List<String> into = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      String k = engine.quote(n.keyColumns.get(i));
      b.append(" TYPE t_w")
          .append(i)
          .append(" IS TABLE OF ")
          .append(n.data)
          .append(".")
          .append(k)
          .append("%TYPE; l_w")
          .append(i)
          .append(" t_w")
          .append(i)
          .append(";");
      returned.add(k);
      into.add("l_w" + i);
    }
    b.append(" l_written NUMBER := 0; l_stamped NUMBER := 0; BEGIN l_i := ?; l_p := ?; l_c := ?; ")
        .append(dml)
        .append(" RETURNING ")
        .append(String.join(", ", returned))
        .append(" BULK COLLECT INTO ")
        .append(String.join(", ", into))
        .append("; l_written := SQL%ROWCOUNT; FORALL i IN 1 .. l_w0.COUNT ");
    b.append(clearTableStamp(n, delete, imported));
    b.append("; l_stamped := SQL%ROWCOUNT; ? := l_written; ? := l_stamped; END;");
    return new Step(
        hinted(b.toString(), "c"), all, Set.of(), 0, List.of(Types.NUMERIC, Types.NUMERIC), false);
  }

  /**
   * The clear-table form's stamp over the keys the DML returned into {@code l_w0, l_w1, ...},
   * {@code i} their index, the stamp's three values in {@code l_i, l_p, l_c}: a created table's
   * UPDATE under the deemed-committed condition, its DELETE removing the metadata row, an imported
   * table's MERGE, which also writes a row a key had none of and a DELETE's tombstone.
   */
  private static String clearTableStamp(Names n, boolean delete, boolean imported) {
    Engine engine = n.engine;
    StringBuilder b = new StringBuilder();
    List<String> byKey = new ArrayList<>();
    List<String> keyed = new ArrayList<>();
    for (int i = 0; i < n.keyColumns.size(); i++) {
      String k = engine.quote(n.keyColumns.get(i));
      byKey.add(k + " = l_w" + i + "(i)");
      keyed.add("l_w" + i + "(i) AS " + k);
    }
    String stamp =
        n.txId
            + " = l_i, "
            + n.preparedAt
            + " = l_p, "
            + n.committedAt
            + " = l_c, "
            + n.txState
            + " = 3, "
            + n.txVersion
            + " = CASE WHEN "
            + n.txId
            + " IS NULL THEN 1 ELSE COALESCE("
            + n.txVersion
            + ", 0) + 1 END";
    if (imported) {
      List<String> on = new ArrayList<>();
      for (String k : n.keyColumns) {
        on.add("m." + engine.quote(k) + " = s." + engine.quote(k));
      }
      List<String> sk = new ArrayList<>();
      for (String k : n.keyColumns) {
        sk.add("s." + engine.quote(k));
      }
      b.append("MERGE INTO ")
          .append(n.meta)
          .append(" m USING (SELECT ")
          .append(String.join(", ", keyed))
          .append(" FROM dual) s ON (")
          .append(String.join(" AND ", on))
          .append(") WHEN MATCHED THEN UPDATE SET m.")
          .append(n.txId)
          .append(" = l_i, m.")
          .append(n.preparedAt)
          .append(" = l_p, m.")
          .append(n.committedAt)
          .append(" = l_c, m.")
          .append(n.txState)
          .append(" = 3, m.")
          .append(n.txVersion)
          .append(" = CASE WHEN m.")
          .append(n.txId)
          .append(" IS NULL THEN 1 ELSE COALESCE(m.")
          .append(n.txVersion)
          .append(", 0) + 1 END")
          .append(" WHERE COALESCE(m.")
          .append(n.txState)
          .append(", 3) = 3 WHEN NOT MATCHED THEN INSERT (")
          .append(n.columns(""))
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
          .append(") VALUES (")
          .append(String.join(", ", sk))
          .append(", l_i, 3, 1, l_p, l_c)");
    } else if (delete) {
      b.append("DELETE FROM ")
          .append(n.meta)
          .append(" WHERE ")
          .append(String.join(" AND ", byKey))
          .append(" AND COALESCE(")
          .append(n.txState)
          .append(", 3) = 3");
    } else {
      b.append("UPDATE ")
          .append(n.meta)
          .append(" SET ")
          .append(stamp)
          .append(" WHERE ")
          .append(String.join(" AND ", byKey))
          .append(" AND COALESCE(")
          .append(n.txState)
          .append(", 3) = 3");
    }
    return b.toString();
  }

  private static final Set<String> STATEMENTS =
      Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE");

  /**
   * An Oracle block with each of its nested statements named by a hint comment of its own, {@code
   * scalardb_native_<shape><n>_<block>}, the n-th statement of a block of {@code shape} whose text
   * hashes to {@code block} (design 4.3, todo 334): PL/SQL keeps one open cursor per nested text, a
   * text two blocks share, or one block repeats per entry, parses on every call, and PL/SQL keeps a
   * hint where it strips a plain comment. Only a statement of the block is named, not a clause of
   * one (a MERGE's {@code THEN UPDATE}); a statement with a hint of its own, the user's, takes the
   * name inside it, after its hints.
   */
  static String hinted(String block, String shape) {
    String name = "scalardb_native_" + shape + "%d_" + Integer.toHexString(block.hashCode());
    StringBuilder out = new StringBuilder(block.length() + 64);
    boolean inStatement = false;
    int depth = 0;
    int nth = 0;
    int i = 0;
    int length = block.length();
    while (i < length) {
      char c = block.charAt(i);
      if (c == '\'' || c == '"') {
        int close = i + 1;
        while (close < length) {
          if (block.charAt(close) == c) {
            if (close + 1 < length && block.charAt(close + 1) == c) {
              close += 2;
              continue;
            }
            break;
          }
          close++;
        }
        out.append(block, i, Math.min(close + 1, length));
        i = close + 1;
        continue;
      }
      if (c == '/' && i + 1 < length && block.charAt(i + 1) == '*') {
        int close = block.indexOf("*/", i + 2);
        close = close < 0 ? length : close + 2;
        out.append(block, i, close);
        i = close;
        continue;
      }
      if (c == '-' && i + 1 < length && block.charAt(i + 1) == '-') {
        int close = block.indexOf('\n', i);
        close = close < 0 ? length : close;
        out.append(block, i, close);
        i = close;
        continue;
      }
      if (inStatement) {
        if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
        } else if (c == ';' && depth == 0) {
          inStatement = false;
        }
        out.append(c);
        i++;
        continue;
      }
      if (Character.isLetter(c)
          && (i == 0 || !isWordPart(block.charAt(i - 1)) && block.charAt(i - 1) != '.')) {
        int stop = i;
        while (stop < length && isWordPart(block.charAt(stop))) {
          stop++;
        }
        String word = block.substring(i, stop);
        out.append(word);
        i = stop;
        if (STATEMENTS.contains(word.toUpperCase(Locale.ROOT))) {
          inStatement = true;
          depth = 0;
          String tag = String.format(Locale.ROOT, name, ++nth);
          int next = i;
          while (next < length && Character.isWhitespace(block.charAt(next))) {
            next++;
          }
          if (block.startsWith("/*+", next)) {
            int close = block.indexOf("*/", next + 3);
            if (close > 0) {
              out.append(block, i, close)
                  .append(close > 0 && block.charAt(close - 1) == ' ' ? "" : " ");
              out.append(tag).append(" */");
              i = close + 2;
              continue;
            }
          }
          out.append(" /*+ ").append(tag).append(" */");
        }
        continue;
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  private static boolean isWordPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '%';
  }

  /**
   * How an Oracle block returns its row: OUT binds, both for the first-use check, or the cursor.
   */
  enum Variant {
    OUT,
    CHECK,
    CURSOR
  }

  /** The parts of an Oracle block's body for one write (todo 334), its locals suffixed. */
  static final class OracleBody {
    final String declare; // the DECLARE items
    final String body; // the statements, the row's return aside
    final List<String> items; // the row's items, locals or expressions over them
    final String stop; // over the locals: the outcome is not final, a block of entries stops

    OracleBody(String declare, String body, List<String> items, String stop) {
      this.declare = declare;
      this.body = body;
      this.items = List.copyOf(items);
      this.stop = stop;
    }

    /** The row as assignments to OUT binds, one per item. */
    String assigns() {
      StringBuilder out = new StringBuilder();
      for (String item : items) {
        out.append(" ? := ").append(item).append(";");
      }
      return out.toString();
    }

    /** The row as a cursor over dual on the same items. */
    String cursor() {
      return " OPEN ? FOR SELECT " + String.join(", ", items) + " FROM dual;";
    }

    /**
     * The body as a block of its own, its row returned as the variant says: OUT binds, those and
     * the cursor after them on the same locals (the check), or the cursor alone.
     */
    String block(Variant v) {
      return hinted(
          "DECLARE "
              + declare
              + " BEGIN "
              + body
              + (v == Variant.CURSOR ? "" : assigns())
              + (v == Variant.OUT ? "" : cursor())
              + " END;",
          "b");
    }
  }

  /**
   * An Oracle block that returns a row with the key (design 4.3, todo 334): its texts per variant,
   * the row's OUT bind types, and the key's, null where a key column's declared type is outside the
   * table the design gives, which keeps the cursor.
   */
  static final class Block {
    final OracleBody body;
    final List<Integer> binds;
    final int stampAfter;
    final List<Integer> rowTypes; // the row's items before the key
    final @Nullable List<Integer> keyTypes;
    final String table;
    private final Map<Variant, Step> steps = new ConcurrentHashMap<>();

    Block(
        OracleBody body,
        List<Integer> binds,
        int stampAfter,
        List<Integer> rowTypes,
        @Nullable List<Integer> keyTypes,
        String table) {
      this.body = body;
      this.binds = List.copyOf(binds);
      this.stampAfter = stampAfter;
      this.rowTypes = List.copyOf(rowTypes);
      this.keyTypes = keyTypes == null ? null : List.copyOf(keyTypes);
      this.table = table;
    }

    /** The variant to run now: the cursor for a key outside the table, else as the table stands. */
    Variant variant() {
      return keyTypes == null ? Variant.CURSOR : OutBindCheck.variant(table);
    }

    Step step(Variant v) {
      return steps.computeIfAbsent(
          v,
          x -> {
            List<Integer> out = new ArrayList<>();
            if (x != Variant.CURSOR) {
              out.addAll(rowTypes);
              out.addAll(Objects.requireNonNull(keyTypes));
            }
            return new Step(body.block(x), binds, Set.of(), stampAfter, out, x != Variant.OUT);
          });
    }
  }

  /**
   * The first-use check of a table's OUT binds (design 4.3 "The keyed write on Oracle", todo 334):
   * the first block of a table in a process returns its key through the OUT binds and through the
   * cursor on the same locals, and the two are compared under {@link Anchors#key}; equal, the
   * table's blocks run with OUT binds alone from then on; different, the table keeps the cursor for
   * the rest of the process, and both values are logged with their classes; a row without a key
   * decides nothing. What a mismatch would otherwise be is an anchor not found, which {@link
   * Anchors#unchanged} takes as unchanged: a silent lost update.
   */
  static final class OutBindCheck {
    private static final Logger LOG = LoggerFactory.getLogger(Writes.class);
    // per table of a process: true once verified, false once a mismatch was seen
    private static final Map<String, Boolean> STATE = new ConcurrentHashMap<>();

    private OutBindCheck() {}

    enum Outcome {
      VERIFIED,
      CURSOR,
      UNDECIDED
    }

    static Variant variant(String table) {
      Boolean state = STATE.get(table);
      return state == null ? Variant.CHECK : state ? Variant.OUT : Variant.CURSOR;
    }

    /** What the two keys say, without recording it. */
    static Outcome compare(Object @Nullable [] out, Object @Nullable [] cursor) {
      if (out == null && cursor == null) {
        return Outcome.UNDECIDED;
      }
      if (out == null || cursor == null) {
        return Outcome.CURSOR;
      }
      return Anchors.key(out).equals(Anchors.key(cursor)) ? Outcome.VERIFIED : Outcome.CURSOR;
    }

    /** Records what the two keys say for {@code table}, once; the first decisive pair counts. */
    static Outcome decide(String table, Object @Nullable [] out, Object @Nullable [] cursor) {
      Outcome outcome = compare(out, cursor);
      if (outcome != Outcome.UNDECIDED
          && STATE.putIfAbsent(table, outcome == Outcome.VERIFIED) == null
          && outcome == Outcome.CURSOR) {
        // the classes per column name the typing mismatch the check exists for; the values, a
        // person's identifier maybe, only at DEBUG (design 4.3)
        LOG.warn(
            "{}: the key read back through OUT binds, typed {}, differs from the key read through"
                + " a cursor, typed {}; its writes keep the cursor, one round trip more each",
            table,
            classes(out),
            classes(cursor));
        LOG.debug(
            "{}: the two keys: through OUT binds {}, through the cursor {}",
            table,
            describe(out),
            describe(cursor));
      }
      return outcome;
    }

    /** Forgets every table, for tests of the first write. */
    static void reset() {
      STATE.clear();
    }

    /** The key's classes per column, "null" for a null column; "no key" for none. */
    static String classes(Object @Nullable [] key) {
      if (key == null) {
        return "no key";
      }
      List<String> out = new ArrayList<>();
      for (Object k : key) {
        out.add(k == null ? "null" : k.getClass().getName());
      }
      return String.join(", ", out);
    }

    /** The key's values with their classes, for the DEBUG line. */
    static String describe(Object @Nullable [] key) {
      if (key == null) {
        return "no key";
      }
      List<String> out = new ArrayList<>();
      for (Object k : key) {
        out.add(
            k == null
                ? "null"
                : (k instanceof byte[] ? Arrays.toString((byte[]) k) : k.toString())
                    + " ("
                    + k.getClass().getName()
                    + ")");
      }
      return String.join(", ", out);
    }
  }

  /**
   * The JDBC type the driver's reads report for an Oracle key column of the declared type (lower
   * case, as {@link #columns} gives it), to register an OUT bind with (design 4.3 on Oracle, todo
   * 334): 2 for NUMBER, 12 for VARCHAR2, 1 for CHAR, 101 for BINARY_DOUBLE and BINARY_FLOAT (read
   * through TO_BINARY_DOUBLE), 93 for DATE and TIMESTAMP, -3 for RAW; null for any other, which
   * keeps the cursor.
   */
  static @Nullable Integer oracleKeyJdbcType(String declared) {
    String d = declared.toLowerCase(Locale.ROOT).trim();
    if (d.startsWith("number")) {
      return Types.NUMERIC;
    }
    if (d.startsWith("varchar2")) {
      return Types.VARCHAR;
    }
    if (d.startsWith("char")) {
      return Types.CHAR;
    }
    if (d.equals("binary_double") || d.equals("binary_float")) {
      return 101; // OracleTypes.BINARY_DOUBLE, as the reads report both
    }
    if (d.equals("date") || (d.startsWith("timestamp") && !d.contains("time zone"))) {
      return Types.TIMESTAMP;
    }
    if (d.startsWith("raw")) {
      return Types.VARBINARY;
    }
    return null;
  }

  /** The key columns' OUT bind types from the declared types, null where one is not known. */
  static @Nullable List<Integer> oracleKeyTypes(@Nullable Map<String, String> columns, Names n) {
    if (columns == null) {
      return null;
    }
    List<Integer> out = new ArrayList<>();
    for (String k : n.keyColumns) {
      Integer type = oracleKeyJdbcType(type(n.engine, columns, k).replace(" generated", ""));
      if (type == null) {
        return null;
      }
      out.add(type);
    }
    return out;
  }

  /**
   * The row's types before the key for the keyed block: rows, meta, tx_id, version, state, written,
   * stamped.
   */
  private static final List<Integer> KEYED_ROW_TYPES =
      List.of(
          Types.NUMERIC,
          Types.NUMERIC,
          Types.VARCHAR,
          Types.NUMERIC,
          Types.NUMERIC,
          Types.NUMERIC,
          Types.NUMERIC);

  /** The row's types before the key for the INSERT block: meta, stamped. */
  private static final List<Integer> INSERT_ROW_TYPES = List.of(Types.NUMERIC, Types.NUMERIC);

  /**
   * Reads a key from OUT binds as {@link #readKey} reads it from a result set, by the same switch
   * on the JDBC type the bind was registered with, so that the two cannot drift.
   */
  static Object @Nullable [] readOutKey(CallableStatement cs, int from, List<Integer> types)
      throws SQLException {
    Object[] key = new Object[types.size()];
    for (int i = 0; i < key.length; i++) {
      int c = from + i;
      switch (types.get(i)) {
        case Types.TIMESTAMP:
          key[i] = cs.getObject(c, LocalDateTime.class);
          break;
        case Types.TIME:
          key[i] = cs.getObject(c, LocalTime.class);
          break;
        case Types.TIMESTAMP_WITH_TIMEZONE:
          key[i] = cs.getObject(c, OffsetDateTime.class);
          break;
        default:
          key[i] = cs.getObject(c);
      }
    }
    return key[0] == null ? null : key;
  }

  /** The keyed block's row, as read from its OUT binds or its cursor. */
  static final class KeyedRow {
    final int rows;
    final int meta;
    final @Nullable String txId;
    final @Nullable Object version;
    final @Nullable Object state;
    final long written;
    final long stamped;
    final Object @Nullable [] key;

    KeyedRow(
        int rows,
        int meta,
        @Nullable String txId,
        @Nullable Object version,
        @Nullable Object state,
        long written,
        long stamped,
        Object @Nullable [] key) {
      this.rows = rows;
      this.meta = meta;
      this.txId = txId;
      this.version = version;
      this.state = state;
      this.written = written;
      this.stamped = stamped;
      this.key = key;
    }

    static KeyedRow from(ResultSet rs, int from, int width) throws SQLException {
      return new KeyedRow(
          rs.getInt(from),
          rs.getInt(from + 1),
          rs.getString(from + 2),
          rs.getObject(from + 3),
          rs.getObject(from + 4),
          rs.getLong(from + 5),
          rs.getLong(from + 6),
          readKey(rs, from + 7, width));
    }

    static KeyedRow from(CallableStatement cs, int at, List<Integer> keyTypes) throws SQLException {
      return new KeyedRow(
          cs.getInt(at),
          cs.getInt(at + 1),
          cs.getString(at + 2),
          cs.getObject(at + 3),
          cs.getObject(at + 4),
          cs.getLong(at + 5),
          cs.getLong(at + 6),
          readOutKey(cs, at + 7, keyTypes));
    }
  }

  /** At most this many entries of a batch go as one Oracle block of entries (design 4.4). */
  static final int ENTRIES_MAX = 16;

  /**
   * An Oracle block over a batch's entries of one write (design 4.4 "Batches on Oracle", todo 334):
   * the write's body once per entry, in order, each entry's locals suffixed by its number and its
   * placeholders its own binds, every entry under {@code IF l_stop = 0}, which the first entry
   * whose outcome is not final sets; the cursor returns one row per entry, {@code n, done} and then
   * the write's own row, {@code done} 0 for an entry not reached.
   */
  static final class Entries {
    private final IntFunction<OracleBody> body;
    final List<Integer> binds; // each entry's, in text order
    final int stampAfter; // where the stamp's three values sit among an entry's binds
    final List<Integer> rowTypes; // the write's row before the key
    final @Nullable List<Integer> keyTypes;
    final String table;
    private final Map<String, String> texts = new ConcurrentHashMap<>();

    Entries(
        IntFunction<OracleBody> body,
        List<Integer> binds,
        int stampAfter,
        List<Integer> rowTypes,
        @Nullable List<Integer> keyTypes,
        String table) {
      this.body = body;
      this.binds = List.copyOf(binds);
      this.stampAfter = stampAfter;
      this.rowTypes = List.copyOf(rowTypes);
      this.keyTypes = keyTypes == null ? null : List.copyOf(keyTypes);
      this.table = table;
    }

    /** The variant to run now, as {@link Block#variant()}. */
    Variant variant() {
      return keyTypes == null ? Variant.CURSOR : OutBindCheck.variant(table);
    }

    /** The OUT binds one entry returns: {@code n, done} and the write's row. */
    List<Integer> outTypes() {
      List<Integer> out = new ArrayList<>(List.of(Types.NUMERIC, Types.NUMERIC));
      out.addAll(rowTypes);
      out.addAll(Objects.requireNonNull(keyTypes));
      return out;
    }

    /** The block for {@code entries} entries, its rows returned as the variant says. */
    String sql(int entries, Variant v) {
      return texts.computeIfAbsent(
          entries + ":" + v,
          key -> {
            int k = entries;
            StringBuilder declare = new StringBuilder("DECLARE");
            StringBuilder run = new StringBuilder(" BEGIN");
            StringBuilder outs = new StringBuilder();
            StringBuilder rows = new StringBuilder(" OPEN ? FOR ");
            for (int i = 1; i <= k; i++) {
              OracleBody b = body.apply(i);
              declare
                  .append(" ")
                  .append(b.declare)
                  .append(" l_done_")
                  .append(i)
                  .append(" NUMBER := 0;");
              run.append(" IF l_stop = 0 THEN l_done_")
                  .append(i)
                  .append(" := 1; ")
                  .append(b.body)
                  .append(" IF ")
                  .append(b.stop)
                  .append(" THEN l_stop := 1; END IF; END IF;");
              outs.append(" ? := ").append(i).append("; ? := l_done_").append(i).append(";");
              outs.append(b.assigns());
              rows.append(i == 1 ? "" : " UNION ALL ")
                  .append("SELECT ")
                  .append(i)
                  .append(", l_done_")
                  .append(i)
                  .append(", ")
                  .append(String.join(", ", b.items))
                  .append(" FROM dual");
            }
            return hinted(
                declare
                    + " l_stop NUMBER := 0;"
                    + run
                    + (v == Variant.CURSOR ? "" : outs)
                    + (v == Variant.OUT ? "" : rows + " ORDER BY 1;")
                    + " END;",
                "e");
          });
    }

    /** The placeholders one entry binds, its stamp included. */
    int parameters() {
      return binds.size() + (stampAfter >= 0 ? 3 : 0);
    }
  }

  /**
   * A batch's entries as one bulk block on Oracle (design 4.4 "The entries merged on a clear table
   * at SNAPSHOT", todo 334): at SNAPSHOT on a decoupled table of which the transaction recorded no
   * anchor, where the write's single form is the clear-table block. One text per write shape and
   * slot types for every batch of up to {@link #SLOTS} entries, the slots past {@code l_n} bound
   * NULL. The stamp's three values and {@code l_n} are bound first, then each placeholder's value
   * per slot into an index-by table typed as ojdbc binds the scalar ({@link #plsqlType}). The keyed
   * write probes the in-flight index once, and under {@code l_g = 0} runs the user's DML as {@code
   * FORALL} returning the keys, the counts per entry from {@code SQL%BULK_ROWCOUNT}, and the
   * clear-table stamp over the keys as one {@code FORALL}; OUT {@code l_g}, the keys returned, the
   * rows stamped, the count per slot. The INSERT runs the user's INSERT as {@code FORALL}, the
   * metadata insert under the hint as {@code FORALL}, and the takeover over the entries whose
   * metadata insert counted 0 as {@code FORALL i IN INDICES OF}; OUT the rows taken over and those
   * stamped.
   */
  static final class Bulk {
    static final int SLOTS = 16;
    private static final char HOLE = '\u0001';
    private final Names n;
    // the user's DML around its placeholders, which are numbered 1.. in text order
    private final List<String> dml;
    // the INSERT's key values around their placeholders, cast to the key columns' types; null for
    // a keyed write
    private final @Nullable List<List<String>> keyValues;
    private final @Nullable List<List<Integer>> keyHoles;
    private final boolean delete;
    private final boolean imported;
    // the keyed write's coarse check, which the open gate passes
    final @Nullable String check;
    private final Map<String, String> texts = new ConcurrentHashMap<>();

    private Bulk(
        Names n,
        List<String> dml,
        @Nullable List<List<String>> keyValues,
        @Nullable List<List<Integer>> keyHoles,
        boolean delete,
        boolean imported,
        @Nullable String check) {
      this.n = n;
      this.dml = List.copyOf(dml);
      this.keyValues = keyValues;
      this.keyHoles = keyHoles;
      this.delete = delete;
      this.imported = imported;
      this.check = check;
    }

    boolean insert() {
      return keyValues != null;
    }

    /** The placeholders of the statement, each a slot collection. */
    int holes() {
      return dml.size() - 1;
    }

    /** The OUT binds after the slots. */
    int outs() {
      return insert() ? 2 : 3 + SLOTS;
    }

    /** The source with each placeholder a one-character mark, so that every offset holds. */
    private static @Nullable String marked(SqlAst ast) {
      List<int[]> holes = ast.placeholderSpans();
      if (holes == null) {
        return null;
      }
      char[] chars = ast.source().toCharArray();
      for (int[] h : holes) {
        if (h[1] - h[0] != 1) {
          return null;
        }
        chars[h[0]] = HOLE;
      }
      String marked = new String(chars);
      return marked.chars().filter(c -> c == HOLE).count() == holes.size() ? marked : null;
    }

    private static @Nullable List<String> pieces(@Nullable String text, int holes) {
      if (text == null) {
        return null;
      }
      List<String> out = List.of(text.split(String.valueOf(HOLE), -1));
      return out.size() == holes + 1 ? out : null;
    }

    /** The keyed write's bulk block, the DML as the clear-table block has it without the gate. */
    static @Nullable Bulk keyed(
        Names n,
        SqlAst ast,
        TableInfo t,
        int[] span,
        boolean delete,
        boolean imported,
        int from,
        int end,
        String check) {
      String source = marked(ast);
      if (source == null || t.layout() == Layout.INLINE) {
        return null;
      }
      String text =
          source.substring(0, from).stripTrailing()
              + " WHERE ("
              + source.substring(span[0], span[1])
              + ")"
              + source.substring(span[1], end);
      List<String> dml =
          pieces(onDataTable(n.engine, ast, t, true, text, from), ast.parameterCount());
      return dml == null ? null : new Bulk(n, dml, null, null, delete, imported, check);
    }

    /**
     * The INSERT's bulk block, for one row of VALUES whose key values are placeholders and literals
     * of key columns of known types (as {@link #oracleInsertAll} takes them).
     */
    static @Nullable Bulk insert(
        Names n,
        SqlAst ast,
        TableInfo t,
        List<Expression> keyValues,
        int end,
        @Nullable Map<String, String> tableColumns) {
      String source = marked(ast);
      if (source == null) {
        return null;
      }
      List<String> dml =
          pieces(
              onDataTable(n.engine, ast, t, false, source.substring(0, end), -1),
              ast.parameterCount());
      List<List<String>> values = new ArrayList<>();
      List<List<Integer>> holes = new ArrayList<>();
      for (int i = 0; i < keyValues.size(); i++) {
        int[] span = ast.span(keyValues.get(i));
        List<Integer> in = span == null ? null : ast.placeholdersIn(span[0], span[1]);
        String full = type(n.engine, tableColumns, t.keyColumns().get(i)).replace(" generated", "");
        if (span == null || in == null || full.isEmpty()) {
          return null;
        }
        List<String> around =
            pieces("CAST(" + source.substring(span[0], span[1]) + " AS " + full + ")", in.size());
        if (around == null) {
          return null;
        }
        values.add(around);
        holes.add(in);
      }
      return dml == null ? null : new Bulk(n, dml, values, holes, false, false, null);
    }

    /** {@code pieces} with their holes the slots of {@code numbers} at index {@code i}. */
    private static String fill(List<String> pieces, List<Integer> numbers) {
      StringBuilder b = new StringBuilder(pieces.get(0));
      for (int h = 1; h < pieces.size(); h++) {
        b.append("l_v").append(numbers.get(h - 1)).append("(i)").append(pieces.get(h));
      }
      return b.toString();
    }

    /** The slot types of the entries, or null where an entry's binding has none or they differ. */
    @Nullable List<String> types(List<Context> entries) {
      List<String> types = new ArrayList<>();
      for (int j = 1; j <= holes(); j++) {
        String type = null;
        for (Context c : entries) {
          String t = c.plsqlType(j);
          if (t == null || (type != null && !type.equals(t))) {
            return null;
          }
          type = t;
        }
        types.add(Objects.requireNonNull(type));
      }
      return types;
    }

    String sql(List<String> types) {
      return texts.computeIfAbsent(String.join(",", types), k -> hinted(build(types), "k"));
    }

    private String build(List<String> types) {
      Engine engine = n.engine;
      StringBuilder b =
          new StringBuilder("DECLARE l_i ")
              .append(n.meta)
              .append(".")
              .append(n.txId)
              .append("%TYPE; l_p ")
              .append(n.meta)
              .append(".")
              .append(n.preparedAt)
              .append("%TYPE; l_c ")
              .append(n.meta)
              .append(".")
              .append(n.committedAt)
              .append("%TYPE;");
      List<Integer> all = new ArrayList<>();
      for (int j = 1; j <= types.size(); j++) {
        b.append(" TYPE t_v")
            .append(j)
            .append(" IS TABLE OF ")
            .append(types.get(j - 1))
            .append(" INDEX BY PLS_INTEGER; l_v")
            .append(j)
            .append(" t_v")
            .append(j)
            .append(";");
        all.add(j);
      }
      List<String> keys = new ArrayList<>();
      for (String k : n.keyColumns) {
        keys.add(engine.quote(k));
      }
      if (!insert()) {
        for (int i = 0; i < keys.size(); i++) {
          b.append(" TYPE t_w")
              .append(i)
              .append(" IS TABLE OF ")
              .append(n.data)
              .append(".")
              .append(keys.get(i))
              .append("%TYPE; l_w")
              .append(i)
              .append(" t_w")
              .append(i)
              .append(";");
        }
      }
      b.append(" TYPE t_o IS TABLE OF NUMBER INDEX BY PLS_INTEGER; l_o t_o; l_n PLS_INTEGER; l_g")
          .append(" NUMBER := 0; l_r NUMBER := 0; l_s NUMBER := 0;")
          .append(" BEGIN l_i := ?; l_p := ?; l_c := ?; l_n := ?;");
      for (int e = 1; e <= SLOTS; e++) {
        for (int j = 1; j <= types.size(); j++) {
          b.append(" l_v").append(j).append("(").append(e).append(") := ?;");
        }
      }
      String stamp =
          n.txId
              + " = l_i, "
              + n.preparedAt
              + " = l_p, "
              + n.committedAt
              + " = l_c, "
              + n.txState
              + " = 3, "
              + n.txVersion
              + " = CASE WHEN "
              + n.txId
              + " IS NULL THEN 1 ELSE COALESCE("
              + n.txVersion
              + ", 0) + 1 END";
      if (!insert()) {
        List<String> into = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
          into.add("l_w" + i);
        }
        b.append(" SELECT COUNT(*) INTO l_g FROM (SELECT ")
            .append(n.inflightHint("m"))
            .append("1 FROM ")
            .append(n.meta)
            .append(" m WHERE m.")
            .append(n.txState)
            .append(" IN (1, 2) AND ROWNUM = 1); IF l_g = 0 THEN FORALL i IN 1 .. l_n ")
            .append(fill(dml, all))
            .append(" RETURNING ")
            .append(String.join(", ", keys))
            .append(" BULK COLLECT INTO ")
            .append(String.join(", ", into))
            .append("; FOR i IN 1 .. l_n LOOP l_o(i) := SQL%BULK_ROWCOUNT(i); END LOOP; l_r :=")
            .append(" l_w0.COUNT; FORALL i IN 1 .. l_w0.COUNT ")
            .append(clearTableStamp(n, delete, imported))
            .append("; l_s := SQL%ROWCOUNT; END IF; ? := l_g; ? := l_r; ? := l_s;");
        for (int e = 1; e <= SLOTS; e++) {
          b.append(" ? := CASE WHEN l_o.EXISTS(")
              .append(e)
              .append(") THEN l_o(")
              .append(e)
              .append(") ELSE 0 END;");
        }
        return b.append(" END;").toString();
      }
      List<String> values = new ArrayList<>();
      List<String> byKey = new ArrayList<>();
      for (int i = 0; i < keys.size(); i++) {
        String v =
            fill(Objects.requireNonNull(keyValues).get(i), Objects.requireNonNull(keyHoles).get(i));
        values.add(v);
        byKey.add(keys.get(i) + " = " + v);
      }
      return b.append(" FORALL i IN 1 .. l_n ")
          .append(fill(dml, all))
          .append("; FORALL i IN 1 .. l_n INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(x (")
          .append(n.columns(""))
          .append(")) */ INTO ")
          .append(n.meta)
          .append(" x (")
          .append(n.columns(""))
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
          .append(") VALUES (")
          .append(String.join(", ", values))
          .append(", l_i, 3, 1, l_p, l_c); FOR i IN 1 .. l_n LOOP IF SQL%BULK_ROWCOUNT(i) = 0")
          .append(" THEN l_o(i) := i; END IF; END LOOP; l_r := l_o.COUNT; IF l_r > 0 THEN FORALL")
          .append(" i IN INDICES OF l_o UPDATE ")
          .append(n.meta)
          .append(" SET ")
          .append(stamp)
          .append(" WHERE ")
          .append(String.join(" AND ", byKey))
          .append(" AND COALESCE(")
          .append(n.txState)
          .append(", 3) = 3; l_s := SQL%ROWCOUNT; END IF; ? := l_r; ? := l_s; END;")
          .toString();
    }
  }

  /**
   * A batch's entries through the plan's bulk block, {@link Bulk#SLOTS} at a time: how many were
   * finalized; 0 where the first block's gate was closed or its entries' bindings have no slot
   * types, for the block of entries to run them; -1 where the block failed and was undone whole,
   * for the entries one by one (design 4.4).
   */
  private static int applyOracleBulk(
      Connection vendor, Bulk bulk, List<Context> contexts, Finalized finalized)
      throws SQLException {
    int done = 0;
    while (done < contexts.size()) {
      List<Context> chunk = contexts.subList(done, Math.min(contexts.size(), done + Bulk.SLOTS));
      List<String> types = bulk.types(chunk);
      if (types == null) {
        return done;
      }
      Run lead = new Run(vendor, chunk.get(0));
      lead.checkCancelled();
      try (CallableStatement cs = lead.track(vendor.prepareCall(bulk.sql(types)))) {
        int at = lead.stamp(cs, 1);
        cs.setInt(at++, chunk.size());
        for (int e = 0; e < Bulk.SLOTS; e++) {
          for (int j = 1; j <= types.size(); j++) {
            if (e < chunk.size()) {
              chunk.get(e).bind(cs, at++, j, false);
            } else {
              cs.setNull(at++, nullTypeOf(types.get(j - 1)));
            }
          }
        }
        int out = at;
        for (int o = 0; o < bulk.outs(); o++) {
          cs.registerOutParameter(at++, Types.NUMERIC);
        }
        try {
          cs.execute();
        } catch (SQLException ex) {
          if (undoable(ex)) {
            return done == 0 ? -1 : done; // nothing of this block remains
          }
          throw ex;
        }
        if (bulk.insert()) {
          // a metadata row taken over that was not stamped is in flight
          if (cs.getLong(out + 1) < cs.getLong(out)) {
            throw new Aborted("a key the statement inserts is being written by ScalarDB");
          }
          for (int e = 0; e < chunk.size(); e++) {
            finalized.record(done + e, 1);
          }
        } else {
          if (cs.getLong(out) != 0) {
            return done; // the gate closed: the block of entries tells each entry
          }
          if (cs.getLong(out + 2) != cs.getLong(out + 1)) {
            throw new Aborted("a row the statement writes changed after it was read");
          }
          if (bulk.check != null) {
            lead.context.pass(bulk.check); // the gate was open: no row of the table is in flight
          }
          for (int e = 0; e < chunk.size(); e++) {
            finalized.record(done + e, cs.getLong(out + 3 + e));
          }
        }
        lead.context.anchors().wrote(lead.txId);
      } finally {
        lead.context.running(null);
      }
      done += chunk.size();
    }
    return done;
  }

  /**
   * The keyed write at READ COMMITTED on Oracle as one statement, tried before the block (design
   * 4.3 "The keyed write at READ COMMITTED on Oracle", todo 334): its two texts, without the anchor
   * and with it, and where each key column's value comes from. On an inline table the user's DML
   * with the stamp in an UPDATE's SET list and its WHERE clause extended by the committed state and
   * the anchor; on a decoupled one a block of the DML under the same conditions read from the
   * metadata row, the key it wrote returned, and the CAS of step 3 under the same conditions, two
   * counts back. Null where the text cannot be rewritten or a placeholder follows the predicate.
   */
  private static @Nullable RcFirst oracleRcFirst(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      @Nullable Expression where,
      List<Integer> all,
      boolean delete,
      boolean imported,
      int from,
      int end) {
    List<Object> sources = keySources(n, ast, where);
    List<Integer> after = ast.placeholdersIn(span[1], end);
    if (sources == null || after == null || !after.isEmpty()) {
      return null;
    }
    boolean inline = t.layout() == Layout.INLINE;
    String plain = rcFirstText(n, ast, t, exposed, span, delete, imported, from, end, false);
    String anchored = rcFirstText(n, ast, t, exposed, span, delete, imported, from, end, true);
    if (plain == null || anchored == null) {
      return null;
    }
    // the stamp: an inline UPDATE's first, a decoupled table's after the DML's binds; an inline
    // DELETE and a created table's DELETE stamp nothing
    boolean stamps = inline ? !delete : imported || !delete;
    return new RcFirst(plain, anchored, all, sources, inline, stamps);
  }

  /** {@link #oracleRcFirst}'s text, with the anchor or without it. */
  private static @Nullable String rcFirstText(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      boolean delete,
      boolean imported,
      int from,
      int end,
      boolean anchored) {
    Engine engine = n.engine;
    String source = ast.source();
    String predicate = source.substring(span[0], span[1]);
    String tail = source.substring(span[1], end);
    String x = exposed;
    if (t.layout() == Layout.INLINE) {
      String conditions =
          "COALESCE("
              + x
              + "."
              + n.txState
              + ", 3) = 3"
              + (anchored
                  ? " AND DECODE("
                      + x
                      + "."
                      + n.txId
                      + ", ?, 1, 0) = 1 AND COALESCE("
                      + x
                      + "."
                      + n.txVersion
                      + ", 0) = COALESCE(?, 0)"
                  : "");
      String text;
      if (delete) {
        text = source.substring(0, span[0]) + "(" + predicate + ") AND " + conditions + tail;
      } else {
        int set = ast.afterFirst("SET");
        if (set < 0 || set > span[0]) {
          return null;
        }
        text =
            source.substring(0, set)
                + " "
                + inlineStampSets(n)
                + ","
                + source.substring(set, span[0])
                + "("
                + predicate
                + ") AND "
                + conditions
                + tail;
      }
      return onDataTable(engine, ast, t, true, text, from);
    }
    String anchor =
        anchored
            ? " AND DECODE(m."
                + n.txId
                + ", l_ai, 1, 0) = 1 AND COALESCE(m."
                + n.txVersion
                + ", 0) = COALESCE(l_av, 0)"
            : "";
    String dml =
        onDataTable(
            engine,
            ast,
            t,
            true,
            source.substring(0, span[0])
                + "("
                + predicate
                + ") AND EXISTS (SELECT 1 FROM "
                + n.meta
                + " m WHERE "
                + n.keyJoin("m", x)
                + " AND COALESCE(m."
                + n.txState
                + ", 3) = 3"
                + anchor
                + ")"
                + tail,
            from);
    if (dml == null) {
      return null;
    }
    StringBuilder b = new StringBuilder("DECLARE l_written NUMBER := 0; l_stamped NUMBER := 0;");
    if (anchored) {
      b.append(" l_ai ")
          .append(n.meta)
          .append(".")
          .append(n.txId)
          .append("%TYPE; l_av ")
          .append(n.meta)
          .append(".")
          .append(n.txVersion)
          .append("%TYPE;");
    }
    List<String> returned = new ArrayList<>();
    List<String> into = new ArrayList<>();
    List<String> byKey = new ArrayList<>();
    for (int i = 0; i < n.keyColumns.size(); i++) {
      String k = engine.quote(n.keyColumns.get(i));
      b.append(" l_k").append(i).append(" ").append(n.data).append(".").append(k).append("%TYPE;");
      returned.add(k);
      into.add("l_k" + i);
      byKey.add(k + " = l_k" + i);
    }
    String metaCas =
        String.join(" AND ", byKey)
            + " AND COALESCE("
            + n.txState
            + ", 3) = 3"
            + (anchored
                ? " AND DECODE("
                    + n.txId
                    + ", l_ai, 1, 0) = 1 AND COALESCE("
                    + n.txVersion
                    + ", 0) = COALESCE(l_av, 0)"
                : "");
    b.append(" BEGIN ")
        .append(anchored ? "l_ai := ?; l_av := ?; " : "")
        .append(dml)
        .append(" RETURNING ")
        .append(String.join(", ", returned))
        .append(" INTO ")
        .append(String.join(", ", into))
        .append("; l_written := SQL%ROWCOUNT; IF l_written = 1 THEN ")
        .append(
            delete && !imported
                ? "DELETE FROM " + n.meta + " WHERE " + metaCas
                : "UPDATE " + n.meta + " SET " + inlineStampSets(n) + " WHERE " + metaCas)
        .append("; l_stamped := SQL%ROWCOUNT; END IF; ? := l_written; ? := l_stamped; END;");
    return hinted(b.toString(), anchored ? "ra" : "r");
  }

  /**
   * Where each key column's value comes from in a WHERE clause that is the full key, in the key's
   * order: a placeholder's number, or a literal's value; null where a value is neither.
   */
  private static @Nullable List<Object> keySources(
      Names n, SqlAst ast, @Nullable Expression where) {
    Engine engine = n.engine;
    List<Expression> conjuncts = new ArrayList<>();
    conjuncts(where, conjuncts);
    Object[] out = new Object[n.keyColumns.size()];
    for (Expression e : conjuncts) {
      if (!(e instanceof EqualsTo)) {
        return null;
      }
      Expression column = ((EqualsTo) e).getLeftExpression();
      Expression value = ((EqualsTo) e).getRightExpression();
      if (!(column instanceof Column)) {
        Expression other = column;
        column = value;
        value = other;
      }
      if (!(column instanceof Column)) {
        return null;
      }
      String name = engine.columnKey(engine.resolveIdentifier(((Column) column).getColumnName()));
      int at = -1;
      for (int i = 0; i < n.keyColumns.size(); i++) {
        if (engine.columnKey(n.keyColumns.get(i)).equals(name)) {
          at = i;
        }
      }
      if (at < 0) {
        return null;
      }
      if (value instanceof JdbcParameter) {
        int[] span = ast.span(value);
        List<Integer> in = span == null ? null : ast.placeholdersIn(span[0], span[1]);
        if (in == null || in.size() != 1) {
          return null;
        }
        out[at] = in.get(0);
      } else if (value instanceof LongValue) {
        out[at] = new Literal(((LongValue) value).getBigIntegerValue());
      } else if (value instanceof StringValue) {
        out[at] = new Literal(((StringValue) value).getValue());
      } else {
        return null;
      }
    }
    for (Object o : out) {
      if (o == null) {
        return null;
      }
    }
    return List.of(out);
  }

  /** A key column's value written into the statement. */
  private static final class Literal {
    final Object value;

    Literal(Object value) {
      this.value = value;
    }
  }

  /** {@link #oracleRcFirst}'s texts and the key's sources. */
  static final class RcFirst {
    final String plain;
    final String anchored;
    final List<Integer> binds;
    private final List<Object> sources;
    final boolean inline;
    final boolean stamps;

    RcFirst(
        String plain,
        String anchored,
        List<Integer> binds,
        List<Object> sources,
        boolean inline,
        boolean stamps) {
      this.plain = plain;
      this.anchored = anchored;
      this.binds = List.copyOf(binds);
      this.sources = sources;
      this.inline = inline;
      this.stamps = stamps;
    }

    /**
     * The statement's key as the reads record a key of an Oracle table, by {@link Anchors#key}: an
     * integral number as ojdbc returns a NUMBER, a BigDecimal of scale 0, a fraction without
     * trailing zeros; null where a value was bound in a way the driver does not read back.
     */
    Object @Nullable [] key(Context context) {
      Object[] key = new Object[sources.size()];
      for (int i = 0; i < key.length; i++) {
        Object s = sources.get(i);
        Object v;
        if (s instanceof Literal) {
          v = ((Literal) s).value;
        } else {
          Object[] one = context.value((Integer) s);
          if (one == null) {
            return null;
          }
          v = one[0];
        }
        key[i] = asRead(v);
      }
      return key;
    }

    private static @Nullable Object asRead(@Nullable Object v) {
      if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
        return BigDecimal.valueOf(((Number) v).longValue());
      }
      if (v instanceof BigInteger) {
        return new BigDecimal((BigInteger) v);
      }
      if (v instanceof BigDecimal) {
        BigDecimal d = ((BigDecimal) v).stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0) : d;
      }
      return v;
    }
  }

  /** Where the stamp's three values sit among the keyed block's binds. */
  private static int keyedStampAfter(
      boolean inline, boolean delete, boolean imported, int inWhere, int all) {
    if (inline) {
      return delete ? -1 : inWhere;
    }
    return delete && !imported ? -1 : inWhere + all;
  }

  /**
   * The keyed write on Oracle as one anonymous block (design 4.3, todo 334): the three steps of 4.3
   * with the two decisions between them made in the block, which returns before writing on every
   * outcome the driver waits on or counts as nothing written, and writes only a committed row. Its
   * read is the data row with its metadata, the row itself in the inline layout; in the decoupled
   * layout the metadata row's own key is read beside the data row's and matches the CAS, as the
   * steps match it (a CHAR(n) key is varchar in the metadata table). Its one cursor row is {@code
   * rows, meta, tx_id, tx_version, tx_state, written, stamped, k...}, the key as the data row reads
   * it. The binds, in text order: the predicate's placeholders for the read; then in the inline
   * UPDATE the stamp, the user's SET placeholders and the predicate's; in the decoupled block the
   * user's placeholders and, where a stamp is written, the stamp's values; the cursor last. Null
   * when the text cannot be rewritten.
   */
  private static @Nullable Block oracleKeyedBlock(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      List<Integer> inWhere,
      List<Integer> all,
      boolean delete,
      boolean imported,
      int from,
      int end,
      @Nullable List<Integer> keyTypes) {
    OracleBody b = keyedBody(n, ast, t, exposed, span, delete, imported, from, end, "");
    if (b == null) {
      return null;
    }
    List<Integer> binds = new ArrayList<>(inWhere);
    binds.addAll(all);
    boolean inline = t.layout() == Layout.INLINE;
    return new Block(
        b,
        binds,
        keyedStampAfter(inline, delete, imported, inWhere.size(), all.size()),
        KEYED_ROW_TYPES,
        keyTypes,
        n.table);
  }

  /** The keyed block over a batch's entries (design 4.4), null where the block cannot be built. */
  private static @Nullable Entries oracleKeyedEntries(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      List<Integer> inWhere,
      List<Integer> all,
      boolean delete,
      boolean imported,
      int from,
      int end,
      @Nullable List<Integer> keyTypes) {
    if (keyedBody(n, ast, t, exposed, span, delete, imported, from, end, "_1") == null) {
      return null;
    }
    List<Integer> binds = new ArrayList<>(inWhere);
    binds.addAll(all);
    boolean inline = t.layout() == Layout.INLINE;
    return new Entries(
        i ->
            Objects.requireNonNull(
                keyedBody(n, ast, t, exposed, span, delete, imported, from, end, "_" + i)),
        binds,
        keyedStampAfter(inline, delete, imported, inWhere.size(), all.size()),
        KEYED_ROW_TYPES,
        keyTypes,
        n.table);
  }

  /** The keyed block's body, its locals suffixed by {@code sfx}; see {@link #oracleKeyedBlock}. */
  private static @Nullable OracleBody keyedBody(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      boolean delete,
      boolean imported,
      int from,
      int end,
      String sfx) {
    Engine engine = n.engine;
    String source = ast.source();
    String predicate = source.substring(span[0], span[1]);
    String tail = source.substring(span[1], end);
    boolean inline = t.layout() == Layout.INLINE;
    String lRows = "l_rows" + sfx;
    String lWritten = "l_written" + sfx;
    String lMeta = "l_meta" + sfx;
    String lStamped = "l_stamped" + sfx;
    String lId = "l_id" + sfx;
    String lVersion = "l_version" + sfx;
    String lState = "l_state" + sfx;
    // the locals: the row's version and state, and the key as the data row holds it
    StringBuilder declare =
        new StringBuilder(lRows + " NUMBER := 0; " + lWritten + " NUMBER := 0;");
    String metaTable = inline ? n.data : n.meta;
    declare
        .append(inline ? "" : " " + lMeta + " NUMBER := 0; " + lStamped + " NUMBER := 0;")
        .append(" ")
        .append(lId)
        .append(" ")
        .append(metaTable)
        .append(".")
        .append(n.txId)
        .append("%TYPE; ")
        .append(lVersion)
        .append(" ")
        .append(metaTable)
        .append(".")
        .append(n.txVersion)
        .append("%TYPE; ")
        .append(lState)
        .append(" ")
        .append(metaTable)
        .append(".")
        .append(n.txState)
        .append("%TYPE;");
    List<String> keyLocals = new ArrayList<>();
    List<String> metaLocals = new ArrayList<>();
    List<String> keyExprs = new ArrayList<>();
    for (int i = 0; i < n.keyColumns.size(); i++) {
      String k = engine.quote(n.keyColumns.get(i));
      String lk = "l_k" + i + sfx;
      keyLocals.add(lk);
      declare
          .append(" ")
          .append(lk)
          .append(" ")
          .append(n.data)
          .append(".")
          .append(k)
          .append("%TYPE;");
      if (!inline) {
        String lm = "l_m" + i + sfx;
        metaLocals.add(lm);
        declare
            .append(" ")
            .append(lm)
            .append(" ")
            .append(n.meta)
            .append(".")
            .append(k)
            .append("%TYPE;");
      }
      keyExprs.add(n.keyExpr(lk, n.keyColumns.get(i)));
    }
    String x = exposed;
    StringBuilder block = new StringBuilder("BEGIN SELECT 1, ");
    String committed;
    if (inline) {
      block
          .append(x)
          .append(".")
          .append(n.txId)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txVersion)
          .append(", ")
          .append(x)
          .append(".")
          .append(n.txState)
          .append(", ")
          .append(n.columns(x))
          .append(" INTO ")
          .append(lRows)
          .append(", ")
          .append(lId)
          .append(", ")
          .append(lVersion)
          .append(", ")
          .append(lState)
          .append(", ")
          .append(String.join(", ", keyLocals))
          .append(" FROM ")
          .append(n.data)
          .append(" ")
          .append(x)
          .append(" WHERE ")
          .append(predicate);
      committed = lRows + " = 1 AND COALESCE(" + lState + ", 3) = 3";
    } else {
      List<String> picked = new ArrayList<>();
      for (String k : n.keyColumns) {
        picked.add(x + "." + engine.quote(k) + " AS " + engine.quote(k));
      }
      block
          .append("CASE WHEN m.")
          .append(engine.quote(n.keyColumns.get(0)))
          .append(" IS NULL THEN 0 ELSE 1 END, m.")
          .append(n.txId)
          .append(", m.")
          .append(n.txVersion)
          .append(", m.")
          .append(n.txState)
          .append(", ")
          .append(n.columns("d"))
          .append(", ")
          .append(n.columns("m"))
          .append(" INTO ")
          .append(lRows)
          .append(", ")
          .append(lMeta)
          .append(", ")
          .append(lId)
          .append(", ")
          .append(lVersion)
          .append(", ")
          .append(lState)
          .append(", ")
          .append(String.join(", ", keyLocals))
          .append(", ")
          .append(String.join(", ", metaLocals))
          .append(" FROM (SELECT ")
          .append(String.join(", ", picked))
          .append(" FROM ")
          .append(n.data)
          .append(" ")
          .append(x)
          .append(" WHERE ")
          .append(predicate)
          .append(") d LEFT JOIN ")
          .append(n.meta)
          .append(" m ON ")
          .append(n.keyJoin("m", "d"));
      committed = lRows + " = 1 AND " + lMeta + " = 1 AND COALESCE(" + lState + ", 3) = 3";
    }
    block
        .append("; EXCEPTION WHEN NO_DATA_FOUND THEN NULL; END; IF ")
        .append(committed)
        .append(" THEN ");
    // the user's DML on the row read: in the inline layout with the stamp in its SET list and the
    // CAS in its WHERE clause, steps 2 and 3 in one row
    String cas =
        "COALESCE("
            + x
            + "."
            + n.txState
            + ", 3) = 3 AND DECODE("
            + x
            + "."
            + n.txId
            + ", "
            + lId
            + ", 1, 0) = 1 AND COALESCE("
            + x
            + "."
            + n.txVersion
            + ", 0) = COALESCE("
            + lVersion
            + ", 0)";
    String text;
    if (inline) {
      if (delete) {
        text = source.substring(0, span[0]) + "(" + predicate + ") AND " + cas + tail;
      } else {
        int set = ast.afterFirst("SET");
        if (set < 0 || set > span[0]) {
          return null;
        }
        text =
            source.substring(0, set)
                + " "
                + inlineStampSets(n)
                + ","
                + source.substring(set, span[0])
                + "("
                + predicate
                + ") AND "
                + cas
                + tail;
      }
    } else {
      text = source.substring(0, span[0]) + "(" + predicate + ")" + tail;
    }
    String dml = onDataTable(engine, ast, t, true, text, from);
    if (dml == null) {
      return null;
    }
    block.append(dml).append("; ").append(lWritten).append(" := SQL%ROWCOUNT;");
    if (!inline) {
      List<String> byMetaKey = new ArrayList<>();
      for (int i = 0; i < n.keyColumns.size(); i++) {
        byMetaKey.add(engine.quote(n.keyColumns.get(i)) + " = " + metaLocals.get(i));
      }
      String metaCas =
          String.join(" AND ", byMetaKey)
              + " AND COALESCE("
              + n.txState
              + ", 3) = 3 AND DECODE("
              + n.txId
              + ", "
              + lId
              + ", 1, 0) = 1 AND COALESCE("
              + n.txVersion
              + ", 0) = COALESCE("
              + lVersion
              + ", 0)";
      block
          .append(" IF ")
          .append(lWritten)
          .append(" = 1 THEN ")
          .append(
              delete && !imported
                  ? "DELETE FROM " + n.meta + " WHERE " + metaCas
                  : "UPDATE " + n.meta + " SET " + inlineStampSets(n) + " WHERE " + metaCas)
          .append("; ")
          .append(lStamped)
          .append(" := SQL%ROWCOUNT; END IF;");
    }
    block.append(" END IF;");
    List<String> items =
        new ArrayList<>(
            List.of(
                lRows,
                inline ? "1" : lMeta,
                lId,
                lVersion,
                lState,
                lWritten,
                inline ? lWritten : lStamped));
    items.addAll(keyExprs);
    // final: no row, a state that is no image, or written and stamped
    String stop =
        "NOT ("
            + lRows
            + " = 0 OR ("
            + (inline ? "" : lMeta + " = 1 AND ")
            + lState
            + " IS NOT NULL AND "
            + lState
            + " NOT IN (1, 2, 3)) OR ("
            + lWritten
            + " = 1"
            + (inline ? "" : " AND " + lStamped + " = 1")
            + "))";
    return new OracleBody(declare.toString(), block.toString(), items, stop);
  }

  /**
   * On Oracle at SNAPSHOT, a locking read as written with the write's gate in its WHERE clause
   * (design 4.3 "The locking read on Oracle", todo 334): the predicate parenthesized and the gate
   * added, or a WHERE clause of the gate alone before the locking clause of a statement without
   * one, which has no ORDER BY nor GROUP BY to place it before; null where it cannot be written.
   */
  static @Nullable String gatedLock(
      Engine engine, SqlAst ast, TableInfo t, Names n, int @Nullable [] span) {
    String source = ast.source();
    String gated;
    if (span != null) {
      gated =
          source.substring(0, span[0])
              + "("
              + source.substring(span[0], span[1])
              + ") AND "
              + clearGate(n)
              + source.substring(span[1]);
    } else {
      int at = ast.afterLast("FOR");
      if (at < 0 || ast.afterLast("ORDER") >= 0 || ast.afterLast("GROUP") >= 0) {
        return null;
      }
      at -= "FOR".length();
      gated = source.substring(0, at) + "WHERE " + clearGate(n) + " " + source.substring(at);
    }
    return onDataTable(engine, ast, t, true, gated, -1);
  }

  private static String clearGate(Names n) {
    if (n.engine == Engine.ORACLE) {
      // hinted to the in-flight index (design 4.2 on Oracle, todo 334)
      return "NOT EXISTS (SELECT "
          + n.inflightHint("m")
          + "1 FROM "
          + n.meta
          + " m WHERE m."
          + n.txState
          + " IN (1, 2))";
    }
    if (n.engine == Engine.POSTGRESQL) {
      return "(" + firstInFlight(n) + ") IS NULL";
    }
    return "NOT EXISTS (SELECT 1 FROM " + n.meta + " WHERE " + n.txState + " IN (1, 2))";
  }

  /**
   * On PostgreSQL, the probe of the whole in-flight index as its first row in the index's order:
   * spelled {@code EXISTS} or {@code LIMIT 1} without the order, it is planned by the estimated
   * share of rows in flight, and under default statistics (before the table's first {@code
   * ANALYZE}) or stale ones that makes a scan of the metadata table the cheaper first row; the
   * ordered first row is the partial index's under any statistics (design 4.2, todo 337).
   */
  static String firstInFlight(Names n) {
    return "SELECT 1 FROM "
        + n.meta
        + " WHERE "
        + n.txState
        + " IN (1, 2) ORDER BY "
        + n.columns("")
        + " LIMIT 1";
  }

  /**
   * The metadata rows of the keys {@code w} returns, stamped as {@link #fastKeyed} says: a created
   * table's by an UPDATE, which a DELETE removes; an imported table's by an upsert. The stamp's
   * three values, where the text has them, are its only placeholders.
   */
  private static String metaStamp(Names n, String w, boolean delete, boolean imported) {
    Engine engine = n.engine;
    int width = n.keyColumns.size();
    List<String> join = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      join.add("m." + engine.quote(n.keyColumns.get(i)) + " = w." + w + i);
    }
    if (imported) {
      // the metadata row written or stamped: a key without one, before the backfill completes
      // (4.4), a DELETE's tombstone (4.5), or the committed row at the next version
      List<String> conflict = new ArrayList<>();
      for (String k : n.keyColumns) {
        conflict.add(engine.quote(k));
      }
      return "INSERT INTO "
          + n.meta
          + " AS m ("
          + n.columns("")
          + ", "
          + n.txId
          + ", "
          + n.txState
          + ", "
          + n.txVersion
          + ", "
          + n.preparedAt
          + ", "
          + n.committedAt
          + ") SELECT "
          + Names.numbered("w", w, width)
          + ", ?, 3, 1, ?, ? FROM "
          + w
          + " w ON CONFLICT ("
          + String.join(", ", conflict)
          + ") DO UPDATE SET "
          + n.txId
          + " = EXCLUDED."
          + n.txId
          + ", "
          + n.preparedAt
          + " = EXCLUDED."
          + n.preparedAt
          + ", "
          + n.committedAt
          + " = EXCLUDED."
          + n.committedAt
          + ", "
          + n.txState
          + " = 3, "
          + n.txVersion
          + " = CASE WHEN m."
          + n.txId
          + " IS NULL THEN 1 ELSE COALESCE(m."
          + n.txVersion
          + ", 0) + 1 END WHERE COALESCE(m."
          + n.txState
          + ", 3) = 3";
    }
    if (delete) {
      return "DELETE FROM "
          + n.meta
          + " m USING "
          + w
          + " w WHERE "
          + String.join(" AND ", join)
          + " AND COALESCE(m."
          + n.txState
          + ", 3) = 3";
    }
    return "UPDATE "
        + n.meta
        + " m SET "
        + inlineStampSets(n)
        + " FROM "
        + w
        + " w WHERE "
        + String.join(" AND ", join)
        + " AND COALESCE(m."
        + n.txState
        + ", 3) = 3";
  }

  /**
   * {@link #fastKeyed} over the entries of a batch (todo 329), each a row of {@code
   * scalardb_native_e}, which the DML is joined to, each placeholder its entry's column, and whose
   * number it returns for each row it wrote: an entry whose number is not returned wrote nothing,
   * or shares its key with another, which the entries one by one tell apart. Null when the text
   * cannot be rewritten.
   */
  private static @Nullable Merge fastMerged(
      Names n,
      SqlAst ast,
      TableInfo t,
      int[] span,
      boolean delete,
      boolean imported,
      int from,
      int end,
      boolean gated) {
    List<int[]> holes = ast.placeholderSpans();
    if (holes == null || holes.isEmpty()) {
      return null;
    }
    String source = ast.source();
    String e = "scalardb_native_e";
    String w = "scalardb_native_w";
    IntFunction<String> p = j -> e + ".scalardb_native_p" + j;
    boolean inline = t.layout() == Layout.INLINE;
    String where =
        " WHERE ("
            + filled(source, span[0], span[1], holes, p)
            + ")"
            + (gated ? " AND " + clearGate(n) : "")
            + filled(source, span[1], end, holes, p);
    String joined = (delete ? " USING " : " FROM ") + e + where;
    String text;
    if (!delete && inline) {
      int set = ast.afterFirst("SET");
      if (set < 0 || set > from) {
        return null;
      }
      text =
          filled(source, 0, set, holes, p)
              + " "
              + inlineStampSets(n)
              + ","
              + filled(source, set, from, holes, p).stripTrailing()
              + joined;
    } else {
      text = filled(source, 0, from, holes, p).stripTrailing() + joined;
    }
    String returning =
        " RETURNING "
            + e
            + ".scalardb_native_n"
            + (inline ? "" : " AS scalardb_native_n, " + n.aliased("", w));
    String dml = onDataTable(n.engine, ast, t, true, text + returning, from);
    if (dml == null) {
      return null;
    }
    StringBuilder head = new StringBuilder("WITH ").append(e).append(" (scalardb_native_n");
    for (int j = 1; j <= holes.size(); j++) {
      head.append(", scalardb_native_p").append(j);
    }
    head.append(") AS (VALUES ");
    String row = ", ?".repeat(holes.size()) + ")";
    String rest =
        inline
            ? ") " + dml
            : "), "
                + w
                + " AS ("
                + dml
                + "), scalardb_native_s AS ("
                + metaStamp(n, w, delete, imported)
                + " RETURNING 1) SELECT w.scalardb_native_n, (SELECT count(*) FROM"
                + " scalardb_native_s) FROM "
                + w
                + " w";
    IntFunction<String> sql =
        k -> {
          StringBuilder out = new StringBuilder(head);
          for (int i = 1; i <= k; i++) {
            out.append(i == 1 ? "(" : ", (").append(i).append(row);
          }
          return out.append(rest).toString();
        };
    return new Merge(sql, holes.size(), false, inline ? !delete : imported || !delete);
  }

  /**
   * A write at SNAPSHOT and SERIALIZABLE on PostgreSQL as the vendor's statement with its stamp
   * (todo 329): every statement of the transaction reads its one snapshot, so where the snapshot
   * holds no row in flight of the table at all, every row the predicate matches is committed, a row
   * changed after the snapshot fails the write with PostgreSQL's 40001, and the anchor read in the
   * statement is the row's own version, which the stamp raises. With {@code gated}, the predicate
   * also requires that no row of the table is in flight, one probe of the in-flight index, for a
   * transaction that has not found the table clear yet; a closed gate writes nothing, which the
   * fused statement then tells apart from a predicate matching nothing.
   *
   * <p>In the inline layout the stamp is in the SET list, ahead of the user's items, so its values
   * bind first; a DELETE takes the row and its metadata at once and binds no stamp. In the
   * decoupled layout the user's DML returns the keys it wrote and the metadata rows are stamped
   * from them after it, the stamp's values bound last: an UPDATE of the committed row on a created
   * table, which a DELETE removes; on an imported table an upsert, which also writes the row a key
   * had none of yet (4.4), the tombstone of a DELETE among them (4.5). The statement returns how
   * many rows the DML wrote and how many were stamped. Null when the text cannot be rewritten.
   */
  private static @Nullable Step fastKeyed(
      Names n,
      SqlAst ast,
      TableInfo t,
      int @Nullable [] span,
      List<Integer> all,
      boolean delete,
      boolean imported,
      int from,
      int end,
      boolean gated) {
    Engine engine = n.engine;
    String source = ast.source();
    String gate = clearGate(n);
    // the predicate, gated: in place of the user's WHERE clause, or appended before the end
    String where =
        span == null
            ? gated ? " WHERE " + gate : ""
            : " WHERE ("
                + source.substring(span[0], span[1])
                + ")"
                + (gated ? " AND " + gate : "")
                + source.substring(span[1], end);
    String head = source.substring(0, from).stripTrailing();
    boolean inline = t.layout() == Layout.INLINE;
    String text;
    if (!delete && inline) {
      int set = ast.afterFirst("SET");
      if (set < 0 || set > from) {
        return null;
      }
      text =
          source.substring(0, set)
              + " "
              + inlineStampSets(n)
              + ","
              + source.substring(set, from).stripTrailing()
              + where
              + source.substring(end);
    } else {
      // a trailing comment left out of the decoupled form, which would run on over the RETURNING
      text = head + where + (inline ? source.substring(end) : "");
    }
    String dml = onDataTable(engine, ast, t, true, text, from);
    if (dml == null) {
      return null;
    }
    if (inline) {
      return new Step(dml, all, Set.of(), !delete);
    }
    if (engine == Engine.ORACLE) {
      return oracleClearTableBlock(n, dml, all, delete, imported);
    }
    String w = "scalardb_native_w";
    String sql =
        "WITH "
            + w
            + " AS ("
            + dml
            + " RETURNING "
            + n.aliased("", w)
            + "), scalardb_native_s AS ("
            + metaStamp(n, w, delete, imported)
            + " RETURNING 1) SELECT (SELECT count(*) FROM "
            + w
            + "), (SELECT count(*) FROM scalardb_native_s)";
    return new Step(sql, all);
  }

  /**
   * A write by the table's key alone as a slimmer statement than {@link #fusedKeyed} (todo 329):
   * the key's metadata row read by the key itself; the DML gated on that row being committed, which
   * a row in flight, a key without a metadata row and one whose committed image is a before image
   * all fail, as at every level does a row in flight whose after image alone the key matches, the
   * same key; and the CAS on its tx_id and version. What a statement costs around its execution
   * grows with its plan: this one's is a third of the fused one's. Its one row is the metadata
   * row's key, tx_id, version and state, how many rows the DML wrote and the CAS stamped, and the
   * key it wrote, as the data table spells it. Null when the text cannot be rewritten.
   */
  private static @Nullable Step slimKeyed(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      List<Integer> inWhere,
      List<Integer> all,
      boolean delete,
      boolean imported,
      int from,
      int end) {
    String source = ast.source();
    int width = n.keyColumns.size();
    String m = "scalardb_native_m";
    String w = "scalardb_native_w";
    String dml =
        onDataTable(
            n.engine,
            ast,
            t,
            true,
            source.substring(0, span[0])
                + "("
                + source.substring(span[0], span[1])
                + ") AND (SELECT scalardb_native_go FROM scalardb_native_gate)"
                + source.substring(span[1], end),
            from);
    if (dml == null) {
      return null;
    }
    boolean remove = delete && !imported;
    StringBuilder sql =
        new StringBuilder("WITH scalardb_native_keys (")
            .append(Names.numbered("", m, width))
            .append(
                ", scalardb_native_id, scalardb_native_version, scalardb_native_state) AS (SELECT ")
            .append(n.readColumns(exposed))
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txId)
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txVersion)
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txState)
            .append(" FROM ")
            .append(n.meta)
            .append(" ")
            .append(exposed)
            .append(" WHERE ")
            .append(source, span[0], span[1])
            .append("), scalardb_native_gate (scalardb_native_go) AS (SELECT EXISTS (SELECT 1 FROM")
            .append(" scalardb_native_keys WHERE COALESCE(scalardb_native_state, 3) = 3)),")
            .append(" scalardb_native_written AS (")
            .append(dml)
            .append(" RETURNING ")
            .append(n.aliased("", w))
            .append(")")
            .append(casSteps(n, Names.numbered("k", m, width), "1", remove));
    sql.append(" SELECT ")
        .append(Names.numbered("k", m, width))
        .append(", k.scalardb_native_id, k.scalardb_native_version, k.scalardb_native_state,")
        .append(" (SELECT count(*) FROM scalardb_native_written), (SELECT count(*) FROM")
        .append(" scalardb_native_stamped)")
        .append(remove ? " + (SELECT count(*) FROM scalardb_native_removed)" : "");
    for (int i = 0; i < width; i++) {
      sql.append(", (SELECT ").append(w).append(i).append(" FROM scalardb_native_written)");
    }
    sql.append(" FROM (SELECT 1) scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE");
    List<Integer> binds = new ArrayList<>(inWhere);
    binds.addAll(all);
    return new Step(sql.toString(), binds);
  }

  /**
   * The CAS of 4.3 step 3 folded into the clear-table statement's stamp on a decoupled table at
   * READ COMMITTED on PostgreSQL (design 4.3, todo 336): the metadata row as the stamp finds it,
   * locked and newest ({@code m}), at the tx_id and version {@code source}'s rows hold for its key,
   * which read the statement's snapshot. PostgreSQL re-evaluates an UPDATE's WHERE, and {@code ON
   * CONFLICT DO UPDATE}'s, on the row as it is after a wait, while the subquery keeps the snapshot
   * (todo 336's confirmation test 1). {@code source}: the keys CTE ({@code c}, its key columns
   * {@code scalardb_native_m}i) or the metadata table itself ({@code s}).
   */
  private static String rcCas(Names n, boolean fromKeys) {
    int width = n.keyColumns.size();
    List<String> join = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      String k = n.engine.quote(n.keyColumns.get(i));
      join.add((fromKeys ? "c.scalardb_native_m" + i : "s." + k) + " = m." + k);
    }
    String id = fromKeys ? "c.scalardb_native_id" : "s." + n.txId;
    String version = fromKeys ? "c.scalardb_native_version" : "s." + n.txVersion;
    return " AND EXISTS (SELECT 1 FROM "
        + (fromKeys ? "scalardb_native_keys c" : n.meta + " s")
        + " WHERE "
        + String.join(" AND ", join)
        + " AND "
        + id
        + " IS NOT DISTINCT FROM m."
        + n.txId
        + " AND "
        + version
        + " IS NOT DISTINCT FROM m."
        + n.txVersion
        + ")";
  }

  /**
   * The keyed write at READ COMMITTED on a decoupled table on PostgreSQL, where no wait is
   * configured (design 4.3 "The keyed write at READ COMMITTED on a decoupled table", todo 336): the
   * key's metadata row as the statement's snapshot reads it, in a CTE; the user's DML as written on
   * the data table, gated on that row's state being committed or absent, which keeps the data row
   * unwritten for a key in flight or in another state; the clear-table stamp from it ({@link
   * #metaStamp}) with the CAS of {@link #rcCas} on that CTE. Its one row: how many rows the DML
   * wrote and the stamp stamped, the snapshot's tx_id, version and state of the key (NULLs without
   * a metadata row), and the key written as the data table spells it, for the anchor. The binds:
   * the predicate's in the CTE, then the statement's, then the stamp's three where it has them.
   * Null when the text cannot be rewritten.
   */
  private static @Nullable Step clearKeyedRc(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      List<Integer> inWhere,
      List<Integer> all,
      boolean delete,
      boolean imported,
      int from,
      int end) {
    String source = ast.source();
    int width = n.keyColumns.size();
    String w = "scalardb_native_w";
    String dml =
        onDataTable(
            n.engine,
            ast,
            t,
            true,
            source.substring(0, from).stripTrailing()
                + " WHERE ("
                + source.substring(span[0], span[1])
                + ") AND NOT EXISTS (SELECT 1 FROM scalardb_native_keys WHERE"
                + " COALESCE(scalardb_native_state, 3) <> 3)"
                + source.substring(span[1], end),
            from);
    if (dml == null) {
      return null;
    }
    StringBuilder sql =
        new StringBuilder("WITH scalardb_native_keys (")
            .append(Names.numbered("", "scalardb_native_m", width))
            .append(
                ", scalardb_native_id, scalardb_native_version, scalardb_native_state) AS (SELECT ")
            .append(n.readColumns(exposed))
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txId)
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txVersion)
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txState)
            .append(" FROM ")
            .append(n.meta)
            .append(" ")
            .append(exposed)
            .append(" WHERE ")
            .append(source, span[0], span[1])
            .append("), ")
            .append(w)
            .append(" AS (")
            .append(dml)
            .append(" RETURNING ")
            .append(n.aliased("", w))
            .append("), scalardb_native_s AS (")
            .append(metaStamp(n, w, delete, imported))
            .append(rcCas(n, true))
            .append(" RETURNING 1) SELECT (SELECT count(*) FROM ")
            .append(w)
            .append("), (SELECT count(*) FROM scalardb_native_s), k.scalardb_native_id,")
            .append(" k.scalardb_native_version, k.scalardb_native_state");
    for (int i = 0; i < width; i++) {
      sql.append(", (SELECT ").append(w).append(i).append(" FROM ").append(w).append(")");
    }
    sql.append(" FROM (SELECT 1) scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE");
    List<Integer> binds = new ArrayList<>(inWhere);
    binds.addAll(all);
    return new Step(sql.toString(), binds);
  }

  /**
   * {@link #clearKeyedRc} over the entries of a batch (design 4.3, todo 336), as {@link
   * #fastMerged} sends them: each entry a row of {@code scalardb_native_e}; its key's metadata row
   * read per entry by a lateral subquery into the keys CTE; the DML joined to the entries,
   * returning each written row's entry and key; the stamp with the CAS on the CTE; and per row
   * written its entry, how many the stamp stamped in all, the snapshot's tx_id and version of its
   * key, and its key. Null when the text cannot be rewritten.
   */
  private static @Nullable Merge clearMergedRc(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      boolean delete,
      boolean imported,
      int from,
      int end) {
    List<int[]> holes = ast.placeholderSpans();
    if (holes == null || holes.isEmpty()) {
      return null;
    }
    String source = ast.source();
    int width = n.keyColumns.size();
    String e = "scalardb_native_e";
    String w = "scalardb_native_w";
    IntFunction<String> p = j -> e + ".scalardb_native_p" + j;
    String predicate = filled(source, span[0], span[1], holes, p);
    String text =
        filled(source, 0, from, holes, p).stripTrailing()
            + (delete ? " USING " : " FROM ")
            + e
            + " WHERE ("
            + predicate
            + ") AND NOT EXISTS (SELECT 1 FROM scalardb_native_keys g WHERE g.scalardb_native_n = "
            + e
            + ".scalardb_native_n AND COALESCE(g.scalardb_native_state, 3) <> 3)"
            + filled(source, span[1], end, holes, p);
    String dml =
        onDataTable(
            n.engine,
            ast,
            t,
            true,
            text
                + " RETURNING "
                + e
                + ".scalardb_native_n AS scalardb_native_n, "
                + n.aliased("", w),
            from);
    if (dml == null) {
      return null;
    }
    StringBuilder head = new StringBuilder("WITH ").append(e).append(" (scalardb_native_n");
    for (int j = 1; j <= holes.size(); j++) {
      head.append(", scalardb_native_p").append(j);
    }
    head.append(") AS (VALUES ");
    String tail =
        "), scalardb_native_keys (scalardb_native_n, "
            + Names.numbered("", "scalardb_native_m", width)
            + ", scalardb_native_id, scalardb_native_version, scalardb_native_state) AS (SELECT "
            + e
            + ".scalardb_native_n, "
            + n.readColumns("x")
            + ", x."
            + n.txId
            + ", x."
            + n.txVersion
            + ", x."
            + n.txState
            + " FROM "
            + e
            + " CROSS JOIN LATERAL (SELECT * FROM "
            + n.meta
            + " "
            + exposed
            + " WHERE "
            + predicate
            + " OFFSET 0) x), "
            + w
            + " AS ("
            + dml
            + "), scalardb_native_s AS ("
            + metaStamp(n, w, delete, imported)
            + rcCas(n, true)
            + " RETURNING 1) SELECT w.scalardb_native_n, (SELECT count(*) FROM"
            + " scalardb_native_s), k.scalardb_native_id, k.scalardb_native_version, "
            + Names.numbered("w", w, width)
            + " FROM "
            + w
            + " w LEFT JOIN scalardb_native_keys k ON k.scalardb_native_n ="
            + " w.scalardb_native_n";
    String row = ", ?".repeat(holes.size()) + ")";
    IntFunction<String> sql =
        k -> {
          StringBuilder out = new StringBuilder(head);
          for (int i = 1; i <= k; i++) {
            out.append(i == 1 ? "(" : ", (").append(i).append(row);
          }
          return out.append(tail).toString();
        };
    return new Merge(sql, holes.size(), false, imported || !delete);
  }

  /**
   * The write beyond the key at READ COMMITTED on a decoupled table on PostgreSQL, for a
   * transaction that recorded no anchor of the table and where no wait is configured (design 4.3
   * "The write beyond the key at READ COMMITTED on a decoupled table", todo 336): whether a row in
   * flight matches the predicate by its before image alone (4.2), one probe of the in-flight index;
   * the user's DML as written on the data table, returning its keys; and the clear-table stamp from
   * them with the CAS of {@link #rcCas} on the metadata table itself, read in the statement's
   * snapshot. Its one row: how many rows the DML wrote, how many the stamp stamped, and the before
   * images' match. The binds: the predicate's in the before images' check, then the statement's,
   * then the stamp's three where it has them. Null when the text cannot be rewritten.
   */
  private static @Nullable Step clearPredicateRc(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int @Nullable [] span,
      List<Integer> inWhere,
      List<Integer> all,
      boolean delete,
      boolean imported,
      int from,
      int end) {
    String source = ast.source();
    String w = "scalardb_native_w";
    String where =
        span == null
            ? ""
            : " WHERE ("
                + source.substring(span[0], span[1])
                + ")"
                + source.substring(span[1], end);
    String dml =
        onDataTable(
            n.engine, ast, t, true, source.substring(0, from).stripTrailing() + where, from);
    if (dml == null) {
      return null;
    }
    String sql =
        "WITH scalardb_native_before (scalardb_native_b) AS (SELECT EXISTS (SELECT 1 FROM "
            + SplitRead.inFlight(n.engine, t, t.dataColumns())
            + " "
            + exposed
            + (span == null ? "" : " WHERE " + source.substring(span[0], span[1]))
            + ")), "
            + w
            + " AS ("
            + dml
            + " RETURNING "
            + n.aliased("", w)
            + "), scalardb_native_s AS ("
            + metaStamp(n, w, delete, imported)
            + rcCas(n, false)
            + " RETURNING 1) SELECT (SELECT count(*) FROM "
            + w
            + "), (SELECT count(*) FROM scalardb_native_s), (SELECT scalardb_native_b FROM"
            + " scalardb_native_before)";
    List<Integer> binds = new ArrayList<>(inWhere);
    binds.addAll(all);
    return new Step(sql, binds);
  }

  /**
   * Whether every column of {@code columns} is a key column of {@code t}: a predicate a row's
   * images cannot differ on, so that a row the statement's snapshot matched still matches its
   * newest version (design 4.3, todo 336, form 3's scope, as the READ COMMITTED lock's).
   */
  private static boolean keyColumnsOnly(Engine engine, TableInfo t, List<String> columns) {
    Set<String> keys = new HashSet<>();
    for (String k : t.keyColumns()) {
      keys.add(engine.columnKey(k));
    }
    for (String c : columns) {
      if (!keys.contains(engine.columnKey(c))) {
        return false;
      }
    }
    return true;
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
    boolean remove = delete && !imported;
    sql.append(casSteps(n, Names.numbered("k", d, width), "k.scalardb_native_n", remove));
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
   * The fused write of {@link #fusedKeyed} on a table in the inline layout (todo 329), its metadata
   * in the row, so its own metadata table: the keys the predicate matches as the level's read
   * surface gives them, the committed rows with their versions and the rows in flight whose before
   * image matches, read from the table itself rather than through the view and a join back; the
   * gate, that no key is in flight (and at SNAPSHOT no after image matches), a condition of the DML
   * evaluated once; and the user's DML over the keys with the stamp in its SET list, its values
   * from a CTE joined in rather than looked up per row, the predicate applied again, so that at
   * READ COMMITTED a row that stopped matching is skipped. A row that still matches but moved since
   * the keys were read is written with version -1, its stamp missed, for the driver to abort on,
   * which undoes it; a DELETE is the conditional delete, its CAS in its WHERE. The same rows as
   * {@link #fusedKeyed}'s, each key's row joined to what the DML returned for it. Null when the
   * text cannot be rewritten.
   */
  private static @Nullable Step fusedKeyedInline(
      Names n,
      SqlAst ast,
      TableInfo t,
      Step keys,
      @Nullable Step afterImages,
      int @Nullable [] span,
      List<Integer> all,
      boolean delete,
      int from,
      int end) {
    String source = ast.source();
    int width = n.keyColumns.size();
    String d = "scalardb_native_d";
    String w = "scalardb_native_w";
    String exposed = Router.exposed(ast.tableRefs().get(0));
    String anchored =
        n.txId
            + " IS NOT DISTINCT FROM k.scalardb_native_id AND "
            + n.txVersion
            + " IS NOT DISTINCT FROM k.scalardb_native_version";
    String gate =
        "NOT EXISTS (SELECT 1 FROM scalardb_native_keys WHERE COALESCE(scalardb_native_state, 3)"
            + " IN (1, 2))"
            + (afterImages == null ? "" : " AND NOT EXISTS (SELECT 1 FROM scalardb_native_after)");
    String matched =
        (span == null ? "" : "(" + source.substring(span[0], span[1]) + ") AND ")
            + "("
            + n.columns("")
            + ") = ("
            + Names.numbered("k", d, width)
            + ") AND "
            + gate
            + " AND "
            + n.committed();
    String head = source.substring(0, from).stripTrailing();
    String rest = span == null ? source.substring(from, end) : source.substring(span[1], end);
    String text =
        delete
            ? head + " USING scalardb_native_keys k WHERE " + matched + " AND " + anchored + rest
            : head
                + ", "
                + n.txId
                + " = s.scalardb_native_i, "
                + n.preparedAt
                + " = s.scalardb_native_p, "
                + n.committedAt
                + " = s.scalardb_native_c, "
                + n.txState
                + " = 3, "
                + n.txVersion
                + " = CASE WHEN "
                + anchored
                + " THEN CASE WHEN k.scalardb_native_id IS NULL THEN 1 ELSE COALESCE("
                + n.txVersion
                + ", 0) + 1 END ELSE -1 END FROM scalardb_native_s s, scalardb_native_keys k WHERE "
                + matched
                + rest;
    String dml = onDataTable(n.engine, ast, t, true, text, from);
    if (dml == null) {
      return null;
    }
    // the keys as the read view gives them: the rows whose state is committed, as the view keeps
    // them (a state that is neither in flight nor committed is no committed image, 4.4), and the
    // rows in flight whose before image matches, which close the gate; the key twice, as its own
    // metadata row's too
    String where = span == null ? "" : " WHERE (" + source.substring(span[0], span[1]) + ")";
    String keyed = n.readColumns(exposed);
    String keysSql =
        "SELECT "
            + keyed
            + ", "
            + keyed
            + ", "
            + exposed
            + "."
            + n.txId
            + ", "
            + exposed
            + "."
            + n.txVersion
            + ", "
            + exposed
            + "."
            + n.txState
            + " FROM "
            + n.data
            + " "
            + exposed
            + where
            + (where.isEmpty() ? " WHERE " : " AND ")
            + "COALESCE("
            + exposed
            + "."
            + n.txState
            + ", 3) = 3 UNION ALL SELECT "
            + keyed
            + ", "
            + keyed
            + ", NULL, NULL, 1 FROM "
            + SplitRead.inFlight(n.engine, t, t.dataColumns())
            + " "
            + exposed
            + where;
    String keyColumns =
        Names.numbered("", d, width)
            + ", "
            + Names.numbered("", "scalardb_native_m", width)
            + ", scalardb_native_id, scalardb_native_version, scalardb_native_state";
    List<Integer> inWhere = span == null ? List.of() : keys.binds;
    List<Integer> binds = new ArrayList<>(inWhere);
    binds.addAll(inWhere);
    StringBuilder sql =
        new StringBuilder(
                "WITH scalardb_native_s (scalardb_native_i, scalardb_native_p, scalardb_native_c)"
                    + " AS (SELECT CAST(? AS text), CAST(? AS bigint), CAST(? AS bigint)),"
                    + " scalardb_native_keys (")
            .append(keyColumns)
            .append(") AS (")
            .append(keysSql)
            .append(")");
    if (afterImages != null) {
      sql.append(", scalardb_native_after AS (").append(afterImages.sql).append(")");
      binds.addAll(afterImages.binds);
    }
    binds.addAll(all);
    sql.append(", scalardb_native_written AS (")
        .append(dml)
        .append(" RETURNING ")
        .append(n.aliased("", w))
        .append(", ")
        .append(delete ? "true" : n.txVersion + " <> -1")
        .append(" AS scalardb_native_ok) SELECT k.*, w.")
        .append(w)
        .append("0 IS NOT NULL, COALESCE(w.scalardb_native_ok, false), ")
        .append(gate)
        .append(afterImages == null ? "" : ", EXISTS (SELECT 1 FROM scalardb_native_after)")
        .append(" FROM (SELECT 1) scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE")
        .append(" LEFT JOIN scalardb_native_written w ON ")
        .append(n.keyJoinNumbered("w", w, "k", d));
    return new Step(sql.toString(), binds, Set.of(), true);
  }

  /** The stamp of I5 as SET items of the row itself, its three values bound in order (inline). */
  private static String inlineStampSets(Names n) {
    return n.txId
        + " = ?, "
        + n.preparedAt
        + " = ?, "
        + n.committedAt
        + " = ?, "
        + n.txState
        + " = 3, "
        + n.txVersion
        + " = CASE WHEN "
        + n.txId
        + " IS NULL THEN 1 ELSE COALESCE("
        + n.txVersion
        + ", 0) + 1 END";
  }

  /**
   * The slim write of {@link #slimKeyed} on a table in the inline layout (todo 329), its metadata
   * in the row: the key's row read by the key, then the user's DML on it with the stamp in its SET
   * list, matched by the key read, committed and at the tx_id and version read, the CAS of 4.3 step
   * 3 in the DML itself; a DELETE is the conditional delete alone. The same one row as {@link
   * #slimKeyed}'s, written and stamped counted alike; a DELETE binds the stamp's values unused.
   * Null when the text cannot be rewritten.
   */
  private static @Nullable Step slimKeyedInline(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      List<Integer> inWhere,
      boolean delete,
      int from,
      int end) {
    String source = ast.source();
    int width = n.keyColumns.size();
    String m = "scalardb_native_m";
    String w = "scalardb_native_w";
    List<Integer> set = ast.placeholdersIn(0, from);
    if (set == null) {
      return null;
    }
    String head = source.substring(0, from).stripTrailing();
    String dml =
        onDataTable(
            n.engine,
            ast,
            t,
            true,
            (delete ? head : head + ", " + inlineStampSets(n))
                + " WHERE ("
                + n.columns("")
                + ") IN (SELECT "
                + Names.numbered("", m, width)
                + " FROM scalardb_native_keys) AND "
                + n.committed()
                + " AND "
                + n.txId
                + " IS NOT DISTINCT FROM (SELECT scalardb_native_id FROM scalardb_native_keys) AND "
                + n.txVersion
                + " IS NOT DISTINCT FROM (SELECT scalardb_native_version FROM"
                + " scalardb_native_keys)"
                + source.substring(span[1], end),
            from);
    if (dml == null) {
      return null;
    }
    StringBuilder sql =
        new StringBuilder("WITH scalardb_native_keys (")
            .append(Names.numbered("", m, width))
            .append(
                ", scalardb_native_id, scalardb_native_version, scalardb_native_state) AS (SELECT ")
            .append(n.readColumns(exposed))
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txId)
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txVersion)
            .append(", ")
            .append(exposed)
            .append(".")
            .append(n.txState)
            .append(" FROM ")
            .append(n.data)
            .append(" ")
            .append(exposed)
            .append(" WHERE ")
            .append(source, span[0], span[1])
            .append("), scalardb_native_written AS (")
            .append(dml)
            .append(" RETURNING ")
            .append(n.aliased("", w))
            .append(") SELECT ")
            .append(Names.numbered("k", m, width))
            .append(", k.scalardb_native_id, k.scalardb_native_version, k.scalardb_native_state,")
            .append(" (SELECT count(*) FROM scalardb_native_written), (SELECT count(*) FROM")
            .append(" scalardb_native_written)");
    for (int i = 0; i < width; i++) {
      sql.append(", (SELECT ").append(w).append(i).append(" FROM scalardb_native_written)");
    }
    sql.append(" FROM ")
        .append(
            delete
                ? "(SELECT CAST(? AS text), CAST(? AS bigint), CAST(? AS bigint))"
                : "(SELECT 1)")
        .append(" scalardb_native_one LEFT JOIN scalardb_native_keys k ON TRUE");
    List<Integer> binds = new ArrayList<>(inWhere);
    binds.addAll(set);
    return new Step(sql.toString(), binds);
  }

  /**
   * The merged write of {@link #mergedKeyed} on a table in the inline layout (todo 329): each
   * entry's row read by its key, the gate closed on a row not committed or two entries on one key,
   * and the user's DML joined to the entries and their rows with the stamp in its SET list, matched
   * committed and at the tx_id and version read; per entry the row {@link KeyedPlan#merged} reads,
   * {@code after} a false after-image column where the plan has that check. A DELETE binds the
   * stamp's values unused. Null when the text cannot be rewritten.
   */
  private static @Nullable Merge mergedKeyedInline(
      Names n,
      SqlAst ast,
      TableInfo t,
      String exposed,
      int[] span,
      boolean delete,
      int from,
      int end,
      boolean after) {
    List<int[]> holes = ast.placeholderSpans();
    if (holes == null || holes.isEmpty()) {
      return null;
    }
    String source = ast.source();
    String e = "scalardb_native_e";
    IntFunction<String> p = j -> e + ".scalardb_native_p" + j;
    String predicate = filled(source, span[0], span[1], holes, p);
    int width = n.keyColumns.size();
    String d = "scalardb_native_d";
    String w = "scalardb_native_w";
    String keys =
        "SELECT "
            + e
            + ".scalardb_native_n, "
            + n.readColumns("x")
            + ", "
            + n.readColumns("x")
            + ", x."
            + n.txId
            + ", x."
            + n.txVersion
            + ", x."
            + n.txState
            + " FROM "
            + e
            + " CROSS JOIN LATERAL (SELECT "
            + n.readColumns(exposed)
            + ", "
            + exposed
            + "."
            + n.txId
            + ", "
            + exposed
            + "."
            + n.txVersion
            + ", "
            + exposed
            + "."
            + n.txState
            + " FROM "
            + n.data
            + " "
            + exposed
            + " WHERE "
            + predicate
            + " OFFSET 0) x";
    String head = filled(source, 0, from, holes, p).stripTrailing();
    String joined =
        e
            + " JOIN scalardb_native_keys k ON k.scalardb_native_n = "
            + e
            + ".scalardb_native_n WHERE ("
            + n.columns("")
            + ") = ("
            + Names.numbered("k", d, width)
            + ") AND (SELECT scalardb_native_go FROM scalardb_native_gate) AND "
            + n.committed()
            + " AND "
            + n.txId
            + " IS NOT DISTINCT FROM k.scalardb_native_id AND "
            + n.txVersion
            + " IS NOT DISTINCT FROM k.scalardb_native_version";
    String dml =
        onDataTable(
            n.engine,
            ast,
            t,
            true,
            (delete ? head + " USING " : head + ", " + inlineStampSets(n) + " FROM ")
                + joined
                + filled(source, span[1], end, holes, p),
            from);
    if (dml == null) {
      return null;
    }
    StringBuilder top = new StringBuilder("WITH ").append(e).append(" (scalardb_native_n");
    for (int j = 1; j <= holes.size(); j++) {
      top.append(", scalardb_native_p").append(j);
    }
    top.append(") AS (VALUES ");
    String rest =
        "), scalardb_native_keys (scalardb_native_n, "
            + Names.numbered("", d, width)
            + ", "
            + Names.numbered("", "scalardb_native_m", width)
            + ", scalardb_native_id, scalardb_native_version, scalardb_native_state) AS ("
            + keys
            + "), scalardb_native_gate (scalardb_native_go) AS (SELECT NOT EXISTS (SELECT 1 FROM"
            + " scalardb_native_keys WHERE COALESCE(scalardb_native_state, 3) <> 3) AND NOT EXISTS"
            + " (SELECT 1 FROM scalardb_native_keys GROUP BY "
            + Names.numbered("", d, width)
            + " HAVING count(*) > 1)), scalardb_native_written AS ("
            + dml
            + " RETURNING "
            + e
            + ".scalardb_native_n AS scalardb_native_wn, "
            + n.aliased("", w)
            + ") SELECT "
            + e
            + ".scalardb_native_n, "
            + Names.numbered("k", d, width)
            + ", "
            + Names.numbered("k", "scalardb_native_m", width)
            + ", k.scalardb_native_id, k.scalardb_native_version, k.scalardb_native_state, EXISTS"
            + " (SELECT 1 FROM scalardb_native_written w WHERE w.scalardb_native_wn = "
            + e
            + ".scalardb_native_n), EXISTS (SELECT 1 FROM scalardb_native_written w WHERE"
            + " w.scalardb_native_wn = "
            + e
            + ".scalardb_native_n), (SELECT scalardb_native_go FROM scalardb_native_gate)"
            + (after ? ", false" : "")
            + " FROM "
            + e
            + " LEFT JOIN scalardb_native_keys k ON k.scalardb_native_n = "
            + e
            + ".scalardb_native_n"
            + (delete
                ? " CROSS JOIN (SELECT CAST(? AS text), CAST(? AS bigint), CAST(? AS bigint))"
                    + " scalardb_native_s"
                : "")
            + " ORDER BY 1";
    String row = ", ?".repeat(holes.size());
    return new Merge(
        k -> {
          StringBuilder out = new StringBuilder(top);
          for (int i = 1; i <= k; i++) {
            out.append(i == 1 ? "(" : ", (").append(i).append(row).append(')');
          }
          return out.append(rest).toString();
        },
        holes.size());
  }

  /**
   * The INSERT of one row on a table in the inline layout as one statement (todo 329): the stamp's
   * columns added to the statement's column list and their values to its row, the three bound last,
   * after the row's own; and the same INSERT over a batch's entries, the stamp's values bound first
   * from a CTE each row reads. Null for any other INSERT, or text that cannot be rewritten.
   */
  private static @Nullable InlineInsert inlineInsert(
      Names n, SqlAst ast, TableInfo t, @Nullable Expression row, int end, boolean savepoint) {
    return inlineInsert(n, ast, t, row, end, savepoint, true);
  }

  /**
   * As {@link #inlineInsert(Names, SqlAst, TableInfo, Expression, int, boolean)}; without {@code
   * returns}, the one statement alone, no keys returned and no merged text (Oracle, todo 334).
   */
  private static @Nullable InlineInsert inlineInsert(
      Names n,
      SqlAst ast,
      TableInfo t,
      @Nullable Expression row,
      int end,
      boolean savepoint,
      boolean returns) {
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
    int open = first[0] - 1;
    while (open >= 0 && Character.isWhitespace(source.charAt(open))) {
      open--;
    }
    int close = last[1];
    while (close < source.length() && Character.isWhitespace(source.charAt(close))) {
      close++;
    }
    int values = open < 0 ? -1 : ast.startOfLastBefore("VALUES", open);
    int columns = values - 1;
    while (columns >= 0 && Character.isWhitespace(source.charAt(columns))) {
      columns--;
    }
    List<Integer> inside =
        open < 0 || close >= source.length() ? null : ast.placeholdersIn(open, close + 1);
    if (open < 0
        || close >= source.length()
        || source.charAt(open) != '('
        || source.charAt(close) != ')'
        || columns < 0
        || source.charAt(columns) != ')'
        || inside == null
        || inside.size() != ast.parameterCount()
        || !source.substring(close + 1, end).isBlank()) {
      return null;
    }
    String metadata =
        ", "
            + n.txId
            + ", "
            + n.txState
            + ", "
            + n.txVersion
            + ", "
            + n.preparedAt
            + ", "
            + n.committedAt;
    String returned = returns ? " RETURNING " + n.aliased("", "scalardb_native_k") : "";
    String one =
        onDataTable(
            n.engine,
            ast,
            t,
            true,
            source.substring(0, columns)
                + metadata
                + source.substring(columns, close)
                + ", ?, 3, 1, ?, ?"
                + source.substring(close, end)
                + returned,
            -1);
    if (!returns) {
      return one == null ? null : new InlineInsert(one, null);
    }
    // each row reads the stamp from the CTE before it, so that the rows bind only their own
    String upToRows = source.substring(0, columns) + metadata + source.substring(columns, open);
    String stampedRow =
        source.substring(open, close)
            + ", (SELECT scalardb_native_i FROM scalardb_native_s), 3, 1, (SELECT scalardb_native_p"
            + " FROM scalardb_native_s), (SELECT scalardb_native_c FROM scalardb_native_s))";
    IntFunction<@Nullable String> many =
        k ->
            onDataTable(
                n.engine,
                ast,
                t,
                true,
                upToRows + stampedRow + (", " + stampedRow).repeat(k - 1) + returned,
                -1);
    if (one == null || many.apply(2) == null) {
      return null;
    }
    String counted =
        ") SELECT scalardb_native_inserted.*, (SELECT COUNT(*) FROM scalardb_native_inserted) FROM"
            + " scalardb_native_inserted";
    String single = "WITH scalardb_native_inserted AS (" + one + counted;
    return new InlineInsert(
        savepoint
            ? "SAVEPOINT " + SAVEPOINT + "; " + single + "; RELEASE SAVEPOINT " + SAVEPOINT
            : single,
        new Merge(
            k ->
                "WITH scalardb_native_s (scalardb_native_i, scalardb_native_p, scalardb_native_c) AS"
                    + " (SELECT CAST(? AS text), CAST(? AS bigint), CAST(? AS bigint)),"
                    + " scalardb_native_inserted AS ("
                    + Objects.requireNonNull(many.apply(k))
                    + counted,
            inside.size(),
            true));
  }

  /** {@link #inlineInsert}'s statement for one entry, and for a batch's entries. */
  private static final class InlineInsert {
    final String one;
    final @Nullable Merge merge;

    InlineInsert(String one, @Nullable Merge merge) {
      this.one = one;
      this.merge = merge;
    }
  }

  /**
   * The CAS of 4.3 step 3 as CTEs over {@code scalardb_native_keys k}, for the keys the DML wrote:
   * each one's metadata row stamped at the version read, a NULL tx_id at version 1 (4.4), as {@code
   * scalardb_native_stamped}; a DELETE of a created table removes the row instead but for a NULL
   * tx_id, as {@code scalardb_native_removed}, an imported table's keeping a tombstone (4.5).
   * {@code read}: k's key columns the DML's rows are matched on; {@code returning}: what each CTE
   * returns. One text for the fused, merged and slim statements.
   */
  private static String casSteps(Names n, String read, String returning, boolean remove) {
    int width = n.keyColumns.size();
    List<String> metaKey = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      metaKey.add(n.engine.quote(n.keyColumns.get(i)) + " = k.scalardb_native_m" + i);
    }
    String cas =
        String.join(" AND ", metaKey)
            + " AND ("
            + read
            + ") IN (SELECT "
            + Names.numbered("", "scalardb_native_w", width)
            + " FROM scalardb_native_written) AND "
            + n.committed()
            + " AND "
            + n.txId
            + " IS NOT DISTINCT FROM k.scalardb_native_id";
    String stamp =
        n.stamp(
            "CASE WHEN k.scalardb_native_id IS NULL THEN 1 ELSE COALESCE("
                + n.txVersion
                + ", 0) + 1 END");
    StringBuilder out =
        new StringBuilder(", scalardb_native_stamped AS (")
            .append(stamp, 0, stamp.length() - "WHERE ".length())
            .append("FROM scalardb_native_keys k WHERE ")
            .append(cas)
            .append(" AND (k.scalardb_native_id IS NULL OR ")
            .append(n.txVersion)
            .append(" IS NOT DISTINCT FROM k.scalardb_native_version)")
            .append(remove ? " AND k.scalardb_native_id IS NULL" : "")
            .append(" RETURNING ")
            .append(returning)
            .append(")");
    if (remove) {
      out.append(", scalardb_native_removed AS (DELETE FROM ")
          .append(n.meta)
          .append(" USING scalardb_native_keys k WHERE ")
          .append(cas)
          .append(" AND k.scalardb_native_id IS NOT NULL AND ")
          .append(n.txVersion)
          .append(" IS NOT DISTINCT FROM k.scalardb_native_version RETURNING ")
          .append(returning)
          .append(")");
    }
    return out.toString();
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
    // a DELETE of a created table removes the metadata row, but for a NULL tx_id, which is
    // stamped and kept (4.4); an imported table's keeps a tombstone (4.5)
    boolean remove = delete && !imported;
    sql.append(casSteps(n, Names.numbered("k", d, width), Names.numbered("k", d, width), remove));
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
    // on PostgreSQL the inline layout's INSERT of one row carries its own stamp (todo 329)
    InlineInsert inline =
        engine == Engine.POSTGRESQL && n.inline && returning == null
            ? inlineInsert(n, ast, t, shape.insertRow(), end, inFlight != null)
            : null;
    // on Oracle too, as the one statement it is; and a decoupled table's INSERT of one row with
    // its metadata row as one block (todo 334)
    Step oracleInline = null;
    Block oracleBlock = null;
    Step oracleInsertAll = null;
    if (engine == Engine.ORACLE && returning == null && shape.insertRow() != null) {
      if (n.inline) {
        InlineInsert one = inlineInsert(n, ast, t, shape.insertRow(), end, false, false);
        // the stamp's three values after the row's own, where the statement names them
        oracleInline = one == null ? null : new Step(one.one, all, Set.of(), all.size());
      } else {
        oracleBlock = oracleInsertBlock(n, ast, t, all, end, oracleKeyTypes(tableColumns, n));
        // a key the statement gives, not one the first INTO would generate for itself alone
        oracleInsertAll =
            oracleBlock == null
                    || rows == null
                    || rows.size() != 1
                    || type(engine, tableColumns, t.keyColumns().get(0)).endsWith(" generated")
                ? null
                : oracleInsertAll(
                    n, ast, t, shape.insertRow(), rows.get(0), all, end, tableColumns);
      }
    }
    return new InsertPlan(
        n,
        inline != null ? inline.merge : merge,
        new Step(insert, all),
        inline != null
            ? new Step(inline.one, all)
            : fused == null ? null : new Step(fusedInsert(n, fused, inFlight != null), all),
        readBack,
        inFlight,
        committed,
        repeats,
        returning,
        Router.exposed(ref),
        type(engine, tableColumns, t.keyColumns().get(0)).endsWith(" generated"),
        oracleBlock,
        oracleInline,
        oracleBlock == null
            ? null
            : oracleInsertEntries(n, ast, t, all, end, oracleKeyTypes(tableColumns, n)),
        oracleInsertAll,
        snapshot && oracleInsertAll != null && rows != null
            ? Bulk.insert(n, ast, t, rows.get(0), end, tableColumns)
            : null);
  }

  /**
   * The decoupled INSERT of one row on Oracle as one statement (design 4.3 "The decoupled INSERT as
   * one statement", todo 334): {@code INSERT ALL} into the data table as written and into the
   * metadata table, its key the row's own values again cast to the key columns' types, then the
   * stamp, {@code SELECT 1 FROM dual}. The binds are the statement's, the key's again, the stamp's
   * three after them. Null when the row is not the statement's last text, or not every placeholder
   * is in it.
   */
  private static @Nullable Step oracleInsertAll(
      Names n,
      SqlAst ast,
      TableInfo t,
      @Nullable Expression row,
      List<Expression> keyValues,
      List<Integer> all,
      int end,
      @Nullable Map<String, String> tableColumns) {
    if (!(row instanceof ParenthesedExpressionList)
        || ((ParenthesedExpressionList<?>) row).isEmpty()) {
      return null;
    }
    ParenthesedExpressionList<?> items = (ParenthesedExpressionList<?>) row;
    String source = ast.source();
    int[] first = ast.span((Expression) items.get(0));
    int[] last = ast.span((Expression) items.get(items.size() - 1));
    int insert = ast.afterFirst("INSERT");
    if (first == null || last == null || insert < 0) {
      return null;
    }
    int close = last[1];
    while (close < end && Character.isWhitespace(source.charAt(close))) {
      close++;
    }
    List<Integer> inside = ast.placeholdersIn(first[0], last[1]);
    if (close >= end
        || source.charAt(close) != ')'
        || !source.substring(close + 1, end).isBlank()
        || inside == null
        || inside.size() != ast.parameterCount()) {
      return null;
    }
    List<String> keys = new ArrayList<>();
    List<Integer> binds = new ArrayList<>(all);
    for (int i = 0; i < keyValues.size(); i++) {
      int[] span = ast.span(keyValues.get(i));
      List<Integer> in = span == null ? null : ast.placeholdersIn(span[0], span[1]);
      if (span == null || in == null) {
        return null;
      }
      // as the data row holds it, which the block's RETURNING gives the metadata row: a CHAR key
      // padded, a NUMBER(p) key rounded; a column of a type unknown here keeps the block
      String full = type(n.engine, tableColumns, t.keyColumns().get(i)).replace(" generated", "");
      if (full.isEmpty()) {
        return null;
      }
      keys.add("CAST(" + source.substring(span[0], span[1]) + " AS " + full + ")");
      binds.addAll(in);
    }
    String data = onDataTable(n.engine, ast, t, false, source.substring(0, end), -1);
    if (data == null) {
      return null;
    }
    // the data table's INSERT keeps the offsets before its table: ALL after its first keyword
    String sql =
        data.substring(0, insert)
            + " ALL"
            + data.substring(insert)
            + " INTO "
            + n.meta
            + " ("
            + n.columns("")
            + ", "
            + n.txId
            + ", "
            + n.txState
            + ", "
            + n.txVersion
            + ", "
            + n.preparedAt
            + ", "
            + n.committedAt
            + ") VALUES ("
            + String.join(", ", keys)
            + ", ?, 3, 1, ?, ?) SELECT 1 FROM dual";
    return new Step(sql, binds, Set.of(), binds.size());
  }

  /**
   * The decoupled INSERT on Oracle as one block (design 4.3 "An INSERT on Oracle", todo 334): the
   * user's INSERT returning its key into locals, an IDENTITY key's included; the metadata row under
   * {@code IGNORE_ROW_ON_DUPKEY_INDEX}; and the row that hint skipped taken over, the stamp under
   * the deemed-committed condition. The stamp's three values are the first binds, the cursor the
   * last; its row is {@code meta, stamped, k...}. Null when the text cannot be rewritten.
   */
  private static @Nullable Block oracleInsertBlock(
      Names n,
      SqlAst ast,
      TableInfo t,
      List<Integer> all,
      int end,
      @Nullable List<Integer> keyTypes) {
    OracleBody b = insertBody(n, ast, t, end, "");
    return b == null ? null : new Block(b, all, 0, INSERT_ROW_TYPES, keyTypes, n.table);
  }

  /** The INSERT block over a batch's entries (design 4.4), null where it cannot be built. */
  private static @Nullable Entries oracleInsertEntries(
      Names n,
      SqlAst ast,
      TableInfo t,
      List<Integer> all,
      int end,
      @Nullable List<Integer> keyTypes) {
    if (insertBody(n, ast, t, end, "_1") == null) {
      return null;
    }
    return new Entries(
        i -> Objects.requireNonNull(insertBody(n, ast, t, end, "_" + i)),
        all,
        0,
        INSERT_ROW_TYPES,
        keyTypes,
        n.table);
  }

  /**
   * The INSERT block's body, its locals suffixed by {@code sfx}; see {@link #oracleInsertBlock}.
   */
  private static @Nullable OracleBody insertBody(
      Names n, SqlAst ast, TableInfo t, int end, String sfx) {
    Engine engine = n.engine;
    String insert = onDataTable(engine, ast, t, false, ast.source().substring(0, end), -1);
    if (insert == null) {
      return null;
    }
    String lI = "l_i" + sfx;
    String lP = "l_p" + sfx;
    String lC = "l_c" + sfx;
    String lMeta = "l_meta" + sfx;
    String lStamped = "l_stamped" + sfx;
    StringBuilder declare =
        new StringBuilder(lI)
            .append(" ")
            .append(n.meta)
            .append(".")
            .append(n.txId)
            .append("%TYPE; ")
            .append(lP)
            .append(" ")
            .append(n.meta)
            .append(".")
            .append(n.preparedAt)
            .append("%TYPE; ")
            .append(lC)
            .append(" ")
            .append(n.meta)
            .append(".")
            .append(n.committedAt)
            .append("%TYPE;");
    List<String> locals = new ArrayList<>();
    List<String> byKey = new ArrayList<>();
    List<String> keyExprs = new ArrayList<>();
    for (int i = 0; i < n.keyColumns.size(); i++) {
      String k = engine.quote(n.keyColumns.get(i));
      String lk = "l_k" + i + sfx;
      declare
          .append(" ")
          .append(lk)
          .append(" ")
          .append(n.data)
          .append(".")
          .append(k)
          .append("%TYPE;");
      locals.add(lk);
      byKey.add(k + " = " + lk);
      keyExprs.add(n.keyExpr(lk, n.keyColumns.get(i)));
    }
    declare
        .append(" ")
        .append(lMeta)
        .append(" NUMBER := 0; ")
        .append(lStamped)
        .append(" NUMBER := 0;");
    String body =
        lI
            + " := ?; "
            + lP
            + " := ?; "
            + lC
            + " := ?; "
            + insert
            + " RETURNING "
            + n.columns("")
            + " INTO "
            + String.join(", ", locals)
            + "; INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(x ("
            + n.columns("")
            + ")) */ INTO "
            + n.meta
            + " x ("
            + n.columns("")
            + ", "
            + n.txId
            + ", "
            + n.txState
            + ", "
            + n.txVersion
            + ", "
            + n.preparedAt
            + ", "
            + n.committedAt
            + ") VALUES ("
            + String.join(", ", locals)
            + ", "
            + lI
            + ", 3, 1, "
            + lP
            + ", "
            + lC
            + "); "
            + lMeta
            + " := SQL%ROWCOUNT; IF "
            + lMeta
            + " = 0 THEN UPDATE "
            + n.meta
            + " SET "
            + n.txId
            + " = "
            + lI
            + ", "
            + n.preparedAt
            + " = "
            + lP
            + ", "
            + n.committedAt
            + " = "
            + lC
            + ", "
            + n.txState
            + " = 3, "
            + n.txVersion
            + " = CASE WHEN "
            + n.txId
            + " IS NULL THEN 1 ELSE COALESCE("
            + n.txVersion
            + ", 0) + 1 END WHERE "
            + String.join(" AND ", byKey)
            + " AND COALESCE("
            + n.txState
            + ", 3) = 3; "
            + lStamped
            + " := SQL%ROWCOUNT; END IF;";
    List<String> items = new ArrayList<>(List.of(lMeta, lStamped));
    items.addAll(keyExprs);
    return new OracleBody(
        declare.toString(), body, items, "NOT (" + lMeta + " = 1 OR " + lStamped + " = 1)");
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
  static @Nullable String onDataTable(
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

  /**
   * The table of each Oracle constraint an INSERT ALL has met, quoted, by {@code OWNER.NAME}: a
   * constraint's name stays its table's while the table stands (todo 334).
   */
  private static final Map<String, String> CONSTRAINT_TABLES = new ConcurrentHashMap<>();

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
   * left set, which otherwise stays to the next such text or the transaction's end; {@code sent}
   * runs as the text is sent, its binds taken, from when it may set the savepoint.
   */
  static long @Nullable [] applyTogether(
      Connection vendor, List<Plan> plans, List<Context> contexts, boolean release, Runnable sent)
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
        if (step.stampFirst) {
          at = runs.get(i).stamp(ps, at);
        }
        for (int b : step.binds) {
          contexts.get(i).bind(ps, at++, b, step.floatKeys.contains(b));
        }
        if (!step.stampFirst) {
          at = runs.get(i).stamp(ps, at);
        }
      }
      try {
        sent.run(); // the savepoint is set from here on, whatever follows
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

  /** Records a batch entry's count as it becomes final, in order. */
  interface Finalized {
    void record(int entry, long count) throws SQLException;
  }

  /**
   * Applies consecutive entries of a batch of one write as its block of entries on Oracle (design
   * 4.4, todo 334): the entries' outcomes in order, each recorded as it is final, until the first
   * whose outcome is the single form's to make. Returns how many were finalized, 0 when the first
   * entry is that one, which the caller then runs alone; -1 when the block failed with an error of
   * its own, after which nothing of it remains and the caller runs the entries one by one; an abort
   * an entry's row says is thrown, the entries before it recorded.
   */
  static int applyOracleEntries(
      Connection vendor, Plan plan, List<Context> contexts, Finalized finalized)
      throws SQLException {
    Bulk bulk = plan.bulk();
    Context first = contexts.get(0);
    // the keys an application asks for come back from the block of entries alone
    if (bulk != null
        && !first.anchors().recorded(plan.n.table)
        && !(bulk.insert() && first.wantsKeys())) {
      int got = applyOracleBulk(vendor, bulk, contexts, finalized);
      if (got != 0) {
        return got;
      }
    }
    Entries e = Objects.requireNonNull(plan.entries());
    List<Run> runs = new ArrayList<>();
    for (Context c : contexts) {
      runs.add(new Run(vendor, c));
    }
    Run lead = runs.get(0);
    int k = contexts.size();
    Variant v = e.variant();
    try (CallableStatement cs = lead.callEntries(e, runs, v)) {
      try {
        cs.execute();
      } catch (SQLException ex) {
        if (undoable(ex)) {
          return -1; // nothing of the block remains: the entries one by one
        }
        throw ex;
      }
      int done = 0;
      if (v == Variant.CURSOR) {
        try (ResultSet rs = Run.cursor(cs, e, k, v)) {
          while (rs.next()) {
            int i = rs.getInt(1) - 1;
            if (i != done || rs.getInt(2) == 0) {
              break; // not reached: after the entry that stopped the block
            }
            Long count = plan.entry(runs.get(i), rs, 3);
            if (count == null) {
              break;
            }
            finalized.record(i, count);
            done++;
          }
        }
        return done;
      }
      List<Integer> keyTypes = Objects.requireNonNull(e.keyTypes);
      if (v == Variant.CHECK) {
        // the first block of the table in this process: each reached entry's key through both
        // paths, and the entry decided from the cursor's row where they differ
        try (ResultSet rs = Run.cursor(cs, e, k, v)) {
          while (rs.next()) {
            int i = rs.getInt(1) - 1;
            if (i != done || rs.getInt(2) == 0) {
              break; // not reached: after the entry that stopped the block
            }
            int at = Run.entryAt(e, k, i);
            OutBindCheck.Outcome outcome =
                OutBindCheck.decide(
                    e.table, plan.entryKey(cs, at + 2, keyTypes), plan.entryKey(rs, 3));
            Long count =
                outcome == OutBindCheck.Outcome.CURSOR
                    ? plan.entry(runs.get(i), rs, 3)
                    : plan.entry(runs.get(i), cs, at + 2, keyTypes);
            if (count == null) {
              break;
            }
            finalized.record(i, count);
            done++;
          }
        }
        return done;
      }
      for (int i = 0; i < k; i++) {
        int at = Run.entryAt(e, k, i);
        if (cs.getInt(at + 1) == 0) {
          break; // not reached: after the entry that stopped the block
        }
        Long count = plan.entry(runs.get(i), cs, at + 2, keyTypes);
        if (count == null) {
          break;
        }
        finalized.record(i, count);
        done++;
      }
      return done;
    } finally {
      lead.context.running(null);
    }
  }

  /**
   * Applies consecutive entries of a batch of one write through the vendor's own batching on Oracle
   * (design 4.4, todo 334): the one plain statement with each entry's binds and its stamp, one
   * {@code executeBatch}. Every entry's count is recorded; a failure, ORA-00001 among them, leaves
   * the entries before the failing one written and counted, as ojdbc reports them, and is thrown
   * classified as the single form classifies it.
   */
  static void applyVendorBatch(
      Connection vendor, Plan plan, List<Context> contexts, Finalized finalized)
      throws SQLException {
    Step step = Objects.requireNonNull(plan.vendorBatch());
    List<Run> runs = new ArrayList<>();
    for (Context c : contexts) {
      runs.add(new Run(vendor, c));
    }
    Run lead = runs.get(0);
    try (PreparedStatement ps = lead.prepare(step.sql)) {
      for (Run r : runs) {
        r.bindStep(ps, step, null);
        ps.addBatch();
      }
      long[] counts;
      try {
        counts = ps.executeLargeBatch();
      } catch (BatchUpdateException b) {
        long[] before = b.getLargeUpdateCounts();
        for (int i = 0; i < before.length && i < runs.size(); i++) {
          finalized.record(i, before[i]);
        }
        int failed = Math.min(before.length, runs.size() - 1);
        SQLException cause = b.getNextException() != null ? b.getNextException() : b;
        throw plan instanceof InsertPlan
            ? ((InsertPlan) plan).duplicateOf(runs.get(failed), cause)
            : cause;
      }
      for (int i = 0; i < counts.length && i < runs.size(); i++) {
        runs.get(i).context.anchors().wrote(runs.get(i).txId);
        finalized.record(i, counts[i]);
      }
    } finally {
      lead.context.running(null);
    }
  }

  /**
   * Applies entries of a batch of one plan as its {@link Plan#merge()} statement (todo 329), under
   * the savepoint of entries sent together, released first as {@link #applyTogether} does. Null
   * when one needs more than the statement: the entries are undone to the savepoint, nothing
   * recorded, and the caller runs them one by one. They share one stamp, as one statement's keys
   * do.
   */
  static long @Nullable [] applyMerged(
      Connection vendor, Plan plan, List<Context> contexts, boolean release, Runnable sent)
      throws SQLException {
    Merge fast = plan.fastMerge(contexts.get(0));
    Merge m = fast != null ? fast : Objects.requireNonNull(plan.merge());
    Run run = new Run(vendor, contexts.get(0));
    String sql =
        (release ? "RELEASE SAVEPOINT " + TOGETHER + "; " : "")
            + "SAVEPOINT "
            + TOGETHER
            + "; "
            + m.sql(contexts.size());
    try (PreparedStatement ps = run.prepare(sql)) {
      int at = m.stampFirst ? run.stamp(ps, 1) : 1;
      for (Context c : contexts) {
        for (int j = 1; j <= m.placeholders; j++) {
          c.bind(ps, at++, j, false);
        }
      }
      if (!m.stampFirst && m.stamps) {
        run.stamp(ps, at);
      }
      try {
        sent.run(); // the savepoint is set from here on, whatever follows
        boolean rows = ps.execute();
        while (!rows) {
          if (ps.getUpdateCount() == -1) {
            throw new SQLException("a merged batch returned no rows", "XX000");
          }
          rows = ps.getMoreResults();
        }
        long[] counts;
        try (ResultSet rs = ps.getResultSet()) {
          counts =
              fast != null
                  ? plan.mergedFast(run, rs, contexts.size(), fast)
                  : plan.merged(run, rs, contexts.size());
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
    // ponytail: two round trips per write on MySQL and MariaDB
    Savepoint sp = plan.savepointed() ? vendor.setSavepoint() : null;
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
    final String txId = freshTxId();
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
        return track(ps);
      } catch (SQLException | RuntimeException e) {
        ps.close(); // track closed it; closed here too so that the resource's path is plain
        throw e;
      }
    }

    /** Gives a driver statement what is left of the timeout, and makes it reachable by cancel. */
    private <S extends Statement> S track(S ps) throws SQLException {
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

    /**
     * An Oracle block as a callable statement, bound as {@link #prepare(Step, Object[])} binds a
     * step, its ref cursor registered as the OUT bind after them (design 4.3 on Oracle, todo 334).
     */
    CallableStatement call(Step step) throws SQLException {
      checkCancelled();
      CallableStatement cs = track(v.prepareCall(step.sql));
      try {
        bindStep(cs, step, null);
        int at = step.outAt();
        for (int type : step.outTypes) {
          cs.registerOutParameter(at++, type);
        }
        if (step.cursor) {
          cs.registerOutParameter(at, ORACLE_CURSOR);
        }
        return cs;
      } catch (SQLException | RuntimeException e) {
        cs.close();
        throw e;
      }
    }

    /** The ref cursor a block of {@link #call} opened, as a result set. */
    static ResultSet cursor(CallableStatement cs, Step step) throws SQLException {
      return (ResultSet) cs.getObject(step.cursorAt());
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
        bindStep(ps, step, key);
        return ps;
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
    }

    /**
     * A block of entries as a callable statement, each entry bound by its own run (its stamp its
     * own), the cursor registered after them all.
     */
    CallableStatement callEntries(Entries e, List<Run> runs, Variant variant) throws SQLException {
      checkCancelled();
      CallableStatement cs = track(v.prepareCall(e.sql(runs.size(), variant)));
      try {
        cs.setFetchSize(runs.size() + 1); // one row per entry and their end in one fetch
        int at = 1;
        for (Run r : runs) {
          for (int j = 0; j <= e.binds.size(); j++) {
            if (j == e.stampAfter) {
              at = r.stamp(cs, at);
            }
            if (j < e.binds.size()) {
              r.context.bind(cs, at++, e.binds.get(j), false);
            }
          }
        }
        if (variant != Variant.CURSOR) {
          List<Integer> out = e.outTypes();
          for (int i = 0; i < runs.size(); i++) {
            for (int type : out) {
              cs.registerOutParameter(at++, type);
            }
          }
        }
        if (variant != Variant.OUT) {
          cs.registerOutParameter(at, ORACLE_CURSOR);
        }
        return cs;
      } catch (SQLException | RuntimeException ex) {
        cs.close();
        throw ex;
      }
    }

    /** The position of entry {@code i}'s first OUT bind, {@code n}. */
    static int entryAt(Entries e, int entries, int i) {
      return entries * e.parameters() + i * e.outTypes().size() + 1;
    }

    /**
     * The ref cursor a block of entries opened, its rows fetched with their end in one round trip:
     * ojdbc prefetches ten, and a row past them would be asked for in a second one.
     */
    static ResultSet cursor(CallableStatement cs, Entries e, int entries, Variant variant)
        throws SQLException {
      int at =
          entries * e.parameters()
              + (variant == Variant.CURSOR ? 0 : entries * e.outTypes().size())
              + 1;
      ResultSet rs = (ResultSet) cs.getObject(at);
      rs.setFetchSize(entries + 1);
      return rs;
    }

    /** A step's binds, the stamp among them where the step places it, then the key's values. */
    void bindStep(PreparedStatement ps, Step step, Object @Nullable [] key) throws SQLException {
      int i = 1;
      for (int at = 0; at <= step.binds.size(); at++) {
        if (at == step.stampAfter) {
          i = stamp(ps, i);
        }
        if (at < step.binds.size()) {
          int b = step.binds.get(at);
          context.bind(ps, i++, b, step.floatKeys.contains(b));
        }
      }
      if (key != null) {
        bind(ps, i, key);
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

  // a generator per thread: UUID.randomUUID shares one, which reads the system's random source
  // on every call, a system call under a lock; a DRBG is seeded from it once and runs in Java
  private static final ThreadLocal<SecureRandom> RANDOM =
      ThreadLocal.withInitial(
          () -> {
            try {
              return SecureRandom.getInstance("DRBG");
            } catch (NoSuchAlgorithmException e) {
              return new SecureRandom();
            }
          });

  /** A fresh transaction id for a write's stamp (I5): a random UUID, as ScalarDB's own are. */
  // the generator's output a kilobyte at a time, sliced per id: a DRBG's call costs the same for
  // sixteen bytes as for a thousand, and a write statement takes one id
  private static final ThreadLocal<byte[]> RANDOM_BYTES =
      ThreadLocal.withInitial(() -> new byte[1024]);

  private static final ThreadLocal<int[]> RANDOM_AT =
      ThreadLocal.withInitial(() -> new int[] {1024});

  static String freshTxId() {
    byte[] buffer = RANDOM_BYTES.get();
    int[] at = RANDOM_AT.get();
    if (at[0] + 16 > buffer.length) {
      RANDOM.get().nextBytes(buffer);
      at[0] = 0;
    }
    byte[] bytes = Arrays.copyOfRange(buffer, at[0], at[0] + 16);
    at[0] += 16;
    bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40); // version 4
    bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80); // IETF variant
    long msb = 0;
    long lsb = 0;
    for (int i = 0; i < 8; i++) {
      msb = (msb << 8) | (bytes[i] & 0xff);
      lsb = (lsb << 8) | (bytes[8 + i] & 0xff);
    }
    return new UUID(msb, lsb).toString();
  }

  /**
   * The coarse check of a table for rows in flight, one probe of the in-flight index of its
   * metadata table: a row means the transaction's snapshot holds a row in flight of the table. Its
   * text is the key under which a transaction remembers that it found none (design 4.2, todo 329).
   */
  static String inFlightCheck(Names n) {
    Engine engine = n.engine;
    if (engine == Engine.ORACLE) {
      // hinted to the in-flight index (design 4.2 on Oracle, todo 334)
      return anyRow(
          engine,
          "SELECT "
              + n.inflightHint("m")
              + "1 FROM "
              + n.meta
              + " m WHERE m."
              + n.txState
              + " IN (1, 2)");
    }
    if (engine == Engine.POSTGRESQL) {
      return firstInFlight(n);
    }
    return anyRow(engine, "SELECT 1 FROM " + n.meta + " WHERE " + n.txState + " IN (1, 2)");
  }

  /**
   * The PL/SQL type of a collection element that holds a value bound by {@code method} with {@code
   * args} (the position first), as ojdbc binds the scalar, so that it compares and converts as in
   * the statement by itself (design 4.4): not the column's type, which would pad a CHAR. A double
   * or float as {@code binary}, the connection's {@code SetFloatAndDoubleUseBinary}, has ojdbc bind
   * it: NUMBER, or BINARY_DOUBLE and BINARY_FLOAT where it is true; null where the connection does
   * not say. Null for any other binding.
   */
  static @Nullable String plsqlType(String method, Object[] args, @Nullable Boolean binary) {
    Object value = args.length > 1 ? args[1] : null;
    switch (method) {
      case "setDouble":
        return binaryOr(binary, "BINARY_DOUBLE");
      case "setFloat":
        return binaryOr(binary, "BINARY_FLOAT");
      case "setInt":
      case "setLong":
      case "setShort":
      case "setByte":
      case "setBigDecimal":
        return "NUMBER";
      case "setString":
        return "VARCHAR2(32767)";
      case "setBytes":
        return "RAW(32767)";
      case "setDate":
        return args.length == 2 ? "DATE" : null;
      case "setTimestamp":
        return args.length == 2 ? "TIMESTAMP" : null;
      case "setNull":
        return value instanceof Integer ? plsqlTypeOfNull((Integer) value, binary) : null;
      case "setObject":
        if (args.length != 2) {
          return null;
        }
        if (value instanceof Integer
            || value instanceof Long
            || value instanceof Short
            || value instanceof Byte
            || value instanceof java.math.BigDecimal
            || value instanceof java.math.BigInteger) {
          return "NUMBER";
        }
        if (value instanceof Double) {
          return binaryOr(binary, "BINARY_DOUBLE");
        }
        if (value instanceof Float) {
          return binaryOr(binary, "BINARY_FLOAT");
        }
        if (value instanceof String) {
          return "VARCHAR2(32767)";
        }
        if (value instanceof byte[]) {
          return "RAW(32767)";
        }
        if (value instanceof java.time.LocalDate || value instanceof java.sql.Date) {
          return "DATE";
        }
        if (value instanceof java.time.LocalDateTime || value instanceof java.sql.Timestamp) {
          return "TIMESTAMP";
        }
        if (value instanceof java.time.OffsetDateTime) {
          return "TIMESTAMP WITH TIME ZONE";
        }
        return null;
      default:
        return null;
    }
  }

  /** {@code type} where the connection binds a double or float as binary, NUMBER where not. */
  private static @Nullable String binaryOr(@Nullable Boolean binary, String type) {
    return binary == null ? null : binary ? type : "NUMBER";
  }

  private static @Nullable String plsqlTypeOfNull(int type, @Nullable Boolean binary) {
    switch (type) {
      case Types.DOUBLE:
      case Types.FLOAT:
        return binaryOr(binary, "BINARY_DOUBLE");
      case Types.REAL:
        return binaryOr(binary, "BINARY_FLOAT");
      case Types.NUMERIC:
      case Types.DECIMAL:
      case Types.INTEGER:
      case Types.BIGINT:
      case Types.SMALLINT:
      case Types.TINYINT:
        return "NUMBER";
      case Types.VARCHAR:
      case Types.CHAR:
        return "VARCHAR2(32767)";
      case Types.VARBINARY:
      case Types.BINARY:
        return "RAW(32767)";
      case Types.DATE:
        return "DATE";
      case Types.TIMESTAMP:
        return "TIMESTAMP";
      default:
        return null;
    }
  }

  /** The JDBC type a slot of {@code plsql} is bound NULL as, past a batch's entries. */
  static int nullTypeOf(String plsql) {
    switch (plsql) {
      case "NUMBER":
        return Types.NUMERIC;
      case "VARCHAR2(32767)":
        return Types.VARCHAR;
      case "RAW(32767)":
        return Types.VARBINARY;
      case "DATE":
        return Types.DATE;
      case "TIMESTAMP":
        return Types.TIMESTAMP;
      case "BINARY_DOUBLE":
        return Types.DOUBLE;
      case "BINARY_FLOAT":
        return Types.REAL;
      default:
        return Types.TIMESTAMP_WITH_TIMEZONE;
    }
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

    /**
     * Adds a key read from OUT binds, which report no type name: the names are PostgreSQL's casts
     * and serve the other engines by their count alone, so a blank stands for each.
     */
    void addFromOut(Object[] key) {
      if (types.isEmpty()) {
        for (int i = 0; i < key.length; i++) {
          types.add("");
        }
      }
      rows.add(key);
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
  static final class KeyedPlan extends Plan {
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
    // the write as a slimmer statement for such a predicate, tried before the fused one (todo 329)
    final @Nullable Step slim;
    // at SNAPSHOT and SERIALIZABLE on PostgreSQL, the vendor's statement with the stamp, gated on
    // the table having no row in flight, and the same for a table the transaction found clear
    // (todo 329)
    final @Nullable Step fast;
    final @Nullable Step fastClear;
    // the same over a batch's entries
    final @Nullable Merge fastMerge;
    final @Nullable Merge fastMergeClear;
    // the coarse check the gate stands for, under which the transaction remembers a clear table
    final String check;
    // on Oracle, the write by the key as one PL/SQL block, whose cursor row the driver decides
    // from, and the write beyond the key as the same block over the predicate's keys (todo 334)
    final @Nullable Block block;
    final @Nullable Step predicateBlock;
    // the keyed block over a batch's entries (design 4.4, todo 334)
    final @Nullable Entries entries;
    // at READ COMMITTED on PostgreSQL on a decoupled table, without a wait: the clear-table
    // statement with the CAS in its stamp, by the key, over a batch's entries, and beyond the key
    // (design 4.3, todo 336)
    final @Nullable Step clearRc;
    final @Nullable Merge clearMergeRc;
    final @Nullable Step predicateRc;
    // at SNAPSHOT, a batch's entries as one bulk block (design 4.4, todo 334)
    final @Nullable Bulk bulk;
    // at READ COMMITTED on Oracle, the write by the key as one statement before the block (todo
    // 334)
    final @Nullable RcFirst rcFirst;

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
        @Nullable Merge merge,
        @Nullable Step slim,
        @Nullable Step fast,
        @Nullable Step fastClear,
        @Nullable Merge fastMerge,
        @Nullable Merge fastMergeClear,
        @Nullable Block block,
        @Nullable Step predicateBlock,
        @Nullable Entries entries,
        @Nullable Step clearRc,
        @Nullable Merge clearMergeRc,
        @Nullable Step predicateRc,
        @Nullable Bulk bulk,
        @Nullable RcFirst rcFirst) {
      super(n);
      this.bulk = bulk;
      this.rcFirst = rcFirst;
      this.clearRc = clearRc;
      this.clearMergeRc = clearMergeRc;
      this.predicateRc = predicateRc;
      this.block = block;
      this.predicateBlock = predicateBlock;
      this.entries = entries;
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
      this.slim = slim;
      this.fast = fast;
      this.fastClear = fastClear;
      this.fastMerge = fastMerge;
      this.fastMergeClear = fastMergeClear;
      this.check = inFlightCheck(n);
    }

    @Override
    List<String> sql() {
      List<String> out = new ArrayList<>();
      for (Step s :
          Arrays.asList(
              afterImages,
              locked,
              keys,
              dml,
              fused,
              slim,
              fast,
              fastClear,
              block == null
                  ? null
                  : block.step(block.keyTypes == null ? Variant.CURSOR : Variant.OUT),
              predicateBlock)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      if (rcFirst != null) {
        // before the block it falls back to, which stays last
        int at = out.size() - (block != null ? 1 : 0) - (predicateBlock != null ? 1 : 0);
        out.add(at, rcFirst.anchored);
        out.add(at, rcFirst.plain);
      }
      return out;
    }

    @Override
    boolean savepointed() {
      // an Oracle block is undone whole by the engine; the steps it may fall back to take their
      // own savepoint
      return block == null && predicateBlock == null && super.savepointed();
    }

    @Override
    @Nullable Bulk bulk() {
      return bulk;
    }

    @Override
    long apply(Run run) throws SQLException {
      // a key this transaction read at a version, by a read that records them, is checked
      // against it by the statements below
      if (fast != null && fastClear != null && !run.context.anchors().recorded(n.table)) {
        boolean passed = run.context.passed(check);
        Long written = applyFast(run, passed ? fastClear : fast, passed);
        if (written != null) {
          return written;
        }
      }
      if (run.context.inflightWait().isZero()) {
        if (clearRc != null) {
          return applyClearRc(run, clearRc);
        }
        if (predicateRc != null && !run.context.anchors().recorded(n.table)) {
          return applyPredicateRc(run, predicateRc);
        }
      }
      if (rcFirst != null) {
        Long written = applyRcFirst(run, rcFirst);
        if (written != null) {
          return written;
        }
      }
      if (block != null || predicateBlock != null) {
        // a write beyond the key on a table the transaction recorded anchors of keeps the steps,
        // which compare each key with its anchor
        Long written =
            block != null
                ? applyBlock(run)
                : run.context.anchors().recorded(n.table) ? null : applyPredicateBlock(run);
        if (written != null) {
          return written;
        }
        Savepoint sp = run.v.setSavepoint();
        try {
          long count = applySteps(run);
          release(n.engine, run.v, sp);
          return count;
        } catch (SQLException | RuntimeException e) {
          try {
            run.v.rollback(sp);
          } catch (SQLException r) {
            e.addSuppressed(r);
          }
          throw e;
        }
      }
      if (slim != null) {
        Long written;
        try (PreparedStatement ps = run.prepare(slim, null)) {
          if (!slim.stampFirst) {
            run.stamp(ps, slim.binds.size() + 1);
          }
          try (ResultSet rs = ps.executeQuery()) {
            written = slim(run, rs, true);
          }
        }
        if (written != null) {
          return written;
        }
      }
      if (fused != null) {
        Long written = applyFused(run, fused);
        if (written != null) {
          return written;
        }
      }
      return applySteps(run);
    }

    /**
     * The keyed write as {@link #clearKeyedRc}'s statement, and what the driver does with its row,
     * in the design's order (4.3, todo 336): nothing written, an abort for a key the snapshot
     * showed in flight, else the count 0 (no row, or a state that is no committed image); written
     * and stamped, the anchor checked against the snapshot's tx_id and version, an abort when it
     * moved; written and not stamped, an abort, "being written" when the snapshot showed the key in
     * flight and "changed after it was read" otherwise. Never the fused statement: there is no wait
     * to take, and an abort undoes the data row written with the transaction.
     */
    private long applyClearRc(Run run, Step step) throws SQLException {
      int width = n.keyColumns.size();
      long written;
      long stamped;
      String txId;
      Object version;
      Object state;
      Object[] key;
      try (PreparedStatement ps = run.prepare(step, null)) {
        if (imported || !delete) {
          run.stamp(ps, step.binds.size() + 1); // a created table's DELETE stamps nothing
        }
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          written = rs.getLong(1);
          stamped = rs.getLong(2);
          txId = rs.getString(3);
          version = rs.getObject(4);
          state = rs.getObject(5);
          key = readKey(rs, 6, width);
        }
      }
      if (written == 0) {
        // the gate closed on a key in flight, or the vendor's 0: no row, or a state that is no
        // committed image, which counts nothing, as the fused statement counts it
        if (inFlight(state)) {
          throw new Aborted(
              "a row the statement writes is being written by a ScalarDB transaction");
        }
        return 0L;
      }
      if (stamped != written || key == null) {
        if (inFlight(state)) {
          throw new Aborted(
              "a row the statement writes is being written by a ScalarDB transaction");
        }
        throw new Aborted("a row the statement writes changed after it was read");
      }
      Anchors anchors = run.context.anchors();
      if (!anchors.unchanged(n.table, key, txId, version)) {
        throw new Aborted("a row this transaction read was changed by another one since");
      }
      anchors.wrote(run.txId);
      return written;
    }

    /**
     * The write beyond the key as {@link #clearPredicateRc}'s statement (design 4.3, todo 336): a
     * row in flight matched by its before image alone, an abort; fewer rows stamped than written,
     * an abort; else the count written.
     */
    private long applyPredicateRc(Run run, Step step) throws SQLException {
      long written;
      long stamped;
      boolean before;
      try (PreparedStatement ps = run.prepare(step, null)) {
        if (imported || !delete) {
          run.stamp(ps, step.binds.size() + 1);
        }
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          written = rs.getLong(1);
          stamped = rs.getLong(2);
          before = rs.getBoolean(3);
        }
      }
      if (before) {
        throw new Aborted("a row the statement writes is being written by a ScalarDB transaction");
      }
      if (stamped != written) {
        throw new Aborted("a row the statement writes changed after it was read");
      }
      if (written > 0) {
        run.context.anchors().wrote(run.txId);
      }
      return written;
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
          if (!step.stampFirst) {
            run.stamp(ps, step.binds.size() + 1);
          }
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

    /**
     * The keyed write at READ COMMITTED on Oracle as {@link #oracleRcFirst}'s statement (design 4.3
     * "The keyed write at READ COMMITTED on Oracle", todo 334): without the anchor for a
     * transaction that recorded no version of the table's keys, with it where the version of the
     * statement's key is found; where versions are recorded and this key's is not, a key the
     * statement spells otherwise than the row (a CHAR key unpadded), null, the block, which matches
     * the key as the row returns it. The row written and stamped is count 1; nothing written is
     * null, the block, which decides as its table says; written and not stamped, an abort.
     */
    private @Nullable Long applyRcFirst(Run run, RcFirst f) throws SQLException {
      Anchors anchors = run.context.anchors();
      Anchors.Version anchor = null;
      if (anchors.recorded(n.table)) {
        Object[] key = f.key(run.context);
        anchor = key == null ? null : anchors.version(n.table, key);
        if (anchor == null) {
          return null;
        }
      }
      long written;
      long stamped;
      if (f.inline) {
        try (PreparedStatement ps = run.prepare(anchor == null ? f.plain : f.anchored)) {
          int at = f.stamps ? run.stamp(ps, 1) : 1;
          for (int b : f.binds) {
            run.context.bind(ps, at++, b, false);
          }
          if (anchor != null) {
            bindAnchor(ps, at, anchor);
          }
          written = ps.executeUpdate();
          stamped = written;
        }
      } else {
        run.checkCancelled();
        try (CallableStatement cs =
            run.track(run.v.prepareCall(anchor == null ? f.plain : f.anchored))) {
          int at = 1;
          if (anchor != null) {
            at = bindAnchor(cs, at, anchor);
          }
          for (int b : f.binds) {
            run.context.bind(cs, at++, b, false);
          }
          if (f.stamps) {
            at = run.stamp(cs, at);
          }
          cs.registerOutParameter(at, Types.NUMERIC);
          cs.registerOutParameter(at + 1, Types.NUMERIC);
          cs.execute();
          written = cs.getLong(at);
          stamped = cs.getLong(at + 1);
        }
      }
      if (written == 0) {
        return null; // no row, a row in flight or moved, a key without metadata: the block tells
      }
      if (written > 1) {
        throw new Aborted("a key matched " + written + " rows");
      }
      if (stamped != 1) {
        throw new Aborted("a row the statement writes changed after it was read");
      }
      anchors.wrote(run.txId);
      return 1L;
    }

    /** The anchor's tx_id and version from {@code at}, either NULL as the version has it. */
    private static int bindAnchor(PreparedStatement ps, int at, Anchors.Version anchor)
        throws SQLException {
      if (anchor.txId == null) {
        ps.setNull(at, Types.VARCHAR);
      } else {
        ps.setString(at, anchor.txId);
      }
      if (anchor.version == null) {
        ps.setNull(at + 1, Types.NUMERIC);
      } else {
        ps.setLong(at + 1, anchor.version);
      }
      return at + 2;
    }

    /**
     * The keyed write on Oracle as one block (design 4.3 "The keyed write on Oracle", todo 334),
     * and what the driver does with the row it returns, in the design's order: no data row, count
     * 0; a data row without a metadata row, null, the steps one by one, which stamp it; a row in
     * flight, the bounded wait and the block again at READ COMMITTED with a wait set, else an
     * abort; a state that is no image, count 0; the version read not the one the transaction read
     * for the key, an abort; written and stamped, count 1; written and not stamped, or not written
     * after a committed row was read, or several rows written, an abort. The block raises nothing
     * of its own; an engine error, ORA-08177 among them, undoes it whole and is mapped as today.
     */
    private @Nullable Long applyBlock(Run run) throws SQLException {
      Block b = Objects.requireNonNull(block);
      int width = n.keyColumns.size();
      long until = System.nanoTime() + run.context.inflightWait().toNanos();
      while (true) {
        Variant v = b.variant();
        Step step = b.step(v);
        KeyedRow row;
        try (CallableStatement cs = run.call(step)) {
          cs.execute();
          if (v == Variant.CURSOR) {
            try (ResultSet rs = Run.cursor(cs, step)) {
              if (!rs.next()) {
                throw new SQLException("the write's block returned no row", "XX000");
              }
              row = KeyedRow.from(rs, 1, width);
            }
          } else {
            row = KeyedRow.from(cs, step.outAt(), Objects.requireNonNull(b.keyTypes));
            if (v == Variant.CHECK) {
              // the first block of the table in this process: the key through both paths, and
              // the row of the cursor where they differ, so that this block's outcome is exact
              try (ResultSet rs = Run.cursor(cs, step)) {
                KeyedRow cursor = rs.next() ? KeyedRow.from(rs, 1, width) : null;
                OutBindCheck.Outcome outcome =
                    OutBindCheck.decide(b.table, row.key, cursor == null ? null : cursor.key);
                if (outcome == OutBindCheck.Outcome.CURSOR) {
                  if (cursor == null) {
                    throw new SQLException("the write's block returned no row", "XX000");
                  }
                  row = cursor;
                }
              }
            }
          }
        }
        Long count = decide(run, row);
        if (count != null) {
          return count;
        }
        if (row.meta == 0) {
          return null; // an imported table's row before its backfill: the steps stamp it
        }
        if (afterImages != null) {
          // SNAPSHOT: the snapshot shows the row in flight, and would until the transaction ends
          throw new Aborted(
              "a row the statement writes is being written by a ScalarDB transaction");
        }
        run.sleep(until, "a row the statement writes is being written by a ScalarDB transaction");
      }
    }

    /**
     * What the keyed block's row means, as the design's table has it: the count where the outcome
     * is final, null for a row in flight or without a metadata row, which the caller waits on or
     * sends to the steps, and an abort thrown for the rest.
     */
    private @Nullable Long decide(Run run, KeyedRow r) throws SQLException {
      int rows = r.rows;
      int meta = r.meta;
      String txId = r.txId;
      Object version = r.version;
      Object state = r.state;
      long written = r.written;
      long stamped = r.stamped;
      Object[] key = r.key;
      if (rows == 0 || key == null) {
        return 0L; // no row for the key, as the vendor's statement finds none
      }
      if (meta == 0 || inFlight(state)) {
        return null;
      }
      if (state != null && ((Number) state).intValue() != 3) {
        return 0L; // no committed image, as the fused statement counts it on PostgreSQL
      }
      Anchors anchors = run.context.anchors();
      if (!anchors.unchanged(n.table, key, txId, version)) {
        throw new Aborted("a row this transaction read was changed by another one since");
      }
      if (written == 1 && stamped == 1) {
        anchors.wrote(run.txId);
        return 1L;
      }
      if (written > 1) {
        throw new Aborted("a key matched " + written + " rows");
      }
      // the row moved, went in flight, lost its metadata or was deleted between the read and
      // the DML or the CAS
      throw new Aborted("a row the statement writes changed after it was read");
    }

    @Override
    @Nullable Entries entries() {
      return entries;
    }

    @Override
    @Nullable Block oracleBlock() {
      return block;
    }

    @Override
    @Nullable Long entry(Run run, ResultSet rs, int from) throws SQLException {
      return decide(run, KeyedRow.from(rs, from, n.keyColumns.size()));
    }

    @Override
    @Nullable Long entry(Run run, CallableStatement cs, int at, List<Integer> keyTypes)
        throws SQLException {
      return decide(run, KeyedRow.from(cs, at, keyTypes));
    }

    @Override
    Object @Nullable [] entryKey(ResultSet rs, int from) throws SQLException {
      return readKey(rs, from + 7, n.keyColumns.size());
    }

    @Override
    Object @Nullable [] entryKey(CallableStatement cs, int at, List<Integer> keyTypes)
        throws SQLException {
      return readOutKey(cs, at + 7, keyTypes);
    }

    /**
     * The write beyond the key on Oracle as one block (design 4.3 "The write beyond the key on
     * Oracle", todo 334), and what the driver does with its row of counts, in the design's order:
     * nothing matched, count 0; an after image matched (SNAPSHOT), an abort; a key in flight, the
     * bounded wait and the block again at READ COMMITTED with a wait set, else an abort; a key
     * without a metadata row, null, the steps; an abort code, an abort (a key the DML skipped was
     * deleted, went in flight, is still at its version, still matches the inline DELETE's
     * predicate, or matched several rows); a stamp short of the rows written, an abort; else the
     * rows written, the skipped keys counting for nothing.
     */
    private @Nullable Long applyPredicateBlock(Run run) throws SQLException {
      Step step = Objects.requireNonNull(predicateBlock);
      long until = System.nanoTime() + run.context.inflightWait().toNanos();
      while (true) {
        long found;
        long held;
        long missing;
        long after;
        long written;
        long stamped;
        int abort;
        try (CallableStatement cs = run.call(step)) {
          cs.execute();
          int at = step.outAt();
          found = cs.getLong(at);
          held = cs.getLong(at + 1);
          missing = cs.getLong(at + 2);
          after = cs.getLong(at + 3);
          written = cs.getLong(at + 4);
          stamped = cs.getLong(at + 5);
          abort = cs.getInt(at + 7);
        }
        if (after > 0) {
          // a row in flight only its after image matches, whether or not a committed image did:
          // the fused statement on PostgreSQL checks it first too
          throw new Aborted(
              "a row the statement writes is being written by a ScalarDB transaction");
        }
        if (found == 0) {
          return 0L; // nothing matched, as the vendor's statement finds none
        }
        if (held > 0) {
          if (afterImages != null) {
            throw new Aborted(
                "a row the statement writes is being written by a ScalarDB transaction");
          }
          run.sleep(until, "a row the statement writes is being written by a ScalarDB transaction");
          continue;
        }
        if (missing > 0) {
          return null; // an imported table's rows before its backfill: the steps stamp them
        }
        switch (abort) {
          case 1:
            throw new Aborted("a row the statement writes was deleted after it was read");
          case 2:
            throw new Aborted(
                "a row the statement writes went in flight in a ScalarDB transaction");
          case 3:
            throw new Aborted("a row the statement writes showed a value in flight");
          case 4:
            throw new Aborted("a row the statement deletes changed after it was read");
          case 5:
            throw new Aborted("a key matched several rows");
          default:
            break;
        }
        if (stamped < written) {
          throw new Aborted("a row the statement writes changed after it was read");
        }
        if (written > 0) {
          run.context.anchors().wrote(run.txId);
        }
        return written;
      }
    }

    /**
     * The vendor's statement with the stamp (todo 329): what it wrote, or null when it wrote
     * nothing and the table has a row in flight in the snapshot, which closed the gate, or may
     * have: the statements below then tell which row, and wait or abort. A write that stamped fewer
     * rows than it wrote met a metadata row that is not committed, or none where a created table
     * must have one: an abort, as a zero-row CAS is (4.3).
     */
    private @Nullable Long applyFast(Run run, Step step, boolean passed) throws SQLException {
      long written;
      long stamped;
      if (n.engine == Engine.ORACLE && !inline) {
        // the clear-table block, its two counts as OUT binds (todo 334)
        try (CallableStatement cs = run.call(step)) {
          cs.execute();
          written = cs.getLong(step.outAt());
          stamped = cs.getLong(step.outAt() + 1);
        }
      } else {
        try (PreparedStatement ps = run.prepare(step, null)) {
          if (inline) {
            written = ps.executeUpdate();
            stamped = written;
          } else {
            if (imported || !delete) {
              run.stamp(ps, step.binds.size() + 1); // a created table's DELETE stamps nothing
            }
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              written = rs.getLong(1);
              stamped = rs.getLong(2);
            }
          }
        }
      }
      if (written == 0) {
        if (passed) {
          return 0L; // nothing matched, as the vendor's statement would find
        }
        try (PreparedStatement ps = run.prepare(check);
            ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return null; // the gate was closed
          }
        }
        run.context.pass(check);
        return 0L;
      }
      if (stamped != written) {
        throw new Aborted("a row the statement writes changed after it was read");
      }
      if (!passed) {
        run.context.pass(check); // the gate was open: no row of the table is in flight
      }
      run.context.anchors().wrote(run.txId);
      return written;
    }

    @Override
    @Nullable Step together() {
      return slim != null ? slim : fused;
    }

    /**
     * What the slim statement returned: how many rows it wrote, recorded; null when it wrote
     * nothing, the gate closed or the key's row gone, which the fused statement then tells. {@code
     * strict}: a stamp that missed, or an anchor that moved, aborts, as the fused statement's do;
     * otherwise it is null too, for entries sent together to be undone and run one by one.
     */
    private @Nullable Long slim(Run run, ResultSet rs, boolean strict) throws SQLException {
      int width = n.keyColumns.size();
      rs.next();
      Object state = rs.getObject(width + 3);
      long written = rs.getLong(width + 4);
      // anything but a committed row written: the fused statement, which tells each case (a key
      // with no row, deleted since it was read, in flight, without metadata, in another state)
      if (readKey(rs, 1, width) == null
          || (state != null && ((Number) state).intValue() != 3)
          || written == 0) {
        return null;
      }
      if (rs.getLong(width + 5) != written) {
        if (!strict) {
          return null;
        }
        throw new Aborted("a row the statement writes changed after it was read");
      }
      Object[] key = Objects.requireNonNull(readKey(rs, width + 6, width));
      if (!run.context
          .anchors()
          .unchanged(n.table, key, rs.getString(width + 1), rs.getObject(width + 2))) {
        if (!strict) {
          return null;
        }
        throw new Aborted("a row this transaction read was changed by another one since");
      }
      if (strict) {
        run.context.anchors().wrote(run.txId);
      }
      return written;
    }

    /** As {@link #applyFused}, null for anything but every key written and stamped at once. */
    @Override
    @Nullable Together together(Run run, ResultSet rs) throws SQLException {
      if (slim != null) {
        Long written = slim(run, rs, false);
        return written == null ? null : new Together(run, written, null, written > 0);
      }
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

    @Override
    @Nullable Merge fastMerge(Context context) {
      if (clearMergeRc != null && context.inflightWait().isZero()) {
        return clearMergeRc;
      }
      if (fastMerge == null || fastMergeClear == null || context.anchors().recorded(n.table)) {
        return null;
      }
      return context.passed(check) ? fastMergeClear : fastMerge;
    }

    @Override
    @Nullable Merge fastMerge(boolean gated) {
      return gated ? fastMerge : fastMergeClear;
    }

    @Override
    long @Nullable [] mergedFast(Run run, ResultSet rs, int entries, Merge m) throws SQLException {
      if (m == clearMergeRc) {
        return mergedRc(run, rs, entries);
      }
      long[] counts = new long[entries];
      long stamped = -1;
      int rows = 0;
      while (rs.next()) {
        int i = rs.getInt(1) - 1;
        if (i < 0 || i >= entries || counts[i] != 0) {
          return null;
        }
        counts[i] = 1;
        rows++;
        if (!inline) {
          stamped = rs.getLong(2);
        }
      }
      // every entry wrote its row and had it stamped; otherwise one by one tells which did not
      if (rows != entries || (!inline && stamped != rows)) {
        return null;
      }
      if (m == fastMerge) {
        run.context.pass(check); // the gate was open
      }
      run.context.anchors().wrote(run.txId);
      return counts;
    }

    /**
     * What {@link #clearMergedRc}'s statement returned for {@code entries} entries: each one's
     * count, recorded, when every entry wrote its row, every row was stamped and no anchor moved;
     * null otherwise, the entries undone and run one by one, each telling its own outcome (a key
     * without a row, two entries on one key, a stamp missed, an anchor moved).
     */
    private long @Nullable [] mergedRc(Run run, ResultSet rs, int entries) throws SQLException {
      int width = n.keyColumns.size();
      long[] counts = new long[entries];
      Anchors anchors = run.context.anchors();
      long stamped = -1;
      int rows = 0;
      while (rs.next()) {
        int i = rs.getInt(1) - 1;
        if (i < 0 || i >= entries || counts[i] != 0) {
          return null;
        }
        stamped = rs.getLong(2);
        Object[] key = readKey(rs, 5, width);
        if (key == null || !anchors.unchanged(n.table, key, rs.getString(3), rs.getObject(4))) {
          return null;
        }
        counts[i] = 1;
        rows++;
      }
      if (rows != entries || stamped != rows) {
        return null;
      }
      anchors.wrote(run.txId);
      return counts;
    }

    /**
     * As {@link #together(Run, ResultSet)} for each entry, its count the key it wrote; null for
     * anything but every entry's key found, written and stamped, with the gate open.
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
          // no row for the entry's key: one by one tells whether it has none, or its value missed
          // it as this statement's VALUES typed it (a CHAR key's blanks against text)
          return null;
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
    // on Oracle, a decoupled table's INSERT and its metadata row as one block, and an inline
    // table's INSERT with its stamp columns as the one statement it is (todo 334)
    final @Nullable Block oracleBlock;
    final @Nullable Step oracleInline;
    // the INSERT block over a batch's entries (design 4.4, todo 334)
    final @Nullable Entries entries;
    // the decoupled INSERT of one row as one INSERT ALL statement, the block its fallback
    final @Nullable Step oracleInsertAll;
    // at SNAPSHOT, a batch's entries as one bulk block (design 4.4, todo 334)
    final @Nullable Bulk bulk;

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
        boolean generatedKey,
        @Nullable Block oracleBlock,
        @Nullable Step oracleInline,
        @Nullable Entries entries,
        @Nullable Step oracleInsertAll,
        @Nullable Bulk bulk) {
      super(n);
      this.oracleInsertAll = oracleInsertAll;
      this.bulk = bulk;
      this.oracleBlock = oracleBlock;
      this.oracleInline = oracleInline;
      this.entries = entries;
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
      for (Step s :
          Arrays.asList(
              inFlight,
              committed,
              repeats,
              fused,
              oracleBlock == null
                  ? null
                  : oracleBlock.step(oracleBlock.keyTypes == null ? Variant.CURSOR : Variant.OUT),
              oracleInline,
              oracleInsertAll)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }

    @Override
    boolean savepointed() {
      // one statement on Oracle, which the engine undoes whole on a failure
      return oracleBlock == null && oracleInline == null && super.savepointed();
    }

    @Override
    @Nullable Entries entries() {
      return entries;
    }

    @Override
    @Nullable Block oracleBlock() {
      return oracleBlock;
    }

    @Override
    @Nullable Bulk bulk() {
      return bulk;
    }

    /** An entry's row: written, or a metadata row in flight, which is an abort at once. */
    @Override
    @Nullable Long entry(Run run, ResultSet rs, int from) throws SQLException {
      if (rs.getInt(from) == 0 && rs.getInt(from + 1) == 0) {
        throw new Aborted("a key the statement inserts is being written by ScalarDB");
      }
      Keys keys = new Keys();
      keys.read(rs, from + 2, n.keyColumns.size());
      return inserted(run, keys);
    }

    @Override
    @Nullable Long entry(Run run, CallableStatement cs, int at, List<Integer> keyTypes)
        throws SQLException {
      if (cs.getInt(at) == 0 && cs.getInt(at + 1) == 0) {
        throw new Aborted("a key the statement inserts is being written by ScalarDB");
      }
      Keys keys = new Keys();
      keys.addFromOut(
          Objects.requireNonNull(
              readOutKey(cs, at + 2, keyTypes), "the INSERT's block returned no key"));
      return inserted(run, keys);
    }

    private Long inserted(Run run, Keys keys) {
      run.context.anchors().wrote(run.txId);
      run.context.inserted(keys);
      return 1L;
    }

    @Override
    Object @Nullable [] entryKey(ResultSet rs, int from) throws SQLException {
      return readKey(rs, from + 2, n.keyColumns.size());
    }

    @Override
    Object @Nullable [] entryKey(CallableStatement cs, int at, List<Integer> keyTypes)
        throws SQLException {
      return readOutKey(cs, at + 2, keyTypes);
    }

    @Override
    @Nullable Step vendorBatch() {
      return oracleInline;
    }

    /** A duplicate out of a vendor batch, classified as the single form classifies it. */
    SQLException duplicateOf(Run run, SQLException e) throws SQLException {
      return n.engine.isDuplicateKey(e) && inFlight != null ? duplicate(run, e) : e;
    }

    @Override
    long apply(Run run) throws SQLException {
      // the keys the application asks for come back from the block alone
      if (oracleInsertAll != null && oracleBlock != null && !run.context.wantsKeys()) {
        return applyOracleInsertAll(run, oracleInsertAll, oracleBlock);
      }
      if (oracleBlock != null) {
        return applyOracleBlock(run, oracleBlock);
      }
      if (oracleInline != null) {
        return applyOracleInline(run, oracleInline);
      }
      return fused != null ? applyFused(run, fused) : applySteps(run);
    }

    /**
     * The decoupled INSERT on Oracle as one block (design 4.3 "An INSERT on Oracle", todo 334): the
     * row, its metadata row under the hint that skips a key with one, and that row taken over when
     * it was skipped; the key back through the cursor. {@code meta} 1, or {@code stamped} 1 over a
     * tombstone or an orphan, is the row written; neither is a metadata row in flight, an abort,
     * the data row left until the rollback. ORA-00001 undoes the block whole and is classified as
     * the steps classify it.
     */
    private long applyOracleBlock(Run run, Block b) throws SQLException {
      int width = n.keyColumns.size();
      Variant v = b.variant();
      Step step = b.step(v);
      Keys keys = new Keys();
      try (CallableStatement cs = run.call(step)) {
        try {
          cs.execute();
        } catch (SQLException e) {
          if (!n.engine.isDuplicateKey(e) || inFlight == null) {
            throw e;
          }
          throw duplicate(run, e);
        }
        if (v == Variant.CURSOR) {
          try (ResultSet rs = Run.cursor(cs, step)) {
            if (!rs.next()) {
              throw new SQLException("the INSERT's block returned no row", "XX000");
            }
            if (rs.getInt(1) == 0 && rs.getInt(2) == 0) {
              throw new Aborted("a key the statement inserts is being written by ScalarDB");
            }
            keys.read(rs, 3, width);
          }
        } else {
          int at = step.outAt();
          Object[] key = readOutKey(cs, at + 2, Objects.requireNonNull(b.keyTypes));
          if (v == Variant.CHECK) {
            // the key through both paths, and the cursor's where they differ
            try (ResultSet rs = Run.cursor(cs, step)) {
              Object[] cursor = rs.next() ? readKey(rs, 3, width) : null;
              if (OutBindCheck.decide(b.table, key, cursor) == OutBindCheck.Outcome.CURSOR) {
                if (cursor == null) {
                  throw new SQLException("the INSERT's block returned no row", "XX000");
                }
                key = cursor;
              }
            }
          }
          if (cs.getInt(at) == 0 && cs.getInt(at + 1) == 0) {
            throw new Aborted("a key the statement inserts is being written by ScalarDB");
          }
          keys.addFromOut(Objects.requireNonNull(key, "the INSERT's block returned no key"));
        }
      }
      run.context.anchors().wrote(run.txId);
      run.context.inserted(keys);
      return 1;
    }

    /**
     * The decoupled INSERT of one row on Oracle as one {@code INSERT ALL} (design 4.3, todo 334):
     * its count, the two rows, reported as the one the application wrote. A metadata row already
     * there, a tombstone or an orphan, in flight or not, fails it with ORA-00001 on the metadata
     * table's constraint, which leaves no data row: the block then takes that row over or aborts, a
     * second round trip only then. The data row's own duplicate is classified as the block's.
     */
    private long applyOracleInsertAll(Run run, Step step, Block block) throws SQLException {
      try (PreparedStatement ps = run.prepare(step, null)) {
        ps.executeUpdate();
      } catch (SQLException e) {
        if (!n.engine.isDuplicateKey(e)) {
          throw e;
        }
        if (onMetadataTable(run, e)) {
          return applyOracleBlock(run, block);
        }
        if (inFlight == null) {
          throw e;
        }
        throw duplicate(run, e);
      }
      run.context.anchors().wrote(run.txId);
      return 1;
    }

    /**
     * Whether ORA-00001 names a constraint of the table's metadata table: its table read from the
     * dictionary once per constraint, so that the tombstone's INSERT is two round trips after it.
     */
    private boolean onMetadataTable(Run run, SQLException e) throws SQLException {
      Matcher m = ORA_CONSTRAINT.matcher(String.valueOf(e.getMessage()));
      if (!m.find()) {
        return false;
      }
      String constraint = m.group(1) + "." + m.group(2);
      String table = CONSTRAINT_TABLES.get(constraint);
      if (table == null) {
        try (PreparedStatement ps =
            run.v.prepareStatement(
                "SELECT table_name FROM all_constraints WHERE owner = ? AND constraint_name = ?")) {
          ps.setString(1, m.group(1));
          ps.setString(2, m.group(2));
          try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
              return false;
            }
            table = n.engine.quote(m.group(1)) + "." + n.engine.quote(rs.getString(1));
          }
        }
        CONSTRAINT_TABLES.put(constraint, table);
      }
      return table.equals(n.meta);
    }

    /**
     * The inline INSERT on Oracle as the one statement it is (todo 334), with its stamp columns:
     * its key read back by the row's ROWID only when the application asks for the generated keys.
     */
    private long applyOracleInline(Run run, Step step) throws SQLException {
      Keys keys = new Keys();
      try (PreparedStatement ps = run.prepare(step, null, false, new String[0])) {
        try {
          ps.executeUpdate();
        } catch (SQLException e) {
          if (!n.engine.isDuplicateKey(e) || inFlight == null) {
            throw e;
          }
          throw duplicate(run, e);
        }
        if (run.context.wantsKeys()) {
          try (ResultSet k = ps.getGeneratedKeys()) {
            while (k.next()) {
              keysByRowid(run, k.getObject(1), keys);
            }
          }
        }
      }
      run.context.anchors().wrote(run.txId);
      run.context.inserted(keys);
      return 1;
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
        if (!step.stampFirst) {
          run.stamp(ps, step.binds.size() + 1);
        }
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
    // there too, the statement as written: alone for a table the transaction found to have no
    // row in flight in its snapshot, else after the coarse check of the table, in one round trip
    // (todo 329)
    final @Nullable Step plain;
    final @Nullable Step checked;
    final String check;
    // at READ COMMITTED on PostgreSQL, a table in the inline layout: the statement as written over
    // the rows as they are, each with its key, version and state, one statement (todo 329); on
    // Oracle in both layouts (todo 334)
    final @Nullable Step fastRc;
    // on Oracle at SNAPSHOT: the statement as written with the write's gate, for a table not yet
    // found clear, and as written alone for one found clear (todo 334)
    final @Nullable Step oracleGated;
    final @Nullable Step oraclePlain;

    LockPlan(
        Names n,
        Step lock,
        @Nullable Step inFlight,
        boolean noWait,
        @Nullable String relock,
        @Nullable Step snapshot,
        boolean fuse,
        @Nullable Step plain,
        @Nullable Step fastRc,
        @Nullable Step oracleGated,
        @Nullable Step oraclePlain) {
      super(n);
      this.fastRc = fastRc;
      this.oracleGated = oracleGated;
      this.oraclePlain = oraclePlain;
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
      this.check = inFlightCheck(n);
      Step p = fuse && inFlight != null ? plain : null;
      this.plain = p;
      this.checked = p == null ? null : new Step(check + "; " + p.sql, p.binds);
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
      for (Step s : Arrays.asList(inFlight, lock, snapshot, fused, plain, fastRc)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }

    @Override
    boolean savepointed() {
      // the Oracle forms are one statement each, which the engine undoes whole on a failure; the
      // steps they may fall back to take a savepoint per attempt of their own
      return oracleGated == null && fastRc == null && super.savepointed();
    }

    @Override
    long apply(Run run) throws SQLException {
      if (plain != null && checked != null) {
        return run.context.passed(check)
            ? applyPlain(run, plain, false)
            : applyPlain(run, checked, true);
      }
      if (oracleGated != null && oraclePlain != null) {
        return run.context.passed(check)
            ? applyGated(run, oraclePlain, false)
            : applyGated(run, oracleGated, true);
      }
      if (fastRc != null && run.context.inflightWait().isZero()) {
        return n.inline || n.engine == Engine.ORACLE
            ? applyFastRc(run, fastRc)
            : applyFastRcDecoupled(run, fastRc);
      }
      return fused != null ? applyFused(run, fused) : applySteps(run);
    }

    /**
     * The locking read on Oracle at SNAPSHOT (design 4.3 "The locking read on Oracle", todo 334):
     * the statement as written on the data table, with the write's gate in its WHERE clause on a
     * table the transaction has not found clear; a row returned proves the gate open and marks the
     * table clear; none returned is the predicate matching nothing or the gate closed, which the
     * coarse check then tells: none found marks the table clear and the empty result is the
     * vendor's; a row found runs the predicate's in-flight check, an abort for a row in flight the
     * statement matches by either image, NOWAIT or not, else the lock over the committed copy as
     * the steps take it, without versions, savepoint or second check, one snapshot making them
     * vacuous. ORA-08177 from the lock is the engine's conflict, mapped as today.
     */
    @SuppressFBWarnings(
        value = "OBL_UNSATISFIED_OBLIGATION",
        justification = "the rows are handed to the application, closed with their statement")
    private long applyGated(Run run, Step step, boolean gated) throws SQLException {
      PreparedStatement ps = run.prepare(step, null, true);
      try {
        ResultSet rs = ps.executeQuery();
        int rows = 0;
        while (rs.next()) {
          rows++;
        }
        if (rows > 0 || !gated) {
          if (gated) {
            run.context.pass(check); // the gate was open: no row of the table is in flight
          }
          rs.beforeFirst();
          ps.closeOnCompletion();
          run.context.rows(rs, 0);
          return rows;
        }
        boolean held;
        try (PreparedStatement c = run.prepare(check);
            ResultSet found = c.executeQuery()) {
          held = found.next();
        }
        if (!held) {
          run.context.pass(check);
          rs.beforeFirst();
          ps.closeOnCompletion();
          run.context.rows(rs, 0);
          return 0;
        }
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
      ps.close();
      if (inFlight != null && matchesInFlight(run)) {
        throw new Aborted("a row the statement locks is being written by a ScalarDB transaction");
      }
      // the committed copy's lock, its rows the application's
      PreparedStatement locked = run.prepare(lock, null, true);
      try {
        ResultSet rs = locked.executeQuery();
        int rows = 0;
        while (rs.next()) {
          rows++;
        }
        rs.beforeFirst();
        locked.closeOnCompletion();
        run.context.rows(rs, 0);
        return rows;
      } catch (SQLException | RuntimeException e) {
        locked.close();
        throw e;
      }
    }

    /**
     * The locking read at READ COMMITTED on PostgreSQL on a decoupled table as two statements in
     * one round trip (todo 329): the lock as the steps take it, then the keys, versions and states
     * of the rows the predicate matches, read from the data table joined to its metadata table in a
     * snapshot taken after the lock, where a prepare that committed before the lock was granted
     * shows, and one that has not committed cannot, since it needs the data row's lock now held
     * (design 4.3). A row in flight among those rows with a before image, or among the locked rows,
     * aborts, or fails as NOWAIT fails, as the steps' checks before and after the lock would; a
     * locked key the read does not show falls back to the steps. The lock's rows go to the
     * application, still open and scrollable past the second result, the versions to the anchors.
     * Only where no wait is configured: a wait would hold the lock.
     */
    private long applyFastRcDecoupled(Run run, Step step) throws SQLException {
      int width = n.keyColumns.size();
      String held = "a row the statement locks is being written by a ScalarDB transaction";
      PreparedStatement ps = run.prepare(step, null, true);
      try {
        ps.execute();
        ResultSet rs = ps.getResultSet();
        if (rs == null) {
          throw new SQLException("the locking read returned no rows", "XX000");
        }
        // the key the lock appends, its last columns (todo 336: no version lookups after it)
        int from = rs.getMetaData().getColumnCount() - width + 1;
        List<Object[]> keys = new ArrayList<>();
        Set<List<Object>> locked = new HashSet<>();
        while (rs.next()) {
          Object[] key = Objects.requireNonNull(readKey(rs, from, width));
          keys.add(key);
          locked.add(Anchors.key(key));
        }
        rs.beforeFirst();
        if (!ps.getMoreResults(Statement.KEEP_CURRENT_RESULT)) {
          throw new SQLException("the locking read returned no versions", "XX000");
        }
        Map<List<Object>, Anchors.Version> versions = new HashMap<>();
        try (ResultSet vs = ps.getResultSet()) {
          while (vs.next()) {
            Object[] key = Objects.requireNonNull(readKey(vs, 1, width));
            if (inFlight(vs.getObject(width + 3))
                && (vs.getBoolean(width + 4) || locked.contains(Anchors.key(key)))) {
              if (noWait) {
                throw notAvailable(held);
              }
              throw new Aborted(held);
            }
            versions.put(
                Anchors.key(key),
                new Anchors.Version(vs.getString(width + 1), vs.getObject(width + 2)));
          }
        }
        for (Object[] key : keys) {
          // a locked key the read does not show has no metadata row: the NULL-metadata anchor of
          // 4.4, as a read of such a row records it (design 4.3, todo 337)
          Anchors.Version v = versions.get(Anchors.key(key));
          run.context.anchor(n.table, key, v != null ? v : new Anchors.Version(null, null));
        }
        ps.closeOnCompletion();
        run.context.rows(rs, width);
        return keys.size();
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
    }

    /**
     * The locking read at READ COMMITTED on PostgreSQL as one statement (todo 329): the statement
     * as written over the rows of a table in the inline layout as they are, each row with its key,
     * version and state as columns of its own. The lock returns each row at its newest version,
     * which PostgreSQL re-reads after a wait on the row, and that version is the anchor the level
     * wants, a read now (4.3 step 1); its state says whether the row is in flight, which aborts, or
     * fails as NOWAIT fails, since the lock held on it must not outlast the statement (section 6).
     * Only where no wait is configured: a wait would hold the lock.
     */
    @SuppressFBWarnings(
        value = "OBL_UNSATISFIED_OBLIGATION",
        justification = "the rows are handed to the application, closed with their statement")
    private long applyFastRc(Run run, Step step) throws SQLException {
      int width = n.keyColumns.size();
      String held = "a row the statement locks is being written by a ScalarDB transaction";
      PreparedStatement ps = run.prepare(step, null, true);
      try {
        ResultSet rs = ps.executeQuery();
        // the key, the version and the state the copy appends
        int from = rs.getMetaData().getColumnCount() - width - 2;
        List<Object[]> keys = new ArrayList<>();
        List<Anchors.Version> versions = new ArrayList<>();
        while (rs.next()) {
          if (inFlight(rs.getObject(from + width + 2))) {
            if (noWait) {
              throw notAvailable(held);
            }
            throw new Aborted(held);
          }
          keys.add(Objects.requireNonNull(readKey(rs, from, width)));
          versions.add(
              new Anchors.Version(rs.getString(from + width), rs.getObject(from + width + 1)));
        }
        for (int i = 0; i < keys.size(); i++) {
          run.context.anchor(n.table, keys.get(i), versions.get(i));
        }
        rs.beforeFirst();
        ps.closeOnCompletion();
        run.context.rows(rs, width + 3);
        return keys.size();
      } catch (SQLException | RuntimeException e) {
        ps.close();
        throw e;
      }
    }

    /**
     * The locking read as written, at SNAPSHOT and SERIALIZABLE on PostgreSQL (todo 329): every
     * statement of the transaction reads its one snapshot, so for a table it holds no row in flight
     * of, which the coarse check sent before the lock decides for a transaction that does not know
     * yet, the rows locked are the committed rows the statement matches, as {@link #applyFused}'s
     * are. A row the check finds leaves the rows locked and asks the in-flight check of the
     * predicate, which aborts at once for a row in flight the statement matches by either image
     * (section 6), the locks released with the transaction.
     */
    private long applyPlain(Run run, Step step, boolean checks) throws SQLException {
      PreparedStatement ps = run.prepare(step, null, true);
      try {
        ps.execute();
        boolean held = false;
        if (checks) {
          try (ResultSet found = ps.getResultSet()) {
            held = found != null && found.next();
          }
          if (!ps.getMoreResults()) {
            throw new SQLException("the locking read returned no rows", "XX000");
          }
        }
        ResultSet rs = ps.getResultSet();
        if (rs == null) {
          throw new SQLException("the locking read returned no rows", "XX000");
        }
        if (held) {
          if (matchesInFlight(run)) {
            throw new Aborted(
                "a row the statement locks is being written by a ScalarDB transaction");
          }
        } else if (checks) {
          run.context.pass(check);
        }
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
    // the in-flight index of the metadata table, quoted, which Oracle's probes are hinted to
    final String inflightIndex;

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
      this.inflightIndex = engine.quote(t.inflightIndex(engine));
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
      return keyExpr((q.isEmpty() ? "" : q + ".") + engine.quote(k), k);
    }

    /** {@code expr}, a value of key column {@code k}, read as {@link #key} reads the column. */
    String keyExpr(String expr, String k) {
      if (engine == Engine.POSTGRESQL || !floatKeys.contains(k)) {
        return expr;
      }
      return engine == Engine.ORACLE ? "TO_BINARY_DOUBLE(" + expr + ")" : "(" + expr + " + 0E0)";
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

    /**
     * On Oracle, the hint that sends a probe of the metadata table, aliased {@code m} as the SQL
     * writes it (quoted where the SQL quotes it), to the in-flight index: a probe's plan must not
     * depend on the metadata table's statistics (design 4.2, todo 334). Elsewhere nothing.
     */
    String inflightHint(String m) {
      return engine == Engine.ORACLE ? "/*+ INDEX(" + m + " " + inflightIndex + ") */ " : "";
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
