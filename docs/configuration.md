# Configuration

## Connection URLs

The driver's URL is the vendor driver's URL with `scalardb-native:` after `jdbc:`. The driver
strips it and hands the rest to the vendor driver:

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

## Driver properties

The driver's own properties start with `scalar.db.native_jdbc.`. Everything else in the URL and the
connect properties goes to the vendor driver unchanged.

| Property | Default | In the URL | What it does |
| --- | --- | --- | --- |
| `scalar.db.native_jdbc.catalog_ttl_millis` | `60000` | yes | How long the driver trusts ScalarDB's catalog, the list of ScalarDB tables, in milliseconds; at most `3600000` (one hour). A table newly added to ScalarDB is protected after at most this long ([Adding a table while applications run](setting-up-tables.md#adding-a-table-to-scalardb-while-applications-run)) |
| `scalar.db.native_jdbc.inflight_wait_millis` | `0` | yes | At READ COMMITTED, how long a write waits for a row a ScalarDB transaction is writing before it fails with `40001`; `0` fails at once. Other levels never wait |
| `scalar.db.native_jdbc.metadata_schema` | `scalardb` | no | The schema where ScalarDB keeps its table metadata (`metadata`, and `virtual_tables` for the decoupled layout). Set it when ScalarDB uses another one |
| `scalar.db.native_jdbc.coordinator_namespace` | `coordinator` | no | ScalarDB's namespace for its coordinator table, `scalar.db.consensus_commit.coordinator.namespace`; no statement through [passthrough](sql-support.md#passthrough) may name it |
| `scalar.db.native_jdbc.allow_no_catalog` | `false` | no | Accept a database without ScalarDB's catalog, every table then ordinary. Otherwise such a database is refused at connect with `08004` |
| `scalar.db.native_jdbc.predicate_checks` | `false` | no | Experimental. At SNAPSHOT, and at SERIALIZABLE on PostgreSQL, a read of one table that would fail with `40001` on a row in flight in that table first checks whether its `WHERE` matches that row |

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

Only `catalog_ttl_millis` and `inflight_wait_millis` may also be given in the URL, in the vendor's
parameter syntax (`?scalar.db.native_jdbc.catalog_ttl_millis=30000`, or
`;scalar.db.native_jdbc.catalog_ttl_millis=30000` on SQL Server), and the URL wins over the connect
properties. A URL is often assembled from configuration someone else controls, so the properties
that could turn the protection off are taken from the connect properties only. The driver refuses
the connection with `08001` when one of them is in the URL, when a `scalar.db.native_jdbc.` key is
unknown, or when a value is malformed.

Up to 3.19.0-alpha.1 the keys were `scalardbnative.` and camel case, such as
`scalardbnative.catalogTtlMs`. The driver refuses such a key with `08001`, naming its new name, and
`setClientInfo("scalardbnative.passthrough", ...)` with `SQLClientInfoException`.

## Passthrough

Passthrough sends statements as written, for DDL and maintenance the driver would otherwise refuse
on tables ScalarDB does not manage. It is not a connect property: it turns the protection off, so
it is set per connection, in autocommit, with `setClientInfo("scalar.db.native_jdbc.passthrough",
"true")`, and in `snsql` with `\passthrough on`. [SQL support](sql-support.md#passthrough) has what
it still refuses.

## Database settings the driver needs

| Engine | Setting | If it is wrong |
| --- | --- | --- |
| MySQL | a strict `sql_mode` (`STRICT_TRANS_TABLES` or `STRICT_ALL_TABLES`; the default has the first) | an `INSERT` into a ScalarDB table is refused, `0AN01` |
| MySQL, MariaDB | `useAffectedRows` false in the vendor driver, its default | connection refused, `08004` |
| SQL Server | `ALTER DATABASE ... SET READ_COMMITTED_SNAPSHOT ON` | connection refused, `08004` |
| SQL Server | `ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON`, for `TRANSACTION_REPEATABLE_READ` and `TRANSACTION_SERIALIZABLE` | `setTransactionIsolation` to either fails, `55000` |
| SQL Server | `XACT_ABORT` off for the session, and not among the server's `user options` | connection refused, `08004` |

`scalardb-native-tools check` reports the SQL Server settings. For SERIALIZABLE on MySQL, MariaDB
and SQL Server, give ScalarDB's own connections a short lock wait, as
[Transactions and isolation levels](transactions.md) explains.

## Database users

- The application's user reads ScalarDB's catalog: it needs SELECT on `scalardb.metadata`, and on
  `scalardb.virtual_tables` for decoupled tables (in the schema `metadata_schema` names). Without
  it, the connection is refused with `42501`.
- It needs SELECT on each ScalarDB table's read view, which the setup creates: grant it
  with `scalardb-native-tools setup --grant <user>`. Without it, a statement on the table fails
  with `55000`.
- The setup itself, and its `teardown`, need a user that can run DDL on the ScalarDB tables.

## ScalarDB settings

ScalarDB needs `scalar.db.jdbc.isolation_level=REPEATABLE_READ` to create or import a table the
driver serves. SERIALIZABLE needs more ScalarDB settings, per engine:
[Isolation levels](transactions.md#isolation-levels).
