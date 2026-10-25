package com.scalar.db.nativejdbc.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.io.DataType;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Setup;
import com.scalar.db.nativejdbc.testing.Engines;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Isolation with Maelstrom and Elle (testing plan T5): Maelstrom's {@code txn-list-append} and
 * {@code txn-rw-register} workloads against two {@link MaelstromNode}s on one ScalarDB table, and
 * Elle checking the history. List-append's reads show each key's whole order of writes; the
 * register's blind writes take other paths, the driver's and ScalarDB's, and tell Elle less.
 *
 * <ul>
 *   <li>{@link Sides#MIXED}: {@code n0} runs its transactions through the driver and {@code n1}
 *       through ScalarDB, ScalarDB with the settings the README gives the level, and the history is
 *       checked against the weaker of the two levels: ScalarDB's SNAPSHOT is read-committed
 *       snapshot isolation, which allows read skew (its documentation), so SNAPSHOT is checked as
 *       Elle's cursor stability, read committed without lost updates;
 *   <li>{@link Sides#DRIVER}: both nodes through the driver, checked against the driver's level.
 * </ul>
 *
 * <p>Two list-append runs must fail, or the workload shows nothing: a plain vendor connection in
 * the driver's place, which runs no protocol, and SERIALIZABLE without ScalarDB's settings; these
 * and the SERIALIZABLE runs last at least {@value #ANOMALY_SECONDS} seconds.
 *
 * <p>Under faults ({@link MaelstromNode}: ScalarDB transactions left after their prepare, the
 * driver's connections dropped in a transaction and in its commit, and closed after an error), the
 * mixed list-append runs must hold the same levels.
 *
 * <p>{@code -Dnativejdbc.isolation=true}; {@code -Dnativejdbc.isolation.seconds} sets each run's
 * length (default 30), {@code -Dnativejdbc.isolation.rate} its requests a second (default 50), and
 * {@code -Dnativejdbc.isolation.faults} the share of transactions that meet a fault in the runs
 * under faults (default 0.1).
 */
@EnabledIfSystemProperty(named = "nativejdbc.isolation", matches = "true")
@SuppressFBWarnings(
    value = "COMMAND_INJECTION",
    justification = "commands written in the test sources")
class MaelstromIntegrationTest {

  private static final String MAELSTROM = "v0.2.4";
  private static final String MAELSTROM_SHA256 =
      "301ec71d6b12af0d765edb413f5cf5aa1046b5609bd4e31376a0b549548e5799";
  private static final Path HOME = Path.of("build", "maelstrom", MAELSTROM);
  private static final String VALID = "Everything looks good!";
  private static final String INVALID = "Analysis invalid!";
  // Elle looks for every cycle, also those the model allows; on a slow machine (GitHub's runners)
  // its search for one may time out, and the verdict is then unknown, with nothing found
  private static final String NOTHING_FOUND =
      "Errors occurred during analysis, but no anomalies found.";
  private static final Pattern ANOMALY_TYPES = Pattern.compile(":anomaly-types \\(([^)]*)\\)");
  private static final Pattern TIMED_OUT = Pattern.compile(":anomaly-spec-type :([\\w-]+)");
  // SQL Server at SERIALIZABLE commits few transactions: 20 seconds showed no anomaly there without
  // ScalarDB's settings, 60 did; the runs with them are as long, for the comparison to hold
  static final int ANOMALY_SECONDS = 60;
  // the least of each dependency between the driver's and ScalarDB's transactions a mixed
  // list-append run must have, of the 100 to 500 a 20-second run has
  private static final long PAIRS = 20;
  private static final Pattern PROCESS = Pattern.compile(":process (\\d+)");

  enum Level {
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED, "read-committed", "read-committed"),
    SNAPSHOT(Connection.TRANSACTION_REPEATABLE_READ, "cursor-stability", "snapshot-isolation"),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE, "serializable", "serializable");

    final int jdbc;
    final String mixed;
    final String driver;

    Level(int jdbc, String mixed, String driver) {
      this.jdbc = jdbc;
      this.mixed = mixed;
      this.driver = driver;
    }
  }

  enum Workload {
    LIST_APPEND("txn-list-append"),
    RW_REGISTER("txn-rw-register");

    final String maelstrom;

    Workload(String maelstrom) {
      this.maelstrom = maelstrom;
    }
  }

  enum Sides {
    MIXED("n0"),
    DRIVER("n0,n1");

    final String drivers;

    Sides(String drivers) {
      this.drivers = drivers;
    }
  }

  static Stream<Arguments> runs() {
    List<Arguments> runs = new ArrayList<>();
    for (Engines e : engines().toList()) {
      for (Level level : Level.values()) {
        // Oracle refuses SERIALIZABLE (design section 5)
        if (level == Level.SERIALIZABLE && e.engine() == Engine.ORACLE) {
          continue;
        }
        for (Workload w : Workload.values()) {
          for (Sides sides : Sides.values()) {
            for (boolean inline : List.of(false, true)) {
              runs.add(Arguments.of(e, w, level, sides, inline));
            }
          }
        }
      }
    }
    return runs.stream();
  }

  static Stream<Engines> engines() {
    return Engines.selected().stream().filter(e -> e != Engines.MYSQL_LOWER_CASE);
  }

  /** Per engine and level, in both layouts. */
  static Stream<Arguments> faultRuns() {
    return runs()
        .map(Arguments::get)
        .filter(a -> a[1] == Workload.LIST_APPEND && a[3] == Sides.MIXED)
        .map(a -> Arguments.of(a[0], a[2], a[4]));
  }

  /** List-append only: the register's histories, without its version orders, showed neither. */
  static Stream<Arguments> controls() {
    return engines().map(e -> Arguments.of(e, Workload.LIST_APPEND));
  }

  @ParameterizedTest(name = "{0} {1} {2} {3} inline={4}")
  @MethodSource("runs")
  void history_holdsTheLevel(Engines e, Workload w, Level level, Sides sides, boolean inline)
      throws Exception {
    String model = sides == Sides.MIXED ? level.mixed : level.driver;
    String output =
        run(
            e,
            w,
            level.name() + "-" + sides + (inline ? "-inline" : ""),
            level,
            scalarDbSettings(e, level),
            sides.drivers,
            inline,
            false,
            model,
            level == Level.SERIALIZABLE ? ANOMALY_SECONDS : 0,
            0,
            0);
    assertHolds(output, model);
    if (w == Workload.LIST_APPEND && sides == Sides.MIXED) {
      assertPairsMeet(e, w, level.name() + "-" + sides + (inline ? "-inline" : ""));
    }
  }

  /** The same levels when transactions on either side are left part way. */
  @ParameterizedTest(name = "{0} {1} inline={2}")
  @MethodSource("faultRuns")
  void history_holdsTheLevelUnderFaults(Engines e, Level level, boolean inline) throws Exception {
    String output =
        run(
            e,
            Workload.LIST_APPEND,
            level.name() + "-FAULTS" + (inline ? "-inline" : ""),
            level,
            scalarDbSettings(e, level),
            Sides.MIXED.drivers,
            inline,
            false,
            level.mixed,
            // a row left undecided blocks its key for recovery's 15 seconds: on SQL Server at
            // SNAPSHOT 20 seconds met each pair of sides about ten times
            ANOMALY_SECONDS,
            Double.parseDouble(System.getProperty("nativejdbc.isolation.faults", "0.1")),
            0);
    assertHolds(output, level.mixed);
    assertPairsMeet(e, Workload.LIST_APPEND, level.name() + "-FAULTS" + (inline ? "-inline" : ""));
  }

  /** The engines whose SERIALIZABLE is the driver's locking re-reads (design 5), each layout. */
  static Stream<Arguments> readOnlyRuns() {
    List<Arguments> runs = new ArrayList<>();
    for (Engines e : engines().toList()) {
      if (e.engine() == Engine.MYSQL
          || e.engine() == Engine.MARIADB
          || e.engine() == Engine.SQLSERVER) {
        for (Sides sides : Sides.values()) {
          for (boolean inline : List.of(false, true)) {
            runs.add(Arguments.of(e, sides, inline));
          }
        }
      }
    }
    return runs.stream();
  }

  /**
   * Todo 324's reader: the driver's transactions that only read run on a read-only connection,
   * which at SERIALIZABLE reads as at SNAPSHOT, without the locking re-reads; every transaction, on
   * either side, at SERIALIZABLE. The history stays serializable.
   */
  @ParameterizedTest(name = "{0} {1} inline={2}", allowZeroInvocations = true)
  @MethodSource("readOnlyRuns")
  void history_serializableWithReadOnlyReaders(Engines e, Sides sides, boolean inline)
      throws Exception {
    String name = "SERIALIZABLE-RO-" + sides + (inline ? "-inline" : "");
    String output =
        run(
            e,
            Workload.LIST_APPEND,
            name,
            Level.SERIALIZABLE,
            scalarDbSettings(e, Level.SERIALIZABLE),
            sides.drivers,
            inline,
            false,
            Level.SERIALIZABLE.mixed,
            ANOMALY_SECONDS,
            0,
            Level.SERIALIZABLE.jdbc);
    assertThat(output).contains(VALID);
    assertReadersRan(e, name);
    if (sides == Sides.MIXED) {
      assertPairsMeet(e, Workload.LIST_APPEND, name);
    }
  }

  /**
   * Its control: the same readers at READ COMMITTED, which returns an in-flight row's before image
   * and takes a snapshot per statement, break it.
   */
  @ParameterizedTest(name = "{0} {1} inline={2}", allowZeroInvocations = true)
  @MethodSource("readOnlyRuns")
  void history_failsWithReadOnlyReadersAtReadCommitted(Engines e, Sides sides, boolean inline)
      throws Exception {
    assumeTrue(sides == Sides.MIXED && !inline, "one run per engine");
    String name = "SERIALIZABLE-RO-READ_COMMITTED";
    String output =
        run(
            e,
            Workload.LIST_APPEND,
            name,
            Level.SERIALIZABLE,
            scalarDbSettings(e, Level.SERIALIZABLE),
            sides.drivers,
            inline,
            false,
            Level.SERIALIZABLE.mixed,
            // 60 seconds showed no read skew on SQL Server, which commits about a third as many,
            // nor
            // on MySQL and MariaDB on GitHub's runners now and then; 240 did
            4 * ANOMALY_SECONDS,
            0,
            Level.READ_COMMITTED.jdbc);
    assertThat(output).contains(INVALID);
  }

  /**
   * The driver's committed transactions that only read and read more than one key, the ones a
   * snapshot that is not serializable could show: at least {@link #PAIRS}.
   */
  private static void assertReadersRan(Engines e, String name) throws IOException {
    Path store =
        Path.of("build", "isolation", e.name(), Workload.LIST_APPEND.name(), name, "store");
    long readers;
    try (var files = Files.walk(store)) {
      Path history =
          files
              .filter(f -> f.toString().endsWith("history.edn"))
              .findFirst()
              .orElseThrow(() -> new IOException("no history.edn under " + store));
      readers =
          Files.readAllLines(history, StandardCharsets.UTF_8).stream()
              .filter(l -> l.contains(":type :ok") && l.contains(":f :txn"))
              .filter(l -> !l.contains(":append"))
              .filter(l -> l.split("\\[:r ", -1).length > 2)
              .filter(
                  l -> {
                    Matcher m = PROCESS.matcher(l);
                    return m.find() && Long.parseLong(m.group(1)) % 2 == 0;
                  })
              .count();
    }
    Files.writeString(store.resolveSibling("readers.txt"), readers + "\n", StandardCharsets.UTF_8);
    assertThat(readers)
        .as("%s %s: the driver's read-only transactions of two keys or more", e, name)
        .isGreaterThanOrEqualTo(PAIRS);
  }

  /**
   * A valid history shows the level held only where the two sides met: each dependency, both ways,
   * between the driver's transactions and ScalarDB's ({@link Dependencies}), counted into the run's
   * {@code dependencies.txt}.
   */
  private static void assertPairsMeet(Engines e, Workload w, String name) throws IOException {
    Path run = Path.of("build", "isolation", e.name(), w.name(), name);
    Dependencies d = Dependencies.of(run.resolve("store"));
    Files.writeString(run.resolve("dependencies.txt"), d + "\n", StandardCharsets.UTF_8);
    for (String kind : List.of("ww", "wr", "rw")) {
      assertThat(d.get(kind, "n0", "n1")).as("%s %s: %s", e, name, d).isGreaterThanOrEqualTo(PAIRS);
      assertThat(d.get(kind, "n1", "n0")).as("%s %s: %s", e, name, d).isGreaterThanOrEqualTo(PAIRS);
    }
  }

  /** A vendor connection runs no protocol: not even read committed holds. */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("controls")
  void history_failsWithoutTheDriver(Engines e, Workload w) throws Exception {
    String output =
        run(
            e,
            w,
            "VENDOR",
            Level.READ_COMMITTED,
            new Properties(),
            Sides.MIXED.drivers,
            true,
            true,
            Level.READ_COMMITTED.mixed,
            ANOMALY_SECONDS,
            0,
            0);
    assertThat(output).contains(INVALID);
  }

  /** SERIALIZABLE between the driver and ScalarDB needs ScalarDB's settings (README). */
  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("controls")
  void history_failsAtSerializableWithoutScalarDbsSettings(Engines e, Workload w) throws Exception {
    assumeTrue(e.engine() != Engine.ORACLE, "Oracle refuses SERIALIZABLE");
    String output =
        run(
            e,
            w,
            "SERIALIZABLE-UNSET",
            Level.SERIALIZABLE,
            new Properties(),
            Sides.MIXED.drivers,
            false,
            false,
            Level.SERIALIZABLE.mixed,
            ANOMALY_SECONDS,
            0,
            0);
    assertThat(output).contains(INVALID);
  }

  /**
   * One Maelstrom run, its output; the run's files in {@code
   * build/isolation/<engine>/<workload>/<name>/}.
   */
  private static String run(
      Engines e,
      Workload w,
      String name,
      Level level,
      Properties scalarDb,
      String drivers,
      boolean inline,
      boolean vendor,
      String model,
      int minimumSeconds,
      double faults,
      int readOnlyLevel)
      throws Exception {
    Path maelstrom = maelstrom();
    String table =
        (w == Workload.LIST_APPEND ? "la_" : "rw_")
            + name.toLowerCase(Locale.ROOT).replace('-', '_');
    String namespace = e.storedSchema("iso");
    String kv = e.storedTable(table);
    String k = e.storedColumn("k");
    String v = e.storedColumn("v");
    try (DistributedTransactionAdmin admin = e.scalarDb(scalarDb).getTransactionAdmin()) {
      admin.createCoordinatorTables(true);
      admin.createNamespace(namespace, true);
      admin.dropTable(namespace, kv, true);
      admin.createTable(
          namespace,
          kv,
          TableMetadata.newBuilder()
              .addColumn(k, DataType.INT)
              .addColumn(v, DataType.TEXT)
              .addPartitionKey(k)
              .build(),
          inline ? Map.of() : Map.of("transaction-metadata-decoupling", "true"));
    }
    try (Connection c = e.open()) {
      assertThat(
              Setup.run(
                  new Catalog(e.engine(), Catalog.DEFAULT_METADATA_SCHEMA, e::open),
                  c,
                  t -> t.namespace().equals(namespace),
                  Duration.ofMinutes(1),
                  List.of()))
          .allMatch(Setup.Result::succeeded, "setup succeeded");
    }

    Path run = Path.of("build", "isolation", e.name(), w.name(), name);
    if (Files.exists(run.resolve("store"))) {
      // a run of an earlier build: Maelstrom adds to it otherwise
      Files.move(run, run.resolveSibling(name + "-" + System.currentTimeMillis()));
    }
    Files.createDirectories(run);
    List<String> node = new ArrayList<>();
    node.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    node.add("-cp");
    node.add(System.getProperty("java.class.path"));
    String prefix = "-Dnativejdbc." + e.name().toLowerCase(Locale.ROOT) + ".";
    node.add("-Dnativejdbc.engines=" + e.name().toLowerCase(Locale.ROOT));
    node.add(prefix + "url=" + e.url());
    node.add(prefix + "username=" + e.username());
    node.add(prefix + "password=" + e.password());
    node.add("-Diso.engine=" + e.name());
    node.add("-Diso.workload=" + w.maelstrom);
    node.add("-Diso.table=iso." + table);
    node.add("-Diso.namespace=" + namespace);
    node.add("-Diso.kv=" + kv);
    node.add("-Diso.k=" + k);
    node.add("-Diso.v=" + v);
    node.add("-Diso.concat=" + concat(e.engine()));
    node.add("-Diso.level=" + level.jdbc);
    node.add("-Diso.drivers=" + drivers);
    node.add("-Diso.vendor=" + vendor);
    node.add("-Diso.faults=" + faults);
    if (readOnlyLevel != 0) {
      node.add("-Diso.readOnlyLevel=" + readOnlyLevel);
    }
    for (String property : scalarDb.stringPropertyNames()) {
      node.add("-Diso.prop." + property + "=" + scalarDb.getProperty(property));
    }
    node.add(MaelstromNode.class.getName());
    Path script = run.resolve("node.sh");
    StringBuilder sh = new StringBuilder("#!/bin/sh\nexec");
    for (String arg : node) {
      sh.append(" '").append(arg.replace("'", "'\\''")).append('\'');
    }
    Files.writeString(script, sh.append('\n').toString(), StandardCharsets.UTF_8);
    assertThat(script.toFile().setExecutable(true)).isTrue();

    String seconds =
        String.valueOf(
            Math.max(
                minimumSeconds,
                Integer.parseInt(System.getProperty("nativejdbc.isolation.seconds", "30"))));
    List<String> test =
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=true",
            "-jar",
            maelstrom.toAbsolutePath().toString(),
            "test",
            "-w",
            w.maelstrom,
            "--bin",
            script.toAbsolutePath().toString(),
            "--node-count",
            "2",
            "--concurrency",
            "4n",
            "--time-limit",
            seconds,
            "--rate",
            System.getProperty("nativejdbc.isolation.rate", "50"),
            "--consistency-models",
            model);
    Path log = run.resolve("maelstrom.log");
    Process p =
        new ProcessBuilder(test)
            .directory(run.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    assertThat(p.waitFor(Long.parseLong(seconds) + 600, TimeUnit.SECONDS))
        .as("Maelstrom ends")
        .isTrue();
    String output = Files.readString(log, StandardCharsets.UTF_8);
    assertThat(output)
        .as("%s %s checked as %s: Elle's verdict, in %s", e, name, model, run.resolve("store"))
        .containsAnyOf(VALID, INVALID, NOTHING_FOUND);
    return output;
  }

  /**
   * Elle's verdict is valid; at READ COMMITTED it may also be unknown when all Elle could not
   * finish is the search for a cycle that level allows (G-single, G2, G-nonadjacent), and it found
   * nothing.
   */
  static void assertHolds(String output, String model) {
    String verdict = output.substring(Math.max(0, output.lastIndexOf(":workload")));
    if (output.contains(VALID)) {
      return;
    }
    assertThat(model).as(verdict).isEqualTo(Level.READ_COMMITTED.mixed);
    assertThat(output).as(verdict).contains(NOTHING_FOUND);
    Matcher types = ANOMALY_TYPES.matcher(verdict);
    assertThat(types.find()).as(verdict).isTrue();
    assertThat(types.group(1).trim()).as(verdict).isEqualTo(":cycle-search-timeout");
    Matcher spec = TIMED_OUT.matcher(verdict);
    assertThat(spec.find()).as(verdict).isTrue();
    do {
      assertThat(spec.group(1)).as(verdict).matches("G-single.*|G2.*|G-nonadjacent.*");
    } while (spec.find());
  }

  /** ScalarDB's settings the README gives the level. */
  static Properties scalarDbSettings(Engines e, Level level) {
    Properties p = new Properties();
    if (level == Level.SERIALIZABLE) {
      p.setProperty("scalar.db.consensus_commit.isolation_level", "SERIALIZABLE");
      if (e.engine() == Engine.POSTGRESQL) {
        p.setProperty("scalar.db.jdbc.isolation_level", "SERIALIZABLE");
      }
    }
    return p;
  }

  /** The SQL that appends the parameter to {@code v}. */
  private static String concat(Engine engine) {
    return switch (engine) {
      case MYSQL, MARIADB -> "CONCAT(v, ?)";
      case SQLSERVER -> "v + ?";
      default -> "v || ?";
    };
  }

  /** Maelstrom's jar, fetched once into {@code build/maelstrom/<version>/} and checked. */
  private static Path maelstrom() throws IOException, InterruptedException {
    Path jar = HOME.resolve("maelstrom/lib/maelstrom.jar");
    if (Files.exists(jar)) {
      return jar;
    }
    Files.createDirectories(HOME);
    Path archive = HOME.resolve("maelstrom.tar.bz2");
    HttpResponse<InputStream> r =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "https://github.com/jepsen-io/maelstrom/releases/download/"
                                + MAELSTROM
                                + "/maelstrom.tar.bz2"))
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());
    if (r.statusCode() != 200) {
      throw new IOException("Maelstrom " + MAELSTROM + ": HTTP " + r.statusCode());
    }
    try (InputStream in = r.body()) {
      Files.copy(in, archive);
    }
    try {
      String sha256 =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
      if (!sha256.equals(MAELSTROM_SHA256)) {
        Files.delete(archive);
        throw new IOException("Maelstrom " + MAELSTROM + ": SHA-256 " + sha256);
      }
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
    Process tar =
        new ProcessBuilder("tar", "xjf", "maelstrom.tar.bz2")
            .directory(HOME.toFile())
            .inheritIO()
            .start();
    if (tar.waitFor() != 0) {
      throw new IOException("Maelstrom " + MAELSTROM + ": tar failed");
    }
    return jar;
  }
}
