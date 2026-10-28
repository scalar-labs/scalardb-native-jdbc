package com.scalar.db.nativejdbc.engine;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final boolean synonym;
    private final @Nullable QualifiedName target;

    /**
     * Creates a stored name of a table or view.
     *
     * @param schema the schema in its stored spelling
     * @param table the table in its stored spelling
     */
    public QualifiedName(String schema, String table) {
      this(schema, table, false);
    }

    /**
     * Creates a stored name.
     *
     * @param schema the schema in its stored spelling
     * @param table the object's name in its stored spelling
     * @param synonym whether the name is a synonym rather than a table or view
     */
    public QualifiedName(String schema, String table, boolean synonym) {
      this(schema, table, synonym, null);
    }

    private QualifiedName(
        String schema, String table, boolean synonym, @Nullable QualifiedName target) {
      this.schema = schema;
      this.table = table;
      this.synonym = synonym;
      this.target = target;
    }

    /**
     * Returns whether the name is a synonym. The driver refuses a statement that names a ScalarDB
     * table through a synonym, and any whose table is not known: a chain or a database link.
     *
     * @return whether it is a synonym
     */
    public boolean isSynonym() {
      return synonym;
    }

    /**
     * Returns the table or view a synonym stands for, in this database, or {@code null} when it is
     * none of those (another synonym, a database link) or the name is no synonym.
     *
     * @return the target
     */
    public @Nullable QualifiedName target() {
      return target;
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
   * Spells an affix the driver adds to a stored name, such as the {@code rc_} of a read view, the
   * way this engine folds an unquoted identifier, so the result reads naturally next to names the
   * engine stored: {@code RC_ORDERS} on Oracle, {@code rc_orders} on PostgreSQL.
   *
   * @param lower the affix in lower case
   * @return the affix as this engine spells it
   */
  public String affix(String lower) {
    return fold == Fold.UPPER ? lower.toUpperCase(Locale.ROOT) : lower;
  }

  /**
   * Returns whether a name the driver creates fits the engine's identifier limit: 63 bytes on
   * PostgreSQL, which truncates a longer one silently, 64 characters on MySQL and MariaDB, 128 on
   * SQL Server and Oracle.
   *
   * @param name the name
   * @return whether it fits
   */
  public boolean fitsIdentifier(String name) {
    switch (this) {
      case POSTGRESQL:
        return name.getBytes(StandardCharsets.UTF_8).length <= 63;
      case MYSQL:
      case MARIADB:
        return name.length() <= 64;
      case SQLSERVER:
      case ORACLE:
        return name.length() <= 128;
    }
    throw new AssertionError(this);
  }

  /**
   * Returns the name of the read view the setup creates for a table, {@code rc_T} (design section
   * 3).
   *
   * @param table the name statements use for the table, in its stored spelling
   * @return the view's name
   */
  public String readViewName(String table) {
    return affix("rc_") + table;
  }

  /**
   * Returns the name of the in-flight index the setup creates on a table's metadata table.
   *
   * @param table the name statements use for the table, in its stored spelling
   * @return the index's name
   */
  public String inflightIndexName(String table) {
    return table + affix("_inflight");
  }

  /**
   * Returns the name of the index the setup creates on a table's metadata table over the key in the
   * data table's order, where the metadata table's primary key orders it otherwise (design section
   * 3).
   *
   * @param table the name statements use for the table, in its stored spelling
   * @return the index's name
   */
  public String keyIndexName(String table) {
    return table + affix("_key");
  }

  /**
   * Lists the read views, with their columns, and the usable in-flight indexes of the given names:
   * only these names, so a load does not list every object of an application schema. An index the
   * engine holds unusable (PostgreSQL {@code indisvalid}, Oracle {@code STATUS}, SQL Server {@code
   * is_disabled}) is not listed.
   *
   * @param connection a connection to the database
   * @param schemas the schemas, in their stored spelling
   * @param views the view names
   * @param indexes the index names
   * @param caseInsensitive whether the database compares table names without regard to case
   * @return the objects found
   * @throws SQLException if the engine's catalog cannot be read
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the query text is constant apart from the number of placeholders")
  public SetupObjects setupObjects(
      Connection connection,
      Collection<String> schemas,
      Collection<String> views,
      Collection<String> indexes,
      boolean caseInsensitive)
      throws SQLException {
    SetupObjects out = new SetupObjects(this, caseInsensitive);
    if (schemas.isEmpty() || views.isEmpty() || indexes.isEmpty()) {
      return out;
    }
    List<String> s = spellings(schemas, caseInsensitive);
    List<String> v = spellings(views, caseInsensitive);
    List<String> i = spellings(indexes, caseInsensitive);
    try (PreparedStatement ps =
        connection.prepareStatement(columnsQuery(s.size(), v.size(), true))) {
      bind(ps, s, v);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.addViewColumn(rs.getString(1), rs.getString(2), rs.getString(3));
        }
      }
    }
    try (PreparedStatement ps = connection.prepareStatement(indexesQuery(s.size(), i.size()))) {
      bind(ps, s, i);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.addIndex(rs.getString(1), rs.getString(2), rs.getString(3));
        }
      }
    }
    return out;
  }

  /**
   * Adds to {@code out} the type each column's before image is cast to on MySQL and MariaDB, where
   * ScalarDB keeps the before image in a wider type than the column's, which a union of the two
   * reports: a {@code CHAR} or {@code VARCHAR} column's as a {@code LONGTEXT}, cast to the column's
   * length, character set and collation (todo 303); a {@code DATETIME} or {@code TIMESTAMP}
   * column's with three fractional digits, cast to the column's. On PostgreSQL and SQL Server, an
   * integer column narrower than ScalarDB's {@code INT}, whose before image and metadata-table key
   * are {@code INT}: cast to {@code SMALLINT}, or SQL Server's {@code TINYINT} (todo 356). Nothing
   * on Oracle, whose integer columns are all {@code NUMBER}.
   *
   * @param connection a connection to the database
   * @param out the listing to add to
   * @param schemas the schemas, in their stored spelling
   * @param tables the data tables
   * @param caseInsensitive whether the database compares table names without regard to case
   * @throws SQLException if the engine's catalog cannot be read
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the query text is constant apart from the number of placeholders")
  public void addBeforeTypes(
      Connection connection,
      SetupObjects out,
      Collection<String> schemas,
      Collection<String> tables,
      boolean caseInsensitive)
      throws SQLException {
    if (this == ORACLE || schemas.isEmpty() || tables.isEmpty()) {
      return;
    }
    List<String> s = spellings(schemas, caseInsensitive);
    List<String> t = spellings(tables, caseInsensitive);
    if (this == POSTGRESQL || this == SQLSERVER) {
      try (PreparedStatement ps =
          connection.prepareStatement(
              "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE"
                  + " FROM INFORMATION_SCHEMA.COLUMNS"
                  + " WHERE DATA_TYPE IN ('smallint', 'tinyint')"
                  + " AND TABLE_SCHEMA IN ("
                  + marks(s.size())
                  + ") AND TABLE_NAME IN ("
                  + marks(t.size())
                  + ")")) {
        bind(ps, s, t);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            out.addBeforeType(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4).toUpperCase(Locale.ROOT) + ")");
          }
        }
      }
      return;
    }
    try (PreparedStatement ps =
        connection.prepareStatement(
            "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, CHARACTER_MAXIMUM_LENGTH,"
                + " CHARACTER_SET_NAME, COLLATION_NAME, DATA_TYPE, DATETIME_PRECISION"
                + " FROM information_schema.COLUMNS"
                + " WHERE DATA_TYPE IN ('char', 'varchar', 'datetime', 'timestamp')"
                + " AND TABLE_SCHEMA IN ("
                + marks(s.size())
                + ") AND TABLE_NAME IN ("
                + marks(t.size())
                + ")")) {
      bind(ps, s, t);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String dataType = rs.getString(7);
          if ("datetime".equalsIgnoreCase(dataType) || "timestamp".equalsIgnoreCase(dataType)) {
            out.addBeforeType(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                "DATETIME(" + rs.getInt(8) + "))");
            continue;
          }
          String charset = rs.getString(5);
          String collation = rs.getString(6);
          // names the server chose, checked anyway before they go into statement text
          if (charset == null
              || collation == null
              || !charset.matches("\\w+")
              || !collation.matches("\\w+")) {
            continue;
          }
          out.addBeforeType(
              rs.getString(1),
              rs.getString(2),
              rs.getString(3),
              "CHAR(" + rs.getLong(4) + ") CHARACTER SET " + charset + ") COLLATE " + collation);
        }
      }
    }
  }

  /**
   * Adds to {@code out} the columns of the given tables, in the table's own order, which {@code
   * SELECT *} returns: ScalarDB's catalog lists the key first (todo 316).
   *
   * @param connection a connection to the database
   * @param out the listing to add to
   * @param schemas the schemas, in their stored spelling
   * @param tables the names statements use for the tables, views included
   * @param caseInsensitive whether the database compares table names without regard to case
   * @throws SQLException if the engine's catalog cannot be read
   */
  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the query text is constant apart from the number of placeholders")
  public void addColumnOrder(
      Connection connection,
      SetupObjects out,
      Collection<String> schemas,
      Collection<String> tables,
      boolean caseInsensitive)
      throws SQLException {
    if (schemas.isEmpty() || tables.isEmpty()) {
      return;
    }
    List<String> s = spellings(schemas, caseInsensitive);
    List<String> t = spellings(tables, caseInsensitive);
    try (PreparedStatement ps =
        connection.prepareStatement(columnsQuery(s.size(), t.size(), false))) {
      bind(ps, s, t);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.addTableColumn(rs.getString(1), rs.getString(2), rs.getString(3));
        }
      }
    }
  }

  /** The names to ask for: as given, and lower-cased where the database ignores case. */
  private static List<String> spellings(Collection<String> names, boolean caseInsensitive) {
    Set<String> out = new LinkedHashSet<>(names);
    if (caseInsensitive) {
      for (String n : names) {
        out.add(n.toLowerCase(Locale.ROOT));
      }
    }
    return new ArrayList<>(out);
  }

  private static void bind(PreparedStatement ps, List<String> first, List<String> second)
      throws SQLException {
    int p = 1;
    for (String x : first) {
      ps.setString(p++, x);
    }
    for (String x : second) {
      ps.setString(p++, x);
    }
  }

  private static String marks(int n) {
    return String.join(", ", Collections.nCopies(n, "?"));
  }

  /**
   * Schema, relation, column of the named relations, in column order: views only, tables of the
   * same name excluded, or tables and views.
   */
  private String columnsQuery(int schemas, int names, boolean views) {
    String s = marks(schemas);
    String n = marks(names);
    switch (this) {
      case POSTGRESQL:
        // pg_catalog, not information_schema, which hides objects the user has no privilege on
        return "SELECT n.nspname, c.relname, a.attname FROM pg_catalog.pg_attribute a"
            + " JOIN pg_catalog.pg_class c ON c.oid = a.attrelid"
            + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
            + (views ? " WHERE c.relkind = 'v'" : " WHERE c.relkind IN ('r', 'p', 'v')")
            + " AND a.attnum > 0 AND NOT a.attisdropped"
            + " AND n.nspname IN ("
            + s
            + ") AND c.relname IN ("
            + n
            + ")"
            + " ORDER BY n.nspname, c.relname, a.attnum";
      case MYSQL:
      case MARIADB:
        return "SELECT c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME FROM information_schema.COLUMNS c"
            + (views
                ? " JOIN information_schema.VIEWS v"
                    + " ON v.TABLE_SCHEMA = c.TABLE_SCHEMA AND v.TABLE_NAME = c.TABLE_NAME"
                : "")
            + " WHERE c.TABLE_SCHEMA IN ("
            + s
            + ") AND c.TABLE_NAME IN ("
            + n
            + ")"
            + " ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION";
      case SQLSERVER:
        return "SELECT s.name, v.name, c.name FROM "
            + (views ? "sys.views v" : "sys.objects v")
            + " JOIN sys.schemas s ON s.schema_id = v.schema_id"
            + " JOIN sys.columns c ON c.object_id = v.object_id"
            + " WHERE "
            + (views ? "" : "v.type IN ('U', 'V') AND ")
            + "s.name IN ("
            + s
            + ") AND v.name IN ("
            + n
            + ")"
            + " ORDER BY s.name, v.name, c.column_id";
      case ORACLE:
        return "SELECT c.OWNER, c.TABLE_NAME, c.COLUMN_NAME FROM ALL_TAB_COLUMNS c"
            + (views ? " JOIN ALL_VIEWS v ON v.OWNER = c.OWNER AND v.VIEW_NAME = c.TABLE_NAME" : "")
            + " WHERE c.OWNER IN ("
            + s
            + ") AND c.TABLE_NAME IN ("
            + n
            + ")"
            + " ORDER BY c.OWNER, c.TABLE_NAME, c.COLUMN_ID";
    }
    throw new AssertionError(this);
  }

  /** Schema, table, index of the named indexes that the engine can use. */
  private String indexesQuery(int schemas, int names) {
    String s = marks(schemas);
    String n = marks(names);
    switch (this) {
      case POSTGRESQL:
        return "SELECT n.nspname, t.relname, i.relname FROM pg_catalog.pg_index x"
            + " JOIN pg_catalog.pg_class i ON i.oid = x.indexrelid"
            + " JOIN pg_catalog.pg_class t ON t.oid = x.indrelid"
            + " JOIN pg_catalog.pg_namespace n ON n.oid = t.relnamespace"
            + " WHERE x.indisvalid AND n.nspname IN ("
            + s
            + ") AND i.relname IN ("
            + n
            + ")";
      case MYSQL:
      case MARIADB:
        return "SELECT DISTINCT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME"
            + " FROM information_schema.STATISTICS"
            + " WHERE TABLE_SCHEMA IN ("
            + s
            + ") AND INDEX_NAME IN ("
            + n
            + ")";
      case SQLSERVER:
        return "SELECT s.name, t.name, i.name FROM sys.indexes i"
            + " JOIN sys.tables t ON t.object_id = i.object_id"
            + " JOIN sys.schemas s ON s.schema_id = t.schema_id"
            + " WHERE i.is_disabled = 0 AND s.name IN ("
            + s
            + ") AND i.name IN ("
            + n
            + ")";
      case ORACLE:
        return "SELECT TABLE_OWNER, TABLE_NAME, INDEX_NAME FROM ALL_INDEXES"
            + " WHERE STATUS = 'VALID' AND TABLE_OWNER IN ("
            + s
            + ") AND INDEX_NAME IN ("
            + n
            + ")";
    }
    throw new AssertionError(this);
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
        return lowerAscii(s); // PostgreSQL folds A-Z only
      case UPPER:
        return upperSimple(s); // see needsServerFold for names this cannot decide
      case NONE:
        return s;
    }
    throw new AssertionError(fold);
  }

  private static String lowerAscii(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      b.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return b.toString();
  }

  /** Code point by code point, never expanding one character into two as String does for ß. */
  private static String upperSimple(String s) {
    StringBuilder b = new StringBuilder(s.length());
    s.codePoints().forEach(cp -> b.appendCodePoint(Character.toUpperCase(cp)));
    return b.toString();
  }

  /**
   * Returns whether the engine itself must fold this identifier: an unquoted name with characters
   * beyond ASCII on Oracle, whose upper-casing of them follows the database's character set rather
   * than Java's rules. {@link #serverFold} asks it.
   *
   * @param raw the identifier as written
   * @return whether to ask the engine
   */
  public boolean needsServerFold(String raw) {
    if (this != ORACLE) {
      return false;
    }
    String s = raw.strip();
    if (s.startsWith("\"")) {
      return false;
    }
    return s.chars().anyMatch(c -> c > 0x7f);
  }

  /**
   * Folds an unquoted identifier the way the engine does, by asking it.
   *
   * @param connection a connection to the database
   * @param raw the unquoted identifier
   * @return the folded identifier
   * @throws SQLException if the engine cannot be asked
   */
  public String serverFold(Connection connection, String raw) throws SQLException {
    if (this != ORACLE) {
      return resolveIdentifier(raw);
    }
    try (PreparedStatement ps = connection.prepareStatement("SELECT UPPER(?) FROM dual")) {
      ps.setString(1, raw.strip());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  /**
   * Returns whether a function of this name runs SQL it receives as text, which the driver cannot
   * inspect (design 4.1). A function written by the user that does so cannot be recognised; this
   * covers the engine's own.
   *
   * @param nameParts the parts of the function's name, unquoted and in lower case
   * @return whether any part names such a function or package
   */
  public boolean runsSqlText(List<String> nameParts) {
    for (String part : nameParts) {
      switch (this) {
        case POSTGRESQL:
          // the XML exports, dblink, the tablefunc extension and the text-search statistics all
          // take a query or a table name as a string
          if (part.startsWith("query_to_xml")
              || part.startsWith("cursor_to_xml")
              || part.startsWith("table_to_xml")
              || part.startsWith("schema_to_xml")
              || part.startsWith("database_to_xml")
              || part.startsWith("dblink")
              || part.startsWith("crosstab")
              || part.equals("connectby")
              || part.equals("ts_stat")
              || part.equals("ts_rewrite")) {
            return true;
          }
          break;
        case ORACLE:
          // the packages run a query string; XQuery reads tables through
          // fn:collection("oradb:...");
          // the URI types (DBURITYPE, XDBURITYPE, HTTPURITYPE, URITYPE) read a table by a path
          if (part.equals("dbms_xmlgen")
              || part.equals("dbms_xmlquery")
              || part.equals("dbms_xmlstore")
              || part.equals("dbms_xmlsave")
              || part.endsWith("uritype")
              || part.equals("sys_dburigen")
              || part.equals("dbms_sql")
              || part.equals("xmlquery")
              || part.equals("xmltable")
              || part.equals("xmlexists")) {
            return true;
          }
          break;
        case SQLSERVER:
          if (part.equals("openquery")
              || part.equals("openrowset")
              || part.equals("opendatasource")) {
            return true;
          }
          break;
        case MYSQL:
        case MARIADB:
          break;
      }
    }
    return false;
  }

  /**
   * Returns whether {@code "x"} quotes an identifier on an engine left at its default settings: not
   * on MySQL and MariaDB, where it is a string unless {@code ANSI_QUOTES} is set.
   *
   * @return whether double quotes quote identifiers by default
   */
  public boolean doubleQuotesQuoteIdentifiersByDefault() {
    return this != MYSQL && this != MARIADB;
  }

  /**
   * Returns whether {@code "x"} quotes an identifier in this session: on MySQL and MariaDB when
   * {@code sql_mode} includes {@code ANSI_QUOTES}, on SQL Server when {@code QUOTED_IDENTIFIER} is
   * on; always on PostgreSQL and Oracle. Read with the other session state and passed to the
   * parser, which refuses a statement that uses {@code "x"} where the engine would read a string.
   *
   * @param connection the user's connection
   * @return whether double quotes quote identifiers
   * @throws SQLException if the setting cannot be read
   */
  public boolean doubleQuotesQuoteIdentifiers(Connection connection) throws SQLException {
    String sql = doubleQuoteSettingQuery();
    if (sql == null) {
      return true;
    }
    try (PreparedStatement ps = connection.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      if (!rs.next()) {
        return doubleQuotesQuoteIdentifiersByDefault();
      }
      String value = rs.getString(1);
      if (value == null) {
        return doubleQuotesQuoteIdentifiersByDefault();
      }
      return this == SQLSERVER
          ? value.equals("1")
          : value.toUpperCase(Locale.ROOT).contains("ANSI_QUOTES");
    }
  }

  private @Nullable String doubleQuoteSettingQuery() {
    switch (this) {
      case MYSQL:
      case MARIADB:
        return "SELECT @@SESSION.sql_mode";
      case SQLSERVER:
        return "SELECT CASE WHEN (@@OPTIONS & 256) = 256 THEN 1 ELSE 0 END";
      case POSTGRESQL:
      case ORACLE:
        return null;
    }
    throw new AssertionError(this);
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
   * Returns the key under which this engine compares a resolved name that is local to a statement:
   * a CTE name, a table alias, a column qualifier. SQL Server compares every identifier without
   * regard to case; the other engines compare these names as resolved.
   *
   * @param resolvedName a name from {@link #resolveIdentifier}
   * @return the comparison key
   */
  public String nameKey(String resolvedName) {
    return this == SQLSERVER ? resolvedName.toLowerCase(Locale.ROOT) : resolvedName;
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
    String sql = sessionSchemasQuery();
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
          // the default schema first, then dbo, in one round trip
          String first =
              schema != null ? schema : sessionSchemas.isEmpty() ? "dbo" : sessionSchemas.get(0);
          String second = schema != null ? schema : "dbo";
          try (PreparedStatement ps =
              connection.prepareStatement(
                  "SELECT TOP 1 s.name, o.name, o.type FROM sys.objects o JOIN sys.schemas s"
                      + " ON s.schema_id = o.schema_id"
                      + " WHERE o.name = ? AND o.type IN ('U', 'V', 'SN') AND s.name IN (?, ?)"
                      + " ORDER BY CASE WHEN s.name = ? THEN 0 ELSE 1 END")) {
            ps.setString(1, table);
            ps.setString(2, first);
            ps.setString(3, second);
            ps.setString(4, first);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next()
                  ? new QualifiedName(
                      rs.getString(1), rs.getString(2), rs.getString(3).strip().equals("SN"))
                  : null;
            }
          }
        }
      case ORACLE:
        return resolveOracle(connection, sessionSchemas, schema, table);
    }
    throw new AssertionError(this);
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

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "the query text is assembled from constants; the names are bound")
  private static @Nullable QualifiedName resolveOracle(
      Connection c, List<String> sessionSchemas, @Nullable String schema, String table)
      throws SQLException {
    // a synonym is reported as one, with the table it stands for (todo 282)
    String own =
        "SELECT owner, object_name, object_type, 0 pri FROM all_objects"
            + " WHERE owner = ? AND object_name = ? AND object_type IN ('TABLE', 'VIEW', 'SYNONYM')";
    String sql;
    String owner;
    if (schema != null) {
      sql = own;
      owner = schema;
    } else if (sessionSchemas.isEmpty()) {
      return null;
    } else {
      // the current schema's own object or private synonym, then a public synonym
      sql =
          "SELECT owner, object_name, object_type, pri FROM ("
              + own
              + " UNION ALL SELECT owner, synonym_name, 'SYNONYM', 1 FROM all_synonyms"
              + " WHERE owner = 'PUBLIC' AND synonym_name = ?)"
              + " ORDER BY pri FETCH FIRST 1 ROW ONLY";
      owner = sessionSchemas.get(0);
    }
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, owner);
      ps.setString(2, table);
      if (schema == null) {
        ps.setString(3, table);
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        if (!"SYNONYM".equals(rs.getString(3))) {
          return new QualifiedName(rs.getString(1), rs.getString(2));
        }
        return new QualifiedName(
            rs.getString(1),
            rs.getString(2),
            true,
            synonymTarget(c, rs.getString(1), rs.getString(2)));
      }
    }
  }

  /**
   * The table or view a synonym stands for in this database, as PUBLIC.DUAL stands for SYS.DUAL
   * (todo 282), or {@code null} for another synonym or a database link.
   */
  private static @Nullable QualifiedName synonymTarget(Connection c, String owner, String name)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT o.owner, o.object_name FROM all_synonyms s JOIN all_objects o ON o.owner ="
                + " s.table_owner AND o.object_name = s.table_name WHERE s.owner = ? AND"
                + " s.synonym_name = ? AND s.db_link IS NULL AND o.object_type IN ('TABLE',"
                + " 'VIEW')")) {
      ps.setString(1, owner);
      ps.setString(2, name);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? new QualifiedName(rs.getString(1), rs.getString(2)) : null;
      }
    }
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
   * Returns whether this database compares table names without regard to case, which is a
   * per-database setting rather than a property of the engine: MySQL and MariaDB when {@code
   * lower_case_table_names} is not 0 (the Windows default, common on managed services), SQL Server
   * when the database collation is case-insensitive; never on PostgreSQL and Oracle. Where it is
   * true, the name ScalarDB recorded at import time may differ in case from the one the engine
   * stores, and the two must be compared without regard to case.
   *
   * @param connection a connection to the database
   * @return whether table names compare without regard to case
   * @throws SQLException if the setting cannot be read
   */
  public boolean tableNamesCaseInsensitive(Connection connection) throws SQLException {
    String sql = tableNameSettingQuery();
    if (sql == null) {
      return false;
    }
    try (PreparedStatement ps = connection.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      if (!rs.next()) {
        return false;
      }
      String value = rs.getString(1);
      if (value == null) {
        return false;
      }
      return this == SQLSERVER
          ? value.toUpperCase(Locale.ROOT).contains("_CI")
          : !value.equals("0");
    }
  }

  /**
   * Returns whether a schema exists, as far as this connection can see. Used to tell a catalog
   * schema that is absent from one whose tables this user is not allowed to see: Oracle answers
   * "does not exist" for both, and {@code all_users} is visible to every user.
   *
   * @param connection a connection to the database
   * @param schema the schema name in its stored spelling
   * @return whether the schema exists
   * @throws SQLException if the engine cannot be asked
   */
  public boolean schemaExists(Connection connection, String schema) throws SQLException {
    String sql = schemaExistsQuery();
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
      ps.setString(1, schema);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Returns whether the exception says that a table the query named does not exist, which is how a
   * database without ScalarDB's catalog answers. On Oracle the same error also means that the table
   * exists but this user may not see it; {@link #schemaExists} tells the two apart.
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
      case ORACLE:
        return e.getErrorCode() == 942;
    }
    throw new AssertionError(this);
  }

  /**
   * Returns whether the exception is a unique constraint's duplicate key, any constraint's.
   *
   * @param e the exception
   * @return whether it is the engine's duplicate key
   */
  public boolean isDuplicateKey(SQLException e) {
    switch (this) {
      case POSTGRESQL:
        return "23505".equals(e.getSQLState());
      case MYSQL:
      case MARIADB:
        return e.getErrorCode() == 1062;
      case SQLSERVER:
        return e.getErrorCode() == 2627 || e.getErrorCode() == 2601;
      case ORACLE:
        return e.getErrorCode() == 1;
    }
    throw new AssertionError(this);
  }

  private String sessionSchemasQuery() {
    switch (this) {
      case POSTGRESQL:
        return "SELECT current_schemas(false)";
      case MYSQL:
      case MARIADB:
        return "SELECT database()";
      case SQLSERVER:
        return "SELECT schema_name()";
      case ORACLE:
        return "SELECT sys_context('USERENV', 'CURRENT_SCHEMA') FROM dual";
    }
    throw new AssertionError(this);
  }

  /** The query reading the setting, or {@code null} where table names always compare exactly. */
  private @Nullable String tableNameSettingQuery() {
    switch (this) {
      case MYSQL:
      case MARIADB:
        return "SELECT @@lower_case_table_names";
      case SQLSERVER:
        return "SELECT CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation'))";
      case POSTGRESQL:
      case ORACLE:
        return null;
    }
    throw new AssertionError(this);
  }

  private String schemaExistsQuery() {
    switch (this) {
      case POSTGRESQL:
        return "SELECT 1 FROM pg_namespace WHERE nspname = ?";
      case MYSQL:
      case MARIADB:
        return "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?";
      case SQLSERVER:
        return "SELECT 1 FROM sys.schemas WHERE name = ?";
      case ORACLE:
        return "SELECT 1 FROM all_users WHERE username = ?";
    }
    throw new AssertionError(this);
  }
}
