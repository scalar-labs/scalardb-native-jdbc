package com.scalar.db.nativejdbc.sql;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
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
 * TABLESAMPLE}, and a clause without a syntax tree. The caller decides what empty means: a read
 * goes to the committed-image view, a write is refused.
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
  private final boolean limited;
  private final @Nullable ForMode lockMode;
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
    this.limited = b.limited;
    this.lockMode = b.lockMode;
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
    return SqlAst.parse(sql).flatMap(ast -> of(ast, engine));
  }

  /**
   * Classifies a parsed statement. Column names are resolved the way {@code engine} resolves them
   * ({@link Engine#resolveIdentifier}) and compared the way it compares them ({@link
   * Engine#columnKey}), so that {@code Amount} and {@code amount} are one column on MySQL and two
   * on PostgreSQL.
   *
   * @param ast the statement
   * @param engine the engine whose identifier rules resolve the column names
   * @return the shape, or empty when the statement is not one of the single-table shapes
   */
  public static Optional<StatementShape> of(SqlAst ast, Engine engine) {
    if (ast.tableRefs().size() != 1 || !ast.cteRefs().isEmpty()) {
      return Optional.empty();
    }
    SqlAst.TableRef table = ast.tableRefs().get(0);
    if (table.partCount() > 2 || hasTableHint(table)) {
      return Optional.empty();
    }
    Builder b = new Builder(ast, table, engine);
    if (!b.resolveColumnQualifiers()) {
      return Optional.empty();
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
      ok = false;
    }
    return ok ? Optional.of(new StatementShape(b)) : Optional.empty();
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

  /** A hint or a sample clause on the table itself, which a renamed table may not carry. */
  private static boolean hasTableHint(SqlAst.TableRef ref) {
    Table t = ref.node();
    return t.getIndexHint() != null
        || t.getSqlServerHints() != null
        || t.getSampleClause() != null
        || t.getPivot() != null
        || t.getUnPivot() != null;
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
    boolean limited;
    @Nullable ForMode lockMode;
    @Nullable Expression where;
    List<String> whereColumns = List.of();
    List<String> insertColumns = List.of();
    int insertRowCount;
    List<String> setColumns = List.of();
    boolean returning;

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
          return false;
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
          return false;
        }
      }
      return true;
    }

    boolean select(PlainSelect s) {
      kind = Kind.SELECT;
      FromItem from = s.getFromItem();
      if (!(from instanceof Table)
          || (s.getJoins() != null && !s.getJoins().isEmpty())
          || (s.getIntoTables() != null && !s.getIntoTables().isEmpty())
          || (s.getWithItemsList() != null && !s.getWithItemsList().isEmpty())
          || s.getTop() != null
          || s.getOracleHint() != null
          || s.isSkipLocked() // the flags below are primitives, invisible to the node-type scan
          || s.isNoWait()
          || s.getWait() != null) {
        return false;
      }
      List<SelectItem<?>> items = s.getSelectItems();
      if (items == null || items.isEmpty()) {
        return false;
      }
      boolean functions = false;
      List<String> columns = new ArrayList<>();
      boolean plain = true;
      for (SelectItem<?> item : items) {
        Expression e = item.getExpression();
        if (e instanceof AllColumns || e instanceof AllTableColumns) {
          if (items.size() != 1 || item.getAlias() != null) {
            return false;
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
      limited = s.getLimit() != null || s.getOffset() != null || s.getFetch() != null;
      lockMode = s.getForMode();
      return predicate(s.getWhere());
    }

    boolean insert(Insert i) {
      kind = Kind.INSERT;
      if (table.hasAlias()
          || !(i.getSelect() instanceof Values)
          || (i.getSetUpdateSets() != null && !i.getSetUpdateSets().isEmpty())
          || i.isModifierIgnore() // an upsert in disguise: a collision looks like success
          || (i.getDuplicateUpdateSets() != null && !i.getDuplicateUpdateSets().isEmpty())
          || i.getConflictTarget() != null
          || i.getConflictAction() != null
          || i.getOutputClause() != null
          || i.getOracleHint() != null) {
        return false; // INSERT ... SELECT, INSERT ... SET and every upsert form are out of scope
      }
      ExpressionList<Column> cols = i.getColumns();
      if (cols != null) {
        List<String> names = new ArrayList<>();
        for (Column c : cols) {
          if (c.getTable() != null) {
            return false;
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
            return false;
          }
          rows++;
        }
      }
      insertRowCount = rows;
      returning = i.getReturningClause() != null;
      return rows > 0;
    }

    boolean update(Update u) {
      kind = Kind.UPDATE;
      if (u.getFromItem() != null
          || (u.getJoins() != null && !u.getJoins().isEmpty())
          || (u.getStartJoins() != null && !u.getStartJoins().isEmpty())
          || u.getLimit() != null
          || (u.getOrderByElements() != null && !u.getOrderByElements().isEmpty())
          || u.isModifierIgnore()
          || u.getOutputClause() != null
          || u.getOracleHint() != null) {
        return false;
      }
      List<UpdateSet> sets = u.getUpdateSets();
      if (sets == null || sets.isEmpty()) {
        return false;
      }
      List<String> names = new ArrayList<>();
      for (UpdateSet set : sets) {
        if (set.getColumns().size() != 1 || set.getValues().size() != 1) {
          return false; // SET (a, b) = (?, ?)
        }
        names.add(engine.resolveIdentifier(set.getColumn(0).getColumnName()));
      }
      setColumns = names;
      returning = u.getReturningClause() != null;
      return predicate(u.getWhere());
    }

    boolean delete(Delete d) {
      kind = Kind.DELETE;
      // a USING list or a join adds table references, which the single-table check already refused
      if ((d.getTables() != null && !d.getTables().isEmpty())
          || (d.getJoins() != null && !d.getJoins().isEmpty())
          || d.getLimit() != null
          || (d.getOrderByElements() != null && !d.getOrderByElements().isEmpty())
          || d.isModifierIgnore()
          || d.getOutputClause() != null
          || d.getOracleHint() != null) {
        return false;
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
        return false; // a predicate that cannot be inspected is not understood
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
      return engine.resolveIdentifier(a).equals(engine.resolveIdentifier(b));
    }
  }
}
