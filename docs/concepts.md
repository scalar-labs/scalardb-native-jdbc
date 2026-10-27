# Concepts

This page describes the terms the other pages use, one at a time. Each page links a term to its
entry here where it first uses it.

## ScalarDB table

A table ScalarDB manages: created through ScalarDB, or created with the database's own DDL and then
imported into ScalarDB. ScalarDB lists it in its metadata and keeps [transaction
metadata](#transaction-metadata) for each of its rows. Any other table in the database is an
**ordinary table**, which the driver leaves alone: statements on it run as written.

## Transaction metadata

What ScalarDB keeps for each row of a ScalarDB table so that its transactions can check one another:
the ID and state of the transaction that last wrote the row, the row's version, and the row's
previous image, its `before_` values. Applications don't read or write it. The driver reads it to
isolate its transactions from ScalarDB's, and writes it with each change so that ScalarDB
transactions see the change.

## Layout

Where a ScalarDB table keeps its [transaction metadata](#transaction-metadata). The driver serves
both of ScalarDB's layouts, and applications name the table the same way in either:

- **Inline**, ScalarDB's default: extra `tx_` and `before_` columns in the table itself. The driver
  hides them: `SELECT *` returns the table's own columns only.
- **Decoupled**: a separate table, `<table>_tx_metadata`, holds the metadata, and the table keeps
  only its own columns. ScalarDB creates a view beside them for its own use, `<table>_scalardb` for an
  imported table. A table is decoupled when it is created or imported with
  `transaction-metadata-decoupling` set to `true`.

## In flight

A row is in flight when a ScalarDB transaction has prepared it, writing its new image and
transaction metadata, and has not yet applied its commit or rollback to the row. The driver never
reads an in-flight row's new image as committed, and a transaction through the driver that needs the
row waits or fails with `40001` ([Conflicts and retries](transactions.md#conflicts-and-retries)).

## Catalog

The driver's list of ScalarDB tables, which it reads from ScalarDB's metadata, in the `scalardb`
schema by default. The driver keeps the list in memory, shared by the JVM's connections to the same
database, and reads it again after `scalar.db.native_jdbc.catalog_ttl_millis`, 60 seconds by
default. Until then it does not know a table newly added to ScalarDB ([Adding a table to ScalarDB while applications
run](setting-up-tables.md#adding-a-table-to-scalardb-while-applications-run)).

## The setup

A one-time preparation of each ScalarDB table for the driver, by an operator with DDL privileges,
with `scalardb-native-tools setup` ([Setting up tables](setting-up-tables.md)). It creates the
table's [in-flight index](#in-flight-index) and [read view](#read-view), and on an imported table
writes the transaction metadata the import did not. The driver refuses statements on a ScalarDB
table that is not set up (`55000`).

## In-flight index

An index the setup creates on each ScalarDB table, `<table>_inflight`, through which the driver
finds the table's [in-flight](#in-flight) rows quickly.

## Read view

A view the setup creates for each ScalarDB table, `rc_<table>`, that shows each row's committed
image. The driver falls back to it, and serves `SELECT *` on an inline table through it. Other
readers may use it too: it is the table as committed transactions left it.

## Passthrough

A per-connection setting that sends statements as written, for DDL and maintenance the driver would
otherwise refuse on ordinary tables. It still refuses any statement that names a ScalarDB table
([Passthrough](sql-support.md#passthrough)).

## SNAPSHOT

Snapshot isolation: a transaction reads the database as it was when the transaction started, and
fails with `40001` if it writes a row another transaction changed since. It is
`TRANSACTION_REPEATABLE_READ` in JDBC, and ScalarDB's default level
([Isolation levels](transactions.md#isolation-levels)).
