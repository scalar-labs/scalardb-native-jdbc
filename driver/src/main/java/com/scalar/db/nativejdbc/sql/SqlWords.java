package com.scalar.db.nativejdbc.sql;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Finds the names a statement's text contains without parsing it, for a statement the driver sends
 * as written (passthrough, todo 323): the words of the text, lower case, its comments left out and
 * its string literals and quoted identifiers kept, so that a name in a function's body or in SQL
 * built from a literal is found too. Erring that way is intended: a word that names no table there
 * still counts.
 */
public final class SqlWords {

  private SqlWords() {}

  /**
   * Returns the first of {@code names} that the text contains as a word, or as a run of text for a
   * name that is not one word, in any case.
   *
   * @param sql the statement's text
   * @param engine the engine, which decides whether {@code #} starts a comment
   * @param names the names, in any case
   * @return the name found, as given, or null
   */
  public static @Nullable String find(String sql, Engine engine, Collection<String> names) {
    String text = withoutComments(sql, engine).toLowerCase(Locale.ROOT);
    Set<String> words = new HashSet<>();
    int i = 0;
    while (i < text.length()) {
      if (!isWordChar(text.charAt(i))) {
        i++;
        continue;
      }
      int start = i;
      while (i < text.length() && isWordChar(text.charAt(i))) {
        i++;
      }
      words.add(text.substring(start, i));
    }
    for (String name : names) {
      String n = name.toLowerCase(Locale.ROOT);
      if (words.contains(n) || (!isWord(n) && text.contains(n))) {
        return name;
      }
    }
    return null;
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
  }

  private static boolean isWord(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (!isWordChar(s.charAt(i))) {
        return false;
      }
    }
    return !s.isEmpty();
  }

  /**
   * The text with its comments blanked: {@code --} to the end of the line, {@code /* ... * /}, and
   * on MySQL and MariaDB {@code #} to the end of the line; string literals and quoted identifiers,
   * where those do not start a comment, kept as they stand.
   */
  static String withoutComments(String sql, Engine engine) {
    boolean hash = engine == Engine.MYSQL || engine == Engine.MARIADB;
    boolean backslash = hash; // MySQL and MariaDB escape a quote with a backslash too
    StringBuilder out = new StringBuilder(sql.length());
    int i = 0;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '\'' || c == '"' || c == '`') {
        int end = quoted(sql, i, c, backslash);
        out.append(sql, i, end);
        i = end;
      } else if (c == '[' && engine == Engine.SQLSERVER) {
        int end = sql.indexOf(']', i + 1);
        end = end < 0 ? sql.length() : end + 1;
        out.append(sql, i, end);
        i = end;
      } else if ((c == '-' && sql.startsWith("--", i)) || (c == '#' && hash)) {
        int end = sql.indexOf('\n', i);
        end = end < 0 ? sql.length() : end;
        out.append(" ".repeat(end - i));
        i = end;
      } else if (c == '/' && sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i + 2);
        end = end < 0 ? sql.length() : end + 2;
        out.append(" ".repeat(end - i));
        i = end;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** The end of a quoted run that starts at {@code start}, its closing quote included. */
  private static int quoted(String sql, int start, char quote, boolean backslash) {
    int i = start + 1;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (backslash && c == '\\' && quote == '\'') {
        i += 2;
      } else if (c == quote) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
          i += 2; // a doubled quote stands for one
        } else {
          return i + 1;
        }
      } else {
        i++;
      }
    }
    return sql.length();
  }
}
