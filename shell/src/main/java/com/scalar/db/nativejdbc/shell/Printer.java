package com.scalar.db.nativejdbc.shell;

import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.WCWidth;

/** Prints a result set as psql's aligned table: a centred header, numbers right-aligned. */
final class Printer {

  private Printer() {}

  /**
   * Prints every row of a result set, then {@code (n rows)}. The rows are held in memory to size
   * the columns, as psql does without {@code FETCH_COUNT}.
   *
   * @param rs the result set, read to its end
   * @param out where to print
   * @throws SQLException if reading the result set fails
   */
  static void table(ResultSet rs, PrintWriter out) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    int columns = md.getColumnCount();
    String[] labels = new String[columns];
    boolean[] numeric = new boolean[columns];
    int[] widths = new int[columns];
    for (int c = 0; c < columns; c++) {
      labels[c] = md.getColumnLabel(c + 1);
      numeric[c] = isNumeric(md.getColumnType(c + 1));
      widths[c] = width(labels[c]);
    }
    List<String[]> rows = new ArrayList<>();
    while (rs.next()) {
      String[] row = new String[columns];
      for (int c = 0; c < columns; c++) {
        String v = rs.getString(c + 1);
        row[c] = v == null ? "" : v;
        widths[c] = Math.max(widths[c], width(row[c]));
      }
      rows.add(row);
    }

    StringBuilder line = new StringBuilder();
    for (int c = 0; c < columns; c++) {
      line.append(c == 0 ? " " : " | ").append(centre(labels[c], widths[c]));
    }
    out.println(stripTrailing(line));
    line.setLength(0);
    for (int c = 0; c < columns; c++) {
      line.append(c == 0 ? "" : "+").append("-".repeat(widths[c] + 2));
    }
    out.println(line);
    for (String[] row : rows) {
      line.setLength(0);
      for (int c = 0; c < columns; c++) {
        line.append(c == 0 ? " " : " | ").append(pad(row[c], widths[c], numeric[c]));
      }
      out.println(stripTrailing(line));
    }
    out.println(rows.size() == 1 ? "(1 row)" : "(" + rows.size() + " rows)");
    out.println();
  }

  private static boolean isNumeric(int type) {
    switch (type) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
      case Types.REAL:
      case Types.FLOAT:
      case Types.DOUBLE:
      case Types.NUMERIC:
      case Types.DECIMAL:
        return true;
      default:
        return false;
    }
  }

  /** The terminal columns a string takes: two for a wide character such as a kanji. */
  static int width(String s) {
    int w = 0;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      w += Math.max(0, WCWidth.wcwidth(cp));
      i += Character.charCount(cp);
    }
    return w;
  }

  private static String pad(String s, int width, boolean right) {
    String fill = " ".repeat(width - width(s));
    return right ? fill + s : s + fill;
  }

  private static String centre(String s, int width) {
    int fill = width - width(s);
    return " ".repeat(fill / 2) + s + " ".repeat(fill - fill / 2);
  }

  private static String stripTrailing(StringBuilder line) {
    int end = line.length();
    while (end > 0 && line.charAt(end - 1) == ' ') {
      end--;
    }
    return line.substring(0, end);
  }
}
