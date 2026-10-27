# ScalarDB Native JDBC

**Status: private preview. Not released, no license yet.**

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

**Before the release, build all three yourself:** [docs/development-builds.md](docs/development-builds.md).
The driver is not on Maven Central yet, and there is no GitHub release to download from.

- **The driver**, `com.scalar-labs:scalardb-native-jdbc`, which the application connects through with
  `jdbc:scalardb-native:postgresql:...`. With Maven or Gradle, its POM brings its dependencies,
  JSQLParser 5.4 and slf4j-api. With the jar alone, put those two on the classpath beside it. Either
  way, add your database's own JDBC driver.
- **`snsql`**, the psql-like shell, `scalardb-native-shell-<version>.zip`.
- **`scalardb-native-tools`**, which sets ScalarDB tables up for the driver,
  `scalardb-native-tools-<version>.zip`.

After the release, the driver comes from Maven Central and the two zips from the GitHub release.

| Java | For |
| --- | --- |
| 11 or later | running the driver, `snsql` and `scalardb-native-tools` |
| 17 or later | the Gradle build of the getting-started example |
| JDK 25 | building this repository ([development builds](docs/development-builds.md)) |

## Documentation

Read [Getting started](docs/getting-started.md) first, then the guide for the task at hand; the
reference pages are for looking things up.

| Guides | What it covers |
| --- | --- |
| [Getting started](docs/getting-started.md) | from an empty database to a program through the driver, in either of ScalarDB's layouts |
| [Development builds](docs/development-builds.md) | building the driver, `snsql` and the tools yourself before a release, and using them |
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
| 3.20.0-beta.x | 3.20.x | PostgreSQL, MySQL, MariaDB, SQL Server, Oracle |
| 3.19.0-alpha.x | 3.19.x | PostgreSQL |

The driver version mirrors the ScalarDB line it is certified against; certification is per line, and
the tests run against the line's newest patch.

Certified in 3.20.0-beta.1, against the ScalarDB 3.20 line: PostgreSQL, MySQL, MariaDB and SQL
Server at READ COMMITTED, SNAPSHOT and SERIALIZABLE, and Oracle at READ COMMITTED and SNAPSHOT. The
first preview, 3.19.0-alpha.1, was PostgreSQL at READ COMMITTED only.

## Building

Building the driver and running its tests: [CONTRIBUTING.md](CONTRIBUTING.md).
