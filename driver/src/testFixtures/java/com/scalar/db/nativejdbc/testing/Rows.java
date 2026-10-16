package com.scalar.db.nativejdbc.testing;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** A query's rows as strings, for assertions: each row's columns joined by {@code /}. */
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
      return rows(st.executeQuery(sql));
    }
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
