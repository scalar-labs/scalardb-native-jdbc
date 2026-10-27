# snsql, the interactive shell

`snsql` is a psql-like shell that goes through the driver, on all five engines. Use it, not the
database's own shell, for ScalarDB tables: `psql`, `mysql`, `sqlcmd` or `sqlplus` would bypass the
driver, read data ScalarDB transactions have not committed, and write rows without the metadata
ScalarDB transactions check. The database's own shell stays right for DDL and for ordinary tables.

## Installing

Take `scalardb-native-shell-<version>.zip` from the GitHub release, unzip it, and put its `bin`
directory on your `PATH`. It needs Java 11 or later. The zip bundles the JDBC drivers of PostgreSQL,
MariaDB (also used for MySQL), SQL Server and Oracle. To use another one, MySQL Connector/J for
example, put its jar in the zip's `lib` directory.

Before the release there is no zip to download: build `snsql` yourself
([Development builds](development-builds.md)).

## Connecting

Give the driver's URL, or the host, port and database with `--engine`:

```bash
snsql -U alice jdbc:scalardb-native:postgresql://localhost:5432/shop
snsql -U alice -h localhost -d shop --engine postgresql -c "select count(*) from orders"
```

`snsql` prompts for the password, unseen. To give it without a prompt, set `SNSQL_PASSWORD`, or pass
`--property password=...`; `-w` never prompts. `--property` also sets the vendor driver's connect
properties and the driver's own `scalar.db.native_jdbc.*` ones ([Configuration](configuration.md)).

| Option | |
| --- | --- |
| `URL` | `jdbc:scalardb-native:...`, or give `-h`, `-p` and `-d` with `--engine` |
| `-h`, `--host` | database server host (default: localhost) |
| `-p`, `--port` | database server port (default: the engine's) |
| `-d`, `--dbname` | database name |
| `--engine` | `postgresql`, `mysql`, `mariadb`, `sqlserver` or `oracle`, with `-h`, `-p` and `-d` |
| `-U`, `--username` | database user name |
| `-w`, `--no-password` | never prompt for a password |
| `--property KEY=VALUE` | a connect property, for the vendor driver or `scalar.db.native_jdbc.*`; may be repeated |
| `-c`, `--command` | run one command, then exit; may be repeated |
| `-f`, `--file` | run the commands in a file, `-` for standard input, then exit |
| `-v`, `--set`, `--variable NAME=VALUE` | set a variable; `ON_ERROR_STOP=1` stops a script at its first error |
| `-?`, `--help` | show the help, then exit |
| `-V`, `--version` | show the version, then exit |

`snsql` exits with 0 when it ends normally, at `\q` too, in a script as at the prompt; 1 on a fatal
error, such as a malformed URL; 2 when it cannot connect, or for an option it does not know; 3 when
a script stops at an error with `ON_ERROR_STOP`.

## Using it

A statement ends at `;` and may span lines. The prompt is psql's: `db=>`, `db->` on a continuation
line, and `db=*>` inside a transaction. Results print as aligned tables, followed by `(n rows)` or
the update count. Ctrl-C cancels the running statement, and at the prompt clears the line. History
is kept in `~/.snsql_history`.

| Command | |
| --- | --- |
| `\q` | quit |
| `\conninfo` | show the current connection |
| `\c [DBNAME]` | connect to another database on the server |
| `\i FILE` | run the commands in a file |
| `\timing [on\|off]` | show how long each command takes |
| `\x [on\|off]` | expanded output, one column per line |
| `\?` | list the commands |
| `\d [NAME]` | describe a table, view or index; without a name, list relations |
| `\dt [PATTERN]` | list tables, marking ScalarDB tables |
| `\dv [PATTERN]` | list views |
| `\di [PATTERN]` | list indexes |
| `\dn [PATTERN]` | list schemas |
| `\l` | list databases |
| `\passthrough [on\|off]` | [passthrough](#passthrough) |

A pattern is `[SCHEMA.]NAME`, with `*` and `?`.

`\dt` has a `ScalarDB` column:

| Value | Meaning |
| --- | --- |
| `yes` | a ScalarDB table, set up for the driver |
| `yes, not set up` | a ScalarDB table the driver's setup has not been run for; statements on it fail with `55000` ([Setting up tables](setting-up-tables.md)) |
| `internal` | a table ScalarDB keeps for a ScalarDB table, such as a decoupled table's `<table>_tx_metadata`; you don't use it |
| empty | an ordinary table |

## Transactions

`snsql` starts in autocommit. These work as in psql, and `snsql` runs them through JDBC, since the
driver must see every transaction:

| Typed | What `snsql` does |
| --- | --- |
| `BEGIN`, `START TRANSACTION` | starts a transaction (`setAutoCommit(false)`) |
| `BEGIN ISOLATION LEVEL x` | starts a transaction at level `x`; the level returns to the session's at its end |
| `COMMIT`, `END` | commits, and returns to autocommit |
| `ROLLBACK`, `ABORT` | rolls back, and returns to autocommit |
| `SET TRANSACTION ISOLATION LEVEL x` | inside a transaction, before its first statement: sets the level for that transaction |
| `SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL x`, `SET SESSION TRANSACTION ISOLATION LEVEL x` | sets the session's level |

The levels are `READ COMMITTED`, `REPEATABLE READ` (or `SNAPSHOT`) and `SERIALIZABLE`
([Transactions and isolation levels](transactions.md)). A commit that fails is rolled back.
`SAVEPOINT` and `ROLLBACK TO SAVEPOINT` go to the database as SQL.

## Passthrough

`\passthrough on` sends statements as written, for DDL and maintenance the driver would refuse on
ordinary tables. It works in autocommit only, and the prompt shows `!`, `db=!>`. The driver still
refuses a statement that names a ScalarDB table there ([SQL support](sql-support.md#passthrough)).
`\passthrough off` ends it.
