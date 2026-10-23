package com.scalar.db.nativejdbc.isolation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maelstrom's JSON: an object is a {@link Map}, an array a {@link List}, a number a {@link Long} or
 * a {@link Double}, and {@code null} null. No JSON library is on the tests' compile classpath.
 */
final class Json {

  private final String text;
  private int at;

  private Json(String text) {
    this.text = text;
  }

  static @Nullable Object parse(String text) {
    Json j = new Json(text);
    Object value = j.value();
    j.blanks();
    if (j.at != text.length()) {
      throw j.error("trailing text");
    }
    return value;
  }

  static String write(@Nullable Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(@Nullable Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String string) {
      out.append('"');
      for (int i = 0; i < string.length(); i++) {
        char c = string.charAt(i);
        switch (c) {
          case '"' -> out.append("\\\"");
          case '\\' -> out.append("\\\\");
          case '\n' -> out.append("\\n");
          case '\r' -> out.append("\\r");
          case '\t' -> out.append("\\t");
          default -> {
            if (c < 0x20) {
              out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
              out.append(c);
            }
          }
        }
      }
      out.append('"');
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!first) {
          out.append(',');
        }
        first = false;
        write(String.valueOf(e.getKey()), out);
        out.append(':');
        write(e.getValue(), out);
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      boolean first = true;
      for (Object e : list) {
        if (!first) {
          out.append(',');
        }
        first = false;
        write(e, out);
      }
      out.append(']');
    } else {
      out.append(value); // a number or a boolean
    }
  }

  private @Nullable Object value() {
    blanks();
    if (at >= text.length()) {
      throw error("end of text");
    }
    return switch (text.charAt(at)) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't' -> word("true", true);
      case 'f' -> word("false", false);
      case 'n' -> word("null", null);
      default -> number();
    };
  }

  private Map<String, @Nullable Object> object() {
    Map<String, @Nullable Object> m = new LinkedHashMap<>();
    at++;
    blanks();
    if (peek() == '}') {
      at++;
      return m;
    }
    while (true) {
      blanks();
      String key = string();
      blanks();
      expect(':');
      m.put(key, value());
      blanks();
      if (peek() == ',') {
        at++;
      } else {
        expect('}');
        return m;
      }
    }
  }

  private List<@Nullable Object> array() {
    List<@Nullable Object> a = new ArrayList<>();
    at++;
    blanks();
    if (peek() == ']') {
      at++;
      return a;
    }
    while (true) {
      a.add(value());
      blanks();
      if (peek() == ',') {
        at++;
      } else {
        expect(']');
        return a;
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder s = new StringBuilder();
    while (true) {
      if (at >= text.length()) {
        throw error("unterminated string");
      }
      char c = text.charAt(at++);
      if (c == '"') {
        return s.toString();
      }
      if (c != '\\') {
        s.append(c);
        continue;
      }
      char e = text.charAt(at++);
      switch (e) {
        case 'b' -> s.append('\b');
        case 'f' -> s.append('\f');
        case 'n' -> s.append('\n');
        case 'r' -> s.append('\r');
        case 't' -> s.append('\t');
        case 'u' -> {
          s.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
          at += 4;
        }
        default -> s.append(e);
      }
    }
  }

  private Object number() {
    int start = at;
    while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
      at++;
    }
    String n = text.substring(start, at);
    if (n.isEmpty()) {
      throw error("unexpected character");
    }
    return n.contains(".") || n.contains("e") || n.contains("E")
        ? (Object) Double.valueOf(n)
        : (Object) Long.valueOf(n);
  }

  private @Nullable Object word(String w, @Nullable Object value) {
    if (!text.startsWith(w, at)) {
      throw error("unexpected word");
    }
    at += w.length();
    return value;
  }

  private void blanks() {
    while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
      at++;
    }
  }

  private char peek() {
    return at < text.length() ? text.charAt(at) : '\0';
  }

  private void expect(char c) {
    if (peek() != c) {
      throw error("expected " + c);
    }
    at++;
  }

  private IllegalArgumentException error(String what) {
    return new IllegalArgumentException(what + " at " + at + " of " + text);
  }
}
