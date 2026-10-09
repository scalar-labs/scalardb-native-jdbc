package com.scalar.db.nativejdbc.sql;

import com.scalar.db.nativejdbc.engine.Engine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Top;
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
 *       yields {@link Optional#empty()}; so does one whose syntax tree holds a table node the
 *       reference walk did not visit, so that a parser update that grows the grammar fails closed
 *       instead of silently passing; and a rewrite that cannot locate the tokens of every reference
 *       it must change yields {@code null} rather than a partial result. Every check reads the
 *       parser's public syntax tree, never its private fields, so nothing here goes blind when the
 *       parser is loaded as a named module.
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
  private final Engine engine;
  private final Node root;
  private final int parameterCount;
  private final boolean lockingOrInto;
  private final List<Table> forUpdateTables;

  private SqlAst(
      String source,
      Statement statement,
      List<TableRef> tableRefs,
      List<TableRef> cteRefs,
      List<ColumnRef> columnRefs,
      Set<String> cteNames,
      Engine engine,
      Node root,
      int parameterCount,
      boolean lockingOrInto,
      List<Table> forUpdateTables) {
    this.source = source;
    this.statement = statement;
    this.tableRefs = tableRefs;
    this.cteRefs = cteRefs;
    this.columnRefs = columnRefs;
    this.cteNames = cteNames;
    this.engine = engine;
    this.root = root;
    this.parameterCount = parameterCount;
    this.lockingOrInto = lockingOrInto;
    this.forUpdateTables = forUpdateTables;
  }

  /** Parses with PostgreSQL's identifier rules; a convenience for the tests of this package. */
  static Optional<SqlAst> parse(String sql) {
    return parse(sql, Engine.POSTGRESQL);
  }

  /**
   * Parses one statement. Names local to the statement, CTE names and the table names column
   * qualifiers refer to, are matched the way {@code engine} matches them.
   *
   * @param sql the statement
   * @param engine the engine whose identifier rules apply
   * @return the AST, or empty when the text is not exactly one statement, when the parser or the
   *     reference walk cannot handle it, or when the syntax tree holds a table the walk missed
   */
  public static Optional<SqlAst> parse(String sql, Engine engine) {
    Statement stmt;
    try {
      // parseStatements, not parse: the latter silently keeps only the first of "a; b"
      Statements statements = CCJSqlParserUtil.parseStatements(sql);
      if (statements.size() != 1) {
        return Optional.empty();
      }
      stmt = statements.get(0);
    } catch (Exception e) {
      return Optional.empty();
    }
    Walker walker = new Walker();
    try {
      walker.getTables(stmt);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    Set<String> cteKeys = new LinkedHashSet<>();
    for (String raw : walker.cteNames) {
      cteKeys.add(key(engine, raw));
    }
    List<TableRef> tables = new ArrayList<>();
    List<TableRef> ctes = new ArrayList<>();
    Set<Table> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Table t : walker.tables) {
      if (!seen.add(t)) {
        continue; // the walk returns the body of a CTE twice
      }
      TableRef ref = new TableRef(t, false);
      if (t.getSchemaName() == null && cteKeys.contains(key(engine, t.getName()))) {
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
    Node root = rootOf(stmt, walker.tables, walker.columns, sql);
    if (root == null || hasCommentAnEngineMayExecute(root)) {
      return Optional.empty();
    }
    Set<Object> values = valuesUnder(root);
    // Every SELECT of the statement, wherever it is nested: the walk visits them, and the tree
    // holds the rest.
    Set<Select> selects = Collections.newSetFromMap(new IdentityHashMap<>());
    selects.addAll(walker.selects);
    if (stmt instanceof Select) {
      selects.add((Select) stmt);
    }
    for (Object v : values) {
      if (v instanceof Select) {
        selects.add((Select) v);
      }
    }
    // Every table node of the parser's own tree must be one the walk found, as a reference, as a
    // column qualifier or as the target of FOR UPDATE OF; otherwise a rewrite would leave it as
    // written.
    Set<Table> accounted = Collections.newSetFromMap(new IdentityHashMap<>());
    accounted.addAll(walker.tables);
    for (ColumnRef c : columns) {
      Table q = c.qualifierNode();
      if (q != null) {
        accounted.add(q);
      }
    }
    List<Table> forUpdateTables = new ArrayList<>();
    boolean lockingOrInto = false;
    for (Select s : selects) {
      if (s.getForUpdateTables() != null) {
        forUpdateTables.addAll(s.getForUpdateTables());
      }
      lockingOrInto |= isLockingOrInto(s);
    }
    accounted.addAll(forUpdateTables);
    // A placeholder that is the operand of a cast or a sign has no tree node of its own, so the
    // placeholders are the union of what the tree and the walk found.
    Set<Object> parameters = Collections.newSetFromMap(new IdentityHashMap<>());
    parameters.addAll(walker.parameters);
    Set<Column> walked = Collections.newSetFromMap(new IdentityHashMap<>());
    walked.addAll(walker.columns);
    for (Object v : values) {
      if (v instanceof Table && !accounted.contains(v)) {
        return Optional.empty();
      }
      if (v instanceof JdbcParameter || v instanceof JdbcNamedParameter) {
        parameters.add(v);
      }
      if (v instanceof Column && walked.add((Column) v)) {
        columns.add(new ColumnRef((Column) v, null)); // a column the walk skipped
      }
    }
    return Optional.of(
        new SqlAst(
            sql,
            stmt,
            List.copyOf(tables),
            List.copyOf(ctes),
            List.copyOf(columns),
            Set.copyOf(cteKeys),
            engine,
            root,
            parameters.size(),
            lockingOrInto,
            List.copyOf(forUpdateTables)));
  }

  /** The key under which a name local to the statement is compared on {@code engine}. */
  private static String key(Engine engine, String raw) {
    return engine.nameKey(engine.resolveIdentifier(raw));
  }

  private static boolean isLockingOrInto(Select s) {
    if (s.getForMode() != null || s.isSkipLocked() || s.isNoWait() || s.getWait() != null) {
      return true;
    }
    if (s instanceof PlainSelect) {
      PlainSelect p = (PlainSelect) s;
      return (p.getIntoTables() != null && !p.getIntoTables().isEmpty())
          || p.getMySqlSelectIntoClause() != null
          || p.getIntoTempTable() != null;
    }
    return false;
  }

  /**
   * Comment forms the parser reads as comments but an engine may execute, so that the statement the
   * engine would run is not the one that was classified: MySQL's executable comments {@code /*!
   * ...} and {@code /*M! ...}; a {@code --} not followed by whitespace, which is not a comment on
   * MySQL and MariaDB ({@code id = 1 --1} is {@code id = 1 - -1}); and a block comment holding
   * another opener, which the parser and PostgreSQL nest but MySQL, MariaDB and Oracle end at the
   * first closer. Refused on every engine rather than per engine: nothing is guessed.
   */
  private static boolean hasCommentAnEngineMayExecute(Node root) {
    for (Token t = root.jjtGetFirstToken(); t != null; t = t.next) {
      for (Token s = t.specialToken; s != null; s = s.specialToken) {
        String image = s.image;
        if (s.kind == CCJSqlParserConstants.MULTI_LINE_COMMENT) {
          if (image.startsWith("/*!") || image.startsWith("/*M!") || image.indexOf("/*", 2) >= 0) {
            return true;
          }
        } else if (isLineComment(s)
            && image.length() > 2
            && !Character.isWhitespace(image.charAt(2))) {
          return true;
        }
      }
      if (t.kind == 0) {
        break; // EOF, whose special tokens are the trailing comments
      }
    }
    return false;
  }

  /**
   * The root of the parser's syntax tree, reached from any node of the statement; for a statement
   * without a single node, which can hold neither a table nor a placeholder, a fresh parse of the
   * text supplies the token chain.
   */
  private static @Nullable Node rootOf(
      Statement stmt, List<Table> tables, List<Column> columns, String sql) {
    Node n = null;
    for (Table t : tables) {
      n = t.getASTNode();
      if (n != null) {
        break;
      }
    }
    if (n == null && stmt instanceof ASTNodeAccess) {
      n = ((ASTNodeAccess) stmt).getASTNode();
    }
    for (int i = 0; n == null && i < columns.size(); i++) {
      n = columns.get(i).getASTNode();
    }
    if (n != null) {
      while (n.jjtGetParent() != null) {
        n = n.jjtGetParent();
      }
      return n;
    }
    try {
      return CCJSqlParserUtil.parseAST(sql);
    } catch (Exception e) {
      return null;
    }
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
   * Returns the names defined by a {@code WITH} clause, as the engine compares them.
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
    return parameterCount;
  }

  /**
   * Returns the engine whose identifier rules the statement was parsed with.
   *
   * @return the engine
   */
  public Engine engine() {
    return engine;
  }

  /**
   * Returns whether any SELECT of the statement, nested ones included, locks rows ({@code FOR
   * UPDATE}, {@code FOR SHARE}, {@code SKIP LOCKED}, {@code NOWAIT}, {@code WAIT}) or writes its
   * result ({@code INTO}). Renaming a table under such a clause changes what is locked or written,
   * so a rewrite of a statement other than a single-table shape must refuse it.
   *
   * @return whether a locking or INTO clause appears anywhere
   */
  public boolean hasLockingOrIntoClause() {
    return lockingOrInto;
  }

  /** The root of the parser's syntax tree. */
  Node root() {
    return root;
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
    if (root.jjtGetFirstToken() == null) {
      return null;
    }
    Map<Token, String> from = new IdentityHashMap<>();
    Map<Token, Token> to = new IdentityHashMap<>();
    if (naming != null) {
      Rewrite plan = planRewrite(naming);
      if (lockTargetIsRenamed(plan) || !collectReplacements(plan, from, to)) {
        return null;
      }
    }
    StringBuilder out = new StringBuilder();
    Token skipEnd = null;
    String prev = null;
    // whether the last appended token is a line comment, which only a newline closes
    boolean openLineComment = false;
    for (Token t = root.jjtGetFirstToken(); t != null; t = t.next) {
      for (Token sp : specialTokensOf(t)) {
        appendToken(out, prev, sp.image, openLineComment);
        openLineComment = isLineComment(sp);
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
        appendToken(out, prev, put, openLineComment);
        openLineComment = false;
        prev = put;
        Token end = to.get(t);
        skipEnd = end == t ? null : end;
        continue;
      }
      appendToken(out, prev, t.image, openLineComment);
      openLineComment = false;
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
      noAlias.add(key(engine, r.name()));
      if (usedAsColumnQualifier(r)) {
        String alias = naming.aliasFor(r);
        if (alias != null) {
          plan.aliases.put(r, alias);
          aliasByName.put(key(engine, r.name()), alias);
        }
      }
    }
    for (ColumnRef c : columnRefs) {
      Table q = c.qualifierNode();
      if (q != null) {
        String last = key(engine, q.getName());
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
    String name = key(engine, r.name());
    for (ColumnRef c : columnRefs) {
      Table q = c.qualifierNode();
      if (q != null && name.equals(key(engine, q.getName()))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code FOR UPDATE OF} names a table the plan renames by its name rather than by an
   * alias: the target would then name a table no longer in the statement.
   */
  private boolean lockTargetIsRenamed(Rewrite plan) {
    for (Table target : forUpdateTables) {
      String name = key(engine, target.getName());
      for (TableRef r : plan.tables.keySet()) {
        if (!r.hasAlias() && name.equals(key(engine, r.name()))) {
          return true;
        }
      }
    }
    return false;
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

  /**
   * Appends {@code image}, after a newline when a line comment is open and else after a space where
   * two tokens would otherwise fuse. The decision is by token kind, never by the text: a line
   * comment may contain anything, the end of a block comment included.
   */
  private static void appendToken(
      StringBuilder out, @Nullable String prev, String image, boolean afterLineComment) {
    if (out.length() > 0) {
      if (afterLineComment) {
        out.append('\n');
      } else if (needsSeparator(prev, image)) {
        out.append(' ');
      }
    }
    out.append(image);
  }

  private static boolean isLineComment(Token t) {
    return t.kind == CCJSqlParserConstants.LINE_COMMENT
        || t.kind == CCJSqlParserConstants.HASH_LINE_COMMENT;
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

  /** The special tokens, comments, are chained in reverse; returned in text order. */
  private static List<Token> specialTokensOf(Token t) {
    if (t.specialToken == null) {
      return List.of();
    }
    List<Token> out = new ArrayList<>();
    for (Token s = t.specialToken; s != null; s = s.specialToken) {
      out.add(s);
    }
    Collections.reverse(out);
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

  /** One table reference, a handle onto a node of the AST. */
  public static final class TableRef {
    private final Table node;
    private final boolean qualifier;

    /** The parser's node. */
    Table node() {
      return node;
    }

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
     * Returns whether the reference is a {@code t.*}.
     *
     * @return whether it names all columns of a table
     */
    public boolean isAllColumnsOfTable() {
      return allTableColumns != null;
    }

    /**
     * Whether this reference's node is one of {@code nodes}, an identity set from {@code
     * valuesUnder}.
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

  /**
   * Collects the tables, columns, placeholders and CTE names while riding on the parser's own
   * traversal.
   */
  private static final class Walker extends TablesNamesFinder<Void> {
    final List<Table> tables = new ArrayList<>();
    final List<Column> columns = new ArrayList<>();
    final List<AllTableColumns> allTableColumns = new ArrayList<>();
    final List<Object> parameters = new ArrayList<>();
    final List<Select> selects = new ArrayList<>();
    final Set<String> cteNames = new LinkedHashSet<>(); // as written

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
      selects.add(plainSelect);
      Top top = plainSelect.getTop();
      if (top != null) {
        Expression e = top.getExpression();
        if (e instanceof JdbcParameter || e instanceof JdbcNamedParameter) {
          parameters.add(e); // TOP ? is neither a tree node nor visited
        }
      }
      return super.visit(plainSelect, context);
    }

    @Override
    public <S> Void visit(SetOperationList setOperationList, S context) {
      selects.add(setOperationList);
      return super.visit(setOperationList, context);
    }

    @Override
    public <S> Void visit(JdbcParameter parameter, S context) {
      parameters.add(parameter);
      return super.visit(parameter, context);
    }

    @Override
    public <S> Void visit(JdbcNamedParameter parameter, S context) {
      parameters.add(parameter);
      return super.visit(parameter, context);
    }

    @Override
    public <S> Void visit(WithItem<?> withItem, S context) {
      if (withItem.getAlias() != null) {
        cteNames.add(withItem.getAlias().getName());
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

  /**
   * Returns the values of every syntax-tree node under {@code expression}, itself included, by
   * identity, so a caller can tell which references lie inside one clause.
   *
   * @param expression an expression of the statement, such as a {@code WHERE} clause
   * @return the values under it, or {@code null} when the expression carries no syntax tree
   */
  static @Nullable Set<Object> valuesUnder(Expression expression) {
    Node n = expression.getASTNode();
    return n == null ? null : valuesUnder(n);
  }

  /** Iterative, with no depth limit: a predicate of any length is walked in full. */
  static Set<Object> valuesUnder(Node root) {
    Set<Object> out = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Node> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      Node n = stack.pop();
      Object v = n.jjtGetValue();
      if (v != null) {
        out.add(v);
      }
      for (int i = n.jjtGetNumChildren() - 1; i >= 0; i--) {
        stack.push(n.jjtGetChild(i));
      }
    }
    return out;
  }
}
