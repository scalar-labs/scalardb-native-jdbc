package com.scalar.db.nativejdbc.engine;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One of the database engines the driver supports, with the rules that differ between them. Every
 * rule here was measured against the engine itself (docs/design.md, "Identifiers per engine"):
 *
 * <ul>
 *   <li><b>Folding.</b> An unquoted identifier is folded to lower case by PostgreSQL, to upper case
 *       by Oracle, and kept as written by MySQL, MariaDB and SQL Server. A quoted identifier is
 *       always kept as written.
 *   <li><b>Column comparison.</b> PostgreSQL and Oracle compare the folded names exactly; MySQL,
 *       MariaDB and SQL Server compare column names without regard to case.
 *   <li><b>Table resolution.</b> Which stored table a name denotes is the engine's decision: it
 *       depends on the session's search path, current database or current schema, on default-schema
 *       fallbacks, on synonyms, and on which plain tables exist. {@link #resolve} asks the engine's
 *       own catalog and returns the stored spelling, so that nothing here guesses.
 * </ul>
 */
public enum Engine {
  /** PostgreSQL: folds to lower case, resolves through {@code search_path}. */
  POSTGRESQL("postgresql", '"', '"', Fold.LOWER, false),
  /** MySQL: keeps the case of table names, compares column names without regard to case. */
  MYSQL("mysql", '`', '`', Fold.NONE, true),
  /** MariaDB: as MySQL. */
  MARIADB("mariadb", '`', '`', Fold.NONE, true),
  /** SQL Server: keeps case, compares without regard to case, falls back to {@code dbo}. */
  SQLSERVER("sqlserver", '[', ']', Fold.NONE, true),
  /** Oracle: folds to upper case, resolves through the current schema and then synonyms. */
  ORACLE("oracle", '"', '"', Fold.UPPER, false);

  private enum Fold {
    LOWER,
    UPPER,
    NONE
  }

  /** A table as the engine stores it: schema and table in their stored spelling. */
  public static final class QualifiedName {
    private final String schema;
    private final String table;

    /**
     * Creates a stored name.
     *
     * @param schema the schema in its stored spelling
     * @param table the table in its stored spelling
     */
    public QualifiedName(String schema, String table) {
      this.schema = schema;
      this.table = table;
    }

    /**
     * Returns the schema in its stored spelling.
     *
     * @return the schema
     */
    public String schema() {
      return schema;
    }

    /**
     * Returns the table in its stored spelling.
     *
     * @return the table
     */
    public String table() {
      return table;
    }

    @Override
    public String toString() {
      return schema + "." + table;
    }
  }

  private final String subProtocol;
  private final char quoteOpen;
  private final char quoteClose;
  private final Fold fold;
  private final boolean columnsCaseInsensitive;

  Engine(
      String subProtocol,
      char quoteOpen,
      char quoteClose,
      Fold fold,
      boolean columnsCaseInsensitive) {
    this.subProtocol = subProtocol;
    this.quoteOpen = quoteOpen;
    this.quoteClose = quoteClose;
    this.fold = fold;
    this.columnsCaseInsensitive = columnsCaseInsensitive;
  }

  /**
   * Returns the engine for a sub-protocol of the driver's URL.
   *
   * @param subProtocol the sub-protocol, such as {@code postgresql}, in any case
   * @return the engine
   * @throws IllegalArgumentException if the sub-protocol is not one the driver supports
   */
  public static Engine of(String subProtocol) {
    String s = subProtocol.toLowerCase(Locale.ROOT);
    for (Engine e : values()) {
      if (e.subProtocol.equals(s)) {
        return e;
      }
    }
    throw new IllegalArgumentException("Unsupported database: " + subProtocol);
  }

  /**
   * Returns the sub-protocol of the driver's URL that selects this engine.
   *
   * @return the sub-protocol
   */
  public String subProtocol() {
    return subProtocol;
  }

  /**
   * Quotes an identifier the way this engine expects, doubling an embedded closing delimiter.
   *
   * @param identifier the identifier in its stored spelling
   * @return the quoted identifier
   */
  public String quote(String identifier) {
    String closing = String.valueOf(quoteClose);
    return quoteOpen + identifier.replace(closing, closing + closing) + quoteClose;
  }

  /**
   * Resolves an identifier as written in a statement to the spelling the engine compares: a quoted
   * identifier, in any of the three quoting styles, is unquoted and kept; an unquoted one is folded
   * the way this engine folds.
   *
   * @param raw the identifier as written, quotes included
   * @return the spelling the engine uses
   */
  public String resolveIdentifier(String raw) {
    String s = raw.strip();
    if (s.length() >= 2) {
      char open = s.charAt(0);
      char close = s.charAt(s.length() - 1);
      if ((open == '"' && close == '"')
          || (open == '`' && close == '`')
          || (open == '[' && close == ']')) {
        String inner = s.substring(1, s.length() - 1);
        String closing = String.valueOf(close);
        return inner.replace(closing + closing, closing);
      }
    }
    switch (fold) {
      case LOWER:
        return s.toLowerCase(Locale.ROOT);
      case UPPER:
        return s.toUpperCase(Locale.ROOT);
      default:
        return s;
    }
  }

  /**
   * Returns the key under which this engine compares a resolved column name: the name itself where
   * the comparison is exact, its lower-case form where the engine ignores case.
   *
   * @param resolvedColumn a column name from {@link #resolveIdentifier} or from the catalog
   * @return the comparison key
   */
  public String columnKey(String resolvedColumn) {
    return columnsCaseInsensitive ? resolvedColumn.toLowerCase(Locale.ROOT) : resolvedColumn;
  }

  /**
   * Returns whether the engine treats two resolved column names as the same column.
   *
   * @param a a resolved column name
   * @param b a resolved column name
   * @return whether they name the same column
   */
  public boolean sameColumn(String a, String b) {
    return columnKey(a).equals(columnKey(b));
  }

  /**
   * Returns the schemas an unqualified table name resolves through in this session, in order: the
   * {@code search_path} on PostgreSQL, the current database on MySQL and MariaDB, the default
   * schema on SQL Server (the engine itself then falls back to {@code dbo}), the current schema on
   * Oracle (synonyms come after it). Read on the user's connection, outside any transaction, at
   * connect time and again after anything that changes the session: {@code SET search_path}, {@code
   * USE}, {@code ALTER SESSION SET CURRENT_SCHEMA}, {@link Connection#setSchema} or {@link
   * Connection#setCatalog}. The JDBC accessors are not used: MySQL Connector/J's {@code
   * getCatalog()} does not follow {@code USE}.
   *
   * @param connection the user's connection
   * @return the schemas, possibly empty when the session has none
   * @throws SQLException if the engine cannot be asked
   */
  public List<String> sessionSchemas(Connection connection) throws SQLException {
    String sql;
    switch (this) {
      case POSTGRESQL:
        sql = "SELECT current_schemas(false)";
        break;
      case MYSQL:
      case MARIADB:
        sql = "SELECT database()";
        break;
      case SQLSERVER:
        sql = "SELECT schema_name()";
        break;
      default:
        sql = "SELECT sys_context('USERENV', 'CURRENT_SCHEMA') FROM dual";
        break;
    }
    try (PreparedStatement ps = connection.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      List<String> out = new ArrayList<>();
      if (rs.next()) {
        if (this == POSTGRESQL) {
          Array a = rs.getArray(1);
          if (a != null) {
            for (Object o : (Object[]) a.getArray()) {
              out.add(String.valueOf(o));
            }
          }
        } else {
          String s = rs.getString(1);
          if (s != null && !s.isEmpty()) {
            out.add(s);
          }
        }
      }
      return out;
    }
  }

  /**
   * Asks the engine's own catalog which stored table or view a name denotes. The name is given as
   * {@link #resolveIdentifier} returns it; a qualified name is looked up in that schema, an
   * unqualified one through {@code sessionSchemas} in order and then through whatever the engine
   * adds (the {@code dbo} schema on SQL Server, public and private synonyms on Oracle).
   *
   * @param connection a connection to the database with the same credentials as the user's; the
   *     session state it needs is passed in, so this need not be the user's connection
   * @param sessionSchemas the schemas from {@link #sessionSchemas}
   * @param schema the resolved schema, or {@code null} for an unqualified name
   * @param table the resolved table name
   * @return the stored name, or {@code null} when the engine knows no such table or view
   * @throws SQLException if the catalog cannot be queried
   */
  public @Nullable QualifiedName resolve(
      Connection connection, List<String> sessionSchemas, @Nullable String schema, String table)
      throws SQLException {
    switch (this) {
      case POSTGRESQL:
        return resolvePostgresql(connection, sessionSchemas, schema, table);
      case MYSQL:
      case MARIADB:
        {
          if (schema == null && sessionSchemas.isEmpty()) {
            return null;
          }
          return one(
              connection,
              "SELECT table_schema, table_name FROM information_schema.tables"
                  + " WHERE table_schema = ? AND table_name = ?",
              schema != null ? schema : sessionSchemas.get(0),
              table);
        }
      case SQLSERVER:
        {
          String sql =
              "SELECT s.name, o.name FROM sys.objects o JOIN sys.schemas s"
                  + " ON s.schema_id = o.schema_id"
                  + " WHERE s.name = ? AND o.name = ? AND o.type IN ('U', 'V')";
          if (schema != null) {
            return one(connection, sql, schema, table);
          }
          List<String> candidates = new ArrayList<>(sessionSchemas);
          candidates.add("dbo");
          for (String s : candidates) {
            QualifiedName found = one(connection, sql, s, table);
            if (found != null) {
              return found;
            }
          }
          return null;
        }
      default:
        return resolveOracle(connection, sessionSchemas, schema, table);
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the query text is assembled from constants; the names are bound")
  private static @Nullable QualifiedName resolvePostgresql(
      Connection c, List<String> sessionSchemas, @Nullable String schema, String table)
      throws SQLException {
    String base =
        "SELECT n.nspname, c.relname FROM pg_class c JOIN pg_namespace n"
            + " ON n.oid = c.relnamespace WHERE c.relname = ?"
            + " AND c.relkind IN ('r', 'p', 'v', 'm', 'f')";
    if (schema != null) {
      return one(c, base + " AND n.nspname = ?", table, schema);
    }
    if (sessionSchemas.isEmpty()) {
      return null;
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            base + " AND n.nspname = ANY(?)" + " ORDER BY array_position(?, n.nspname) LIMIT 1")) {
      Array path = c.createArrayOf("text", sessionSchemas.toArray());
      ps.setString(1, table);
      ps.setArray(2, path);
      ps.setArray(3, path);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new QualifiedName(rs.getString(1), rs.getString(2)) : null;
      }
    }
  }

  private static @Nullable QualifiedName resolveOracle(
      Connection c, List<String> sessionSchemas, @Nullable String schema, String table)
      throws SQLException {
    String objects =
        "SELECT owner, object_name FROM all_objects WHERE owner = ? AND object_name = ?"
            + " AND object_type IN ('TABLE', 'VIEW')";
    if (schema != null) {
      return one(c, objects, schema, table);
    }
    if (sessionSchemas.isEmpty()) {
      return null;
    }
    String current = sessionSchemas.get(0);
    QualifiedName own = one(c, objects, current, table);
    if (own != null) {
      return own;
    }
    // then a private synonym of the current schema, then a public one; one level, as a table
    QualifiedName target =
        one(
            c,
            "SELECT table_owner, table_name FROM all_synonyms WHERE synonym_name = ?"
                + " AND owner IN (?, 'PUBLIC')"
                + " ORDER BY CASE owner WHEN 'PUBLIC' THEN 1 ELSE 0 END",
            table,
            current);
    return target == null ? null : one(c, objects, target.schema, target.table);
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "sql is one of the constant query texts above; the names are bound")
  private static @Nullable QualifiedName one(Connection c, String sql, String p1, String p2)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, p1);
      ps.setString(2, p2);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new QualifiedName(rs.getString(1), rs.getString(2)) : null;
      }
    }
  }

  /**
   * Returns whether the exception says that a table the query named does not exist, which is how a
   * database without ScalarDB's catalog answers.
   *
   * @param e the exception
   * @return whether it is the engine's "no such table"
   */
  public boolean isMissingTable(SQLException e) {
    String state = e.getSQLState();
    switch (this) {
      case POSTGRESQL:
        return "42P01".equals(state);
      case MYSQL:
      case MARIADB:
        // 1146 is "table doesn't exist", 1049 "unknown database" (SQLSTATE 42000)
        return "42S02".equals(state) || e.getErrorCode() == 1146 || e.getErrorCode() == 1049;
      case SQLSERVER:
        return "S0002".equals(state) || e.getErrorCode() == 208;
      default:
        return e.getErrorCode() == 942;
    }
  }
}
