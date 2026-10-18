package com.scalar.db.nativejdbc.testing;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A query's rows as strings, for assertions: each row's columns joined by {@code /}. */
@SuppressFBWarnings(value = "REDOS", justification = "patterns over the tests' own statements")
public final class Rows {

  private Rows() {}

  /**
   * Runs a query and returns its rows.
   *
   * @param c the connection
   * @param sql the query
   * @return the rows
   * @throws SQLException when the query fails
   */
  @SuppressFBWarnings(value = "SQL_INJECTION_JDBC", justification = "statements of the tests")
  public static List<String> rows(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      return rows(
          st.executeQuery(
              c.getMetaData().getDatabaseProductName().equals("Microsoft SQL Server")
                  ? tsql(sql)
                  : sql));
    }
  }

  private static final Pattern LOCK =
      Pattern.compile(
          "(?i)\\s+FOR\\s+(UPDATE|SHARE)(?:\\s+OF\\s+\\S+)?(\\s+NOWAIT|\\s+SKIP\\s+LOCKED)?\\s*(;?)$");
  private static final Pattern LIMIT = Pattern.compile("(?i)\\s+LIMIT\\s+(\\d+)$");
  private static final Pattern TABLE =
      Pattern.compile(
          "(?i)\\bFROM\\s+[\\w.\\[\\]]+(\\s+(?:AS\\s+)?(?!WHERE\\b|ORDER\\b|JOIN\\b|GROUP\\b|LEFT\\b"
              + "|INNER\\b|ON\\b)\\w+)?");

  /**
   * A test's query in T-SQL: its trailing locking clause as table hints on the first table it
   * reads, FOR UPDATE as UPDLOCK, FOR SHARE as REPEATABLEREAD, and LIMIT as TOP or FETCH.
   *
   * @param sql a query with PostgreSQL's and MySQL's clauses
   * @return the query as SQL Server takes it
   */
  public static String tsql(String sql) {
    String s = sql;
    List<String> hints = new ArrayList<>();
    Matcher lock = LOCK.matcher(s);
    if (lock.find()) {
      hints.add(lock.group(1).equalsIgnoreCase("UPDATE") ? "UPDLOCK" : "REPEATABLEREAD");
      hints.add("ROWLOCK");
      String wait = lock.group(2) == null ? "" : lock.group(2).strip().toUpperCase(Locale.ROOT);
      if (wait.equals("NOWAIT")) {
        hints.add("NOWAIT");
      } else if (!wait.isEmpty()) {
        hints.add("READPAST");
      }
      s = s.substring(0, lock.start()) + lock.group(3);
    }
    Matcher limit = LIMIT.matcher(s);
    if (limit.find()) {
      String n = limit.group(1);
      s = s.substring(0, limit.start());
      s =
          s.toUpperCase(Locale.ROOT).contains(" ORDER BY ")
              ? s + " OFFSET 0 ROWS FETCH NEXT " + n + " ROWS ONLY"
              : s.replaceFirst("(?i)\\bSELECT\\s+", "SELECT TOP " + n + " ");
    }
    if (!hints.isEmpty()) {
      Matcher t = TABLE.matcher(s);
      if (t.find()) {
        s =
            s.substring(0, t.end())
                + " WITH ("
                + String.join(", ", hints)
                + ")"
                + s.substring(t.end());
      }
    }
    return s;
  }

  /**
   * Runs a prepared query and returns its rows.
   *
   * @param ps the query, its parameters set
   * @return the rows
   * @throws SQLException when the query fails
   */
  public static List<String> rows(PreparedStatement ps) throws SQLException {
    return rows(ps.executeQuery());
  }

  /**
   * Reads a result set to its end, and closes it.
   *
   * @param result the result set
   * @return the rows
   * @throws SQLException when reading fails
   */
  public static List<String> rows(ResultSet result) throws SQLException {
    try (ResultSet rs = result) {
      int n = rs.getMetaData().getColumnCount();
      List<String> out = new ArrayList<>();
      while (rs.next()) {
        List<String> row = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
          row.add(rs.getString(i));
        }
        out.add(String.join("/", row));
      }
      return out;
    }
  }
}
