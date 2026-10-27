# Transactions and isolation levels

This page describes how transactions through the driver behave beside ScalarDB transactions on the
same tables: which isolation level to use and the ScalarDB settings that must match it, conflicts
and retries, read-only transactions, and each database's specifics.

Transactions through the driver are the database's own: `setAutoCommit(false)`, `commit()` and
`rollback()`, at the level set with `setTransactionIsolation`. That level also decides how they are
isolated from ScalarDB transactions on the same tables.

A row *[in flight](concepts.md#in-flight)* below is a row a ScalarDB transaction has prepared, and
whose commit or rollback is not yet applied to the row. [Concepts](concepts.md) has the other terms.

## Isolation levels

**Set the same level in the driver and in ScalarDB** for applications that write the same tables.
A level that is weaker on one side breaks what the other side's level protects: a driver transaction
at SNAPSHOT can cause write skew that ScalarDB's SERIALIZABLE was to rule out, and one at READ
COMMITTED can overwrite a commit that ScalarDB's SNAPSHOT counted on. The defaults differ: a
driver connection starts at READ COMMITTED, ScalarDB at SNAPSHOT, so set one of them.

| `setTransactionIsolation` | `scalar.db.consensus_commit.isolation_level` | `scalar.db.jdbc.isolation_level` | Engines |
| --- | --- | --- | --- |
| `TRANSACTION_READ_COMMITTED` (the driver's default) | `READ_COMMITTED` | `REPEATABLE_READ` | all five |
| `TRANSACTION_REPEATABLE_READ` (SNAPSHOT) | `SNAPSHOT` (ScalarDB's default) | `REPEATABLE_READ` | all five |
| `TRANSACTION_SERIALIZABLE` | `SERIALIZABLE` | `SERIALIZABLE`, for the driver, not for ScalarDB ([below](#serializable-on-postgresql)) | PostgreSQL |
| `TRANSACTION_SERIALIZABLE` | `SERIALIZABLE` | `REPEATABLE_READ`, with a short lock wait ([below](#serializable-on-mysql-mariadb-and-sql-server)) | MySQL, MariaDB, SQL Server |

`scalar.db.jdbc.isolation_level` is the level of ScalarDB's own connections to the database.
ScalarDB needs at least `REPEATABLE_READ` there to create or import a table the driver serves.

- `TRANSACTION_REPEATABLE_READ` is snapshot isolation, called SNAPSHOT in these docs. SQL Server and
  Oracle map the levels to their own ([SQL Server](#sql-server), [Oracle](#oracle)).
- `TRANSACTION_SERIALIZABLE` is refused on Oracle with `0AN01` ([No SERIALIZABLE on
  Oracle](#no-serializable-on-oracle)).
- `TRANSACTION_READ_UNCOMMITTED` is refused with `0AN01`.

### SERIALIZABLE on PostgreSQL

Without the driver, ScalarDB's connections can stay at `REPEATABLE_READ` even at ScalarDB's
`SERIALIZABLE`: ScalarDB checks its own reads at commit. The driver is what needs `SERIALIZABLE`
there. On PostgreSQL its SERIALIZABLE relies on PostgreSQL's serializable mode (SSI), which detects
conflicts only between transactions that both run at `SERIALIZABLE`. With ScalarDB's connections at
`REPEATABLE_READ`, a ScalarDB write that conflicts with a driver transaction's read goes undetected,
and the two sides can see phantoms and write skew.

### SERIALIZABLE on MySQL, MariaDB and SQL Server

Here the driver locks what its SERIALIZABLE transactions read, until they commit. A lock blocks
ScalarDB's writes whatever level ScalarDB's connections run at, so `scalar.db.jdbc.isolation_level`
stays `REPEATABLE_READ`; `SERIALIZABLE` there would only make ScalarDB's own reads take more locks.
A ScalarDB write that meets such a lock waits for it, up to the database's lock timeout on
ScalarDB's connection, and then fails as a conflict. Give ScalarDB's connections a short lock wait,
so that it fails fast, in the URL of `scalar.db.contact_points`:

- MySQL and MariaDB, whose default is 50 seconds:
  `jdbc:mysql://host:3306/db?sessionVariables=innodb_lock_wait_timeout=1`, in seconds.
- SQL Server, which waits without a limit by default:
  `jdbc:sqlserver://host:1433;databaseName=db;lockTimeout=1000`, in milliseconds.

### No SERIALIZABLE on Oracle

Oracle's own SERIALIZABLE is snapshot isolation, and `TRANSACTION_REPEATABLE_READ` already runs at
it: an application used to Oracle's SERIALIZABLE gets the same with `TRANSACTION_REPEATABLE_READ`.
True serializability needs PostgreSQL's serializable mode or shared and range locks held to commit,
as the driver uses on the other engines; Oracle has neither. JDBC lets a driver run a stricter level
than the one asked for, never a weaker one, so the driver refuses `TRANSACTION_SERIALIZABLE` with
`0AN01`. For tables the driver also writes on Oracle, run ScalarDB at `READ_COMMITTED` or
`SNAPSHOT`, to match.

### Setting the level

- Set it with `setTransactionIsolation` only. `SET`, `RESET` and `set_config` of it are refused. A
  database function of your own that changes it is not detected, so don't write one.
- Change it between transactions. Once the open transaction has run a statement,
  `setTransactionIsolation` fails with `25001`, as pgjdbc's does.

## Conflicts and retries

A transaction through the driver fails with `40001`, and is left rollback-only, when:

- at SNAPSHOT and SERIALIZABLE, a statement reaches a row a ScalarDB transaction is writing;
- a write would lose an update, made by a ScalarDB transaction or by another transaction through the
  driver.

Roll it back and retry it. A query timeout or a cancel during a write also leaves the transaction
rollback-only. On the engines' own errors:

- On MySQL and MariaDB, a lock wait timeout undoes the statement only, unless
  `innodb_rollback_on_timeout` is on.
- On SQL Server, any error that ends the transaction, a deadlock or a conversion error among them,
  leaves it rollback-only too.

When a transaction writes a row, it checks that the row is unchanged since the transaction read it.
Rows read through a RIGHT or FULL JOIN, a derived table, a CTE or a UNION are read committed, but are
not checked this way.

## ScalarDB applications beside the driver

- With ScalarDB at SERIALIZABLE, PostgreSQL may also abort a ScalarDB transaction's read with a
  serialization failure. ScalarDB reports a failed read as `CrudException`, or as
  `ValidationException` or `CommitException` at commit, not as a conflict exception. A ScalarDB
  application retries those too when the cause's SQLState is `40001`.
- At SERIALIZABLE on MySQL, MariaDB and SQL Server, ScalarDB's own connections wait on the locks the
  driver's reads take. Give them a short lock wait
  ([SERIALIZABLE on MySQL, MariaDB and SQL Server](#serializable-on-mysql-mariadb-and-sql-server)).

## Read-only transactions

Declare a transaction that only reads as read-only: `setReadOnly(true)`, or Spring's
`@Transactional(readOnly = true)`.

- At SERIALIZABLE on MySQL, MariaDB and SQL Server, it then reads without the locks the level
  otherwise takes, and stays serializable: its reads neither wait on writers nor hold them off.
- It runs reads only. A write of any table, SQL Server's `SELECT ... INTO`, any other kind of
  statement, and passthrough are refused with `25006`.
- `setReadOnly` fails with `25001` once the transaction has run a statement.

## MySQL and MariaDB

### SERIALIZABLE

The driver runs the connection at REPEATABLE READ, and every SELECT, each subquery included, takes
shared locks held to commit, as the engine's own SERIALIZABLE would. Tables ScalarDB does not manage
are locked too. On ScalarDB tables:

- A read of one ScalarDB table locks its rows one by one, and checks every row its `WHERE` matches,
  regardless of its `LIMIT`, for a ScalarDB write in flight.
- Any other read first locks the in-flight index of each ScalarDB table it reads. That holds off every
  ScalarDB write to the table until commit, and fails with `40001` if the table has a row in flight
  at all.

A ScalarDB transaction that writes the rows read, or inserts into a range read, waits on these locks
up to `innodb_lock_wait_timeout` (50 seconds by default) on ScalarDB's own connections, and then
fails as a conflict. Give ScalarDB's connections a short lock wait
([SERIALIZABLE on MySQL, MariaDB and SQL Server](#serializable-on-mysql-mariadb-and-sql-server)).

At this level the driver refuses:

- a ScalarDB table in a derived table or a set operation, with `0AN01`;
- a statement with a `#` comment, whose clause the comment would swallow, with `0AN01`;
- a SELECT that joins `information_schema` to other tables;
- a locking clause whose MySQL `OF` list leaves out one of the SELECT's tables.

The driver puts the operands of a set operation in parentheses, where each takes a locking clause of
its own. It does not lock `information_schema`, which MySQL refuses to lock; a view over it, `sys`'s
among them, fails with MySQL's error 3550.

### A level set in SQL

A level set in SQL through passthrough is followed for the session. `SET TRANSACTION`, which sets
the next transaction alone, and `SET GLOBAL` are not followed, and the driver clears the former.

## SQL Server

### REPEATABLE READ

`TRANSACTION_REPEATABLE_READ` runs at SQL Server's SNAPSHOT level, which the database must allow
(`ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON`, else `55000`).

- Its update conflict, error 3960, comes as `40001`, even when it arrives with a row rather than at
  the execute.
- `READPAST` is not available at this level.

### SERIALIZABLE

`TRANSACTION_SERIALIZABLE` runs at SNAPSHOT and takes `HOLDLOCK` on every table each read reaches, as
SQL Server's own SERIALIZABLE would. On ScalarDB tables it works as on
[MySQL and MariaDB](#mysql-and-mariadb): a read of one ScalarDB table locks its rows one by one, any
other read locks the in-flight index of each ScalarDB table it reads, and a ScalarDB table in a
derived table or a set operation is refused.

- A write to tables ScalarDB does not manage is held too, its target and every table it reads,
  since SNAPSHOT would read them unlocked.
- A table with a `NOLOCK` or `INDEX` hint is refused, and so is a call of a function of your own,
  whose reads no hint reaches.
- `sys` and `information_schema` are not locked.
- ScalarDB's own connections wait on these locks up to their lock timeout, none by default. Give
  them a short one, `lockTimeout` in their URL.

A read-write transaction that scans a table ScalarDB keeps writing hardly progresses at this level:
its range locks and ScalarDB's prepares wait on and deadlock with each other. The data stays
correct. Declare a transaction that only reads read-only; otherwise read and write by key, or run
such transactions at SNAPSHOT.

### A level set in SQL

A level set in SQL through passthrough is read back from the session. SQL Server's own REPEATABLE
READ, which locks and keeps no snapshot, refuses statements on ScalarDB tables with `0AN01`.

## Oracle

- `TRANSACTION_REPEATABLE_READ` runs at Oracle's SERIALIZABLE, which is snapshot isolation, in a
  transaction. In autocommit it runs at READ COMMITTED, where each statement's snapshot is its own.
- ORA-08177 and ORA-08176 come as `40001` and leave the transaction rollback-only.
- `TRANSACTION_SERIALIZABLE` is refused with `0AN01` ([No SERIALIZABLE on
  Oracle](#no-serializable-on-oracle)). `DatabaseMetaData.supportsTransactionIsolationLevel` reports it unsupported, and REPEATABLE READ
  supported.
- A level set in SQL through passthrough is not followed: the driver sets the connection's level
  again.
