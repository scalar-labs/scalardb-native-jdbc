# ScalarDB Native JDBC

**Status: private preview. Not released, no license yet.**

ScalarDB Native JDBC is a JDBC driver for a database that holds both ordinary tables and
ScalarDB-managed tables. The application uses that database's own SQL and its own transactions across
both kinds of table, and ScalarDB's transactions on the same tables keep their guarantees.

The driver wraps the database's JDBC driver. Statements on ordinary tables pass through unchanged;
statements on ScalarDB-managed tables are rewritten so that they read committed data and leave behind
the row metadata that ScalarDB's own transactions check. The result is one native transaction, ACID
across ordinary and ScalarDB tables, isolated from concurrent ScalarDB transactions at the level chosen with
`setTransactionIsolation` (see [Isolation levels](#isolation-levels)), with no call into ScalarDB. It is not a ScalarDB client, not a dialect translator, and not a cross-database transaction
manager.

One database per connection, and a transaction never spans databases. A ScalarDB transaction that also
touches other databases needs no special handling: the driver only meets the rows that transaction
prepares in this database, and those rows follow the same protocol as any other.

The design, with the rules the driver implements and the reasoning behind them, is in
[docs/design.md](docs/design.md).

## Using it

```
jdbc:scalardb-native:postgresql://host:5432/db     -> pgjdbc,              jdbc:postgresql://host:5432/db
jdbc:scalardb-native:mysql://host:3306/db          -> Connector/J,         jdbc:mysql://host:3306/db
jdbc:scalardb-native:mariadb://host:3306/db        -> MariaDB Connector/J, jdbc:mariadb://host:3306/db
jdbc:scalardb-native:sqlserver://host:1433;databaseName=db -> mssql-jdbc,  jdbc:sqlserver://host:1433;databaseName=db
jdbc:scalardb-native:oracle:thin:@host:1521/db     -> ojdbc,               jdbc:oracle:thin:@host:1521/db
```

The driver has no dependency on ScalarDB and none on the vendor drivers: the application supplies
its own vendor driver, and the driver finds it through `DriverManager` for the stripped URL. The
driver requires Java 11 or later.

For a psql-like prompt, use `snsql`, which goes through the driver; psql itself would bypass it. Build
the zip with `./gradlew :shell:distZip` (it lands in `shell/build/distributions/`), unzip it, and run:

```bash
snsql -U alice jdbc:scalardb-native:postgresql://localhost:5432/shop
snsql -U alice -h localhost -d shop --engine postgresql -c "select count(*) from orders"
```

The zip bundles the PostgreSQL, MariaDB (also used for MySQL), SQL Server and Oracle drivers and needs
Java 11 or later. `BEGIN`, `COMMIT` and `ROLLBACK` work as in psql, `\dt` marks ScalarDB tables, and
`\?` lists the commands.

## Getting started

The driver serves ScalarDB tables in both layouts: the decoupled one, created or imported with
`transaction-metadata-decoupling` set to `true`, and the inline one, ScalarDB's default, its metadata
columns in the row. ScalarDB itself needs
`scalar.db.jdbc.isolation_level=REPEATABLE_READ` to create or import them, or `SERIALIZABLE` where the
driver runs at SERIALIZABLE on PostgreSQL (see [Isolation levels](#isolation-levels)).

Before the driver serves a table, an operator with DDL privileges runs the setup once. It creates the
in-flight index and the read view, and on an imported table writes the row metadata ScalarDB has not.
It is re-runnable, and is run again after a table is added. `scalardb-native-tools`, in
`scalardb-native-tools-<version>.zip` with the vendor drivers whose licences allow bundling, runs it:

```sh
export SCALARDB_NATIVE_PASSWORD=...   # it never prompts
bin/scalardb-native-tools setup jdbc:scalardb-native:postgresql://host:5432/db -U admin
bin/scalardb-native-tools check jdbc:scalardb-native:postgresql://host:5432/db -U admin
```

| Action | What it does |
| --- | --- |
| `setup` | sets up every ScalarDB table, or those named with `--table ns.t`; `--grant user` grants SELECT on the read views to the application's users; `--dry-run` prints the statements instead. After setting up a table for the first time it waits out the applications' catalog TTL, `--catalog-ttl` seconds (60), and then says ScalarDB transactions may write it; `--no-wait` prints from when instead |
| `check` | read-only: the database settings the driver needs (SQL Server's), whether each table is set up, and the rows ScalarDB left prepared longer ago than `--lifetime` seconds (15), which the driver cannot settle; a ScalarDB read of the rows, or ScalarDB's recovery, settles them |
| `teardown` | drops the read view and the in-flight index, as ScalarDB's `dropTable` needs on PostgreSQL; `--dry-run` too |

It takes the application's URL or the vendor's, and exits 0 when all is well, 1 when `check` finds
something to fix, and 2 on an error; `--json` prints the outcome as JSON. The same is available in
Java as `Setup.run`, `Setup.plan`, `Setup.teardown` and `Check` in `com.scalar.db.nativejdbc.setup`.

The application then needs the driver jar, JSQLParser 5.4, slf4j-api and its vendor driver on the
classpath, and connects with `jdbc:scalardb-native:postgresql:...`.

**Adding a table to ScalarDB while applications run.** Each driver loads ScalarDB's catalog once and
trusts it for `scalardbnative.catalogTtlMs` (60 seconds by default), across connection pools in the
same JVM. Until then it does not know a newly imported or created ScalarDB table and treats it as a
plain one: its reads can return rows a ScalarDB transaction holds prepared, and its writes bypass the
driver. So, in this order:

1. import or create the table in ScalarDB;
2. run `scalardb-native-tools setup`. For a new table it waits out the catalog TTL, then prints
   `Done: ScalarDB transactions may write ... now.`; give `--catalog-ttl` the longest
   `catalogTtlMs` your applications set, in seconds, rounded up. With `--no-wait`, or `--json`, it
   returns at once and gives the time instead (`writableAt` in JSON). It waits after a run that
   failed too, for the tables it did set up; if it is interrupted while it runs, wait out the TTL
   yourself;
3. only then let ScalarDB transactions write the table, or once every application instance that uses
   the driver on that database has restarted.

A table applications start using with a new release is safe by step 3 when that release is deployed.

## Isolation levels

| `setTransactionIsolation` | Engines | ScalarDB settings |
|---|---|---|
| `TRANSACTION_READ_COMMITTED` | all five | none |
| `TRANSACTION_REPEATABLE_READ` (SNAPSHOT) | PostgreSQL, MySQL, MariaDB | none |
| `TRANSACTION_SERIALIZABLE` | PostgreSQL | `scalar.db.consensus_commit.isolation_level=SERIALIZABLE` and `scalar.db.jdbc.isolation_level=SERIALIZABLE` |
| `TRANSACTION_SERIALIZABLE` | MySQL, MariaDB | `scalar.db.consensus_commit.isolation_level=SERIALIZABLE` |

At SNAPSHOT and SERIALIZABLE, a statement that reaches a row a ScalarDB transaction is writing fails
with `40001`: roll back and retry. Without the ScalarDB settings in its row, SERIALIZABLE between
the driver and ScalarDB is not guaranteed.

With ScalarDB at SERIALIZABLE, PostgreSQL may also abort a ScalarDB transaction's read with a
serialization failure. ScalarDB reports a failed read as `CrudException`, or `ValidationException` or
`CommitException` at commit, not as a conflict exception: a ScalarDB application retries those when
the cause's SQLState is `40001`.

SERIALIZABLE on MySQL and MariaDB runs the connection at REPEATABLE READ, and every SELECT, each
subquery included, takes shared locks held to commit, as the engine's own SERIALIZABLE would; other
tables are locked so too. A ScalarDB transaction writing the rows read, or inserting into a range
read, waits on them up to `innodb_lock_wait_timeout` (50 seconds by default) on ScalarDB's own
connections and then fails as a conflict: give those a short lock wait. A read of one ScalarDB table
row by row locks its rows and checks every row its `WHERE` matches for a ScalarDB write in flight, its
`LIMIT` aside. Any other read first locks the in-flight index of each ScalarDB table it reads, which
holds off every ScalarDB write to the table until commit, and fails with `40001` if the table has a
row in flight at all. A ScalarDB table in a derived table or a set operation is refused with `0AN01`,
and so is a statement with a `#` comment, whose clause the comment would swallow. The driver puts
the operands of a set operation in parentheses, where each takes a clause of its own, and does not
lock `information_schema`, which MySQL refuses to (a view over it, `sys`'s among them, fails there
with MySQL's error 3550); a SELECT joining it to other tables is refused,
as is a locking clause whose MySQL `OF` list leaves one of the SELECT's tables out. A level set in
SQL through passthrough is followed for the session; `SET TRANSACTION` for the next transaction
alone and `SET GLOBAL` are not, and the driver clears the former.

`TRANSACTION_READ_UNCOMMITTED` is refused with SQLState `0AN01`. On SQL Server
`TRANSACTION_REPEATABLE_READ` runs at SQL Server's SNAPSHOT level, which the database must allow
(`ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON`, else `55000`); its update conflict, error
3960, comes as `40001`, even when it arrives with a row rather than at the execute, and `READPAST`
is not available there. A level set in SQL through passthrough is read back from the session; SQL
Server's own REPEATABLE READ, which locks and keeps no snapshot, refuses statements on ScalarDB
tables with `0AN01`. SERIALIZABLE on SQL Server runs at SNAPSHOT and takes `HOLDLOCK` on every table
each read reaches, as SQL Server's own SERIALIZABLE would; otherwise as the MySQL paragraph above
says: a read of one ScalarDB table row by row locks its rows, any other locks the in-flight index of
each ScalarDB table it reads, and a ScalarDB table in a derived table or a set operation is refused.
A write to tables ScalarDB does not manage is held too, its target and every table it reads, since
SNAPSHOT would read them unlocked. A table with a `NOLOCK` or `INDEX` hint, and a call of a function
of your own, whose reads no hint reaches, are refused at that level; `sys` and `information_schema`
are not locked. ScalarDB's own connections wait on these locks up to their lock timeout (none by
default): give them a short one, `lockTimeout` in the URL.

On Oracle `TRANSACTION_REPEATABLE_READ` runs at Oracle's SERIALIZABLE, which is snapshot isolation,
in a transaction, and at READ COMMITTED in autocommit, where a statement's snapshot is its own;
ORA-08177 and ORA-08176 come as `40001` and leave the transaction rollback-only.
`TRANSACTION_SERIALIZABLE` is refused there with `0AN01`, and
`DatabaseMetaData.supportsTransactionIsolationLevel` reports it unsupported, REPEATABLE READ
supported. A level set in SQL through passthrough on Oracle is not followed: the connection's is set
again.

## Preview scope

Certified in 3.20.0-beta.1, against the ScalarDB 3.20 line: PostgreSQL, MySQL, MariaDB and SQL
Server at READ COMMITTED, SNAPSHOT and SERIALIZABLE, and Oracle at READ COMMITTED and SNAPSHOT. The
first preview, 3.19.0-alpha.1, was PostgreSQL at READ COMMITTED only.

- Reads and writes of ScalarDB tables work on all five engines, in both of ScalarDB's layouts. In
  the inline layout, the metadata columns in the row, they are kept from the application: `SELECT *`
  and `RETURNING *` give the data columns, an `INSERT` without a column list gives its values to
  the user's columns (refused where they would reach a metadata column), and a statement naming a
  `tx_` or `before_` column ScalarDB keeps is refused with `0AN01`.
- Declare transactions that only read as read-only (`setReadOnly(true)`, Spring's
  `@Transactional(readOnly = true)`). At SERIALIZABLE on MySQL, MariaDB and SQL Server such a
  transaction reads without the locks the level otherwise takes, and stays serializable: its reads
  neither wait on writers nor hold them off. It runs reads only: a write of any table, SQL Server's
  `SELECT ... INTO`, another kind of statement and passthrough are refused with `25006`, and
  `setReadOnly` fails with `25001` once the transaction has run a statement.
- On SQL Server at SERIALIZABLE, a read-write transaction that scans a table ScalarDB keeps writing
  hardly progresses: its range locks and ScalarDB's prepares wait on and deadlock with each other.
  The data stays correct. Declare a transaction that only reads read-only; otherwise read and write
  by key there, or run such transactions at SNAPSHOT.
- A write is one `INSERT ... VALUES`, `UPDATE` or `DELETE` of one ScalarDB table, in batches too, with
  `RETURNING` and generated keys. `FOR UPDATE` and `FOR SHARE` lock a read of one ScalarDB table.
- On MySQL an INSERT has no `RETURNING`, and gives each key column a literal or a placeholder of the
  column's type, or leaves out an `AUTO_INCREMENT` key (not NULL or 0 for it). It needs a strict `sql_mode`,
  and no `BEFORE INSERT` trigger on the table.
  On MySQL and MariaDB a `CHAR` column of a ScalarDB table reads as `VARCHAR`. A `BOOLEAN` column
  that a SELECT returns as it is, by name or through `*`, reads as on the database; one returned
  through a derived table, a CTE or a `*` that also covers a plain table reads as `TINYINT`, whose
  `getObject` is an `Integer`: read it with `getBoolean`.
  On MySQL and MariaDB, `useAffectedRows` must stay false, the drivers' default, and a locking read
  takes `FOR UPDATE` or `FOR SHARE` (`LOCK IN SHARE MODE`).
- On SQL Server an INSERT has no `RETURNING` (the driver reads its keys back with `OUTPUT`, which a
  table with a trigger refuses), and `XACT_ABORT` must be off. A locking read takes table hints,
  `UPDLOCK` or `XLOCK` as `FOR UPDATE`, `HOLDLOCK`, `SERIALIZABLE` or `REPEATABLEREAD` as
  `FOR SHARE`, with `ROWLOCK`, `PAGLOCK`, `READPAST` and `NOWAIT`; any other hint on a ScalarDB table,
  `NOLOCK` and `READUNCOMMITTED` among them, written with `WITH` or without, is refused, as is a hint
  the parser cannot read on another table of a statement that reads a ScalarDB table. T-SQL's own
  transaction statements (`BEGIN TRANSACTION`, `SAVE TRANSACTION`) are refused: use the JDBC methods.
  The setup refuses an imported table keyed by a `datetime`, which ScalarDB's metadata holds as
  `datetime2(3)` and SQL Server does not compare equal for values ending in .003 or .007: use
  `datetime2(3)`.
- On Oracle an INSERT has no `RETURNING` (`RETURNING ... INTO` does not parse): the keys are read back
  by the values the statement gives them, as on MySQL, or come from an `IDENTITY` key the INSERT
  leaves out, which a multi-row `VALUES` cannot return (Oracle's ORA-63809); no `BEFORE INSERT`
  or `COMPOUND` trigger on the table. A locking read takes `FOR UPDATE`, with `OF` a column, `NOWAIT` and `SKIP
  LOCKED`, not `WAIT n`, and no row limit, which Oracle refuses with it; Oracle has no `FOR SHARE`,
  and the driver refuses it. A synonym of a table ScalarDB does not manage, `DUAL` among them, reads
  as that table; one of a ScalarDB table is refused. `ALTER SESSION SET ISOLATION_LEVEL` is refused:
  use `setTransactionIsolation`.
- A name holding an invisible formatting character, such as a zero-width space, is refused: SQL Server
  reads it as whitespace where the parser keeps it in the name.
- `setTransactionIsolation` fails with `25001` once the open transaction has run a statement, as
  pgjdbc's does: change the level between transactions.
- A write that would lose an update, against a ScalarDB transaction or another driver transaction,
  fails with `40001` and leaves the transaction rollback-only: roll back and retry it. A query
  timeout or a cancel during a write does the same. A MySQL or MariaDB lock wait timeout undoes the
  statement only, unless `innodb_rollback_on_timeout` is on. On SQL Server any error that ends the
  transaction, a deadlock or a conversion error among them, leaves it rollback-only too.
- A transaction checks, when it writes a row, that the row is unchanged since it read it. Rows
  returned through a RIGHT or FULL JOIN, a derived table, a CTE or a UNION are read committed, but are
  not checked this way.
- Set the isolation level with `setTransactionIsolation` only. `SET`, `RESET` and `set_config` of
  it are refused; a database function of your own that changes it is not detected, so don't.
- A statement on a ScalarDB table that the driver cannot parse or rewrite is refused with `0AN01`, not
  run as written. One the parser cannot read is sent as written, in a transaction too, when its words
  name no ScalarDB table (comments and literals included) and it is a single query, DML statement, or
  DDL of a table, index, view, schema, sequence or domain; not on SQL Server, nor at SERIALIZABLE on
  MySQL and MariaDB. A read-only connection cannot write ScalarDB tables (`25006`).
- Passthrough, `setClientInfo("scalardbnative.passthrough", "true")` in autocommit, sends statements as
  written, for what the driver refuses on tables ScalarDB does not manage. A statement that names a
  ScalarDB table, or ScalarDB's own schema or coordinator namespace, is refused there with `0AN01`,
  wherever the name stands, a comment or a plain table of the same name in another schema included.
  Set `scalardbnative.coordinatorNamespace` in the connect properties when ScalarDB's
  `scalar.db.consensus_commit.coordinator.namespace` is not `coordinator`. DDL or maintenance
  on a ScalarDB table goes through a connection of the database's own driver, with ScalarDB's
  transactions on the table stopped.
- Don't create, over a ScalarDB table: a view; a trigger, on it or on another table that writes it; a
  procedure or function that reads or writes it; a foreign key on it with `ON DELETE` or `ON UPDATE`
  `CASCADE`, `SET NULL` or `SET DEFAULT`. A statement through one of them, or the database itself,
  reaches the table without ScalarDB's protocol, outside passthrough too: the driver sees only the
  name the statement uses. The tools' `check` reports those it finds, and `setup` warns of them; SQL
  built at run time is not seen.
- A statement text the connection has seen costs the driver a few microseconds; a new text costs
  about 0.1 ms, and 0.6-0.8 ms on PostgreSQL, which also asks the server for its plan
  (docs/benchmarks/read-overhead.md).
- Not on Maven Central during the preview: the jar is attached to the GitHub release.

## Compatibility

| Driver version | ScalarDB line | Engines |
| --- | --- | --- |
| 3.20.0-beta.x | 3.20.x | PostgreSQL, MySQL, MariaDB, SQL Server, Oracle |
| 3.19.0-alpha.x | 3.19.x | PostgreSQL |

The driver version mirrors the ScalarDB line it is certified against; certification is per line, and
the tests run against the line's newest patch.

## Building

Requires JDK 25 to build; the driver artifact is compiled for Java 11 with `--release`.

```bash
./gradlew check          # unit and integration tests, javadoc, Spotless, SpotBugs with FindSecBugs, Error Prone, NullAway
./gradlew spotlessApply  # format to the Google Java Style Guide
```

The integration tests run database containers through Testcontainers and need Docker. `check` runs
them in one task per engine configuration (`integrationTestPostgresql`, `integrationTestOracle`,
`integrationTestMysql`, `integrationTestMysqlLowerCase` for MySQL with `lower_case_table_names=1`,
`integrationTestMariadb`, `integrationTestSqlserver`), three at a time, each with its own container;
`-Pnativejdbc.parallelEngines=2` lowers that where Docker has less memory.
`-Dnativejdbc.engines=postgresql,mysql` selects a subset (`none` for no integration tests at all),
and `./gradlew :driver:integrationTest --tests '*ReadIntegrationTest*' -Dnativejdbc.engines=oracle`
runs some tests on the selected engines in one task. `-Dnativejdbc.postgresql.url=...`, with
`.username` and `.password`, runs an engine against an existing database instead of a container (the
requirements are in `testing.Engines`). Oracle and SQL Server take a minute or more to start, and SQL
Server runs under emulation on Apple Silicon.

| Path | What |
| --- | --- |
| `driver/` | the driver, published as `com.scalar-labs:scalardb-native-jdbc` |
| `shell/` | `snsql`, the psql-like interactive shell, distributed as `scalardb-native-shell-<version>.zip` (`docs/plans/shell.md`) |
| `tools/` | `scalardb-native-tools`, the setup, its check and its teardown, distributed as `scalardb-native-tools-<version>.zip` |
| `samples/` | `crud-jdbc` and `crud-spring-data-jdbc`: small applications through the driver, each run as an acceptance test on every engine and both layouts beside ScalarDB's own transactions |
| `docs/design.md` | the design rules |
| `build-logic/`, `gradle/libs.versions.toml` | shared Gradle conventions and the dependency versions |

The build follows the conventions of ScalarDB Saga and the ScalarDB PostgreSQL frontend, and the code
follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
