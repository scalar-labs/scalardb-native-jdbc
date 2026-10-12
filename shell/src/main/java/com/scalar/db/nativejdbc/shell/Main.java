package com.scalar.db.nativejdbc.shell;

import com.scalar.db.nativejdbc.NativeJdbcUrl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.jline.reader.EOFError;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code snsql}, the interactive SQL shell of ScalarDB Native JDBC, in the manner of psql. Every
 * statement goes through the driver, so ScalarDB tables keep their protections, unlike with psql
 * itself, which reaches the database directly.
 */
@Command(
    name = "snsql",
    sortOptions = false,
    versionProvider = Main.Version.class,
    description = "The interactive SQL shell of ScalarDB Native JDBC.")
public final class Main implements Callable<Integer> {

  private static final int EXIT_OK = 0;
  private static final int EXIT_FATAL = 1;
  private static final int EXIT_CONNECTION = 2;
  private static final int EXIT_SCRIPT = 3;

  @Parameters(
      arity = "0..1",
      paramLabel = "URL",
      description = "jdbc:scalardb-native:..., or give -h, -p and -d with --engine")
  private @Nullable String url;

  @Option(
      names = {"-h", "--host"},
      description = "database server host (default: localhost)")
  private @Nullable String host;

  @Option(
      names = {"-p", "--port"},
      description = "database server port (default: the engine's)")
  private @Nullable Integer port;

  @Option(
      names = {"-d", "--dbname"},
      description = "database name")
  private @Nullable String dbname;

  @Option(
      names = "--engine",
      description = "postgresql, mysql, mariadb, sqlserver or oracle, with -h, -p and -d")
  private @Nullable String engine;

  @Option(
      names = {"-U", "--username"},
      description = "database user name")
  private @Nullable String user;

  @Option(
      names = {"-w", "--no-password"},
      description = "never prompt for a password")
  private boolean noPassword;

  @Option(
      names = {"-v", "--set", "--variable"},
      paramLabel = "NAME=VALUE",
      description = "set a variable; ON_ERROR_STOP=1 stops a script at its first error")
  private Map<String, String> variables = new LinkedHashMap<>();

  @Option(
      names = "--property",
      paramLabel = "KEY=VALUE",
      description = "a connect property, for the vendor driver or scalardbnative.*")
  private Map<String, String> properties = new LinkedHashMap<>();

  @Option(
      names = {"-?", "--help"},
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

  /** The -c and -f options in the order given, as psql runs them. */
  private final List<String[]> actions = new ArrayList<>();

  private final PrintWriter out;
  private final PrintWriter err;

  private Main(PrintWriter out, PrintWriter err) {
    this.out = out;
    this.err = err;
  }

  @Option(
      names = {"-c", "--command"},
      paramLabel = "COMMAND",
      description = "run one command, then exit; may be repeated")
  @SuppressWarnings("UnusedMethod") // called by picocli
  private void command(String command) {
    actions.add(new String[] {"-c", command});
  }

  @Option(
      names = {"-f", "--file"},
      paramLabel = "FILE",
      description = "run the commands in a file, - for standard input, then exit")
  @SuppressWarnings("UnusedMethod") // called by picocli
  private void file(String file) {
    actions.add(new String[] {"-f", file});
  }

  /**
   * Runs the shell.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    Charset charset = Charset.defaultCharset();
    PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, charset), true);
    PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, charset), true);
    System.exit(run(out, err, args));
  }

  /** Runs the shell with the given streams; the tests call this. */
  static int run(PrintWriter out, PrintWriter err, String... args) {
    return new CommandLine(new Main(out, err))
        .setOut(out)
        .setErr(err)
        // each -c and -f calls its setter, which keeps them in order
        .setOverwrittenOptionsAllowed(true)
        .execute(args);
  }

  @Override
  @SuppressFBWarnings(
      value = "HARD_CODE_PASSWORD",
      justification = "the password comes from the environment or the user, never the code")
  public Integer call() {
    String target;
    NativeJdbcUrl parsed;
    try {
      target = url();
      parsed = NativeJdbcUrl.parse(target);
    } catch (IllegalArgumentException e) {
      err.println("snsql: error: " + e.getMessage());
      return EXIT_FATAL;
    }
    if (DriverManager.drivers().noneMatch(d -> accepts(d, parsed.vendorUrl()))) {
      err.println(
          "snsql: error: no JDBC driver for jdbc:"
              + parsed.subProtocol()
              + ":; put "
              + driverName(parsed.subProtocol())
              + " in "
              + libDirectory());
      return EXIT_CONNECTION;
    }
    Properties connect = new Properties();
    connect.putAll(properties);
    if (user != null) {
      connect.setProperty("user", user);
    }
    // a terminal only when snsql talks to a person: for the password, or for the prompt
    Terminal terminal = null;
    try {
      String password = System.getenv("SNSQL_PASSWORD");
      if (password != null) {
        connect.setProperty("password", password);
      } else if (user != null && !noPassword && !connect.containsKey("password")) {
        terminal = terminal();
        if (terminal == null) {
          err.println("snsql: error: no terminal to prompt for the password; set SNSQL_PASSWORD");
          return EXIT_CONNECTION;
        }
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
        try {
          connect.setProperty(
              "password", reader.readLine("Password for user " + user + ": ", '\0'));
        } catch (UserInterruptException | EndOfFileException e) {
          return EXIT_CONNECTION;
        }
      }

      Connection connection;
      try {
        connection = DriverManager.getConnection(target, connect);
      } catch (SQLException e) {
        err.println("snsql: error: connection failed: " + e.getMessage());
        return EXIT_CONNECTION;
      }
      boolean onErrorStop = isOn(variables.get("ON_ERROR_STOP"));
      StatementSplitter.Dialect dialect = StatementSplitter.Dialect.of(parsed.subProtocol());
      String shown = withoutParameters(target);
      try {
        if (actions.isEmpty() && terminal == null) {
          terminal = terminal();
        }
        if (actions.isEmpty() && terminal != null) {
          return interactive(terminal, connection, dialect, shown);
        }
        if (actions.isEmpty()) {
          // input piped in: a script, as psql reads it
          actions.add(new String[] {"-f", "-"});
        }
        return script(connection, dialect, shown, onErrorStop);
      } finally {
        try {
          connection.close();
        } catch (SQLException e) {
          // the session is over either way
        }
      }
    } catch (SQLException | IOException e) {
      err.println("snsql: error: " + e.getMessage());
      return EXIT_FATAL;
    } finally {
      if (terminal != null) {
        try {
          terminal.close();
        } catch (IOException e) {
          // the session is over either way
        }
      }
    }
  }

  /** The system terminal, or null when standard input or output is not one. */
  private static @Nullable Terminal terminal() throws IOException {
    // dumb(true): no warning when there is no terminal, only a dumb one, which this checks for
    Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
    if (terminal.getType().startsWith(Terminal.TYPE_DUMB)) {
      terminal.close();
      return null;
    }
    return terminal;
  }

  /** The URL given, or one built from -h, -p, -d and --engine. */
  private String url() {
    if (url != null) {
      if (host != null || port != null || dbname != null || engine != null) {
        throw new IllegalArgumentException("give either a URL or -h, -p, -d and --engine");
      }
      return mysqlThroughMariaDb(url);
    }
    if (engine == null) {
      throw new IllegalArgumentException(
          "no database to connect to: give a URL, or --engine with -h, -p and -d");
    }
    String e = engine.toLowerCase(Locale.ROOT);
    String h = host == null ? "localhost" : host;
    String d = dbname == null ? "" : dbname;
    String built;
    switch (e) {
      case "postgresql":
        built = "postgresql://" + h + ":" + (port == null ? 5432 : port) + "/" + d;
        break;
      case "mysql":
      case "mariadb":
        built = e + "://" + h + ":" + (port == null ? 3306 : port) + "/" + d;
        break;
      case "sqlserver":
        built =
            "sqlserver://"
                + h
                + ":"
                + (port == null ? 1433 : port)
                + (d.isEmpty() ? "" : ";databaseName=" + d);
        break;
      case "oracle":
        built = "oracle:thin:@//" + h + ":" + (port == null ? 1521 : port) + "/" + d;
        break;
      default:
        throw new IllegalArgumentException(
            "Unsupported engine '"
                + engine
                + "': expected postgresql, mysql, mariadb, sqlserver "
                + "or oracle");
    }
    return mysqlThroughMariaDb(NativeJdbcUrl.PREFIX + built);
  }

  /**
   * MySQL goes through the bundled MariaDB Connector/J, which takes a {@code jdbc:mysql:} URL only
   * with {@code permitMysqlScheme} in the URL itself (docs/design.md, URL scheme). Left alone when
   * the user has put MySQL Connector/J in {@code lib/}.
   */
  private static String mysqlThroughMariaDb(String url) {
    String lower = url.toLowerCase(Locale.ROOT);
    if (!lower.startsWith(NativeJdbcUrl.PREFIX + "mysql:")
        || lower.contains("permitmysqlscheme")
        || isPresent("com.mysql.cj.jdbc.Driver")) {
      return url;
    }
    return url + (url.indexOf('?') < 0 ? "?" : "&") + "permitMysqlScheme=true";
  }

  private static boolean isPresent(String className) {
    try {
      Class.forName(className, false, Main.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private static boolean accepts(java.sql.Driver driver, String vendorUrl) {
    try {
      return driver.acceptsURL(vendorUrl);
    } catch (SQLException e) {
      return false;
    }
  }

  private static String driverName(String subProtocol) {
    switch (subProtocol) {
      case "postgresql":
        return "the PostgreSQL JDBC driver (pgjdbc)";
      case "mysql":
        return "MariaDB Connector/J or MySQL Connector/J";
      case "mariadb":
        return "MariaDB Connector/J";
      case "sqlserver":
        return "the Microsoft JDBC Driver for SQL Server (mssql-jdbc)";
      default:
        return "the Oracle JDBC driver (ojdbc11)";
    }
  }

  /** The distribution's lib directory, where this class's jar lies. */
  @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "the path of snsql's own jar")
  private static String libDirectory() {
    try {
      Path jar = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path dir = jar.getParent();
      return dir == null ? "lib/" : dir.toString();
    } catch (Exception e) {
      return "lib/";
    }
  }

  /** The URL without its parameters, which may hold a password, for {@code \conninfo}. */
  private static String withoutParameters(String url) {
    int q = url.indexOf('?');
    int semicolon = url.indexOf(';');
    int cut = q < 0 ? semicolon : semicolon < 0 ? q : Math.min(q, semicolon);
    return cut < 0 ? url : url.substring(0, cut);
  }

  private static boolean isOn(@Nullable String value) {
    if (value == null) {
      return false;
    }
    switch (value.toLowerCase(Locale.ROOT)) {
      case "":
      case "1":
      case "on":
      case "true":
      case "yes":
        return true;
      default:
        return false;
    }
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification = "the user names the script to run, with the user's own permissions")
  private int script(
      Connection connection, StatementSplitter.Dialect dialect, String shown, boolean onErrorStop)
      throws SQLException, IOException {
    Repl repl = new Repl(connection, dialect, shown, out, onErrorStop);
    for (String[] action : actions) {
      boolean goOn;
      if (action[0].equals("-c")) {
        goOn = repl.feed(action[1]) && repl.finish();
      } else if (action[1].equals("-")) {
        goOn = read(repl, new InputStreamReader(System.in, Charset.defaultCharset()));
      } else {
        goOn = read(repl, Files.newBufferedReader(Paths.get(action[1]), StandardCharsets.UTF_8));
      }
      if (!goOn) {
        repl.close();
        return EXIT_SCRIPT;
      }
    }
    repl.close();
    return EXIT_OK;
  }

  /** Feeds a script line by line; returns whether the session goes on. */
  private static boolean read(Repl repl, java.io.Reader source) throws IOException {
    try (BufferedReader reader = new BufferedReader(source)) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (!repl.feed(line)) {
          return false;
        }
      }
    }
    return repl.finish();
  }

  private static int interactive(
      Terminal terminal, Connection connection, StatementSplitter.Dialect dialect, String shown)
      throws SQLException {
    PrintWriter screen = terminal.writer();
    Repl repl = new Repl(connection, dialect, shown, screen, false);
    LineReader reader =
        LineReaderBuilder.builder()
            .terminal(terminal)
            .appName("snsql")
            .parser(new SqlParser(repl))
            .variable(
                LineReader.HISTORY_FILE,
                Paths.get(System.getProperty("user.home"), ".snsql_history"))
            // `!` is SQL, not history expansion
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build();
    terminal.handle(Terminal.Signal.INT, signal -> repl.cancel());
    screen.println(
        new Version().getVersion()[0]
            + " ("
            + connection.getMetaData().getDatabaseProductName()
            + " "
            + connection.getMetaData().getDatabaseProductVersion()
            + ")");
    screen.println("Type \"\\?\" for help.");
    screen.println();
    while (true) {
      reader.variable(LineReader.SECONDARY_PROMPT_PATTERN, repl.prompt(true).replace("%", "%%"));
      String line;
      try {
        line = reader.readLine(repl.prompt(false));
      } catch (UserInterruptException e) {
        continue; // Ctrl-C at the prompt clears the line
      } catch (EndOfFileException e) {
        screen.println("\\q");
        break;
      }
      if (!repl.feed(line)) {
        break;
      }
    }
    repl.close();
    return EXIT_OK;
  }

  /** Accepts a line only once it completes a statement; until then the prompt continues it. */
  private static final class SqlParser implements Parser {
    private final Repl repl;
    // no escapes and no quotes: words only matter for completion, and `\` starts a meta-command
    private final DefaultParser words =
        new DefaultParser().escapeChars(null).quoteChars(new char[0]);

    SqlParser(Repl repl) {
      this.repl = repl;
    }

    @Override
    public ParsedLine parse(String line, int cursor, ParseContext context) {
      if (context == ParseContext.ACCEPT_LINE && !repl.isComplete(line)) {
        throw new EOFError(-1, cursor, "the statement continues");
      }
      return words.parse(line, cursor, context);
    }
  }

  /** The version from the jar's manifest. */
  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      String v = Main.class.getPackage().getImplementationVersion();
      return new String[] {"snsql " + (v == null ? "(development build)" : v)};
    }
  }
}
