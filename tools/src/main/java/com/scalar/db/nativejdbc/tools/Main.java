package com.scalar.db.nativejdbc.tools;

import com.scalar.db.nativejdbc.NativeJdbcUrl;
import com.scalar.db.nativejdbc.catalog.Catalog;
import com.scalar.db.nativejdbc.catalog.TableInfo;
import com.scalar.db.nativejdbc.engine.Engine;
import com.scalar.db.nativejdbc.setup.Check;
import com.scalar.db.nativejdbc.setup.Setup;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code scalardb-native-tools}: the setup each ScalarDB table needs before the driver serves it,
 * its check, and its teardown (docs/design.md, section 3). It connects through the vendor driver,
 * with DDL privileges for setup and teardown, and never prompts. Exit 0 when all is well, 1 when
 * {@code check} finds something missing or rows stuck, 2 on an error.
 */
@Command(
    name = "scalardb-native-tools",
    sortOptions = false,
    versionProvider = Main.Version.class,
    description = {
      "Sets up ScalarDB tables for ScalarDB Native JDBC, checks them, or takes the setup down.",
      "The password comes from SCALARDB_NATIVE_PASSWORD, or --property password=...; it never"
          + " prompts.",
      "After setting up a newly added table, setup waits out the applications' catalog TTL"
          + " (--catalog-ttl, 60 s) so that their drivers know the table, then says ScalarDB"
          + " transactions may write it; --no-wait and --json give from when instead. If setup is"
          + " interrupted while it runs, wait out the TTL before ScalarDB writes the tables."
    },
    exitCodeListHeading = "Exit codes:%n",
    exitCodeList = {
      "0:all is well",
      "1:check found a table not set up, rows stuck, a database setting to fix, or an object that"
          + " reaches a ScalarDB table around the driver",
      "2:an error"
    })
public final class Main implements Callable<Integer> {

  static final int EXIT_OK = 0;
  static final int EXIT_FOUND = 1;
  static final int EXIT_ERROR = 2;

  private static final String METADATA_SCHEMA = "scalardbnative.metadataSchema";
  private static final String PASSWORD_ENV = "SCALARDB_NATIVE_PASSWORD";

  /** What the command does. */
  enum Action {
    setup,
    check,
    teardown
  }

  @Parameters(index = "0", paramLabel = "ACTION", description = "setup, check or teardown")
  private @Nullable Action action;

  @Parameters(
      index = "1",
      paramLabel = "URL",
      description = "jdbc:scalardb-native:..., as the application uses, or the vendor's URL")
  private @Nullable String url;

  @Option(
      names = {"-U", "--username"},
      description = "database user name")
  private @Nullable String user;

  @Option(
      names = "--property",
      paramLabel = "KEY=VALUE",
      description = "a connect property for the vendor driver")
  private Map<String, String> properties = new LinkedHashMap<>();

  @Option(
      names = "--metadata-schema",
      description = "the schema of ScalarDB's catalog (default: the URL's, else scalardb)")
  private @Nullable String metadataSchema;

  @Option(
      names = "--table",
      paramLabel = "NAMESPACE.TABLE",
      description = "a table to act on; may be repeated (default: every ScalarDB table)")
  private List<String> tables = new ArrayList<>();

  @Option(names = "--dry-run", description = "print the statements setup or teardown would run")
  private boolean dryRun;

  @Option(
      names = "--grant",
      paramLabel = "USER",
      description = "setup: grant SELECT on each read view to this user or role; may be repeated")
  private List<String> grantees = new ArrayList<>();

  @Option(
      names = "--backfill-wait",
      paramLabel = "SECONDS",
      description = "setup: how long to wait per table for rows in flight (default: 60)")
  private long backfillWait = 60;

  @Option(
      names = "--catalog-ttl",
      paramLabel = "SECONDS",
      description =
          "setup: how long the applications' drivers trust ScalarDB's catalog, their"
              + " scalardbnative.catalogTtlMs in seconds, rounded up, to wait out after a new table;"
              + " 0 to 3600 (default: 60)")
  private long catalogTtl = Catalog.DEFAULT_TTL.toSeconds();

  @Option(
      names = "--no-wait",
      description = "setup: print from when ScalarDB may write a new table instead of waiting")
  private boolean noWait;

  @Option(
      names = "--lifetime",
      paramLabel = "SECONDS",
      description = "check: how long a row may be prepared before it is stuck (default: 15)")
  private long lifetime = Check.DEFAULT_LIFETIME.toSeconds();

  @Option(names = "--json", description = "print the outcome as JSON")
  private boolean json;

  @Option(
      names = {"-h", "--help"},
      usageHelp = true,
      description = "show this help, then exit")
  @SuppressWarnings("UnusedVariable") // read by picocli
  private boolean help;

  @Option(
      names = {"-V", "--version"},
      versionHelp = true,
      description = "output version information, then exit")
  @SuppressWarnings("UnusedVariable") // read by picocli
  private boolean version;

  private final PrintWriter out;
  private final PrintWriter err;
  // the tables this setup set up for the first time, and from when ScalarDB may write them
  private final List<String> newTables = new ArrayList<>();
  private @Nullable Instant writableAt;

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)
          .withZone(ZoneId.systemDefault());

  private Main(PrintWriter out, PrintWriter err) {
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    Charset charset = Charset.defaultCharset();
    PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, charset), true);
    PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, charset), true);
    System.exit(run(out, err, args));
  }

  /** Runs the command with the given streams; the tests call this. */
  static int run(PrintWriter out, PrintWriter err, String... args) {
    CommandLine cl = new CommandLine(new Main(out, err)).setOut(out).setErr(err);
    cl.setCaseInsensitiveEnumValuesAllowed(true);
    cl.setParameterExceptionHandler(
        (e, a) -> {
          err.println(e.getMessage());
          e.getCommandLine().usage(err);
          return EXIT_ERROR;
        });
    cl.setExecutionExceptionHandler(
        (e, c, p) -> {
          err.println(e.getMessage() == null ? e.toString() : e.getMessage());
          return EXIT_ERROR;
        });
    return cl.execute(args);
  }

  @Override
  public Integer call() throws SQLException {
    if (catalogTtl < 0 || catalogTtl > Catalog.MAX_TTL.toSeconds()) {
      throw new IllegalArgumentException(
          "--catalog-ttl is between 0 and "
              + Catalog.MAX_TTL.toSeconds()
              + " seconds, as the driver's catalogTtlMs is");
    }
    int exit = connectAndRun();
    // after the connections closed: the wait holds none (todo 298)
    if (action == Action.setup && !json) {
      waitForDrivers();
    }
    return exit;
  }

  @SuppressFBWarnings(
      value = "HARD_CODE_PASSWORD",
      justification = "the password comes from the environment or the caller, never the code")
  private int connectAndRun() throws SQLException {
    NativeJdbcUrl parsed = parse(Objects.requireNonNull(url, "URL"));
    Engine engine = parsed.engine();
    String schema =
        metadataSchema != null
            ? metadataSchema
            : parsed
                .driverParameters()
                .getOrDefault(METADATA_SCHEMA, Catalog.DEFAULT_METADATA_SCHEMA);
    Properties props = new Properties();
    if (user != null) {
      props.setProperty("user", user);
    }
    String password = System.getenv(PASSWORD_ENV);
    if (password != null) {
      props.setProperty("password", password);
    }
    props.putAll(properties);
    String vendorUrl = mysqlThroughMariaDb(engine, parsed.vendorUrl());
    try (Connection c = DriverManager.getConnection(vendorUrl, props);
        Catalog catalog =
            new Catalog(engine, schema, () -> DriverManager.getConnection(vendorUrl, props))) {
      c.setAutoCommit(true);
      Set<String> unmatched = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      unmatched.addAll(tables);
      Predicate<TableInfo> which =
          t -> {
            String name = t.namespace() + "." + t.table();
            unmatched.remove(name);
            return tables.isEmpty() || tables.stream().anyMatch(name::equalsIgnoreCase);
          };
      int exit;
      switch (Objects.requireNonNull(action, "ACTION")) {
        case setup:
          exit =
              changes(
                  dryRun
                      ? Setup.plan(catalog, c, which, grantees)
                      : Setup.run(catalog, c, which, Duration.ofSeconds(backfillWait), grantees));
          // objects that reach a ScalarDB table around the driver (todo 323): warned, not refused
          for (String o : Check.objects(catalog, c)) {
            err.println("warning: " + o);
          }
          break;
        case teardown:
          exit =
              changes(
                  dryRun
                      ? Setup.planTeardown(catalog, c, which)
                      : Setup.teardown(catalog, c, which));
          break;
        case check:
          exit =
              check(
                  concat(Check.database(engine, c), Check.objects(catalog, c)),
                  Check.tables(catalog, c, which, Duration.ofSeconds(lifetime)));
          break;
        default:
          throw new AssertionError(action);
      }
      if (!unmatched.isEmpty()) {
        err.println("no ScalarDB table " + String.join(", ", unmatched));
        return EXIT_ERROR;
      }
      return exit;
    }
  }

  private static List<String> concat(List<String> a, List<String> b) {
    List<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private int changes(List<Setup.Result> results) {
    boolean failed = false;
    if (action == Action.setup && !dryRun) {
      for (Setup.Result r : results) {
        // a failure after the view still leaves a table new to the drivers, which a rerun no
        // longer sees as new
        if (r.createdReadView()) {
          newTables.add(r.table());
        }
      }
      writableAt = Instant.now().plusSeconds(catalogTtl);
    }
    if (json) {
      List<String> items = new ArrayList<>();
      for (Setup.Result r : results) {
        SQLException e = r.error();
        items.add(
            "{\"table\":"
                + Json.string(r.table())
                + ",\"actions\":"
                + Json.strings(r.actions())
                + ",\"rowsStamped\":"
                + r.rowsStamped()
                + ",\"error\":"
                + (e == null ? "null" : Json.string(e.getMessage()))
                + "}");
        failed |= !r.succeeded();
      }
      String writable =
          newTables.isEmpty() || writableAt == null
              ? ""
              : ",\"newTables\":"
                  + Json.strings(newTables)
                  + ",\"writableAt\":"
                  + Json.string(writableAt.truncatedTo(ChronoUnit.SECONDS).toString());
      out.println(
          "{\"action\":"
              + Json.string(String.valueOf(action))
              + ",\"dryRun\":"
              + dryRun
              + ",\"tables\":["
              + String.join(",", items)
              + "]"
              + writable
              + "}");
    } else {
      for (Setup.Result r : results) {
        if (dryRun) {
          out.println("-- " + r.table());
          for (String s : r.actions()) {
            out.println(s.startsWith("--") ? s : s + ";");
          }
        } else {
          out.println(r);
        }
        failed |= !r.succeeded();
      }
    }
    return failed ? EXIT_ERROR : EXIT_OK;
  }

  /**
   * After a new table, waits out the applications' catalog TTL, so that no driver still takes the
   * table for a plain one when ScalarDB writes it (todo 298), and says when ScalarDB may; with
   * {@code --no-wait}, or when interrupted, says from when.
   */
  private void waitForDrivers() {
    Instant at = writableAt;
    if (newTables.isEmpty() || at == null) {
      return;
    }
    String names = String.join(", ", newTables);
    String from =
        "ScalarDB transactions may write "
            + names
            + " from "
            + TIME.format(at)
            + ", or after every application using the driver on this database has restarted.";
    out.println();
    out.println(
        (newTables.size() == 1 ? "New ScalarDB table: " : "New ScalarDB tables: ") + names + ".");
    if (noWait || catalogTtl <= 0) {
      out.println(
          catalogTtl <= 0 ? "Done: ScalarDB transactions may write " + names + " now." : from);
      return;
    }
    out.println(
        "Waiting "
            + catalogTtl
            + " seconds for running applications' drivers to learn about "
            + (newTables.size() == 1 ? "it" : "them")
            + " (their catalog TTL)...");
    // an interrupted wait still says from when, Ctrl-C included; one of the two lines, never both
    AtomicBoolean said = new AtomicBoolean();
    Thread hook =
        new Thread(
            () -> {
              if (!said.getAndSet(true)) {
                out.println(from);
              }
            });
    Runtime.getRuntime().addShutdownHook(hook);
    try {
      Thread.sleep(Math.max(0, Duration.between(Instant.now(), at).toMillis()));
      if (!said.getAndSet(true)) {
        out.println("Done: ScalarDB transactions may write " + names + " now.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (!said.getAndSet(true)) {
        out.println(from);
      }
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException shuttingDown) {
        // the hook runs and says from when
      }
    }
  }

  private int check(List<String> database, List<Check.Result> results) {
    boolean found = !database.isEmpty();
    boolean failed = false;
    for (Check.Result r : results) {
      found |= !r.setUp() || r.stuck() > 0;
      failed |= r.error() != null;
    }
    if (json) {
      List<String> items = new ArrayList<>();
      for (Check.Result r : results) {
        SQLException e = r.error();
        Duration oldest = r.oldest();
        items.add(
            "{\"table\":"
                + Json.string(r.table())
                + ",\"setUp\":"
                + r.setUp()
                + ",\"stuck\":"
                + r.stuck()
                + ",\"oldestSeconds\":"
                + (oldest == null ? "null" : oldest.toSeconds())
                + ",\"error\":"
                + (e == null ? "null" : Json.string(e.getMessage()))
                + "}");
      }
      out.println(
          "{\"action\":\"check\",\"database\":"
              + Json.strings(database)
              + ",\"tables\":["
              + String.join(",", items)
              + "]}");
    } else {
      for (String d : database) {
        out.println(d);
      }
      for (Check.Result r : results) {
        out.println(r);
      }
    }
    return failed ? EXIT_ERROR : found ? EXIT_FOUND : EXIT_OK;
  }

  /** The URL as the application gives it, or the vendor's with the driver's prefix added. */
  private static NativeJdbcUrl parse(String url) {
    if (NativeJdbcUrl.accepts(url)) {
      return NativeJdbcUrl.parse(url);
    }
    if (url.regionMatches(true, 0, "jdbc:", 0, 5)) {
      return NativeJdbcUrl.parse(NativeJdbcUrl.PREFIX + url.substring(5));
    }
    throw new IllegalArgumentException("not a JDBC URL");
  }

  /**
   * MySQL goes through the bundled MariaDB Connector/J, which takes a {@code jdbc:mysql:} URL only
   * with {@code permitMysqlScheme} in the URL itself, as the shell does it. Left alone when MySQL
   * Connector/J is on the class path.
   */
  private static String mysqlThroughMariaDb(Engine engine, String vendorUrl) {
    if (engine != Engine.MYSQL
        || vendorUrl.toLowerCase(Locale.ROOT).contains("permitmysqlscheme")
        || DriverManager.drivers()
            .anyMatch(d -> d.getClass().getName().equals("com.mysql.cj.jdbc.Driver"))) {
      return vendorUrl;
    }
    return vendorUrl + (vendorUrl.indexOf('?') < 0 ? "?" : "&") + "permitMysqlScheme=true";
  }

  /** The version from the jar's manifest. */
  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String v = Main.class.getPackage().getImplementationVersion();
      return new String[] {"scalardb-native-tools " + (v == null ? "(development build)" : v)};
    }
  }

  /** The little JSON the output needs. */
  static final class Json {
    private Json() {}

    static String string(@Nullable String s) {
      if (s == null) {
        return "null";
      }
      StringBuilder b = new StringBuilder("\"");
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"':
            b.append("\\\"");
            break;
          case '\\':
            b.append("\\\\");
            break;
          case '\n':
            b.append("\\n");
            break;
          case '\r':
            b.append("\\r");
            break;
          case '\t':
            b.append("\\t");
            break;
          default:
            if (c < 0x20) {
              b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
              b.append(c);
            }
        }
      }
      return b.append('"').toString();
    }

    static String strings(List<String> list) {
      List<String> out = new ArrayList<>();
      for (String s : list) {
        out.add(string(s));
      }
      return "[" + String.join(",", out) + "]";
    }
  }
}
