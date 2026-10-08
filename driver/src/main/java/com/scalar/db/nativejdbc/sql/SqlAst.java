package com.scalar.db.nativejdbc.sql;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.jspecify.annotations.Nullable;

/**
 * A parsed statement with its table and column references, and a rewriter that emits the statement
 * by re-joining the original tokens.
 *
 * <p>Three rules shape this class.
 *
 * <ul>
 *   <li><b>Touch nothing unless everything is understood.</b> A statement that cannot be parsed
 *       yields {@link Optional#empty()}; a rewrite that cannot locate the tokens of every reference
 *       it must change yields {@code null} rather than a partial result; and {@link
 *       #unknownNodes()} reports a table node the enumeration missed or a node type this class has
 *       not been taught, so that a parser update that grows the grammar fails closed instead of
 *       silently passing.
 *   <li><b>Decide by the tree, not the text.</b> The kind of statement comes from the AST type, so
 *       a leading comment does not change it; every table reference is found by walking the tree,
 *       so a second table in a join or a subquery is not missed; and literals are single tokens, so
 *       a table name inside a string is never rewritten.
 *   <li><b>Emit by joining the original tokens.</b> {@link #joinTokens(Naming)} replaces the tokens
 *       of the references to rewrite and copies every other token as written, so comments, hints,
 *       the case of keywords and the quoting style survive; only whitespace is normalised. The
 *       result is parsed again before it is returned.
 * </ul>
 */
// Tokens and AST nodes are compared and keyed by identity throughout: two equal-looking tokens at
// different positions are different tokens.
@SuppressWarnings({"ReferenceEquality", "IdentityHashMapUsage"})
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "every collection handed out is an immutable copy made by List.copyOf or Set.copyOf")
public final class SqlAst {

  /** The kind of statement, decided from the type of the AST. */
  public enum Kind {
    /** A {@code SELECT}, including {@code WITH ... SELECT} and set operations. */
    SELECT,
    /** {@code INSERT}, {@code UPDATE}, {@code DELETE}, {@code UPSERT} or {@code MERGE}. */
    DML,
    /** {@code TRUNCATE}. */
    TRUNCATE,
    /** {@code CREATE}, {@code ALTER}, {@code DROP} or {@code COMMENT}. */
    DDL,
    /** Anything else. */
    OTHER
  }

  /**
   * What a rewrite replaces. Only {@link #replacementFor(TableRef)} is required; the defaults
   * replace nothing else.
   */
  public interface Naming {
    /**
     * Returns the name that replaces this table reference, already quoted for the engine, or {@code
     * null} to leave the reference alone. The qualifiers of the reference are dropped with the
     * name.
     *
     * @param ref a table reference of the statement
     * @return the replacement, or {@code null}
     */
    @Nullable String replacementFor(TableRef ref);

    /**
     * Returns the alias to attach to a replaced table reference that has none while a column is
     * qualified by its name, already quoted for the engine, or {@code null} to attach none. It is
     * asked about the reference before the replacement. Without an alias the qualified columns
     * would refer to a name the replacement removed.
     *
     * @param ref a table reference of the statement that is being replaced
     * @return the alias, or {@code null}
     */
    default @Nullable String aliasFor(TableRef ref) {
      return null;
    }

    /**
     * Returns the name that replaces this column reference's name, already quoted for the engine,
     * or {@code null} to leave it alone. The qualifier is kept.
     *
     * @param ref a column reference of the statement
     * @return the replacement, or {@code null}
     */
    default @Nullable String columnReplacementFor(ColumnRef ref) {
      return null;
    }
  }

  /**
   * The node types the rewrite has been taught. Measured from the statements of the tests, never
   * guessed: a type that appears in a statement but not here is reported by {@link
   * #unknownNodes()}.
   */
  private static final Set<String> KNOWN_NODE_TYPES =
      Set.of(
          "net.sf.jsqlparser.expression.Alias",
          "net.sf.jsqlparser.expression.BooleanValue",
          "net.sf.jsqlparser.expression.CaseExpression",
          "net.sf.jsqlparser.expression.CastExpression",
          "net.sf.jsqlparser.expression.DateValue",
          "net.sf.jsqlparser.expression.DoubleValue",
          "net.sf.jsqlparser.expression.Function",
          "net.sf.jsqlparser.expression.JdbcNamedParameter",
          "net.sf.jsqlparser.expression.JdbcParameter",
          "net.sf.jsqlparser.expression.LongValue",
          "net.sf.jsqlparser.expression.NotExpression",
          "net.sf.jsqlparser.expression.NullValue",
          "net.sf.jsqlparser.expression.SignedExpression",
          "net.sf.jsqlparser.expression.StringValue",
          "net.sf.jsqlparser.expression.TimeValue",
          "net.sf.jsqlparser.expression.TimestampValue",
          "net.sf.jsqlparser.expression.WhenClause",
          "net.sf.jsqlparser.expression.operators.arithmetic.Addition",
          "net.sf.jsqlparser.expression.operators.arithmetic.Concat",
          "net.sf.jsqlparser.expression.operators.arithmetic.Division",
          "net.sf.jsqlparser.expression.operators.arithmetic.Multiplication",
          "net.sf.jsqlparser.expression.operators.arithmetic.Subtraction",
          "net.sf.jsqlparser.expression.operators.conditional.AndExpression",
          "net.sf.jsqlparser.expression.operators.conditional.OrExpression",
          "net.sf.jsqlparser.expression.operators.relational.Between",
          "net.sf.jsqlparser.expression.operators.relational.EqualsTo",
          "net.sf.jsqlparser.expression.operators.relational.ExpressionList",
          "net.sf.jsqlparser.expression.operators.relational.GreaterThan",
          "net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals",
          "net.sf.jsqlparser.expression.operators.relational.InExpression",
          "net.sf.jsqlparser.expression.operators.relational.IsNullExpression",
          "net.sf.jsqlparser.expression.operators.relational.LikeExpression",
          "net.sf.jsqlparser.expression.operators.relational.LikeExpression$KeyWord",
          "net.sf.jsqlparser.expression.operators.relational.MinorThan",
          "net.sf.jsqlparser.expression.operators.relational.MinorThanEquals",
          "net.sf.jsqlparser.expression.operators.relational.NotEqualsTo",
          "net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList",
          "net.sf.jsqlparser.schema.Column",
          "net.sf.jsqlparser.schema.Table",
          "net.sf.jsqlparser.statement.ReturningClause",
          "net.sf.jsqlparser.statement.ReturningClause$Keyword",
          "net.sf.jsqlparser.statement.delete.Delete",
          "net.sf.jsqlparser.statement.insert.Insert",
          "net.sf.jsqlparser.statement.insert.Insert$OverridingMode",
          "net.sf.jsqlparser.statement.select.AllColumns",
          "net.sf.jsqlparser.statement.select.AllTableColumns",
          "net.sf.jsqlparser.statement.select.Distinct",
          "net.sf.jsqlparser.statement.select.Fetch",
          "net.sf.jsqlparser.statement.select.ForMode",
          "net.sf.jsqlparser.statement.select.GroupByElement",
          "net.sf.jsqlparser.statement.select.Join",
          "net.sf.jsqlparser.statement.select.LateralSubSelect",
          "net.sf.jsqlparser.statement.select.Limit",
          "net.sf.jsqlparser.statement.select.Offset",
          "net.sf.jsqlparser.statement.select.OrderByElement",
          "net.sf.jsqlparser.statement.select.ParenthesedSelect",
          "net.sf.jsqlparser.statement.select.PlainSelect",
          "net.sf.jsqlparser.statement.select.PlainSelect$EmitMode",
          "net.sf.jsqlparser.statement.select.SelectItem",
          "net.sf.jsqlparser.statement.select.SetOperationList",
          "net.sf.jsqlparser.statement.select.SetOperationList$SetOperationType",
          "net.sf.jsqlparser.statement.select.TableFunction",
          "net.sf.jsqlparser.statement.select.UnionOp",
          "net.sf.jsqlparser.statement.select.Values",
          "net.sf.jsqlparser.statement.select.WithItem",
          "net.sf.jsqlparser.statement.update.Update",
          "net.sf.jsqlparser.statement.update.UpdateSet");

  /** Symbols that take no space before them. */
  private static final Set<String> NO_SPACE_BEFORE = Set.of(".", "..", ",", "(", ")", ";", "}");

  /** Symbols that take no space after them. */
  private static final Set<String> NO_SPACE_AFTER = Set.of(".", "..", "(", ":");

  private final String source;
  private final Statement statement;
  private final List<TableRef> tableRefs;
  private final List<TableRef> cteRefs;
  private final List<ColumnRef> columnRefs;
  private final Set<String> cteNames;
  private @Nullable List<String> unknownNodes;

  private SqlAst(
      String source,
      Statement statement,
      List<TableRef> tableRefs,
      List<TableRef> cteRefs,
      List<ColumnRef> columnRefs,
      Set<String> cteNames) {
    this.source = source;
    this.statement = statement;
    this.tableRefs = tableRefs;
    this.cteRefs = cteRefs;
    this.columnRefs = columnRefs;
    this.cteNames = cteNames;
  }

  /**
   * Parses one statement.
   *
   * @param sql the statement
   * @return the AST, or empty when the parser or the reference walk cannot handle the statement
   */
  public static Optional<SqlAst> parse(String sql) {
    Statement stmt;
    try {
      stmt = CCJSqlParserUtil.parse(sql);
    } catch (Exception e) {
      return Optional.empty();
    }
    Walker walker = new Walker();
    try {
      walker.getTables(stmt);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    List<TableRef> tables = new ArrayList<>();
    List<TableRef> ctes = new ArrayList<>();
    Set<Table> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Table t : walker.tables) {
      if (!seen.add(t)) {
        continue; // the walk returns the body of a CTE twice
      }
      TableRef ref = new TableRef(t, false);
      if (t.getSchemaName() == null && walker.cteNames.contains(fold(t.getName()))) {
        ctes.add(ref);
      } else {
        tables.add(ref);
      }
    }
    List<ColumnRef> columns = new ArrayList<>();
    for (Column c : walker.columns) {
      columns.add(new ColumnRef(c, null));
    }
    for (AllTableColumns a : walker.allTableColumns) {
      columns.add(new ColumnRef(null, a));
    }
    return Optional.of(
        new SqlAst(
            sql,
            stmt,
            List.copyOf(tables),
            List.copyOf(ctes),
            List.copyOf(columns),
            Set.copyOf(walker.cteNames)));
  }

  /**
   * Returns the kind of statement, decided from the AST type so that a leading comment does not
   * change it.
   *
   * @return the kind
   */
  public Kind kind() {
    if (statement instanceof Select) {
      return Kind.SELECT;
    }
    if (statement instanceof Insert
        || statement instanceof Update
        || statement instanceof Delete
        || statement instanceof Upsert
        || statement instanceof Merge) {
      return Kind.DML;
    }
    if (statement instanceof Truncate) {
      return Kind.TRUNCATE;
    }
    String n = statement.getClass().getName();
    if (n.startsWith("net.sf.jsqlparser.statement.create.")
        || n.startsWith("net.sf.jsqlparser.statement.alter.")
        || n.startsWith("net.sf.jsqlparser.statement.drop.")
        || n.startsWith("net.sf.jsqlparser.statement.comment.")) {
      return Kind.DDL;
    }
    return Kind.OTHER;
  }

  /**
   * Returns the original text.
   *
   * @return the statement as given
   */
  public String source() {
    return source;
  }

  /**
   * Returns the parsed statement, for callers that need more than this class exposes.
   *
   * @return the AST
   */
  public Statement statement() {
    return statement;
  }

  /**
   * Returns every table reference in the statement, wherever it appears, except references to a CTE
   * name.
   *
   * @return the table references in order of appearance
   */
  public List<TableRef> tableRefs() {
    return tableRefs;
  }

  /**
   * Returns the references resolved by a CTE name.
   *
   * @return the CTE references
   */
  public List<TableRef> cteRefs() {
    return cteRefs;
  }

  /**
   * Returns every column reference in the statement, including the qualifier of a {@code t.*}.
   *
   * @return the column references in order of appearance
   */
  public List<ColumnRef> columnRefs() {
    return columnRefs;
  }

  /**
   * Returns the names defined by a {@code WITH} clause, folded.
   *
   * @return the CTE names
   */
  public Set<String> cteNames() {
    return cteNames;
  }

  /**
   * Returns the number of value placeholders, {@code ?} and {@code :name}.
   *
   * @return the placeholder count
   */
  public int parameterCount() {
    int n = 0;
    for (Object o : scanNodes().keySet()) {
      if (o instanceof JdbcParameter || o instanceof JdbcNamedParameter) {
        n++;
      }
    }
    return n;
  }

  /**
   * Returns what the walk did not understand: {@code table:<name>} for a table node that is neither
   * a table reference, a CTE reference nor a column qualifier, which a rewrite would miss; and
   * {@code type:<class>} for a node type this class has not been taught. A caller that rewrites
   * must refuse a statement with a {@code table:} entry; a {@code type:} entry means the statement
   * is not one of the shapes the driver handles.
   *
   * @return the findings, empty when everything was understood
   */
  public List<String> unknownNodes() {
    List<String> cached = unknownNodes;
    if (cached != null) {
      return cached;
    }
    List<String> out = new ArrayList<>();
    Set<Table> accounted = Collections.newSetFromMap(new IdentityHashMap<>());
    for (TableRef r : tableRefs) {
      accounted.add(r.node);
    }
    for (TableRef r : cteRefs) {
      accounted.add(r.node);
    }
    for (ColumnRef c : columnRefs) {
      Table q = c.qualifierNode();
      if (q != null) {
        accounted.add(q);
      }
    }
    Set<String> types = new TreeSet<>();
    for (Object node : scanNodes().keySet()) {
      types.add(node.getClass().getName());
      if (node instanceof Table && !accounted.contains(node)) {
        out.add("table:" + ((Table) node).getFullyQualifiedName());
      }
    }
    for (String t : types) {
      if (!KNOWN_NODE_TYPES.contains(t)) {
        out.add("type:" + t);
      }
    }
    cached = List.copyOf(out);
    unknownNodes = cached;
    return cached;
  }

  /**
   * Returns whether {@link #unknownNodes()} is not empty.
   *
   * @return whether something was not understood
   */
  public boolean hasUnknownNodes() {
    return !unknownNodes().isEmpty();
  }

  /**
   * Returns the node types that appear in the statement.
   *
   * @return the class names, sorted
   */
  public Set<String> nodeTypes() {
    Set<String> types = new TreeSet<>();
    for (Object node : scanNodes().keySet()) {
      types.add(node.getClass().getName());
    }
    return types;
  }

  /**
   * Returns the statement with the original tokens joined and nothing replaced: comments, hints,
   * case and quoting as written, whitespace normalised.
   *
   * @return the joined statement, or {@code null} when the token chain is unavailable
   */
  public @Nullable String joinTokens() {
    return joinTokens(null);
  }

  /**
   * Returns the statement with the original tokens joined and the references {@code naming} names
   * replaced. A replaced table reference without an alias whose name qualifies a column gets the
   * alias {@link Naming#aliasFor} supplies, and the column qualifiers follow it. The result is
   * parsed again before it is returned.
   *
   * @param naming what to replace, or {@code null} to replace nothing
   * @return the rewritten statement, or {@code null} when a token range cannot be located or the
   *     result does not parse, in which case nothing has been rewritten
   */
  public @Nullable String joinTokens(@Nullable Naming naming) {
    Node root = astRoot();
    if (root == null || root.jjtGetFirstToken() == null) {
      return null;
    }
    Map<Token, String> from = new IdentityHashMap<>();
    Map<Token, Token> to = new IdentityHashMap<>();
    if (naming != null && !collectReplacements(planRewrite(naming), from, to)) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    Token skipEnd = null;
    String prev = null;
    for (Token t = root.jjtGetFirstToken(); t != null; t = t.next) {
      for (Token sp : specialTokensOf(t)) {
        appendToken(out, prev, sp.image);
        prev = null; // nothing is tightened right after a comment
      }
      if (t.kind == 0 || t.image.isEmpty()) {
        break; // EOF
      }
      if (skipEnd != null) {
        if (t == skipEnd) {
          skipEnd = null;
        }
        continue; // the rest of a replaced range
      }
      String put = from.get(t);
      if (put != null) {
        appendToken(out, prev, put);
        prev = put;
        Token end = to.get(t);
        skipEnd = end == t ? null : end;
        continue;
      }
      appendToken(out, prev, t.image);
      prev = t.image;
    }
    String joined = out.toString().strip();
    try {
      CCJSqlParserUtil.parse(joined); // two tokens may only have fused into one if this fails
    } catch (Exception e) {
      return null;
    }
    return joined;
  }

  /** The decisions of a rewrite, shared by the planning and the token emission. */
  private static final class Rewrite {
    final Map<TableRef, String> tables = new LinkedHashMap<>();
    final Map<TableRef, String> aliases = new IdentityHashMap<>();
    final Map<ColumnRef, String> qualifiers = new IdentityHashMap<>();
    final Map<ColumnRef, String> columnNames = new IdentityHashMap<>();
  }

  private Rewrite planRewrite(Naming naming) {
    Rewrite plan = new Rewrite();
    for (TableRef r : tableRefs) {
      String to = naming.replacementFor(r);
      if (to != null) {
        plan.tables.put(r, to);
      }
    }
    // A replaced reference without an alias may be named by a column qualifier; give it one.
    Set<String> noAlias = new LinkedHashSet<>();
    Map<String, String> aliasByName = new LinkedHashMap<>();
    for (TableRef r : plan.tables.keySet()) {
      if (r.hasAlias()) {
        continue;
      }
      noAlias.add(r.foldedName());
      if (usedAsColumnQualifier(r)) {
        String alias = naming.aliasFor(r);
        if (alias != null) {
          plan.aliases.put(r, alias);
          aliasByName.put(r.foldedName(), alias);
        }
      }
    }
    for (ColumnRef c : columnRefs) {
      Table q = c.qualifierNode();
      if (q != null) {
        String last = fold(q.getName());
        if (noAlias.contains(last)) {
          String alias = aliasByName.get(last);
          String to = alias != null ? alias : q.getName();
          if (q.getSchemaName() != null || !to.equals(q.getName())) {
            plan.qualifiers.put(c, to);
          }
        }
      }
      if (!c.isAllColumnsOfTable()) {
        String name = naming.columnReplacementFor(c);
        if (name != null && !name.equals(c.name())) {
          plan.columnNames.put(c, name);
        }
      }
    }
    return plan;
  }

  private boolean usedAsColumnQualifier(TableRef r) {
    for (ColumnRef c : columnRefs) {
      Table q = c.qualifierNode();
      if (q != null && r.foldedName().equals(fold(q.getName()))) {
        return true;
      }
    }
    return false;
  }

  private @Nullable Node astRoot() {
    for (TableRef r : tableRefs) {
      Node n = r.node.getASTNode();
      if (n != null) {
        while (n.jjtGetParent() != null) {
          n = n.jjtGetParent();
        }
        return n;
      }
    }
    try {
      return CCJSqlParserUtil.parseAST(source); // nothing to replace in a statement without tables
    } catch (Exception e) {
      return null;
    }
  }

  /** Turns the plan into token instructions: first token to text, and first token to last token. */
  private boolean collectReplacements(Rewrite plan, Map<Token, String> from, Map<Token, Token> to) {
    for (Map.Entry<TableRef, String> e : plan.tables.entrySet()) {
      TableRef r = e.getKey();
      Node n = r.node.getASTNode();
      if (n == null || n.jjtGetFirstToken() == null) {
        return false;
      }
      Token first = n.jjtGetFirstToken();
      String alias = plan.aliases.get(r);
      from.put(first, alias == null ? e.getValue() : e.getValue() + " AS " + alias);
      to.put(first, nameEndOf(first));
    }
    Set<ColumnRef> touched = Collections.newSetFromMap(new IdentityHashMap<>());
    touched.addAll(plan.qualifiers.keySet());
    touched.addAll(plan.columnNames.keySet());
    for (ColumnRef c : touched) {
      boolean plainColumn = !c.isAllColumnsOfTable();
      Node n = plainColumn ? c.columnNode() : c.qualifierAstNode();
      if (n == null || n.jjtGetFirstToken() == null) {
        return false;
      }
      Token first = n.jjtGetFirstToken();
      Token end = plainColumn ? n.jjtGetLastToken() : nameEndOf(first);
      if (end == null) {
        return false;
      }
      String qualifier = plan.qualifiers.get(c);
      if (qualifier == null) {
        Table q = c.qualifierNode();
        qualifier = q == null ? "" : q.getFullyQualifiedName();
      }
      String name = plainColumn ? plan.columnNames.getOrDefault(c, c.name()) : "*";
      from.put(first, qualifier.isEmpty() ? name : qualifier + "." + name);
      to.put(first, end);
    }
    return true;
  }

  private static void appendToken(StringBuilder out, @Nullable String prev, String image) {
    if (out.length() > 0) {
      if (endsWithOpenLineComment(out)) {
        out.append('\n');
      } else if (needsSeparator(prev, image)) {
        out.append(' ');
      }
    }
    out.append(image);
  }

  /**
   * A separator is required only between two non-delimiter tokens (ISO/IEC 9075-2, 5.2); the driver
   * tightens only around the symbols above, so two operators never fuse into a different token.
   */
  private static boolean needsSeparator(@Nullable String prev, String image) {
    if (prev == null || prev.isEmpty() || image.isEmpty()) {
      return true;
    }
    return !NO_SPACE_AFTER.contains(prev) && !NO_SPACE_BEFORE.contains(image);
  }

  private static boolean endsWithOpenLineComment(StringBuilder out) {
    int nl = out.lastIndexOf("\n");
    String lastLine = out.substring(nl + 1);
    int lc = lastLine.lastIndexOf("--");
    return lc >= 0 && !lastLine.substring(lc).contains("*/");
  }

  /** The special tokens, comments, are chained in reverse; returned in text order. */
  private static List<Token> specialTokensOf(Token t) {
    if (t.specialToken == null) {
      return List.of();
    }
    List<Token> out = new ArrayList<>();
    for (Token s = t.specialToken; s != null; s = s.specialToken) {
      out.add(0, s);
    }
    return out;
  }

  /**
   * The last token of a dotted name starting at {@code first}; stops before an alias or a clause.
   */
  private static Token nameEndOf(Token first) {
    Token end = first;
    for (Token t = first.next; t != null; t = t.next) {
      if (t.image.equals(".")) {
        end = t;
        continue;
      }
      if (!end.image.equals(".")) {
        break;
      }
      end = t;
    }
    return end;
  }

  /**
   * Removes identifier quoting: {@code "x"}, {@code `x`} and {@code [x]} become {@code x}.
   *
   * @param s an identifier as written
   * @return the identifier without its quotes
   */
  public static String unquote(String s) {
    if (s.length() < 2) {
      return s;
    }
    char c = s.charAt(0);
    if ((c == '"' || c == '`' || c == '[')
        && (s.endsWith("\"") || s.endsWith("`") || s.endsWith("]"))) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  /**
   * Folds an identifier for matching: unquoted and lower-cased.
   *
   * @param s an identifier as written
   * @return the folded identifier
   */
  public static String fold(String s) {
    return unquote(s).toLowerCase(Locale.ROOT);
  }

  /**
   * Returns whether an identifier is quoted.
   *
   * @param s an identifier as written
   * @return whether it carries quotes
   */
  public static boolean isQuoted(String s) {
    if (s.length() < 2) {
      return false;
    }
    char c = s.charAt(0);
    return (c == '"' && s.endsWith("\""))
        || (c == '`' && s.endsWith("`"))
        || (c == '[' && s.endsWith("]"));
  }

  /**
   * Returns the name an identifier resolves to: as written without quotes when it was quoted,
   * folded when it was not, which is the rule of PostgreSQL and MySQL alike.
   *
   * @param s an identifier as written
   * @return the resolved name
   */
  public static String resolve(String s) {
    return isQuoted(s) ? unquote(s) : fold(s);
  }

  /** One table reference, a handle onto a node of the AST. */
  public static final class TableRef {
    private final Table node;
    private final boolean qualifier;

    private TableRef(Table node, boolean qualifier) {
      this.node = node;
      this.qualifier = qualifier;
    }

    /**
     * Returns the table name as written, quotes included.
     *
     * @return the name
     */
    public String name() {
      return node.getName();
    }

    /**
     * Returns the folded table name.
     *
     * @return the name, unquoted and lower-cased
     */
    public String foldedName() {
      return fold(node.getName());
    }

    /**
     * Returns the name the reference resolves to: as written when quoted, folded otherwise.
     *
     * @return the resolved name
     */
    public String resolvedName() {
      return resolve(node.getName());
    }

    /**
     * Returns whether the table name is quoted.
     *
     * @return whether it carries quotes
     */
    public boolean quoted() {
      return isQuoted(node.getName());
    }

    /**
     * Returns how many parts the name has: 1 for {@code t}, 2 for {@code s.t}, 3 for {@code d.s.t}.
     *
     * @return the part count
     */
    public int partCount() {
      List<String> parts = node.getNameParts();
      return parts == null ? 1 : parts.size();
    }

    /**
     * Returns the schema qualifier as written, or {@code null} for an unqualified name. A
     * three-part name reports its middle part.
     *
     * @return the schema, or {@code null}
     */
    public @Nullable String schema() {
      return node.getSchemaName();
    }

    /**
     * Returns the resolved schema qualifier, or {@code null} for an unqualified name.
     *
     * @return the resolved schema, or {@code null}
     */
    public @Nullable String resolvedSchema() {
      String s = node.getSchemaName();
      return s == null ? null : resolve(s);
    }

    /**
     * Returns the name with its qualifiers, as written.
     *
     * @return the fully qualified name
     */
    public String fullyQualifiedName() {
      return node.getFullyQualifiedName();
    }

    /**
     * Returns whether the reference carries an alias.
     *
     * @return whether it has an alias
     */
    public boolean hasAlias() {
      return node.getAlias() != null;
    }

    /**
     * Returns the alias as written, or {@code null}.
     *
     * @return the alias, or {@code null}
     */
    public @Nullable String alias() {
      Alias a = node.getAlias();
      return a == null ? null : a.getName();
    }

    /**
     * Returns whether this table node appeared as a column qualifier rather than in a FROM clause.
     *
     * @return whether it is a qualifier
     */
    public boolean isColumnQualifier() {
      return qualifier;
    }

    @Override
    public String toString() {
      String a = alias();
      return fullyQualifiedName() + (a == null ? "" : " AS " + a);
    }
  }

  /** One column reference, including a {@code t.*}. */
  public static final class ColumnRef {
    private final @Nullable Column column;
    private final @Nullable AllTableColumns allTableColumns;

    private ColumnRef(@Nullable Column column, @Nullable AllTableColumns allTableColumns) {
      this.column = column;
      this.allTableColumns = allTableColumns;
    }

    /**
     * Returns the column name as written, or {@code *} for a {@code t.*}.
     *
     * @return the name
     */
    public String name() {
      return column != null ? column.getColumnName() : "*";
    }

    /**
     * Returns the resolved column name: as written when quoted, folded otherwise.
     *
     * @return the resolved name
     */
    public String resolvedName() {
      return resolve(name());
    }

    /**
     * Returns whether the reference is a {@code t.*}.
     *
     * @return whether it names all columns of a table
     */
    public boolean isAllColumnsOfTable() {
      return allTableColumns != null;
    }

    /**
     * Whether this reference's node is one of {@code nodes}, an identity set from {@code
     * nodesUnder}.
     */
    boolean isAmong(Set<Object> nodes) {
      return column != null && nodes.contains(column);
    }

    /**
     * Returns the qualifier, the {@code t} of {@code t.x}, or {@code null} when there is none.
     *
     * @return the qualifier as a table reference, or {@code null}
     */
    public @Nullable TableRef qualifier() {
      Table t = qualifierNode();
      return t == null ? null : new TableRef(t, true);
    }

    private @Nullable Table qualifierNode() {
      if (column != null) {
        return column.getTable();
      }
      return allTableColumns == null ? null : allTableColumns.getTable();
    }

    /**
     * The node of the column, whose range includes the qualifier; {@code null} for a {@code t.*}.
     */
    private @Nullable Node columnNode() {
      return column == null ? null : column.getASTNode();
    }

    /** The node of the qualifier of a {@code t.*}, which carries the range instead. */
    private @Nullable Node qualifierAstNode() {
      Table t = qualifierNode();
      return t == null ? null : t.getASTNode();
    }

    @Override
    public String toString() {
      Table q = qualifierNode();
      return (q == null ? "" : q.getFullyQualifiedName() + ".") + name();
    }
  }

  /** Collects the tables, columns and CTE names while riding on the parser's own traversal. */
  private static final class Walker extends TablesNamesFinder<Void> {
    final List<Table> tables = new ArrayList<>();
    final List<Column> columns = new ArrayList<>();
    final List<AllTableColumns> allTableColumns = new ArrayList<>();
    final Set<String> cteNames = new LinkedHashSet<>();

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
      if (withItem.getAlias() != null) {
        cteNames.add(fold(withItem.getAlias().getName()));
      }
      return super.visit(withItem, context);
    }

    @Override
    public <S> Void visit(Table table, S context) {
      tables.add(table);
      return super.visit(table, context);
    }

    /**
     * {@code TRUNCATE a, b}: the traversal follows only the last table, so the rest are added here.
     */
    @Override
    public <S> Void visit(Truncate truncate, S context) {
      if (truncate.getTables() != null) {
        for (Table t : truncate.getTables()) {
          if (t != truncate.getTable()) {
            tables.add(t);
          }
        }
      }
      return super.visit(truncate, context);
    }

    @Override
    public <S> Void visit(Column column, S context) {
      columns.add(column);
      return super.visit(column, context);
    }

    @Override
    public <S> Void visit(AllTableColumns allTableColumns, S context) {
      if (allTableColumns.getTable() != null) {
        this.allTableColumns.add(allTableColumns);
      }
      return super.visit(allTableColumns, context);
    }
  }

  /** Every {@code net.sf.jsqlparser} object reachable from the statement, found by reflection. */
  private Map<Object, Boolean> scanNodes() {
    Map<Object, Boolean> seen = new IdentityHashMap<>();
    scan(statement, seen, 0);
    return seen;
  }

  /**
   * Returns every {@code net.sf.jsqlparser} node reachable from {@code root}, by identity, so a
   * caller can tell which references lie inside one clause.
   *
   * @param root a node of the statement, such as a {@code WHERE} expression
   * @return the nodes under it, including itself
   */
  static Set<Object> nodesUnder(Object root) {
    Map<Object, Boolean> seen = new IdentityHashMap<>();
    scan(root, seen, 0);
    return seen.keySet();
  }

  private static void scan(@Nullable Object o, Map<Object, Boolean> seen, int depth) {
    if (o == null || depth > 64) {
      return;
    }
    Class<?> k = o.getClass();
    String name = k.getName();
    boolean node =
        name.startsWith("net.sf.jsqlparser.") && !name.startsWith("net.sf.jsqlparser.parser.");
    if (node) {
      if (seen.put(o, Boolean.TRUE) != null) {
        return;
      }
      if (o instanceof Enum<?>) {
        return;
      }
    }
    if (o instanceof Collection<?>) {
      for (Object e : (Collection<?>) o) {
        scan(e, seen, depth + 1);
      }
    } else if (o instanceof Map<?, ?>) {
      for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
        scan(e.getKey(), seen, depth + 1);
        scan(e.getValue(), seen, depth + 1);
      }
    } else if (k.isArray() && !k.getComponentType().isPrimitive()) {
      int n = Array.getLength(o);
      for (int i = 0; i < n; i++) {
        scan(Array.get(o, i), seen, depth + 1);
      }
    }
    if (!node) {
      return;
    }
    for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
      if (!c.getName().startsWith("net.sf.jsqlparser.")) {
        continue;
      }
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
          continue;
        }
        Object v;
        try {
          f.setAccessible(true);
          v = f.get(o);
        } catch (RuntimeException | ReflectiveOperationException e) {
          continue;
        }
        scan(v, seen, depth + 1);
      }
    }
  }
}
