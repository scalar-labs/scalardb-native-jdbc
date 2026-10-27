# ScalarDB Native JDBC

**Status: private preview. Not released, no license yet.**

ScalarDB Native JDBC is a JDBC driver for a database that holds both ordinary tables and
ScalarDB-managed tables. The application uses that database's own SQL and its own transactions across
both kinds of table, and ScalarDB's transactions on the same tables keep their guarantees.

The driver wraps the database's JDBC driver. Statements on ordinary tables pass through unchanged;
statements on ScalarDB-managed tables are rewritten so that they read committed data and leave behind
the row metadata that ScalarDB's own transactions check. The result is one native transaction, ACID
across ordinary and ScalarDB tables, isolated from concurrent ScalarDB transactions at the level chosen with
`setTransactionIsolation` (see [Isolation levels](docs/transactions.md#isolation-levels)), with no call into ScalarDB. It is not a ScalarDB client, not a dialect translator, and not a cross-database transaction
manager.

One database per connection, and a transaction never spans databases. A ScalarDB transaction that also
touches other databases needs no special handling: the driver only meets the rows that transaction
prepares in this database, and those rows follow the same protocol as any other.

To try it, from a database to a program through the driver, follow
[docs/getting-started.md](docs/getting-started.md).

## Getting it

- **The driver**, `com.scalar-labs:scalardb-native-jdbc`. The application then needs the driver jar,
  JSQLParser 5.4, slf4j-api and its vendor driver on the classpath, and connects with
  `jdbc:scalardb-native:postgresql:...`. Not on Maven Central during the preview: the jar is attached
  to the GitHub release.
- **`snsql`**, the psql-like shell, `scalardb-native-shell-<version>.zip` on the GitHub release.
- **`scalardb-native-tools`**, which sets ScalarDB tables up for the driver,
  `scalardb-native-tools-<version>.zip` on the GitHub release.

## Documentation

| Document | What it covers |
| --- | --- |
| [Getting started](docs/getting-started.md) | from an empty database to a program through the driver, in either of ScalarDB's layouts |
| [Setting up tables](docs/setting-up-tables.md) | `scalardb-native-tools`: setting ScalarDB tables up for the driver, checking them, adding a table while applications run |
| [Configuration](docs/configuration.md) | the driver's URLs and properties, passthrough, the database settings and users the driver needs |
| [Transactions and isolation levels](docs/transactions.md) | the levels per engine, the ScalarDB settings each needs, conflicts and retries, read-only transactions |
| [SQL support](docs/sql-support.md) | what the driver runs and refuses on ScalarDB tables, per engine; passthrough |
| [Errors](docs/errors.md) | the SQLStates the driver returns, and what to do about each |
| [snsql](docs/snsql.md) | the interactive shell: options, commands, transactions |
| [Design](docs/design.md) | the rules the driver implements and the reasoning behind them |

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
