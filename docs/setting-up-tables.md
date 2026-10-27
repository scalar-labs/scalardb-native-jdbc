# Setting up tables

This guide explains how to set up ScalarDB tables for the driver: the layouts it serves, what the
one-time setup creates, the `scalardb-native-tools` commands that run, check and undo it, and how to
add a table while applications run.

The driver serves ScalarDB tables in both of ScalarDB's [layouts](concepts.md#layout):

- **inline**, ScalarDB's default, with ScalarDB's metadata columns in the row;
- **decoupled**, created or imported with `transaction-metadata-decoupling` set to `true`.

ScalarDB itself needs `scalar.db.jdbc.isolation_level=REPEATABLE_READ` to create or import them, or
`SERIALIZABLE` where the driver runs at SERIALIZABLE on PostgreSQL
([Isolation levels](transactions.md#isolation-levels)).

## The setup

Before the driver serves a table, an operator with DDL privileges runs the setup once. For each
ScalarDB table it creates:

- the **[in-flight index](concepts.md#in-flight-index)**, through which the driver finds the rows ScalarDB transactions are
  writing;
- the **[read view](concepts.md#read-view)**, which shows each row's committed image. The driver falls back to it, and
  serves `SELECT *` on an inline table through it; other readers of the table may use it too.

On an imported table it also writes the row metadata ScalarDB has not. The setup is re-runnable, and
is run again after a table is added.

`scalardb-native-tools` runs it. It comes in `scalardb-native-tools-<version>.zip`, with the vendor
drivers whose licences allow bundling; before the release, build it yourself
([Development builds](development-builds.md)):

```sh
export SCALARDB_NATIVE_PASSWORD=...   # it never prompts
bin/scalardb-native-tools setup jdbc:scalardb-native:postgresql://host:5432/db -U admin
bin/scalardb-native-tools check jdbc:scalardb-native:postgresql://host:5432/db -U admin
```

It takes the application's URL or the vendor's. It exits with 0 when all is well, 1 when `check`
finds something to fix, and 2 on an error; `--json` prints the outcome as JSON. The same is available
in Java as `Setup.run`, `Setup.plan`, `Setup.teardown` and `Check` in
`com.scalar.db.nativejdbc.setup`.

### `setup`

Sets up every ScalarDB table, or those named with `--table ns.t`.

- `--grant user` grants SELECT on the read views to the application's users.
- `--dry-run` prints the statements instead of running them.
- After setting up a table for the first time, it waits out the applications' [catalog](concepts.md#catalog) TTL,
  `--catalog-ttl` seconds (60), and then says ScalarDB transactions may write it. `--no-wait` prints
  from when instead ([Adding a table](#adding-a-table-to-scalardb-while-applications-run)).

### `check`

Read-only. It reports:

- the database settings the driver needs (SQL Server's);
- whether each table is set up;
- the rows ScalarDB left prepared longer ago than `--lifetime` seconds (15). The driver cannot settle
  them; a ScalarDB read of the rows, or ScalarDB's recovery, settles them.

### `teardown`

Drops the read view and the in-flight index, as ScalarDB's `dropTable` needs on PostgreSQL.
`--dry-run` prints the statements instead.

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
