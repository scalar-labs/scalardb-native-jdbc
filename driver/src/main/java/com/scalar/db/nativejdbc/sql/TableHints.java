package com.scalar.db.nativejdbc.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL Server's table hints that the parser cannot read (design 4.7): {@code WITH (UPDLOCK,
 * ROWLOCK)}, and the hint list without {@code WITH}, which it reads as a function call or an
 * alias's column list. Each is blanked out of the text before parsing, its length kept so that
 * every offset still holds, and recorded for the table it follows. {@code WITH (NOLOCK)} and {@code
 * WITH (INDEX(...))}, which the parser reads, stay.
 */
final class TableHints {

  /** A hint list blanked out of the text: where it stood, and its hints, upper case. */
  static final class Clause {
    final int start;
    final int end;
    final List<String> hints;

    Clause(int start, int end, List<String> hints) {
      this.start = start;
      this.end = end;
      this.hints = List.copyOf(hints);
    }
  }

  /** The text with the clauses blanked, and the clauses. */
  static final class Result {
    final String text;
    final List<Clause> clauses;

    Result(String text, List<Clause> clauses) {
      this.text = text;
      this.clauses = List.copyOf(clauses);
    }
  }

  /** SQL Server's table hints. */
  private static final Set<String> HINTS =
      Set.of(
          "NOEXPAND",
          "INDEX",
          "FORCESEEK",
          "FORCESCAN",
          "HOLDLOCK",
          "NOLOCK",
          "NOWAIT",
          "PAGLOCK",
          "READCOMMITTED",
          "READCOMMITTEDLOCK",
          "READPAST",
          "READUNCOMMITTED",
          "REPEATABLEREAD",
          "ROWLOCK",
          "SERIALIZABLE",
          "SNAPSHOT",
          "SPATIAL_WINDOW_MAX_CELLS",
          "TABLOCK",
          "TABLOCKX",
          "UPDLOCK",
          "XLOCK",
          "KEEPIDENTITY",
          "KEEPDEFAULTS",
          "IGNORE_CONSTRAINTS",
          "IGNORE_TRIGGERS");

  /** The hints the parser reads in {@code WITH (...)}. */
  private static final Set<String> PARSED = Set.of("NOLOCK", "INDEX");

  /** Words after which a parenthesized list is never a table's hints. */
  private static final Set<String> NOT_A_TABLE =
      Set.of(
          "VALUES", "IN", "EXISTS", "AS", "ON", "AND", "OR", "NOT", "WHERE", "SELECT", "BY",
          "HAVING", "WHEN", "THEN", "ELSE", "RETURN", "OVER", "USING", "WITH", "INTO", "TOP",
          "OUTPUT");

  private TableHints() {}

  /** Whether {@code word} is one of SQL Server's table hints, in any case. */
  static boolean isHint(String word) {
    return HINTS.contains(word.toUpperCase(Locale.ROOT));
  }

  /** A token: its span, its kind, and its text upper case. */
  private static final class Token {
    final int start;
    final int end;
    final char kind;
    final String upper;

    Token(int start, int end, char kind, String upper) {
      this.start = start;
      this.end = end;
      this.kind = kind;
      this.upper = upper;
    }
  }

  /**
   * Blanks out the hint lists the parser cannot read.
   *
   * @param text a statement
   * @return the text, as long as before, and the lists blanked
   */
  static Result blank(String text) {
    List<Token> tokens = tokens(text);
    StringBuilder out = new StringBuilder(text);
    List<Clause> clauses = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      boolean with = t.upper.equals("WITH") && next(tokens, i + 1, "(");
      boolean legacy =
          !with
              && t.kind == '('
              && i > 0
              && (tokens.get(i - 1).kind == 'w' || tokens.get(i - 1).kind == 'q')
              && !NOT_A_TABLE.contains(tokens.get(i - 1).upper);
      int open = with ? i + 1 : legacy ? i : -1;
      if (open < 0) {
        continue;
      }
      List<String> hints = new ArrayList<>();
      int close = hintList(tokens, open, hints);
      if (close < 0) {
        continue;
      }
      if (with && PARSED.containsAll(hints)) {
        i = close;
        continue;
      }
      int start = tokens.get(with ? i : open).start;
      int end = tokens.get(close).end;
      for (int c = start; c < end; c++) {
        if (out.charAt(c) != '\n') {
          out.setCharAt(c, ' ');
        }
      }
      clauses.add(new Clause(start, end, hints));
      i = close;
    }
    return new Result(out.toString(), clauses);
  }

  /**
   * The tokens of {@code text} in {@code [from, to)}, comments and whitespace aside.
   *
   * @return how many there are
   */
  static int tokensBetween(String text, int from, int to) {
    int count = 0;
    for (Token t : tokens(text)) {
      if (t.start >= from && t.end <= to) {
        count++;
      }
    }
    return count;
  }

  /** The first token of {@code text} in {@code [from, to)}, upper case, or "" when none. */
  static String firstBetween(String text, int from, int to) {
    for (Token t : tokens(text)) {
      if (t.start >= from && t.end <= to) {
        return t.upper;
      }
    }
    return "";
  }

  private static boolean next(List<Token> tokens, int i, String image) {
    return i < tokens.size() && tokens.get(i).upper.equals(image);
  }

  /**
   * Reads {@code (hint [, hint]...)} from the parenthesis at {@code open}: each hint a known word,
   * with its own parenthesized arguments or {@code = value} if any.
   *
   * @return the closing parenthesis's index, or -1 when it is no hint list
   */
  private static int hintList(List<Token> tokens, int open, List<String> hints) {
    int i = open + 1;
    while (true) {
      if (i >= tokens.size() || !HINTS.contains(tokens.get(i).upper)) {
        return -1;
      }
      hints.add(tokens.get(i).upper);
      i++;
      if (next(tokens, i, "(")) {
        int depth = 0;
        for (; i < tokens.size(); i++) {
          String u = tokens.get(i).upper;
          depth += u.equals("(") ? 1 : u.equals(")") ? -1 : 0;
          if (depth == 0) {
            break;
          }
        }
        i++;
      } else if (next(tokens, i, "=") && i + 1 < tokens.size()) {
        i += 2;
      }
      if (next(tokens, i, ")")) {
        return i;
      }
      if (!next(tokens, i, ",")) {
        return -1;
      }
      i++;
    }
  }

  /**
   * T-SQL's tokens: words ({@code w}), quoted names ({@code q}), literals ({@code s}) and single
   * characters as their own kind; comments, nested as SQL Server nests them, and whitespace left
   * out.
   */
  private static List<Token> tokens(String text) {
    List<Token> out = new ArrayList<>();
    int n = text.length();
    int i = 0;
    while (i < n) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
        // a line comment ends at a carriage return too, for SQL Server and the parser alike
        while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
        int depth = 0;
        while (i < n) {
          if (text.startsWith("/*", i)) {
            depth++;
            i += 2;
          } else if (text.startsWith("*/", i)) {
            depth--;
            i += 2;
            if (depth == 0) {
              break;
            }
          } else {
            i++;
          }
        }
      } else if (c == '\'' || c == '"' || c == '[') {
        char close = c == '[' ? ']' : c;
        int start = i++;
        while (i < n) {
          if (text.charAt(i) == close) {
            if (i + 1 < n && text.charAt(i + 1) == close) {
              i += 2; // doubled: part of the literal or name
              continue;
            }
            i++;
            break;
          }
          i++;
        }
        out.add(new Token(start, i, c == '\'' ? 's' : 'q', text.substring(start, i)));
      } else if (Character.isLetterOrDigit(c) || c == '_' || c == '@' || c == '#' || c == '$') {
        int start = i;
        while (i < n
            && (Character.isLetterOrDigit(text.charAt(i)) || "_@#$".indexOf(text.charAt(i)) >= 0)) {
          i++;
        }
        // N'...' is a literal
        if (i - start == 1 && (c == 'N' || c == 'n') && i < n && text.charAt(i) == '\'') {
          continue;
        }
        out.add(new Token(start, i, 'w', text.substring(start, i).toUpperCase(Locale.ROOT)));
      } else {
        out.add(new Token(i, i + 1, c, String.valueOf(c)));
        i++;
      }
    }
    return out;
  }
}
