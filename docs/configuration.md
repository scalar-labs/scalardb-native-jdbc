# Configuration

This page describes how applications connect through the driver and how to configure it: the URL
for each database, the driver's own properties, passthrough, and what the database, its users and
ScalarDB need to be set to.

## Connection URLs

The driver's URL is the vendor driver's URL with `scalardb-native:` after `jdbc:`. The driver
strips it and hands the rest to the vendor driver:

| Database | Driver URL | Vendor URL | Vendor driver |
| --- | --- | --- | --- |
| PostgreSQL | `jdbc:scalardb-native:postgresql://host:5432/db` | `jdbc:postgresql://host:5432/db` | `org.postgresql:postgresql` |
| MySQL | `jdbc:scalardb-native:mysql://host:3306/db` | `jdbc:mysql://host:3306/db` | `com.mysql:mysql-connector-j` |
| MariaDB | `jdbc:scalardb-native:mariadb://host:3306/db` | `jdbc:mariadb://host:3306/db` | `org.mariadb.jdbc:mariadb-java-client` |
| SQL Server | `jdbc:scalardb-native:sqlserver://host:1433;databaseName=db` | `jdbc:sqlserver://host:1433;databaseName=db` | `com.microsoft.sqlserver:mssql-jdbc` |
| Oracle | `jdbc:scalardb-native:oracle:thin:@host:1521/db` | `jdbc:oracle:thin:@host:1521/db` | `com.oracle.database.jdbc:ojdbc11` |

The driver has no dependency on ScalarDB and none on the vendor drivers: the application supplies
its own vendor driver, and the driver finds it through `DriverManager` for the vendor URL. The
driver requires Java 11 or later.

## Driver properties

The driver's own properties start with `scalar.db.native_jdbc.`. Everything else in the URL and the
connect properties goes to the vendor driver unchanged.

| Property | Default | In the URL | What it does |
| --- | --- | --- | --- |
| `scalar.db.native_jdbc.catalog_ttl_millis` | `60000` | yes | How long the driver trusts its [catalog](concepts.md#catalog), in milliseconds |
| `scalar.db.native_jdbc.inflight_wait_millis` | `0` | yes | At READ COMMITTED, how long a write waits for a row [in flight](concepts.md#in-flight) |
| `scalar.db.native_jdbc.metadata_schema` | `scalardb` | no | The schema of ScalarDB's table catalog |
| `scalar.db.native_jdbc.coordinator_namespace` | `coordinator` | no | ScalarDB's namespace for its coordinator table |
| `scalar.db.native_jdbc.allow_no_catalog` | `false` | no | Accept a database without ScalarDB's catalog |
| `scalar.db.native_jdbc.predicate_checks` | `false` | no | Experimental: check a read's `WHERE` against a row in flight before failing |

- `catalog_ttl_millis` is at most `3600000`, one hour. A table newly added to ScalarDB is protected
  after at most this long ([Adding a table while applications
  run](setting-up-tables.md#adding-a-table-to-scalardb-while-applications-run)).
- `inflight_wait_millis`: the write fails with `40001` when the wait ends; `0` fails at once. The
  other levels never wait.
- `metadata_schema` holds `metadata`, and `virtual_tables` for the decoupled layout. Set it when
  ScalarDB uses another schema than `scalardb`.
- `coordinator_namespace` is ScalarDB's `scalar.db.consensus_commit.coordinator.namespace`. No
  statement through [passthrough](sql-support.md#passthrough) may name it.
- `allow_no_catalog`: every table is then ordinary. Without it, a database without ScalarDB's catalog
  is refused at connect with `08004`.
- `predicate_checks`: at SNAPSHOT, and at SERIALIZABLE on PostgreSQL, a read of one table that would
  fail with `40001` on a row in flight in that table first checks whether its `WHERE` matches that
  row.

Set them as connect properties, as for any JDBC driver:

```java
Properties props = new Properties();
props.setProperty("user", "app");
props.setProperty("password", "...");
props.setProperty("scalar.db.native_jdbc.catalog_ttl_millis", "30000");
Connection c = DriverManager.getConnection(
    "jdbc:scalardb-native:postgresql://localhost:5432/warehouse", props);
```

With HikariCP and a `jdbcUrl`, `addDataSourceProperty` passes them the same way:

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:scalardb-native:postgresql://localhost:5432/warehouse");
config.addDataSourceProperty("scalar.db.native_jdbc.catalog_ttl_millis", "30000");
```

In the URL:

- Only `catalog_ttl_millis` and `inflight_wait_millis` may be given there, in the vendor's parameter
  syntax: `?scalar.db.native_jdbc.catalog_ttl_millis=30000`, or
  `;scalar.db.native_jdbc.catalog_ttl_millis=30000` on SQL Server.
- The URL wins over the connect properties.
- The others are taken from the connect properties only, since they could turn the protection off,
  and a URL is often assembled from configuration someone else controls.
- The driver refuses the connection with `08001` when one of the others is in the URL, when a
  `scalar.db.native_jdbc.` key is unknown, or when a value is malformed.

## Passthrough

[Passthrough](concepts.md#passthrough) is not a connect property: it turns the protection off, so it
is set per connection, with `setClientInfo` ([SQL support](sql-support.md#passthrough)).

## Database settings the driver needs

| Engine | Setting | If it is wrong |
| --- | --- | --- |
| MySQL | a strict `sql_mode` (`STRICT_TRANS_TABLES` or `STRICT_ALL_TABLES`; the default has the first) | an `INSERT` into a ScalarDB table is refused, `0AN01` |
| MySQL, MariaDB | `useAffectedRows` false in the vendor driver, its default | connection refused, `08004` |
| SQL Server | `ALTER DATABASE ... SET READ_COMMITTED_SNAPSHOT ON` | connection refused, `08004` |
| SQL Server | `ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON`, since a connection starts at SNAPSHOT (`TRANSACTION_REPEATABLE_READ`), and SERIALIZABLE runs at it | connection refused, `08004` |
| SQL Server | `XACT_ABORT` off for the session, and not among the server's `user options` | connection refused, `08004` |
| SQL Server | `QUOTED_IDENTIFIER`, `ANSI_NULLS`, `ANSI_PADDING` and `ANSI_WARNINGS` on in every session that writes a ScalarDB table's transaction metadata, ScalarDB's and maintenance scripts' included; the JDBC driver's defaults have them on, `sqlcmd` needs `-I` | the write fails, since the setup's in-flight index is a filtered index |

`scalardb-native-tools check` reports the SQL Server settings. For SERIALIZABLE on MySQL, MariaDB
and SQL Server, give ScalarDB's own connections a short lock wait
([MySQL and MariaDB](transactions.md#serializable-on-mysql-and-mariadb),
[SQL Server](transactions.md#serializable-on-sql-server)).

## Database users

- The application's user reads ScalarDB's catalog: it needs SELECT on `scalardb.metadata`, and on
  `scalardb.virtual_tables` when the database has decoupled tables (in the schema `metadata_schema`
  names). Without it, the connection is refused with `42501`.
- It needs the privileges a ScalarDB application has on each ScalarDB table it uses, and on a
  decoupled table's `<table>_tx_metadata`.
- It needs SELECT on each ScalarDB table's read view, which the setup creates: grant it
  with `scalardb-native-tools setup --grant <user>`. Without it, a statement on the table fails
  with `55000`.
- On PostgreSQL before 15, SQL Server and Oracle, the read view runs with its owner's rights, so
  column privileges on the table do not hold through it. Grant the view only to users that may read
  every column of the table. Row-level security that SQL Server's security policies or Oracle's VPD
  put on the table still applies.
- The setup itself, and its `teardown`, need a user that can run DDL on the ScalarDB tables.

## ScalarDB settings

Every ScalarDB application that writes the same tables sets two isolation settings in its own
ScalarDB configuration, such as `scalardb.properties`, to match the driver's level: `scalar.db.consensus_commit.isolation_level` and `scalar.db.jdbc.isolation_level`.
[Isolation levels](transactions.md#isolation-levels) has which values go with each level.
