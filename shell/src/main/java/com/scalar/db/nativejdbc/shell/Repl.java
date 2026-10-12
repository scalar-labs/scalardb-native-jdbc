package com.scalar.db.nativejdbc.shell;

import com.scalar.db.nativejdbc.NativeJdbcDriver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The shell's session: takes typed text, runs the statements it completes and the meta-commands,
 * and prints the results. The prompt, scripts and {@code -c} all feed it, so they behave alike.
 *
 * <p>The driver refuses {@code BEGIN}, {@code SET TRANSACTION} and their kin as SQL, since it must
 * know the transaction boundaries and the level (docs/design.md, section 4). This class recognises
 * them and calls the JDBC methods instead, so a psql user's {@code BEGIN; ... COMMIT;} works.
 */
@SuppressFBWarnings(
    value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
    justification = "the session's state stays on its thread; only the running statement is shared")
final class Repl {

  private static final String LEVEL =
      "(READ\\s+UNCOMMITTED|READ\\s+COMMITTED|REPEATABLE\\s+READ|SNAPSHOT|SERIALIZABLE)";
  private static final Pattern BEGIN =
      Pattern.compile(
          "(?is)(?:BEGIN|START\\s+TRANSACTION)(?:\\s+(?:WORK|TRANSACTION|TRAN))?"
              + "(?:\\s+ISOLATION\\s+LEVEL\\s+"
              + LEVEL
              + ")?");
  private static final Pattern COMMIT =
      Pattern.compile("(?is)(?:COMMIT|END)(?:\\s+(?:WORK|TRANSACTION|TRAN))?");
  private static final Pattern ROLLBACK =
      Pattern.compile("(?is)(?:ROLLBACK|ABORT)(?:\\s+(?:WORK|TRANSACTION|TRAN))?");
  private static final Pattern SET_TRANSACTION =
      Pattern.compile("(?is)SET\\s+TRANSACTION\\s+ISOLATION\\s+LEVEL\\s+" + LEVEL);
  private static final Pattern SET_SESSION =
      Pattern.compile(
          "(?is)SET\\s+(?:SESSION\\s+CHARACTERISTICS\\s+AS\\s+TRANSACTION|SESSION\\s+TRANSACTION)"
              + "\\s+ISOLATION\\s+LEVEL\\s+"
              + LEVEL);
  private static final Pattern FIRST_WORDS = Pattern.compile("(\\w+)(?:\\s+(\\w+))?");

  /** Opens the connection {@code \c} asks for. */
  interface Connector {
    /**
     * Opens a connection.
     *
     * @param target a database name on the same server; null for the current database again
     * @return the new connection
     * @throws SQLException if it cannot be opened
     */
    Connected connect(@Nullable String target) throws SQLException;
  }

  /** A connection {@link Connector} opened, with what the session needs to know about it. */
  static final class Connected {
    final Connection connection;
    final StatementSplitter.Dialect dialect;
    final String url;

    /**
     * @param connection the driver's connection, in autocommit mode
     * @param dialect the engine's lexical rules
     * @param url the URL, without credentials, for {@code \conninfo}
     */
    Connected(Connection connection, StatementSplitter.Dialect dialect, String url) {
      this.connection = connection;
      this.dialect = dialect;
      this.url = url;
    }
  }

  private Connection connection;
  private StatementSplitter.Dialect dialect;
  private final PrintWriter out;
  private String url;
  private String database;
  private CatalogCommands catalog;
  private final boolean onErrorStop;
  private final @Nullable Connector connector;
  private final StringBuilder buffer = new StringBuilder();
  private boolean inTransaction;
  private int sessionLevel;
  private boolean expanded;
  private boolean timing;
  private @Nullable Set<String> completions;
  // the one field Ctrl-C's signal thread reads; everything else stays on the session's thread
  private final AtomicReference<@Nullable Statement> running = new AtomicReference<>();

  /**
   * Starts a session on an open connection.
   *
   * @param connection the driver's connection, in autocommit mode
   * @param dialect the engine's lexical rules
   * @param url the URL connected to, without credentials, for {@code \conninfo}
   * @param out where results and errors go
   * @param onErrorStop whether an error ends the session, psql's {@code ON_ERROR_STOP}
   * @throws SQLException if the connection cannot report its database
   */
  Repl(
      Connection connection,
      StatementSplitter.Dialect dialect,
      String url,
      PrintWriter out,
      boolean onErrorStop)
      throws SQLException {
    this(new Connected(connection, dialect, url), out, onErrorStop, null);
  }

  /**
   * Starts a session on an open connection.
   *
   * @param connected the connection
   * @param out where results and errors go
   * @param onErrorStop whether an error ends the session, psql's {@code ON_ERROR_STOP}
   * @param connector opens the connection {@code \c} asks for; null where {@code \c} is refused
   * @throws SQLException if the connection cannot report its database
   */
  Repl(Connected connected, PrintWriter out, boolean onErrorStop, @Nullable Connector connector)
      throws SQLException {
    this.out = out;
    this.onErrorStop = onErrorStop;
    this.connector = connector;
    this.connection = connected.connection;
    this.dialect = connected.dialect;
    this.url = connected.url;
    this.catalog = new CatalogCommands(connection, dialect);
    this.database = database(connection);
    this.sessionLevel = connection.getTransactionIsolation();
  }

  private static String database(Connection connection) throws SQLException {
    String catalog = connection.getCatalog();
    String schema = catalog == null || catalog.isEmpty() ? connection.getSchema() : catalog;
    return schema == null || schema.isEmpty() ? "snsql" : Printer.printable(schema);
  }

  /**
   * The prompt, psql's {@code db=>}: {@code -} instead of {@code =} on a continuation line, and a
   * {@code *} inside a transaction.
   *
   * @param continuation whether a statement is being continued
   * @return the prompt
   */
  String prompt(boolean continuation) {
    return database
        + (continuation ? "-" : "=")
        + (inTransaction ? "*" : "")
        + (passthrough() ? "!" : "")
        + "> ";
  }

  /**
   * The names to complete at the prompt: schemas, and the tables, views and columns of the current
   * schema, read once and again after DDL or {@code \c}.
   *
   * @return the names; empty if they cannot be read
   */
  Set<String> completions() {
    Set<String> names = completions;
    if (names == null) {
      try {
        names = catalog.completions();
      } catch (SQLException e) {
        names = Set.of();
      }
      completions = names;
    }
    return names;
  }

  /**
   * Whether typed text is ready to run: a meta-command, or statements that all end. The prompt
   * keeps reading lines until it is.
   *
   * @param text the lines typed so far
   * @return whether to accept the text
   */
  boolean isComplete(String text) {
    if (buffer.toString().isBlank() && text.strip().startsWith("\\")) {
      return true;
    }
    return !StatementSplitter.split(buffer + text, dialect).pending();
  }

  /**
   * Takes one line, or several the prompt accepted together, and runs what they complete.
   *
   * @param text the text
   * @return whether the session goes on: false after {@code \q}, or after an error with {@code
   *     ON_ERROR_STOP}
   */
  boolean feed(String text) {
    if (buffer.toString().isBlank() && text.strip().startsWith("\\")) {
      buffer.setLength(0);
      return meta(text.strip());
    }
    buffer.append(text).append('\n');
    StatementSplitter.Result split = StatementSplitter.split(buffer.toString(), dialect);
    buffer.setLength(0);
    if (split.pending()) {
      buffer.append(split.remainder());
    }
    for (String statement : split.statements()) {
      if (!run(statement)) {
        buffer.setLength(0);
        return false;
      }
    }
    return true;
  }

  /**
   * Runs what is left at the end of a script or a {@code -c} string, as psql does with a last
   * statement that has no {@code ;}.
   *
   * @return whether the session goes on
   */
  boolean finish() {
    String rest = buffer.toString().strip();
    buffer.setLength(0);
    return rest.isEmpty() || run(rest);
  }

  /**
   * The database product and version, for the banner.
   *
   * @return the product
   * @throws SQLException if the connection cannot report it
   */
  String product() throws SQLException {
    DatabaseMetaData md = connection.getMetaData();
    return Printer.printable(md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
  }

  /** Cancels the running statement, for Ctrl-C; called from the signal thread. */
  void cancel() {
    Statement s = running.get();
    if (s != null) {
      try {
        s.cancel();
      } catch (SQLException e) {
        out.println("Could not send cancel request: " + e.getMessage());
        out.flush();
      }
    }
  }

  /**
   * Ends the session: rolls back an open transaction, as the server would, and closes the
   * connection.
   *
   * @throws SQLException if closing fails
   */
  void close() throws SQLException {
    try {
      if (inTransaction) {
        connection.rollback();
      }
    } finally {
      connection.close();
    }
  }

  private boolean meta(String line) {
    String[] parts = line.split("\\s+", 2);
    String arg = parts.length > 1 && !parts[1].isBlank() ? parts[1].strip() : null;
    // psql's \dt+ and \dtS: more detail and system objects, which JDBC does not separate
    String command = parts[0].length() > 2 ? parts[0].replaceAll("[+S]+$", "") : parts[0];
    try {
      switch (command) {
        case "\\q":
          return false;
        case "\\?":
          help();
          break;
        case "\\conninfo":
          conninfo();
          break;
        case "\\c":
        case "\\connect":
          reconnect(arg);
          break;
        case "\\d":
          if (arg == null) {
            catalog.relations(null, expanded, out);
          } else {
            catalog.describe(arg, expanded, out);
          }
          break;
        case "\\dt":
          catalog.tables(arg, expanded, out);
          break;
        case "\\dv":
          catalog.views(arg, expanded, out);
          break;
        case "\\di":
          catalog.indexes(arg, expanded, out);
          break;
        case "\\dn":
          catalog.schemas(arg, expanded, out);
          break;
        case "\\l":
        case "\\list":
          catalog.databases(expanded, out);
          break;
        case "\\x":
          expanded = toggle(arg, expanded);
          out.println("Expanded display is " + (expanded ? "on." : "off."));
          break;
        case "\\timing":
          timing = toggle(arg, timing);
          out.println("Timing is " + (timing ? "on." : "off."));
          break;
        case "\\passthrough":
          return passthrough(arg);
        case "\\i":
        case "\\include":
          return include(arg);
        default:
          out.println("invalid command " + parts[0]);
          out.println("Try \\? for help.");
          break;
      }
    } catch (SQLException e) {
      error(e);
      return !onErrorStop;
    } catch (IllegalArgumentException e) {
      out.println(parts[0] + ": " + e.getMessage());
      return !onErrorStop;
    } finally {
      out.flush();
    }
    return true;
  }

  private void help() {
    out.println("General");
    out.println("  \\q                     quit snsql");
    out.println("  \\conninfo              display information about the current connection");
    out.println("  \\c [DBNAME]            connect to another database on the server");
    out.println("  \\i FILE                execute commands from file");
    out.println("  \\timing [on|off]       toggle timing of commands");
    out.println("  \\x [on|off]            toggle expanded output");
    out.println("  \\?                     show this help");
    out.println();
    out.println("Informational (PATTERN: [SCHEMA.]NAME, with * and ?)");
    out.println("  \\d [NAME]              describe table, view or index; list relations");
    out.println("  \\dt [PATTERN]          list tables, marking ScalarDB tables");
    out.println("  \\dv [PATTERN]          list views");
    out.println("  \\di [PATTERN]          list indexes");
    out.println("  \\dn [PATTERN]          list schemas");
    out.println("  \\l                     list databases");
    out.println();
    out.println("Transactions");
    out.println("  BEGIN, COMMIT, ROLLBACK and SET TRANSACTION ISOLATION LEVEL work as in psql;");
    out.println("  snsql runs them through JDBC, since the driver must see every transaction.");
    out.println("  \\passthrough [on|off]  run statements unchecked by the driver, in autocommit");
    out.println("                         only, for DDL and maintenance; the prompt shows !");
    out.println();
  }

  private static boolean toggle(@Nullable String arg, boolean current) {
    if (arg == null) {
      return !current;
    }
    switch (arg.toLowerCase(Locale.ROOT)) {
      case "on":
      case "true":
      case "1":
        return true;
      case "off":
      case "false":
      case "0":
        return false;
      default:
        throw new IllegalArgumentException("unrecognized value \"" + arg + "\": on or off");
    }
  }

  private boolean passthrough() {
    try {
      return Boolean.parseBoolean(connection.getClientInfo(NativeJdbcDriver.PASSTHROUGH));
    } catch (SQLException e) {
      return false;
    }
  }

  /** {@code \passthrough}: the driver's passthrough setting, in autocommit only, as it requires. */
  private boolean passthrough(@Nullable String arg) throws SQLException {
    boolean on = toggle(arg, passthrough());
    if (on && inTransaction) {
      out.println(
          "ERROR:  \\passthrough applies in autocommit mode only; end the transaction first");
      return !onErrorStop;
    }
    connection.setClientInfo(NativeJdbcDriver.PASSTHROUGH, Boolean.toString(on));
    completions = null; // anything may run now
    out.println(
        on
            ? "Passthrough is on: statements reach the database unchecked by the driver."
            : "Passthrough is off.");
    return true;
  }

  /** {@code \i}: runs a file's commands in this session, as {@code -f} does. */
  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification = "the user names the script to run, with the user's own permissions")
  private boolean include(@Nullable String file) {
    if (file == null) {
      throw new IllegalArgumentException("missing required argument");
    }
    try (BufferedReader reader = Files.newBufferedReader(Paths.get(file), StandardCharsets.UTF_8)) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (!feed(line)) {
          return false;
        }
      }
    } catch (NoSuchFileException e) {
      out.println(file + ": No such file or directory");
      return !onErrorStop;
    } catch (IOException e) {
      out.println(file + ": " + e.getMessage());
      return !onErrorStop;
    }
    return finish();
  }

  /** {@code \c}: a new connection replaces the current one only once it is open, as in psql. */
  private void reconnect(@Nullable String target) throws SQLException {
    if (connector == null) {
      out.println("\\c is not available here");
      return;
    }
    Connected next;
    try {
      next = connector.connect(target);
    } catch (SQLException e) {
      error(e);
      out.println("Previous connection kept");
      return;
    }
    Connection previous = connection;
    boolean wasInTransaction = inTransaction;
    connection = next.connection;
    dialect = next.dialect;
    url = next.url;
    catalog = new CatalogCommands(connection, dialect);
    database = database(connection);
    sessionLevel = connection.getTransactionIsolation();
    inTransaction = false;
    completions = null;
    try {
      if (wasInTransaction) {
        previous.rollback();
      }
    } finally {
      previous.close();
    }
    out.println(
        "You are now connected to database \""
            + database
            + "\" as user \""
            + Printer.printable(String.valueOf(connection.getMetaData().getUserName()))
            + "\".");
  }

  private void conninfo() throws SQLException {
    DatabaseMetaData md = connection.getMetaData();
    out.println(
        "You are connected to database \""
            + database
            + "\" as user \""
            + Printer.printable(String.valueOf(md.getUserName()))
            + "\" via "
            + url
            + ".");
    out.println(
        Printer.printable(
            md.getDatabaseProductName()
                + " "
                + md.getDatabaseProductVersion()
                + ", "
                + md.getDriverName()
                + " "
                + md.getDriverVersion()));
  }

  /** Runs one statement; returns whether the session goes on. */
  private boolean run(String sql) {
    long start = System.nanoTime();
    try {
      String s = StatementSplitter.withoutLeadingComments(sql);
      if (!transactionCommand(s)) {
        execute(sql);
      }
      return true;
    } catch (SQLException e) {
      error(e);
      return !onErrorStop;
    } finally {
      if (timing) {
        out.println(String.format(Locale.ROOT, "Time: %.3f ms", (System.nanoTime() - start) / 1e6));
      }
      out.flush();
    }
  }

  private boolean transactionCommand(String s) throws SQLException {
    Matcher begin = BEGIN.matcher(s);
    Matcher setTransaction = SET_TRANSACTION.matcher(s);
    Matcher setSession = SET_SESSION.matcher(s);
    if (begin.matches()) {
      if (inTransaction) {
        out.println("WARNING:  there is already a transaction in progress");
      } else {
        connection.setAutoCommit(false);
        inTransaction = true;
        if (begin.group(1) != null) {
          connection.setTransactionIsolation(level(begin.group(1)));
        }
      }
      out.println("BEGIN");
    } else if (COMMIT.matcher(s).matches()) {
      end(true);
      out.println("COMMIT");
    } else if (ROLLBACK.matcher(s).matches()) {
      end(false);
      out.println("ROLLBACK");
    } else if (setTransaction.matches()) {
      if (inTransaction) {
        connection.setTransactionIsolation(level(setTransaction.group(1)));
      } else {
        // as PostgreSQL: the setting is for the current transaction only
        out.println("WARNING:  SET TRANSACTION can only be used in transaction blocks");
      }
      out.println("SET");
    } else if (setSession.matches()) {
      sessionLevel = level(setSession.group(1));
      if (!inTransaction) {
        connection.setTransactionIsolation(sessionLevel);
      }
      out.println("SET");
    } else {
      return false;
    }
    return true;
  }

  /** Ends the transaction and returns to autocommit at the session's level. */
  private void end(boolean commit) throws SQLException {
    if (!inTransaction) {
      out.println("WARNING:  there is no transaction in progress");
      return;
    }
    inTransaction = false;
    SQLException failure = null;
    try {
      if (commit) {
        connection.commit();
      } else {
        connection.rollback();
      }
    } catch (SQLException e) {
      failure = e;
      try {
        connection.rollback(); // so that returning to autocommit cannot commit what failed
      } catch (SQLException r) {
        failure.addSuppressed(r);
      }
    }
    try {
      connection.setAutoCommit(true);
      if (connection.getTransactionIsolation() != sessionLevel) {
        connection.setTransactionIsolation(sessionLevel);
      }
    } catch (SQLException e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static int level(String name) {
    switch (name.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ")) {
      case "READ UNCOMMITTED":
        return Connection.TRANSACTION_READ_UNCOMMITTED;
      case "READ COMMITTED":
        return Connection.TRANSACTION_READ_COMMITTED;
      case "REPEATABLE READ":
      case "SNAPSHOT": // the driver's SNAPSHOT is JDBC's REPEATABLE READ (design section 1)
        return Connection.TRANSACTION_REPEATABLE_READ;
      default:
        return Connection.TRANSACTION_SERIALIZABLE;
    }
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "a SQL shell runs the SQL its user types, through the driver's protections")
  private void execute(String sql) throws SQLException {
    try (Statement st = connection.createStatement()) {
      running.set(st);
      boolean isResult = st.execute(sql);
      while (true) {
        if (isResult) {
          try (ResultSet rs = st.getResultSet()) {
            Printer.print(Printer.read(rs), null, expanded, true, out);
          }
        } else {
          int count = st.getUpdateCount();
          if (count < 0) {
            break;
          }
          String tag = tag(sql, count);
          if (tag.startsWith("CREATE") || tag.startsWith("DROP") || tag.startsWith("ALTER")) {
            completions = null; // the names may have changed
          }
          out.println(tag);
        }
        isResult = st.getMoreResults();
      }
      warnings(st.getWarnings());
    } finally {
      running.set(null);
    }
  }

  /** psql's command tag: {@code INSERT 3}, {@code CREATE TABLE}, {@code SET}. */
  private static String tag(String sql, int count) {
    Matcher m = FIRST_WORDS.matcher(StatementSplitter.withoutLeadingComments(sql));
    if (!m.lookingAt()) {
      return String.valueOf(count);
    }
    String first = m.group(1).toUpperCase(Locale.ROOT);
    switch (first) {
      case "INSERT":
      case "UPDATE":
      case "DELETE":
      case "MERGE":
        return first + " " + count;
      case "CREATE":
      case "DROP":
      case "ALTER":
        return m.group(2) == null ? first : first + " " + m.group(2).toUpperCase(Locale.ROOT);
      default:
        return first;
    }
  }

  private void warnings(@Nullable SQLWarning warning) {
    for (SQLWarning w = warning; w != null; w = w.getNextWarning()) {
      out.println("WARNING:  " + w.getMessage());
    }
  }

  private void error(SQLException e) {
    String message = String.valueOf(e.getMessage());
    // pgjdbc already starts its messages with the severity
    out.println(message.startsWith("ERROR:") ? message : "ERROR:  " + message);
    out.flush();
  }
}
