# Setting up tables

This guide explains how to set up ScalarDB tables for the driver: what the one-time setup creates,
the `scalardb-native-tools` commands that run, check and undo it, and how to add a table while
applications run.

## What the setup does

Before the driver serves a ScalarDB table, an operator with DDL privileges runs [the
setup](concepts.md#the-setup) once. For each ScalarDB table, in either of ScalarDB's
[layouts](concepts.md#layout), inline or decoupled, it creates:

- the **[in-flight index](concepts.md#in-flight-index)**, through which the driver finds the rows ScalarDB transactions are
  writing;
- the **[read view](concepts.md#read-view)**, which shows each row's committed image. The driver falls back to it, and
  serves `SELECT *` on an inline table through it; other readers of the table may use it too.

It also:

- by default on an imported decoupled table, writes the transaction metadata ScalarDB has not, the
  backfill ([`backfill`](#backfill));
- on PostgreSQL, MySQL and Oracle, gathers or sets the statistics of the transaction metadata, so
  that the database plans the driver's in-flight checks through the index. On Oracle it locks the
  statistics of a decoupled table's `<table>_tx_metadata`, so that the nightly job does not replace
  them;
- grants SELECT on the read views to the users given with `--grant`.

The setup is re-runnable, and is run again after a table is added. The tables themselves are
ScalarDB's to create or import, with `scalar.db.jdbc.isolation_level` as
[Isolation levels](transactions.md#isolation-levels) gives.

## Running it

`scalardb-native-tools` runs the setup. It comes in `scalardb-native-tools-<version>.zip`, with the
vendor drivers whose licences allow bundling; before the release, build it yourself
([Development builds](development-builds.md)).

```sh
export SCALARDB_NATIVE_PASSWORD=...   # it never prompts
bin/scalardb-native-tools setup jdbc:scalardb-native:postgresql://host:5432/db -U admin --grant app
bin/scalardb-native-tools check jdbc:scalardb-native:postgresql://host:5432/db -U admin
```

It takes the application's URL or the vendor's. It exits with 0 when all is well, 1 when `check`
finds something to fix, and 2 on an error; `--json` prints the outcome as JSON. The same is available
in Java as `Setup.run`, `Setup.plan`, `Setup.backfill`, `Setup.teardown` and `Check` in the driver's
`com.scalar.db.nativejdbc.setup` package; their Javadoc has the details.

The commands act on every ScalarDB table, or on those named with `--table ns.t`, which may be
repeated.

### `setup`

- `--grant user` grants SELECT on the read views to an application's user or role; repeat it for
  several, `--grant app1 --grant app2`. On PostgreSQL before 15, SQL Server and Oracle the view runs
  with its owner's rights, so grant it only to users that may read every column of the table
  ([Database users](configuration.md#database-users)). Give the same `--grant` users on every run:
  a run may drop a read view and create it again, which drops its grants. It does so for a view an
  earlier version made in another column order, and on PostgreSQL for one that reads a `SMALLINT`
  column as `INTEGER`.
- `--backfill` backfills every imported or inline table the run sets up, and `--no-backfill` none.
  Without either, it backfills an imported decoupled table and no other ([`backfill`](#backfill)).
  A table it does not backfill says `backfill skipped (--backfill runs it)`.
- `--backfill-wait` is how long the backfill waits for rows ScalarDB has in flight, in seconds per
  table (60).
- `--dry-run` prints the statements instead of running them.
- After setting up a table for the first time, it waits out the applications' [catalog](concepts.md#catalog) TTL,
  `--catalog-ttl` seconds (60), and then says ScalarDB transactions may write it. `--no-wait` prints
  from when instead ([Adding a table](#adding-a-table-to-scalardb-while-applications-run)).
- It warns of the objects `check` reports below, and sets the tables up regardless.

### `check`

Read-only. It reports, and exits with 1 for any of them:

- the database settings the driver needs (SQL Server's);
- each table that is not set up;
- the rows ScalarDB left prepared longer ago than `--lifetime` seconds (15). The driver cannot settle
  them; a ScalarDB read of the rows, or ScalarDB's recovery, settles them;
- on an imported decoupled table, the rows with NULL metadata, which ScalarDB cannot write again
  until [`backfill`](#backfill) repairs them;
- the database's own objects that reach a ScalarDB table around the driver: a view, trigger,
  procedure or function that names it, a trigger on it, and a foreign key on it with a cascading
  action ([Objects over ScalarDB tables](sql-support.md#objects-over-scalardb-tables)). It matches
  names by their words, so an object that names a plain table of the same name in another schema
  counts too.

### `backfill`

Writes the transaction metadata of an imported decoupled table's rows that have none, and repairs
the rows ScalarDB left with NULL metadata, on tables that are set up. It runs only DML on the
metadata table, so it needs no DDL privileges, and it can run at any time and be repeated. It waits
for rows ScalarDB has in flight, up to `--backfill-wait` seconds per table (60). On Oracle it then
sets the metadata table's statistics again, as the setup does. `--dry-run` prints the statements and
the number of rows it would write.

The driver doesn't need the backfill. ScalarDB does, on an imported decoupled table: a ScalarDB
transaction that rolls back over a row without metadata leaves the row with NULL metadata, and
every later ScalarDB update of the row fails with `DB-CORE-20013`. Run it:

- on an imported decoupled table that ScalarDB transactions also write, which the setup does by
  default;
- after `DB-CORE-20013` on a row of an imported table, or when `check` reports rows with NULL
  metadata;
- not on an inline table, where it is never needed. `backfill` leaves an inline table alone;
  `setup --backfill` backfills one.

On a large table it is one transaction over the rows it writes, so run it in a quiet window.

```sh
bin/scalardb-native-tools backfill jdbc:scalardb-native:postgresql://host:5432/db -U admin --table warehouse.stock
```

### `teardown`

Drops the read view and the in-flight index, and on MySQL and Oracle undoes the setup's statistics:
it deletes the histogram and unlocks the statistics. ScalarDB's `dropTable` and `dropNamespace` need
it first on PostgreSQL, where the view depends on the table. `--dry-run` prints the statements
instead.

```sh
bin/scalardb-native-tools teardown jdbc:scalardb-native:postgresql://host:5432/db -U admin --table warehouse.stock
```

## Adding a table to ScalarDB while applications run

Each driver loads ScalarDB's catalog once and trusts it for `scalar.db.native_jdbc.catalog_ttl_millis` (60 seconds
by default), across connection pools in the same JVM. Until then it does not know a newly imported or
created ScalarDB table and treats it as a plain one: its reads can return rows a ScalarDB transaction
holds prepared, and its writes bypass the driver. So, in this order:

1. Import or create the table in ScalarDB.
2. Run `scalardb-native-tools setup`. For a new table it waits out the catalog TTL, then prints
   `Done: ScalarDB transactions may write ... now.` Give `--catalog-ttl` the longest `catalog_ttl_millis`
   your applications set, in seconds, rounded up.
   - With `--no-wait`, or `--json`, it returns at once and gives the time instead (`writableAt` in
     JSON).
   - It waits after a run that failed too, for the tables it did set up.
   - If it is interrupted while it runs, wait out the TTL yourself.
3. Only then let ScalarDB transactions write the table, or once every application instance that uses
   the driver on that database has restarted.

A table applications start using with a new release is safe by step 3 when that release is deployed.
