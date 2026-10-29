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
COMMITTED can overwrite a commit that ScalarDB's SNAPSHOT counted on. The defaults are the same: a
driver connection starts at SNAPSHOT, ScalarDB's default level. At SNAPSHOT a transaction that writes
a row another transaction changed since its snapshot fails with `40001` and is retried, and a long
transaction keeps its snapshot; READ COMMITTED gives fresh reads per statement and lock waits on such
writes instead, at the per-statement cost of the driver's READ COMMITTED forms ([Why READ
COMMITTED costs more in the driver](#why-read-committed-costs-more-in-the-driver)).

| `setTransactionIsolation` | `scalar.db.consensus_commit.isolation_level` | `scalar.db.jdbc.isolation_level` | Engines |
| --- | --- | --- | --- |
| `TRANSACTION_READ_COMMITTED` | `READ_COMMITTED` | `REPEATABLE_READ` | all five |
| `TRANSACTION_REPEATABLE_READ` (SNAPSHOT, the driver's default) | `SNAPSHOT` (ScalarDB's default) | `REPEATABLE_READ` | all five |
| `TRANSACTION_SERIALIZABLE` | `SERIALIZABLE` | `SERIALIZABLE`, for the driver, not for ScalarDB | [PostgreSQL](#serializable-on-postgresql) |
| `TRANSACTION_SERIALIZABLE` | `SERIALIZABLE` | `REPEATABLE_READ`, with a short lock wait | [MySQL and MariaDB](#serializable-on-mysql-and-mariadb), [SQL Server](#serializable-on-sql-server) |

`scalar.db.jdbc.isolation_level` is the level of ScalarDB's own connections to the database.
ScalarDB needs at least `REPEATABLE_READ` there to create or import a table the driver serves.

- `TRANSACTION_REPEATABLE_READ` is snapshot isolation, called SNAPSHOT in these docs. SQL Server and
  Oracle map the levels to their own ([SQL Server](#sql-server), [Oracle](#oracle)).
- `TRANSACTION_SERIALIZABLE` is refused on Oracle with `0AN01` ([No SERIALIZABLE on
  Oracle](#no-serializable-on-oracle)).
- `TRANSACTION_READ_UNCOMMITTED` is refused with `0AN01`.

### Choosing a level

| Use | For | What it costs |
| --- | --- | --- |
| SNAPSHOT, the default | Most transactions, read-write and read-only | A transaction fails with `40001` when it reaches a row in flight, or writes a row another transaction changed since its snapshot; retry it |
| SERIALIZABLE | Transactions whose correctness rests on rows they read but don't write (write skew) | More `40001` failures than SNAPSHOT; ScalarDB must run at `SERIALIZABLE` too; not on Oracle |
| READ COMMITTED, read-only | Reads that must not fail on tables ScalarDB is writing: lookups, listings, dashboards | Each statement sees what is committed when it runs, so two statements can disagree, a total and its details for example |
| READ COMMITTED, read-write | Writes that compute from the row they write, in one statement, on rows many transactions update: `SET qty = qty - 1`, `SET balance = balance + ?` | ScalarDB must run at `READ_COMMITTED` too; a write computed from other rows read in earlier statements can combine values that were never true together |

**SNAPSHOT** gives every statement of a transaction the same point in time. A ScalarDB transaction's
in-flight rows are part of that point, and the transaction cannot tell what they will become, so a
statement that reaches one fails. A write of a row another transaction committed after the snapshot
fails too, as in the database's own snapshot isolation.

**READ COMMITTED reads never fail on in-flight rows.** They return the row's last committed value,
which ScalarDB keeps as the row's before image. This is the main reason to use the level. A
transaction that only reads cannot break what ScalarDB's level protects, so a read-only transaction
can run at READ COMMITTED even when ScalarDB and the driver's other transactions run at SNAPSHOT.
Declare it [read-only](#read-only-transactions).

**READ COMMITTED writes apply to the latest committed row.** For example, take a balance of 100. A
transaction runs `UPDATE account SET balance = balance - 30 WHERE id = 1` after another transaction
has committed a deposit of 50 to the same account:

- at SNAPSHOT, the update fails with `40001`, because the row changed after the transaction's
  snapshot;
- at READ COMMITTED, the update applies to the latest value: 150 − 30 = 120.

A write that carries a value the transaction read earlier still fails if the row has changed since,
because it would lose the other transaction's update: read a balance of 100, then `SET balance = 70`,
fails with `40001`. This holds for rows read with a SELECT of their own table; [Conflicts and
retries](#conflicts-and-retries) lists the reads that are not checked. What READ COMMITTED does not protect is a write computed from *other* rows read
in separate statements. Each statement sees its own moment, so a transaction can read row A before a
ScalarDB transaction commits and row B after, and write a value computed from an old A and a new B.
Use SNAPSHOT for such a transaction.

At READ COMMITTED, a write that reaches a row in flight fails with `40001` by default, as at
SNAPSHOT. With [`scalar.db.native_jdbc.inflight_wait_millis`](configuration.md) set, it waits for
the row to settle instead.

### Why READ COMMITTED costs more in the driver

In the database alone, READ COMMITTED is the cheapest level: the database keeps old row versions
and hands each statement the latest committed one. ScalarDB's in-flight rows are different. A
ScalarDB transaction writes its new values into the row at prepare and keeps the old values in the
row's [transaction metadata](concepts.md#transaction-metadata). To the database that row is
committed, so it cannot hand the driver the old values; the driver has to find them itself.

- **Reads.** At SNAPSHOT, the driver only checks whether the statement reaches a row in flight, and
  fails if it does: usually one look at the [in-flight index](concepts.md#in-flight-index), and
  then your statement as written. At READ COMMITTED it must return the old values of a row in
  flight instead of failing, so it reads committed rows from the table and in-flight rows' old
  values from the metadata. It rewrites the statement into copies joined by `UNION ALL`, one for
  each combination of committed and in-flight tables: two copies for one table, four for a join of
  two. Where a quick check shows nothing in flight in the statement's key range, it runs the
  statement as written instead. When nothing is in flight, the extra copies find no rows, but the
  database still parses, plans and starts them.
- **Writes.** A SNAPSHOT transaction's view never changes, so once the driver finds a table with no
  row in flight, it remembers this until the transaction ends: every later read and write of that
  table runs as your statement as written, with the transaction metadata the write needs. A READ
  COMMITTED transaction sees a new moment at every statement, so the driver cannot remember
  anything. Each write reads and checks the metadata of the rows it writes, in the same statement
  as the write.

Both costs remain when no ScalarDB transaction is running, because the driver can only know that
by checking. A write costs noticeably more at READ COMMITTED than at SNAPSHOT, and TPC-C commits
fewer transactions at READ COMMITTED on both PostgreSQL and Oracle, in both layouts; the benchmark
pages have the figures ([PostgreSQL](benchmarks/benchbase-postgresql-aws.md),
[Oracle](benchmarks/benchbase-oracle-aws.md)). Choose READ COMMITTED
for its behaviour, reads that never fail, and not for speed.

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

Roll it back and retry it; [Getting started](getting-started.md#5-run-the-warehouse-application)
shows a retry loop. A query timeout or a cancel during a write also leaves the transaction
rollback-only. On the engines' own errors:

- On MySQL and MariaDB, a lock wait timeout undoes the statement only, unless
  `innodb_rollback_on_timeout` is on.
- On SQL Server, any error that ends the transaction, a deadlock or a conversion error among them,
  leaves it rollback-only too.

When a transaction writes a row, it checks that the row is unchanged since the transaction read it.
Rows read only through a subquery, a grouped or `DISTINCT` read, a RIGHT or FULL JOIN, a derived
table, a CTE or a UNION are read committed, but are not checked this way: at READ COMMITTED, a write
based on such a read can overwrite a change made after it. Read the rows you will write with a
SELECT of their own table, or lock them first with `SELECT ... FOR UPDATE`.

## Read-only transactions

Declare a transaction that only reads as read-only: `setReadOnly(true)`, or Spring's
`@Transactional(readOnly = true)`.

- A write of a ScalarDB table is refused with `25006`, at every level and in autocommit too. A write
  of an ordinary table is left to the database's own driver, which may refuse it with its own
  read-only error (pgjdbc does, in a transaction).
- At SERIALIZABLE on MySQL, MariaDB and SQL Server, it reads without the locks the level otherwise
  takes, and stays serializable: its reads neither wait on writers nor hold them off. For that it
  runs reads only: a write of any table, SQL Server's `SELECT ... INTO`, a locking hint on SQL
  Server, any statement other than a query or a session setting, a statement the driver cannot
  parse, and passthrough are refused with `25006`.
- On MySQL, a locking read of a ScalarDB table, `FOR UPDATE` or `FOR SHARE`, is refused with
  `25006`: MySQL Connector/J refuses the savepoints it runs under. Leave such a transaction
  read-write.
- Changing `setReadOnly` fails with `25001` once the transaction has run a statement. Setting it to
  the value it already has does not.

## PostgreSQL

### SERIALIZABLE on PostgreSQL

Set `scalar.db.jdbc.isolation_level=SERIALIZABLE` in every ScalarDB application that writes the
tables. Without the driver, ScalarDB's connections can stay at `REPEATABLE_READ` even at ScalarDB's
`SERIALIZABLE`: ScalarDB checks its own reads at commit. The driver is what needs `SERIALIZABLE`
there. Its SERIALIZABLE relies on PostgreSQL's serializable mode (SSI), which detects conflicts only
between transactions that both run at `SERIALIZABLE`. With ScalarDB's connections at
`REPEATABLE_READ`, a ScalarDB write that conflicts with a driver transaction's read goes undetected,
and the two sides can see phantoms and write skew.

PostgreSQL may then also abort a ScalarDB transaction's read with a serialization failure. ScalarDB
reports a failed read as `CrudException`, or as `ValidationException` or `CommitException` at
commit, not as a conflict exception. A ScalarDB application retries those too when the cause's
SQLState is `40001`.

## MySQL and MariaDB

### SERIALIZABLE on MySQL and MariaDB

The driver runs the connection at REPEATABLE READ, and every SELECT, each subquery included, takes
shared locks held to commit, as the engine's own SERIALIZABLE would. Tables ScalarDB does not manage
are locked too. On ScalarDB tables:

- A read of one ScalarDB table locks its rows one by one, and checks every row its `WHERE` matches,
  regardless of its `LIMIT`, for a ScalarDB write in flight.
- Any other read first locks the in-flight index of each ScalarDB table it reads. That holds off every
  ScalarDB write to the table until commit, and fails with `40001` if the table has a row in flight
  at all.

A lock blocks ScalarDB's writes whatever level ScalarDB's connections run at, so
`scalar.db.jdbc.isolation_level` stays `REPEATABLE_READ`; `SERIALIZABLE` there would only make
ScalarDB's own reads take more locks. A ScalarDB transaction that writes the rows read, or inserts
into a range read, waits on these locks up to `innodb_lock_wait_timeout`, 50 seconds by default, on
its own connection, and then fails as a conflict. Give ScalarDB's connections a short lock wait, so
that it fails fast, in the URL of `scalar.db.contact_points`, in seconds:

```text
jdbc:mysql://host:3306/db?sessionVariables=innodb_lock_wait_timeout=1
```

At this level the driver refuses:

- a ScalarDB table in a derived table or a set operation, with `0AN01`;
- a statement with a `#` comment, whose clause the comment would swallow, with `0AN01`;
- a SELECT that joins `information_schema` to other tables;
- a locking clause whose MySQL `OF` list leaves out one of the SELECT's tables.

The driver puts the operands of a set operation in parentheses, where each takes a locking clause of
its own. It does not lock `information_schema`, which MySQL refuses to lock; a view over it, `sys`'s
among them, fails with MySQL's error 3550.

### A level set in SQL on MySQL and MariaDB

A level set in SQL through passthrough is followed for the session. `SET TRANSACTION`, which sets
the next transaction alone, and `SET GLOBAL` are not followed, and the driver clears the former.

## SQL Server

### REPEATABLE READ on SQL Server

`TRANSACTION_REPEATABLE_READ` runs at SQL Server's SNAPSHOT level, which the database must allow
(`ALTER DATABASE ... SET ALLOW_SNAPSHOT_ISOLATION ON`), and since a connection starts at SNAPSHOT
a database without it is refused at connect with `08004`.

- Its update conflict, error 3960, comes as `40001`, even when it arrives with a row rather than at
  the execute.
- `READPAST` is not available at this level.

### SERIALIZABLE on SQL Server

`TRANSACTION_SERIALIZABLE` runs at SNAPSHOT, which the database must allow as for REPEATABLE READ,
and takes `HOLDLOCK` on every table each read reaches, as SQL Server's own SERIALIZABLE would. On
ScalarDB tables it works as on [MySQL and MariaDB](#serializable-on-mysql-and-mariadb): a read of one
ScalarDB table locks its rows one by one, any other read locks the in-flight index of each ScalarDB
table it reads, and a ScalarDB table in a derived table or a set operation is refused.

- A write to tables ScalarDB does not manage is held too, its target and every table it reads,
  since SNAPSHOT would read them unlocked.
- A table with an `INDEX` hint, or with an isolation hint that holds no lock (`NOLOCK`,
  `READUNCOMMITTED`, `READCOMMITTED`, `READCOMMITTEDLOCK`, `READPAST`, `SNAPSHOT`), is refused, and
  so is a call of a function of your own, whose reads no hint reaches.
- `sys` and `information_schema` are not locked.

As on MySQL and MariaDB, `scalar.db.jdbc.isolation_level` stays `REPEATABLE_READ`. ScalarDB's own
connections wait on these locks up to their lock timeout, without a limit by default. Give them a
short one in the URL of `scalar.db.contact_points`, in milliseconds:

```text
jdbc:sqlserver://host:1433;databaseName=db;lockTimeout=1000
```

A read-write transaction that scans a table ScalarDB keeps writing hardly progresses at this level:
its range locks and ScalarDB's prepares wait on and deadlock with each other. The data stays
correct. Declare a transaction that only reads as read-only. For one that writes, read and write by
key, or run it at SNAPSHOT.

### A level set in SQL on SQL Server

A level set in SQL through passthrough is read back from the session. SQL Server's own REPEATABLE
READ, which locks and keeps no snapshot, refuses statements on ScalarDB tables with `0AN01`.

## Oracle

- `TRANSACTION_REPEATABLE_READ` runs at Oracle's SERIALIZABLE, which is snapshot isolation, in a
  transaction. In autocommit it runs at READ COMMITTED, where each statement's snapshot is its own.
- ORA-08177 and ORA-08176 come as `40001` and leave the transaction rollback-only.
- A level set in SQL through passthrough is not followed: the driver sets the connection's level
  again.

### No SERIALIZABLE on Oracle

`TRANSACTION_SERIALIZABLE` is refused with `0AN01`, and
`DatabaseMetaData.supportsTransactionIsolationLevel` reports it unsupported, REPEATABLE READ
supported. Oracle's own SERIALIZABLE is snapshot isolation, and `TRANSACTION_REPEATABLE_READ` already
runs at it: an application used to Oracle's SERIALIZABLE gets the same with
`TRANSACTION_REPEATABLE_READ`. True serializability needs PostgreSQL's serializable mode or shared
and range locks held to commit, as the driver uses on the other engines; Oracle has neither. JDBC
lets a driver run a stricter level than the one asked for, never a weaker one, so the driver refuses
it. For tables the driver also writes on Oracle, run ScalarDB at `READ_COMMITTED` or `SNAPSHOT`, to
match.
