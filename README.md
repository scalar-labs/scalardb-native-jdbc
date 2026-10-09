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
| `tools/` | planned: the setup command and the sweeper, published as `com.scalar-labs:scalardb-native-jdbc-tools`; added with the first tool |
| `docs/design.md` | the design rules |
| `build-logic/`, `gradle/libs.versions.toml` | shared Gradle conventions and the dependency versions |

The build follows the conventions of ScalarDB Saga and the ScalarDB PostgreSQL frontend, and the code
follows the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).
