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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
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
    if ((engine == Engine.MYSQL || engine == Engine.ORACLE)
        && vendor != null
        && shape.kind() == StatementShape.Kind.INSERT) {
      String why = engine == Engine.ORACLE ? oracleTrigger(vendor, n) : storesOtherKeys(vendor, n);
      if (why != null) {
        throw refused(tables, why);
      }
    }
    return shape.kind() == StatementShape.Kind.INSERT
        ? insertPlan(shape, t, n, all, tables, snapshot, vendor == null ? null : columns(vendor, n))
        : keyedPlan(shape, t, n, all, tables, snapshot, locks);
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
   * The data table's columns in their own order with their types (todos 203, 208), an
   * AUTO_INCREMENT one's followed by {@code " auto_increment"} (todo 218).
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
              rs.getString(2).toLowerCase(Locale.ROOT)
                  + (rs.getBoolean(3) ? " auto_increment" : ""));
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
    return new KeyedPlan(
        n,
        shape.kind() == StatementShape.Kind.DELETE,
        t.layout() == Layout.DECOUPLED_IMPORTED,
        afterImages,
        locked,
        new Step(keys, inWhere),
        new Step(dml, all),
        where != null);
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
                : returns
                    ? source.substring(0, end)
                        + (returning == null ? " RETURNING " : ", ")
                        + n.readColumns("")
                        + source.substring(end)
                    : source);
    if (insert == null) {
      throw refused(tables, "the statement's text cannot be rewritten");
    }
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
          String full =
              type(engine, tableColumns, t.keyColumns().get(i)).replace(" auto_increment", "");
          if (engine == Engine.ORACLE && !full.isEmpty()) {
            // as the column holds it: a VARCHAR2 bind does not equal a blank-padded CHAR key, and
            // a number bound for a text key would convert every key to a number (todo 277)
            value = "CAST(" + value + " AS " + full + ")";
          }
          terms.add(k + " = " + value);
          termsData.add("d." + k + " = " + value);
          if (!returns
              && type(engine, tableColumns, t.keyColumns().get(i)).endsWith(" auto_increment")) {
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
    return new InsertPlan(
        n,
        new Step(insert, all),
        readBack,
        inFlight,
        committed,
        repeats,
        returning,
        Router.exposed(ref));
  }

  /**
   * Whether a column is single precision: FLOAT on MySQL and MariaDB, REAL on PostgreSQL and SQL
   * Server, where FLOAT is double, BINARY_FLOAT on Oracle.
   */
  private static boolean isFloat(
      Engine engine, @Nullable Map<String, String> columns, String column) {
    String type = type(engine, columns, column).replace(" auto_increment", "");
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
   * working. {@code text} is the source with edits only after the name.
   *
   * @return the text, or {@code null} when the name cannot be placed in it
   */
  private static @Nullable String onDataTable(
      Engine engine, SqlAst ast, TableInfo t, boolean alias, String text) {
    return onDataTable(engine, ast, t, alias, text, -1);
  }

  /**
   * As {@link #onDataTable(Engine, SqlAst, TableInfo, boolean, String)}; {@code from}, where an
   * UPDATE's WHERE starts in the source, is where SQL Server's FROM clause goes.
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
   * Whether a vendor error ended the transaction: one of class 40, except a MySQL or MariaDB lock
   * wait timeout, which undoes the statement only unless innodb_rollback_on_timeout is on, and
   * which MariaDB's driver reports as a plain error (todos 204, 211); and MariaDB's 1020, a locking
   * read of a row changed since the snapshot under innodb_snapshot_isolation, which ends it too
   * (todo 248). On SQL Server, whatever the error, whether a transaction is left.
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
   * SQLState, as the retryable conflict it is: first-committer-wins (design 5).
   */
  static SQLException asRetryable(SQLException e) {
    if (e.getErrorCode() == 3960 && String.valueOf(e.getSQLState()).startsWith("S0")) {
      return new SQLTransactionRollbackException(e.getMessage(), "40001", 3960, e);
    }
    return e;
  }

  /** An Oracle constraint as its messages name it, {@code (OWNER.NAME)}. */
  private static final Pattern ORA_CONSTRAINT =
      Pattern.compile("\\(([A-Za-z0-9_$#]+)\\.([A-Za-z0-9_$#]+)\\)");

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
   * @param rollbackOnly marks the application's transaction rollback-only
   * @param work the writes
   * @throws SQLException a database error, a timeout, or {@code 40001} for an abort the driver
   *     decides
   */
  // ponytail: autocommit is off on the vendor connection while the write runs; another thread
  // sharing the connection then joins its transaction, as with any JDBC transaction
  static void within(Connection vendor, Runnable rollbackOnly, Work work) throws SQLException {
    if (vendor.isReadOnly()) {
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
    final @Nullable Step afterImages;
    final @Nullable Step locked;
    final Step keys;
    final Step dml;
    final boolean predicate;

    KeyedPlan(
        Names n,
        boolean delete,
        boolean imported,
        @Nullable Step afterImages,
        @Nullable Step locked,
        Step keys,
        Step dml,
        boolean predicate) {
      super(n);
      this.delete = delete;
      this.imported = imported;
      this.afterImages = afterImages;
      this.locked = locked;
      this.keys = keys;
      this.dml = dml;
      this.predicate = predicate;
    }

    @Override
    List<String> sql() {
      List<String> out = new ArrayList<>();
      for (Step s : Arrays.asList(afterImages, locked, keys, dml)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }

    @Override
    long apply(Run run) throws SQLException {
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
      Map<List<Object>, Anchors.Version> out =
          versions(
              run,
              n,
              keys,
              n.engine == Engine.MARIADB
                  ? "LOCK IN SHARE MODE"
                  : n.engine == Engine.SQLSERVER ? "REPEATABLEREAD, ROWLOCK" : "FOR SHARE");
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
                      + n.keysOf(keys.types, chunk.size())))) {
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
    // on MySQL, which has no RETURNING, each row's key read back by its values; none for an
    // AUTO_INCREMENT key the statement leaves out, which the vendor driver reports
    final List<Step> readBack;
    // whether that key is AUTO_INCREMENT, asked once
    private volatile @Nullable Boolean autoIncrement;
    final @Nullable Step inFlight;
    // at SNAPSHOT and SERIALIZABLE, the keys committed in the snapshot
    final @Nullable Step committed;
    // at those levels, for several rows: whether the statement repeats a key
    final @Nullable Step repeats;
    final @Nullable String returning;
    final String exposed;

    InsertPlan(
        Names n,
        Step insert,
        List<Step> readBack,
        @Nullable Step inFlight,
        @Nullable Step committed,
        @Nullable Step repeats,
        @Nullable String returning,
        String exposed) {
      super(n);
      this.insert = insert;
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
      for (Step s : Arrays.asList(inFlight, committed, repeats)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
    }

    @SuppressFBWarnings(
        value = "OBL_UNSATISFIED_OBLIGATION",
        justification = "the rows are handed to the application, closed with their statement")
    @Override
    long apply(Run run) throws SQLException {
      int width = n.keyColumns.size();
      Keys keys = new Keys();
      PreparedStatement ps = null;
      ResultSet rs = null;
      Savepoint sp = run.v.setSavepoint();
      try {
        if (generated()) {
          if (!autoIncrement(run)) {
            throw Errors.unsupported(
                "an INSERT of a ScalarDB table that leaves out its key needs an AUTO_INCREMENT"
                    + " or IDENTITY key on this engine");
          }
          // ponytail: the vendor driver numbers the rows from the first value by the increment it
          // read at connect; a session that changes auto_increment_increment gets wrong keys
          ps =
              run.prepare(
                  insert,
                  null,
                  false,
                  n.engine == Engine.ORACLE ? n.keyColumns.toArray(new String[0]) : new String[0]);
          long count = ps.executeLargeUpdate();
          try (ResultSet k = ps.getGeneratedKeys()) {
            while (k.next()) {
              keys.read(k, 1, width);
            }
          }
          if (keys.rows.size() != count) {
            throw new SQLException(
                "the database reported " + keys.rows.size() + " keys for " + count + " rows",
                "0AN01");
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
        if (!keyViolated(run, e)) {
          throw e; // another unique constraint: the application's duplicate (todo 178)
        }
        try (PreparedStatement check = run.prepare(inFlight, null);
            ResultSet r = check.executeQuery()) {
          r.next();
          if (r.getLong(1) > 0) {
            Aborted a = new Aborted("a key the statement inserts is being written by ScalarDB");
            a.addSuppressed(e);
            throw a;
          }
        }
        if (committed != null && !repeatsKey(run)) {
          try (PreparedStatement check = run.prepare(committed, null);
              ResultSet r = check.executeQuery()) {
            r.next();
            if (r.getLong(1) == 0) {
              Aborted a = new Aborted("a key the statement inserts was written after the snapshot");
              a.addSuppressed(e);
              throw a;
            }
          }
        }
        throw e; // the application's duplicate
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

    /** Whether the database generates the key: MySQL or Oracle, keys not read back. */
    private boolean generated() {
      return (n.engine == Engine.MYSQL || n.engine == Engine.ORACLE) && readBack.isEmpty();
    }

    private boolean autoIncrement(Run run) throws SQLException {
      Boolean known = autoIncrement;
      if (known == null) {
        try (PreparedStatement ps =
            run.v.prepareStatement(
                n.engine == Engine.ORACLE
                    ? "SELECT 1 FROM all_tab_identity_cols WHERE owner = ? AND table_name = ? AND"
                        + " column_name = ?"
                    : "SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND"
                        + " table_name = ? AND column_name = ? AND extra LIKE '%auto_increment%'")) {
          ps.setString(1, n.namespace);
          ps.setString(2, n.dataTable);
          ps.setString(3, n.keyColumns.get(0));
          try (ResultSet rs = ps.executeQuery()) {
            known = rs.next();
          }
        }
        autoIncrement = known;
      }
      return known;
    }

    /**
     * Whether the duplicate is of the data table's primary key: the constraint pgjdbc reports, read
     * without depending on pgjdbc, or the key MySQL's message names; true when it cannot be told.
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
      int stamped = stampFromData(run, n, keys);
      if (stamped == keys.rows.size()) {
        return;
      }
      // a metadata row with no data row is an orphan, a tombstone: taken over in place (4.5)
      int taken = 0;
      int size = chunk(n);
      for (int from = 0; from < keys.rows.size(); from += size) {
        List<Object[]> chunk = keys.rows.subList(from, Math.min(keys.rows.size(), from + size));
        // on MySQL and MariaDB the keys drive the update, as dataOf (todo 220), and are bound first
        boolean pg = !n.lookups();
        try (PreparedStatement ps =
            run.prepare(
                n.stamp(
                        pg
                            ? n.meta
                            : n.keyValues(keys.types, chunk.size())
                                + " STRAIGHT_JOIN "
                                + n.meta
                                + " ON "
                                + n.keyJoinValues(n.meta),
                        "COALESCE(" + n.txVersion + ", 0) + 1")
                    + "EXISTS (SELECT 1 FROM "
                    + n.data
                    + " d WHERE "
                    + n.keyJoin(n.meta, "d")
                    + (pg ? " AND " + n.keyIn("d", keys.types, chunk.size()) : "")
                    + ") AND "
                    + n.distinctFrom(n.txId)
                    + " AND "
                    + n.committed())) {
          int i = pg ? bindAll(ps, run.stamp(ps, 1), chunk) : run.stamp(ps, bindAll(ps, 1, chunk));
          ps.setString(i, run.txId);
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
        // ojdbc reads a name as Oracle reads it unquoted, folded to upper case
        boolean folds = plan.n.engine == Engine.ORACLE && c.matches("[A-Za-z][A-Za-z0-9_$#]*");
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
                + " "
                + q
                + " WHERE 1 = 0"
            : "SELECT "
                + (items.equals("*") ? q + ".*" : items)
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

    LockPlan(
        Names n,
        Step lock,
        @Nullable Step inFlight,
        boolean noWait,
        @Nullable String relock,
        @Nullable Step snapshot) {
      super(n);
      this.lock = lock;
      this.inFlight = inFlight;
      this.noWait = noWait;
      this.relock = relock;
      this.snapshot = snapshot;
    }

    @Override
    boolean returnsRows() {
      return true;
    }

    @Override
    List<String> sql() {
      List<String> out = new ArrayList<>();
      for (Step s : Arrays.asList(inFlight, lock, snapshot)) {
        if (s != null) {
          out.add(s.sql);
        }
      }
      return out;
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
          // that
          // READPAST did not skip, which holds off its ScalarDB commit: every lock goes with the
          // savepoint, and the rows returned are locked again by key, at the versions returned
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
      // and the metadata row under the lock too, which SNAPSHOT would read in its snapshot
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
      return withMeta(data + " d", keyQualifier, where);
    }

    /** As {@link #withMeta(String, String)}, the data table as d in {@code source}. */
    String withMeta(String source, String keyQualifier, String where) {
      return withMeta(source, "", keyQualifier, where);
    }

    /** As {@link #withMeta(String, String, String)}, the metadata table under {@code hint}. */
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
      // IGNORE also turns data errors into warnings, which rows the driver builds do not have
      boolean pg = engine == Engine.POSTGRESQL;
      boolean mssql = engine == Engine.SQLSERVER;
      boolean oracle = engine == Engine.ORACLE;
      // Oracle's hint skips a key that has a row, waiting on one being inserted, as IGNORE does
      return (pg || mssql
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
          + (pg ? " ON CONFLICT DO NOTHING" : "")
          // rows from d, with their WHERE: a key with a metadata row, or one being inserted, is
          // left out, its range held until the end (SQL Server has no insert that skips one)
          + (mssql
              ? " AND NOT EXISTS (SELECT 1 FROM "
                  + meta
                  + " x WITH (UPDLOCK, HOLDLOCK) WHERE "
                  + keyJoin("x", "d")
                  + ")"
              : "");
    }
  }
}
