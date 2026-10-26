package com.scalar.db.nativejdbc.sql;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DateTimeLiteralExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.IntegerDivision;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression;
import net.sf.jsqlparser.expression.operators.relational.IsDistinctExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ForMode;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import org.jspecify.annotations.Nullable;

/**
 * A statement over exactly one table, in one of the shapes the driver's rewrite rules handle: a
 * single-table {@code SELECT}, an {@code INSERT ... VALUES}, an {@code UPDATE} or a {@code DELETE}.
 *
 * <p>{@link #of} returns empty for every other shape: several tables, a join, a subquery over a
 * table, a set operation, a CTE, {@code INSERT ... SELECT}, {@code MERGE}, a multi-column {@code
 * SET}, a column qualified by anything but this table's name or alias, a second statement after a
 * {@code ;}, the modifiers that change what a statement does ({@code INSERT IGNORE} and {@code
 * UPDATE IGNORE}, {@code ON DUPLICATE KEY UPDATE}, {@code ON CONFLICT}, an {@code OUTPUT} clause,
 * {@code SKIP LOCKED}, {@code NOWAIT} and {@code WAIT}), an optimizer hint, a table hint and {@code
 * TABLESAMPLE}, a database link, a clause that reads or writes something other than the table's
 * rows ({@code USING}, {@code FOR PORTION OF}, {@code CONNECT BY}, {@code QUALIFY}, {@code LATERAL
 * VIEW}, {@code FOR XML}, {@code INTO OUTFILE}, a temporary target table, {@code PREWHERE}), and a
 * clause without a syntax tree. The caller decides what empty means: a read goes to the
 * committed-image view, a write is refused.
 *
 * <p>Values are never interpreted here. The native database evaluates every literal and expression;
 * the driver only needs the structure: which table, which columns, where the predicate is, and
 * whether the predicate addresses the table by key.
 */
public final class StatementShape {

  /** The kind of statement. */
  public enum Kind {
    /** A single-table {@code SELECT}. */
    SELECT,
    /** An {@code INSERT ... VALUES}. */
    INSERT,
    /** An {@code UPDATE}. */
    UPDATE,
    /** A {@code DELETE}. */
    DELETE
  }

  private final SqlAst ast;
  private final Engine engine;
  private final Kind kind;
  private final SqlAst.TableRef table;
  private final boolean selectAll;
  private final List<String> selectColumns;
  private final boolean grouped;
  private final boolean ordered;
  private final List<String> orderByColumns;
  private final boolean orderByPure;
  private final boolean limited;

  /** SQL Server's table hints a locking read of a ScalarDB table takes. */
  public static final Set<String> LOCK_HINTS =
      Set.of(
          "UPDLOCK",
          "XLOCK",
          "HOLDLOCK",
          "SERIALIZABLE",
          "REPEATABLEREAD",
          "ROWLOCK",
          "PAGLOCK",
          "READPAST",
          "NOWAIT");

  private final @Nullable ForMode lockMode;
  private final boolean skipLocked;
  private final boolean noWait;
  private final @Nullable Expression where;
  private final List<String> whereColumns;
  private final List<String> insertColumns;
  private final int insertRowCount;
  private final List<String> setColumns;
  private final boolean returning;

  private StatementShape(Builder b) {
    this.ast = b.ast;
    this.engine = b.engine;
    this.kind = b.kind;
    this.table = b.table;
    this.selectAll = b.selectAll;
    this.selectColumns = List.copyOf(b.selectColumns);
    this.grouped = b.grouped;
    this.ordered = b.ordered;
    this.orderByColumns = List.copyOf(b.orderByColumns);
    this.orderByPure = b.orderByPure;
    this.limited = b.limited;
    this.lockMode = b.lockMode;
    this.skipLocked = b.skipLocked;
    this.noWait = b.noWait;
    this.where = b.where;
    this.whereColumns = List.copyOf(b.whereColumns);
    this.insertColumns = List.copyOf(b.insertColumns);
    this.insertRowCount = b.insertRowCount;
    this.setColumns = List.copyOf(b.setColumns);
    this.returning = b.returning;
  }

  /**
   * Classifies a statement.
   *
   * @param sql one statement
   * @param engine the engine whose identifier rules resolve the column names
   * @return the shape, or empty when the statement is not one of the single-table shapes
   */
  public static Optional<StatementShape> of(String sql, Engine engine) {
    return SqlAst.parse(sql, engine).flatMap(StatementShape::of);
  }

  /**
   * Classifies a parsed statement as {@link #of(SqlAst)} does, and says why when it is not one of
   * the single-table shapes.
   *
   * @param ast the statement
   * @return the shape, or the reason the statement is not one
   */
  public static Outcome<StatementShape> classify(SqlAst ast) {
    Engine engine = ast.engine();
    if (ast.tableRefs().size() != 1) {
      return Outcome.refused(
          ast.tableRefs().isEmpty()
              ? "the statement names no table"
              : "the statement reads " + ast.tableRefs().size() + " tables");
    }
    if (!ast.cteRefs().isEmpty()) {
      return Outcome.refused("the statement has a WITH clause");
    }
    SqlAst.TableRef table = ast.tableRefs().get(0);
    if (table.partCount() > 2) {
      return Outcome.refused("the table name has more than a schema and a table");
    }
    if (table.hasHintOrLink()) {
      return Outcome.refused(
          "the table carries a hint, a sample clause, a PIVOT or a database link");
    }
    // SQL Server's locking hints, which stand for a locking clause, and nothing else (4.7)
    List<String> hints = ast.hints(table);
    if (!LOCK_HINTS.containsAll(hints)) {
      return Outcome.refused(
          "only the table hints "
              + String.join(", ", new TreeSet<>(LOCK_HINTS))
              + " are supported on a ScalarDB table");
    }
    if (!hints.isEmpty() && !(ast.statement() instanceof PlainSelect)) {
      return Outcome.refused("a table hint on a ScalarDB table is supported in a SELECT only");
    }
    Builder b = new Builder(ast, table, engine);
    if (!b.resolveColumnQualifiers()) {
      return Outcome.refused(b.refusal);
    }
    Statement stmt = ast.statement();
    boolean ok;
    if (stmt instanceof PlainSelect) {
      ok = b.select((PlainSelect) stmt);
    } else if (stmt instanceof Insert) {
      ok = b.insert((Insert) stmt);
    } else if (stmt instanceof Update) {
      ok = b.update((Update) stmt);
    } else if (stmt instanceof Delete) {
      ok = b.delete((Delete) stmt);
    } else {
      ok =
          b.refuse(
              "a " + stmt.getClass().getSimpleName() + " statement is not a single-table shape");
    }
    return ok ? Outcome.of(new StatementShape(b)) : Outcome.refused(b.refusal);
  }

  /**
   * Classifies a parsed statement. Column names are resolved the way the statement's engine
   * resolves them ({@link Engine#resolveIdentifier}) and compared the way it compares them ({@link
   * Engine#columnKey}), so that {@code Amount} and {@code amount} are one column on MySQL and two
   * on PostgreSQL.
   *
   * @param ast the statement
   * @return the shape, or empty when the statement is not one of the single-table shapes
   */
  public static Optional<StatementShape> of(SqlAst ast) {
    return classify(ast).toOptional();
  }

  /**
   * Returns the parsed statement.
   *
   * @return the AST
   */
  public SqlAst ast() {
    return ast;
  }

  /**
   * Returns the kind of statement.
   *
   * @return the kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the one table the statement addresses.
   *
   * @return the table reference
   */
  public SqlAst.TableRef table() {
    return table;
  }

  /**
   * Returns whether a {@code SELECT} projects every column, {@code *} or {@code t.*}.
   *
   * @return whether the projection is all columns
   */
  public boolean selectAll() {
    return selectAll;
  }

  /**
   * Returns the resolved column names a {@code SELECT} projects when every select item is a plain
   * column; empty for {@code *} and for a projection with an expression, a function or an alias.
   *
   * @return the projected columns
   */
  public List<String> selectColumns() {
    return selectColumns;
  }

  /**
   * Returns whether a {@code SELECT} has {@code DISTINCT}, {@code GROUP BY}, {@code HAVING} or a
   * function in its projection, which the rewrite applies on top of the two-arm union.
   *
   * @return whether the select aggregates or groups
   */
  public boolean grouped() {
    return grouped;
  }

  /**
   * Returns whether a {@code SELECT} has {@code ORDER BY}.
   *
   * @return whether it is ordered
   */
  public boolean ordered() {
    return ordered;
  }

  /**
   * Returns the columns the {@code ORDER BY} clause names, as the engine resolves them, in order of
   * appearance and without duplicates; empty without one, or where the clause cannot be inspected,
   * which {@link #orderByPure()} says.
   *
   * @return the clause's columns
   */
  public List<String> orderByColumns() {
    return orderByColumns;
  }

  /**
   * Returns whether the {@code ORDER BY} clause is built of columns alone, each of them known, so
   * that the order of the rows depends on nothing but their columns. True without a clause.
   *
   * @return whether the clause is columns alone
   */
  public boolean orderByPure() {
    return orderByPure;
  }

  /**
   * Returns whether a {@code SELECT} has {@code LIMIT}, {@code OFFSET} or {@code FETCH}.
   *
   * @return whether it is limited
   */
  public boolean limited() {
    return limited;
  }

  /**
   * Returns the locking clause of a {@code SELECT}, {@code FOR UPDATE} or {@code FOR SHARE}, or
   * {@code null} when there is none.
   *
   * @return the lock mode, or {@code null}
   */
  public @Nullable ForMode lockMode() {
    return lockMode;
  }

  /**
   * Returns whether the locking clause skips rows locked by others, {@code SKIP LOCKED}.
   *
   * @return whether it skips them
   */
  public boolean skipLocked() {
    return skipLocked;
  }

  /**
   * Returns whether the locking clause fails at once on a row locked by others, {@code NOWAIT}.
   *
   * @return whether it does not wait
   */
  public boolean noWait() {
    return noWait;
  }

  /**
   * Returns the {@code WHERE} expression, or {@code null} when there is none.
   *
   * @return the predicate, or {@code null}
   */
  public @Nullable Expression where() {
    return where;
  }

  /**
   * Returns the resolved names of the columns the {@code WHERE} clause references, in order of
   * appearance and without duplicates.
   *
   * @return the predicate's columns
   */
  public List<String> whereColumns() {
    return whereColumns;
  }

  /**
   * Returns whether the {@code WHERE} clause is built of columns, placeholders, literals,
   * operators, CAST and CASE alone, so that it gives a row the same answer in every statement of a
   * transaction: no function, no subquery, no keyword the engine evaluates as the statement runs,
   * such as {@code CURRENT_TIMESTAMP}. A clause the tree does not show is not pure. True without a
   * {@code WHERE} clause.
   *
   * @return whether the predicate is pure
   */
  public boolean wherePure() {
    return where == null || pure(where, false);
  }

  /**
   * Returns whether an expression is pure as {@link #wherePure()} says, or, with {@code
   * subqueries}, built of such expressions and of IN and EXISTS subqueries over tables whose own
   * clauses are such expressions: a join's ON clause, or a WHERE clause that semi-joins another
   * table.
   *
   * @param e the expression
   * @param subqueries whether a subquery may stand in it
   * @return whether it is pure
   */
  public static boolean pure(Expression e, boolean subqueries) {
    Set<Object> nodes = SqlAst.valuesUnder(e);
    if (nodes == null) {
      return false;
    }
    for (Object n : nodes) {
      if (subqueries && SUBQUERY_PARTS.contains(n.getClass())) {
        continue;
      }
      boolean pure =
          n instanceof Column
              || n instanceof Table
              || n instanceof JdbcParameter
              || n instanceof JdbcNamedParameter
              || n instanceof LongValue
              || n instanceof DoubleValue
              || n instanceof StringValue
              || n instanceof HexValue
              || n instanceof NullValue
              || n instanceof BooleanValue
              || n instanceof DateValue
              || n instanceof TimeValue
              || n instanceof TimestampValue
              || n instanceof DateTimeLiteralExpression
              || n instanceof SignedExpression
              || n instanceof NotExpression
              || n instanceof CastExpression
              || n instanceof CaseExpression
              || n instanceof WhenClause
              || PURE_OPERATORS.contains(n.getClass());
      if (!pure) {
        return false;
      }
    }
    return true;
  }

  /** What a subquery adds to a pure expression's tree: the subquery itself, its FROM and SELECT. */
  private static final Set<Class<?>> SUBQUERY_PARTS =
      Set.of(
          ExistsExpression.class,
          ParenthesedSelect.class,
          PlainSelect.class,
          SelectItem.class,
          AllColumns.class,
          Alias.class);

  /**
   * The operators a pure predicate may use, by exact class: not the rest of the parser's operators,
   * among them full-text MATCH, which needs the base table's index, Oracle's (+) and JSON paths.
   */
  private static final Set<Class<?>> PURE_OPERATORS =
      Set.of(
          AndExpression.class,
          OrExpression.class,
          EqualsTo.class,
          NotEqualsTo.class,
          GreaterThan.class,
          GreaterThanEquals.class,
          MinorThan.class,
          MinorThanEquals.class,
          Between.class,
          InExpression.class,
          IsNullExpression.class,
          IsBooleanExpression.class,
          IsDistinctExpression.class,
          LikeExpression.class,
          ExpressionList.class,
          ParenthesedExpressionList.class,
          Addition.class,
          Subtraction.class,
          Multiplication.class,
          Division.class,
          IntegerDivision.class,
          Modulo.class,
          Concat.class);

  /**
   * Returns the resolved names in an {@code INSERT}'s column list; empty when the statement has
   * none.
   *
   * @return the insert columns
   */
  public List<String> insertColumns() {
    return insertColumns;
  }

  /**
   * Returns the number of {@code VALUES} rows of an {@code INSERT}.
   *
   * @return the row count
   */
  public int insertRowCount() {
    return insertRowCount;
  }

  /**
   * Returns the one row of an INSERT's VALUES, parenthesized, or null for an INSERT of several rows
   * or another statement.
   *
   * @return the row
   */
  public @Nullable Expression insertRow() {
    if (kind != Kind.INSERT) {
      return null;
    }
    ExpressionList<?> top = ((Values) ((Insert) ast.statement()).getSelect()).getExpressions();
    return top instanceof ParenthesedExpressionList<?> ? (Expression) top : null;
  }

  /**
   * Returns, per {@code VALUES} row of an {@code INSERT}, the values of the given key columns in
   * their order, when every row gives each of them as a placeholder or a literal.
   *
   * @param keyColumns the table's key columns as the catalog spells them
   * @param tableColumns the data table's columns in its own order, which a statement that lists no
   *     columns gives values in (the catalog's order need not be the table's, todo 188), or {@code
   *     null} when not known
   * @return the key values per row, or {@code null} when a key column is not listed, the statement
   *     lists no columns and the table's are not known, or a value is anything else
   */
  public @Nullable List<List<Expression>> insertKeyValues(
      List<String> keyColumns, @Nullable List<String> tableColumns) {
    List<String> columns = insertColumns.isEmpty() ? tableColumns : insertColumns;
    if (kind != Kind.INSERT || columns == null || columns.isEmpty()) {
      return null;
    }
    List<Integer> at = new ArrayList<>();
    for (String k : keyColumns) {
      int found = -1;
      for (int i = 0; i < columns.size(); i++) {
        if (engine.columnKey(columns.get(i)).equals(engine.columnKey(k))) {
          found = i;
        }
      }
      if (found < 0) {
        return null;
      }
      at.add(found);
    }
    ExpressionList<?> top = ((Values) ((Insert) ast.statement()).getSelect()).getExpressions();
    List<ExpressionList<?>> rows = new ArrayList<>();
    if (top instanceof ParenthesedExpressionList<?>) {
      rows.add(top);
    } else {
      for (Object row : top) {
        rows.add((ExpressionList<?>) row);
      }
    }
    List<List<Expression>> out = new ArrayList<>();
    for (ExpressionList<?> row : rows) {
      if (row.size() != columns.size()) {
        return null;
      }
      List<Expression> keys = new ArrayList<>();
      for (int i : at) {
        Expression v = (Expression) row.get(i);
        if (!isConstant(v)) {
          return null;
        }
        keys.add(v);
      }
      out.add(keys);
    }
    return out;
  }

  /**
   * Returns the resolved names of the columns an {@code UPDATE} assigns.
   *
   * @return the assigned columns
   */
  public List<String> setColumns() {
    return setColumns;
  }

  /**
   * Returns whether the statement has a {@code RETURNING} clause.
   *
   * @return whether it returns rows
   */
  public boolean returning() {
    return returning;
  }

  /**
   * Returns whether the {@code WHERE} clause addresses exactly the given key: a conjunction of
   * {@code column = value} terms, one per key column and nothing else, where each value is a
   * placeholder or a literal, possibly signed or cast. Such a statement is a key read or a key
   * write; {@code id = id + 0}, {@code id = (select ...)}, {@code id = f(...)} and {@code id =
   * null} are not.
   *
   * @param keyColumns the table's key columns as the catalog spells them
   * @return whether the predicate is a key equality
   */
  public boolean isKeyEquality(List<String> keyColumns) {
    if (where == null) {
      return false;
    }
    List<Expression> terms = new ArrayList<>();
    flattenAnd(where, terms);
    Set<String> seen = new LinkedHashSet<>();
    Set<String> keys = new LinkedHashSet<>();
    for (String k : keyColumns) {
      keys.add(engine.columnKey(k));
    }
    for (Expression term : terms) {
      if (!(term instanceof EqualsTo)) {
        return false;
      }
      EqualsTo eq = (EqualsTo) term;
      Column c;
      Expression other;
      if (eq.getLeftExpression() instanceof Column) {
        c = (Column) eq.getLeftExpression();
        other = eq.getRightExpression();
      } else if (eq.getRightExpression() instanceof Column) {
        c = (Column) eq.getRightExpression();
        other = eq.getLeftExpression();
      } else {
        return false;
      }
      if (!isConstant(other)
          || !seen.add(engine.columnKey(engine.resolveIdentifier(c.getColumnName())))) {
        return false; // not a value the engine can bind as a key, or the same key column twice
      }
    }
    return seen.equals(keys);
  }

  /** A placeholder or a literal, possibly signed or cast; everything else fails closed. */
  private static boolean isConstant(Expression e) {
    if (e instanceof SignedExpression) {
      return isConstant(((SignedExpression) e).getExpression());
    }
    if (e instanceof CastExpression) {
      return isConstant(((CastExpression) e).getLeftExpression());
    }
    return e instanceof JdbcParameter
        || e instanceof JdbcNamedParameter
        || e instanceof LongValue
        || e instanceof DoubleValue
        || e instanceof StringValue
        || e instanceof DateValue
        || e instanceof TimeValue
        || e instanceof TimestampValue
        || e instanceof HexValue;
  }

  private static boolean nonEmpty(@Nullable List<?> list) {
    return list != null && !list.isEmpty();
  }

  private static void flattenAnd(Expression e, List<Expression> out) {
    if (e instanceof AndExpression) {
      AndExpression and = (AndExpression) e;
      flattenAnd(and.getLeftExpression(), out);
      flattenAnd(and.getRightExpression(), out);
    } else {
      out.add(e);
    }
  }

  /** Reads the clauses of one statement; every reader returns {@code false} to refuse the shape. */
  private static final class Builder {
    final SqlAst ast;
    final SqlAst.TableRef table;
    final Engine engine;
    Kind kind = Kind.SELECT;
    boolean selectAll;
    List<String> selectColumns = List.of();
    boolean grouped;
    boolean ordered;
    List<String> orderByColumns = List.of();
    boolean orderByPure = true;
    boolean limited;
    @Nullable ForMode lockMode;
    boolean skipLocked;
    boolean noWait;
    @Nullable Expression where;
    List<String> whereColumns = List.of();
    List<String> insertColumns = List.of();
    int insertRowCount;
    List<String> setColumns = List.of();
    boolean returning;

    String refusal = "not a single-table shape";

    boolean refuse(String why) {
      refusal = why;
      return false;
    }

    /** The reason of the first condition that holds, from (condition, reason) pairs, or null. */
    static @Nullable String first(Object... conditionThenReason) {
      for (int i = 0; i < conditionThenReason.length; i += 2) {
        if ((Boolean) conditionThenReason[i]) {
          return (String) conditionThenReason[i + 1];
        }
      }
      return null;
    }

    Builder(SqlAst ast, SqlAst.TableRef table, Engine engine) {
      this.ast = ast;
      this.table = table;
      this.engine = engine;
    }

    /**
     * Every qualified column must name this table, by its alias, its name or its schema and name.
     */
    boolean resolveColumnQualifiers() {
      String alias = table.alias();
      for (SqlAst.ColumnRef c : ast.columnRefs()) {
        SqlAst.TableRef q = c.qualifier();
        if (q == null) {
          continue;
        }
        if (q.partCount() > 2) {
          return refuse("the column " + c + " is qualified by more than a schema and a table");
        }
        String qualifierSchema = q.schema();
        String tableSchema = table.schema();
        boolean byAlias =
            alias != null && qualifierSchema == null && sameIdentifier(q.name(), alias);
        boolean byName =
            sameIdentifier(q.name(), table.name())
                && (qualifierSchema == null
                    || (tableSchema != null && sameIdentifier(qualifierSchema, tableSchema)));
        if (!byAlias && !byName) {
          return refuse("the column " + c + " is qualified by a name that is not this table's");
        }
      }
      return true;
    }

    boolean select(PlainSelect s) {
      kind = Kind.SELECT;
      FromItem from = s.getFromItem();
      String why =
          first(
              !(from instanceof Table),
              "the FROM item is not a table",
              nonEmpty(s.getJoins()),
              "the statement has a join",
              nonEmpty(s.getIntoTables()),
              "SELECT ... INTO",
              nonEmpty(s.getWithItemsList()),
              "the statement has a WITH clause",
              s.getTop() != null,
              "TOP",
              s.getOracleHint() != null,
              "an optimizer hint",
              s.getWait() != null,
              "WAIT",
              // clauses that read or write something other than the table's rows
              s.getOracleHierarchical() != null,
              "CONNECT BY",
              s.getQualify() != null,
              "QUALIFY",
              nonEmpty(s.getLateralViews()),
              "LATERAL VIEW",
              s.getForXmlPath() != null || s.getForClause() != null,
              "FOR XML or FOR JSON",
              s.getMySqlSelectIntoClause() != null,
              "INTO OUTFILE or INTO DUMPFILE",
              s.getIntoTempTable() != null,
              "a temporary target table",
              s.getPreWhere() != null,
              "PREWHERE");
      if (why != null) {
        return refuse(why + " is not supported on a ScalarDB table");
      }
      List<SelectItem<?>> items = s.getSelectItems();
      if (items == null || items.isEmpty()) {
        return refuse("the select list is empty");
      }
      boolean functions = false;
      List<String> columns = new ArrayList<>();
      boolean plain = true;
      for (SelectItem<?> item : items) {
        Expression e = item.getExpression();
        if (e instanceof AllColumns || e instanceof AllTableColumns) {
          if (items.size() != 1 || item.getAlias() != null) {
            return refuse("* is combined with other select items or an alias");
          }
          selectAll = true;
          plain = false;
        } else if (e instanceof Column && item.getAlias() == null) {
          columns.add(engine.resolveIdentifier(((Column) e).getColumnName()));
        } else {
          plain = false;
          if (containsFunction(e)) {
            functions = true;
          }
        }
      }
      selectColumns = plain ? columns : List.of();
      grouped =
          functions || s.getDistinct() != null || s.getGroupBy() != null || s.getHaving() != null;
      ordered = s.getOrderByElements() != null && !s.getOrderByElements().isEmpty();
      if (ordered) {
        Set<String> names = new LinkedHashSet<>();
        for (OrderByElement o : s.getOrderByElements()) {
          Expression e = o.getExpression();
          Set<Object> nodes = e instanceof Column ? SqlAst.valuesUnder(e) : null;
          if (nodes == null) {
            orderByPure = false;
            continue;
          }
          for (SqlAst.ColumnRef c : ast.columnRefs()) {
            if (c.isAmong(nodes)) {
              names.add(engine.resolveIdentifier(c.name()));
            }
          }
        }
        orderByColumns = new ArrayList<>(names);
      }
      limited = s.getLimit() != null || s.getOffset() != null || s.getFetch() != null;
      lockMode = s.getForMode();
      skipLocked = s.isSkipLocked();
      noWait = s.isNoWait();
      List<String> hints = ast.hints(table);
      if (!hints.isEmpty()) {
        // SQL Server: UPDLOCK and XLOCK as FOR UPDATE, the shared ones held as FOR SHARE
        lockMode =
            hints.contains("UPDLOCK") || hints.contains("XLOCK")
                ? ForMode.UPDATE
                : hints.contains("HOLDLOCK")
                        || hints.contains("SERIALIZABLE")
                        || hints.contains("REPEATABLEREAD")
                    ? ForMode.SHARE
                    : null;
        skipLocked = hints.contains("READPAST");
        noWait = hints.contains("NOWAIT");
        if (lockMode == null && (skipLocked || noWait)) {
          return refuse("READPAST and NOWAIT go with a locking hint, UPDLOCK or HOLDLOCK");
        }
      }
      if (s.getForUpdateTables() != null) {
        for (Table target : s.getForUpdateTables()) {
          // FOR UPDATE OF names this table, by its alias or its name, or nothing this shape knows;
          // on Oracle it names a column, alone or by this table's alias or name, which locks the
          // one table the read has
          String self = table.alias() != null ? table.alias() : table.name();
          String named = engine == Engine.ORACLE ? target.getSchemaName() : target.getName();
          boolean ok =
              engine == Engine.ORACLE
                  ? named == null || sameIdentifier(named, self)
                  : target.getSchemaName() == null && named != null && sameIdentifier(named, self);
          if (!ok) {
            return refuse("FOR UPDATE OF " + target + " does not name this table");
          }
        }
      }
      return predicate(s.getWhere());
    }

    boolean insert(Insert i) {
      kind = Kind.INSERT;
      String why =
          first(
              table.hasAlias(),
              "an aliased INSERT target",
              !(i.getSelect() instanceof Values),
              "INSERT ... SELECT",
              nonEmpty(i.getSetUpdateSets()),
              "INSERT ... SET",
              // an upsert in disguise: a collision looks like success
              i.isModifierIgnore(),
              "INSERT IGNORE",
              nonEmpty(i.getDuplicateUpdateSets()),
              "ON DUPLICATE KEY UPDATE",
              i.getConflictTarget() != null || i.getConflictAction() != null,
              "ON CONFLICT",
              i.getOutputClause() != null,
              "OUTPUT",
              i.getOracleHint() != null,
              "an optimizer hint");
      if (why != null) {
        return refuse(why + " is not supported on a ScalarDB table");
      }
      ExpressionList<Column> cols = i.getColumns();
      if (cols != null) {
        List<String> names = new ArrayList<>();
        for (Column c : cols) {
          if (c.getTable() != null) {
            return refuse("the INSERT column " + c + " is qualified");
          }
          names.add(engine.resolveIdentifier(c.getColumnName()));
        }
        insertColumns = names;
      }
      ExpressionList<?> top = ((Values) i.getSelect()).getExpressions();
      int rows = 0;
      if (top instanceof ParenthesedExpressionList<?>) {
        rows = 1;
      } else {
        for (Object row : top) {
          if (!(row instanceof ParenthesedExpressionList<?>)) {
            return refuse("a VALUES row is not a parenthesised list");
          }
          rows++;
        }
      }
      insertRowCount = rows;
      returning = i.getReturningClause() != null;
      return rows > 0 || refuse("the INSERT has no VALUES rows");
    }

    boolean update(Update u) {
      kind = Kind.UPDATE;
      String why =
          first(
              u.getFromItem() != null || nonEmpty(u.getJoins()) || nonEmpty(u.getStartJoins()),
              "UPDATE with FROM or a join",
              u.getLimit() != null,
              "UPDATE ... LIMIT",
              nonEmpty(u.getOrderByElements()),
              "UPDATE ... ORDER BY",
              u.isModifierIgnore(),
              "UPDATE IGNORE",
              u.getOutputClause() != null,
              "OUTPUT",
              u.getOracleHint() != null,
              "an optimizer hint",
              u.getForPortionClause() != null,
              "FOR PORTION OF");
      if (why != null) {
        return refuse(why + " is not supported on a ScalarDB table");
      }
      List<UpdateSet> sets = u.getUpdateSets();
      if (sets == null || sets.isEmpty()) {
        return refuse("the UPDATE sets no column");
      }
      List<String> names = new ArrayList<>();
      for (UpdateSet set : sets) {
        if (set.getColumns().size() != 1 || set.getValues().size() != 1) {
          return refuse("SET (a, b) = (...) is not supported; set one column at a time");
        }
        names.add(engine.resolveIdentifier(set.getColumn(0).getColumnName()));
      }
      setColumns = names;
      returning = u.getReturningClause() != null;
      return predicate(u.getWhere());
    }

    boolean delete(Delete d) {
      kind = Kind.DELETE;
      // a USING item or a join reads other rows: a table is caught by the single-table check, a
      // subquery or a VALUES list is not, so the list itself is refused
      String why =
          first(
              nonEmpty(d.getTables()) || nonEmpty(d.getJoins()),
              "DELETE with a join",
              nonEmpty(d.getUsingFromItemList()),
              "DELETE ... USING",
              d.getLimit() != null,
              "DELETE ... LIMIT",
              nonEmpty(d.getOrderByElements()),
              "DELETE ... ORDER BY",
              d.isModifierIgnore(),
              "DELETE IGNORE",
              d.getOutputClause() != null,
              "OUTPUT",
              d.getOracleHint() != null,
              "an optimizer hint",
              d.getForPortionClause() != null,
              "FOR PORTION OF");
      if (why != null) {
        return refuse(why + " is not supported on a ScalarDB table");
      }
      returning = d.getReturningClause() != null;
      return predicate(d.getWhere());
    }

    boolean predicate(@Nullable Expression w) {
      where = w;
      if (w == null) {
        return true;
      }
      Set<Object> nodes = SqlAst.valuesUnder(w);
      if (nodes == null) {
        return refuse("the WHERE clause cannot be inspected");
      }
      Set<String> names = new LinkedHashSet<>();
      for (SqlAst.ColumnRef c : ast.columnRefs()) {
        if (c.isAmong(nodes)) {
          names.add(engine.resolveIdentifier(c.name()));
        }
      }
      whereColumns = new ArrayList<>(names);
      return true;
    }

    private static boolean containsFunction(Expression e) {
      Set<Object> nodes = SqlAst.valuesUnder(e);
      if (nodes == null) {
        return true; // not inspectable: assume the worst, which only costs the anchor
      }
      for (Object node : nodes) {
        if (node instanceof Function) {
          return true;
        }
      }
      return false;
    }

    private boolean sameIdentifier(String a, String b) {
      return engine
          .nameKey(engine.resolveIdentifier(a))
          .equals(engine.nameKey(engine.resolveIdentifier(b)));
    }
  }
}
