package com.scalar.db.nativejdbc.coverage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The statements of a psql script, as psql sends them: each ends at a semicolon outside quotes,
 * comments and parentheses. What only psql understands is left out: its backslash commands, a
 * statement a backslash command ends ({@code \gset}, {@code \gexec}), one that uses a psql
 * variable, {@code COPY} from or to psql with its data, and statements psql joins with {@code \;}.
 */
final class PsqlScript {

  private static final Pattern COPY_FROM_STDIN =
      Pattern.compile("(?is)^\\s*COPY\\b.*\\bFROM\\s+(STDIN|STDOUT)\\b.*");
  private static final Pattern COPY_TO_STDOUT =
      Pattern.compile("(?is)^\\s*COPY\\b.*\\bTO\\s+STDOUT\\b.*");
  private static final Pattern SET = Pattern.compile("^\\\\set\\s+(\\w+)");
  private static final Pattern DOLLAR_TAG = Pattern.compile("\\$(?:[A-Za-z_]\\w*)?\\$");

  private PsqlScript() {}

  static List<String> statements(String script) {
    List<String> out = new ArrayList<>();
    Set<String> variables = new HashSet<>();
    StringBuilder cur = new StringBuilder();
    boolean copyData = false;
    int depth = 0;
    String dollar = null; // the open dollar quote's tag
    int block = 0; // nesting of /* */ comments
    boolean single = false;
    boolean escapes = false; // an E'' string
    boolean dquote = false;
    for (String line : script.split("\n", -1)) {
      if (copyData) {
        copyData = !line.equals("\\.");
        continue;
      }
      boolean inside = dollar != null || block > 0 || single || dquote;
      if (!inside && line.strip().startsWith("\\")) {
        String meta = line.strip();
        Matcher m = SET.matcher(meta);
        if (m.find()) {
          variables.add(m.group(1));
        }
        if (meta.startsWith("\\g") && !cur.toString().isBlank()) {
          cur.setLength(0); // a statement psql runs itself, with its output to psql
          depth = 0;
        }
        continue;
      }
      for (int i = 0; i < line.length(); i++) {
        char c = line.charAt(i);
        if (dollar != null) {
          if (line.startsWith(dollar, i)) {
            cur.append(dollar);
            i += dollar.length() - 1;
            dollar = null;
          } else {
            cur.append(c);
          }
          continue;
        }
        if (block > 0) {
          if (line.startsWith("*/", i)) {
            block--;
            cur.append("*/");
            i++;
          } else if (line.startsWith("/*", i)) {
            block++;
            cur.append("/*");
            i++;
          } else {
            cur.append(c);
          }
          continue;
        }
        if (single) {
          cur.append(c);
          if (escapes && c == '\\' && i + 1 < line.length()) {
            cur.append(line.charAt(++i));
          } else if (c == '\'') {
            if (i + 1 < line.length() && line.charAt(i + 1) == '\'') {
              cur.append(line.charAt(++i));
            } else {
              single = false;
            }
          }
          continue;
        }
        if (dquote) {
          cur.append(c);
          dquote = c != '"';
          continue;
        }
        if (line.startsWith("--", i)) {
          break; // to the end of the line
        }
        if (line.startsWith("/*", i)) {
          block++;
          cur.append("/*");
          i++;
          continue;
        }
        if (c == '\'') {
          single = true;
          escapes = i > 0 && (line.charAt(i - 1) == 'E' || line.charAt(i - 1) == 'e');
        } else if (c == '"') {
          dquote = true;
        } else if (c == '$') {
          Matcher m = DOLLAR_TAG.matcher(line).region(i, line.length());
          if (m.lookingAt() && (i == 0 || !Character.isLetterOrDigit(line.charAt(i - 1)))) {
            dollar = m.group();
            cur.append(dollar);
            i += dollar.length() - 1;
            continue;
          }
        } else if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
        } else if (c == ';' && depth <= 0) {
          String st = cur.toString().strip();
          cur.setLength(0);
          depth = 0;
          if (COPY_FROM_STDIN.matcher(st).matches()) {
            copyData = true;
          } else if (!st.isEmpty()
              && !usesVariable(st, variables)
              && !st.endsWith("\\") // psql's \; sends two statements as one
              && !COPY_TO_STDOUT.matcher(st).matches()) {
            out.add(st);
          }
          continue;
        }
        cur.append(c);
      }
      cur.append('\n');
    }
    return out;
  }

  /**
   * Whether a statement names a psql variable: {@code :'v'}, {@code :"v"} or a {@code \set} one.
   */
  private static boolean usesVariable(String st, Set<String> variables) {
    if (st.contains(":'") || st.contains(":\"")) {
      return true;
    }
    Matcher m = Pattern.compile("(?<!:):(\\w+)").matcher(st);
    while (m.find()) {
      if (variables.contains(m.group(1))
          || variables.contains(m.group(1).toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
