package com.scalar.db.nativejdbc.sql;

import com.scalar.db.nativejdbc.engine.Engine;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
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
    return first(withoutComments(sql, engine).toLowerCase(Locale.ROOT), names);
  }

  /** The words a statement may start with to be sent as written unparsed (todo 314). */
  private static final Set<String> AS_WRITTEN =
      Set.of(
          "select", "with", "values", "table", "insert", "update", "delete", "merge", "create",
          "alter", "drop", "comment");

  /**
   * PostgreSQL's cursor statements; elsewhere {@code DECLARE} starts a block of procedural code.
   */
  private static final Set<String> POSTGRESQL_CURSORS = Set.of("declare", "fetch", "move", "close");

  /** Words of a {@code CREATE} or {@code ALTER} that stores code or changes the session. */
  private static final Set<String> CODE_OR_SESSION =
      Set.of(
          "function",
          "procedure",
          "trigger",
          "rule",
          "package",
          "body",
          "event",
          "routine",
          "library",
          "java",
          "session",
          "system");

  /** PostgreSQL's Unicode escapes, by which a name is spelled without its letters. */
  private static final Pattern UNICODE_ESCAPE = Pattern.compile("(?i)u&['\"]");

  /**
   * Returns why a statement the parser could not read is not sent as written, or null when it may
   * be (todo 314). The parsed path decides by the statement's tables; here only its text is known,
   * so the checks err toward refusing:
   *
   * <ul>
   *   <li>one statement, with no {@code ;} but at its end;
   *   <li>a query, DML or DDL, by its first word: transaction and session control change what the
   *       driver tracks, and procedural code reaches tables without naming them, as does a {@code
   *       CREATE} or {@code ALTER} of a function, trigger or rule;
   *   <li>no function that runs SQL given as text, nor PostgreSQL's {@code set_config};
   *   <li>no name spelled other than by its letters: PostgreSQL's Unicode escapes, an invisible
   *       formatting character, a comment MySQL or MariaDB runs;
   *   <li>none of {@code names}, in any case, anywhere in the text, comments included.
   * </ul>
   *
   * @param sql the statement's text
   * @param engine the engine
   * @param names the names the statement must not contain
   * @return the reason, or null
   */
  public static @Nullable String whyNotAsWritten(
      String sql, Engine engine, Collection<String> names) {
    if (sql.codePoints().anyMatch(c -> Character.getType(c) == Character.FORMAT)) {
      return "it holds an invisible formatting character";
    }
    if (engine == Engine.POSTGRESQL && UNICODE_ESCAPE.matcher(sql).find()) {
      return "it holds a Unicode-escaped name or string (U&)";
    }
    if ((engine == Engine.MYSQL || engine == Engine.MARIADB)
        && (sql.contains("/*!") || sql.contains("/*M!"))) {
      return "it holds a comment that MySQL and MariaDB run (/*! ... */)";
    }
    String text = sql.toLowerCase(Locale.ROOT);
    Set<String> words = words(text);
    String head = firstWord(withoutComments(sql, engine).toLowerCase(Locale.ROOT));
    if (!(AS_WRITTEN.contains(head)
        || (engine == Engine.POSTGRESQL && POSTGRESQL_CURSORS.contains(head))
        || ((engine == Engine.MYSQL || engine == Engine.MARIADB) && head.equals("replace")))) {
      return "only a query, DML or DDL is sent as written, and it starts with "
          + (head.isEmpty() ? "no word" : head.toUpperCase(Locale.ROOT));
    }
    String body = sql.strip();
    while (body.endsWith(";")) {
      body = body.substring(0, body.length() - 1).strip();
    }
    if (body.indexOf(';') >= 0) {
      return "it may hold more than one statement";
    }
    if (head.equals("create") || head.equals("alter")) {
      for (String w : words) {
        if (CODE_OR_SESSION.contains(w)) {
          return "it may store code or change the session (" + w.toUpperCase(Locale.ROOT) + ")";
        }
      }
    }
    for (String w : words) {
      if (engine.runsSqlText(List.of(w)) || w.equals("set_config")) {
        return "it calls " + w + ", which runs SQL given as text or changes the session";
      }
    }
    String named = first(text, names);
    return named == null ? null : "it names " + named + ", which ScalarDB manages";
  }

  /**
   * Returns whether a statement starts with {@code CREATE}, {@code ALTER}, {@code DROP} or {@code
   * COMMENT}, its comments left out.
   *
   * @param sql the statement's text
   * @param engine the engine, which decides whether {@code #} starts a comment
   * @return whether it is DDL by its first word
   */
  public static boolean startsDdl(String sql, Engine engine) {
    return Set.of("create", "alter", "drop", "comment")
        .contains(firstWord(withoutComments(sql, engine).toLowerCase(Locale.ROOT)));
  }

  /** The first word of a text, lower case already, or empty. */
  private static String firstWord(String text) {
    int i = 0;
    while (i < text.length() && !isWordChar(text.charAt(i))) {
      if (!Character.isWhitespace(text.charAt(i)) && text.charAt(i) != '(') {
        return "";
      }
      i++;
    }
    int start = i;
    while (i < text.length() && isWordChar(text.charAt(i))) {
      i++;
    }
    return text.substring(start, i);
  }

  /** The first of {@code names} a lower-case text contains, as {@link #find} matches. */
  private static @Nullable String first(String text, Collection<String> names) {
    Set<String> words = words(text);
    for (String name : names) {
      String n = name.toLowerCase(Locale.ROOT);
      if (words.contains(n) || (!isWord(n) && text.contains(n))) {
        return name;
      }
    }
    return null;
  }

  private static Set<String> words(String text) {
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
    return words;
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
