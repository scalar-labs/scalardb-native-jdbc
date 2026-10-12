package com.scalar.db.nativejdbc.shell;

import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jline.utils.WCWidth;
import org.jspecify.annotations.Nullable;

/**
 * Prints rows as psql does: an aligned table with a centred header and numbers right-aligned, or
 * with {@code \x} one record per block.
 */
final class Printer {

  /** Rows to print: their column labels, which columns are numeric, and the values as text. */
  static final class Rows {
    final String[] labels;
    final boolean[] numeric;
    final List<String[]> values = new ArrayList<>();

    Rows(String... labels) {
      this.labels = labels.clone();
      this.numeric = new boolean[labels.length];
    }

    /** Adds a row; a null value prints empty, as psql prints NULL. */
    Rows add(@Nullable String... row) {
      String[] r = new String[labels.length];
      for (int c = 0; c < r.length; c++) {
        r[c] = c < row.length && row[c] != null ? printable(row[c]) : "";
      }
      values.add(r);
      return this;
    }
  }

  private Printer() {}

  /**
   * Reads a result set to its end. The rows are held in memory to size the columns, as psql does
   * without {@code FETCH_COUNT}.
   *
   * @param rs the result set
   * @return its rows
   * @throws SQLException if reading the result set fails
   */
  static Rows read(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    String[] labels = new String[md.getColumnCount()];
    for (int c = 0; c < labels.length; c++) {
      labels[c] = printable(md.getColumnLabel(c + 1));
    }
    Rows rows = new Rows(labels);
    for (int c = 0; c < labels.length; c++) {
      rows.numeric[c] = isNumeric(md.getColumnType(c + 1));
    }
    while (rs.next()) {
      String[] row = new String[labels.length];
      for (int c = 0; c < row.length; c++) {
        row[c] = rs.getString(c + 1);
      }
      rows.add(row);
    }
    return rows;
  }

  /**
   * Prints rows, then {@code (n rows)} unless expanded, as psql does.
   *
   * @param rows the rows
   * @param rawTitle a line centred above the table, such as {@code List of relations}, or null
   * @param expanded whether to print one record per block, psql's {@code \x}
   * @param footer whether to end with the row count
   * @param out where to print
   */
  static void print(
      Rows rows, @Nullable String rawTitle, boolean expanded, boolean footer, PrintWriter out) {
    // a title may carry stored names, such as \d's Table "schema.name"
    String title = rawTitle == null ? null : printable(rawTitle);
    if (expanded) {
      expanded(rows, title, out);
      return;
    }
    int columns = rows.labels.length;
    int[] widths = new int[columns];
    for (int c = 0; c < columns; c++) {
      widths[c] = width(rows.labels[c]);
      for (String[] row : rows.values) {
        widths[c] = Math.max(widths[c], width(row[c]));
      }
    }
    if (title != null) {
      int total = columns == 0 ? 0 : columns * 3 - 1;
      for (int w : widths) {
        total += w;
      }
      out.println(stripTrailing(new StringBuilder(centre(title, total))));
    }
    StringBuilder line = new StringBuilder();
    for (int c = 0; c < columns; c++) {
      line.append(c == 0 ? " " : " | ").append(centre(rows.labels[c], widths[c]));
    }
    out.println(stripTrailing(line));
    line.setLength(0);
    for (int c = 0; c < columns; c++) {
      line.append(c == 0 ? "" : "+").append("-".repeat(widths[c] + 2));
    }
    out.println(line);
    for (String[] row : rows.values) {
      line.setLength(0);
      for (int c = 0; c < columns; c++) {
        line.append(c == 0 ? " " : " | ").append(pad(row[c], widths[c], rows.numeric[c]));
      }
      out.println(stripTrailing(line));
    }
    if (footer) {
      out.println(count(rows));
      out.println();
    }
  }

  /** psql's expanded display: {@code -[ RECORD 1 ]---} and a {@code label | value} line each. */
  private static void expanded(Rows rows, @Nullable String title, PrintWriter out) {
    if (title != null) {
      out.println(title);
    }
    if (rows.values.isEmpty()) {
      out.println("(0 rows)");
      out.println();
      return;
    }
    int labelWidth = 0;
    for (String label : rows.labels) {
      labelWidth = Math.max(labelWidth, width(label));
    }
    int valueWidth = 0;
    for (String[] row : rows.values) {
      for (String v : row) {
        valueWidth = Math.max(valueWidth, width(v));
      }
    }
    int lineWidth = labelWidth + 3 + valueWidth;
    for (int r = 0; r < rows.values.size(); r++) {
      String header = "-[ RECORD " + (r + 1) + " ]";
      out.println(header + "-".repeat(Math.max(0, lineWidth - header.length())));
      String[] row = rows.values.get(r);
      for (int c = 0; c < row.length; c++) {
        out.println(
            stripTrailing(
                new StringBuilder(pad(rows.labels[c], labelWidth, false))
                    .append(" | ")
                    .append(row[c])));
      }
    }
    out.println();
  }

  private static String count(Rows rows) {
    return rows.values.size() == 1 ? "(1 row)" : "(" + rows.values.size() + " rows)";
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

  /**
   * The value with its control characters escaped, {@code \n} or {@code \x1b}: a stored escape
   * sequence would otherwise reach the terminal, and a newline or tab would break the alignment.
   */
  static String printable(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch == '\n') {
        b.append("\\n");
      } else if (ch == '\t') {
        b.append("\\t");
      } else if (Character.isISOControl(ch)) {
        b.append(String.format(Locale.ROOT, "\\x%02x", (int) ch));
      } else {
        b.append(ch);
      }
    }
    return b.toString();
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
    String fill = " ".repeat(Math.max(0, width - width(s)));
    return right ? fill + s : s + fill;
  }

  private static String centre(String s, int width) {
    int fill = Math.max(0, width - width(s));
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
