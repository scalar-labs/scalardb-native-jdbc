# ScalarDB Native JDBC

**Status: private preview. Not released, no license yet.**

ScalarDB Native JDBC is a JDBC driver for a database that holds both ordinary tables and
ScalarDB-managed tables. The application uses that database's own SQL and its own transactions across
both kinds of table, and ScalarDB's transactions on the same tables keep their guarantees.

The driver wraps the database's JDBC driver. Statements on ordinary tables pass through unchanged;
statements on ScalarDB-managed tables are rewritten so that they read committed data and leave behind
the row metadata that ScalarDB's own transactions check. The result is one native transaction, ACID
across ordinary and ScalarDB tables, isolated from concurrent ScalarDB transactions at READ COMMITTED,
SNAPSHOT or SERIALIZABLE, chosen with `setTransactionIsolation`, with no change to ScalarDB and no call
into it. It is not a ScalarDB client, not a dialect translator, and not a cross-database transaction
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
`scalar.db.jdbc.isolation_level=REPEATABLE_READ` (or `SERIALIZABLE`) to create or import them.

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

## Preview scope (3.19.0-alpha.1)

Certified: PostgreSQL at READ COMMITTED, against the ScalarDB 3.19 line.

- Reads of ScalarDB tables work on all five engines; writes run on PostgreSQL only. Any other isolation
  level, and ScalarDB's classic layout, are refused with SQLState `0AN01`.
- A write is one `INSERT ... VALUES`, `UPDATE` or `DELETE` of one ScalarDB table, in batches too, with
  `RETURNING` and generated keys. `FOR UPDATE` and `FOR SHARE` lock a read of one ScalarDB table.
- A write that would lose an update, against a ScalarDB transaction or another driver transaction,
  fails with `40001` and leaves the transaction rollback-only: roll back and retry it. A query
  timeout or a cancel during a write does the same.
- A transaction checks, when it writes a row, that the row is unchanged since it read it. Rows
  returned through a RIGHT or FULL JOIN, a derived table, a CTE or a UNION are read committed, but are
  not checked this way.
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
