package com.scalar.db.nativejdbc.sql;

import com.scalar.db.nativejdbc.engine.Engine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.ConnectByRootOperator;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JsonAggregateFunction;
import net.sf.jsqlparser.expression.KeepExpression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.expression.XMLSerializeExpr;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserConstants;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.StringProvider;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ResetStatement;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UseStatement;
import net.sf.jsqlparser.statement.alter.AlterSession;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Fetch;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
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
    /**
     * A statement that changes the session, {@code SET}, {@code RESET}, {@code USE} or {@code ALTER
     * SESSION}: after it the session's schemas and settings must be read again.
     */
    SESSION,
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
  private final boolean positionalParameters;
  private final boolean lockingOrInto;
  private final List<Table> forUpdateTables;
  private final boolean setsSessionInAnExpression;

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
      boolean positionalParameters,
      boolean lockingOrInto,
      List<Table> forUpdateTables,
      boolean setsSessionInAnExpression) {
    this.setsSessionInAnExpression = setsSessionInAnExpression;
    this.source = source;
    this.statement = statement;
    this.tableRefs = tableRefs;
    this.cteRefs = cteRefs;
    this.columnRefs = columnRefs;
    this.cteNames = cteNames;
    this.engine = engine;
    this.root = root;
    this.parameterCount = parameterCount;
    this.positionalParameters = positionalParameters;
    this.lockingOrInto = lockingOrInto;
    this.forUpdateTables = forUpdateTables;
  }

  /** Parses with PostgreSQL's identifier rules; a convenience for the tests of this package. */
  static Optional<SqlAst> parse(String sql) {
    return parse(sql, Engine.POSTGRESQL);
  }

  /**
   * Parses one statement for an engine left at its default settings, where {@code "x"} quotes an
   * identifier except on MySQL and MariaDB.
   *
   * @param sql the statement
   * @param engine the engine whose identifier rules apply
   * @return the AST, or empty as {@link #parse(String, Engine, boolean)} says
   */
  public static Optional<SqlAst> parse(String sql, Engine engine) {
    return parse(sql, engine, engine.doubleQuotesQuoteIdentifiersByDefault());
  }

  /**
   * Parses one statement. Names local to the statement, CTE names and the table names column
   * qualifiers refer to, are matched the way {@code engine} matches them.
   *
   * @param sql the statement
   * @param engine the engine whose identifier rules apply
   * @param doubleQuotesQuoteIdentifiers whether {@code "x"} quotes an identifier in the session
   *     ({@link Engine#doubleQuotesQuoteIdentifiers}); when it does not, the engine reads {@code
   *     "x"} as a string, while the parser reads it as a name, so such a statement is refused
   * @return the AST, or empty when the text is not exactly one statement, when the parser or the
   *     reference walk cannot handle it, when the syntax tree holds a table the walk missed, or
   *     when a name is double-quoted where the session reads a string
   */
  public static Optional<SqlAst> parse(
      String sql, Engine engine, boolean doubleQuotesQuoteIdentifiers) {
    return analyze(sql, engine, doubleQuotesQuoteIdentifiers).toOptional();
  }

  /**
   * The threads JSQLParser parses on, so that it can time a parse out. Without one it starts and
   * stops a thread per parse, most of a parse's cost (todo 008); a timed-out parse keeps its thread
   * busy, so the pool grows rather than queue, and idle threads end after a minute.
   */
  private static final ExecutorService PARSER =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "scalardb-native-jdbc-parser");
            t.setDaemon(true);
            return t;
          });

  /**
   * Parses one statement as {@link #parse(String, Engine, boolean)} does, and says why when it
   * refuses it.
   *
   * @param sql the statement
   * @param engine the engine whose identifier rules apply
   * @param doubleQuotesQuoteIdentifiers whether {@code "x"} quotes an identifier in the session
   * @return the AST, or the reason it was refused
   */
  public static Outcome<SqlAst> analyze(
      String sql, Engine engine, boolean doubleQuotesQuoteIdentifiers) {
    Statement stmt;
    try {
      // parseStatements, not parse: the latter silently keeps only the first of "a; b"
      Statements statements =
          CCJSqlParserUtil.parseStatements(
              sql, PARSER, p -> p.withSquareBracketQuotation(engine == Engine.SQLSERVER));
      if (statements.size() != 1) {
        return Outcome.refused(
            statements.isEmpty()
                ? "the text holds no statement"
                : "the text holds " + statements.size() + " statements; send one at a time");
      }
      stmt = statements.get(0);
    } catch (Exception e) {
      return Outcome.refused("the statement cannot be parsed: " + firstLine(e));
    }
    if (stmt instanceof Execute) {
      return Outcome.refused(
          "a procedure call cannot be inspected; run it with the passthrough setting if it does"
              + " not touch ScalarDB tables");
    }
    if (stmt instanceof SetStatement) {
      String why = transactionModeSet((SetStatement) stmt);
      if (why != null) {
        return Outcome.refused(why);
      }
    }
    Walker walker = new Walker();
    try {
      walker.getTables(stmt);
    } catch (RuntimeException e) {
      // the walk does not know SET and RESET; they name no table unless an expression holds a
      // subquery, and the check below refuses any table node the walk did not visit
      if (!(stmt instanceof SetStatement) && !(stmt instanceof ResetStatement)) {
        return Outcome.refused("the statement's table references cannot be enumerated");
      }
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
      String name = key(engine, t.getName());
      if (t.getSchemaName() == null
          && cteKeys.contains(name)
          && !isInOwnBody(walker.enclosingCtes.get(t), name, engine)) {
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
    Node root = rootOf(stmt, walker.tables, walker.columns, sql, engine == Engine.SQLSERVER);
    if (root == null) {
      return Outcome.refused("the statement's tokens cannot be located");
    }
    if (engine == Engine.POSTGRESQL && hasUnicodeEscapedIdentifier(root)) {
      return Outcome.refused(
          "a Unicode-escaped identifier (U&\"...\") is not supported: the parser does not read"
              + " the name the database does");
    }
    String comment = commentAnEngineMayExecute(root, engine);
    if (comment != null) {
      return Outcome.refused(comment);
    }
    Set<Object> values = valuesUnder(root);
    boolean setsSession = false;
    for (Object v : values) {
      if (v instanceof Function) {
        List<String> parts = new ArrayList<>();
        for (String p : functionNameParts((Function) v)) {
          parts.add(unquoted(p.strip()).toLowerCase(Locale.ROOT));
        }
        if (engine.runsSqlText(parts)) {
          return Outcome.refused(
              "the function "
                  + String.join(".", parts)
                  + " runs SQL given as text, which cannot be inspected; run it with the"
                  + " passthrough setting if that SQL does not touch ScalarDB tables");
        }
        // PostgreSQL's SET as a function
        setsSession |= !parts.isEmpty() && parts.get(parts.size() - 1).equals("set_config");
      }
    }
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
    for (Table t : accounted) {
      List<String> parts = t.getNameParts();
      // a database-qualified name resolves outside the catalog's database; a quoted name holding
      // a dot is one identifier to the engine and two to the parser
      if (parts != null && parts.size() > 2) {
        return Outcome.refused(
            "the table name "
                + t.getFullyQualifiedName()
                + " names a database; name the schema"
                + " and table only, on a connection to that database");
      }
      if (hasDottedQuotedToken(t.getASTNode())) {
        return Outcome.refused(
            "the quoted table name in " + t.getFullyQualifiedName() + " contains a dot");
      }
    }
    if (!doubleQuotesQuoteIdentifiers) {
      for (Table t : accounted) {
        if (isDoubleQuoted(t.getName()) || isDoubleQuoted(t.getSchemaName())) {
          return Outcome.refused(doubleQuoteReason(engine, t.getFullyQualifiedName()));
        }
      }
    }
    // A placeholder that is the operand of a cast or a sign has no tree node of its own, so the
    // placeholders are the union of what the tree and the walk found.
    Set<Object> parameters = Collections.newSetFromMap(new IdentityHashMap<>());
    parameters.addAll(walker.parameters);
    Set<Column> walked = Collections.newSetFromMap(new IdentityHashMap<>());
    walked.addAll(walker.columns);
    for (Object v : values) {
      if (v instanceof Table && !accounted.contains(v)) {
        return Outcome.refused(
            "the table "
                + ((Table) v).getFullyQualifiedName()
                + " appears in a clause the driver"
                + " does not interpret");
      }
      if (v instanceof JdbcParameter || v instanceof JdbcNamedParameter) {
        parameters.add(v);
      }
      if (v instanceof Column && walked.add((Column) v)) {
        columns.add(new ColumnRef((Column) v, null)); // a column the walk skipped
      }
    }
    for (Column c : walked) {
      if (hasDottedQuotedToken(c.getASTNode())) {
        return Outcome.refused(
            "the quoted column name in " + c.getFullyQualifiedName() + " contains a dot");
      }
    }
    if (!doubleQuotesQuoteIdentifiers) {
      for (Column c : walked) {
        if (isDoubleQuoted(c.getColumnName())) {
          // a string to the engine, a column to the parser
          return Outcome.refused(doubleQuoteReason(engine, c.getColumnName()));
        }
      }
    }
    return Outcome.of(
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
            parameters.stream()
                .allMatch(
                    p -> p instanceof JdbcParameter && !((JdbcParameter) p).isUseFixedIndex()),
            lockingOrInto,
            List.copyOf(forUpdateTables),
            setsSession));
  }

  /**
   * Whether a name's tokens include a quoted identifier with a dot inside, such as {@code "a.b"}.
   */
  private static boolean hasDottedQuotedToken(@Nullable Node n) {
    if (n == null || n.jjtGetFirstToken() == null) {
      return false;
    }
    Token last = n.jjtGetLastToken();
    for (Token t = n.jjtGetFirstToken(); t != null; t = t.next) {
      String image = t.image;
      if (image.length() > 2
          && (image.charAt(0) == '"' || image.charAt(0) == '`' || image.charAt(0) == '[')
          && image.indexOf('.') >= 0) {
        return true;
      }
      if (t == last) {
        break;
      }
    }
    return false;
  }

  private static boolean isDoubleQuoted(@Nullable String name) {
    return name != null && name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"");
  }

  /**
   * Whether a reference named {@code name} sits in the body of a non-recursive CTE of that name,
   * where the name still denotes the table: a CTE body does not see its own name unless it is
   * recursive.
   */
  private static boolean isInOwnBody(@Nullable List<String> enclosing, String name, Engine engine) {
    if (enclosing == null) {
      return false;
    }
    for (String raw : enclosing) {
      if (key(engine, raw).equals(name)) {
        return true;
      }
    }
    return false;
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
   * A comment form the parser reads as a comment but this engine executes, so that the statement
   * the engine would run is not the one that was classified, or {@code null}. Each rule is the
   * engine's own, measured on its container: MySQL and MariaDB execute {@code /*! ...}, MariaDB
   * also {@code /*M! ...}, and both read a {@code --} not followed by whitespace as two minus signs
   * ({@code id = 1 --1} is {@code id = 1 - -1}); MySQL, MariaDB and Oracle end a block comment at
   * its first closer, where the parser, PostgreSQL and SQL Server nest. On the other engines the
   * same text is a comment, as Oracle's {@code --+} hints are.
   */
  private static boolean hasUnicodeEscapedIdentifier(Node root) {
    // the parser reads U&"x" as the column U, a bitwise AND and the name "x", escapes undecoded,
    // where PostgreSQL reads one identifier: refuse U, & and a quoted name written together
    for (Token t = root.jjtGetFirstToken(); t != null; t = t.next) {
      Token amp = t.next;
      Token quoted = amp == null ? null : amp.next;
      if (t.image != null
          && t.image.equalsIgnoreCase("u")
          && quoted != null
          && "&".equals(amp.image)
          && quoted.image != null
          && quoted.image.startsWith("\"")
          && adjacent(t, amp)
          && adjacent(amp, quoted)) {
        return true;
      }
    }
    return false;
  }

  private static boolean adjacent(Token a, Token b) {
    return a.endLine == b.beginLine && a.endColumn + 1 == b.beginColumn;
  }

  private static @Nullable String commentAnEngineMayExecute(Node root, Engine engine) {
    boolean mysqlLike = engine == Engine.MYSQL || engine == Engine.MARIADB;
    boolean flatBlockComments = mysqlLike || engine == Engine.ORACLE;
    for (Token t = root.jjtGetFirstToken(); t != null; t = t.next) {
      for (Token s = t.specialToken; s != null; s = s.specialToken) {
        String image = s.image;
        if (s.kind == CCJSqlParserConstants.MULTI_LINE_COMMENT) {
          // both forms on both engines: a mysql URL may be served by a MariaDB server, which runs
          // /*M! as well, and the URL is all the driver knows
          if (mysqlLike && (image.startsWith("/*!") || image.startsWith("/*M!"))) {
            return "a comment of the form "
                + (image.startsWith("/*!") ? "/*! ... */" : "/*M! ... */")
                + " is executed by MySQL and MariaDB";
          }
          if (flatBlockComments && image.indexOf("/*", 2) >= 0) {
            return "a nested block comment ends at its first */ on " + engine.subProtocol();
          }
        } else if (mysqlLike
            && isLineComment(s)
            && image.length() > 2
            && !Character.isWhitespace(image.charAt(2))) {
          return "\"--\" not followed by a space is not a comment on " + engine.subProtocol();
        }
      }
      if (t.kind == 0) {
        break; // EOF, whose special tokens are the trailing comments
      }
    }
    return null;
  }

  // Where the parser stopped, by token kind and position only: the token's text may be a literal
  // holding data, and the reason becomes an exception message that ends up in logs.
  private static String firstLine(Exception e) {
    Throwable t = e;
    while (t != null && !(t instanceof ParseException)) {
      t = t.getCause();
    }
    if (t != null) {
      Token current = ((ParseException) t).currentToken;
      Token next = current == null ? null : current.next;
      if (next != null) {
        String kind =
            next.kind >= 0 && next.kind < CCJSqlParserConstants.tokenImage.length
                ? CCJSqlParserConstants.tokenImage[next.kind]
                : "token";
        return "unexpected " + kind + " at line " + next.beginLine + ", column " + next.beginColumn;
      }
    }
    return "the parser gave no position";
  }

  private static String doubleQuoteReason(Engine engine, String name) {
    return "the double-quoted name "
        + name
        + " is a string, not a name, in this "
        + engine.subProtocol()
        + " session; use the engine's own quoting"
        + (engine == Engine.SQLSERVER ? " or SET QUOTED_IDENTIFIER ON" : " or ANSI_QUOTES");
  }

  /**
   * The root of the parser's syntax tree, reached from any node of the statement; for a statement
   * without a single node, which can hold neither a table nor a placeholder, a fresh parse of the
   * text supplies the token chain.
   */
  private static @Nullable Node rootOf(
      Statement stmt,
      List<Table> tables,
      List<Column> columns,
      String sql,
      boolean squareBrackets) {
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
      TreeParser parser = new TreeParser(sql);
      parser.withSquareBracketQuotation(squareBrackets);
      parser.Statement();
      return parser.tree();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * The parser with its syntax tree reachable: {@code CCJSqlParserUtil.parseAST} takes no options.
   */
  private static final class TreeParser extends CCJSqlParser {
    TreeParser(String sql) {
      super(new StringProvider(sql));
    }

    Node tree() {
      return jjtree.rootNode();
    }
  }

  /**
   * Returns the kind of statement, decided from the AST type so that a leading comment does not
   * change it.
   *
   * @return the kind
   */
  public Kind kind() {
    if (setsSessionInAnExpression
        || statement instanceof SetStatement
        || statement instanceof ResetStatement
        || statement instanceof UseStatement
        || statement instanceof AlterSession) {
      return Kind.SESSION;
    }
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

  /**
   * The database a {@code USE} statement switches to, unquoted, or {@code null} for any other
   * statement.
   *
   * @return the database
   */
  public @Nullable String useTarget() {
    return statement instanceof UseStatement
        ? unquoted(((UseStatement) statement).getName().strip())
        : null;
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
   * replaced. A replaced table reference without an alias whose name qualifies a column is given
   * its own name as the alias, so the qualifiers keep naming it; a schema in such a qualifier is
   * dropped. The result is parsed again before it is returned.
   *
   * @param naming what to replace, or {@code null} to replace nothing
   * @return the rewritten statement, or {@code null} when a token range cannot be located, when two
   *     renamed tables of one name would need the same alias, when a {@code FOR UPDATE OF} names a
   *     renamed table, or when the result does not parse; in each case nothing has been rewritten
   */
  public @Nullable String joinTokens(@Nullable Naming naming) {
    String joined = joined(naming, false);
    return joined != null && parses(joined) ? joined : null;
  }

  /**
   * Returns the statement with the references {@code naming} names replaced, as {@link
   * #joinTokens(Naming)} does, and its placeholders numbered {@code $1, $2, ...}, as PostgreSQL's
   * {@code EXPLAIN (GENERIC_PLAN)} takes them. Not parsed again: the parser does not read {@code
   * $n}.
   *
   * @param naming what to replace
   * @return the statement, or {@code null} when it cannot be rewritten
   */
  public @Nullable String joinTokensNumbered(Naming naming) {
    return joined(naming, true);
  }

  /** The tokens joined with the references {@code naming} names replaced; not parsed again. */
  private @Nullable String joined(@Nullable Naming naming, boolean numbered) {
    if (root.jjtGetFirstToken() == null || hasMisreadHashComment()) {
      return null;
    }
    Map<Token, String> from = new IdentityHashMap<>();
    Map<Token, Token> to = new IdentityHashMap<>();
    if (naming != null) {
      Rewrite plan = planRewrite(naming, false);
      if (plan.unrepresentable
          || lockTargetIsRenamed(plan)
          || !collectReplacements(plan, from, to)) {
        return null;
      }
    }
    if (numbered) {
      int n = 0;
      for (Token t = root.jjtGetFirstToken(); t != null && t.kind != 0; t = t.next) {
        if (t.image.equals("?")) {
          from.put(t, "$" + ++n);
          to.put(t, t);
        }
      }
      if (n != parameterCount) {
        return null;
      }
    }
    Emitter emitter = new Emitter(from, to, Map.of(), new ArrayList<>());
    emitter.tokens(root.jjtGetFirstToken(), null);
    return emitter.text();
  }

  /**
   * Whether the parser reads {@code sql}: two tokens may only have fused into one if it does not. A
   * replacement on SQL Server is bracket-quoted (Engine.quote), which quotes a name whatever
   * QUOTED_IDENTIFIER says.
   */
  private boolean parses(String sql) {
    try {
      CCJSqlParserUtil.parse(
          sql, PARSER, p -> p.withSquareBracketQuotation(engine == Engine.SQLSERVER));
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** The decisions of a rewrite, shared by the planning and the token emission. */
  private static final class Rewrite {
    final Map<TableRef, String> tables = new LinkedHashMap<>();
    final Map<TableRef, String> aliases = new IdentityHashMap<>();
    final Map<ColumnRef, String> qualifiers = new IdentityHashMap<>();
    final Map<ColumnRef, String> columnNames = new IdentityHashMap<>();
    boolean unrepresentable;
  }

  /**
   * Plans a rewrite; {@code aliasAll} gives every replaced reference without an alias its own name
   * as the alias, as a derived table needs one.
   */
  private Rewrite planRewrite(Naming naming, boolean aliasAll) {
    Rewrite plan = new Rewrite();
    for (TableRef r : tableRefs) {
      String to = naming.replacementFor(r);
      if (to != null) {
        plan.tables.put(r, to);
      }
    }
    // A replaced reference without an alias may be named by a column qualifier. The only alias that
    // keeps such a qualifier meaning the same table is the table's own name, so it gets that one.
    List<TableRef> renamedWithoutAlias = new ArrayList<>();
    Set<String> aliasesGiven = new LinkedHashSet<>();
    for (TableRef r : plan.tables.keySet()) {
      if (r.hasAlias()) {
        continue;
      }
      renamedWithoutAlias.add(r);
      if (aliasAll || usedAsColumnQualifier(r)) {
        if (!aliasesGiven.add(key(engine, r.name()))) {
          plan.unrepresentable = true; // two tables of one name would share the alias
        }
        plan.aliases.put(r, r.name());
      }
    }
    for (ColumnRef c : columnRefs) {
      Table q = c.qualifierNode();
      if (q != null) {
        TableRef target = qualifiedTable(q, renamedWithoutAlias);
        if (target != null && q.getSchemaName() != null) {
          plan.qualifiers.put(c, target.name()); // s.t.x becomes t.x, t being the alias now
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
      if (q != null && qualifies(q, r)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether column qualifier {@code q} names table reference {@code r}: the same name, and the same
   * schema when the qualifier carries one and the reference does too.
   */
  private boolean qualifies(Table q, TableRef r) {
    if (!key(engine, q.getName()).equals(key(engine, r.name()))) {
      return false;
    }
    String qs = q.getSchemaName();
    String rs = r.schema();
    return qs == null || rs == null || key(engine, qs).equals(key(engine, rs));
  }

  private @Nullable TableRef qualifiedTable(Table q, List<TableRef> candidates) {
    for (TableRef r : candidates) {
      if (qualifies(q, r)) {
        return r;
      }
    }
    return null;
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
      // no AS: Oracle rejects it before a table alias, and the bare form is valid on every engine
      from.put(first, alias == null ? e.getValue() : e.getValue() + " " + alias);
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

  /**
   * Joins tokens with the replacements of a rewrite, as {@link #joinTokens(Naming)} does, and
   * records which placeholder of the statement each emitted placeholder is.
   */
  private static final class Emitter {
    private final StringBuilder out = new StringBuilder();
    private final Map<Token, String> from;
    private final Map<Token, Token> to;
    private final Map<Token, Integer> placeholders;
    private final List<Integer> emitted;
    private @Nullable String prev;
    // whether the last appended token is a line comment, which only a newline closes
    private boolean openLineComment;

    Emitter(
        Map<Token, String> from,
        Map<Token, Token> to,
        Map<Token, Integer> placeholders,
        List<Integer> emitted) {
      this.from = from;
      this.to = to;
      this.placeholders = placeholders;
      this.emitted = emitted;
    }

    /** Emits the tokens from {@code first} to {@code last}, or to the end when it is null. */
    void tokens(Token first, @Nullable Token last) {
      Token skipEnd = null;
      for (Token t = first; t != null; t = t.next) {
        for (Token sp : specialTokensOf(t)) {
          appendToken(out, prev, sp.image, openLineComment);
          openLineComment = isLineComment(sp);
          prev = null; // nothing is tightened right after a comment
        }
        if (t.kind == 0 || t.image.isEmpty()) {
          return; // EOF
        }
        if (skipEnd != null) {
          if (t == skipEnd) {
            skipEnd = null; // the rest of a replaced range ends here
          }
        } else if (from.containsKey(t)) {
          text(from.get(t));
          Token end = to.get(t);
          skipEnd = end == t ? null : end;
        } else {
          Integer p = placeholders.get(t);
          if (p != null) {
            emitted.add(p);
          }
          text(t.image);
        }
        if (t == last) {
          return;
        }
      }
    }

    void text(String image) {
      appendToken(out, prev, image, openLineComment);
      openLineComment = false;
      prev = image;
    }

    /** The text; one that ends in a line comment ends with the newline that closes it. */
    String text() {
      String text = out.toString().strip();
      return openLineComment ? text + "\n" : text;
    }
  }

  // ---- the split read (design 4.2)

  /**
   * Unqualified functions that compute one value from one row on every engine. Any other function
   * in the select list or the ORDER BY may be an aggregate, a user-defined one included, which the
   * copies would compute once each.
   */
  private static final Set<String> ROW_FUNCTIONS =
      Set.of(
          "abs",
          "ascii",
          "ceil",
          "ceiling",
          "char",
          "char_length",
          "character_length",
          "charindex",
          "chr",
          "coalesce",
          "concat",
          "concat_ws",
          "date_part",
          "date_trunc",
          "dateadd",
          "datediff",
          "datename",
          "datepart",
          "day",
          "decode",
          "exp",
          "floor",
          "greatest",
          "hour",
          "iif",
          "ifnull",
          "initcap",
          "instr",
          "isnull",
          "lcase",
          "least",
          "left",
          "len",
          "length",
          "ln",
          "locate",
          "log",
          "lower",
          "lpad",
          "ltrim",
          "minute",
          "mod",
          "month",
          "nullif",
          "nvl",
          "nvl2",
          "octet_length",
          "position",
          "power",
          "replace",
          "reverse",
          "right",
          "round",
          "rpad",
          "rtrim",
          "second",
          "sign",
          "sqrt",
          "strpos",
          "substr",
          "substring",
          "to_char",
          "to_date",
          "to_number",
          "to_timestamp",
          "trim",
          "trunc",
          "truncate",
          "ucase",
          "upper",
          "year");

  /**
   * Returns the table references a split read may take copies over: those of the statement's
   * top-level FROM clause that are joined by inner joins, a LEFT JOIN's preserved side included,
   * where each row of the result comes from exactly one row of each. Empty unless the statement is
   * a single SELECT of a shape the split handles: no WITH clause or set operation, no window
   * function, no RIGHT or FULL join, no row numbering, and only {@code ?} placeholders; GROUP BY
   * only over columns, and only the aggregates COUNT, SUM, MIN, MAX and AVG. ORDER BY, a row limit,
   * DISTINCT and the aggregates are applied on top of the copies.
   *
   * @return the references, in the order of the FROM clause
   */
  public List<TableRef> splittableRefs() {
    if (!(statement instanceof PlainSelect) || !positionalParameters) {
      return List.of();
    }
    PlainSelect s = (PlainSelect) statement;
    boolean unsupportedClause =
        s.getFromItem() == null
            || (s.getWithItemsList() != null && !s.getWithItemsList().isEmpty())
            || s.getQualify() != null
            || s.getPreWhere() != null
            || s.getOracleHierarchical() != null
            || s.getWindowDefinitions() != null
            || s.getIntoTables() != null
            || s.getMySqlSelectIntoClause() != null
            || s.getMySqlSqlCalcFoundRows()
            || s.getForClause() != null
            || s.getForXmlPath() != null
            || s.getFirst() != null
            || s.getSkip() != null
            || s.getLimitBy() != null
            || s.getOption() != null
            || s.getLateralViews() != null
            || (s.getDistinct() != null && s.getDistinct().getOnSelectItems() != null);
    if (unsupportedClause) {
      return List.of();
    }
    if (isGrouped(s)) {
      if (grouping(s) == null) {
        return List.of();
      }
    } else {
      RowWise check = new RowWise(false);
      for (Expression e : selectAndOrderBy(s)) {
        e.accept(check, null);
      }
      if (!check.rowWise) {
        return List.of();
      }
    }
    for (ColumnRef c : columnRefs) {
      if (engine == Engine.ORACLE
          && (c.name().equalsIgnoreCase("rownum")
              || c.name().equalsIgnoreCase("nextval")
              || c.name().equalsIgnoreCase("currval"))) {
        return List.of(); // numbered per copy; a sequence is not allowed in a UNION
      }
    }
    for (Token t = root.jjtGetFirstToken(); t != null && t.kind != 0; t = t.next) {
      if (engine == Engine.ORACLE
          && t.image.equals("(")
          && t.next != null
          && t.next.image.equals("+")
          && t.next.next != null
          && t.next.next.image.equals(")")) {
        return List.of(); // (+): an outer join whose nullable side the FROM clause does not show
      }
      if ((engine == Engine.MYSQL || engine == Engine.MARIADB)
          && MYSQL_SELECT_MODIFIERS.contains(t.image.toUpperCase(Locale.ROOT))) {
        return List.of(); // not allowed in a SELECT of a UNION
      }
    }
    Set<Object> split = Collections.newSetFromMap(new IdentityHashMap<>());
    split.add(s.getFromItem());
    if (s.getJoins() != null) {
      for (Join j : s.getJoins()) {
        if (j.isRight() || j.isFull() || (j.isOuter() && !j.isLeft()) || j.isSemi()) {
          return List.of(); // what comes before is on a nullable side
        }
        if (!j.isLeft() && !j.isApply()) {
          split.add(j.getFromItem());
        }
      }
    }
    List<TableRef> out = new ArrayList<>();
    for (TableRef r : tableRefs) {
      if (split.contains(r.node)) {
        out.add(r);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Returns the names, lower-cased and unquoted, of the columns the statement may read from {@code
   * r}: every column qualified by its alias or name, and every unqualified column, whichever table
   * it belongs to. Null when it may read them all: a {@code *} in the select list, a {@code t.*} of
   * it, or a NATURAL or USING join. A split copy reads only these (todo 096); naming too many is
   * harmless, too few would fail the statement or let an unqualified name resolve to another table.
   *
   * @param r a reference of {@link #splittableRefs()}
   * @return the column names, or {@code null} for all
   */
  public @Nullable Set<String> columnsReadFrom(TableRef r) {
    if (!(statement instanceof PlainSelect)) {
      return null;
    }
    PlainSelect s = (PlainSelect) statement;
    for (SelectItem<?> item : s.getSelectItems()) {
      Expression e = item.getExpression();
      if (e instanceof AllColumns && !(e instanceof AllTableColumns)) {
        return null; // a t.* is a column reference, below
      }
    }
    if (s.getJoins() != null) {
      for (Join j : s.getJoins()) {
        if (j.isNatural() || (j.getUsingColumns() != null && !j.getUsingColumns().isEmpty())) {
          return null;
        }
      }
    }
    String self = r.exposedName();
    Set<String> out = new HashSet<>();
    for (ColumnRef c : columnRefs) {
      Table q = c.qualifierNode();
      if (q != null && q.getName() != null && !lowerUnquoted(q.getName()).equals(self)) {
        continue;
      }
      if (c.isAllColumnsOfTable()) {
        return null;
      }
      String name = lowerUnquoted(c.name());
      if (q == null && name.equals(self)) {
        return null; // PostgreSQL reads an unqualified table name as its whole row (todo 110)
      }
      out.add(name);
    }
    return out;
  }

  /**
   * Returns the references the statement reaches through a column that leads one of their indexes,
   * as far as the statement shows: a top-level conjunct of the WHERE clause or of a join's ON
   * clause compares such a column, by = or IN with a list, or with {@code ranges} also by {@code <,
   * <=, >, >=} or BETWEEN, with expressions whose columns all belong to references already reached,
   * or to tables not in {@code leading}, such as plain tables. A range may read much of a table, so
   * the caller asks for ranges separately (todo 112). A column of a reference is one qualified by
   * it, or an unqualified one when it is the only table of the FROM clause; another unqualified
   * column could be anyone's. An approximation that only chooses a plan: a wrong answer costs time,
   * never rows (todos 103, 112).
   *
   * @param leading for references of {@link #splittableRefs()}, the lower-cased names of the
   *     columns that lead an index of their tables
   * @param ranges whether a range reaches a reference too
   * @return the references reached
   */
  public Set<TableRef> reachedByIndex(Map<TableRef, Set<String>> leading, boolean ranges) {
    Set<TableRef> reached = Collections.newSetFromMap(new IdentityHashMap<>());
    if (!(statement instanceof PlainSelect)) {
      return reached;
    }
    PlainSelect s = (PlainSelect) statement;
    boolean only = s.getJoins() == null || s.getJoins().isEmpty();
    Map<String, TableRef> byName = new HashMap<>();
    for (TableRef r : leading.keySet()) {
      byName.put(r.exposedName(), r);
    }
    List<Expression[]> sides = new ArrayList<>(); // a column, then the expressions it meets
    List<Expression> conjuncts = new ArrayList<>();
    conjuncts(s.getWhere(), conjuncts);
    if (s.getJoins() != null) {
      for (Join j : s.getJoins()) {
        if (j.getOnExpressions() != null) {
          for (Expression on : j.getOnExpressions()) {
            conjuncts(on, conjuncts);
          }
        }
      }
    }
    for (Expression e : conjuncts) {
      if (e instanceof EqualsTo
          || (ranges
              && (e instanceof GreaterThan
                  || e instanceof GreaterThanEquals
                  || e instanceof MinorThan
                  || e instanceof MinorThanEquals))) {
        ComparisonOperator c = (ComparisonOperator) e;
        sides.add(new Expression[] {c.getLeftExpression(), c.getRightExpression()});
        sides.add(new Expression[] {c.getRightExpression(), c.getLeftExpression()});
      } else if (ranges && e instanceof Between && !((Between) e).isNot()) {
        Between b = (Between) e;
        sides.add(
            new Expression[] {
              b.getLeftExpression(), b.getBetweenExpressionStart(), b.getBetweenExpressionEnd()
            });
      } else if (e instanceof InExpression
          && !((InExpression) e).isNot()
          && ((InExpression) e).getRightExpression() instanceof ExpressionList) {
        InExpression in = (InExpression) e;
        sides.add(new Expression[] {in.getLeftExpression(), in.getRightExpression()});
      }
    }
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Expression[] side : sides) {
        if (!(side[0] instanceof Column)) {
          continue;
        }
        Column col = (Column) side[0];
        TableRef r = owner(col, byName, only);
        if (r == null
            || reached.contains(r)
            || !Objects.requireNonNull(leading.get(r))
                .contains(lowerUnquoted(col.getColumnName()))) {
          continue;
        }
        boolean fromReached = true;
        for (int i = 1; i < side.length && fromReached; i++) {
          Node n = side[i].getASTNode();
          if (n == null) {
            fromReached = false; // not located: assume the worst
            break;
          }
          for (Object v : valuesUnder(n)) {
            if (v instanceof Column) {
              Column other = (Column) v;
              TableRef o = owner(other, byName, only);
              boolean unknown = other.getTable() == null && !only;
              fromReached &= !unknown && o != r && (o == null || reached.contains(o));
            }
          }
        }
        if (fromReached) {
          reached.add(r);
          grew = true;
        }
      }
    }
    return reached;
  }

  /**
   * The reference of {@code byName} a column belongs to: by its qualifier, or the only table of the
   * FROM clause for an unqualified one; null for another table's column or an unqualified one.
   */
  private static @Nullable TableRef owner(Column c, Map<String, TableRef> byName, boolean only) {
    Table q = c.getTable();
    if (q == null || q.getName() == null) {
      return only && byName.size() == 1 ? byName.values().iterator().next() : null;
    }
    return byName.get(lowerUnquoted(q.getName()));
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

  /** A name as the engine stores it, lower-cased: quotes removed, a doubled quote made single. */
  private static String lowerUnquoted(String name) {
    String stripped = name.strip();
    String bare = unquoted(stripped);
    if (bare.length() < stripped.length()) {
      String close = stripped.substring(stripped.length() - 1);
      bare = bare.replace(close + close, close);
    }
    return bare.toLowerCase(Locale.ROOT);
  }

  private static final Set<String> MYSQL_SELECT_MODIFIERS =
      Set.of(
          "HIGH_PRIORITY",
          "SQL_CACHE",
          "SQL_NO_CACHE",
          "SQL_BUFFER_RESULT",
          "SQL_SMALL_RESULT",
          "SQL_BIG_RESULT");

  /** The aggregates a split read computes per copy or applies on top of the copies. */
  private static final Set<String> AGGREGATES = Set.of("count", "sum", "min", "max", "avg");

  /**
   * Whether an expression computes one value from one row: no aggregate, window function, unknown
   * function or session variable. A subquery is its own scope and is not looked into. With
   * aggregates allowed, the plain aggregates of {@link #AGGREGATES} pass, not nested, and the
   * visitor collects them and the columns outside and inside them; a subquery then fails, since on
   * top of the copies its columns would no longer resolve.
   */
  private static class RowWise extends ExpressionVisitorAdapter<Void> {
    boolean rowWise = true;
    final boolean aggregates;
    final List<Function> found = new ArrayList<>();
    final List<Column> outside = new ArrayList<>();
    final List<Column> inside = new ArrayList<>();
    private int depth;

    RowWise(boolean aggregates) {
      this.aggregates = aggregates;
    }

    @Override
    public <S> Void visit(Function function, S context) {
      String name = bareName(function);
      if (aggregates && AGGREGATES.contains(name)) {
        if (depth > 0
            || function.getKeep() != null
            || function.getOrderByElements() != null
            || function.getHavingClause() != null
            || function.getNullHandling() != null
            || function.getLimit() != null
            || function.isUnique()) {
          rowWise = false;
          return null;
        }
        found.add(function);
        depth++;
        super.visit(function, context);
        depth--;
        return null;
      }
      if (!ROW_FUNCTIONS.contains(name)) {
        rowWise = false;
        return null;
      }
      return super.visit(function, context);
    }

    @Override
    public <S> Void visit(Column column, S context) {
      if (aggregates) {
        (depth > 0 ? inside : outside).add(column);
      }
      return null;
    }

    @Override
    public <S> Void visit(AllTableColumns allTableColumns, S context) {
      // COUNT(t.*): on top of the copies t no longer resolves (todo 109)
      rowWise &= !aggregates;
      return null;
    }

    @Override
    public <S> Void visit(AnalyticExpression e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(MySQLGroupConcat e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(JsonAggregateFunction e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(KeepExpression e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(XMLSerializeExpr e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(UserVariable e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(VariableAssignment e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(ConnectByRootOperator e, S context) {
      rowWise = false;
      return null;
    }

    @Override
    public <S> Void visit(NextValExpression e, S context) {
      rowWise = false; // a sequence is not allowed in a UNION
      return null;
    }

    @Override
    public <S> Void visit(ParenthesedSelect select, S context) {
      rowWise &= !aggregates;
      return null;
    }

    @Override
    public <S> Void visit(Select select, S context) {
      rowWise &= !aggregates;
      return null;
    }
  }

  private static List<Expression> selectAndOrderBy(PlainSelect s) {
    List<Expression> out = new ArrayList<>();
    for (SelectItem<?> item : s.getSelectItems()) {
      if (item.getExpression() != null) {
        out.add(item.getExpression());
      }
    }
    if (s.getOrderByElements() != null) {
      for (OrderByElement o : s.getOrderByElements()) {
        out.add(o.getExpression());
      }
    }
    return out;
  }

  /**
   * Whether the statement groups: GROUP BY, HAVING, or an aggregate in its select list or ORDER BY.
   */
  private static boolean isGrouped(PlainSelect s) {
    if (s.getGroupBy() != null || s.getHaving() != null) {
      return true;
    }
    RowWise check = new RowWise(true);
    for (Expression e : selectAndOrderBy(s)) {
      e.accept(check, null);
    }
    return !check.found.isEmpty();
  }

  /** A grouping statement's aggregates and columns, as the split reads it. */
  private static final class Grouping {
    final List<Column> groupBy = new ArrayList<>();
    List<Function> aggregates = List.of();
    // outside the aggregates, each a GROUP BY column, and inside them
    final List<Column> outside = new ArrayList<>();
    List<Column> inside = List.of();
    // the aggregates computed per copy and combined on top, rather than applied on top
    boolean perCopy;
  }

  /**
   * Reads a grouping statement for the split, or returns null when the split cannot take it: a
   * GROUP BY item that is not a column, a column outside the aggregates that is not a GROUP BY
   * column, a subquery, a {@code *}, or an aggregate other than the plain ones. The aggregates are
   * computed per copy and combined on top when every one decomposes, except on PostgreSQL, where
   * the aggregate over the union measured as fast (design 4.2).
   */
  private @Nullable Grouping grouping(PlainSelect s) {
    RowWise check = new RowWise(true);
    Set<String> labels = new HashSet<>();
    for (SelectItem<?> item : s.getSelectItems()) {
      Expression e = item.getExpression();
      if (e == null || e instanceof AllColumns || e instanceof AllTableColumns) {
        return null;
      }
      e.accept(check, null);
      if (item.getAlias() != null) {
        labels.add(key(engine, item.getAlias().getName()));
      } else if (e instanceof Column) {
        labels.add(key(engine, ((Column) e).getColumnName()));
      }
    }
    if (s.getHaving() != null) {
      s.getHaving().accept(check, null);
    }
    int beforeOrderBy = check.outside.size();
    // an ORDER BY element that is a bare name may name an item on top; inside an expression,
    // PostgreSQL, Oracle and SQL Server do not resolve item names (todo 109)
    Set<Object> bareOrderBy = Collections.newSetFromMap(new IdentityHashMap<>());
    if (s.getOrderByElements() != null) {
      for (OrderByElement o : s.getOrderByElements()) {
        o.getExpression().accept(check, null);
        bareOrderBy.add(o.getExpression());
      }
    }
    if (!check.rowWise) {
      return null;
    }
    Grouping g = new Grouping();
    Set<String> groupKeys = new HashSet<>();
    GroupByElement gb = s.getGroupBy();
    if (gb != null) {
      if ((gb.getGroupingSets() != null && !gb.getGroupingSets().isEmpty())
          || gb.isMysqlWithRollup()
          || gb.hasGroupBySortDirections()
          || gb.getGroupByExpressionList() == null) {
        return null;
      }
      for (Expression e : gb.getGroupByExpressionList()) {
        if (!(e instanceof Column)) {
          return null;
        }
        Column c = (Column) e;
        if (c.getTable() == null
            && labels.contains(key(engine, c.getColumnName()))
            && !isUnaliasedItem(s, c)) {
          return null; // an item's alias, which only MySQL groups by
        }
        g.groupBy.add(c);
        groupKeys.add(columnKey(c));
      }
    }
    for (int i = 0; i < check.outside.size(); i++) {
      Column c = check.outside.get(i);
      if (groupKeys.contains(columnKey(c))) {
        g.outside.add(c);
      } else if (i < beforeOrderBy
          || c.getTable() != null
          || !bareOrderBy.contains(c)
          || !labels.contains(key(engine, c.getColumnName()))) {
        return null; // on top a column must be grouped, or an ORDER BY name an item
      }
    }
    g.aggregates = check.found;
    g.inside = check.inside;
    g.perCopy = engine != Engine.POSTGRESQL;
    for (Function f : g.aggregates) {
      String name = bareName(f);
      // ponytail: SQL Server's AVG of a decimal rounds to a scale SUM / COUNT does not, so AVG is
      // applied on top there; decompose it per type if it matters
      if (f.isDistinct() || (name.equals("avg") && engine == Engine.SQLSERVER)) {
        g.perCopy = false;
      }
    }
    return g;
  }

  /** Whether {@code c} is, by name, an unaliased column item of the select list. */
  private boolean isUnaliasedItem(PlainSelect s, Column c) {
    for (SelectItem<?> item : s.getSelectItems()) {
      if (item.getAlias() == null
          && item.getExpression() instanceof Column
          && columnKey((Column) item.getExpression()).equals(columnKey(c))) {
        return true;
      }
    }
    return false;
  }

  /** A column's identity on top of the copies: its qualifier and name, as the engine compares. */
  private String columnKey(Column c) {
    Table t = c.getTable();
    String schema = t == null || t.getSchemaName() == null ? "" : key(engine, t.getSchemaName());
    return schema
        + "."
        + (t == null || t.getName() == null ? "" : key(engine, t.getName()))
        + "."
        + key(engine, c.getColumnName());
  }

  /** A split read's text, and the statement's placeholder each of its placeholders takes. */
  public static final class SplitText {
    private final String sql;
    private final List<Integer> parameterSources;

    private SplitText(String sql, List<Integer> parameterSources) {
      this.sql = sql;
      this.parameterSources = List.copyOf(parameterSources);
    }

    /**
     * Returns the statement to run.
     *
     * @return the statement
     */
    public String sql() {
      return sql;
    }

    /**
     * Returns, for each placeholder of {@link #sql()} in order, the 1-based index of the
     * statement's placeholder whose value it takes.
     *
     * @return the sources
     */
    public List<Integer> parameterSources() {
      return parameterSources;
    }
  }

  /**
   * Emits a split read: the statement once per copy, each copy rewritten by its naming, joined by
   * UNION ALL. A statement with ORDER BY, a row limit or DISTINCT has them applied on top, over the
   * copies as a derived table whose columns the copies expose under generated names; the select
   * list on top gives each column its label back, which needs an alias or a column for every item
   * and the columns of every table a {@code *} covers. Every replaced reference is given an alias,
   * as a derived table needs one.
   *
   * @param copies one naming per copy, which replaces the references of {@link #splittableRefs()}
   *     with the copy's derived tables and may rename others
   * @param columns the columns of the tables a {@code *} may cover, as the engine names them; a
   *     table without an entry cannot be covered
   * @return the split read, or {@code null} when it cannot be emitted; nothing has been rewritten
   */
  public @Nullable SplitText emitSplit(List<Naming> copies, Map<TableRef, List<String>> columns) {
    Token start = root.jjtGetFirstToken();
    if (start == null
        || copies.isEmpty()
        || !(statement instanceof PlainSelect)
        || hasMisreadHashComment()) {
      return null;
    }
    Map<Token, Integer> placeholders = new IdentityHashMap<>();
    Token last = null;
    for (Token t = start; t != null && t.kind != 0 && !t.image.isEmpty(); t = t.next) {
      if (t.image.equals("?")) {
        placeholders.put(t, placeholders.size() + 1);
      }
      last = t;
    }
    if (placeholders.size() != parameterCount || last == null || last.image.equals(";")) {
      return null;
    }
    List<Map<Token, String>> froms = new ArrayList<>();
    List<Map<Token, Token>> tos = new ArrayList<>();
    for (Naming n : copies) {
      Rewrite plan = planRewrite(n, true);
      Map<Token, String> from = new IdentityHashMap<>();
      Map<Token, Token> to = new IdentityHashMap<>();
      if (plan.unrepresentable
          || lockTargetIsRenamed(plan)
          || !collectReplacements(plan, from, to)) {
        return null;
      }
      froms.add(from);
      tos.add(to);
    }
    PlainSelect s = (PlainSelect) statement;
    Grouping grouping = null;
    if (isGrouped(s)) {
      grouping = grouping(s);
      if (grouping == null) {
        return null;
      }
    }
    boolean onTop =
        grouping != null
            || s.getDistinct() != null
            || s.getTop() != null
            || s.getLimit() != null
            || s.getOffset() != null
            || s.getFetch() != null
            || s.getOrderByElements() != null;
    List<Integer> sources = new ArrayList<>();
    if (!onTop) {
      List<String> texts = new ArrayList<>();
      for (int c = 0; c < copies.size(); c++) {
        Emitter e = new Emitter(froms.get(c), tos.get(c), placeholders, sources);
        e.tokens(start, null);
        texts.add(e.text());
      }
      // the copies differ only in the derived tables the naming gives
      return parses(texts.get(0)) ? checked(String.join(" UNION ALL ", texts), sources) : null;
    }
    Lift lift = grouping != null ? liftGrouped(s, grouping) : lift(s, columns);
    if (lift == null) {
      return null;
    }
    // emitted in the order of the text, so that the placeholders' sources follow it
    Emitter prefix = new Emitter(froms.get(0), tos.get(0), placeholders, sources);
    if (start.next != lift.firstItem) {
      if (lift.beforeFirstItem == null) {
        return null;
      }
      prefix.tokens(start.next, lift.beforeFirstItem);
    }
    Map<Token, String> topFrom = new IdentityHashMap<>(froms.get(0));
    Map<Token, Token> topTo = new IdentityHashMap<>(tos.get(0));
    topFrom.putAll(lift.tailFrom);
    topTo.putAll(lift.tailTo);
    Emitter top = new Emitter(topFrom, topTo, placeholders, sources);
    for (int i = 0; i < lift.outer.size(); i++) {
      if (i > 0) {
        top.text(",");
      }
      lift.outer.get(i).emit(top);
    }
    CopyLimit limit = copyLimit(s); // none for a grouped statement
    if (limit != null && (lift.copyOrder.isEmpty() || lift.copyOrder.contains(null))) {
      limit = null;
    }
    List<String> texts = new ArrayList<>();
    for (int c = 0; c < copies.size(); c++) {
      Emitter e = new Emitter(froms.get(c), tos.get(c), placeholders, sources);
      if (limit != null) {
        e.text("SELECT");
        e.text("*");
        e.text("FROM");
        e.text("(");
      }
      e.text("SELECT");
      for (int i = 0; i < lift.inner.size(); i++) {
        if (i > 0) {
          e.text(",");
        }
        lift.inner.get(i).emit(e);
      }
      e.tokens(lift.bodyStart, lift.bodyEnd);
      if (limit != null) {
        // each copy sorts and stops after the rows the limit on top can take from it
        e.text("ORDER BY " + String.join(", ", lift.copyOrder));
        if (engine == Engine.ORACLE) {
          // FETCH FIRST would number the whole copy; ROWNUM over the ordered copy stops early
          e.text(")");
          e.text("WHERE ROWNUM <=");
          limit.emit(e);
        } else {
          e.text("LIMIT");
          limit.emit(e);
          e.text(")");
          e.text("p" + (c + 1));
        }
      }
      texts.add(e.text());
    }
    Emitter tail = new Emitter(topFrom, topTo, placeholders, sources);
    if (!lift.tailLead.isEmpty()) {
      tail.text(lift.tailLead);
    }
    if (lift.bodyEnd.next != null) {
      tail.tokens(lift.bodyEnd.next, null);
    }
    String prefixText = prefix.text();
    String head =
        "SELECT " + (prefixText.isEmpty() ? "" : prefixText + " ") + top.text() + " FROM (";
    String end = (") v " + tail.text()).strip();
    if (!parses(head + texts.get(0) + end)) {
      return null;
    }
    return checked(head + String.join(" UNION ALL ", texts) + end, sources);
  }

  /**
   * The split read, if its text holds exactly the placeholders it was emitted with, which a comment
   * swallowing a copy would break, and no more than the engine takes. Without a comment in the
   * statement, the copies repeat its tokens, as the first copy parsed, around the driver's own
   * fragments, which hold no placeholder: lexing the whole text, most of an uncached route's time
   * (todo 008), would only count what was emitted.
   */
  private @Nullable SplitText checked(String sql, List<Integer> sources) {
    // SQL Server takes 2100 parameters; the others' protocols 65535
    int max = engine == Engine.SQLSERVER ? 2100 : 65535;
    if (sources.size() > max) {
      return null;
    }
    if (!hasComments()) {
      return new SplitText(sql, sources);
    }
    CCJSqlParser lexer =
        CCJSqlParserUtil.newParser(sql).withSquareBracketQuotation(engine == Engine.SQLSERVER);
    int placeholders = 0;
    for (Token t = lexer.getNextToken(); t.kind != 0; t = lexer.getNextToken()) {
      if (t.image.equals("?")) {
        placeholders++;
      }
    }
    return placeholders == sources.size() ? new SplitText(sql, sources) : null;
  }

  /**
   * Tokens to emit, from {@code first} to {@code last} when first is not null, inside {@code
   * wrap(...)} when wrap is not null, then text.
   */
  private static final class Piece {
    final @Nullable String wrap;
    final @Nullable Token first;
    final @Nullable Token last;
    final String text;

    Piece(@Nullable String wrap, @Nullable Token first, @Nullable Token last, String text) {
      this.wrap = wrap;
      this.first = first;
      this.last = last;
      this.text = text;
    }

    Piece(@Nullable Token first, @Nullable Token last, String text) {
      this(null, first, last, text);
    }

    Piece(String text) {
      this(null, null, null, text);
    }

    void emit(Emitter e) {
      if (wrap != null) {
        e.text(wrap);
        e.text("(");
      }
      if (first != null) {
        e.tokens(first, last);
      }
      if (wrap != null) {
        e.text(")");
      }
      if (!text.isEmpty()) {
        e.text(text);
      }
    }
  }

  /** How a statement's ORDER BY, row limit and DISTINCT go on top of its copies. */
  private static final class Lift {
    final Token firstItem;
    final @Nullable Token beforeFirstItem;
    final Token bodyStart;
    final Token bodyEnd;
    final List<Piece> inner = new ArrayList<>();
    final List<Piece> outer = new ArrayList<>();
    // the statement's ORDER BY in the copies' own column names; a null where one cannot be named
    final List<@Nullable String> copyOrder = new ArrayList<>();
    // replacements in the select list on top and in the tail
    final Map<Token, String> tailFrom = new IdentityHashMap<>();
    final Map<Token, Token> tailTo = new IdentityHashMap<>();
    // emitted on top before the tail's tokens
    String tailLead = "";

    Lift(Token firstItem, @Nullable Token beforeFirstItem, Token bodyStart, Token bodyEnd) {
      this.firstItem = firstItem;
      this.beforeFirstItem = beforeFirstItem;
      this.bodyStart = bodyStart;
      this.bodyEnd = bodyEnd;
    }
  }

  /** The node a copy's body ends with: the WHERE clause, the last join or the FROM item. */
  private static @Nullable Node bodyEnd(PlainSelect s) {
    return s.getWhere() != null
        ? s.getWhere().getASTNode()
        : s.getJoins() != null && !s.getJoins().isEmpty()
            ? s.getJoins().get(s.getJoins().size() - 1).getASTNode()
            : s.getFromItem().getASTNode();
  }

  /** A lift's token positions: the select list, and the body up to {@code bodyEndNode}. */
  private @Nullable Lift opening(PlainSelect s, @Nullable Node bodyEndNode) {
    List<SelectItem<?>> items = s.getSelectItems();
    Node firstNode = items.get(0).getASTNode();
    Node lastNode = items.get(items.size() - 1).getASTNode();
    if (firstNode == null || lastNode == null || bodyEndNode == null) {
      return null;
    }
    Token firstItem = firstNode.jjtGetFirstToken();
    Token lastItem = lastNode.jjtGetLastToken();
    Token bodyEnd = bodyEndNode.jjtGetLastToken();
    if (firstItem == null || lastItem == null || lastItem.next == null || bodyEnd == null) {
      return null;
    }
    return new Lift(firstItem, before(root.jjtGetFirstToken(), firstItem), lastItem.next, bodyEnd);
  }

  private @Nullable Lift lift(PlainSelect s, Map<TableRef, List<String>> columns) {
    List<SelectItem<?>> items = s.getSelectItems();
    Lift lift = opening(s, bodyEnd(s));
    if (lift == null) {
      return null;
    }
    // the FROM clause's tables in order, for a *
    List<TableRef> fromRefs = new ArrayList<>();
    boolean merged = false; // NATURAL or USING: * shows the joined columns once
    Map<Object, TableRef> byNode = new IdentityHashMap<>();
    for (TableRef r : tableRefs) {
      byNode.put(r.node, r);
    }
    List<Object> fromItems = new ArrayList<>();
    fromItems.add(s.getFromItem());
    if (s.getJoins() != null) {
      for (Join j : s.getJoins()) {
        fromItems.add(j.getFromItem());
        merged |= j.isNatural() || (j.getUsingColumns() != null && !j.getUsingColumns().isEmpty());
      }
    }
    for (Object f : fromItems) {
      fromRefs.add(byNode.get(f)); // null for a subquery or another item
    }
    List<String> labels = new ArrayList<>();
    for (SelectItem<?> item : items) {
      Expression e = item.getExpression();
      if (e instanceof AllColumns || e instanceof AllTableColumns) {
        if (merged) {
          return null;
        }
        Table q = e instanceof AllTableColumns ? ((AllTableColumns) e).getTable() : null;
        boolean found = false;
        for (TableRef r : fromRefs) {
          if (r == null) {
            if (q == null) {
              return null; // a * over an item whose columns are not known
            }
            continue;
          }
          if (q != null && !(r.hasAlias() ? sameAlias(q, r) : qualifies(q, r))) {
            continue;
          }
          List<String> cols = columns.get(r);
          if (cols == null) {
            return null;
          }
          found = true;
          String qualifier = r.hasAlias() ? Objects.requireNonNull(r.alias()) : r.name();
          for (String col : cols) {
            String name = engine.quote(col);
            String generated = "c" + (lift.outer.size() + 1);
            lift.inner.add(new Piece(null, null, qualifier + "." + name + " " + "AS " + generated));
            lift.outer.add(new Piece("v." + generated + " AS " + name));
            labels.add(col);
          }
        }
        if (!found) {
          return null;
        }
        continue;
      }
      Node n = item.getASTNode();
      if (n == null || n.jjtGetFirstToken() == null || n.jjtGetLastToken() == null) {
        return null;
      }
      Token first = n.jjtGetFirstToken();
      Token end = n.jjtGetLastToken();
      String label;
      Alias alias = item.getAlias();
      if (alias != null) {
        if (!end.image.equals(alias.getName()) || alias.getAliasColumns() != null) {
          return null;
        }
        end = before(first, end);
        if (end != null && alias.isUseAs()) {
          end = before(first, end);
        }
        if (end == null) {
          return null;
        }
        label = alias.getName();
      } else if (e instanceof Column) {
        label = columnLabel((Column) e);
      } else {
        return null; // the engine's label for an expression differs by engine
      }
      String generated = "c" + (lift.outer.size() + 1);
      lift.inner.add(new Piece(first, end, "AS " + generated));
      lift.outer.add(new Piece("v." + generated + " AS " + label));
      labels.add(label);
    }
    if (s.getOrderByElements() != null) {
      int hidden = 0;
      for (OrderByElement o : s.getOrderByElements()) {
        Expression e = o.getExpression();
        if (e instanceof LongValue) {
          // a position in the select list on top, which keeps its order
          long position = ((LongValue) e).getValue();
          lift.copyOrder.add(
              position >= 1 && position <= lift.outer.size()
                  ? "c" + position + direction(o)
                  : null);
          continue;
        }
        if (e instanceof Column && ((Column) e).getTable() == null) {
          String name = key(engine, ((Column) e).getColumnName());
          int matches = 0;
          int at = -1;
          for (int i = 0; i < labels.size(); i++) {
            if (key(engine, labels.get(i)).equals(name)) {
              matches++;
              at = i;
            }
          }
          if (matches > 1) {
            return null; // ambiguous on top, where the copies' items are distinct columns
          }
          if (matches == 1) {
            lift.copyOrder.add("c" + (at + 1) + direction(o));
            continue; // names a column on top
          }
        }
        Node n = e.getASTNode();
        if (n == null || n.jjtGetFirstToken() == null || n.jjtGetLastToken() == null) {
          return null;
        }
        if (s.getDistinct() != null) {
          return null; // on top, DISTINCT may sort only by what it selects
        }
        String generated = "s" + ++hidden;
        lift.copyOrder.add(generated + direction(o));
        lift.inner.add(new Piece(n.jjtGetFirstToken(), n.jjtGetLastToken(), "AS " + generated));
        lift.tailFrom.put(n.jjtGetFirstToken(), "v." + generated);
        lift.tailTo.put(n.jjtGetFirstToken(), n.jjtGetLastToken());
      }
    }
    return lift;
  }

  /** An ORDER BY element's direction and null ordering, as written. */
  private static String direction(OrderByElement o) {
    String out = o.isAscDescPresent() ? (o.isAsc() ? " ASC" : " DESC") : "";
    if (o.getNullOrdering() == OrderByElement.NullOrdering.NULLS_FIRST) {
      out += " NULLS FIRST";
    } else if (o.getNullOrdering() == OrderByElement.NullOrdering.NULLS_LAST) {
      out += " NULLS LAST";
    }
    return out;
  }

  /**
   * How many rows each copy needs of a statement with ORDER BY and a row limit, so that each copy
   * sorts and stops early as well (todo 089): the offset plus the count when both are literals, or
   * the count's placeholder when there is no offset. Null when the copies cannot stop early: no
   * limit, a DISTINCT or an aggregate on top, a limit that is not a plain row count, and on SQL
   * Server, whose plan already stops early over the union.
   */
  private @Nullable CopyLimit copyLimit(PlainSelect s) {
    if (engine == Engine.SQLSERVER
        || s.getOrderByElements() == null
        || s.getDistinct() != null
        || s.getTop() != null
        || isGrouped(s)) {
      return null;
    }
    Expression count = null;
    Expression offset = null;
    if (s.getLimit() != null) {
      Limit l = s.getLimit();
      if (l.getByExpressions() != null) {
        return null; // LIMIT ALL and LIMIT NULL have no number, which leaves them out below
      }
      count = l.getRowCount();
      offset = l.getOffset();
    }
    if (s.getFetch() != null) {
      Fetch f = s.getFetch();
      for (String p : f.getFetchParameters()) {
        String u = p.toUpperCase(Locale.ROOT);
        if (u.contains("PERCENT") || u.contains("TIES")) {
          return null;
        }
      }
      if (count != null) {
        return null;
      }
      count = f.getExpression();
    }
    if (s.getOffset() != null) {
      if (offset != null) {
        return null;
      }
      offset = s.getOffset().getOffset();
    }
    BigInteger skipped = BigInteger.ZERO;
    if (offset != null) {
      if (!(offset instanceof LongValue)) {
        return null;
      }
      skipped = ((LongValue) offset).getBigIntegerValue();
    }
    if (count instanceof LongValue) {
      // a count beyond a long, such as MySQL's 18446744073709551615 for "to the end", stops nothing
      BigInteger rows = skipped.add(((LongValue) count).getBigIntegerValue());
      return rows.bitLength() < 64 ? new CopyLimit(null, rows.longValue()) : null;
    }
    if (count instanceof JdbcParameter && skipped.signum() == 0) {
      Node n = count.getASTNode();
      Token t = n == null ? null : n.jjtGetFirstToken();
      return t == null || !t.image.equals("?") ? null : new CopyLimit(t, 0);
    }
    return null;
  }

  /** The rows each copy needs: a literal count, or the statement's placeholder for it. */
  private static final class CopyLimit {
    final @Nullable Token placeholder;
    final long rows;

    CopyLimit(@Nullable Token placeholder, long rows) {
      this.placeholder = placeholder;
      this.rows = rows;
    }

    void emit(Emitter e) {
      if (placeholder != null) {
        e.tokens(placeholder, placeholder);
      } else {
        e.text(Long.toString(rows));
      }
    }
  }

  /**
   * Returns whether each copy of the split read sorts and stops after the rows the statement's
   * limit needs, rather than only the union on top (todo 089).
   *
   * @return whether the copies apply the limit too
   */
  public boolean limitsEachCopy() {
    return statement instanceof PlainSelect && copyLimit((PlainSelect) statement) != null;
  }

  /**
   * Returns whether each copy stops after at most {@code rows} rows: a literal limit within it, or
   * a count placeholder, taken as a page.
   *
   * @param rows the most rows
   * @return whether the copies stop that early
   */
  public boolean limitsEachCopyTo(long rows) {
    if (!(statement instanceof PlainSelect)) {
      return false;
    }
    CopyLimit l = copyLimit((PlainSelect) statement);
    return l != null && (l.placeholder != null || l.rows <= rows);
  }

  /** A column item's label on top; a reserved word may follow a qualifier on MySQL, but not AS. */
  private String columnLabel(Column c) {
    String label = c.getColumnName();
    if ((engine == Engine.MYSQL || engine == Engine.MARIADB)
        && !label.startsWith("`")
        && !label.startsWith("\"")) {
      label = engine.quote(label);
    }
    return label;
  }

  /**
   * How a grouping statement goes on top of its copies. Per copy, each copy selects its GROUP BY
   * columns and a partial result per aggregate, which the statement on top groups again and
   * combines: COUNT as SUM, AVG as SUM over COUNT, the others as themselves. Otherwise each copy
   * selects the columns the statement on top uses, and the GROUP BY, HAVING and aggregates all
   * apply on top. Either way the select list on top is the statement's own, its columns and
   * aggregates replaced, with an unaliased item's label kept as the engine would give it.
   */
  private @Nullable Lift liftGrouped(PlainSelect s, Grouping g) {
    List<SelectItem<?>> items = s.getSelectItems();
    Lift lift =
        opening(
            s,
            g.perCopy && !g.groupBy.isEmpty()
                ? g.groupBy.get(g.groupBy.size() - 1).getASTNode()
                : bodyEnd(s));
    if (lift == null) {
      return null;
    }
    Map<String, String> generated = new LinkedHashMap<>(); // column key to its column on top
    List<Column> selected = new ArrayList<>(g.groupBy);
    if (!g.perCopy) {
      selected.addAll(g.outside);
      selected.addAll(g.inside);
    }
    String prefix = g.perCopy ? "g" : "c";
    for (Column c : selected) {
      String k = columnKey(c);
      if (generated.containsKey(k)) {
        continue;
      }
      Node n = c.getASTNode();
      if (n == null || n.jjtGetFirstToken() == null || n.jjtGetLastToken() == null) {
        return null;
      }
      String name = prefix + (generated.size() + 1);
      lift.inner.add(new Piece(n.jjtGetFirstToken(), n.jjtGetLastToken(), "AS " + name));
      generated.put(k, name);
    }
    List<Column> replaced = new ArrayList<>(g.outside);
    if (g.perCopy) {
      if (!g.groupBy.isEmpty()) {
        lift.tailLead = "GROUP BY v." + String.join(", v.", generated.values());
      }
      int n = 0;
      for (Function f : g.aggregates) {
        Node fn = f.getASTNode();
        Token first = fn == null ? null : fn.jjtGetFirstToken();
        Token last = fn == null ? null : fn.jjtGetLastToken();
        if (first == null || last == null) {
          return null;
        }
        String name = bareName(f);
        String a = "a" + ++n;
        String combined;
        if (name.equals("avg")) {
          Token open = first.next;
          Token argLast = before(first, last);
          if (open == null || !open.image.equals("(") || open.next == null || argLast == open) {
            return null;
          }
          String b = "a" + ++n;
          lift.inner.add(new Piece("SUM", open.next, argLast, "AS " + a));
          lift.inner.add(new Piece("COUNT", open.next, argLast, "AS " + b));
          combined = "SUM(v." + a + ") / NULLIF(SUM(v." + b + "), 0)";
        } else {
          lift.inner.add(new Piece(first, last, "AS " + a));
          combined =
              (name.equals("count") ? "SUM" : name.toUpperCase(Locale.ROOT)) + "(v." + a + ")";
          if (name.equals("count") && (engine == Engine.MYSQL || engine == Engine.MARIADB)) {
            combined =
                "CAST(" + combined + " AS SIGNED)"; // SUM would make COUNT's BIGINT a DECIMAL
          }
        }
        lift.tailFrom.put(first, combined);
        lift.tailTo.put(first, last);
      }
    } else {
      if (generated.isEmpty()) {
        lift.inner.add(new Piece("1 AS c1"));
      }
      replaced.addAll(g.groupBy);
      replaced.addAll(g.inside);
    }
    for (Column c : replaced) {
      Node n = c.getASTNode();
      if (n == null || n.jjtGetFirstToken() == null || n.jjtGetLastToken() == null) {
        return null;
      }
      lift.tailFrom.put(n.jjtGetFirstToken(), "v." + generated.get(columnKey(c)));
      lift.tailTo.put(n.jjtGetFirstToken(), n.jjtGetLastToken());
    }
    for (SelectItem<?> item : items) {
      Node n = item.getASTNode();
      Token first = n == null ? null : n.jjtGetFirstToken();
      Token last = n == null ? null : n.jjtGetLastToken();
      if (first == null || last == null) {
        return null;
      }
      Expression e = item.getExpression();
      String label = "";
      if (item.getAlias() != null) {
        if (item.getAlias().getAliasColumns() != null) {
          return null;
        }
      } else if (e instanceof Column) {
        label = columnLabel((Column) e);
      } else if (replacesWithin(lift, first, last)) {
        label = expressionLabel(e, first, last);
        if (label == null) {
          return null;
        }
      }
      lift.outer.add(new Piece(first, last, label.isEmpty() ? "" : "AS " + label));
    }
    return lift;
  }

  private static boolean replacesWithin(Lift lift, Token first, Token last) {
    for (Token t = first; t != null; t = t.next) {
      if (lift.tailFrom.containsKey(t)) {
        return true;
      }
      if (t == last) {
        break;
      }
    }
    return false;
  }

  /**
   * The alias that gives a rewritten unaliased expression the label the engine gives the original,
   * empty when the engine's label survives the rewrite, or null when it cannot be given: SQL Server
   * labels no expression, PostgreSQL labels a function call by its name, and MySQL, MariaDB and
   * Oracle by the expression's text.
   */
  private @Nullable String expressionLabel(Expression e, Token first, Token last) {
    if (engine != Engine.SQLSERVER && engine != Engine.POSTGRESQL) {
      for (Token t = first; t != null; t = t.next) {
        if (t.image.equals("?")) {
          return null; // the engine labels the text with the value bound in its place (todo 109)
        }
        if (t == last) {
          break;
        }
      }
    }
    switch (engine) {
      case SQLSERVER:
        return "";
      case POSTGRESQL:
        return e instanceof Function ? "" : null;
      case MYSQL:
      case MARIADB:
        {
          String text = sourceText(first, last);
          return text == null || text.length() > 64 ? null : engine.quote(text);
        }
      case ORACLE:
        {
          String text = sourceText(first, last);
          if (text == null) {
            return null;
          }
          String label = oracleLabel(text);
          // a quoted identifier has no quoted spelling: Oracle refuses " inside one
          return label.length() > 128 || label.indexOf('"') >= 0 ? null : engine.quote(label);
        }
    }
    throw new AssertionError(engine);
  }

  /**
   * Oracle's label for an unaliased expression: its text upper-cased, blanks removed, outside
   * quotes.
   */
  private static String oracleLabel(String text) {
    StringBuilder out = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quote != 0) {
        out.append(ch);
        if (ch == quote) {
          quote = 0;
        }
      } else if (ch == '\'' || ch == '"') {
        quote = ch;
        out.append(ch);
      } else if (!Character.isWhitespace(ch)) {
        out.append(Character.toUpperCase(ch));
      }
    }
    return out.toString();
  }

  /** The statement's text from {@code first} to {@code last}, or null when it cannot be located. */
  private @Nullable String sourceText(Token first, Token last) {
    if (source.indexOf('\t') >= 0) {
      return null; // the parser counts a tab as more than one column
    }
    List<Integer> lineStarts = new ArrayList<>();
    lineStarts.add(0);
    for (int i = 0; i < source.length(); i++) {
      char ch = source.charAt(i);
      if (ch == '\n'
          || (ch == '\r' && (i + 1 == source.length() || source.charAt(i + 1) != '\n'))) {
        lineStarts.add(i + 1);
      }
    }
    if (first.beginLine < 1 || last.endLine > lineStarts.size() || first.beginLine > last.endLine) {
      return null;
    }
    int b = lineStarts.get(first.beginLine - 1) + first.beginColumn - 1;
    int e = lineStarts.get(last.endLine - 1) + last.endColumn;
    if (b < 0 || e > source.length() || b >= e) {
      return null;
    }
    String text = source.substring(b, e);
    return text.startsWith(first.image) && text.endsWith(last.image) ? text : null;
  }

  private boolean sameAlias(Table q, TableRef r) {
    return q.getSchemaName() == null
        && key(engine, q.getName()).equals(key(engine, Objects.requireNonNull(r.alias())));
  }

  /**
   * Whether the statement has a {@code #}, which starts a comment on MySQL and MariaDB but which
   * the parser reads as an operator: re-emitted, the comment's text loses the newline that ends it
   * and comments out what follows it.
   */
  private boolean hasMisreadHashComment() {
    if (engine != Engine.MYSQL && engine != Engine.MARIADB) {
      return false;
    }
    for (Token t = root.jjtGetFirstToken(); t != null && t.kind != 0; t = t.next) {
      if (t.image.equals("#")) {
        return true;
      }
    }
    return false;
  }

  /** Whether the statement holds a comment, a hint included, anywhere, after its end too. */
  private boolean hasComments() {
    for (Token t = root.jjtGetFirstToken(); t != null; t = t.next) {
      if (t.specialToken != null) {
        return true;
      }
      if (t.kind == 0) {
        break;
      }
    }
    return false;
  }

  /** The token just before {@code target}, searching from {@code first}; null if none. */
  private static @Nullable Token before(Token first, Token target) {
    for (Token t = first; t != null && t.next != null; t = t.next) {
      if (t.next == target) {
        return t;
      }
    }
    return null;
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

    /** The name the statement refers to it by, lower-cased: its alias, or else its name. */
    String exposedName() {
      return lowerUnquoted(hasAlias() ? Objects.requireNonNull(alias()) : name());
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
     * Returns whether the reference carries a hint, a sample clause, a PIVOT or a database link,
     * which a table renamed to another object may not carry: {@code t@remote} names a table
     * elsewhere, and a hint such as SQL Server's {@code NOLOCK} changes how the rows are read.
     *
     * @return whether it has one
     */
    public boolean hasHintOrLink() {
      return node.getIndexHint() != null
          || node.getSqlServerHints() != null
          || node.getSampleClause() != null
          || node.getPivot() != null
          || node.getUnPivot() != null
          || hasDatabaseLink(node);
    }

    /**
     * {@code t@remote}: the lexer keeps the link inside the name token (and {@code getDBLinkName()}
     * answers the table's own name for a plain table), and a quoted name holding an {@code @} is
     * mangled by the parser; both are refused by looking at the token.
     */
    private static boolean hasDatabaseLink(Table t) {
      Node n = t.getASTNode();
      return n != null
          && n.jjtGetLastToken() != null
          && n.jjtGetLastToken().image.indexOf('@') >= 0;
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

    /** For each table node, the non-recursive CTEs whose bodies contain it, names as written. */
    final Map<Table, List<String>> enclosingCtes = new IdentityHashMap<>();

    private final Deque<String> withBodies = new ArrayDeque<>();

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
      boolean scoped = withItem.getAlias() != null && !withItem.isRecursive();
      if (scoped) {
        withBodies.push(withItem.getAlias().getName());
      }
      try {
        return super.visit(withItem, context);
      } finally {
        if (scoped) {
          withBodies.pop();
        }
      }
    }

    @Override
    public <S> Void visit(Table table, S context) {
      tables.add(table);
      if (!withBodies.isEmpty()) {
        enclosingCtes.computeIfAbsent(table, k -> new ArrayList<>()).addAll(withBodies);
      }
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

  /** A function's name, lower-cased and unquoted; empty when it is qualified. */
  private static String bareName(Function f) {
    List<String> parts = functionNameParts(f);
    return parts.size() == 1 ? unquoted(parts.get(0).strip()).toLowerCase(Locale.ROOT) : "";
  }

  /**
   * The parts of a function's name. The parser leaves them out for a table function with a column
   * definition list, {@code f(...) AS r(a int)}, so they are then read from its text.
   */
  private static List<String> functionNameParts(Function f) {
    List<String> parts = f.getMultipartName();
    if (parts != null) {
      return parts;
    }
    String text = f.toString();
    int paren = text.indexOf('(');
    return List.of((paren < 0 ? text : text.substring(0, paren)).split("\\."));
  }

  /**
   * Why a SET is refused for setting the transaction mode, which the JDBC methods carry, or null.
   * The parser reads {@code SET GLOBAL v = x} as a variable named GLOBAL and a second assignment of
   * {@code SET a = 1, b = 2} as part of the first value, so both forms are refused outright: they
   * would hide the variable they set.
   */
  private static @Nullable String transactionModeSet(SetStatement set) {
    for (int i = 0; i < set.getKeyValuePairs().size(); i++) {
      Object rawName = set.getName(i);
      String name = rawName == null ? "" : rawName.toString().strip().toLowerCase(Locale.ROOT);
      if (name.equals("global") || name.equals("persist") || name.equals("persist_only")) {
        return "a SET of a server-wide setting is not run through the driver; use the passthrough"
            + " setting";
      }
      for (Expression value : set.getExpressions(i)) {
        if (value instanceof VariableAssignment) {
          return "a SET of several variables in one statement is not supported; set one per"
              + " statement";
        }
      }
      name = name.replaceFirst("^@@", "").replaceFirst("^(session|local|global)\\.", "");
      if (name.equals("autocommit")
          || name.equals("completion_type")
          || name.equals("implicit_transactions")
          || name.startsWith("transaction")
          || name.startsWith("tx_")
          || name.startsWith("default_transaction")) {
        return "the transaction mode is set through Connection.setAutoCommit and"
            + " Connection.setTransactionIsolation, not in SQL";
      }
    }
    return null;
  }

  /** A name part without its quotes, in any of the engines' quoting styles. */
  private static String unquoted(String part) {
    if (part.length() >= 2) {
      char first = part.charAt(0);
      char last = part.charAt(part.length() - 1);
      if ((first == '"' && last == '"')
          || (first == '`' && last == '`')
          || (first == '[' && last == ']')) {
        return part.substring(1, part.length() - 1);
      }
    }
    return part;
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
