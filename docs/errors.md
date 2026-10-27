# Errors

The driver's own errors carry these SQLStates. Errors from the database come through as the vendor
driver reports them, except that SQL Server's update conflict (error 3960) and Oracle's
serialization failures (ORA-08177, ORA-08176) come as `40001`.

| SQLState | When | What to do |
| --- | --- | --- |
| `40001` | The transaction conflicts with a ScalarDB transaction or another transaction through the driver: a write that would lose an update, or, at SNAPSHOT and SERIALIZABLE, a statement that reaches a row a ScalarDB transaction is writing. The transaction is left rollback-only | Roll back and retry the transaction ([Conflicts and retries](transactions.md#conflicts-and-retries)) |
| `0AN01` | A statement or setting the driver does not support, such as a statement on a ScalarDB table it cannot rewrite, or `TRANSACTION_READ_UNCOMMITTED` | Don't retry: change the statement ([SQL support](sql-support.md)) |
| `42000` | A statement names a synonym of a ScalarDB table, or one of the tables ScalarDB keeps for it: a decoupled table's `<table>_scalardb` view, data table or `<table>_tx_metadata` table | Name the ScalarDB table itself |
| `25006` | A write in a read-only transaction or on a read-only connection | Run it in a read-write transaction ([Read-only transactions](transactions.md#read-only-transactions)) |
| `25001` | `setTransactionIsolation` or `setReadOnly` after the open transaction has run a statement | Change them between transactions |
| `55000` | A statement on a ScalarDB table the setup has not been run for, or whose read view this user cannot read. On SQL Server, also `setTransactionIsolation` to REPEATABLE READ or SERIALIZABLE on a database that does not allow snapshot isolation | Run `scalardb-native-tools setup`, with `--grant <user>` ([Setting up tables](setting-up-tables.md)); on SQL Server, `ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON` |
| `57014` | A write was cancelled, or its query timeout passed. The transaction is left rollback-only | Roll back |
| `08001` | The URL or a `scalar.db.native_jdbc.` property is malformed, unknown, or not allowed in the URL; a key of 3.19.0-alpha.1, `scalardbnative.*`; or no vendor driver for the URL is on the class path | Fix the URL or the property ([Configuration](configuration.md)) |
| `08004` | The connection is refused: the database has no ScalarDB catalog; or, on MySQL and MariaDB, `useAffectedRows` is true; or, on SQL Server, `READ_COMMITTED_SNAPSHOT` is off or `XACT_ABORT` is on | Fix the setting ([Database settings](configuration.md#database-settings-the-driver-needs)); for a database without ScalarDB, set `scalar.db.native_jdbc.allow_no_catalog` |
| `42501` | ScalarDB's catalog exists but this user cannot read it | Grant the user SELECT on `scalardb.metadata` and `scalardb.virtual_tables` |

A locking read with `NOWAIT` that finds a row locked fails with the engine's own error for it:
`55P03` on PostgreSQL, error 3572 on MySQL, 1205 on MariaDB, 1222 on SQL Server and ORA-00054 on
Oracle.
