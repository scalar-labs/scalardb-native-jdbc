package com.scalar.db.nativejdbc.isolation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The dependencies between the two nodes' committed transactions in a list-append history, the
 * driver's ({@code n0}) and ScalarDB's ({@code n1}) in a mixed run, which Elle checks but does not
 * count for a valid one: write-write from each key's order of appends, write-read from the last
 * element a read returns, read-write from the element appended after it. A pair is counted once per
 * key it meets on.
 *
 * <p>Maelstrom gives process {@code p} the node {@code n(p mod 2)} with two nodes and an even
 * concurrency, a crashed process's successor {@code p + concurrency} included: {@code n0}, the the
 * even processes (testing plan T5).
 */
final class Dependencies {

  private static final Pattern OK =
      Pattern.compile(
          "\\{:index \\d+, :time \\d+, :type :ok, :process (\\d+), :f :txn, :value \\[(.*)\\]\\}");
  private static final Pattern OP =
      Pattern.compile("\\[:(r|append) (\\d+) (nil|\\[[^\\]]*\\]|\\d+)\\]");

  /** The counts, by kind and direction, as {@code ww n0->n1}. */
  final Map<String, Long> counts = new TreeMap<>();

  private Dependencies() {}

  /** The counts of the history Maelstrom wrote under the run's {@code store}. */
  static Dependencies of(Path store) throws IOException {
    Path history;
    try (var files = Files.walk(store)) {
      history =
          files
              .filter(f -> f.toString().endsWith("history.edn"))
              .findFirst()
              .orElseThrow(() -> new IOException("no history.edn under " + store));
    }
    // the committed transactions: their side, appends and reads
    List<Boolean> n0 = new ArrayList<>();
    Map<String, Integer> writer = new HashMap<>(); // "key element" -> transaction
    List<List<long[]>> reads = new ArrayList<>(); // per transaction: key, then the list read
    Map<Long, List<Long>> longest = new HashMap<>();
    for (String line : Files.readAllLines(history, StandardCharsets.UTF_8)) {
      Matcher m = OK.matcher(line);
      if (!m.matches()) {
        continue;
      }
      int t = n0.size();
      n0.add(Long.parseLong(m.group(1)) % 2 == 0);
      List<long[]> read = new ArrayList<>();
      Matcher op = OP.matcher(m.group(2));
      while (op.find()) {
        long key = Long.parseLong(op.group(2));
        if (op.group(1).equals("append")) {
          writer.put(key + " " + op.group(3), t);
        } else if (!op.group(3).equals("nil")) {
          String inner = op.group(3).substring(1, op.group(3).length() - 1).trim();
          List<Long> list = new ArrayList<>();
          for (String e : inner.isEmpty() ? new String[0] : inner.split(" ")) {
            list.add(Long.parseLong(e));
          }
          long[] r = new long[list.size() + 1];
          r[0] = key;
          for (int i = 0; i < list.size(); i++) {
            r[i + 1] = list.get(i);
          }
          read.add(r);
          longest.merge(key, list, (a, b) -> a.size() >= b.size() ? a : b);
        } else {
          read.add(new long[] {key});
        }
      }
      reads.add(read);
    }
    Dependencies d = new Dependencies();
    for (Map.Entry<Long, List<Long>> k : longest.entrySet()) {
      List<Long> order = k.getValue();
      for (int i = 0; i + 1 < order.size(); i++) {
        d.count(
            "ww",
            writer.get(k.getKey() + " " + order.get(i)),
            writer.get(k.getKey() + " " + order.get(i + 1)),
            n0);
      }
    }
    for (int t = 0; t < reads.size(); t++) {
      for (long[] r : reads.get(t)) {
        int n = r.length - 1;
        List<Long> order = longest.getOrDefault(r[0], List.of());
        if (n > 0) {
          d.count("wr", writer.get(r[0] + " " + r[n]), t, n0);
        }
        if (n < order.size()) {
          d.count("rw", t, writer.get(r[0] + " " + order.get(n)), n0);
        }
      }
    }
    return d;
  }

  private void count(String kind, @Nullable Integer from, @Nullable Integer to, List<Boolean> n0) {
    if (from == null || to == null || from.equals(to)) {
      return; // an element whose append did not commit, or the transaction's own
    }
    counts.merge(
        String.format(
            Locale.ROOT, "%s %s->%s", kind, n0.get(from) ? "n0" : "n1", n0.get(to) ? "n0" : "n1"),
        1L,
        Long::sum);
  }

  long get(String kind, String from, String to) {
    return counts.getOrDefault(kind + " " + from + "->" + to, 0L);
  }

  @Override
  public String toString() {
    return counts.toString();
  }
}
