package com.scalar.db.nativejdbc.shell;

import com.scalar.db.nativejdbc.ScalarDbTables;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * psql's catalog commands, {@code \dt}, {@code \d table} and the rest, from {@code
 * DatabaseMetaData}, so they work alike on every engine. On MySQL and MariaDB a schema is a
 * database, which JDBC calls a catalog. ScalarDB tables are marked from the driver's own answer
 * ({@link ScalarDbTables}), the one it routes statements by.
 */
final class CatalogCommands {

  private static final Set<String> TABLES = Set.of("TABLE", "BASE TABLE", "PARTITIONED TABLE");
  private static final Set<String> VIEWS = Set.of("VIEW");
  private static final Set<String> RELATIONS =
      Set.of(
          "TABLE",
          "BASE TABLE",
          "PARTITIONED TABLE",
          "VIEW",
          "MATERIALIZED VIEW",
          "SEQUENCE",
          "FOREIGN TABLE");

  private final Connection connection;
  private final boolean schemaIsCatalog;

  /** One relation as {@code getTables} reports it. */
  private static final class Relation {
    final @Nullable String catalog;
    final String schema;
    final String name;
    final String type;

    Relation(@Nullable String catalog, String schema, String name, String type) {
      this.catalog = catalog;
      this.schema = schema;
      this.name = name;
      this.type = type;
    }

    @Nullable String schemaArg(boolean schemaIsCatalog) {
      return schemaIsCatalog ? null : schema;
    }

    @Nullable String catalogArg(boolean schemaIsCatalog) {
      return schemaIsCatalog ? schema : catalog;
    }
  }

  /** A psql pattern, {@code [schema.]name} with {@code *} and {@code ?}, as LIKE patterns. */
  private static final class Scope {
    final String schema;
    final String name;
    final boolean schemaHasWildcards;

    Scope(String schema, String name, boolean schemaHasWildcards) {
      this.schema = schema;
      this.name = name;
      this.schemaHasWildcards = schemaHasWildcards;
    }
  }

  CatalogCommands(Connection connection, StatementSplitter.Dialect dialect) {
    this.connection = connection;
    this.schemaIsCatalog = dialect == StatementSplitter.Dialect.MYSQL;
  }

  /** {@code \dt}: tables, and views that are ScalarDB tables, which users take for tables. */
  void tables(@Nullable String pattern, boolean expanded, PrintWriter out) throws SQLException {
    list(pattern, TABLES, true, expanded, out);
  }

  /** {@code \dv}. */
  void views(@Nullable String pattern, boolean expanded, PrintWriter out) throws SQLException {
    list(pattern, VIEWS, false, expanded, out);
  }

  /** {@code \d} without a name: every relation. */
  void relations(@Nullable String pattern, boolean expanded, PrintWriter out) throws SQLException {
    list(pattern, RELATIONS, false, expanded, out);
  }

  private void list(
      @Nullable String pattern,
      Set<String> types,
      boolean scalarDbViews,
      boolean expanded,
      PrintWriter out)
      throws SQLException {
    Printer.Rows rows = new Printer.Rows("Schema", "Name", "Type", "ScalarDB");
    for (Relation r : find(scope(pattern), RELATIONS)) {
      String kind = kind(r);
      boolean wanted =
          types.contains(r.type)
              || (scalarDbViews && VIEWS.contains(r.type) && kind.startsWith("yes"));
      if (wanted) {
        rows.add(r.schema, r.name, typeName(r.type), kind);
      }
    }
    if (rows.values.isEmpty()) {
      out.println(
          pattern == null
              ? "Did not find any relations."
              : "Did not find any relation named \"" + pattern + "\".");
      return;
    }
    Printer.print(rows, "List of relations", expanded, true, out);
  }

  /** {@code \di}: the indexes of the tables in scope, the pattern naming the index. */
  void indexes(@Nullable String pattern, boolean expanded, PrintWriter out) throws SQLException {
    Scope scope = scope(pattern);
    Pattern name = like(scope.name);
    DatabaseMetaData md = connection.getMetaData();
    Printer.Rows rows = new Printer.Rows("Schema", "Name", "Type", "Table");
    for (Relation t : find(new Scope(scope.schema, "%", scope.schemaHasWildcards), TABLES)) {
      Set<String> seen = new TreeSet<>();
      try (ResultSet rs =
          md.getIndexInfo(
              t.catalogArg(schemaIsCatalog), t.schemaArg(schemaIsCatalog), t.name, false, true)) {
        while (rs.next()) {
          String index = rs.getString("INDEX_NAME");
          if (index != null
              && rs.getShort("TYPE") != DatabaseMetaData.tableIndexStatistic
              && name.matcher(index).matches()
              && seen.add(index)) {
            rows.add(t.schema, index, "index", t.name);
          }
        }
      }
    }
    if (rows.values.isEmpty()) {
      out.println(
          pattern == null
              ? "Did not find any relations."
              : "Did not find any relation named \"" + pattern + "\".");
      return;
    }
    Printer.print(rows, "List of relations", expanded, true, out);
  }

  /** {@code \dn}. */
  void schemas(@Nullable String pattern, boolean expanded, PrintWriter out) throws SQLException {
    Pattern name = like(pattern == null ? "%" : part(pattern));
    Printer.Rows rows = new Printer.Rows("Name");
    for (String s : schemaNames()) {
      if (name.matcher(s).matches()) {
        rows.add(s);
      }
    }
    Printer.print(rows, "List of schemas", expanded, true, out);
  }

  /** {@code \l}: the databases the server lists; Oracle lists none. */
  void databases(boolean expanded, PrintWriter out) throws SQLException {
    Printer.Rows rows = new Printer.Rows("Name");
    try (ResultSet rs = connection.getMetaData().getCatalogs()) {
      while (rs.next()) {
        rows.add(rs.getString(1));
      }
    }
    Printer.print(rows, "List of databases", expanded, true, out);
  }

  /**
   * {@code \d name}: each matching relation's columns, then its indexes, foreign keys and how the
   * driver treats it.
   */
  void describe(String pattern, boolean expanded, PrintWriter out) throws SQLException {
    List<Relation> found = find(scope(pattern), RELATIONS);
    if (found.isEmpty()) {
      out.println("Did not find any relation named \"" + pattern + "\".");
      return;
    }
    for (Relation r : found) {
      describe(r, expanded, out);
    }
  }

  private void describe(Relation r, boolean expanded, PrintWriter out) throws SQLException {
    DatabaseMetaData md = connection.getMetaData();
    String catalog = r.catalogArg(schemaIsCatalog);
    String schema = r.schemaArg(schemaIsCatalog);
    Printer.Rows columns = new Printer.Rows("Column", "Type", "Nullable", "Default");
    try (ResultSet rs = md.getColumns(catalog, schema, escape(r.name), "%")) {
      while (rs.next()) {
        String def = rs.getString("COLUMN_DEF");
        columns.add(
            rs.getString("COLUMN_NAME"),
            type(rs),
            "NO".equals(rs.getString("IS_NULLABLE")) ? "not null" : "",
            def == null ? null : def.strip());
      }
    }
    String title =
        typeName(r.type).substring(0, 1).toUpperCase(Locale.ROOT)
            + typeName(r.type).substring(1)
            + " \""
            + r.schema
            + "."
            + r.name
            + "\"";
    Printer.print(columns, title, expanded, false, out);

    if (!VIEWS.contains(r.type)) {
      List<String> indexes = indexLines(md, catalog, schema, r.name);
      if (!indexes.isEmpty()) {
        out.println("Indexes:");
        indexes.forEach(out::println);
      }
      List<String> foreignKeys = foreignKeyLines(md, catalog, schema, r.name);
      if (!foreignKeys.isEmpty()) {
        out.println("Foreign-key constraints:");
        foreignKeys.forEach(out::println);
      }
    }
    String kind = kind(r);
    switch (kind) {
      case "yes":
        out.println("ScalarDB table: statements on it go through the driver's rewrite");
        break;
      case "yes, not set up":
        out.println(
            "ScalarDB table, not set up: statements on it are refused until the setup runs");
        break;
      case "internal":
        out.println("ScalarDB's own table for a ScalarDB table: statements on it are refused");
        break;
      default:
        break;
    }
    out.println();
  }

  private List<String> indexLines(
      DatabaseMetaData md, @Nullable String catalog, @Nullable String schema, String table)
      throws SQLException {
    String primaryKey = null;
    try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
      if (rs.next()) {
        primaryKey = rs.getString("PK_NAME");
      }
    }
    // index name -> unique, then the columns by position
    Map<String, Boolean> unique = new TreeMap<>();
    Map<String, Map<Short, String>> columns = new TreeMap<>();
    try (ResultSet rs = md.getIndexInfo(catalog, schema, table, false, true)) {
      while (rs.next()) {
        String index = rs.getString("INDEX_NAME");
        if (index == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
          continue;
        }
        unique.put(index, !rs.getBoolean("NON_UNIQUE"));
        String column = rs.getString("COLUMN_NAME");
        columns
            .computeIfAbsent(index, k -> new TreeMap<>())
            .put(rs.getShort("ORDINAL_POSITION"), column == null ? "?" : column);
      }
    }
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, Map<Short, String>> e : columns.entrySet()) {
      String index = e.getKey();
      String kind =
          index.equals(primaryKey)
              ? " PRIMARY KEY"
              : Boolean.TRUE.equals(unique.get(index)) ? " UNIQUE" : "";
      lines.add(
          "    \""
              + Printer.printable(index)
              + "\""
              + kind
              + " ("
              + Printer.printable(String.join(", ", e.getValue().values()))
              + ")");
    }
    return lines;
  }

  private List<String> foreignKeyLines(
      DatabaseMetaData md, @Nullable String catalog, @Nullable String schema, String table)
      throws SQLException {
    Map<String, List<String[]>> keys = new LinkedHashMap<>();
    try (ResultSet rs = md.getImportedKeys(catalog, schema, table)) {
      while (rs.next()) {
        String name = rs.getString("FK_NAME");
        String parentSchema = rs.getString(schemaIsCatalog ? "PKTABLE_CAT" : "PKTABLE_SCHEM");
        keys.computeIfAbsent(name == null ? "?" : name, k -> new ArrayList<>())
            .add(
                new String[] {
                  rs.getString("FKCOLUMN_NAME"),
                  (parentSchema == null ? "" : parentSchema + ".") + rs.getString("PKTABLE_NAME"),
                  rs.getString("PKCOLUMN_NAME")
                });
      }
    }
    List<String> lines = new ArrayList<>();
    for (Map.Entry<String, List<String[]>> e : keys.entrySet()) {
      List<String> from = new ArrayList<>();
      List<String> to = new ArrayList<>();
      for (String[] c : e.getValue()) {
        from.add(c[0]);
        to.add(c[2]);
      }
      lines.add(
          Printer.printable(
              "    \""
                  + e.getKey()
                  + "\" FOREIGN KEY ("
                  + String.join(", ", from)
                  + ") REFERENCES "
                  + e.getValue().get(0)[1]
                  + "("
                  + String.join(", ", to)
                  + ")"));
    }
    return lines;
  }

  /**
   * The names worth completing: the schemas, and the tables, views and their columns of the current
   * schema.
   */
  Set<String> completions() throws SQLException {
    Set<String> names = new TreeSet<>();
    schemaNames().forEach(n -> add(names, n));
    String current = currentSchema();
    DatabaseMetaData md = connection.getMetaData();
    try (ResultSet rs =
        md.getTables(
            schemaIsCatalog ? current : null,
            schemaIsCatalog ? null : escape(current),
            "%",
            null)) {
      while (rs.next()) {
        add(names, rs.getString("TABLE_NAME"));
      }
    }
    try (ResultSet rs =
        md.getColumns(
            schemaIsCatalog ? current : null, schemaIsCatalog ? null : escape(current), "%", "%")) {
      while (rs.next()) {
        add(names, rs.getString("COLUMN_NAME"));
      }
    }
    return names;
  }

  private static void add(Set<String> names, @Nullable String name) {
    if (name != null) {
      names.add(name);
    }
  }

  private List<String> schemaNames() throws SQLException {
    List<String> names = new ArrayList<>();
    DatabaseMetaData md = connection.getMetaData();
    try (ResultSet rs = schemaIsCatalog ? md.getCatalogs() : md.getSchemas()) {
      while (rs.next()) {
        names.add(rs.getString(1));
      }
    }
    return names;
  }

  private List<Relation> find(Scope scope, Set<String> types) throws SQLException {
    DatabaseMetaData md = connection.getMetaData();
    List<Relation> found = new ArrayList<>();
    Pattern schemaPattern = like(scope.schema);
    String catalogArg =
        schemaIsCatalog && !scope.schemaHasWildcards ? unescape(scope.schema) : null;
    try (ResultSet rs =
        md.getTables(catalogArg, schemaIsCatalog ? null : scope.schema, scope.name, null)) {
      while (rs.next()) {
        String type = String.valueOf(rs.getString("TABLE_TYPE")).toUpperCase(Locale.ROOT);
        String catalog = rs.getString("TABLE_CAT");
        String schema = schemaIsCatalog ? catalog : rs.getString("TABLE_SCHEM");
        if (types.contains(type) && schema != null && schemaPattern.matcher(schema).matches()) {
          found.add(new Relation(catalog, schema, rs.getString("TABLE_NAME"), type));
        }
      }
    }
    found.sort(Comparator.comparing((Relation r) -> r.schema).thenComparing(r -> r.name));
    return found;
  }

  /** How the driver treats a relation, for the ScalarDB column. */
  private String kind(Relation r) throws SQLException {
    if (!connection.isWrapperFor(ScalarDbTables.class)) {
      return "";
    }
    switch (connection.unwrap(ScalarDbTables.class).kind(r.schema, r.name)) {
      case SCALARDB:
        return "yes";
      case NOT_SET_UP:
        return "yes, not set up";
      case REFUSED:
        return "internal";
      case PLAIN:
        return "";
    }
    return "";
  }

  private Scope scope(@Nullable String pattern) throws SQLException {
    if (pattern == null) {
      return new Scope(escape(currentSchema()), "%", false);
    }
    int dot = unquotedDot(pattern);
    if (dot < 0) {
      return new Scope(escape(currentSchema()), part(pattern), false);
    }
    String schema = part(pattern.substring(0, dot));
    return new Scope(schema, part(pattern.substring(dot + 1)), hasWildcards(schema));
  }

  private String currentSchema() throws SQLException {
    String s = schemaIsCatalog ? connection.getCatalog() : connection.getSchema();
    return s == null ? "" : s;
  }

  /**
   * One part of a psql pattern as a LIKE pattern in the stored spelling: {@code *} and {@code ?}
   * are wildcards, an unquoted name is folded as the database folds names, a quoted one is kept.
   */
  private String part(String text) throws SQLException {
    DatabaseMetaData md = connection.getMetaData();
    StringBuilder like = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          like.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (!quoted && ch == '*') {
        like.append('%');
      } else if (!quoted && ch == '?') {
        like.append('_');
      } else {
        String s = String.valueOf(ch);
        if (!quoted && md.storesUpperCaseIdentifiers()) {
          s = s.toUpperCase(Locale.ROOT);
        } else if (!quoted && md.storesLowerCaseIdentifiers()) {
          s = s.toLowerCase(Locale.ROOT);
        }
        like.append(escape(s));
      }
    }
    return like.toString();
  }

  private static int unquotedDot(String pattern) {
    boolean quoted = false;
    for (int i = 0; i < pattern.length(); i++) {
      char ch = pattern.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
      } else if (ch == '.' && !quoted) {
        return i;
      }
    }
    return -1;
  }

  /** A literal name as a LIKE pattern: its {@code %} and {@code _} escaped. */
  private String escape(String name) throws SQLException {
    String e = connection.getMetaData().getSearchStringEscape();
    if (e == null || e.isEmpty()) {
      return name;
    }
    return name.replace(e, e + e).replace("%", e + "%").replace("_", e + "_");
  }

  private String unescape(String like) throws SQLException {
    String e = connection.getMetaData().getSearchStringEscape();
    if (e == null || e.isEmpty()) {
      return like;
    }
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < like.length(); i++) {
      if (like.startsWith(e, i) && i + e.length() < like.length()) {
        i += e.length();
      }
      b.append(like.charAt(i));
    }
    return b.toString();
  }

  private boolean hasWildcards(String like) throws SQLException {
    String e = connection.getMetaData().getSearchStringEscape();
    for (int i = 0; i < like.length(); i++) {
      if (e != null && !e.isEmpty() && like.startsWith(e, i) && i + e.length() < like.length()) {
        i += e.length();
      } else if (like.charAt(i) == '%' || like.charAt(i) == '_') {
        return true;
      }
    }
    return false;
  }

  /** A LIKE pattern as a regular expression, to filter what JDBC cannot. */
  private Pattern like(String like) throws SQLException {
    String e = connection.getMetaData().getSearchStringEscape();
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < like.length(); i++) {
      if (e != null && !e.isEmpty() && like.startsWith(e, i) && i + e.length() < like.length()) {
        i += e.length();
        regex.append(Pattern.quote(String.valueOf(like.charAt(i))));
      } else if (like.charAt(i) == '%') {
        regex.append(".*");
      } else if (like.charAt(i) == '_') {
        regex.append('.');
      } else {
        regex.append(Pattern.quote(String.valueOf(like.charAt(i))));
      }
    }
    return Pattern.compile(regex.toString(), Pattern.DOTALL);
  }

  private static String typeName(String jdbcType) {
    return jdbcType.equals("BASE TABLE") ? "table" : jdbcType.toLowerCase(Locale.ROOT);
  }

  /** The column's type as written in DDL: {@code varchar(20)}, {@code numeric(10,2)}. */
  private static String type(ResultSet rs) throws SQLException {
    String name = String.valueOf(rs.getString("TYPE_NAME"));
    if (name.contains("(")) {
      return name;
    }
    int size = rs.getInt("COLUMN_SIZE");
    boolean hasSize = !rs.wasNull() && size > 0 && size < 1_000_000;
    switch (rs.getInt("DATA_TYPE")) {
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.NCHAR:
      case Types.NVARCHAR:
      case Types.BINARY:
      case Types.VARBINARY:
        return hasSize ? name + "(" + size + ")" : name;
      case Types.DECIMAL:
      case Types.NUMERIC:
        {
          int scale = rs.getInt("DECIMAL_DIGITS");
          boolean hasScale = !rs.wasNull();
          if (!hasSize || size >= 1000) {
            return name;
          }
          return name + "(" + size + (hasScale ? "," + scale : "") + ")";
        }
      default:
        return name;
    }
  }
}
