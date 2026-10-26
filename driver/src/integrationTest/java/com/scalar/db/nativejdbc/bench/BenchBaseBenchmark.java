package com.scalar.db.nativejdbc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Whole workloads through the driver against the vendor driver, with BenchBase (milestone T6 of
 * {@code docs/plans/testing.md}). Run with {@code -Dnativejdbc.benchbase=<dir>}, the directory of
 * an unpacked BenchBase distribution built for the engine; the result is written to {@code
 * build/bench/benchbase-<engine>.md}, BenchBase's own output beside it.
 *
 * <p>Four runs per workload, each on freshly loaded tables, as ScalarDB's schema and coordinator
 * tables exist in all of them:
 *
 * <ul>
 *   <li>(a) the vendor URL;
 *   <li>(b) the driver, every table plain: the cost of routing;
 *   <li>(c) the driver, every table with a primary key imported into ScalarDB and set up: the cost
 *       of the rewrite. TPC-C's {@code history} has none and stays plain.
 *   <li>(d) the vendor URL on the tables of (c), imported and set up, bypassing ScalarDB's
 *       protocol: the cost of ScalarDB's layout alone, which in the inline layout is the row's
 *       metadata and before-image columns, so that (c) against (d) is the driver's protocol by
 *       itself (todo 329). In the decoupled layout the data tables are the vendor's own, and (d) is
 *       near (a). On PostgreSQL an INSERT without a column list fills the columns it leaves out
 *       with NULL, so YCSB's runs there too.
 * </ul>
 *
 * <p>No ScalarDB transactions run. BenchBase loads through the vendor URL in every run, from its
 * own DDL with each {@code decimal} a double, which ScalarDB 3.19 cannot import otherwise. TPC-H's
 * Q15 creates a view, which the driver refuses over ScalarDB tables, so its weight is 0 in every
 * run.
 */
@EnabledIfSystemProperty(named = "nativejdbc.benchbase", matches = ".+")
@SuppressFBWarnings(
    value = "COMMAND_INJECTION",
    justification = "BenchBase run with the benchmark's own settings")
class BenchBaseBenchmark {

  private static final Path HOME = Path.of(System.getProperty("nativejdbc.benchbase", "."));

  // -Dnativejdbc.benchbase.workloads=ycsb,tpcc,tpch
  private static final List<String> WORKLOADS =
      list(System.getProperty("nativejdbc.benchbase.workloads", "ycsb,tpcc,tpch"));

  // -Dnativejdbc.benchbase.runs=a,b,c
  private static final List<String> RUNS =
      list(System.getProperty("nativejdbc.benchbase.runs", "a,b,c"));

  // -Dnativejdbc.benchbase.isolation=REPEATABLE_READ: the driver's level, SNAPSHOT by default;
  // run (a) takes the vendor's matching one (vendorIsolation)
  private static final String ISOLATION =
      System.getProperty("nativejdbc.benchbase.isolation", "REPEATABLE_READ");

  private static final int TERMINALS = Integer.getInteger("nativejdbc.benchbase.terminals", 4);

  // seconds per run; TPC-H runs each query once instead (BenchBase's serial mode)
  private static final int TIME = Integer.getInteger("nativejdbc.benchbase.time", 60);

  private static final int REPS = Integer.getInteger("nativejdbc.benchbase.reps", 1);

  // -Dnativejdbc.benchbase.scale.tpcc=1: a workload's scale factor, BenchBase's sample otherwise
  private static String scale(String workload) {
    return System.getProperty("nativejdbc.benchbase.scale." + workload, "");
  }

  // -Dnativejdbc.benchbase.weights.ycsb=50,0,15,15,0,20: a workload's transaction weights, in
  // the order of its sample config, BenchBase's sample otherwise
  private static String weights(String workload) {
    return System.getProperty("nativejdbc.benchbase.weights." + workload, "");
  }

  // -Dnativejdbc.benchbase.layout=inline: ScalarDB's default layout, decoupled otherwise
  private static final boolean DECOUPLED =
      !System.getProperty("nativejdbc.benchbase.layout", "decoupled").equals("inline");

  private static final String TIER = System.getProperty("nativejdbc.bench.tier", "local");

  // -Dnativejdbc.benchbase.serverStats=true: per run, PostgreSQL's account of it (pg_stat_database,
  // pg_stat_wal, pg_stat_statements if loaded, the server's CPU and packets where it can read
  // them),
  // read over JDBC, so that it works from a client of its own
  private static final boolean SERVER_STATS =
      Boolean.getBoolean("nativejdbc.benchbase.serverStats");

  private static final Path OUT = Path.of("build", "bench");

  private static final String SCHEMA = "bb";

  // the vendor drivers on this JVM's classpath replace BenchBase's, so that (a) and the driver's
  // runs use the version the integration tests run
  private static final Pattern VENDOR_JAR =
      Pattern.compile("(postgresql|mysql-connector-j|mariadb-java-client|mssql-jdbc|ojdbc\\d+)-.*");

  private static List<String> list(String s) {
    return Arrays.stream(s.split(",", -1)).map(String::trim).filter(x -> !x.isEmpty()).toList();
  }

  static Stream<Engines> engines() {
    return Engines.selected().stream().filter(e -> e != Engines.MYSQL_LOWER_CASE);
  }

  @ParameterizedTest
  @MethodSource("engines")
  void benchBase(Engines e) throws Exception {
    assumeTrue(e.engine() == Engine.POSTGRESQL, "PostgreSQL only so far");
    assertThat(HOME.resolve("benchbase.jar")).exists();
    Path dir = OUT.resolve("benchbase-" + e.name().toLowerCase(Locale.ROOT));
    Files.createDirectories(dir);
    StringBuilder report = new StringBuilder();
    try (Connection c = e.open()) {
      report
          .append("# BenchBase, ")
          .append(e)
          .append("\n\nEnvironment: ")
          .append(TIER)
          .append(", ")
          .append(c.getMetaData().getDatabaseProductVersion().replace('\n', ' '))
          .append(". Driver isolation ")
          .append(ISOLATION)
          .append(", vendor ")
          .append(vendorIsolation(e.engine()))
          .append("; ")
          .append(TERMINALS)
          .append(" terminals, ")
          .append(TIME)
          .append(" s a run, ")
          .append(REPS)
          .append(" repetition(s), layout ")
          .append(DECOUPLED ? "decoupled" : "inline")
          .append(".\n");
    }
    for (String workload : WORKLOADS) {
      Map<String, List<Result>> results = new LinkedHashMap<>();
      for (int rep = 1; rep <= REPS; rep++) {
        for (String run : RUNS) {
          Path runDir = dir.resolve(workload + "-" + run + "-" + rep);
          results.computeIfAbsent(run, r -> new ArrayList<>()).add(run(e, workload, run, runDir));
        }
      }
      report.append(table(workload, results));
      Files.writeString(dir.resolveSibling(dir.getFileName() + ".md"), report);
    }
    System.out.println(report);
  }

  /** One run: fresh tables, loaded through the vendor URL, set up for (c), then executed. */
  private static Result run(Engines e, String workload, String run, Path dir) throws Exception {
    // BenchBase names its files by the time; a directory left from a run before holds others
    if (Files.exists(dir)) {
      try (Stream<Path> old = Files.walk(dir)) {
        for (Path p : old.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
    Files.createDirectories(dir);
    try (Connection c = e.open()) {
      e.dropSchemas(c, e.storedSchema(SCHEMA), "scalardb", "coordinator");
    }
    String ns = e.storedSchema(SCHEMA);
    try (DistributedTransactionAdmin admin = e.admin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(ns, true);
    }
    benchBase(e, workload, "a", dir, "load", "--create=true", "--load=true");
    List<String> imported = new ArrayList<>();
    List<String> plain = new ArrayList<>();
    if (run.equals("c") || run.equals("d")) {
      try (Connection c = e.open()) {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getTables(null, ns, "%", new String[] {"TABLE"})) {
          while (rs.next()) {
            String t = rs.getString("TABLE_NAME");
            try (ResultSet pk = md.getPrimaryKeys(null, ns, t)) {
              (pk.next() ? imported : plain).add(t);
            }
          }
        }
      }
      try (DistributedTransactionAdmin admin = e.admin()) {
        for (String t : imported) {
          admin.importTable(
              ns,
              t,
              DECOUPLED ? Map.of("transaction-metadata-decoupling", "true") : Map.of(),
              Map.of());
        }
      }
      Catalog catalog = new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open);
      try (Connection c = e.open()) {
        assertThat(
                Setup.run(
                    catalog, c, t -> t.namespace().equals(ns), Duration.ofMinutes(10), List.of()))
            .allMatch(Setup.Result::succeeded, "succeeded");
      }
    }
    try (Connection c = e.open();
        Statement st = c.createStatement()) {
      // the statistics of the loaded tables, and in (c) of the metadata tables the setup filled
      st.execute("ANALYZE");
      if (SERVER_STATS) {
        Server.reset(st);
      }
      // -Dnativejdbc.benchbase.beforeExecute=SELECT pg_stat_statements_reset(): a statement run
      // last
      String before = System.getProperty("nativejdbc.benchbase.beforeExecute", "");
      if (!before.isEmpty()) {
        st.execute(before);
      }
    }
    long[] roundTrip = SERVER_STATS ? Server.roundTrip(e) : null;
    Server.@Nullable Snapshot start = SERVER_STATS ? Server.snapshot(e) : null;
    Sampler sampler = SERVER_STATS ? new Sampler(e) : null;
    Path histograms = dir.resolve("histograms.json");
    try {
      benchBase(
          e,
          workload,
          run,
          dir,
          "execute",
          "--execute=true",
          "-jh",
          histograms.toAbsolutePath().toString());
    } finally {
      if (sampler != null) {
        sampler.close();
      }
    }
    Server server =
        start == null || roundTrip == null
            ? null
            : Server.since(e, start, sampler, roundTrip, dir.resolve("server.txt"));
    return Result.read(run, dir, histograms, imported, plain, server);
  }

  /**
   * What PostgreSQL counted over a run's execute phase: its commits, the time its sessions were
   * active and idle in a transaction (pg_stat_database), the WAL written, and, where the server
   * lets {@code pg_read_file} read them (a superuser in a container, not RDS), the container's CPU
   * and the packets the host sent; -1 for what it could not read.
   */
  record Server(
      long commits,
      double activeMs,
      double idleInTxMs,
      long walBytes,
      long cpuMicros,
      long packets,
      double backendsCpuMs,
      double othersCpuMs,
      long roundTripMedianMicros,
      long roundTripP99Micros) {

    /**
     * The network's round trip from this JVM, the client's: a prepared {@code SELECT 1} 2,000 times
     * after 200 to warm it, its median and 99th percentile in microseconds.
     */
    static long[] roundTrip(Engines e) throws SQLException {
      long[] times = new long[2000];
      try (Connection c = e.open();
          PreparedStatement ps = c.prepareStatement("SELECT 1")) {
        for (int i = -200; i < times.length; i++) {
          long t = System.nanoTime();
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
          }
          if (i >= 0) {
            times[i] = System.nanoTime() - t;
          }
        }
      }
      Arrays.sort(times);
      return new long[] {times[times.length / 2] / 1000, times[times.length * 99 / 100] / 1000};
    }

    /** The counters at one moment. */
    record Snapshot(
        long commits,
        double activeMs,
        double idleInTxMs,
        long walBytes,
        long cpuMicros,
        long packets) {}

    /** Before a run: pg_stat_statements emptied, where it is loaded. */
    static void reset(Statement st) {
      try {
        st.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        st.execute("SELECT pg_stat_statements_reset()");
      } catch (SQLException ex) {
        // not in shared_preload_libraries: the run goes on without its statements
      }
    }

    static Snapshot snapshot(Engines e) throws SQLException {
      try (Connection c = e.open();
          Statement st = c.createStatement()) {
        long commits;
        double active;
        double idle;
        long wal;
        try (ResultSet rs =
            st.executeQuery(
                "SELECT d.xact_commit, d.active_time, d.idle_in_transaction_time, w.wal_bytes"
                    + " FROM pg_stat_database d, pg_stat_wal w"
                    + " WHERE d.datname = current_database()")) {
          assertThat(rs.next()).isTrue();
          commits = rs.getLong(1);
          active = rs.getDouble(2);
          idle = rs.getDouble(3);
          wal = rs.getLong(4);
        }
        long cpu = -1;
        String stat = file(c, "/sys/fs/cgroup/cpu.stat");
        if (stat != null) {
          Matcher m = Pattern.compile("(?m)^usage_usec (\\d+)").matcher(stat);
          cpu = m.find() ? Long.parseLong(m.group(1)) : -1;
        }
        long packets = -1;
        String net = file(c, "/proc/net/dev");
        if (net != null) {
          packets = 0;
          for (String line : net.split("\n", -1)) {
            int colon = line.indexOf(':');
            if (colon < 0 || line.substring(0, colon).trim().equals("lo")) {
              continue;
            }
            // receive: bytes packets errs drop fifo frame compressed multicast; then transmit
            String[] f = line.substring(colon + 1).trim().split("\\s+", -1);
            packets += Long.parseLong(f[9]);
          }
        }
        return new Snapshot(commits, active, idle, wal, cpu, packets);
      }
    }

    /**
     * The counters since {@code start}, with pg_stat_statements' top statements written to {@code
     * to}.
     */
    static Server since(
        Engines e, Snapshot start, @Nullable Sampler sampler, long[] roundTrip, Path to)
        throws Exception {
      // the benchmark's sessions report their counters as they end, just before
      Thread.sleep(1000);
      Snapshot end = snapshot(e);
      StringBuilder out = new StringBuilder("calls\texec ms\tplan ms\tplans\trows\tquery\n");
      try (Connection c = e.open();
          Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT calls, round(total_exec_time::numeric, 1), round(total_plan_time::numeric,"
                      + " 1), plans, rows, left(regexp_replace(query, '\\s+', ' ', 'g'), 400)"
                      + " FROM pg_stat_statements"
                      + " WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())"
                      + " ORDER BY total_exec_time DESC LIMIT 80")) {
        while (rs.next()) {
          for (int i = 1; i <= 6; i++) {
            out.append(rs.getString(i)).append(i < 6 ? "\t" : "\n");
          }
        }
      } catch (SQLException ex) {
        out.append("pg_stat_statements unavailable: ").append(ex.getMessage()).append('\n');
      }
      double backends = -1;
      double others = -1;
      if (sampler != null) {
        out.append('\n').append(sampler.report());
        backends = sampler.cpuMs(true);
        others = sampler.cpuMs(false);
      }
      Files.writeString(to, out);
      return new Server(
          end.commits() - start.commits(),
          end.activeMs() - start.activeMs(),
          end.idleInTxMs() - start.idleInTxMs(),
          end.walBytes() - start.walBytes(),
          end.cpuMicros() < 0 || start.cpuMicros() < 0 ? -1 : end.cpuMicros() - start.cpuMicros(),
          end.packets() < 0 || start.packets() < 0 ? -1 : end.packets() - start.packets(),
          backends,
          others,
          roundTrip[0],
          roundTrip[1]);
    }

    private static @Nullable String file(Connection c, String path) {
      try (PreparedStatement ps = c.prepareStatement("SELECT pg_read_file(?)")) {
        ps.setString(1, path);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      } catch (SQLException ex) {
        return null; // no superuser, or no such file: RDS
      }
    }

    double perCommit(double v) {
      return commits == 0 ? Double.NaN : v / commits;
    }
  }

  /**
   * Over a run's execute phase, every 200 ms on a connection of its own: each server process's type
   * and CPU ({@code /proc/<pid>/stat}, kept at its last reading, so that a session that ends still
   * counts), and the client backends' state and wait. Where {@code /proc} cannot be read (RDS), the
   * states only. CPU in ticks of 10 ms, Linux's USER_HZ.
   */
  static final class Sampler implements AutoCloseable {

    private static final String WITH_CPU =
        "SELECT pid, backend_type, state, wait_event_type, wait_event,"
            + " pg_read_file('/proc/' || pid || '/stat', 0, 400, true)"
            + " FROM pg_stat_activity WHERE pid <> pg_backend_pid()";

    private static final String WITHOUT_CPU =
        "SELECT pid, backend_type, state, wait_event_type, wait_event, NULL"
            + " FROM pg_stat_activity WHERE pid <> pg_backend_pid()";

    private final Thread thread;
    private volatile boolean stop;
    private final Map<Integer, String> types = new TreeMap<>();
    // per process: its first and last [user, system] ticks
    private final Map<Integer, long[]> first = new TreeMap<>();
    private final Map<Integer, long[]> last = new TreeMap<>();
    // per client backend: how often it was seen in each "state / wait"
    private final Map<Integer, Map<String, Integer>> seen = new TreeMap<>();
    private int samples;
    private @Nullable String failure;

    Sampler(Engines e) {
      thread = new Thread(() -> run(e), "server-sampler");
      thread.setDaemon(true);
      thread.start();
    }

    private void run(Engines e) {
      try (Connection c = e.open();
          Statement st = c.createStatement()) {
        String query = WITH_CPU;
        while (!stop) {
          try (ResultSet rs = st.executeQuery(query)) {
            synchronized (this) {
              samples++;
              while (rs.next()) {
                int pid = rs.getInt(1);
                String type = String.valueOf(rs.getString(2));
                types.put(pid, type);
                String stat = rs.getString(6);
                if (stat != null && stat.indexOf(')') > 0) {
                  String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ", -1);
                  long[] ticks = {Long.parseLong(f[11]), Long.parseLong(f[12])};
                  first.putIfAbsent(pid, ticks);
                  last.put(pid, ticks);
                }
                if (type.equals("client backend")) {
                  String state =
                      rs.getString(3)
                          + (rs.getString(4) == null
                              ? ""
                              : " / " + rs.getString(4) + ":" + rs.getString(5));
                  seen.computeIfAbsent(pid, k -> new TreeMap<>()).merge(state, 1, Integer::sum);
                }
              }
            }
          } catch (SQLException ex) {
            if (query.equals(WITHOUT_CPU)) {
              throw ex;
            }
            query = WITHOUT_CPU; // no superuser: pg_read_file refused
          }
          Thread.sleep(200);
        }
      } catch (SQLException | InterruptedException | RuntimeException ex) {
        synchronized (this) {
          failure = String.valueOf(ex);
        }
      }
    }

    @Override
    public void close() throws InterruptedException {
      stop = true;
      thread.join(10_000);
    }

    /** The CPU of the client backends, or of every other process, over the run; -1 if unread. */
    synchronized double cpuMs(boolean backends) {
      if (last.isEmpty()) {
        return -1;
      }
      long ticks = 0;
      for (Map.Entry<Integer, long[]> p : last.entrySet()) {
        if ("client backend".equals(types.get(p.getKey())) == backends) {
          long[] f = Objects.requireNonNull(first.get(p.getKey()));
          ticks += p.getValue()[0] - f[0] + p.getValue()[1] - f[1];
        }
      }
      return ticks * 10.0;
    }

    /** CPU per process type, and the busy client backends' states, as text for server.txt. */
    synchronized String report() {
      StringBuilder out = new StringBuilder("samples\t").append(samples).append('\n');
      if (failure != null) {
        out.append("sampler failed: ").append(failure).append('\n');
      }
      Map<String, long[]> byType = new TreeMap<>();
      for (Map.Entry<Integer, long[]> p : last.entrySet()) {
        long[] f = Objects.requireNonNull(first.get(p.getKey()));
        long[] t = byType.computeIfAbsent(types.get(p.getKey()), k -> new long[3]);
        t[0]++;
        t[1] += p.getValue()[0] - f[0];
        t[2] += p.getValue()[1] - f[1];
      }
      out.append("\nprocess type\tprocesses\tuser ms\tsystem ms\n");
      byType.forEach(
          (type, t) ->
              out.append(type)
                  .append('\t')
                  .append(t[0])
                  .append('\t')
                  .append(t[1] * 10)
                  .append('\t')
                  .append(t[2] * 10)
                  .append('\n'));
      // the benchmark's sessions: the client backends not idle in at least 10% of the samples
      Map<String, Integer> states = new TreeMap<>();
      int total = 0;
      for (Map<String, Integer> m : seen.values()) {
        int all = m.values().stream().mapToInt(Integer::intValue).sum();
        if (all - m.getOrDefault("idle / Client:ClientRead", 0) < all / 10) {
          continue;
        }
        for (Map.Entry<String, Integer> x : m.entrySet()) {
          states.merge(x.getKey(), x.getValue(), Integer::sum);
          total += x.getValue();
        }
      }
      out.append("\nbusy client backends' state / wait\tsamples\tshare\n");
      int sum = total;
      states.entrySet().stream()
          .sorted((x, y) -> y.getValue() - x.getValue())
          .forEach(
              x ->
                  out.append(x.getKey())
                      .append('\t')
                      .append(x.getValue())
                      .append('\t')
                      .append(String.format(Locale.ROOT, "%.1f%%", 100.0 * x.getValue() / sum))
                      .append('\n'));
      return out.toString();
    }
  }

  /** Runs BenchBase in its own JVM, with the driver's classes and this JVM's vendor driver. */
  private static void benchBase(
      Engines e, String workload, String run, Path dir, String phase, String... args)
      throws Exception {
    Path config = dir.resolve(phase + ".xml");
    writeConfig(e, workload, run, config);
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    // -Dnativejdbc.benchbase.jvmArgs=-XX:StartFlightRecording=...: for the execute phase only;
    // {dir} is the run's directory, as for a recording per run: filename={dir}/execute.jfr
    if (phase.equals("execute")) {
      for (String arg : System.getProperty("nativejdbc.benchbase.jvmArgs", "").split(" ", -1)) {
        if (!arg.isEmpty()) {
          command.add(arg.replace("{dir}", dir.toAbsolutePath().toString()));
        }
      }
    }
    command.add("-cp");
    command.add(classpath());
    command.add("com.oltpbenchmark.DBWorkload");
    command.addAll(List.of("-b", workload, "-c", config.toAbsolutePath().toString()));
    command.addAll(List.of(args));
    command.addAll(List.of("-d", dir.resolve(phase).toAbsolutePath().toString()));
    Path log = dir.resolve(phase + ".log");
    Files.writeString(dir.resolve(phase + ".cmd"), String.join("\n", command) + "\n");
    Process p =
        new ProcessBuilder(command)
            .directory(HOME.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    if (!p.waitFor(3, TimeUnit.HOURS)) {
      p.destroyForcibly();
      throw new IllegalStateException("BenchBase did not finish: " + log);
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException(
          "BenchBase failed (" + p.exitValue() + "): " + log.toAbsolutePath() + "\n" + tail(log));
    }
  }

  private static String tail(Path log) throws IOException {
    List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
    return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
  }

  private static String classpath() throws IOException {
    List<String> out = new ArrayList<>();
    out.add(HOME.resolve("benchbase.jar").toString());
    try (Stream<Path> lib = Files.list(HOME.resolve("lib"))) {
      lib.filter(j -> !VENDOR_JAR.matcher(String.valueOf(j.getFileName())).matches())
          .sorted()
          .forEach(j -> out.add(j.toString()));
    }
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator, -1)) {
      String name = String.valueOf(Path.of(entry).getFileName());
      // the driver's main output, a jar or its classes and resources, not the test fixtures
      boolean driver =
          (name.startsWith("scalardb-native-jdbc-") && !name.contains("test-fixtures"))
              || entry.endsWith("/driver/build/classes/java/main")
              || entry.endsWith("/driver/build/resources/main");
      if (driver || name.startsWith("jsqlparser-") || VENDOR_JAR.matcher(name).matches()) {
        out.add(entry);
      }
    }
    return String.join(File.pathSeparator, out);
  }

  /** BenchBase's sample configuration for the workload, with this run's connection and load. */
  private static void writeConfig(Engines e, String workload, String run, Path to)
      throws Exception {
    Path sample = HOME.resolve("config").resolve(configDir(e.engine()));
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document d =
        f.newDocumentBuilder().parse(sample.resolve("sample_" + workload + "_config.xml").toFile());
    boolean vendor = run.equals("a") || run.equals("d");
    String url = e.url() + (e.url().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    set(
        d,
        "driver",
        vendor ? vendorDriverClass(e.engine()) : "com.scalar.db.nativejdbc.NativeJdbcDriver");
    set(d, "url", vendor ? url : "jdbc:scalardb-native:" + url.substring("jdbc:".length()));
    set(d, "username", e.username());
    set(d, "password", e.password());
    set(d, "isolation", "TRANSACTION_" + (vendor ? vendorIsolation(e.engine()) : ISOLATION));
    set(d, "terminals", String.valueOf(TERMINALS));
    if (!scale(workload).isEmpty()) {
      set(d, "scalefactor", scale(workload));
    }
    Element work = (Element) d.getElementsByTagName("work").item(0);
    boolean serial = text(work, "serial").equals("true");
    if (!serial) {
      setIn(work, "time", String.valueOf(TIME));
      setIn(work, "rate", "unlimited");
    }
    Path ddl = to.resolveSibling("ddl.sql");
    if (writeDdl(e.engine(), workload, ddl)) {
      set(d, "ddlpath", ddl.toAbsolutePath().toString());
    }
    if (!weights(workload).isEmpty()) {
      setIn(work, "weights", weights(workload));
    }
    if (workload.equals("tpch")) {
      // Q15 creates and drops a view, which the driver refuses over ScalarDB tables
      List<String> w = new ArrayList<>(Arrays.asList(text(work, "weights").split(",", -1)));
      w.set(14, "0");
      setIn(work, "weights", String.join(",", w));
    }
    TransformerFactory tf = TransformerFactory.newInstance();
    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    var t = tf.newTransformer();
    t.setOutputProperty(OutputKeys.INDENT, "no");
    t.transform(new DOMSource(d), new StreamResult(to.toFile()));
  }

  private static final Pattern DECIMAL =
      Pattern.compile("\\bdecimal\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)", Pattern.CASE_INSENSITIVE);

  /**
   * BenchBase's DDL for the workload with its decimals as doubles, which ScalarDB 3.19 cannot
   * import otherwise; every run creates its tables from it, so that (a) and (b) read the same
   * types.
   *
   * @return whether the DDL differs from BenchBase's, and was written
   */
  private static boolean writeDdl(Engine engine, String workload, Path to) throws IOException {
    String name = "benchmarks/" + workload + "/ddl-" + configDir(engine) + ".sql";
    String ddl;
    try (JarFile jar = new JarFile(HOME.resolve("benchbase.jar").toFile())) {
      JarEntry entry = Objects.requireNonNull(jar.getJarEntry(name), name);
      try (InputStream in = jar.getInputStream(entry)) {
        ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    String changed = DECIMAL.matcher(ddl).replaceAll("double precision");
    if (changed.equals(ddl)) {
      return false;
    }
    Files.writeString(to, changed);
    return true;
  }

  private static void set(Document d, String tag, String value) {
    NodeList n = d.getDocumentElement().getElementsByTagName(tag);
    if (n.getLength() == 0) {
      Element el = d.createElement(tag);
      d.getDocumentElement().appendChild(el);
      el.setTextContent(value);
    } else {
      n.item(0).setTextContent(value);
    }
  }

  private static void setIn(Element parent, String tag, String value) {
    NodeList n = parent.getElementsByTagName(tag);
    if (n.getLength() == 0) {
      Element el = parent.getOwnerDocument().createElement(tag);
      parent.appendChild(el);
      el.setTextContent(value);
    } else {
      n.item(0).setTextContent(value);
    }
  }

  private static String text(Element parent, String tag) {
    NodeList n = parent.getElementsByTagName(tag);
    return n.getLength() == 0 ? "" : n.item(0).getTextContent().trim();
  }

  private static String configDir(Engine engine) {
    return switch (engine) {
      case POSTGRESQL -> "postgres";
      case MYSQL -> "mysql";
      case MARIADB -> "mariadb";
      case ORACLE -> "oracle";
      default -> "sqlserver";
    };
  }

  private static String vendorDriverClass(Engine engine) {
    return switch (engine) {
      case POSTGRESQL -> "org.postgresql.Driver";
      case MYSQL -> "com.mysql.cj.jdbc.Driver";
      case MARIADB -> "org.mariadb.jdbc.Driver";
      case ORACLE -> "oracle.jdbc.OracleDriver";
      default -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    };
  }

  /**
   * The vendor's level that matches the driver's (T6, "Isolation"): the driver's SNAPSHOT is
   * Oracle's SERIALIZABLE and SQL Server's SNAPSHOT; elsewhere the same constant.
   */
  private static String vendorIsolation(Engine engine) {
    if (ISOLATION.equals("REPEATABLE_READ")) {
      if (engine == Engine.ORACLE) {
        return "SERIALIZABLE";
      }
      if (engine == Engine.SQLSERVER) {
        return "SNAPSHOT";
      }
    }
    return ISOLATION;
  }

  /** One run's numbers, from BenchBase's summary, raw samples and histograms. */
  record Result(
      String run,
      double throughput,
      double goodput,
      Map<String, Long> latencyMicros,
      Map<String, Map<String, Long>> counts,
      Map<String, long[]> latencies,
      List<String> imported,
      List<String> plain,
      @Nullable Server server) {

    private static final Pattern NUMBER = Pattern.compile("\"([^\"]+)\": *([0-9.E+-]+)");

    private static final Pattern SECTION =
        Pattern.compile(
            "\"(completed|aborted|rejected|unexpected)\":\\{\"HISTOGRAM\":\\{([^}]*)\\}");

    private static final Pattern ENTRY = Pattern.compile("\"[^\"]*\\.(\\w+)/\\d+\":(\\d+)");

    static Result read(
        String run,
        Path dir,
        Path histograms,
        List<String> imported,
        List<String> plain,
        @Nullable Server server)
        throws IOException {
      Path out = dir.resolve("execute");
      Path summary = only(out, ".summary.json");
      Map<String, Double> numbers = new TreeMap<>();
      Matcher m = NUMBER.matcher(Files.readString(summary));
      while (m.find()) {
        numbers.put(m.group(1), Double.parseDouble(m.group(2)));
      }
      Map<String, Long> latency = new LinkedHashMap<>();
      for (String p : List.of("Median", "95th Percentile", "99th Percentile")) {
        latency.put(p, number(numbers, p + " Latency (microseconds)").longValue());
      }
      Map<String, Map<String, Long>> counts = new TreeMap<>();
      Matcher s = SECTION.matcher(Files.readString(histograms));
      while (s.find()) {
        Matcher entry = ENTRY.matcher(s.group(2));
        while (entry.find()) {
          counts
              .computeIfAbsent(entry.group(1), k -> new TreeMap<>())
              .put(s.group(1), Long.parseLong(entry.group(2)));
        }
      }
      // Transaction Type Index,Transaction Name,Start Time,Latency (microseconds),Worker,Phase
      Map<String, List<Long>> samples = new TreeMap<>();
      try (Stream<String> lines = Files.lines(only(out, ".raw.csv"))) {
        lines
            .skip(1)
            .forEach(
                l -> {
                  String[] f = l.split(",", -1);
                  samples.computeIfAbsent(f[1], k -> new ArrayList<>()).add(Long.parseLong(f[3]));
                });
      }
      Map<String, long[]> latencies = new TreeMap<>();
      samples.forEach(
          (k, v) -> latencies.put(k, v.stream().mapToLong(Long::longValue).sorted().toArray()));
      return new Result(
          run,
          number(numbers, "Throughput (requests/second)"),
          number(numbers, "Goodput (requests/second)"),
          latency,
          counts,
          latencies,
          imported,
          plain,
          server);
    }

    private static Double number(Map<String, Double> numbers, String key) {
      return Objects.requireNonNull(numbers.get(key), key);
    }

    private static Path only(Path dir, String suffix) throws IOException {
      try (Stream<Path> files = Files.list(dir)) {
        List<Path> found =
            files.filter(p -> String.valueOf(p.getFileName()).endsWith(suffix)).toList();
        assertThat(found).as(dir + "/*" + suffix).hasSize(1);
        return found.get(0);
      }
    }

    long count(String txn, String outcome) {
      return counts.getOrDefault(txn, Map.of()).getOrDefault(outcome, 0L);
    }
  }

  private static long percentile(long[] sorted, double p) {
    if (sorted.length == 0) {
      return 0;
    }
    return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
  }

  private static double median(List<Double> v) {
    List<Double> s = v.stream().sorted().toList();
    return s.size() % 2 == 1
        ? s.get(s.size() / 2)
        : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2;
  }

  /** The workload's section: the runs overall, then per transaction type, medians of the reps. */
  private static String table(String workload, Map<String, List<Result>> results) {
    StringBuilder out = new StringBuilder("\n## ").append(workload).append("\n\n");
    List<Result> c = results.get("c") != null ? results.get("c") : results.get("d");
    if (c != null) {
      out.append("Runs (c) and (d): imported ")
          .append(String.join(", ", c.get(0).imported()))
          .append("; plain ")
          .append(c.get(0).plain().isEmpty() ? "none" : String.join(", ", c.get(0).plain()))
          .append(".\n\n");
    }
    List<Result> a = results.get("a");
    double base = a == null ? Double.NaN : median(a.stream().map(Result::throughput).toList());
    out.append(
        "| run | throughput/s | vs (a) | goodput/s | p50 µs | p95 µs | p99 µs |\n"
            + "|---|---|---|---|---|---|---|\n");
    results.forEach(
        (run, rs) -> {
          double tp = median(rs.stream().map(Result::throughput).toList());
          out.append(
              String.format(
                  Locale.ROOT,
                  "| (%s) | %.1f | %s | %.1f | %d | %d | %d |%n",
                  run,
                  tp,
                  Double.isNaN(base) ? "" : String.format(Locale.ROOT, "%.2f", tp / base),
                  median(rs.stream().map(Result::goodput).toList()),
                  Math.round(median(med(rs, "Median"))),
                  Math.round(median(med(rs, "95th Percentile"))),
                  Math.round(median(med(rs, "99th Percentile")))));
        });
    if (results.values().stream().flatMap(List::stream).anyMatch(r -> r.server() != null)) {
      out.append(
          "\n| run | commits | active ms/commit | idle in transaction ms/commit | WAL KB/commit |"
              + " server CPU ms/commit | backends' CPU ms/commit | other processes' CPU ms/commit |"
              + " server packets/commit | round trip µs, median / p99 |\n"
              + "|---|---|---|---|---|---|---|---|---|---|\n");
      results.forEach(
          (run, rs) -> {
            List<Server> ss = rs.stream().map(Result::server).filter(Objects::nonNull).toList();
            if (ss.isEmpty()) {
              return;
            }
            out.append(
                String.format(
                    Locale.ROOT,
                    "| (%s) | %.0f | %.3f | %.3f | %.1f | %s | %s | %s | %s | %.0f / %.0f |%n",
                    run,
                    median(ss.stream().map(x -> (double) x.commits()).toList()),
                    median(ss.stream().map(x -> x.perCommit(x.activeMs())).toList()),
                    median(ss.stream().map(x -> x.perCommit(x.idleInTxMs())).toList()),
                    median(
                        ss.stream().map(x -> x.perCommit((double) x.walBytes()) / 1024).toList()),
                    ss.stream().anyMatch(x -> x.cpuMicros() < 0)
                        ? "n/a"
                        : String.format(
                            Locale.ROOT,
                            "%.3f",
                            median(
                                ss.stream()
                                    .map(x -> x.perCommit((double) x.cpuMicros()) / 1000)
                                    .toList())),
                    ss.stream().anyMatch(x -> x.backendsCpuMs() < 0)
                        ? "n/a"
                        : String.format(
                            Locale.ROOT,
                            "%.3f",
                            median(ss.stream().map(x -> x.perCommit(x.backendsCpuMs())).toList())),
                    ss.stream().anyMatch(x -> x.othersCpuMs() < 0)
                        ? "n/a"
                        : String.format(
                            Locale.ROOT,
                            "%.3f",
                            median(ss.stream().map(x -> x.perCommit(x.othersCpuMs())).toList())),
                    ss.stream().anyMatch(x -> x.packets() < 0)
                        ? "n/a"
                        : String.format(
                            Locale.ROOT,
                            "%.1f",
                            median(
                                ss.stream().map(x -> x.perCommit((double) x.packets())).toList())),
                    median(ss.stream().map(x -> (double) x.roundTripMedianMicros()).toList()),
                    median(ss.stream().map(x -> (double) x.roundTripP99Micros()).toList())));
          });
    }
    List<String> txns =
        results.values().stream()
            .flatMap(List::stream)
            .flatMap(
                r -> Stream.concat(r.latencies().keySet().stream(), r.counts().keySet().stream()))
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    out.append(
        "\n| transaction | run | completed | aborted | rejected | unexpected | p50 µs | p95 µs |"
            + " p95 vs (a) |\n|---|---|---|---|---|---|---|---|---|\n");
    for (String txn : txns) {
      double baseP95 = a == null ? Double.NaN : medianP95(a, txn);
      results.forEach(
          (run, rs) -> {
            double p95 = medianP95(rs, txn);
            out.append(
                String.format(
                    Locale.ROOT,
                    "| %s | (%s) | %d | %d | %d | %d | %d | %.0f | %s |%n",
                    txn,
                    run,
                    rs.stream().mapToLong(r -> r.count(txn, "completed")).sum(),
                    rs.stream().mapToLong(r -> r.count(txn, "aborted")).sum(),
                    rs.stream().mapToLong(r -> r.count(txn, "rejected")).sum(),
                    rs.stream().mapToLong(r -> r.count(txn, "unexpected")).sum(),
                    Math.round(
                        median(
                            rs.stream()
                                .map(
                                    r ->
                                        (double)
                                            percentile(
                                                r.latencies().getOrDefault(txn, new long[0]), 0.5))
                                .toList())),
                    p95,
                    Double.isNaN(baseP95) || baseP95 == 0
                        ? ""
                        : String.format(Locale.ROOT, "%.2f", p95 / baseP95)));
          });
    }
    return out.toString();
  }

  private static List<Double> med(List<Result> rs, String key) {
    return rs.stream()
        .map(r -> (double) Objects.requireNonNull(r.latencyMicros().get(key), key))
        .toList();
  }

  private static double medianP95(List<Result> rs, String txn) {
    return median(
        rs.stream()
            .map(r -> (double) percentile(r.latencies().getOrDefault(txn, new long[0]), 0.95))
            .toList());
  }
}
