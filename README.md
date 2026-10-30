# ScalarDB Native JDBC

ScalarDB Native JDBC lets an application use its database's own SQL and transactions on tables that
ScalarDB also manages. ScalarDB's transactions on the same tables keep their guarantees.

It is a JDBC driver that wraps the database's own. Statements on ordinary tables pass through
unchanged. Statements on ScalarDB tables are rewritten so that they read committed data and leave
behind the transaction metadata ScalarDB's transactions check. A transaction through the driver is
one native transaction, ACID across both kinds of table, and isolated from ScalarDB's transactions
at the level set with `setTransactionIsolation`
([Isolation levels](docs/transactions.md#isolation-levels)). It makes no call into ScalarDB.

It is not a ScalarDB client, not a dialect translator, and not a cross-database transaction manager:
one database per connection, and a transaction never spans databases. A ScalarDB transaction that
also touches other databases needs no special handling: the driver only meets the rows that
transaction prepares in this database.

**Start with [Getting started](docs/getting-started.md)**, from an empty database to a program
through the driver.

## Getting it

`<VERSION>` below is the latest release, on [Maven Central](https://central.sonatype.com/artifact/com.scalar-labs/scalardb-native-jdbc) and the
[GitHub releases](https://github.com/scalar-labs/scalardb-native-jdbc/releases).

- **The driver**, `com.scalar-labs:scalardb-native-jdbc` on Maven Central, which the application
  connects through with `jdbc:scalardb-native:postgresql:...`:

  ```kotlin
  implementation("com.scalar-labs:scalardb-native-jdbc:<VERSION>")
  ```

  ```xml
  <dependency>
    <groupId>com.scalar-labs</groupId>
    <artifactId>scalardb-native-jdbc</artifactId>
    <version><VERSION></version>
  </dependency>
  ```

  With Maven or Gradle, its POM brings its dependencies,
  JSQLParser 5.4 and slf4j-api. With the jar alone, put those two on the classpath beside it. Either
  way, add your database's own JDBC driver. If your application also uses JSQLParser, directly or
  through a library such as MyBatis-Plus or PageHelper, your build resolves one version for both:
  pin it to 5.4, the version the driver is tested with, or the driver can fail at run time, for
  example with `NoSuchMethodError`.
- **`snsql`**, the psql-like shell, `scalardb-native-shell-<version>.zip` on the
  [GitHub release](https://github.com/scalar-labs/scalardb-native-jdbc/releases).
- **`scalardb-native-tools`**, which sets ScalarDB tables up for the driver,
  `scalardb-native-tools-<version>.zip` on the same release.

A version not released yet: [Unreleased versions](docs/development-builds.md).

| Java | For |
| --- | --- |
| 11 or later | running the driver, `snsql` and `scalardb-native-tools` |
| 17 or later | Gradle 9, which builds the getting-started example; the example itself runs on Java 11 |
| JDK 25 | building this repository ([unreleased versions](docs/development-builds.md)) |

## Documentation

Read [Getting started](docs/getting-started.md) first, then the guide for the task at hand; the
reference pages are for looking things up.

| Guides | What it covers |
| --- | --- |
| [Getting started](docs/getting-started.md) | from an empty database to a program through the driver, in either of ScalarDB's layouts |
| [Unreleased versions](docs/development-builds.md) | a snapshot of the driver from the Maven Central snapshot repository, or the driver, `snsql` and the tools built from this repository |
| [Setting up tables](docs/setting-up-tables.md) | `scalardb-native-tools`: setting ScalarDB tables up for the driver, checking them, adding a table while applications run |

| Reference | What it covers |
| --- | --- |
| [Concepts](docs/concepts.md) | the terms the other pages use: ScalarDB table, layout, in flight, catalog, the setup, read view, passthrough, SNAPSHOT |
| [Configuration](docs/configuration.md) | the driver's URLs and properties, passthrough, the database settings and users the driver needs |
| [Transactions and isolation levels](docs/transactions.md) | the levels per engine, the ScalarDB settings each needs, conflicts and retries, read-only transactions |
| [SQL support](docs/sql-support.md) | what the driver runs and refuses on ScalarDB tables, per engine; passthrough |
| [Errors](docs/errors.md) | the SQLStates the driver returns, and what to do about each |
| [snsql](docs/snsql.md) | the interactive shell: options, commands, transactions |

| Design | What it covers |
| --- | --- |
| [Design](docs/design.md) | the rules the driver implements and the reasoning behind them |

## Troubleshooting a first run

| You see | Why | What to do |
| --- | --- | --- |
| `No suitable driver found for jdbc:scalardb-native:...` | the driver's jar is not on the classpath | add `com.scalar-labs:scalardb-native-jdbc` ([Getting it](#getting-it)) |
| `08001`, naming a vendor driver | the database's own JDBC driver is not on the classpath | add it ([Connection URLs](docs/configuration.md#connection-urls)) |
| `08004`, no ScalarDB catalog | ScalarDB has created nothing in this database yet, or the URL names another database | create or import a ScalarDB table first, or fix the URL |
| `42501` | this user cannot read ScalarDB's table catalog | grant it ([Database users](docs/configuration.md#database-users)) |
| `55000` | the setup has not been run for the table, or this user cannot read its read view | run `scalardb-native-tools setup --grant <user>` ([Setting up tables](docs/setting-up-tables.md)) |
| `0AN01` | a statement the driver does not run on a ScalarDB table | [What the driver refuses](docs/sql-support.md#what-the-driver-refuses) |

To tell that a connection goes through the driver: its URL starts with `jdbc:scalardb-native:`, and
`SELECT *` on an inline ScalarDB table returns the table's own columns only, without ScalarDB's
`tx_` and `before_` columns. [Errors](docs/errors.md) has every SQLState.

## Compatibility

| Driver version | ScalarDB line | Engines |
| --- | --- | --- |
| 3.20.x | 3.20.x | PostgreSQL, MySQL, MariaDB, SQL Server, Oracle |

The driver version mirrors the ScalarDB line it is certified against; certification is per line, and
the tests run against the line's newest patch.

Tested in 3.20.0 against ScalarDB 3.19.1: PostgreSQL, MySQL, MariaDB and SQL Server at READ
COMMITTED, SNAPSHOT and SERIALIZABLE, and Oracle at READ COMMITTED and SNAPSHOT. ScalarDB 3.20 writes
the same transaction metadata columns and states as 3.19.

The application supplies its database's JDBC driver. 3.20.0 is tested with the servers and JDBC
driver versions below, the newest patch of each line in the range; use a version in the range:

| Database | Server | JDBC driver | Versions |
| --- | --- | --- | --- |
| PostgreSQL | 16 | pgjdbc (`org.postgresql:postgresql`) | 42.2 to 42.7 |
| MySQL | 8.4, also with `lower_case_table_names=1` | MySQL Connector/J (`com.mysql:mysql-connector-j`) | 8.0.33, 8.4, 9.7 and 26.x |
| MariaDB | 11.4 | MariaDB Connector/J (`org.mariadb.jdbc:mariadb-java-client`) | 3.3, and 3.5 to 4.0 |
| SQL Server | 2022 | mssql-jdbc (`com.microsoft.sqlserver:mssql-jdbc`), `.jre11` | 11.2 to 13.6 |
| Oracle | Oracle AI Database 26ai Free (23.26) | ojdbc11 (`com.oracle.database.jdbc:ojdbc11`) | 21.1 to 21.23, and 23.4 to 23.26 except 23.6 |

- pgjdbc before 42.7 fails `Connection.abort()` on Java 24 or later with a `SecurityException`, as
  it does without the driver: use 42.7 there.
- MariaDB Connector/J 3.4 fails one of the tests of writes that conflict with a transaction, and is
  not in the range.

`snsql` and `scalardb-native-tools` bundle pgjdbc 42.7.14, MariaDB Connector/J 3.5.10, which they
use for MySQL too, mssql-jdbc 13.6.0.jre11 and ojdbc11 23.26.3.0.0; not MySQL Connector/J.

## Building

Building the driver and running its tests: [CONTRIBUTING.md](CONTRIBUTING.md).

## License

ScalarDB Native JDBC is licensed under the [Apache License, Version 2.0](LICENSE).
