# Plan: the interactive SQL shell

Status: accepted 2026-10-11 (todo 081), with the four decisions under "Decisions". Milestone 1
done 2026-10-11, milestone 2 done 2026-10-13.

## Goal

Customers know psql and similar REPLs, and the driver is reachable only from JDBC code. Left without a
shell they will use psql, which talks to the database directly: on a ScalarDB table its reads see
prepared records and its writes leave no row metadata. The shell gives them a psql-like prompt that goes
through the driver, on every engine the driver supports.

The command is `snsql`, for ScalarDB Native SQL: short like `psql`, and named for the product, since it
is the driver's shell, supported with this driver only, although most of it is plain JDBC. The Maven
artifact and the distribution zip keep the long name, `scalardb-native-shell`, where findability matters.

SQLLine, the usual JDBC shell, is unmaintained (last release 1.12.0, 2021-11). The shell is our own, a
few hundred lines on JLine, which is maintained and handles line editing, history, completion, masked
input and Windows terminals.

## How close to psql

**The same, on every engine, over plain JDBC.**

- Prompt `db=>`, `db->` on a continuation line, `db=*>` and `db-*>` inside a transaction, as psql.
- A statement ends at `;` and may span lines; quotes and comments are respected, and per engine
  PostgreSQL's dollar quotes, Oracle's `/` after a PL/SQL block and SQL Server's `GO`.
- Aligned result tables, `(n rows)`, the update count; `\x` expanded display, `\timing`.
- `\i file`, `-c sql`, `-f file`, `ON_ERROR_STOP`; history in `~/.snsql_history`.
- Ctrl-C cancels the running statement through `Statement.cancel`; at the prompt it clears the line.
- `\dt`, `\dv`, `\dn`, `\di`, `\d table` (columns, types, nullability, defaults, primary key, indexes,
  foreign keys), `\l`, `\c dbname`, `\conninfo`, `\q`, `\?`, all from `DatabaseMetaData`, with
  psql's patterns (`[schema.]name`, `*`, `?`, an unquoted name folded as the database folds it).
- Server notices printed from the `SQLWarning` chain.

**Different, because of the driver.** The driver refuses `BEGIN`, `SET TRANSACTION` and `SET autocommit`
as SQL with `0AN01` (design section 4, the statement table), since it must know the transaction
boundaries and the level: it wraps a write in autocommit in a transaction of its own (4.4), chooses the
read surface by level (4.2), and re-reads session state on commit. A `BEGIN` sent as SQL would leave the
driver in autocommit, and its next wrapped write would commit the user's transaction halfway. The shell
therefore recognises these statements before sending anything and calls the JDBC method instead:

| Typed | Shell calls |
|---|---|
| `BEGIN`, `START TRANSACTION` | `setAutoCommit(false)` |
| `COMMIT`, `END` | `commit()`, `setAutoCommit(true)` |
| `ROLLBACK`, `ABORT` | `rollback()`, `setAutoCommit(true)` |
| `SET TRANSACTION ISOLATION LEVEL x` | `setTransactionIsolation(x)` for the next transaction |

`BEGIN ISOLATION LEVEL x` and `SET TRANSACTION ISOLATION LEVEL x` hold for the one transaction, as in
PostgreSQL, and the level returns to the session's at its end; outside a transaction `SET TRANSACTION`
only warns. `SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL x` and MySQL's `SET SESSION
TRANSACTION ISOLATION LEVEL x` set the session's level. A failed commit is rolled back before the
connection returns to autocommit, which would otherwise commit it. `ROLLBACK TO SAVEPOINT` and
`SAVEPOINT` go through as SQL, as today. Additions no generic shell has:
`\dt` marks ScalarDB tables in a `ScalarDB` column (`yes`, `yes, not set up`, or `internal` for the data,
metadata and virtual tables ScalarDB keeps), and lists a ScalarDB table stored as a view, as a created
decoupled table is, since users take it for a table; `\d table` says how the driver treats it.
`\passthrough on|off` wraps `setClientInfo("scalardbnative.passthrough", ...)` for DDL and maintenance,
refused by the shell inside a transaction as the driver would, and the prompt shows it, `db=!>`.

**Not matched.** `\d` details JDBC metadata does not carry (check constraints, triggers, partitions,
ACLs, `\dp`, `\du`, `\dx`), which need per-engine catalog queries; `\h`, which embeds PostgreSQL's grammar
help; `\copy`, which in pgjdbc reaches the server through `CopyManager` and so bypasses the rewrite on a
ScalarDB table; psql's context-aware completion, replaced by table and column name completion; psql's
instant start, the JVM takes about half a second.

## Command line

```
snsql [options] [URL]
  URL                 jdbc:scalardb-native:...; or -h/-p/-d with --engine
  -U, --username      user; the password is prompted, unseen, unless SNSQL_PASSWORD or
                      --property password=... is set
  -w, --no-password   never prompt
  -h, -p, -d          host, port, database, as psql; need --engine postgresql|mysql|mariadb|sqlserver|oracle
  -c SQL, -f FILE     run and exit; repeatable, run in order; -f - reads standard input
  -v ON_ERROR_STOP=1  as psql
  --property k=v      a connect property, repeatable; vendor and scalardbnative.* keys alike
```

The engine, which the statement splitter needs, comes from the URL prefix. Input piped in is read as
a script. Exit codes are psql's: 0, 1 for a fatal error, 2 for a failed connection, 3 for a script
stopped by `ON_ERROR_STOP`.

## Install and launch

The zip is attached to the GitHub release during the preview (`RELEASING.md`). It needs Java 11 or
later, found through `JAVA_HOME` or `java` on the `PATH` by Gradle's start scripts, `bin/snsql` and
`bin/snsql.bat`. The scripts pass `--enable-native-access=ALL-UNNAMED` and
`--add-opens=java.base/java.lang=ALL-UNNAMED`, which keep JDK 24 and later, and JDK 11, from warning about
JLine's terminal access on every start, behind `-XX:+IgnoreUnrecognizedVMOptions`, so that JDK 11 to 16
skip the option they do not know; checked on 11, 17, 21 and 25.

```bash
unzip scalardb-native-shell-3.19.0-alpha.1.zip
export PATH="$PWD/scalardb-native-shell-3.19.0-alpha.1/bin:$PATH"
snsql -U alice jdbc:scalardb-native:postgresql://db.example.com:5432/shop
snsql -U alice -h db.example.com -d shop --engine mysql
```

`lib/` holds the driver and four vendor drivers: pgjdbc, MariaDB Connector/J, mssql-jdbc and ojdbc11,
at the versions the integration tests run, with their licences under `licenses/`. MySQL goes through
MariaDB Connector/J, certified for MySQL by the driver (design section 1, URL scheme): snsql appends
`permitMysqlScheme=true` to a `mysql` URL unless the URL has it or Connector/J is on the classpath.
Connector/J is not bundled, its GPL with the FOSS exception does not allow it in a non-FOSS
distribution; a user who wants it drops the jar in `lib/`. Upgrading a bundled driver replaces its jar,
since two versions of one driver on `lib/*` conflict.

When no driver accepts the stripped URL, snsql names the jar to add and where, for example: no driver
for `jdbc:mysql:`; put MySQL Connector/J or MariaDB Connector/J in `<install>/lib`.

## Milestones

Each ends with `./gradlew check` green and a commit.

### Milestone 1: a working prompt

The `shell` module and its entry point; connect with URL or flags and the masked password prompt; the
read loop on JLine with history; the statement splitter; execution and aligned output; the transaction
mapping above and the prompt that shows it; Ctrl-C; `\q`, `\?`, `\conninfo`; `-c`, `-f`,
`ON_ERROR_STOP`; the distribution zip with the bundled drivers and their licences, the `permitMysqlScheme`
append and the missing-driver message.

Tests: the splitter and the transaction mapping as unit tests, per engine; an integration test that runs
a script through `-f` on PostgreSQL, the same code path as the prompt, covering `BEGIN ... ROLLBACK`
leaving no change and `BEGIN ... COMMIT` leaving the row, `ON_ERROR_STOP`, the driver's refusal of
`BEGIN` as SQL, and the missing-driver message.

**Done 2026-10-11.** Differences from the above:

- The transactions run on a plain table, not a ScalarDB table: the driver refuses writes to ScalarDB
  tables until proxy-step milestone 4. The ScalarDB-table case (`BEGIN ... COMMIT` leaving rows ScalarDB
  reads, `ROLLBACK` leaving none) joins milestone 2's tests once that lands.
- Ctrl-C, the password prompt, the prompts and history were checked by hand through a pseudo-terminal
  on PostgreSQL; there is no automated terminal test.
- Not published to Maven (decision 1): the zip is the deliverable, and a Maven publication is added when
  something consumes the jar.
- The zip also carries the driver's and the shell's own dependencies (JSQLParser, SLF4J with
  `slf4j-nop`, JLine, picocli, and pgjdbc's checker-qual), with their licences and an index,
  `licenses/README.txt`.

### Milestone 2: catalog commands and the rest of v1

`\dt`, `\dv`, `\dn`, `\di`, `\d`, `\l`, `\c`; ScalarDB tables marked in `\dt`; `\passthrough`; `\x`,
`\timing`, `\i`; table and column completion.

Tests: the integration script widened to every engine configuration, MySQL through MariaDB Connector/J
included (todo 082).

**Done 2026-10-13.** Differences from the above:

- The integration tests run on every engine but `mysql_lower_case`, which changes nothing in the shell,
  with the drivers the zip bundles; MySQL Connector/J, which Testcontainers needs to start MySQL, is
  deregistered before snsql connects. They share the driver's engine containers, now its test fixtures.
  Each database holds a plain table and a table imported into ScalarDB and set up; the transactions
  still run on the plain one (milestone 1's note).
- Ctrl-C's cancel is tested on every engine; SQL Server's `WAITFOR` runs under `\passthrough`, since the
  driver does not read it, and Oracle cancels a long cross join, having no sleep a query can call.
- `\c` takes a database name on the same server only, made of letters, digits, `_`, `$`, `.` and `-`:
  not psql's `\c dbname user`, which would need a new password, and not a URL, since the session's
  password would go to whatever server a URL in a `\i` script named. The name is checked so that it
  cannot add URL parameters or change the host (`db?socketFactory=...`, `;serverName=...`).
- Completion offers names only, no keywords.
- The prompt shows passthrough as `db=!>`, which psql has no counterpart for.
- Found todo 104: the driver refuses PostgreSQL's `SET search_path TO ...` (the `=` form works).

### Later, when asked for

`\set` and `:var` interpolation, `\watch`, `\e`, `\o`, `\pset`, per-engine `\d` details, `\copy` on plain
tables only.

**A single native binary.** A GraalVM native image makes `snsql` one file that needs no Java and starts
in about 20 ms instead of the JVM's half second, the closest to psql, and the natural form for a Homebrew
formula. JLine supports native images (its `graal` module); pgjdbc and ojdbc ship native-image
configuration, MariaDB Connector/J and mssql-jdbc are to be checked and may need ours. The cost:

- One build per platform, macOS arm64 and x64, Linux x64 and arm64, Windows, in a CI matrix, and the
  integration script run against the native binary as well as the JVM build.
- No drivers added at run time: the bundled four are all there is, so Connector/J as a drop-in goes away.
  MySQL already goes through MariaDB Connector/J, so nothing is lost for the supported engines.
- MariaDB Connector/J compiled into the binary is static linking, and LGPL 2.1 then requires that users
  can relink against a modified library; the zip avoids this by keeping it a separate jar. Legal reviews
  this before a native binary ships.

Prerequisites: that review, and the four drivers proven in a native image. The zip stays as the
distribution for platforms without a binary. An executable jar with a shell stub was considered and
dropped: it still needs Java and starts as slowly, does not run on Windows, and needs the Shadow plugin
to merge the drivers' `META-INF/services/java.sql.Driver` files and strip their signatures.

## Code layout

One package, `com.scalar.db.nativejdbc.shell`: `Main` (picocli options, connecting, the prompt's
parser and completer), `Repl` (the session and the meta-commands), `CatalogCommands` (`\d` and its
kin from `DatabaseMetaData`), `StatementSplitter` (engine-aware, no JDBC), `Printer` (tables and
expanded output).
`Repl` takes typed text and a `PrintWriter`, and the prompt's JLine parser asks it whether the text is
complete, so `-f` and the tests run the same code as the prompt. Depends on the driver, JLine
(`jline-reader`, `jline-terminal`, and `jline-terminal-jni` at run time) and picocli.

## Decisions (accepted 2026-10-11)

1. **Module.** A new `shell` module, published as `com.scalar-labs:scalardb-native-shell` at
   the driver's version. Not in `tools`, which will depend on ScalarDB.
2. **Java.** Java 11, the driver's floor, through the existing `java11-conventions`. JLine 4's
   jars are Java 11 bytecode; milestone 1 confirmed the terminal on JDK 11, so no fallback to JLine 3.
3. **Distribution** (revised 2026-10-11). Gradle's built-in `application` plugin, a zip with
   `bin/snsql` (`applicationName = "snsql"`), `lib/` and `licenses/`, the start script's classpath set to
   `lib/*`. No fat jar, which would need the Shadow plugin. The zip bundles the vendor drivers whose
   licences allow it: pgjdbc (BSD-2-Clause), MariaDB Connector/J (LGPL-2.1-or-later, unmodified, with
   its licence and a link to its source), mssql-jdbc (MIT) and ojdbc11 (since 23.26 the Oracle Free
   Distribution, Hosting, and Use Terms: unmodified, with the licence, no additional fee for its
   distribution or use, which they state still permits distribution "as part of your for-fee product or
   service that adds substantial additional value"). MySQL is reached through MariaDB
   Connector/J, as ScalarDB does; Connector/J (GPLv2 with the Universal FOSS Exception) is not bundled.
4. **Marking ScalarDB tables.** The classification is the catalog's (design section 2: two layouts,
   case rules, `virtual_tables`), and the shell must not repeat it: a small public interface in
   the driver, reached with `unwrap`, that answers whether a table is ScalarDB-managed; its first user is
   the shell, and it is useful to operators' own tooling too. Implemented in milestone 2 as
   `com.scalar.db.nativejdbc.ScalarDbTables`, `kind(schema, table)` by stored name.

## Risks

- **Licences.** The licence terms in decision 3 are read from each driver's POM, not a legal review. Legal signs
  off before the first release that carries the zip, in particular the LGPL notice for MariaDB
  Connector/J; ojdbc11's terms address a paid product directly (decision 3).

- **Statement splitting** is the part most likely to surprise: a `;` inside a PostgreSQL function body,
  an Oracle PL/SQL block, a SQL Server batch. The splitter's unit tests carry one case per construct per
  engine; anything it cannot split, such as a MySQL procedure body (no `DELIMITER` command yet) or a
  PostgreSQL `BEGIN ATOMIC` body, is cut at its first `;` and the engine's error shown.
- **`Statement.cancel` through the proxies.** The proxies forward it; milestone 2's integration test
  cancels a long statement on every engine.
- **Terminals.** JLine falls back to a dumb terminal when it cannot find a native one, losing editing but
  not function; the `-f` path needs no terminal at all.
