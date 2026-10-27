# SQL support

What the driver runs on ScalarDB tables, what it refuses, and the rules per engine. Statements on
tables ScalarDB does not manage pass through unchanged. A refused statement is never run as written;
most fail with `0AN01` ([Errors](errors.md)).

## ScalarDB tables

Reads and writes of ScalarDB tables work on all five engines, in both of ScalarDB's layouts.

- **Writes.** A write is one `INSERT ... VALUES`, `UPDATE` or `DELETE` of one ScalarDB table. Batches,
  `RETURNING` and generated keys work too; the engines differ on `RETURNING` (below).
- **Locking reads.** `FOR UPDATE` and `FOR SHARE` lock a read of one ScalarDB table.
- **Read-only connections** cannot write ScalarDB tables (`25006`).

In the inline layout, where ScalarDB's metadata columns are in the row, the driver keeps those columns
from the application:

- `SELECT *` and `RETURNING *` give the data columns only.
- An `INSERT` without a column list gives its values to the data columns, and is refused where they
  would reach a metadata column.
- A statement that names a `tx_` or `before_` column ScalarDB keeps is refused with `0AN01`.

## Statements the driver cannot rewrite

- A statement on a ScalarDB table that the driver cannot parse or rewrite is refused with `0AN01`.
- A statement the parser cannot read is sent as written, in a transaction too, when both hold:
  - its words name no ScalarDB table, comments and literals included;
  - it is a single query, a DML statement, or DDL of a table, index, view, schema, sequence or
    domain.

  Not on SQL Server, nor at SERIALIZABLE on MySQL and MariaDB, where it is refused.
- A name holding an invisible formatting character, such as a zero-width space, is refused: SQL
  Server reads it as whitespace where the parser keeps it in the name.

## Passthrough

Passthrough sends statements as written, for what the driver refuses on tables ScalarDB does not
manage. Turn it on with `setClientInfo("scalar.db.native_jdbc.passthrough", "true")`, in autocommit.

- A statement that names a ScalarDB table, or ScalarDB's own schema or coordinator namespace, is still
  refused with `0AN01`, wherever the name stands, a comment or a plain table of the same name in another schema
  included.
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

A statement through one of them, or the database itself, reaches the table without ScalarDB's
protocol, outside passthrough too: the driver sees only the name the statement uses. The tools'
`check` reports those it finds, and `setup` warns of them; SQL built at run time is not seen.

## MySQL and MariaDB

**INSERT, on MySQL**

- It has no `RETURNING`.
- It gives each key column a literal or a placeholder of the column's type, or leaves out an
  `AUTO_INCREMENT` key; not NULL or 0 for it.
- It needs a strict `sql_mode`, and no `BEFORE INSERT` trigger on the table.

**Types, on MySQL and MariaDB**

- A `CHAR` column of a ScalarDB table reads as `VARCHAR`.
- A `BOOLEAN` column that a SELECT returns as it is, by name or through `*`, reads as on the
  database. One returned through a derived table, a CTE, or a `*` that also covers a plain table
  reads as `TINYINT`, whose `getObject` is an `Integer`: read it with `getBoolean`.

**Settings and locking reads, on MySQL and MariaDB**

- `useAffectedRows` must stay false, the drivers' default; the driver refuses a connection with it
  true (`08004`).
- A locking read takes `FOR UPDATE`, or `FOR SHARE` (`LOCK IN SHARE MODE`).

## SQL Server

**INSERT**

- It has no `RETURNING`. The driver reads its keys back with `OUTPUT`, which a table with a trigger
  refuses.

**Locking reads** take table hints:

| Hint | Works as |
| --- | --- |
| `UPDLOCK`, `XLOCK` | `FOR UPDATE` |
| `HOLDLOCK`, `SERIALIZABLE`, `REPEATABLEREAD` | `FOR SHARE` |
| `ROWLOCK`, `PAGLOCK`, `READPAST`, `NOWAIT` | with either |

Any other hint on a ScalarDB table, `NOLOCK` and `READUNCOMMITTED` among them, written with `WITH` or
without, is refused. So is a hint the parser cannot read on another table of a statement that reads
a ScalarDB table.

**Other rules**

- `XACT_ABORT` must be off; the driver refuses a connection with it on (`08004`).
- T-SQL's own transaction statements (`BEGIN TRANSACTION`, `SAVE TRANSACTION`) are refused: use the
  JDBC methods.
- The setup refuses an imported table keyed by a `datetime`, which ScalarDB's metadata holds as
  `datetime2(3)` and SQL Server does not compare equal for values ending in .003 or .007. Use
  `datetime2(3)`.

## Oracle

**INSERT**

- It has no `RETURNING`; `RETURNING ... INTO` does not parse.
- The keys are read back by the values the statement gives them, as on MySQL, or come from an
  `IDENTITY` key the INSERT leaves out. A multi-row `VALUES` cannot return such a key (Oracle's
  ORA-63809).
- No `BEFORE INSERT` or `COMPOUND` trigger on the table.

**Locking reads**

- `FOR UPDATE`, with `OF` a column, `NOWAIT` and `SKIP LOCKED`; not `WAIT n`.
- No row limit, which Oracle refuses with `FOR UPDATE`.
- Oracle has no `FOR SHARE`, and the driver refuses it.

**Other rules**

- A synonym of a table ScalarDB does not manage, `DUAL` among them, reads as that table. A synonym of
  a ScalarDB table is refused, with `42000`.
- `ALTER SESSION SET ISOLATION_LEVEL` is refused: use `setTransactionIsolation`.

## Statement cost

A statement text the connection has seen costs the driver a few microseconds. A new text costs
about 0.1 ms, and 0.6-0.8 ms on PostgreSQL, which also asks the server for its plan
([benchmarks/read-overhead.md](benchmarks/read-overhead.md)).
