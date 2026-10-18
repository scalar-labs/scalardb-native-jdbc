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

The driver serves ScalarDB tables in the decoupled layout: created, or imported, with
`transaction-metadata-decoupling` set to `true`. ScalarDB itself needs
`scalar.db.jdbc.isolation_level=REPEATABLE_READ` to create or import them, or `SERIALIZABLE` where the
driver runs at SERIALIZABLE on PostgreSQL (see [Isolation levels](#isolation-levels)).

Before the driver serves a table, an operator with DDL privileges runs the setup once. It creates the
in-flight index and the read view, and on an imported table writes the row metadata ScalarDB has not.
It is re-runnable, and is run again after a table is added:

```java
String url = "jdbc:postgresql://host:5432/db";
try (Connection c = DriverManager.getConnection(url, "admin", password)) {
  Catalog catalog = new Catalog(Engine.POSTGRESQL, Catalog.DEFAULT_METADATA_SCHEMA,
      () -> DriverManager.getConnection(url, "admin", password));
  for (Setup.Result r : Setup.run(catalog, c)) {
    System.out.println(r);
  }
}
```

`Catalog`, `Engine` and `Setup` are in `com.scalar.db.nativejdbc.catalog`, `.engine` and `.setup`.
`Setup.run(catalog, c, which, backfillWait, grantees)` chooses the tables, and grants SELECT on the
read views to the application's users. The application then needs the driver jar, JSQLParser 5.4,
slf4j-api and its vendor driver on the classpath, and connects with `jdbc:scalardb-native:postgresql:...`.

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

`TRANSACTION_READ_UNCOMMITTED` is refused with SQLState `0AN01`. The other two levels on SQL Server
and Oracle are accepted, but every statement on a ScalarDB table is refused with `0AN01` there for
now. Oracle will refuse SERIALIZABLE outright, since its SERIALIZABLE is snapshot isolation;
`TRANSACTION_REPEATABLE_READ` will be the level to use there.

## Preview scope

Certified in 3.19.0-alpha.1: PostgreSQL at READ COMMITTED, against the ScalarDB 3.19 line. SNAPSHOT
and SERIALIZABLE on PostgreSQL, and all three levels on MySQL and MariaDB, came after it.

- Reads of ScalarDB tables work on all five engines; writes run on all but Oracle, on SQL Server at
  READ COMMITTED so far.
  ScalarDB's classic layout is refused with SQLState `0AN01`.
- A write is one `INSERT ... VALUES`, `UPDATE` or `DELETE` of one ScalarDB table, in batches too, with
  `RETURNING` and generated keys. `FOR UPDATE` and `FOR SHARE` lock a read of one ScalarDB table.
- On MySQL an INSERT has no `RETURNING`, and gives each key column a literal or a placeholder of the
  column's type, or leaves out an `AUTO_INCREMENT` key (not NULL or 0 for it). It needs a strict `sql_mode`,
  and no `BEFORE INSERT` trigger on the table.
  On MySQL and MariaDB, `useAffectedRows` must stay false, the drivers' default, and a locking read
  takes `FOR UPDATE` or `FOR SHARE` (`LOCK IN SHARE MODE`).
- On SQL Server an INSERT has no `RETURNING` (the driver reads its keys back with `OUTPUT`, which a
  table with a trigger refuses), and `XACT_ABORT` must be off. A locking read takes table hints,
  `UPDLOCK` or `XLOCK` as `FOR UPDATE`, `HOLDLOCK`, `SERIALIZABLE` or `REPEATABLEREAD` as
  `FOR SHARE`, with `ROWLOCK`, `PAGLOCK`, `READPAST` and `NOWAIT`; any other hint on a ScalarDB table,
  `NOLOCK` and `READUNCOMMITTED` among them, written with `WITH` or without, is refused, as is a hint
  the parser cannot read on another table of a statement that reads a ScalarDB table. T-SQL's own
  transaction statements (`BEGIN TRANSACTION`, `SAVE TRANSACTION`) are refused: use the JDBC methods.
  The vendor driver ignores `setReadOnly`, so a read-only connection writes there as any other.
  The setup refuses an imported table keyed by a `datetime`, which ScalarDB's metadata holds as
  `datetime2(3)` and SQL Server does not compare equal for values ending in .003 or .007: use
  `datetime2(3)`.
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
  run as written. A read-only connection cannot write ScalarDB tables (`25006`).
- A statement text the connection has seen costs the driver a few microseconds; a new text costs
  about 0.1 ms, and 0.6-0.8 ms on PostgreSQL, which also asks the server for its plan
  (docs/benchmarks/read-overhead.md).
- Not on Maven Central during the preview: the jar is attached to the GitHub release.

## Compatibility

| Driver version | ScalarDB line | Engines |
| --- | --- | --- |
| 3.19.0-alpha.x | 3.19.x | PostgreSQL first; MySQL and MariaDB, SQL Server, Oracle in that order |

The driver version mirrors the ScalarDB line it is certified against; certification is per line, and
the tests run against the line's newest patch.

## Building

Requires JDK 25 to build; the driver artifact is compiled for Java 11 with `--release`.

```bash
./gradlew check          # unit and integration tests, javadoc, Spotless, SpotBugs with FindSecBugs, Error Prone, NullAway
./gradlew spotlessApply  # format to the Google Java Style Guide
```

The integration tests run database containers through Testcontainers and need Docker. They run
against all engine configurations by default; `./gradlew integrationTest -Dnativejdbc.engines=postgresql,mysql`
selects a subset (`postgresql`, `mysql`, `mysql_lower_case` for MySQL with `lower_case_table_names=1`,
`mariadb`, `sqlserver`, `oracle`). Oracle and SQL Server take a minute or more to start, and SQL Server
runs under emulation on Apple Silicon.

| Path | What |
| --- | --- |
| `driver/` | the driver, published as `com.scalar-labs:scalardb-native-jdbc` |
| `shell/` | `snsql`, the psql-like interactive shell, distributed as `scalardb-native-shell-<version>.zip` (`docs/plans/shell.md`) |
| `tools/` | planned: the setup command and the sweeper, published as `com.scalar-labs:scalardb-native-jdbc-tools`; added with the first tool |
| `docs/design.md` | the design rules |
| `build-logic/`, `gradle/libs.versions.toml` | shared Gradle conventions and the dependency versions |

The build follows the conventions of ScalarDB Saga and the ScalarDB PostgreSQL frontend, and the code
follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
