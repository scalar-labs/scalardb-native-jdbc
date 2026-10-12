package com.scalar.db.nativejdbc.shell;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Splits typed text into statements the way the engine's own client does: at {@code ;} outside
 * quotes, identifiers and comments, and at a terminator line, {@code /} on Oracle and {@code GO} on
 * SQL Server, which also ends a PL/SQL or T-SQL block whose body holds {@code ;}.
 *
 * <p>It is a pure function over the whole buffer, rescanned on every line: buffers are typed by
 * hand and small. Anything it cannot split, such as a MySQL procedure body without a {@code
 * DELIMITER} command or a PostgreSQL {@code BEGIN ATOMIC} body, is split at its first {@code ;} and
 * the engine's error shown.
 */
@SuppressFBWarnings(
    value = "REDOS",
    justification =
        "the patterns match the start of a statement the user typed, in the user's own shell")
final class StatementSplitter {

  /** The lexical rules of one engine family. */
  enum Dialect {
    POSTGRESQL(null, null),
    MYSQL(null, null),
    SQLSERVER(
        "GO",
        Pattern.compile(
            "(?i)(?:CREATE\\s+(?:OR\\s+ALTER\\s+)?|ALTER\\s+)(?:PROCEDURE|PROC|FUNCTION|TRIGGER)\\b")),
    ORACLE(
        "/",
        Pattern.compile(
            "(?i)(?:CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?"
                + "(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE\\s+BODY)\\b|DECLARE\\b|BEGIN\\b)"));

    private final @Nullable String terminatorLine;
    private final @Nullable Pattern blockStart;

    Dialect(@Nullable String terminatorLine, @Nullable Pattern blockStart) {
      this.terminatorLine = terminatorLine;
      this.blockStart = blockStart;
    }

    /**
     * The dialect of a sub-protocol of the driver's URL.
     *
     * @param subProtocol {@code postgresql}, {@code mysql}, {@code mariadb}, {@code sqlserver} or
     *     {@code oracle}
     * @return the dialect
     */
    static Dialect of(String subProtocol) {
      switch (subProtocol.toLowerCase(Locale.ROOT)) {
        case "postgresql":
          return POSTGRESQL;
        case "mysql":
        case "mariadb":
          return MYSQL;
        case "sqlserver":
          return SQLSERVER;
        case "oracle":
          return ORACLE;
        default:
          throw new IllegalArgumentException("Unsupported database '" + subProtocol + "'");
      }
    }
  }

  /** The statements a buffer completes and the text left over. */
  static final class Result {
    private final List<String> statements;
    private final String remainder;
    private final boolean pending;

    private Result(List<String> statements, String remainder, boolean pending) {
      this.statements = statements;
      this.remainder = remainder;
      this.pending = pending;
    }

    /** The complete statements, trimmed, without their terminator. */
    List<String> statements() {
      return statements;
    }

    /** The text after the last complete statement. */
    String remainder() {
      return remainder;
    }

    /**
     * Whether the remainder holds the start of a statement, rather than only whitespace and
     * complete comments: the buffer then waits for more lines.
     */
    boolean pending() {
      return pending;
    }
  }

  // Oracle's BEGIN opens a PL/SQL block, except the transaction command the shell itself handles
  private static final Pattern TRANSACTION_BEGIN =
      Pattern.compile("(?is)BEGIN(?:\\s+(?:WORK|TRANSACTION))?(?:\\s+ISOLATION\\s+LEVEL\\s+.*)?");

  private static final Pattern DOLLAR_TAG =
      Pattern.compile("\\$(?:[A-Za-z_\\u0080-\\uffff][A-Za-z_0-9\\u0080-\\uffff]*)?\\$");

  private StatementSplitter() {}

  /**
   * Splits a buffer.
   *
   * @param text the buffer, possibly several lines
   * @param dialect the engine's lexical rules
   * @return the complete statements and the remainder
   */
  static Result split(String text, Dialect dialect) {
    List<String> statements = new ArrayList<>();
    int n = text.length();
    int start = 0;
    boolean content = false;
    int i = 0;
    while (i < n) {
      if (dialect.terminatorLine != null && (i == 0 || text.charAt(i - 1) == '\n')) {
        int eol = text.indexOf('\n', i);
        int end = eol < 0 ? n : eol;
        if (text.substring(i, end).trim().equalsIgnoreCase(dialect.terminatorLine)) {
          if (content) {
            statements.add(text.substring(start, i).trim());
          }
          start = eol < 0 ? n : eol + 1;
          i = start;
          content = false;
          continue;
        }
      }
      char c = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : '\0';
      if (Character.isWhitespace(c)) {
        i++;
      } else if ((c == '-' && next == '-') || (c == '#' && dialect == Dialect.MYSQL)) {
        int eol = text.indexOf('\n', i);
        i = eol < 0 ? n : eol;
      } else if (c == '/' && next == '*') {
        int end = commentEnd(text, i, dialect == Dialect.POSTGRESQL);
        if (end < 0) {
          content = true; // an unterminated comment waits for its end
          i = n;
        } else {
          i = end;
        }
      } else if (c == ';') {
        String statement = text.substring(start, i).trim();
        if (!content || !isBlock(statement, dialect)) {
          if (content) {
            statements.add(statement);
          }
          start = i + 1;
          content = false;
        }
        i++;
      } else {
        content = true;
        i = skipToken(text, i, dialect);
      }
    }
    return new Result(statements, text.substring(start), content);
  }

  private static boolean isBlock(String statement, Dialect dialect) {
    if (dialect.blockStart == null) {
      return false;
    }
    String s = withoutLeadingComments(statement);
    Matcher m = dialect.blockStart.matcher(s);
    return m.lookingAt() && !(dialect == Dialect.ORACLE && TRANSACTION_BEGIN.matcher(s).matches());
  }

  /**
   * Strips the whitespace and comments a statement starts with.
   *
   * @param statement a statement
   * @return the statement from its first token
   */
  static String withoutLeadingComments(String statement) {
    int i = 0;
    int n = statement.length();
    while (i < n) {
      char c = statement.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (statement.startsWith("--", i)) {
        int eol = statement.indexOf('\n', i);
        i = eol < 0 ? n : eol;
      } else if (statement.startsWith("/*", i)) {
        int end = commentEnd(statement, i, true);
        i = end < 0 ? n : end;
      } else {
        break;
      }
    }
    return statement.substring(i);
  }

  /** The index after a block comment starting at {@code i}, or -1 if it does not end. */
  private static int commentEnd(String text, int i, boolean nested) {
    int depth = 0;
    int j = i;
    while (j + 1 < text.length()) {
      if (text.startsWith("/*", j)) {
        depth = nested ? depth + 1 : 1;
        j += 2;
      } else if (text.startsWith("*/", j)) {
        if (--depth == 0) {
          return j + 2;
        }
        j += 2;
      } else {
        j++;
      }
    }
    return -1;
  }

  /** Skips the token at {@code i}: a quoted string or identifier, a dollar quote, or one char. */
  private static int skipToken(String text, int i, Dialect dialect) {
    char c = text.charAt(i);
    switch (c) {
      case '\'':
        return quoteEnd(text, i, '\'', dialect == Dialect.MYSQL || isEString(text, i));
      case '"':
        return quoteEnd(text, i, '"', dialect == Dialect.MYSQL);
      case '`':
        return dialect == Dialect.MYSQL ? quoteEnd(text, i, '`', false) : i + 1;
      case '[':
        return dialect == Dialect.SQLSERVER ? quoteEnd(text, i, ']', false) : i + 1;
      case '$':
        if (dialect == Dialect.POSTGRESQL && (i == 0 || !isIdentifierChar(text.charAt(i - 1)))) {
          Matcher m = DOLLAR_TAG.matcher(text).region(i, text.length());
          if (m.lookingAt()) {
            int close = text.indexOf(m.group(), m.end());
            return close < 0 ? text.length() : close + m.group().length();
          }
        }
        return i + 1;
      default:
        return i + 1;
    }
  }

  /** The index after the quote closing the one at {@code i}, or the end if there is none. */
  private static int quoteEnd(String text, int i, char close, boolean backslashEscapes) {
    int j = i + 1;
    while (j < text.length()) {
      char c = text.charAt(j);
      if (backslashEscapes && c == '\\') {
        j += 2;
      } else if (c == close) {
        return j + 1; // a doubled quote reads as two adjacent quoted parts
      } else {
        j++;
      }
    }
    return text.length();
  }

  /** PostgreSQL's {@code E'...'}, where a backslash escapes. */
  private static boolean isEString(String text, int i) {
    return i > 0
        && (text.charAt(i - 1) == 'E' || text.charAt(i - 1) == 'e')
        && (i == 1 || !isIdentifierChar(text.charAt(i - 2)));
  }

  private static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
  }
}
