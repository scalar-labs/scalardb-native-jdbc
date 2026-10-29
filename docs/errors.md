# Errors

This page describes the errors the driver raises, by SQLState: what causes each and what to do
about it. Look up an SQLState here when an application gets an error from the driver.

The driver's own errors carry these SQLStates. Errors from the database come through as the vendor
driver reports them, except that SQL Server's update conflict (error 3960) and Oracle's
serialization failures (ORA-08177, ORA-08176) come as `40001`.

## Retry the transaction

| SQLState | When | What to do |
| --- | --- | --- |
| `40001` | The transaction conflicts with a ScalarDB transaction or another transaction through the driver: a write that would lose an update, or, at SNAPSHOT and SERIALIZABLE, a statement that reaches a row a ScalarDB transaction is writing. The transaction is left rollback-only | Roll back and retry the transaction ([Conflicts and retries](transactions.md#conflicts-and-retries)) |
| `57014` | A write was cancelled, or its query timeout passed. The transaction is left rollback-only | Roll back; retry if the timeout, not a cancel, ended it |

## Change the statement or the call

Retrying does not help: the same statement fails the same way.

| SQLState | When | What to do |
| --- | --- | --- |
| `0AN01` | A statement or setting the driver does not support, such as a statement on a ScalarDB table it cannot rewrite, or `TRANSACTION_READ_UNCOMMITTED` | Change the statement ([SQL support](sql-support.md#what-the-driver-refuses)) |
| `42000` | A statement names a synonym of a ScalarDB table, or one of the tables ScalarDB keeps for it: a decoupled table's `<table>_scalardb` view, data table or `<table>_tx_metadata` table | Name the ScalarDB table itself |
| `25006` | On a read-only connection: a write of a ScalarDB table; at SERIALIZABLE on MySQL, MariaDB and SQL Server, anything but a query; on MySQL, a locking read of a ScalarDB table | Run it in a read-write transaction ([Read-only transactions](transactions.md#read-only-transactions)) |
| `25001` | `setTransactionIsolation`, or a change of `setReadOnly`, after the open transaction has run a statement | Change them between transactions |

## Fix the setup or the connection

| SQLState | When | What to do |
| --- | --- | --- |
| `55000` | A statement on a ScalarDB table [the setup](concepts.md#the-setup) has not been run for | Run `scalardb-native-tools setup` ([Setting up tables](setting-up-tables.md)) |
| `55000` | A statement on a ScalarDB table whose [read view](concepts.md#read-view) this user cannot read | Grant it: `scalardb-native-tools setup --grant <user>` |
| `55000` | On SQL Server, `setTransactionIsolation` to REPEATABLE READ or SERIALIZABLE after the database stopped allowing snapshot isolation | `ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON` |
| `08001` | The URL or a `scalar.db.native_jdbc.` property is malformed, unknown, or not allowed in the URL | Fix the URL or the property ([Configuration](configuration.md)) |
| `08001` | No vendor driver for the URL is on the classpath | Add the database's JDBC driver ([Other databases](getting-started.md#other-databases)) |
| `08004` | The database has no ScalarDB [catalog](concepts.md#catalog) | Connect to the database ScalarDB uses; for a database without ScalarDB, set `scalar.db.native_jdbc.allow_no_catalog` |
| `08004` | On MySQL and MariaDB, `useAffectedRows` is true | Leave it false, the default ([Database settings](configuration.md#database-settings-the-driver-needs)) |
| `08004` | On SQL Server, `READ_COMMITTED_SNAPSHOT` is off | `ALTER DATABASE ... SET READ_COMMITTED_SNAPSHOT ON` |
| `08004` | On SQL Server, `ALLOW_SNAPSHOT_ISOLATION` is off; a connection starts at SNAPSHOT | `ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON` |
| `08004` | On SQL Server, `XACT_ABORT` is on | Turn it off, for the session and in the server's `user options` |
| `42501` | ScalarDB's metadata schema exists but this user cannot read its catalog tables | Grant the user SELECT on `scalardb.metadata`, and on `scalardb.virtual_tables` when the database has decoupled tables ([Database users](configuration.md#database-users)) |
| `42501` | ScalarDB's metadata schema exists but ScalarDB has not created its catalog tables yet | Create a ScalarDB table first |

## The database's own errors

These come from the database, through the driver unchanged:

- **A duplicate key**, from an `INSERT` of a key that exists: `23505` on PostgreSQL, error 1062 on
  MySQL and MariaDB, 2627 on SQL Server and ORA-00001 on Oracle. At READ COMMITTED it is also how
  an upsert's race ends; retry the transaction ([Upserts](sql-support.md#upserts)).
- **A row locked, with `NOWAIT`**: `55P03` on PostgreSQL, error 3572 on MySQL, 1205 on MariaDB, 1222
  on SQL Server and ORA-00054 on Oracle. Retry, or wait for the lock.
- **A lock wait timeout** on MySQL and MariaDB (error 1205) undoes the statement only
  ([Conflicts and retries](transactions.md#conflicts-and-retries)).
