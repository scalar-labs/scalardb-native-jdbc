package com.scalar.db.nativejdbc.coverage;

import com.scalar.db.nativejdbc.engine.Engine;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The read corpus of {@code src/integrationTest/resources/corpus}: the shared files and those of
 * the engine's directory, every file but {@code schema.sql} (README.md there).
 */
final class Corpus {

  /** One statement: its file, its text, and whether its rows compare in order. */
  record Entry(String file, String sql, boolean ordered) {}

  private static final Pattern LIMIT =
      Pattern.compile("(?i)\\b(LIMIT|FETCH\\s+FIRST|FETCH\\s+NEXT|OFFSET|TOP)\\b");

  private Corpus() {}

  static List<Entry> load(Engine engine) throws IOException, URISyntaxException {
    Path root =
        Path.of(
            Objects.requireNonNull(Corpus.class.getClassLoader().getResource("corpus"), "corpus")
                .toURI());
    List<Path> files = new ArrayList<>(sqlFiles(root));
    Path own = root.resolve(engine.name().toLowerCase(Locale.ROOT));
    if (Files.isDirectory(own)) {
      files.addAll(sqlFiles(own));
    }
    List<Entry> out = new ArrayList<>();
    for (Path f : files) {
      String name = root.relativize(f).toString();
      out.addAll(parse(name, Files.readString(f, StandardCharsets.UTF_8)));
    }
    return out;
  }

  private static List<Path> sqlFiles(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(".sql"))
          .filter(p -> !String.valueOf(p.getFileName()).equals("schema.sql"))
          .sorted()
          .toList();
    }
  }

  /**
   * The statements of one file. A statement with a row limit must be marked {@code -- ordered}:
   * without a total order its rows are any of several, and the comparison would be noise.
   */
  static List<Entry> parse(String file, String text) {
    List<Entry> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean ordered = false;
    for (String line : text.split("\n", -1)) {
      String t = line.strip();
      if (t.equals("-- ordered")) {
        ordered = true;
        continue;
      }
      if (t.isEmpty() || t.startsWith("--")) {
        continue;
      }
      cur.append(cur.length() == 0 ? "" : " ").append(t);
      if (t.endsWith(";")) {
        String sql = cur.substring(0, cur.length() - 1);
        if (!ordered && LIMIT.matcher(sql).find()) {
          throw new IllegalStateException(
              file + ": a row limit needs -- ordered and a total ORDER BY: " + sql);
        }
        out.add(new Entry(file, sql, ordered));
        cur.setLength(0);
        ordered = false;
      }
    }
    return out;
  }
}
