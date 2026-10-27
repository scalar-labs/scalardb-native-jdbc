# SQL support

This page describes the SQL that runs through the driver and the SQL it refuses, with what to do
instead. Start with [At a glance](#at-a-glance); the sections after it give the details and each
database's rules.

Statements on ordinary tables run as written. On ScalarDB tables, the driver runs reads and writes of
one table, and refuses what it cannot run safely, mostly with `0AN01`. A refused statement is never
run, in part or as written.

## At a glance

### What runs on ScalarDB tables

| Statement | Notes |
| --- | --- |
| `SELECT`, also joined with ordinary tables, with subqueries, aggregates, CTEs and window functions | |
| `INSERT ... VALUES`, one row or several | key rules on [MySQL](#insert-on-mysql) and [Oracle](#insert-on-oracle) |
| `UPDATE` and `DELETE` of one table, by key or by any `WHERE` on that table | not of a key column |
| Locking reads: `FOR UPDATE`, `FOR SHARE` | of one ScalarDB table; each engine's spelling: [MySQL and MariaDB](#settings-and-locking-reads-on-mysql-and-mariadb), [SQL Server](#locking-reads-on-sql-server), [Oracle](#locking-reads-on-oracle) |
| Batches | |
| Generated keys (`getGeneratedKeys`) from an `INSERT` | with `RETURN_GENERATED_KEYS` or column names; by column index, or of a `tx_` or `before_` column, refused with `0AN01` |
| `RETURNING` on an `INSERT` | PostgreSQL and MariaDB, which have it |

### What the driver refuses

| Statement | Error | Do this instead |
| --- | --- | --- |
| `INSERT ... SELECT` into a ScalarDB table, or into an ordinary table from a ScalarDB table | `0AN01` | read the rows, then insert them with `INSERT ... VALUES`, a batch for many |
| An upsert: `ON CONFLICT`, `MERGE`, `ON DUPLICATE KEY UPDATE`, `INSERT IGNORE` | `0AN01` | in one transaction, read the row, then insert or update it ([Upserts](#upserts)) |
| An `UPDATE` or `DELETE` that also reads another table: `FROM`, `USING`, a join, or a subquery on another table | `0AN01` | read the keys first, then update or delete by key, in the same transaction |
| One statement that writes several tables | `0AN01` | one statement per table, in one transaction |
| A `WITH` item that writes (`WITH u AS (UPDATE ...) SELECT ...`), in a statement on a ScalarDB table | `0AN01` | one statement per table, in one transaction |
| An `UPDATE` of a key column | `0AN01` | delete the row and insert it with the new key, in one transaction |
| `RETURNING` on an `UPDATE` or `DELETE` | `0AN01` | read the rows in the same transaction |
| `SELECT ... INTO`, `CREATE TABLE ... AS SELECT` from a ScalarDB table | `0AN01` | create the table, then read and insert the rows |
| DDL and `TRUNCATE` on a ScalarDB table, `CREATE VIEW` and `CREATE INDEX` on it included | `0AN01` | change the table through ScalarDB's admin, then run the setup again ([Setting up tables](setting-up-tables.md)) |
| A procedure call: `CALL`, `EXEC`, `{call ...}` | `0AN01` | [passthrough](#passthrough), if the procedure touches no ScalarDB table |
| A function that runs SQL given as text, such as PostgreSQL's `query_to_xml` or `dblink`, or SQL Server's `OPENQUERY` | `0AN01` | [passthrough](#passthrough), if that SQL touches no ScalarDB table |
| A write that names one of ScalarDB's metadata columns, `tx_` or `before_` | `0AN01` | none: they are ScalarDB's. A read of one fails as a column that does not exist, since the driver hides them ([Inline layout](#inline-layout)) |
| ScalarDB's own table and view of a decoupled table (`<table>_tx_metadata`, `<table>_scalardb`), or a synonym of a ScalarDB table | `42000` | name the table itself |
| A statement on a ScalarDB table the driver cannot parse | `0AN01` | write it in a form the driver reads ([Statements the driver cannot parse](#statements-the-driver-cannot-parse)) |

Each engine adds a few rules of its own ([MySQL and MariaDB](#mysql-and-mariadb),
[SQL Server](#sql-server), [Oracle](#oracle)).

## Inline layout

In the inline [layout](concepts.md#layout), ScalarDB's metadata columns are in the table's own rows. The driver keeps
them from the application:

- `SELECT *` and `RETURNING *` give the data columns only.
- An `INSERT` without a column list gives its values to the data columns, and is refused where they
  would reach a metadata column.
- A write that names a `tx_` or `before_` column ScalarDB keeps is refused with `0AN01`. A read that
  names one fails as the database's "no such column" (`42703` on PostgreSQL).

## Statements the driver cannot parse

| Statement | What the driver does |
| --- | --- |
| A statement on a ScalarDB table that the driver cannot parse or rewrite | refuses it with `0AN01` |
| A statement the parser cannot read, whose words name no ScalarDB table, comments and literals included, and that is a single query, a DML statement, or DDL of a table, index, view, schema, sequence or domain | sends it as written, in a transaction too; on SQL Server, and at SERIALIZABLE on MySQL and MariaDB, refuses it with `0AN01` |
| Any other statement the parser cannot read | refuses it with `0AN01` |
| A name holding an invisible formatting character, such as a zero-width space | refuses it: SQL Server reads the character as whitespace where the parser keeps it in the name |

## Passthrough

[Passthrough](concepts.md#passthrough) sends statements as written, for what the driver refuses on tables ScalarDB does not
manage. Turn it on with `setClientInfo("scalar.db.native_jdbc.passthrough", "true")`, in
autocommit, or `\passthrough on` in `snsql`.

- A statement that names a ScalarDB table, or ScalarDB's own schema or coordinator namespace, is still
  refused with `0AN01`, wherever the name stands, a comment or a plain table of the same name in
  another schema included.
- Set `scalar.db.native_jdbc.coordinator_namespace` in the connect properties when ScalarDB's
  `scalar.db.consensus_commit.coordinator.namespace` is not `coordinator`.
- DDL or maintenance on a ScalarDB table goes through a connection of the database's own driver, with
  ScalarDB's transactions on the table stopped.

## Objects over ScalarDB tables

Don't create any of these over a ScalarDB table:

- a view;
- a trigger, on it or on another table that writes it;
- a procedure or function that reads or writes it;
- a foreign key on it with `ON DELETE` or `ON UPDATE` `CASCADE`, `SET NULL` or `SET DEFAULT`.

Through the driver, DDL that names a ScalarDB table is refused, but the database's own tools run it.
A statement through one of these objects, or the database itself, reaches the table without
ScalarDB's protocol, outside passthrough too: the driver sees only the name the statement uses. The
tools' `check` reports those it finds, and `setup` warns of them; SQL built at run time is not seen.

## Upserts

The driver refuses `ON CONFLICT`, `MERGE`, `ON DUPLICATE KEY UPDATE` and `INSERT IGNORE` on a
ScalarDB table. You don't need them to stay correct: read the row, then insert or update it, in one
transaction. The driver detects a concurrent write of the same row, by another transaction through
the driver or by a ScalarDB transaction, and fails the transaction that lost the race rather than
lose an update or insert the row twice:

| The losing transaction meets | At SNAPSHOT and SERIALIZABLE | At READ COMMITTED |
| --- | --- | --- |
| A ScalarDB transaction writing the same key | `40001` | `40001` |
| Another transaction that committed the same key after this one read | `40001` | the database's duplicate-key error |

The duplicate-key error is `23505` on PostgreSQL, 1062 on MySQL and MariaDB, 2627 on SQL Server
and ORA-00001 on Oracle. Either way, roll back and run the transaction again: the retry reads the row
and updates it.

```java
// one attempt; on 40001, or the duplicate-key error at READ COMMITTED, roll back and retry
c.setAutoCommit(false);
boolean exists;
try (PreparedStatement read =
    c.prepareStatement("SELECT 1 FROM warehouse.stock WHERE item_id = ?")) {
  read.setInt(1, item);
  try (ResultSet rs = read.executeQuery()) {
    exists = rs.next();
  }
}
try (PreparedStatement write =
    c.prepareStatement(
        exists
            ? "UPDATE warehouse.stock SET quantity = quantity + ? WHERE item_id = ?"
            : "INSERT INTO warehouse.stock (quantity, item_id, name) VALUES (?, ?, 'new')")) {
  write.setInt(1, quantity);
  write.setInt(2, item);
  write.executeUpdate();
}
c.commit();
```

[Getting started](getting-started.md#5-run-the-warehouse-application) shows a retry loop around
such a transaction.

## MySQL and MariaDB

### INSERT on MySQL

- MySQL has no `RETURNING`.
- An `INSERT` gives each key column a literal or a placeholder of the column's type, or leaves out an
  `AUTO_INCREMENT` key; not NULL or 0 for it.
- The session needs a strict `sql_mode`, and the table no `BEFORE INSERT` trigger.

### Types on MySQL and MariaDB

- A `CHAR` column of a ScalarDB table reads as `VARCHAR`.
- A `BOOLEAN` column that a SELECT returns as it is, by name or through `*`, reads as on the
  database. One returned through a derived table, a CTE, or a `*` that also covers a plain table
  reads as `TINYINT`, whose `getObject` is an `Integer`: read it with `getBoolean`.

### Settings and locking reads on MySQL and MariaDB

- `useAffectedRows` must stay false, the drivers' default; the driver refuses a connection with it
  true (`08004`).
- A locking read takes `FOR UPDATE`, or `FOR SHARE` (`LOCK IN SHARE MODE`).

## SQL Server

### INSERT on SQL Server

- SQL Server has no `RETURNING`. The driver reads its keys back with `OUTPUT`, which a table with a trigger
  refuses.

### Locking reads on SQL Server

Locking reads take table hints:

| Hint | Works as |
| --- | --- |
| `UPDLOCK`, `XLOCK` | `FOR UPDATE` |
| `HOLDLOCK`, `SERIALIZABLE`, `REPEATABLEREAD` | `FOR SHARE` |
| `ROWLOCK`, `PAGLOCK`, `READPAST`, `NOWAIT` | with either |

Any other hint on a ScalarDB table, `NOLOCK` and `READUNCOMMITTED` among them, written with `WITH` or
without, is refused. So is a hint the parser cannot read on another table of a statement that reads
a ScalarDB table.

### Other rules on SQL Server

- `XACT_ABORT` must be off; the driver refuses a connection with it on (`08004`).
- T-SQL's own transaction statements (`BEGIN TRANSACTION`, `SAVE TRANSACTION`) are refused: use the
  JDBC methods.
- The setup refuses an imported table keyed by a `datetime`, which ScalarDB's table catalog records as
  `datetime2(3)` and SQL Server does not compare equal for values ending in .003 or .007. Use
  `datetime2(3)`.

## Oracle

### INSERT on Oracle

- An `INSERT` takes no `RETURNING`: Oracle's `RETURNING ... INTO` does not parse.
- The keys are read back by the values the statement gives them, as on MySQL, or come from an
  `IDENTITY` key the INSERT leaves out. A multi-row `VALUES` cannot return such a key (Oracle's
  ORA-63809).
- The table must have no `BEFORE INSERT` or `COMPOUND` trigger.

### Locking reads on Oracle

- `FOR UPDATE`, with `OF` a column, `NOWAIT` and `SKIP LOCKED`; not `WAIT n`.
- No row limit, which Oracle refuses with `FOR UPDATE`.
- Oracle has no `FOR SHARE`, and the driver refuses it.

### Other rules on Oracle

- A synonym of a table ScalarDB does not manage, `DUAL` among them, reads as that table. A synonym of
  a ScalarDB table is refused, with `42000`.
- `ALTER SESSION SET ISOLATION_LEVEL` is refused: use `setTransactionIsolation`.

## Statement cost

A statement text the connection has seen costs the driver a few microseconds. A new text costs
about 0.1 ms, and 0.6-0.8 ms on PostgreSQL, which also asks the server for its plan
([benchmarks/read-overhead.md](benchmarks/read-overhead.md)).
