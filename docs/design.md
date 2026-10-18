# ScalarDB Native JDBC: design rules

Design notes, reviewed in three independent rounds on 2026-10-07 and 2026-10-08. The driver targets
tables that ScalarDB itself manages, in ScalarDB's eager layout, inside one database.

**Name.** ScalarDB Native JDBC; artifact `scalardb-native-jdbc`; URL prefix `jdbc:scalardb-native:`;
Java package `com.scalar.db.nativejdbc`, because `native` is a reserved word in Java. Tagline: your
database's SQL and transactions, safe alongside ScalarDB.

**Description.** ScalarDB Native JDBC is a JDBC driver for a database that holds both ordinary tables and
ScalarDB-managed tables. The application uses that database's own SQL and its own transactions across both kinds of table, and
ScalarDB's transactions on the same tables keep their guarantees. The driver wraps the database's JDBC
driver. Statements on ordinary tables pass through unchanged; statements on ScalarDB-managed tables are
rewritten so that they read committed data and leave behind the row metadata that ScalarDB's own
transactions check. The result is one native transaction, ACID across ordinary and ScalarDB tables,
isolated from concurrent ScalarDB transactions at READ COMMITTED, SNAPSHOT or SERIALIZABLE, chosen with
`setTransactionIsolation`, with no change to ScalarDB and no call into it. It is not a ScalarDB client,
not a dialect translator, and not a cross-database transaction manager.

**Scope.** One database per connection, and a transaction never spans databases. "One database" means
whatever one connection can transact over: a database on PostgreSQL, a server across schemas on MySQL and
MariaDB, an instance across databases on SQL Server, an instance or pluggable database on Oracle. A
ScalarDB transaction that also touches other databases needs no special handling. The driver only meets
the rows that transaction prepares in this database, and those rows follow the same protocol as any
other. The rows the ScalarDB transaction writes in other databases are covered by ScalarDB's own
validation, which spans every database the transaction touched. The ScalarDB coordinator can live
anywhere, because the driver never reads it.

---

## 1. Setting

- One database holds plain tables, ScalarDB tables and, optionally, the ScalarDB coordinator table. The
  database is ACID across all of them.
- A ScalarDB table `T` is three objects: `T` (the user's columns), `T_meta` (transaction metadata, primary
  key copied from `T`) and the views of section 3.
- The driver wraps the native JDBC driver. It parses every statement with JSQLParser, passes statements on
  plain tables through unchanged, and rewrites statements on ScalarDB tables as in section 4. A driver
  transaction **is** the native transaction of the wrapped connection.
- The driver never reads or writes the coordinator table. Section 8 lists what the coordinator would add.

### ScalarDB facts the rules depend on

1. Row states: `1` PREPARED, `2` DELETED, `3` COMMITTED. Metadata per row: `tx_id`, `tx_version`,
   `tx_state`, `tx_prepared_at`, `tx_committed_at` (epoch milliseconds), `before_tx_id`,
   `before_tx_version`, `before_tx_state`, `before_tx_prepared_at`, `before_tx_committed_at`, and one
   `before_<col>` per non-key column. `P_before` (4.2) substitutes only the `before_<col>` columns.
2. Prepare writes the **after image into `T`** and the **before image into `T_meta`**, sets state 1 or 2,
   a new `tx_id`, `tx_version + 1`. Its condition is **`tx_id` alone**: equal to the `tx_id` the
   transaction read, `tx_id IS NULL` for a row that has no metadata yet, or "does not exist" for an
   insert. It never checks `tx_version` (`PrepareMutationComposer`). A prepare over a row whose `tx_id`
   is NULL stores `before_tx_version = 0` and a NULL `before_tx_id`, and the row's other metadata, all
   NULL, in the other `before_*` columns; a rollback of that prepare copies them back, so the row ends
   with `tx_id`, `tx_version` and `tx_state` all NULL (`RollbackMutationComposer`). ScalarDB reads a NULL
   `tx_state` as state 3 (`TransactionResult.getState`), and so does every rule in this document:
   **`tx_state = 3` below always means `COALESCE(tx_state, 3) = 3`**; `tx_state IN (1, 2)` is unaffected.
3. The commit point is the coordinator record. Record commit follows: state to 3, or the row removed for
   state 2, conditional on `tx_id` and state. A failure there is logged and ignored, and left to lazy
   recovery. All prepares of one transaction on one JDBC storage run in **one database transaction**, and
   so do its record commits (`MutationsGrouper` with the STORAGE atomicity unit, `JdbcDatabase.mutate`).
   A mix of states 1 and 3 across one transaction's rows comes only from per-record lazy recovery or
   from a record commit that failed part-way and was ignored.
4. All prepares of a transaction precede all of its record commits. One decision per transaction; roll
   forward or roll back applies to all of its rows.
5. `before_*` is **set to NULL** at commit, at rollback, by lazy recovery and by one-phase commit
   (`CommitMutationComposer`, `RollbackMutationComposer`, `OnePhaseCommitMutationComposer`). A state 3 row
   never carries a before image. A row has a before image when
   `before_tx_id IS NOT NULL OR before_tx_version IS NOT NULL` (`TransactionResult.hasBeforeImage`).
6. ScalarDB consults the coordinator only for rows in state 1 or 2. A SNAPSHOT or SERIALIZABLE ScalarDB
   read of such a row returns the decided value at once when the coordinator holds a decision, at any
   age, and hands the physical roll forward or back to a background task, best-effort
   (`RecoveryExecutor`, `CrudHandler.executeRecovery`); a READ_COMMITTED read returns the before image
   regardless and recovers in the background only in read-write mode. When there is no decision, the
   writer is aborted only after the transaction lifetime, 15 s
   (`RecoveryHandler.TRANSACTION_LIFETIME_MILLIS`, measured on `tx_prepared_at`); until then a SNAPSHOT or
   SERIALIZABLE reader fails with a retryable conflict.
7. SERIALIZABLE validation re-reads the read set and compares **`tx_id`** per record, plus presence and
   set membership for scans (`Snapshot.isChanged`); keys in its own write set are covered by the prepare
   condition instead. It never compares `tx_version`.
8. Every ScalarDB storage call is its own database transaction: one `mutate` call, such as a
   transaction's prepares, is one transaction, and no lock outlives the call. A scan keeps its
   connection open until the scanner is closed. On PostgreSQL, MySQL, MariaDB and Oracle ScalarDB's reads
   take no locks; at SERIALIZABLE on PostgreSQL they take SSI's predicate locks, which block nothing. On
   SQL Server they do: ScalarDB's connections must run at REPEATABLE READ or higher
   for decoupled tables, enforced only at create and import time through `StorageInfo` and otherwise by
   the `scalar.db.jdbc.isolation_level` setting, and that level is lock-based there, so a ScalarDB get or
   scan holds shared locks, and range
   locks at SERIALIZABLE, until the call ends, or until the scanner is closed. Those locks block driver
   writes and can deadlock with them; the deadlock is detected and retryable on both sides.

### Requirements on the ScalarDB side

- ScalarDB writes `T` and `T_meta` in **one database transaction** per storage mutation: a split put is
  two puts in one `mutate` call (`JdbcDatabase.dividePutForSourceTables`), so the driver never observes a
  torn row. The call groups batchable statements by SQL text and runs the non-batchable ones, inserts
  with "not exists", last (`JdbcCrudService.mutate`). Per key the data-table write precedes the metadata
  write for every update and delete prepare, and for an insert prepare on a created table, where both
  sides are "not exists". The exception is an insert prepare on an imported table: its "not exists"
  condition lands on the data table only, so the metadata upsert runs first and the data insert second.
  Section 4.3 uses the data-then-metadata order; a driver INSERT racing a ScalarDB insert of the same key
  on an imported table can therefore deadlock, which the database detects and both sides retry.
- Layout and names are ScalarDB's. A table created through ScalarDB is `T_data` plus `T_tx_metadata` with
  an INNER-join virtual table named `T`. An imported table is `T` plus `T_tx_metadata` with a LEFT-OUTER
  virtual table named `T_scalardb`. Virtual tables are registered in `virtual_tables`. The driver maps the
  user's table name to the physical data table per layout and reuses the virtual table as `cc_T`
  (section 3) rather than maintaining a parallel view. `T` and `T_meta` below are the logical names;
  `T_data`, where it appears below, means the physical data table whichever its name, `T_data` for a
  created table and `T` for an imported one.
- Reads of the two-table views need **statement-level snapshot semantics**. PostgreSQL, MySQL and Oracle
  give one snapshot per statement at READ COMMITTED. SQL Server does not unless the database option
  `READ_COMMITTED_SNAPSHOT` is on, which the driver checks at connect and refuses without; a locking read
  there can pair a new `T` row with an old `T_meta` row and leak an after image. A SQL Server connection
  then stays in the database it was opened in, for which the catalog and this check hold: `USE` or
  `setCatalog` naming another database is refused with `0AN01`, and a switch made under the passthrough
  setting stops the next statement until the connection is back. ScalarDB applies the same
  rule to itself: its JDBC adapter requires at least REPEATABLE READ, SERIALIZABLE on Oracle, for
  consistent virtual-table reads (`getMinimumIsolationLevelForConsistentVirtualTableRead`).
- On an imported table a row of `T` may have no metadata row, or one whose metadata is all NULL; a created
  table always has a metadata row with a real `tx_id`. ScalarDB's `importTable`
  creates an **empty** `_tx_metadata`, and a ScalarDB rollback over a row without metadata leaves a
  metadata row with `tx_id`, `tx_version` and `tx_state` all NULL (fact 2). ScalarDB reads both as
  committed, and so does the driver: every state 3 test in this document is `COALESCE(tx_state, 3) = 3`,
  the first arm of `rc_T` is a left outer join (section 3), and 4.4 defines the CAS for a NULL or missing metadata row and
  the tombstone a DELETE of such a row must leave.
  Run a backfill before the driver is enabled: a sentinel `tx_id`, version 1, state 3 and non-null
  timestamps for every row whose `tx_id` is NULL or whose metadata row is missing, repeated until no such
  row remains and no in-flight row has a NULL `before_tx_id`. After that the NULL case cannot recur, since
  every later before image carries a real `tx_id`; the driver's NULL handling covers the window before
  the backfill completes. The backfill runs at READ COMMITTED, one statement per step over the whole
  table, so it writes every row of the table in one transaction, holds those row locks and fills the
  log for its duration (about 25 s for 5M rows on PostgreSQL): run it in a quiet window. A unique
  violation, from a ScalarDB insert that came first, and a deadlock or lock timeout count as nothing
  done and the next pass retries; any other error stops the table at once. The backfill also protects ScalarDB's own liveness, because of a ScalarDB-side
  defect: on an imported table ScalarDB cannot re-prepare a row whose metadata row exists with all NULLs.
  Its prepare condition `tx_id IS NULL` lands on the metadata table, and `JdbcDatabase` turns an
  all-IS-NULL condition on the right side of a LEFT OUTER virtual table into "insert if not exists" by
  default (`isAllIsNullOnRightColumnsInLeftOuterJoin`; the attribute
  `LEFT_OUTER_VIRTUAL_TABLE_PUT_IF_IS_NULL_ON_RIGHT_COLUMNS_CONVERSION_ENABLED` is never set by Consensus
  Commit); the insert hits a duplicate key and the prepare fails on every attempt. The fix belongs in
  ScalarDB, where a rollback over a deemed-committed row should remove the metadata row instead of
  nulling it, or the import should create the metadata rows. A second ScalarDB-side limit on imported
  tables: a delete of a key ScalarDB read nothing for is prepared as a key-only reservation insert into
  the data table, which fails on NOT NULL columns, so such deletes fail non-retryably there (4.5).
- For SERIALIZABLE between the two sides, ScalarDB transactions must run at SERIALIZABLE, and on
  PostgreSQL its storage connections too, `scalar.db.jdbc.isolation_level=SERIALIZABLE`, so that SSI
  sees ScalarDB's storage transactions (section 5).

### Layouts

The rules are written for ScalarDB's decoupled layout, data table plus `_tx_metadata` plus a virtual
table, because that is the layout for existing tables: importing a table into the classic layout means
ScalarDB adds its metadata columns to the table with ALTER TABLE, which the decoupled layout exists to
avoid. The driver also supports the **classic layout**, metadata columns in the same row, and gets it
almost for free: the protocol is identical, and every two-table concern in this document collapses.

| Concern | Decoupled layout | Classic layout |
|---|---|---|
| Catalog | `virtual_tables` | ScalarDB's `metadata` table, which marks a table as transactional; both layouts may coexist in one database |
| Views | join of two tables; outer join for an imported table | `cc_T` is the table itself; `rc_T` is the CASE view over it |
| Two-arm read | required, the OR cannot use indexes across the join | still the fastest form; the plain `x = ? OR before_x = ?` also works on one table (BitmapOr, index merge) |
| Statement-level snapshot at READ COMMITTED | required, or the data and metadata rows may be from different times | not required; one row is read atomically |
| Write by key | DML on `T`, then CAS on `T_meta`, data-then-metadata order | one statement: the user's SET list plus the stamp of I5, the anchor in the WHERE; DELETE is the conditional delete itself |
| Lock order, insert order on imported tables | an argument to make | gone; one row lock |
| Tombstones, orphans (4.5) | required; "no metadata row" is ambiguous | gone; the deemed-committed condition is `WHERE pk AND tx_id IS NULL` on the row, and a deleted row fails it by itself |
| Backfill | required for correctness and for ScalarDB's liveness | wanted only to end the NULL-rule window: the all-NULL liveness defect of section 1 does not exist here, since the deemed-committed condition is a row-level test; the driver's NULL anchor is a plain `tx_id IS NULL` on the row |
| Locking clause through an outer-join view | rewritten onto the base tables | no join, no issue; the covering-index caveat of 4.2 also largely disappears, because the locking re-read fetches `tx_id` and `tx_version` from the row, which forces the clustered record to be locked, unless those columns are added to a secondary index |
| Coarse SERIALIZABLE path, stuck-row check, in-flight index | on `T_data` and `T_meta` | on `T` |
| ScalarDB facts | identical | identical: same composers, conditions, before-image nulling, validation |

Three additions for the classic layout. User statements are kept away from the metadata columns:
`SELECT *` is served through `rc_T`, so they never appear, and `ResultSetMetaData` is filtered to match;
an INSERT without a column list is expanded to one before the stamp columns are added; an UPDATE that
names a metadata column is refused. The driver must not create, and should warn about, a secondary index
that covers the metadata columns, including ScalarDB's own `before_*` indexes, which live on `T` here and
can serve a user `FOR UPDATE` as a covering index, for the locking reason above.

### Architecture

- **A JDBC driver in front of the vendor driver, nothing in between.** A `java.sql.Driver` registered
  under its own URL prefix opens the connection with the vendor driver named by the URL and returns proxies for
  `Connection`, `Statement`, `PreparedStatement` and `ResultSet`, each an `InvocationHandler` over the
  vendor object that forwards every method it does not intercept.
- **One logical connection is one physical connection to one database.** Every statement on it, plain or
  ScalarDB, goes to that database in that database's own dialect. The driver translates no dialect and
  routes nothing per query. A connection pool wraps the driver like any other, and an ORM sees plain
  JDBC. A transaction that must span two databases is a ScalarDB transaction, not this driver.
- **Per statement**: parse with JSQLParser under the engine's identifier rules and the session's
  double-quote setting; resolve every table reference, first through the engine's own catalog and then
  against ScalarDB's (see "The catalog"); classify (plain tables only, a SELECT mixing plain and ScalarDB tables, DML on one ScalarDB table, or
  refused); rewrite as in section 4; forward on the wrapped connection; post-process the result (strip
  metadata, remember anchors). A statement with no ScalarDB table is forwarded verbatim. Placeholders
  duplicated by a rewrite, `P` into `P_before` or into a second UNION ALL arm, are re-bound with the same
  values at their new positions. The decision is made once per statement text and connection: each
  connection keeps the routes of its last 256 texts, at prepare and at `Statement.execute` alike, and
  reuses one under the same checks as a prepared statement's route (below), so a text an ORM repeats
  is parsed again only when the session, the catalog or an in-flight count it depends on has moved
  (todo 008). JSQLParser parses on a shared pool of daemon threads, not a new thread per parse. A
  new text is parsed once: the split read's copies are parsed again only where Java assertions are
  enabled for the driver's classes, since the emitter separates any two tokens that could fuse and
  two more parses were most of the cost of a statement the cache does not hold. That is the driver's
  tests, and by default an application's tests too (Gradle and Maven Surefire enable assertions),
  where a copy the parser cannot read throws an `AssertionError`; with assertions off it reaches the
  database, which fails it with a syntax error.
- **Connection methods**: `setTransactionIsolation` is forwarded and selects the level of section 5
  (`TRANSACTION_REPEATABLE_READ` is SNAPSHOT; `READ_UNCOMMITTED` is refused; section 5 maps the
  constants per engine). The driver sets READ COMMITTED at connect, because vendor defaults differ, with
  Connector/J and MariaDB Connector/J starting at REPEATABLE READ and the others at READ COMMITTED, so
  the default level is the same on every engine. `setAutoCommit`, `commit` and `rollback` are forwarded
  untouched, because the transaction is the database's own; a write issued in autocommit mode is wrapped
  by the driver in a transaction of its own (4.4). `getMetaData`
  is forwarded, optionally filtered to hide `_data`, `_tx_metadata` and `virtual_tables`. A passthrough
  flag via `setClientInfo` sends statements verbatim for DDL and maintenance, only in
  autocommit.
- **The catalog** is one per database and identity, keyed by a SHA-256 digest of the vendor URL,
  every vendor property, password included, and the catalog settings: what `all_objects`,
  `sys.objects` and `pg_class` show depends on who asks (the user, an Oracle proxy user, a role set
  through the properties), a rotated password gets a fresh catalog, and no credential is kept in a
  key. It is created only after the caller's own connection has logged in, so a failed login leaves
  nothing behind. The driver keeps at most 64 catalogs, least recently used first; one pushed out
  releases its database session, and a connection still holding it reopens one when it needs to. It reads two ordinary tables in ScalarDB's system schema, `scalardb` by default
  (`scalar.db.system_namespace_name`, the `scalardbnative.metadataSchema` property): `virtual_tables`
  and `metadata`. They sit in the same database as the user's tables, carry no transaction metadata and
  are written only by ScalarDB's admin API; the driver reads them whole, with plain SELECTs on a
  connection of its own in autocommit, never on the user's connection, and never writes them. A query on
  the user's connection would fix the snapshot of the user's transaction before the statement 4.2
  requires to come first, and a failure would abort that transaction.

  A name as written is first resolved by **the engine's own catalog** ("Identifiers per engine"): which
  stored table or view it denotes, given the session's schemas, in the stored spelling. Only that answer
  is looked up in ScalarDB's catalog, in this order:
  0. A synonym is **refused** with SQLSTATE `42000`, "name the table it stands for", whatever it stands
     for: which table a synonym denotes, chains included, is the engine's to say.
  1. A `virtual_tables` row whose **virtual table name** is the user's name: a created decoupled table.
     Its left source is `T_data`, its right source `T_tx_metadata`, join type INNER. This is also ScalarDB's
     own test for "decoupling applied" (`ConsensusCommitAdmin.throwIfTransactionMetadataDecouplingApplied`).
  2. Otherwise a `virtual_tables` row whose **left source** is the user's name **and whose `join_type` is
     LEFT_OUTER**: an imported decoupled table. The user's name is the data table, the right source is
     `T_tx_metadata`, the virtual table is `T_scalardb`. A left-source match with join type INNER is the
     `_data` table of a created table, which a user statement must not name: refuse it. Both columns hold
     namespace-qualified full names.
  3. Otherwise a `metadata` entry for the user's name that carries the transaction metadata columns,
     `tx_id`, `tx_state`, `tx_version`, the timestamps, the `before_tx_*` columns and a `before_` twin of
     each data column: a classic-layout table (`ConsensusCommitUtils.isTransactionTableMetadata`).
  4. A name that is the **right source** of a `virtual_tables` row, `T_tx_metadata`, is refused like
     `T_data` and the virtual table of an imported table; the three refusals use SQLSTATE `42000` and
     name the table to use instead.
  5. Otherwise a plain table: pass through.

  ScalarDB records a name in the spelling the operator gave `createTable` or `importTable`. Where the
  database compares table names without regard to case (MySQL and MariaDB with
  `lower_case_table_names` not 0, SQL Server with a case-insensitive collation; read once per load),
  that spelling may differ from the stored one, and the catalog matches the two without regard to case
  as well. A database without ScalarDB's catalog is an **error unless allowed**: the schema existing
  but its tables not visible is SQLSTATE `42501` naming the SELECT grants (Oracle reports a table it
  does not grant as absent), the schema not existing is `08004` naming the property, unless
  `scalardbnative.allowNoCatalog` says the database has no ScalarDB. A database whose `virtual_tables`
  is missing but `metadata` is present (a ScalarDB older than virtual tables) is logged and has classic
  layouts only.

  The `metadata` table has entries for physical tables only; a virtual table has none, and ScalarDB's
  own `getTableMetadata` merges the two source entries at read time. So for a decoupled table the
  primary key, the clustering order and the user's columns come from the **data table's** entry, and
  the `before_*` set follows from the non-key columns; nothing is inferred from naming conventions: a plain table called `foo_data`, or one with a column named `tx_id` but no `metadata`
  entry, is not misclassified. Refreshed as in 4.4.
- **Session state** the connection proxy captures on the user's connection, outside a transaction, and
  passes to every lookup: the schemas an unqualified name resolves through, and whether `"x"` quotes an
  identifier. Captured at connect and re-read lazily before the next lookup after anything that may
  change it: `setSchema`, `setCatalog`, any statement the driver did not classify as a SELECT or DML on
  known tables (`SET search_path`, `SET ROLE`, `USE`, `ALTER SESSION`, `EXECUTE AS` and passthrough
  statements included, since JSQLParser does not parse several of them), and `commit` or `rollback`
  after a `SET` inside the transaction (`SET LOCAL` reverts), including the commit `setAutoCommit(true)`
  makes; a rollback to a savepoint marks it too but keeps the transaction's record. PostgreSQL's
  `set_config` counts as a `SET` wherever it appears, and is refused for a transaction setting, or a
  setting it does not name as a literal; `RESET ALL` and `RESET` of a transaction setting are refused,
  since they revert the level `setTransactionIsolation` set. A function of the application's own that
  calls `set_config` on the level is not seen: the README says not to, and a statement that fails counts as one that
  ran, since it may have taken effect first. DDL invalidates the catalog's resolutions. A prepared
  statement keeps its route only while the session, the catalog's resolutions, the passthrough
  setting and, for a passthrough statement, autocommit are as they were when it was prepared; when
  any has moved, or the catalog's TTL has passed since, it is routed again before it runs, and refused if
  the answer differs. A batch is checked the same way when it runs, entry by entry, and a statement that
  changes the session or what names resolve to cannot be batched outside passthrough, since the entries
  after it were routed before it ran. On PostgreSQL a Unicode-escaped identifier, `U&"..."`, is refused:
  the parser reads it as an expression with the escapes undecoded.
  A re-read is a SELECT; inside a transaction it would take the first-statement slot the coarse
  SERIALIZABLE path of 4.2 was designed with on SQL Server, so a session-changing statement inside a
  SERIALIZABLE transaction forces the statement-level path (MySQL and MariaDB, as built, need no slot:
  section 5). Not supported: temporary tables named like a ScalarDB table (PostgreSQL
  `pg_temp` is searched first and is not in `current_schemas(false)`; MySQL temporary tables are not in
  `information_schema`), and SQL Server `USE` of another database than the catalog's.
- **No ScalarDB API.** The driver never calls ScalarDB and knows nothing of Cluster. ScalarDB
  transactions run through ScalarDB's own JDBC storage adapter on their own connections. The two are
  independent clients of one database, coordinated only through the row metadata (section 2) and row
  locks.
- **Per-engine strategy**, selected from the URL, for the SQL the driver itself emits:

  | Concern | PostgreSQL | MySQL, MariaDB | SQL Server | Oracle |
  |---|---|---|---|---|
  | Vendor driver, prefix | pgjdbc, `jdbc:postgresql:` | MySQL: Connector/J, or MariaDB Connector/J with `permitMysqlScheme=true` in the URL, both certified, `jdbc:mysql:` (ScalarDB itself reaches MySQL only through the MariaDB driver); MariaDB: MariaDB Connector/J `jdbc:mariadb:` | mssql-jdbc, `jdbc:sqlserver:` | ojdbc, `jdbc:oracle:` |
  | Statement snapshot at READ COMMITTED | yes | yes | only with `READ_COMMITTED_SNAPSHOT` on, checked at connect | yes |
  | Read lock at SERIALIZABLE (4.2) | none: SSI, with ScalarDB's storage connections at SERIALIZABLE (section 5) | a separate locking re-read of the first arm's predicate, LEFT JOINed to `T_meta` by primary key, `LOCK IN SHARE MODE` (as built, the metadata rows locked again by primary key under `FOR SHARE` on MySQL, section 5); the engine's SERIALIZABLE level is not used | the same separate re-read `WITH (HOLDLOCK)`; the engine's SERIALIZABLE level is not used | SERIALIZABLE is refused (section 5) |
  | Insert that may collide | `INSERT ... ON CONFLICT DO NOTHING`, check the row count | catch error 1062; the transaction survives. Not `INSERT IGNORE` for the user's statement, which also downgrades data errors such as a NOT NULL violation to warnings; the driver's own metadata rows use it (4.3) | catch 2627 or 2601; the transaction survives with `XACT_ABORT` off, which the driver requires | catch ORA-00001; statement-level rollback keeps the transaction |
  | Update count | rows matched | `useAffectedRows=false`, the default on both drivers; the value the vendor driver reports for the URL and the connect properties (`getPropertyInfo`, its own spellings and precedence) must not be true, or the connection is refused with `08004` | rows matched | rows matched |
  | In-flight index | partial index `WHERE tx_state IN (1, 2)` | index on `(tx_state, <pk>)` | filtered index `WHERE tx_state IN (1, 2)` | index on `(tx_state, <pk>)` |
  | Retryable errors | SQLSTATE `40001`, `40P01` | SQLSTATE `40001` (error 1213), error 1205 (SQLSTATE `HY000`) | 1205 deadlock victim (`40001`), 3960 snapshot update conflict, 1222 if `LOCK_TIMEOUT` is set | ORA-08177 cannot serialize, ORA-00060 deadlock, ORA-08176; ScalarDB maps the same three |
  | Duplicate key | `23505` | error 1062 | 2627, 2601 | ORA-00001 |
  | Coarse SERIALIZABLE path (4.2) | none: the coarse check, then the statement, as under SNAPSHOT; SSI covers the statement | the coarse check as a locking read of the in-flight index, `LOCK IN SHARE MODE`, whose gap lock blocks every prepare on the table until commit; the user's statement then runs on the data tables with `LOCK IN SHARE MODE` on every SELECT of it (as built, section 5) | the same `WITH (HOLDLOCK)` | SERIALIZABLE is refused |
  | Identifier quoting | `"name"` | `` `name` `` | `[name]` | `"NAME"`; unquoted names fold to upper case |
  | Row limit, boolean test | `LIMIT n`; `NOT COALESCE(P, false)` | `LIMIT n`; `NOT COALESCE(P, false)` | `TOP n`; `CASE WHEN P THEN 1 ELSE 0 END = 0` | `FETCH FIRST n ROWS ONLY`; `CASE WHEN P THEN 1 ELSE 0 END = 0` |
  | Vendor hints the parser keeps (4.7) | pg_hint_plan `/*+ ... */` after SELECT, as the Oracle-style hint node | optimizer `/*+ ... */`, `USE`/`FORCE`/`IGNORE INDEX`, `STRAIGHT_JOIN` | `WITH (NOLOCK)`, `WITH (INDEX(...))`, `OPTION (...)`; not `WITH (UPDLOCK, ROWLOCK)` or `UPDATE T WITH (ROWLOCK)`, which fail to parse and are stripped before parsing | `/*+ ... */` and `--+` after SELECT, UPDATE, DELETE, INSERT and MERGE; `FOR UPDATE OF col`, `NOWAIT`, `SKIP LOCKED`; not `RETURNING ... INTO`, `AS OF`, `MODEL` |
  | Known quirk | none | none | `XACT_ABORT` must be off; the filtered index needs `QUOTED_IDENTIFIER ON` in every session that writes `T_meta`, including maintenance scripts and the backfill; `sqlcmd` defaults it off, so pass `-I` | empty string is NULL, so `before_x = ''` never matches; use `IS NULL` |

- **Identifiers per engine.** Measured on PostgreSQL 16, MySQL 8.4, MariaDB 11.4, SQL Server 2022 and
  Oracle Free 23 with tables created unquoted and imported by ScalarDB 3.19.1, and with a table created
  by ScalarDB from mixed-case names. The driver applies each rule exactly (`Engine`), and asks the engine
  where a rule depends on a setting or on the data.

  | | PostgreSQL | MySQL, MariaDB | SQL Server | Oracle |
  |---|---|---|---|---|
  | Unquoted name folds to | lower case, A to Z only | as written | as written | upper case; beyond ASCII, as Oracle folds (the driver asks it) |
  | Table names compare | exactly | exactly; without regard to case when `lower_case_table_names` is not 0, names then stored in lower case | without regard to case (collation `CI`) | exactly |
  | Column names compare | exactly | without regard to case | without regard to case | exactly |
  | `"x"` | an identifier | a string, unless `sql_mode` has `ANSI_QUOTES` | an identifier while `QUOTED_IDENTIFIER` is on | an identifier |
  | Unqualified name resolves through | `search_path` in order; a plain table earlier shadows a ScalarDB table later | the current database | the user's default schema, then `dbo` | the current schema, then a private synonym, then a public one |
  | How the driver asks | `pg_class` ordered by `array_position` over `current_schemas(false)` | `information_schema.tables`; `database()`, since Connector/J's `getCatalog()` does not follow `USE` | `sys.objects` over the default schema and `dbo`, one query | `all_objects` and `all_synonyms`, one query |
  | Synonyms | none | none | refused | refused |

  ScalarDB keeps names exactly as given on every engine and quotes the names it creates, so its own
  catalog columns are lower case on Oracle, where the driver quotes them; its catalog compares
  `full_table_name` exactly on PostgreSQL and Oracle and without regard to case on MySQL, MariaDB and SQL
  Server, by collation. ScalarDB creates a real view for every decoupled table's virtual name, `T` for a
  created table and `T_scalardb` for an imported one, on every engine. It cannot import an Oracle `INT`
  column, which Oracle stores as `NUMBER(38,0)`; use `NUMBER(10)` or `NUMBER(19)`.
- **Where engine variance lives.** The conversion is mechanical and engine-neutral apart from spelling;
  what differs is how each engine implements its isolation levels. Two rules were chosen to keep that
  variance out of the protocol: the CAS is always conditioned on the anchor and never relies on the
  engine's own first-updater-wins check, so MySQL's "UPDATE sees the latest row" costs nothing; and a
  zero-row CAS always aborts, so an engine that keeps locks on non-matching rows changes nothing. The
  ScalarDB side is engine-neutral by construction, because its adapter already hides the engine. Adding an
  engine is therefore a strategy class plus a run of the interleaving matrix, and only Oracle changes a
  promise (section 5, engine mapping).

  | Part of the mechanism | Engine-neutral? | What varies |
  |---|---|---|
  | Parse, classify, name mapping, rewrite to views and the two-arm UNION ALL | yes | quoting |
  | Views, in-flight index | yes | partial index syntax or a composite index |
  | CAS statement, driver stamp, anchor rules, refuse-in-flight policy | yes | spelling only: the boolean test of the second arm, the NULL-metadata CAS, Oracle's empty string |
  | First-committer-wins at SNAPSHOT | yes, through the anchored CAS | how a conflict surfaces: PostgreSQL, Oracle and SQL Server raise their own error before the CAS; MySQL returns zero rows |
  | Statement-level snapshot at READ COMMITTED | prerequisite | SQL Server needs `READ_COMMITTED_SNAPSHOT` |
  | Read lock at SERIALIZABLE | **no** | none on PostgreSQL, where SSI does the work; a separate locking re-read by primary key on MySQL, MariaDB and SQL Server, because a shared lock taken through a covering index locks only the index entry there; Oracle refuses SERIALIZABLE |
  | Phantoms against ScalarDB inserts | **no** | SSI on PostgreSQL; gap or range locks on MySQL, MariaDB and SQL Server; Oracle refuses SERIALIZABLE |
  | Serializability between driver transactions | **no** | SSI or locking; Oracle refuses SERIALIZABLE |
  | Failed-statement handling, error codes, lock timeouts | plumbing | codes, whether the transaction survives, whether ScalarDB maps the timeout as retryable |

### URL scheme

```
jdbc:scalardb-native:<vendor sub-protocol>://<vendor URL remainder>[?vendor and driver properties]

jdbc:scalardb-native:postgresql://host:5432/db              ->  pgjdbc,              jdbc:postgresql://host:5432/db
jdbc:scalardb-native:mysql://host:3306/db                   ->  Connector/J,         jdbc:mysql://host:3306/db
jdbc:scalardb-native:mysql://host:3306/db?permitMysqlScheme=true -> MariaDB Connector/J, jdbc:mysql://host:3306/db?permitMysqlScheme=true
jdbc:scalardb-native:mariadb://host:3306/db                 ->  MariaDB Connector/J, jdbc:mariadb://host:3306/db
jdbc:scalardb-native:sqlserver://host:1433;databaseName=db  ->  mssql-jdbc,          jdbc:sqlserver://host:1433;databaseName=db
jdbc:scalardb-native:oracle:thin:@host:1521/db              ->  ojdbc,               jdbc:oracle:thin:@host:1521/db
```

- The driver accepts a URL by its prefix, strips the prefix, and hands the remainder to `DriverManager`,
  which finds the vendor driver through its `ServiceLoader` registration. The sub-protocol token selects
  the per-engine strategy, the way ScalarDB's `RdbEngineFactory` picks an engine by URL prefix. The same
  trick is used by p6spy and the AWS Advanced JDBC Wrapper.
- **The sub-protocol is the engine, not the library.** A `mysql` URL gets the MySQL rules whichever
  driver `DriverManager` finds for `jdbc:mysql:`. MySQL is certified through both Connector/J and
  MariaDB Connector/J, the second because ScalarDB uses it and because its LGPL, unlike Connector/J's
  GPL with the FOSS exception, lets a non-FOSS distribution such as the shell bundle it. MariaDB
  Connector/J accepts a `jdbc:mysql:` URL only with `permitMysqlScheme` in the URL itself, read before
  any connect property, as ScalarDB's `RdbEngineMysql` notes; the key passes through as a vendor
  property. With both drivers on the classpath, which one serves the URL depends on `DriverManager`'s
  registration order, so an application keeps one. The library still matters where the drivers differ:
  the SQLSTATE and exception class around the server's error numbers (1213, 1205), what `getCatalog`
  and the metadata calls report, and how `Statement.cancel` reaches the server; the MySQL engine
  configuration runs with each library to certify them.
- Accepted sub-protocols: `postgresql`, `mysql`, `mariadb`, `sqlserver`, `oracle`, the engines ScalarDB
  itself runs on. Anything else is refused at `connect`, not guessed at. Certification order: PostgreSQL,
  then MySQL and MariaDB together, then SQL Server, then Oracle. At connect the driver sets the session
  isolation level explicitly, and on SQL Server checks `sys.databases.is_read_committed_snapshot_on` and
  `snapshot_isolation_state`, refusing the connection without the first and SNAPSHOT without the second.
- Vendor properties pass through untouched. Driver properties are namespaced and few:
  `scalardbnative.inflightWaitMs`, the bounded wait of section 6 under READ COMMITTED, 0 to abort at
  once; `scalardbnative.passthrough`, settable **only** through `setClientInfo`, never from the URL or
  the connect properties, since it turns the protection off; after a passthrough statement whose text
  may set or reset the isolation level, the driver reads the level back from the session; `scalardbnative.metadataSchema`,
  where ScalarDB keeps `virtual_tables` and its table metadata, `scalardb` by default;
  `scalardbnative.catalogTtlMs`, how long a loaded catalog and a resolved name are trusted, 60000 by
  default (4.4); `scalardbnative.allowNoCatalog`, `false` by default, to accept a database without
  ScalarDB's catalog, every table then plain. They are read from the connect `Properties`. A URL is often
  assembled from configuration someone else controls, so only the two settings that cannot weaken the
  protection, `catalogTtlMs` and `inflightWaitMs`, may also come from the URL, in the vendor's parameter
  syntax (`?k=v&...`, or `;k=v` for SQL Server), the URL winning; `metadataSchema` and `allowNoCatalog`
  from the URL fail `connect` with `08001`, as does an unknown or malformed `scalardbnative.*` key. The
  URL is split exactly as the vendor driver splits it: a SQL Server value is braced only when its first
  character is `{`, `}}` inside braces is a literal, and an unbalanced brace refuses the URL; a `?`
  inside the host part, which the vendor drivers read differently, refuses it too. `catalogTtlMs` is at
  most one hour. The driver strips its own keys before handing the rest to the vendor driver, and
  `getPropertyInfo` lists them ahead of the vendor's.
- **What the application holds.** Every JDBC object the driver hands out is its own proxy, and so is
  anything such an object hands back: a statement's connection, a result set's statement (null for a
  metadata result set), a metadata object's connection. `unwrap` to a vendor interface, such as
  `PGConnection`, returns the vendor's object, as JDBC requires; it is an escape hatch like passthrough,
  since SQL run on it reaches the database unchecked. A result set read at SNAPSHOT or SERIALIZABLE
  whose rows are checked one by one (4.2) is not unwrapped: `0AN01`. `unwrap(ScalarDbTables.class)` returns the
  connection itself as the driver's one public interface of its own: given a table's stored schema and
  name it answers plain, ScalarDB, ScalarDB but not set up, or refused (ScalarDB's data, metadata or
  virtual table), from the catalog statements are routed by, so a tool such as the shell's `\dt` marks
  ScalarDB tables without repeating section 2's naming rules. Passthrough, in turn, is per connection, off
  again after `setAutoCommit(false)` or a `setClientInfo(Properties)` without the key; a pool does not
  reset client info, so an application that turns it on turns it off before returning the connection.
- **Errors.** Every error the driver raises itself is a standard JDBC subclass with a fixed SQLSTATE,
  so a caller can tell what to do without parsing messages; a message never contains the URL.

  | SQLSTATE | Exception | Meaning | What the caller does |
  |---|---|---|---|
  | `40001` | `SQLTransactionRollbackException` | a driver-decided abort: a conflict with a ScalarDB or driver transaction (4.4, section 6) | roll back and retry the transaction |
  | `0AN01` | `SQLFeatureNotSupportedException` | a statement or setting the driver does not handle, with the reason: a statement on a ScalarDB table it cannot rewrite, one the parser cannot interpret, a procedure call or a function that runs SQL text (4.1), the transaction mode set in SQL, a passthrough statement outside autocommit; `READ_UNCOMMITTED`, and `SERIALIZABLE` on Oracle once milestone 8 builds it (section 5) | rewrite the statement; never retry |
  | `55000` | `SQLNonTransientException` | a statement names a ScalarDB table the setup of section 3 has not been run for; not `08004`, since it is raised per statement and an `08` state makes a pool discard a working connection | run the setup |
  | `42000` | `SQLSyntaxErrorException` | a name a statement must not use: `T_data`, `T_tx_metadata`, `T_scalardb`, a synonym; the message names the table to use | rewrite the statement |
  | `42501` | `SQLSyntaxErrorException` | ScalarDB's catalog exists but this user cannot read it | grant SELECT on the catalog tables |
  | `08004` | `SQLNonTransientConnectionException` | refused at connect: no ScalarDB catalog (unless allowed), SQL Server without `READ_COMMITTED_SNAPSHOT`, `useAffectedRows` on MySQL or MariaDB | fix the configuration or run the setup |
  | `08001` | `SQLException` | a malformed URL or driver property, a missing vendor driver | fix the URL or the class path |

  A refusal uses `0AN01`, an implementation-defined subclass of the standard's feature-not-supported
  class, and not `0AN01` itself: HikariCP treats `0AN01` as a broken connection and evicts it,
  ending any transaction on it, while the connection is in fact fine.

  Errors the database raises pass through unchanged. The isolation level is not a URL
  property; it is `Connection.setTransactionIsolation`, so `@Transactional(isolation = ...)` works as is.

### Repository and build

The driver lives in its own repository, `scalar-labs/scalardb-native-jdbc`, private during the preview,
and is released on its own. The build follows the conventions of ScalarDB Saga and the ScalarDB
PostgreSQL frontend: Kotlin DSL, convention plugins in `build-logic/`, a version catalog, a JDK 25
toolchain with `--release` for the published surface, and the same checks. Code follows the
[Google Java Style Guide](https://google.github.io/styleguide/javaguide.html); formatting is enforced by
google-java-format through Spotless, and the guide's non-formatting rules, naming, one top-level class per
file, Javadoc on public types and members, no wildcard imports, are enforced by the build where a tool
exists (`forbidWildcardImports`, doclint with its `missing` group) and by review otherwise.

**Layout**

```
scalardb-native-jdbc/
  settings.gradle.kts           rootProject.name; pluginManagement { includeBuild("build-logic") }; include driver, shell, tools, samples
  build.gradle.kts              no source; a repositories block that exists for Dependabot only
  gradle.properties             version=3.19.0-alpha.1-SNAPSHOT; org.gradle.parallel, caching, configuration-cache
  gradle/libs.versions.toml     every version, including those used only as strings, so Dependabot can bump them
  .java-version                 25.0
  build-logic/src/main/kotlin/  scalardb-native-jdbc.{base,java,java11,publishing,license-report}-conventions
  config/spotbugs/exclude.xml
  driver/                       the published driver: com.scalar-labs:scalardb-native-jdbc, package com.scalar.db.nativejdbc
  shell/                        the snsql interactive shell: com.scalar-labs:scalardb-native-shell, a zip with bin/snsql and lib/; docs/plans/shell.md
  tools/                        setup command and stuck-row check: com.scalar-labs:scalardb-native-jdbc-tools; no ScalarDB dependency; added with the first tool
  samples/                      crud-jdbc, crud-spring-data-jdbc, crud-jpa, crud-mybatis: the ORM acceptance suite (4.6)
  docs/design.md                this document
  README.md, RELEASING.md                                  (no LICENSE during the private preview)
```

The `driver` module overrides the archive name and the published coordinates to the bare
`scalardb-native-jdbc`, the way the Saga client SDK overrides its derived name; every other module keeps
the `scalardb-native-jdbc-<module>` pattern.

**Conventions**, from the two template repositories' `java-conventions`:

- `java-library`; `base.archivesName`; `repositories { mavenCentral() }`; reproducible archives
  (`isPreserveFileTimestamps = false`, `isReproducibleFileOrder = true`); javadoc with `isNoTimestamp`
  and `Xmaxwarns` raised, doclint at its full default including `missing`.
- JDK 25 toolchain for compiling, Error Prone, NullAway and all tests. NullAway's `annotatedPackages`
  is `com.scalar.db.nativejdbc`; packages are `@NullMarked` with jspecify.
- Spotless: `target("src/*/java/**/*.java")`, `importOrder()`, `removeUnusedImports()`,
  `forbidWildcardImports()`, `googleJavaFormat(<catalog version>)`, `formatAnnotations()`;
  `spotlessCheck` runs under `check`, `spotlessApply` formats.
- SpotBugs at `Effort.MAX`, `Confidence.MEDIUM`, with FindSecBugs and the HTML report, excludes in
  `config/spotbugs/exclude.xml`.
- Test suites through `testing.suites`: JUnit Jupiter `test`, and an `integrationTest` `JvmTestSuite`
  wired into `check`; AssertJ and Mockito. The driver's integration tests are the interleaving matrix on
  Testcontainers images of the five engines, PostgreSQL first, MySQL through both Connector/J and MariaDB
  Connector/J, with a system property to point at an
  external database instead. The engine containers (`testing.Engines`) are the driver's test fixtures,
  which the shell's integration tests use too; the fixtures are not published.
- Publishing: the Vanniktech `maven-publish` plugin with explicit coordinates, signing only when a key is
  configured, `publishToMavenCentral()`. No license file during the private preview, so the templates'
  `META-INF/LICENSE` step is left out until one is chosen; publishing then goes to GitHub Packages or to
  release assets, and the conventions are ready for Central when the preview becomes public. `RELEASING.md` follows ScalarDB's branching model: `main` for the
  next version, a version branch per line, `3.19`, releases cut from the branch, never from `main`.
- Workflows, from Saga: `ci.yml` runs `check` on JDK 25 (Temurin) for `main`, version branches and pull
  requests, then a compiler-warnings pass without the build cache, and uploads test reports on failure;
  `auto-pr.yml`. To be added with the first release: `release-snapshot.yml`, a snapshot on every push to
  `main` and version branches, and `release.yml`, from a tag. Two additions: an engine matrix job that runs the
  interleaving matrix on the hosted runner's Docker, and a nightly that runs it against the newest 3.19
  patch and against the 4.0 line, which is not published, so the nightly clones ScalarDB `master` and
  runs `./gradlew :core:publishToMavenLocal` first, as the frontend's build does.

**Java target: 11 for the driver artifact, 25 toolchain for everything else.** JSQLParser 5 needs
Java 11, and 11 is the widest floor that allows it: a Java 11 jar loads in every application stack that
can use the driver, Spring Boot 2.7 on 11, Hibernate 6 on 11, Spring Boot 3 on 17, Spring Boot 4 on 17 or
later, while a Java 17 jar would remove the first two. Raising the floor later is a one-line change;
lowering it would be a rewrite. The mechanism is Saga's `java8-conventions` pattern with 11 in place of 8:

```kotlin
// build-logic: scalardb-native-jdbc.java11-conventions.gradle.kts
plugins { id("scalardb-native-jdbc.java-conventions") }
tasks.named<JavaCompile>("compileJava") {
    options.release = 11                                   // Java 11 API and bytecode, checked by ct.sym
    options.compilerArgs.add("-Xlint:all,-options")        // keep javac lint on the published surface
}
// tests are not constrained: they run on the JDK 25 toolchain
```

`driver` applies it, so records, switch expressions and pattern matching are unavailable in the driver's
main sources, while its tests use whatever the toolchain offers. `tools` applies it too, since it is a
command-line tool that should run wherever the driver does, and so does `shell`. The `samples` are Spring Boot 3 on Java 17
and Spring Boot 4 on Java 21, each with its own `release`.

**Dependencies**, all from the catalog:

| Module | Runtime | Compile-only | Test |
|---|---|---|---|
| `driver` | `jsqlparser`, `slf4j-api`. **No ScalarDB dependency.** | the five vendor JDBC drivers, which the application supplies | `scalardb` (newest 3.19 patch), Testcontainers, the vendor drivers, JUnit, AssertJ, Mockito, `logback-classic` |
| `shell` | the driver, `jline`, `picocli`, `slf4j-nop`; in the distribution zip also pgjdbc, MariaDB Connector/J (for MySQL as well), mssql-jdbc and ojdbc11, at the versions the integration tests run, with their licence files. Not Connector/J, whose GPL the FOSS exception does not lift for a non-FOSS distribution; a user who wants it adds it to `lib/` | | Testcontainers, the vendor drivers, JUnit, AssertJ |
| `tools` | the driver, `picocli`, `slf4j-api`, `logback-classic`. **No ScalarDB dependency**: settling rows is ScalarDB's (section 6) | | as above |
| `samples` | Spring Boot and the ORM of each sample; the driver | | the sample's own tests, against the driver and real ScalarDB transactions |

**Version and compatibility.** The driver version mirrors the ScalarDB line it is certified against:
`3.19.0-alpha.1`, `3.19.0-alpha.2`, and so on during the preview, where the major and minor say
"certified against ScalarDB 3.19.x" and the rest is the driver's own numbering. Certification is per
ScalarDB line, not per patch: patch releases do not change the storage format or the protocol, so the
contract tests run against the newest patch of the line and the nightly picks each new patch up. A new
minor or major gets its own certification run, and the README carries the table of certified ScalarDB
lines and engines. The facts of section 1 were read on the 4.0 line and confirmed at the contract level
on the 3.19 line (decoupled layout, virtual tables, the split put and its "not exists" conversion, the
`tx_id`-only prepare condition, before-image nulling, `hasBeforeImage`, the 15 s lifetime, the
batching in `mutate`, `recoverRecord`), and on 2026-10-09 every line-level claim was re-read on the
`v3.19.1` tag: the recovery types per isolation level, the background roll and the expiry guard with its
re-read, the ignored record-commit failure, the one-phase-commit guard, the split put and its "not
exists" conversion, the batching order, the rollback of NULL metadata, validation on `tx_id`, the
per-engine conflict codes and minimum isolation levels, the catalog and naming. All identical to the
4.0 line; the files differ elsewhere. From here the contract tests carry the facts forward.

**Stability and migration.** The driver moves into the ScalarDB repository as a subproject once the
protocol has stopped moving: the interleaving matrix green on the certified engines, and one preview
cycle without a protocol change. Everything above is chosen so that the move is mechanical: the package
name is final, the conventions match ScalarDB's, and `driver/` becomes `native-jdbc/` with its
`build.gradle.kts` rewritten into the host build's Groovy and its integration test task renamed
`integrationTestJdbc` so the host's engine matrix picks it up. `tools` goes to a ScalarDB tool and the
samples stay where they are useful. Whichever repository does not host the driver keeps a contract test
that runs the driver's interleavings against the published artifact, so a change to a composer, to the
split put or to a catalog table on either side fails a test the same day.

---

## 2. Invariants

- **I1 Version identity.** `tx_id` identifies one version of a row; it is unique per write (ScalarDB and
  the driver both use UUIDs). `tx_version` is informational: a rollback restores `before_tx_version`, so
  it is not monotonic. **The fresh `tx_id` is load-bearing**: ScalarDB's prepare condition and its
  SERIALIZABLE validation look at `tx_id` only (facts 2 and 7), so a driver write that merely bumped the
  version would be invisible to both. Never replace the UUID stamp with a version bump.
- **I2 Every writer conditions on what it read.** ScalarDB on `tx_id`; the driver on `tx_id`,
  `tx_version` and `tx_state = 3`. The database runs the conditional UPDATE under the row lock, so
  check and write are atomic.
- **I3 State 3 is a committed value.** A row in state 1 or 2 is in flight: its committed value is the before
  image if the transaction is undecided or aborted, the after image if it committed, and the driver cannot
  tell which.
- **I4 The driver never overwrites an in-flight row and never returns its after image.** Under READ
  COMMITTED it returns the before image. Under SNAPSHOT and SERIALIZABLE it refuses the row.
- **I5 Driver stamp.** A driver write leaves the row in state 3 with a fresh `tx_id`, `tx_version + 1`
  (1 for an insert), `tx_prepared_at = tx_committed_at = now`, `before_*` untouched (null on insert), and
  **no coordinator record**. ScalarDB's prepare then loses on `tx_id`, its insert loses on
  "not exists", its prepare of a deleted row finds nothing, or finds the tombstone of 4.5 where the row
  had no metadata, and it never looks up the coordinator for a
  state 3 row (fact 6).

---

## 3. Views

Two surfaces per table. `cc_T` is ScalarDB's own virtual table, shown here in its own terms; `rc_T` is
created by the driver's **setup command**, once per table, together with the in-flight index below and
the backfill of section 1. The command needs DDL privileges and is run by an operator, never at connect;
when it loads the catalog the driver only checks that the view and the index exist, and a statement that
names a table without them is refused with `55000`. Example for `T(id, x, y)`; a composite key joins on
every key column.

The setup's names, in the table's schema: the view `rc_T` and, on the metadata table, the index
`T_inflight`, each affix spelled the way the engine folds an unquoted name and joined to the table's
stored spelling (`RC_ORDERS` for an Oracle table stored `ORDERS`, `RC_made` for one ScalarDB created
as `"made"`); a name beyond the engine's identifier limit is refused rather than truncated. Per table,
in order: the index, built without blocking writes where the engine can (`CREATE INDEX CONCURRENTLY` on
PostgreSQL, `ALGORITHM=INPLACE, LOCK=NONE` on MySQL and MariaDB, `ONLINE` on SQL Server editions that
have online builds and on Oracle where the edition allows); on an imported table the backfill, which
stamps `tx_id = 'scalardb-native-backfill'`; then the view, with `CREATE OR REPLACE` (`CREATE OR
ALTER` on SQL Server); then, on Oracle and MySQL, a histogram on the metadata table's `tx_state`
(`DBMS_STATS` with `FOR COLUMNS TX_STATE SIZE 254`; `ANALYZE TABLE ... UPDATE HISTOGRAM ON tx_state`),
without which both estimate `tx_state IN (1, 2)` as most of the table and scan it (Oracle 22x, MySQL
12-69x on the split read); last a grant of `SELECT` on the view to each application user given. The backfill waits only for rows ScalarDB has in flight over a deemed-committed
row, which carry `before_tx_id` NULL and `before_tx_version` 0, never for an in-flight insert.

A table is set up when its index exists and is usable (an invalid index a failed concurrent build left
does not count) and its view exists with exactly the table's columns, in order. Because the view comes
last, it also says that the backfill completed. Names compare exactly, except where the database
compares table names without regard to case. The setup does not replace a view of the same name whose
columns differ, since it did not create it. Other drivers see a new setup after their catalog TTL.

Taking the setup down drops the view and the index. On PostgreSQL the view depends on its tables, so
ScalarDB's `dropTable` and `dropNamespace` need the teardown first; ScalarDB itself refuses the other
schema changes on a decoupled table (adding, altering, dropping or renaming a column, renaming the table,
repairing it), so the view's columns cannot drift through ScalarDB, only through DDL run outside it,
which the column check then reports as not set up. Two costs to know: on SQL Server the filtered index
requires `QUOTED_IDENTIFIER`, `ANSI_NULLS`, `ANSI_PADDING` and `ANSI_WARNINGS` on in every session that
writes the metadata table (the JDBC driver's defaults are), and on PostgreSQL the partial index's
predicate on `tx_state` prevents heap-only-tuple updates when ScalarDB changes a row's state.

```sql
-- cc_T: raw columns + metadata + before image. The driver's surface.
CREATE VIEW cc_T AS
SELECT t.id, t.x, t.y,
       m.tx_id, m.tx_version, m.tx_state,
       m.before_tx_id, m.before_tx_version, m.before_x, m.before_y
FROM T t JOIN T_meta m ON m.id = t.id;

-- rc_T: the committed image. READ COMMITTED surface, also safe for any native reader.
CREATE VIEW rc_T AS                                     -- with the reader's rights, see below
SELECT t.id, t.x, t.y                                   -- own columns: T's indexes apply
FROM T t LEFT JOIN T_meta m ON m.id = t.id              -- never unmatched for a created table
WHERE COALESCE(m.tx_state, 3) = 3                       -- NULL metadata is committed (fact 2)
UNION ALL
SELECT t.id, m.before_x AS x, m.before_y AS y           -- the before image of a row in flight
FROM T t JOIN T_meta m ON m.id = t.id
WHERE m.tx_state IN (1, 2)                              -- the in-flight index
  AND (m.before_tx_id IS NOT NULL OR m.before_tx_version IS NOT NULL); -- an in-flight insert has no committed image
```

The two arms are the two arms of 4.2 without the user's predicate. The first arm returns `T`'s own
columns, so a filter on them reaches `T`'s indexes; a view of one `CASE` per column would hide every
non-key column behind an expression and turn each such filter or join into a scan (40-800x on a non-key
predicate, `docs/benchmarks/read-overhead.md`). Every engine pushes a filter into each arm of the UNION
ALL view, but none pushes a join predicate into it, PostgreSQL included: through `rc_T` a key join from
a plain table is 24-236x slower than on `T`, and a join of two ScalarDB tables on MySQL up to 1800x.
MariaDB also pushes a filter only when its value is written in the statement, as MariaDB Connector/J
sends it by default; with `useServerPrepStmts=true` a `?` reaches the server unbound and a filter through
`rc_T` scans the table. So `rc_T` is the committed image for native readers and the driver's fallback,
not its read route: the driver splits each read over the copies of its tables instead (4.2, the split
read), which keeps every join and filter on the base tables' indexes.

**Privileges.** The view runs with the reader's rights where the engine has that: `security_invoker` on
PostgreSQL 15 and later, `SQL SECURITY INVOKER` on MySQL and MariaDB, so row-level security, column
grants and the like on `T` and `T_meta` hold through it. On PostgreSQL before 15, SQL Server (ownership
chaining) and Oracle the view runs with its owner's rights over its tables: row-level security that
predicates on the table (SQL Server security policies, Oracle VPD) still applies, column privileges do
not, so grant the view only to users that may read every column of the table. The setup grants `SELECT`
on the view to the application users it is given; they need their usual ScalarDB privileges on `T`,
`T_meta` and ScalarDB's metadata schema besides. MySQL, SQL Server and Oracle list a view or an index
only to a user with a privilege on it or its table, so without that grant the driver reports a set-up
table as not set up; the `55000` message says the objects are missing or not visible to this user.

A row in state 2 keeps its before image and stays visible until ScalarDB's record commit removes it.
For an imported table the ScalarDB surface is the LEFT OUTER view `T_scalardb`; a row without metadata
shows a NULL `tx_state` there and counts as committed (fact 2). Neither `rc_T` nor `T_scalardb` accepts a
locking clause on PostgreSQL, because both have an outer join; the driver rewrites locking reads onto the
base tables (4.2, locking clauses).

**Indexes.** The reads of 4.2 are a UNION ALL of two arms. The first arm uses the user's own index on
`T(x)`. The second arm needs only one index per table over the in-flight rows: on PostgreSQL a partial
index, `CREATE INDEX ON T_meta (<pk>) WHERE tx_state IN (1, 2)`, and a filtered index of the same shape on SQL
Server; on MySQL, MariaDB and Oracle, which have no partial indexes, a plain index on `(tx_state, <pk>)`. With few rows in flight, scanning them all and filtering `P_before`
in SQL is cheap for any predicate. The cost grows with the in-flight count, though (at 10k rows in
flight, 3-6x on PostgreSQL and Oracle, up to 365x on SQL Server, which scans the metadata table), unless
an index on the before image serves `P_before`. ScalarDB already creates one: a `before_x` index
whenever `x` is one of its secondary indexes, unless eventually consistent index reads are enabled.
Since `before_*` is NULL on every row not in flight, its plain index holds what a filtered one would,
and with it the selective reads return to their cost at 100 rows in flight
(docs/benchmarks/read-overhead.md). The driver creates no `before_*` index. The setup reports each
index of the data table that has no `before_*` counterpart (a multi-column index, or one created
outside ScalarDB, as on imported tables), and tables without before-image indexes because eventually
consistent index reads are on, with the DDL to add each; it creates them only on an explicit option,
and then owns and drops them (todo 102). Primary-key access needs none: the key bounds the metadata
lookup.
Not on `tx_state`: ScalarDB's own before-index lookups carry no state predicate, so a `tx_state` partial
index would be unusable for them. The OR form across the join,
`x = ? OR before_x = ?`, cannot use either index on PostgreSQL or MySQL, because neither pushes a
cross-table OR into both scans and neither rewrites it to a UNION; on a single table they would (BitmapOr,
index merge). ScalarDB splits its own scans this way for indexed columns only: `requiresBeforeIndexCheck`
runs a second lookup on the before image index; for other columns it uses the OR form `P OR P_before`
(`convertConjunctions`).

---

## 4. Statement rules

### 4.1 Routing

| Statement | Route |
|---|---|
| Touches plain tables only | pass through unchanged |
| SELECT over plain and/or ScalarDB tables | each ScalarDB table name replaced by the level's read surface (4.2); one native statement, except the coarse-check path of 4.2, which sends the check first, and the SERIALIZABLE locking re-read of 4.2 on MySQL, MariaDB and SQL Server |
| INSERT / UPDATE / DELETE on exactly one ScalarDB table | 4.3 |
| DML on several tables, INSERT ... SELECT, MERGE, DML mixing plain and ScalarDB tables, UPDATE of a key column, anything the parser cannot interpret | **refuse** with a clear error; never guess. 4.7 lists the vendor syntax the parser does not interpret and the hints refused on a ScalarDB table |
| A session statement: `SET`, `RESET`, `USE`, `ALTER SESSION` | pass through, then re-read the session state (section 1); refused if it reads a table, as in `SET x = (SELECT ...)`, and `SET autocommit` and `SET TRANSACTION` are refused, since the JDBC methods carry those. Forms JSQLParser does not parse, such as PostgreSQL `SET search_path TO a` (write `= a`, or call `setSchema`) and `BEGIN`, fall under "anything the parser cannot interpret" |
| A procedure call: `CALL`, `EXEC`, `{call ...}` | **refuse** (`0AN01`): the driver cannot see what the procedure touches; run it in passthrough mode if it touches no ScalarDB table |
| A function that runs SQL given as text: PostgreSQL `query_to_xml` and the other XML exports, `dblink`, `crosstab`, `connectby`, `ts_stat`, `ts_rewrite`; Oracle `dbms_xmlgen`, `dbms_xmlquery`, `dbms_xmlstore`, `dbms_xmlsave`, `dbms_sql`, the URI types (`DBURITYPE`, `XDBURITYPE`, `HTTPURITYPE`, `URITYPE`) and `SYS_DBURIGEN`, `XMLQUERY`, `XMLTABLE`, `XMLEXISTS` (XQuery reads tables through `fn:collection("oradb:...")`); SQL Server `OPENQUERY`, `OPENROWSET`, `OPENDATASOURCE` | **refuse** (`0AN01`) wherever it appears, for the same reason; run it in passthrough mode if its SQL touches no ScalarDB table. A function the user wrote that runs SQL or changes the session cannot be recognised and is not supported on a connection that also reaches ScalarDB tables |
| A name through a synonym, `T_data`, `T_tx_metadata` or `T_scalardb` | **refuse** (`42000`), naming the table to use (section 1, "The catalog") |
| DDL, bulk maintenance | passthrough mode only, in autocommit, with the table quiesced; ScalarDB refuses schema changes on decoupled tables, such as adding a column (`throwIfTransactionMetadataDecouplingApplied`), so DDL there goes through ScalarDB's admin or not at all |

**What "the parser can interpret" means.** The text is exactly one statement; JSQLParser parses it;
every table node of the parser's public syntax tree is a reference the walk visited, a column qualifier
or a `FOR UPDATE OF` target (the driver reads the public tree only, never private fields, so it gives the
same answers with JSQLParser as a named module); no comment is of a form this engine executes, each rule
the engine's own and checked on its container: MySQL and MariaDB run `/*! ... */`, MariaDB also
`/*M! ... */`, which the driver refuses on MySQL as well since a `mysql` URL may be served by a MariaDB
server, and both read a `--` not followed by whitespace as two minus signs, MySQL, MariaDB and Oracle end a block
comment at its first `*/` where PostgreSQL and SQL Server nest; no name is a database-qualified one or a
quoted name holding a dot; and no name is double-quoted where the session reads `"x"` as a string.
There is no list of known node types: an expression the shape does not inspect cannot hide a table or a
column, since both are taken from the tree. A single-table shape further refuses the modifiers and
clauses that read or write something else than the table's rows: `INSERT`, `UPDATE` and `DELETE IGNORE`,
`ON DUPLICATE KEY UPDATE`, `ON CONFLICT`, `OUTPUT`, `SKIP LOCKED`, `NOWAIT`, `WAIT`, optimizer and table
hints, `TABLESAMPLE`, `PIVOT`, a database link, `DELETE ... USING`, `FOR PORTION OF`, `CONNECT BY`,
`QUALIFY`, `LATERAL VIEW`, `FOR XML`, `INTO OUTFILE`, a temporary target table and `PREWHERE`; and a key
equality is a key column compared with a placeholder or a literal, possibly signed or cast. A statement
other than a single-table shape that locks rows or writes its result anywhere (`FOR UPDATE`, `FOR SHARE`,
`SKIP LOCKED`, `INTO`) is refused on the split read (4.2), and so is a rewrite that would rename a table a
`FOR UPDATE OF` names by its name.

### 4.2 Reads

`P` is the user's predicate. `P_before` is `P` with every non-key column `c` replaced by `before_c`.
Every read below is a UNION ALL of two arms over `cc_T`, sent as **one statement**, so both arms come
from one snapshot and each arm gets its own index plan (section 3, Indexes). Under READ COMMITTED the
single statement is mandatory; under SNAPSHOT and SERIALIZABLE the transaction-wide snapshot allows the
split the SERIALIZABLE bullet describes. The user's ORDER BY, LIMIT or aggregate is applied on top of the
union as a derived table.

- **READ COMMITTED**: key lookups and simple single-table predicates:
  ```sql
  SELECT <user columns>, tx_id, tx_version FROM (
    SELECT <raw columns>, tx_id, tx_version FROM cc_T
    WHERE COALESCE(tx_state, 3) = 3 AND P                               -- index on T(x)
    UNION ALL
    SELECT <before_* columns AS user columns>,
           before_tx_id AS tx_id, before_tx_version AS tx_version FROM cc_T
    WHERE tx_state IN (1, 2) AND P_before                               -- in-flight index
      AND (before_tx_id IS NOT NULL OR before_tx_version IS NOT NULL)   -- no committed image: skip
  ) v ORDER BY ... LIMIT ...;
  ```
  The arms are disjoint by state. The driver strips the metadata and **remembers `(tx_id, tx_version)`
  per key as the anchor** for a later write in this transaction; an anchor whose `tx_id` is NULL, with
  a version of NULL or 0, is the NULL-metadata anchor of 4.4. For a row from the second arm that is
  the **before image's identity**, `(before_tx_id, before_tx_version)`, never the in-flight writer's: a
  ScalarDB rollback restores exactly that pair, and a commit keeps the writer's `tx_id`, so the CAS of
  4.3 later matches if and only if the row is back at the version this transaction read. Anchoring on
  the writer's pair would let the CAS succeed after the writer committed, a lost update. A join takes
  the split read below, and when it is read row by row, each copy appends the same key and version
  for every table joined in the top-level FROM clause, a LEFT JOIN's nullable side included: those of
  the table's piece in that copy, NULL for its unmatched piece, which anchors nothing. A table only in
  a subquery returns no row and takes no anchor, nor does any table of a grouped or DISTINCT read,
  nor a read through `rc_T`. The versions cost two lookups per anchored table and row, up to 11x
  on a join of 150k rows, so a read takes them only where they are recorded: in a transaction, on a
  connection that is not read-only. A statement prepared in the other mode is prepared again when it
  runs; either text returns the same rows. A
  user `SELECT ... FOR UPDATE` or `FOR SHARE` of one table, read row by row, reads the **first arm
  alone**: the statement as written over the first arm's derived table, its locking clause, `NOWAIT`
  and `SKIP LOCKED` included, which on PostgreSQL locks the rows of `T` only, the in-flight check
  being a sublink the lock does not reach, and blocks a ScalarDB prepare on them until commit; no
  union, which PostgreSQL would refuse a locking clause on. A row in flight is a row another
  transaction holds, as a native lock would treat it: `SKIP LOCKED` skips it, the first arm leaving
  it out already; otherwise the driver first checks the predicate over the second arm, and a row
  there fails a `NOWAIT` with `55P03` and otherwise waits the bounded wait of section 6, then aborts
  with `40001`, which at SNAPSHOT and SERIALIZABLE it does at once, `NOWAIT` or not; it is made again after the lock, for a row that went in flight before the lock
  statement began, which the first arm then leaves out unseen. That check ignores `ORDER BY ... LIMIT`, so a row in flight that the limit would not
  have reached waits or aborts too, conservatively. The second arm is never locked nor returned: a
  lock on its rows would block the ScalarDB commit, rollback or recovery that has to settle them, and
  a later write to such a key by this transaction would then wait, in 4.3 step 1, on a row that
  cannot settle while this transaction holds the lock. **Under the lock the driver reads each locked
  row's metadata again**, and those versions are the rows' anchors: PostgreSQL re-checks a row
  updated after the statement began against the other tables as they were when it began, the
  metadata table included, so a row a ScalarDB prepare wrote in between, queued for the lock ahead of
  the read, would pass as committed with its after image. Each attempt runs under a savepoint: when
  either check finds a row in flight, its rollback releases the attempt's locks, and the read is
  made again, at once under `SKIP LOCKED`, which then skips the row, after the wait otherwise;
  `NOWAIT` fails with `55P03`. `FOR KEY SHARE` is refused: a ScalarDB prepare writes the row with a
  non-key UPDATE, which it does not block. **On MySQL and MariaDB** a locking clause outside a derived
  table locks none of the rows the derived table reads, so the statement is parsed again without its
  clause, which goes into the first arm, whose derived table the engines merge into the statement,
  the user's predicate with it: only the rows it matches are locked (measured on MySQL 8.4 and MariaDB
  11.4). The merge is asked for, `/*+ MERGE(t) */` on MySQL and `SET STATEMENT
  optimizer_switch='derived_merge=on' FOR` on MariaDB, since a session that turns merging off would
  otherwise lock every committed row. Only `FOR UPDATE` and `FOR SHARE` are taken; `OF` adds nothing
  on one table and is dropped. There the first arm leaves a row in
  flight out by a scalar subquery on its state, a condition on the row itself: these engines lock a
  row as they read it, and release the lock of a row that a condition on it rejects, but keep the lock
  of a row a join rejects, which would hold up ScalarDB's commit of it. A shared lock read through a
  covering index locks the index entry only, not the row, so under `FOR SHARE` the versions read under
  the lock lock each row again through the primary key, at READ COMMITTED not its metadata row on
  MySQL (at REPEATABLE READ under `FOR UPDATE` too, and the metadata row with it, 5). `LOCK IN SHARE MODE`, which the parser does not read, is parsed
  as `FOR SHARE`, the engines' spelling restored in the statement run; `NOWAIT` fails as the engine
  reports its own, MySQL error 3572 and MariaDB 1205. These engines lock a row before the rest of the
  predicate runs, so a row goes in flight around the lock only through a ScalarDB prepare queued for
  it ahead of the read, which the versions read under the lock catch. On SQL Server any user locking
  clause is taken as the separate primary-key re-read of the SERIALIZABLE bullet. A user locking
  clause on a join or a subquery is refused for now.
- **SNAPSHOT**: the detector `P OR (tx_state IN (1,2) AND P_before)`: a row in flight that the read
  would return, by its after image or its before image, aborts the transaction (section 6). Where every
  ScalarDB table of the read returns rows, the tables the anchors of 4.2 cover, the read is the split
  read with each committed copy **taken whole**, rows in flight included with their after images, and
  each table's piece appending its row's `tx_state` after the anchor columns; the in-flight copies keep
  the before images. Any row with `tx_state` 1 or 2 aborts the read as the application reaches it,
  marking the transaction rollback-only. Rows in state 3, or with NULL metadata, are committed. A row
  in flight that neither image brings into the result, past a row limit included, changes nothing
  whichever image is true, so it does not abort. Two shapes break that and take the per-table check:
  an outer join, where a row in flight decides whether a row comes back unmatched without being
  returned itself, and an OFFSET, whose skipped rows count without being returned. A scrollable
  result set runs the per-table check too, since its moves pass over rows. One table is the two-arm form:
  ```sql
  SELECT <user columns>, <anchor columns>, tx_state FROM T WHERE P                 -- index on T(x)
  UNION ALL
  SELECT <before_* columns>, <anchor columns>, tx_state FROM <in-flight copy> WHERE P_before
  ```
  Every other shape, a subquery's or an expression's table, a grouped, aggregate or DISTINCT read,
  one past the copy limit, takes the **per-table check** below.
- **Why one statement, not two.** Under READ COMMITTED every statement has its own snapshot. A row in
  flight with `x = x2` and `before_x = x1` fails the `P` arm; if its writer rolls back before a second
  statement runs, the row is committed with `x = x1` and no before image, and fails the in-flight arm
  too. Both arms miss a committed match. One statement reads both arms from one snapshot and cannot.
  Pipelining or a multi-statement string still gives two snapshots. Under SNAPSHOT and SERIALIZABLE the
  snapshot is transaction-wide, so two statements would be safe there and the single statement is only
  cheaper.
- **The split read** (READ COMMITTED, any shape). A statement naming ScalarDB tables `T1 ... Tn` is
  repeated once per combination of their **copies**, 2^n statements joined by UNION ALL into one: in a
  copy each table is either *committed*, `T` with its rows in flight excluded and its own columns, or
  *in flight*, `T JOIN T_meta` restricted to the rows in flight that have a before image, with every
  non-key column `c` read as `before_c`. Either reads only the columns the statement may read from
  the table, every column it qualifies by the table and every unqualified one, and all of them under
  a `*` or a NATURAL or USING join; on a 50-column table this shortens the statement up to 5x, which
  MySQL and MariaDB parse on every execution. A row is in exactly one copy of its table, so the copies are
  disjoint and need no dedup, and each copy is the user's own statement over base tables, so every
  join and filter keeps its indexes; the copies with a table in flight start from the in-flight index
  and are empty unless a matching row is in flight. Measured (`docs/benchmarks/read-overhead.md`):
  one-table shapes 1.0-1.7x the plain query, joins of two tables up to 2.6x, three tables up to 3.1x,
  four up to 5x, full scans up to 2.5x; with a 1 ms round trip to the database short queries come
  within 1.0-1.8x. A form with n + 1 copies through LATERAL was measured and rejected: a table after
  the in-flight one can be joined only through its unindexed before image.
  - **How a committed table excludes its rows in flight**, per engine: `NOT EXISTS (SELECT 1 FROM
    T_meta q WHERE q.<pk> = t.<pk> AND q.tx_state IN (1, 2))` on PostgreSQL and Oracle; on MySQL and
    MariaDB a left join to `(SELECT DISTINCT <pk> FROM T_meta WHERE tx_state IN (1, 2))` with the key
    NULL, which makes them build the in-flight keys once instead of probing the metadata per row (the
    set grows with the in-flight count, so a table the statement reaches through the first column
    of its primary key, in the database's order, or a ScalarDB-indexed column, by = or IN with a
    list in a top-level conjunct of the WHERE or an ON clause, from constants or tables already
    reached, takes the per-row left join on `T_meta`'s key instead once it has more than 500 rows in
    flight, which measured flat for tables reached through their key while the set grew 1.6-23x
    from 1,000 rows (todo 113); a table reached only
    through a range, which may read thousands of rows, once it has more than 5,000; either goes back
    to the set below half its threshold (todo 112); the count is read through the in-flight index,
    capped at 5,001, and kept for a second per connection, and a
    prepared statement whose form the count changes is prepared again with its settings and
    parameters replayed, so pooled statements follow the count, todo 103); on
    SQL Server `NOT EXISTS` in the all-committed copy of a statement over one or two ScalarDB tables
    and the DISTINCT set over three or more, and in the copies with a table in flight a left join on
    `T_meta`'s key with `COALESCE(tx_state, 3) = 3`, since SQL Server otherwise plans those copies as
    if empty and scans the in-flight index once per row (5-10x with rows in flight).
  - **On top of the union**: the user's ORDER BY, row limit, DISTINCT and window functions, over the
    copies as a derived table. A statement with ORDER BY and a row limit also sorts and stops each
    copy after offset + n rows, so pagination does not read every matching row (todo 089: 20 of
    500k rows 36-365x down to 1.1-1.5x): `LIMIT` inside the copy on PostgreSQL, MySQL and MariaDB,
    `WHERE ROWNUM <=` around the ordered copy on Oracle, whose `FETCH FIRST` numbers the whole
    copy, and nothing on SQL Server, whose plan already stops early over the union. On MySQL every
    committed table of such a statement uses the per-row left join when offset + n is at most
    1,000, since the DISTINCT set and NOT EXISTS are built whole before the first row. Only for a literal offset and count, or a
    count placeholder without offset, and not under DISTINCT or an aggregate. A whole-query aggregate that decomposes (`COUNT`, `SUM`, `MIN`, `MAX`,
    `AVG` as `SUM` and `COUNT`, each with its `GROUP BY`) is computed per copy and combined on top on
    every engine but PostgreSQL, so that no copy streams its rows through the union (full scans
    3-10x down to 1.2-2.5x); any other aggregate is applied on top of the union. Per copy, each copy
    groups by the GROUP BY columns and selects one partial result per aggregate, and the statement
    on top groups again and combines them: COUNT as SUM (cast back to BIGINT on MySQL and MariaDB),
    SUM, MIN and MAX as themselves, AVG as SUM over COUNT. A DISTINCT aggregate, and AVG on SQL
    Server, whose AVG of a decimal rounds differently from SUM over COUNT, are applied on top. The
    split takes GROUP BY over columns only, with every column outside an aggregate grouped, and
    COUNT, SUM, MIN, MAX and AVG; anything else stays on `rc_T`. An unaliased item keeps the label
    the engine gives the original: PostgreSQL names a function call by the function, SQL Server
    names no expression, and MySQL, MariaDB and Oracle name it by its text, which the item on top
    takes as its alias.
  - **Which tables are split.** The statement is repeated per combination of *pieces* of its ScalarDB
    table references, each piece reading the table through the fragment of its copy. Four kinds of
    reference, by where the table stands (todo 095):
    1. **Inner**: a table joined by an inner join in the top-level FROM clause, a LEFT JOIN's
       preserved side included. Two pieces, committed and in flight. A row of the result comes from
       exactly one row of the table, which is in exactly one copy.
    2. **Nullable**: one table, not a join, on the nullable side of a top-level LEFT JOIN with an ON
       clause (a RIGHT JOIN, USING and NATURAL stay on `rc_T`), the ON clause naming only it and the
       tables before it, every column qualified: the unmatched piece repeats the ON clause in the
       WHERE clause, where an unqualified column could also name a table joined later (todo 123).
       Three pieces. In the committed and the in-flight piece the LEFT JOIN becomes an inner join
       with that copy's fragment. The **unmatched piece** joins an empty fragment, `(SELECT <columns> FROM T WHERE
       1 = 0)`, so every column of the table is NULL with its own type, and adds to the WHERE clause
       ```sql
       NOT EXISTS (SELECT 1 FROM <committed fragment> b WHERE <ON clause>)
       AND NOT EXISTS (SELECT 1 FROM <in-flight fragment> b WHERE <ON clause>)
       ```
       The subqueries keep the table's alias, so the ON clause is copied unchanged and its references
       to the table resolve to the subquery. A preserved row with matches gets one row per matching
       row, each in exactly one copy; one without matches gets exactly one NULL-extended row; an ON
       clause that is UNKNOWN is no match both in the join and in `NOT EXISTS`. Predicates after the
       join see the values of the plain statement, NULLs in the unmatched piece. Measured with one
       inner table (6 pieces): 1.3-2.5x by hand, against 376-1495x through `rc_T` on MySQL and
       MariaDB; through the driver 1.2-2.3x on every engine (todo 095).
    3. **Semi-joined**: the one ScalarDB table of an `IN (subquery)` or `EXISTS (subquery)` that is
       a top-level conjunct of the WHERE clause, the subquery a single SELECT naming no other
       ScalarDB table, without aggregates, GROUP BY, HAVING, a row limit, window functions or a set
       operation. The table is joined inside the subquery by an inner join, so that the subquery's
       rows are the union of its rows over each piece. Two pieces, as for an inner table, so the
       engine can still drive the plan from the subquery's index. An outer row must come out of
       exactly one piece: the committed piece keeps the subquery over the committed fragment; the
       in-flight piece keeps it over the in-flight fragment and adds that it does not match over the
       committed fragment: `NOT EXISTS (S_c)` for EXISTS; for `x IN (SELECT y FROM ... WHERE w)`,
       `NOT EXISTS (SELECT 1 FROM ... WHERE (w) AND (y) = (x))` over the committed fragment, which
       probes the subquery's index row by row. A row the committed piece returns is not returned
       again, and an UNKNOWN comparison is no match there as it is in IN. The correlated form needs
       x to be one column qualified by a name the subquery's FROM clause does not use, or the
       subquery could read it as its own; otherwise the IN is repeated whole, `CASE WHEN x IN (S_c)
       THEN 1 ELSE 0 END = 0`, which PostgreSQL computes by hashing the whole committed subquery
       (1205x at 10,000 rows in flight in the benchmark's shape). On PostgreSQL, when the subquery
       selects the table's key, a single column, each value is in exactly one copy and the condition
       is left out: the table is split as an inner one. The other engines compare a text key with a
       number as numbers, so '1' committed and '01' in flight would both match 1, and the key case
       is split as above (todo 121), at about the same cost (MySQL, MariaDB and Oracle unchanged,
       SQL Server 2.7x against 2.1x at 100 rows in flight). Measured for the key case, before that
       change: 1.7-3.7x by hand, against 109-1200x through the view on MySQL and MariaDB, where an
       IN over a union runs once per outer row; through the driver 0.4-2.4x at 100 rows in flight,
       up to 16x on MySQL at 10,000. The non-key case, with the correlated `NOT EXISTS`: 1.7-5.4x at
       100 rows in flight, against 190-1520x through the view on PostgreSQL, MySQL and MariaDB; at
       10,000 up to 6.8x, 27x on MySQL (todo 118).
    4. **In an expression**: `NOT IN`, `NOT EXISTS`, and an IN or EXISTS under OR or NOT, anywhere
       in the WHERE clause, over a subquery of the same form; in the select list, the ON clauses
       and HAVING a subquery's table stays on `rc_T`. These add no pieces: the subquery is written
       twice inside every piece, as `x NOT IN (S_c) AND x NOT IN (S_f)`, `NOT EXISTS (S_c) AND NOT
       EXISTS (S_f)`, `(x IN (S_c) OR x IN (S_f))` or `(EXISTS (S_c) OR EXISTS (S_f))`, `S_c` and
       `S_f` the subquery over the committed and the in-flight fragment. In three-valued logic each
       equals the quantifier over the union of the two, De Morgan's laws included, and an outer row
       is returned once. A NOT EXISTS replaces only the EXISTS under its NOT, which De Morgan turns
       into the AND. ANY, SOME and ALL, which would follow IN and NOT IN, stay on `rc_T` so far.
       On MySQL the in-flight writing carries `/*+ NO_SEMIJOIN() */`: built whole as an anti-join,
       it is read by a scan of the metadata table, whose state MySQL estimates at every row (93-399x
       through the view, 13.6x at 100 rows in flight and 2.0x at 10,000 with the hint; elsewhere
       1.1-6.3x).

    A statement is split into at most 16 copies, four inner tables or a nullable table with two
    inner ones; one that needs more is read through `rc_T` (todo 095). Splitting a subquery's
    table naively, as an inner table, would be wrong: an outer row whose
    subquery matches rows in both copies would come out twice, and an outer join would add a
    NULL-extended row in each copy. The rules above exist to prevent that.
  - **Placeholders** that the copies repeat are re-bound in each copy (todo 012), and the statement is
    cached per text (todo 008).
  - **PostgreSQL parallel plans.** The split's estimated cost makes PostgreSQL start parallel workers,
    about 10 ms, for statements that take 1 ms (12-28x). The driver runs split reads with
    `max_parallel_workers_per_gather = 0` and every other read with the session's own value, read
    once and again after a statement that names the setting or resets every setting, and at the end
    of a transaction that held one (todo 107); writes and session statements, which PostgreSQL
    never runs in parallel, leave it as it is. The setting changes only when the next statement
    needs the other value, since toggling it around every statement cost about 1 ms each, and a
    rollback, which undoes a SET in its transaction, makes it read the setting again. Behind a
    transaction-mode pooler such as PgBouncer the session-level SET reaches other clients; the
    driver assumes it owns its session. A split
    read whose plain statement, over the data tables, PostgreSQL plans as parallel, a long one, keeps
    the session's value: asked once per statement text with `EXPLAIN (GENERIC_PLAN)`, which plans
    without the parameter values; before PostgreSQL 16 the answer is no.
  - **Not split, served through `rc_T`**: a ScalarDB table in a shape the four kinds do not cover (a
    scalar subquery, a subquery naming two ScalarDB tables or holding an aggregate or a row limit, a
    CTE, a set operation's branch, a nullable side that is itself a join, parenthesized or written
    `a LEFT JOIN b JOIN c ON ... ON ...` (todo 120), a FULL JOIN, Oracle's `(+)`), and a statement whose pieces, 2 per inner or semi-joined table and 3 per nullable one,
    multiply past the configured limit (default 16), which keeps the split statement bounded; correct,
    at the costs of section 3. Whether a statement past the limit is cheaper whole through `rc_T` or
    with its first tables split and the rest through `rc_T`, and where the limit belongs (a 5-table
    join is 32 pieces, two optional many-to-one joins on one table 18), is measured before the limit
    is fixed (todo 095).
- **SERIALIZABLE**: the SNAPSHOT form at the engine's SERIALIZABLE on PostgreSQL, where SSI does the
  work (section 5); on MySQL, MariaDB and SQL Server the SNAPSHOT form plus a read lock on every row of the first arm, held to
  commit. The second arm is never locked: its rows abort the transaction anyway, and a lock on them would block
  the ScalarDB commit, rollback or recovery that has to settle them. How the lock is taken differs by
  engine:
  - **PostgreSQL**: no lock. The connection runs at PostgreSQL's SERIALIZABLE, and so must ScalarDB's
    storage connections; SSI then breaks every cycle between the two sides, phantoms included (section
    5). A `FOR SHARE` in the first arm would add nothing SSI does not give, and would stall ScalarDB's
    prepares of the rows read.
  - **MySQL, MariaDB, SQL Server**: the engine's SERIALIZABLE level is **not** used, for two reasons. A
    shared lock taken through a covering secondary index locks only the index entry, not the row, so a
    ScalarDB prepare that changes other columns is not blocked. And at that level every plain SELECT is a
    locking read, so the second arm's scan of the in-flight index takes a gap lock that blocks every
    ScalarDB prepare on the table until the driver commits. Instead the connection runs at the engine's
    snapshot level, REPEATABLE READ or SQL Server's SNAPSHOT, the two-arm read runs unlocked, and a second
    statement re-reads the first arm's predicate as a **locking read joined to `T_meta` by primary key**,
    `LOCK IN SHARE MODE` on MySQL and MariaDB (MariaDB rejects `FOR SHARE` and `OF t`, so one spelling
    serves both), `WITH (HOLDLOCK)` on SQL Server:
    ```sql
    SELECT t.k, m.tx_id, m.tx_version, m.tx_state
    FROM T t LEFT JOIN T_meta m ON m.k = t.k WHERE P ORDER BY t.k LOCK IN SHARE MODE;
    ```
    The join is a LEFT JOIN: on an imported table a row the backfill has not reached has no metadata row
    and must still be read and locked, and the lock on the empty primary-key slot of `T_meta` is a gap
    lock that blocks a ScalarDB "not exists" prepare of that key. A locking read returns the latest
    committed rows. The driver compares its key set and its `(tx_id, tx_version, tx_state)` values, NULLs
    compared as values, with the first arm's snapshot result, both ordered by primary key and merged row
    by row so memory is one row per side: any key added or missing, any changed value, or any state 1 or
    2 aborts with `40001`. A first arm larger than a configurable row cap takes the coarse path below
    instead. The next-key or key-range locks on the
    predicate's index block ScalarDB inserts into the range until commit, which is the phantom coverage of
    section 7; the `T_meta` rows locked by primary key block every prepare of those keys, because a
    prepare always updates the metadata row. The in-flight index is never locked. A next-key lock also
    covers the first key beyond the range, so a ScalarDB update of the row adjacent to the predicate range
    waits until commit: a false conflict, not an error.
  - **Oracle**: SERIALIZABLE is refused (section 5).
  The transaction-wide snapshot of every engine at these levels is what makes a second statement safe.
- **Locking clauses never go through a view with an outer join, on PostgreSQL.** PostgreSQL refuses
  `FOR UPDATE` and `FOR SHARE` on the nullable side of an outer join, and a locking clause on a view
  applies to every table in the view. `rc_T`'s first arm is a left join, and so is the `T_scalardb` view of an
  imported table. The driver therefore rewrites every locking read on PostgreSQL, its own under
  SERIALIZABLE on the other engines and a user's `FOR UPDATE` or `FOR SHARE` at any level, onto the base tables:
  `FROM T t LEFT JOIN T_meta m ON ... FOR SHARE OF t`, or `FOR UPDATE OF t`, locking the data-table side
  only; `OF t` does not reach through a view, so the base tables are inlined. On PostgreSQL that lock
  alone blocks a ScalarDB prepare, which writes `T` first, and it covers rows that have no metadata row.
  The inner-join views of a created table may take the clause directly, and there the lock lands on both
  `T` and `T_meta`. On MySQL and MariaDB a user's clause at READ COMMITTED moves into the first arm
  instead, as the READ COMMITTED bullet says, and a shared one locks the rows again by primary key;
  their SERIALIZABLE path and SQL Server lock `T_meta` by primary key, as the SERIALIZABLE bullet says,
  since a lock on the data table alone is not enough when the read is served by a covering index.
  MariaDB rejects `FOR SHARE` and the `OF t` form and uses `LOCK IN SHARE MODE`. Oracle accepts `FOR UPDATE` on an outer-join view. A user locking clause on a
  shape that runs against `rc_T`, joins and subqueries, is refused, since the lock cannot be rewritten
  through an arbitrary join. Sections 7 and 9 rely on this rule wherever they say `FOR UPDATE`.
- **The per-table check**, for the shapes the row-by-row detector of the SNAPSHOT bullet does not
  cover (a table only in a subquery or an expression, an aggregate, DISTINCT or GROUP BY, a read past
  the copy limit, an outer join, an OFFSET, a scrollable result set): under SNAPSHOT run a coarse check first, `SELECT 1 FROM T_meta WHERE tx_state IN (1, 2) LIMIT 1` per
  ScalarDB table, abort if it returns a row, then run the READ COMMITTED read, split or through
  `rc_T`, which with no row in flight in the snapshot returns the committed rows. A table that passed
  is not checked again in the transaction: its snapshot keeps it so. The checks run under the
  statement's query timeout. In autocommit the check and the
  read run in a transaction of their own, its rows fetched whole, since its commit closes a cursor.
  Under SERIALIZABLE
  on PostgreSQL the sequence is the same, at the engine's SERIALIZABLE, and SSI covers the statement. On
  MySQL, MariaDB and SQL Server the coarse check is instead a **locking read** of the in-flight index,
  `LOCK IN SHARE MODE` or `WITH (HOLDLOCK)`, whose gap or range lock blocks every later prepare on the
  table until commit, stalling every ScalarDB writer of the table as a table lock would. (As built on
  MySQL and MariaDB, section 5, every SELECT of the statement is locked, so none reads the snapshot and
  the first-statement rule and `rc_T` below are not needed there.) It must be the
  transaction's **first statement**: a locking read does not establish the
  snapshot on these engines, so when it comes first the snapshot is taken afterwards, under the lock, and
  nothing can go in flight before commit; when a statement ran earlier, a ScalarDB transaction that
  prepared and record-committed between that snapshot and the check is invisible to both, so the driver
  aborts with `40001`; the application can retry with this statement first. Driver writes keep `tx_state = 3` and never touch the in-flight
  index, so that lock does nothing between driver transactions; the user's statement itself is therefore
  run as a locking read through `rc_T`, `LOCK IN SHARE MODE` or `WITH (HOLDLOCK)`, which these engines
  apply to the base rows and ranges behind the view, giving two-phase locking between driver
  transactions. Coarse and correct, phantoms included.
- The driver's own reads that produce anchors for writes follow the same rules.

### 4.3 Writes on one ScalarDB table `T`, key `k`

**INSERT**

```sql
INSERT INTO T (...) VALUES (...);
INSERT INTO T_meta (k, tx_id, tx_state, tx_version, tx_prepared_at, tx_committed_at)
VALUES (:k, :new_id, 3, 1, :now, :now);
```

A duplicate key on either statement is a conflict. On PostgreSQL a failed statement aborts the whole
transaction, so use `INSERT ... ON CONFLICT DO NOTHING` and check the row count, or wrap the inserts in a
SAVEPOINT. On MySQL catch error 1062; the transaction survives it. Do not use `INSERT IGNORE` for `T`, which also
turns data errors such as a NOT NULL violation into warnings. The `T` insert decides the classification.
If it failed, read `T_meta.tx_state` for `k`: state 3 or NULL is an application duplicate; state 1 or 2
is an in-flight ScalarDB insert or delete, a retryable conflict. If it succeeded and the `T_meta` insert
collided, the metadata row is an **orphan**, a tombstone left by a driver DELETE on an imported table
(4.5) or by a native delete; take it over with the CAS of step 3 on its current `(tx_id, tx_version)`,
or on `tx_id IS NULL`, writing the stamp of I5 with `tx_version + 1`, as the CAS does.
An orphan is invisible to `rc_T`, `cc_T` and `T_scalardb`, which start from `T`, and a ScalarDB insert
overwrites it, since its metadata put on an imported table is unconditional. A serial or auto-increment key is obtained from the `T` insert with `RETURNING` or
`getGeneratedKeys` before the `T_meta` insert.

**UPDATE / DELETE by key**

1. **Anchor and state**, by a plain non-locking read of the key through the data-anchored surface,
   `T LEFT JOIN T_meta`, returning whether the data row exists and its `tx_id, tx_version, tx_state`.
   **No data row: the key does not exist**, whatever metadata row may remain (a tombstone, 4.5); run the
   user's DML, which affects zero rows, return 0, no CAS, no abort, exactly as the database would.
   Otherwise no shared lock is needed even under SERIALIZABLE: step 2 takes the exclusive lock on the row at once and
   the CAS of step 3 verifies the anchor. At every level the anchor is the identity of the version this
   transaction read: remembered from its first read of the key (4.2), or taken now if the key was never
   read. For a row read in flight under READ COMMITTED that identity is
   `(before_tx_id, before_tx_version)` (4.2). Under SNAPSHOT and SERIALIZABLE the read is inside the
   snapshot. Never derive the anchor from a blind write, because MySQL evaluates an UPDATE against the
   latest row. Under SNAPSHOT and SERIALIZABLE a row in flight that matches the predicate by its after
   image alone, which the committed images do not show, aborts here too. State 1 or 2 here: section 6; under READ COMMITTED **this is the only point where a wait
   is allowed**, because nothing is locked on this row yet. The anchor is what turns read-then-write
   into first-updater-wins: if the row moved after this transaction read it, the CAS matches nothing and
   the transaction aborts instead of losing the other write. This matches ScalarDB, whose prepare is
   conditioned on the `tx_id` it read at every isolation level. A later read of the same key in this
   transaction may see a newer version; the write still checks the first anchor, which is conservative
   and correct.
2. **The user's DML** on `T` by key, first. ScalarDB's split put writes the data table before the
   metadata table for each key; using the same order means two writers on a hot key cannot deadlock by
   taking the two row locks in opposite orders. Zero rows affected although step 1 saw a data row: read
   the state; 1 or 2 means the row went in flight after step 1, section 6 (abort); no data row means it
   was deleted after step 1, by another driver transaction or a ScalarDB delete commit, so the anchored
   version is gone (abort with `40001`); 3 or NULL with the data row still present means it stopped
   matching a re-applied predicate (below), skip step 3. Without a re-applied predicate, zero rows with
   the data row still present in state 3 or NULL should not occur; treat it as an abort, not a skip.
3. **CAS** on `T_meta`, second. Must affect exactly one row. Zero rows is **always an abort**, never a
   wait: the DML of step 2 already holds the row lock on `T` that ScalarDB's rollback and recovery write,
   and its record commit on every engine but Oracle, and on MySQL under REPEATABLE READ even a zero-row
   UPDATE keeps a lock on the `T_meta` row. The whole transaction aborts, so the DML of step 2 is undone
   with it.
   ```sql
   UPDATE T_meta
      SET tx_id = :new_id, tx_version = tx_version + 1, tx_state = 3,
          tx_prepared_at = :now, tx_committed_at = :now
    WHERE k = :k AND tx_id = :seen_id AND tx_version = :seen_version AND tx_state = 3;
   ```
   For a DELETE the CAS is the conditional delete itself:
   `DELETE FROM T_meta WHERE k = :k AND tx_id = :seen_id AND tx_version = :seen_version AND tx_state = 3`.
   The `tx_id`/`tx_version` terms carry the anchor; `tx_state = 3` refuses in-flight rows. For DELETE the
   `T` row goes in step 2 and the `T_meta` row here, in the same transaction, **except** on an imported
   table: there the DELETE leaves a stamped state 3 metadata row behind as a tombstone (4.5), the CAS
   stamping it instead of deleting it. Without it, a ScalarDB transaction that read the row as deemed committed would prepare
   it with "metadata does not exist", succeed, and resurrect the deleted row, on commit or on rollback
   alike; the tombstone makes that prepare fail.
4. The same row written again in the same transaction: no second CAS. The lock is held and the version was
   already raised. Locking reads through the view take their row locks in plan order; a deadlock between
   such a read and a ScalarDB prepare is detected by the database and is retryable (section 6).

**UPDATE / DELETE by predicate**

1. `keys := SELECT k FROM <level's read surface> WHERE P` (4.2, including the in-flight rules).
2. Each key as above. Under READ COMMITTED re-apply `P` in the per-key DML (`WHERE k = :k AND P`) so a row
   that stopped matching between key selection and write is skipped, as a native UPDATE would skip it.
   Step 1 has waited for the row to settle, so `P` is evaluated against a committed value; if the row went
   back in flight meanwhile, the DML sees the after image in `T` and may match nothing, which the zero-row
   rule of step 2 catches by reading the state. Under SNAPSHOT and SERIALIZABLE the anchored CAS already
   aborts if the row moved.

### 4.4 Builder notes

- **Autocommit.** A write is three statements that must share one database transaction. In autocommit
  mode the driver turns autocommit off, runs them, commits or rolls back, and restores autocommit.
- **After an abort decided by the driver** (a zero-row CAS, an in-flight row under SNAPSHOT or
  SERIALIZABLE, a timed-out wait): the driver marks the transaction rollback-only, throws
  `java.sql.SQLTransactionRollbackException`, the JDBC 4 subclass for SQLSTATE class 40, with SQLSTATE
  `40001`, fails every further statement and `commit()` with the same exception, and forwards
  `rollback()`. The driver enforces this on every engine: a driver-decided abort is not a database
  error, so even PostgreSQL, which does this natively only after a failed statement, would otherwise
  accept further statements and a commit. A query timeout or a cancel the driver raises itself during
  a write marks the transaction the same way (it throws `57014`): it can come between a key's write
  and its stamp, which would otherwise commit unstamped. So does a vendor error of class 40 during a
  write, except a MySQL or MariaDB lock wait timeout (1205) while `innodb_rollback_on_timeout` is
  off, which undoes the statement only, as the write's savepoint does; with it on, the timeout ends
  the transaction on MariaDB too, whose driver reports it as a plain error.
- **NULL or missing metadata.** If step 1 finds a metadata row with `tx_id IS NULL`, which a ScalarDB
  rollback over a row that had no metadata leaves behind with `tx_state` NULL as well (fact 2), the anchor
  has a NULL `tx_id` and a version of NULL or 0, which the CAS ignores: its condition is
  `tx_id IS NULL AND COALESCE(tx_state, 3) = 3`, the deemed-committed rule ScalarDB's own prepare uses;
  the CAS writes the full stamp of I5 with version 1. If there is no metadata row, the CAS is an `INSERT`
  of a version 1 row guarded by the existence of the `T` row; a duplicate key means another writer got
  there first, so return to step 1. For a DELETE in either
  case the stamped metadata row is written the same way and **kept** as a tombstone after the `T` row is
  deleted (4.3 step 3); the next INSERT of that key takes it over (4.3, INSERT). These cases arise only on
  imported tables, and only until the backfill of section 1 has completed.
- **RETURNING and generated keys.** The user's INSERT returns the keys it wrote, `RETURNING k` after
  its own items if it has any, and the `T_meta` rows are stamped from them, each key copied from its
  data row, since the metadata table spells some types otherwise (CHAR(n) is varchar there). What the
  application asked for, its own `RETURNING` items or the generated keys (`RETURN_GENERATED_KEYS` as
  every column, as PostgreSQL's driver returns them, or the named columns): the statement's own
  `RETURNING` rows are its result as the database returned them, the appended keys hidden; generated
  keys are read back from `T` by key after the writes, in their transaction and in the order written,
  so a batch's keys come in the order of its entries. Key lists are bound as a `VALUES` table, each
  value cast to its column's type, at most 1,000 keys per statement where order does not matter; on
  MySQL and MariaDB as a `UNION ALL` of one-row selects, uncast. An INSERT without a column list
  gives its values in the data table's own column order, which `information_schema` gives when the
  statement is planned. MariaDB's INSERT has `RETURNING`; on MySQL, which has none, the statement
  either gives each key column a literal or a placeholder, each row's key then read back by those
  values and required to match exactly one row, since MySQL may store another value than the one
  given (a NULL or 0 it generates, `1.6` in an INT) and compares after conversion (`1` equals
  `'01'`), or leaves out a single key column that `information_schema` shows `AUTO_INCREMENT`, whose
  values the vendor driver reports; anything else is refused, and so is the statement's own
  `RETURNING`. The read-back is sound only where MySQL stores the values given, or values that
  compare equal to them, which a primary key then keeps unique: such an INSERT is refused when the
  session's `sql_mode` is not strict, which truncates, or the data table has a `BEFORE INSERT`
  trigger, which may set the key, either of which could make the read-back find an older row; and
  the read-back of an `AUTO_INCREMENT` key leaves out 0, which MySQL generates a key for, so that a
  0 reads back no row and is refused rather than find an older row 0 (todo 218). A
  check before the INSERT would have to see the latest committed rows, a locking read, whose gap
  lock at REPEATABLE READ deadlocks concurrent INSERTs (todo 214). A FLOAT key is
  bound as its exact double, which MySQL compares an uncast value as, and so is a float the
  application binds alone to a FLOAT key, when the driver matches keys; the column types come from
  `information_schema` with the column order. Both engines send a FLOAT as six digits, which can
  name another float, so the driver reads a key of ScalarDB's FLOAT type as `k + 0E0`, a double
  sent with all its digits. The metadata rows a key does not have yet are written there by `INSERT IGNORE`, which
  only the driver's own rows go through. Neither engine's INSERT takes an alias, nor MariaDB's DELETE
  of one table, which becomes `DELETE T FROM T_data AS T`; a column qualified by a created table's name
  in `RETURNING` is refused on MariaDB. A failed statement there leaves the transaction open with the
  earlier keys of a write already written, so each write runs under a savepoint and is undone to its
  start on any failure, as the engine undoes a statement of its own. A duplicate of a key the database generated is the application's duplicate:
  without the key as written there is nothing to look up in flight. Under SNAPSHOT and SERIALIZABLE
  the snapshot cannot see a key written after it, from which the duplicate may come: a duplicate of a
  key the snapshot does not show committed is a conflict, `40001`. Only a duplicate of the primary
  key is looked into: another unique constraint's, or a key the statement itself repeats, is the
  application's duplicate at every level. `RETURNING` on an UPDATE or
  DELETE, and generated keys by column index, are refused. ScalarDB's import refuses a `SERIAL`
  column; an identity column, `GENERATED BY DEFAULT AS IDENTITY`, imports.
- **Return values.** `executeUpdate` of a predicate DML is the sum of the per-key counts; an INSERT
  returns 1.
- **Batches.** A batch that holds a write runs in the driver at `executeBatch`: each entry in order,
  by the rules of 4.3, and a statement batch's other entries in the same order, all in one transaction
  of their own in autocommit mode. A failure is a `BatchUpdateException` with the counts of the
  entries before it and the failure itself as its next exception, so a `40001` keeps its state. A
  statement with its own `RETURNING` is not batched. Multi-key driver writes and multi-key ScalarDB prepares can
  deadlock across keys whatever the per-key order; the database detects it and the retry contract of
  section 6 covers it.
- **Predicate shapes for the two-arm read.** `P` must reference one table, be deterministic (it is
  evaluated twice through `NOT COALESCE(P, false)` or its CASE form), and contain no subquery over `T`. `LIKE`, `IN`,
  ranges and `IS NULL` rewrite by column substitution, as ScalarDB's `convertConjunctionToBeforeImage`
  does; `c IS NULL` also matches in-flight inserts in the second arm, a harmless extra abort under
  SNAPSHOT and SERIALIZABLE.
- **Composite keys.** `k = :k` everywhere means all key columns; the anchor map is keyed by all of them.
- **Timestamps** in the metadata are epoch milliseconds, as ScalarDB writes them.
- **Stuck-row check contract.** Scan `T_meta WHERE tx_state IN (1, 2) AND tx_prepared_at < now_ms -
  15000`, served by the in-flight index, read-only and in autocommit, and report per table the count and
  the oldest age. It never settles a row: whether a prepared row's transaction committed is known only to
  the coordinator, and the driver depends on neither the coordinator nor ScalarDB's runtime (section 6).
- **Catalog cache.** ScalarDB's catalog is loaded whole and trusted for `scalardbnative.catalogTtlMs`;
  each resolved name is trusted for the same time and cached with its classification, in a bounded LRU.
  No lock is held across a round trip: while one thread reloads, the others are served the previous
  catalog, and a stale resolution is served while the catalog connection is busy. The catalog
  connection is retried once after a connection failure. **The operator contract:** a table imported or
  created after the load is plain until the TTL expires or the catalog is refreshed; a miss cannot
  trigger a refresh, since every plain table misses. So import first, then refresh through the JDBC
  surface or wait for the TTL, before native writers touch the table. ScalarDB refuses schema changes on
  decoupled tables, but index creation and drop are not guarded, so changes come from registering,
  importing or dropping a table and from index DDL. `virtual_tables` and ScalarDB's table metadata live
  in ScalarDB's metadata schema, `scalardb` by default, given by the `scalardbnative.metadataSchema`
  property.

### 4.5 Tombstones: why a DELETE of a metadata-less row keeps a metadata row

**The problem.** On an imported table a row the backfill has not reached has no metadata row. ScalarDB
reads such a row as deemed committed and remembers `tx_id = NULL` as the version it read. Its later
update is prepared with the condition `tx_id IS NULL`, which `JdbcDatabase` turns, on a LEFT OUTER
virtual table, into "insert the metadata row if none exists". ScalarDB therefore decides "has this row
changed since I read it?" by asking "is there still no metadata row?". If the driver deletes the row and
leaves no metadata row behind, "untouched" and "deleted" look the same: the stale update prepares
successfully and its unconditional data-side put re-inserts the row with the ScalarDB transaction's
values. If that transaction commits, the driver's committed delete is lost, a lost update with a delete
in the role of the lost write. If it aborts, its rollback restores the before image it saved at prepare,
the old row, and an aborted transaction has changed the database. Both outcomes are wrong, and the
update is not a blind write: every ScalarDB Put and Update is conditioned on a read, explicit or the
implicit pre-read, so there is no interleaving in which the outcome is acceptable.

**The rule.** When the driver deletes a row of an imported table, it writes the stamp of I5 into the
row's metadata row, fresh `tx_id`, state 3, version + 1 (1 for a row that had no metadata row or NULL
metadata), timestamps, `before_*` untouched, and deletes only the data row. The metadata row stays
behind without a data row: the tombstone. ScalarDB's stale update now finds that a metadata row exists
with another `tx_id`, its "insert if none exists" or its `tx_id` condition fails, and it aborts, as
first-committer-wins requires. Every deleted row keeps one, not only a row without metadata at the
time of the DELETE: a ScalarDB transaction may have read the row before an earlier driver write gave
it metadata, and a deleted metadata row would answer its "insert if none exists" (todo 132). On a
created table a DELETE removes the metadata row with the data row: ScalarDB always wrote metadata
there, so no transaction can hold a NULL anchor for it.

**What the rows look like**, for key 42 on an imported table `T(id, x)`:

| Stage | `T` row | `T_meta` row for 42 |
|---|---|---|
| Before, backfill not yet done | `(42, x = 10)` | none |
| After the driver's DELETE commits | none | `tx_id = <fresh UUID>`, state 3, version 1, timestamps now, `before_*` NULL: the tombstone |
| After the driver's INSERT of 42 takes it over | `(42, x = 20)` | the same row updated in place by the CAS: another fresh `tx_id`, version 2, state 3 |
| After a ScalarDB insert of 42 instead | `(42, x = 30)` | overwritten by ScalarDB's prepare: its own `tx_id`, state 1, version 1; state 3 at its commit |

**How ScalarDB treats a metadata row without a data row.** It never sees one, and the only writes it can
make to that key overwrite it (`JdbcAdmin.createVirtualTableView`, `JdbcDatabase.dividePutForSourceTables`,
`PrepareMutationComposer`):

- Reads start from the data table. The virtual table is `FROM <data> LEFT OUTER JOIN <metadata>`: a data
  row without metadata appears with NULL metadata, a metadata row without a data row does not appear.
  Every Get, Scan, validation re-read and lazy-recovery read goes through that view, so to ScalarDB the
  key does not exist. The stuck-row check of 4.4 ignores it too, since it is state 3.
- A ScalarDB insert or upsert of the key overwrites it. Having read nothing, ScalarDB prepares with "not
  exists", which on an imported table applies to the data side only; the metadata side gets an
  unconditional put, run as an upsert (`INSERT ... ON CONFLICT DO UPDATE`, `ON DUPLICATE KEY UPDATE`).
  The data insert succeeds, the upsert replaces the tombstone with ScalarDB's own state 1 row, and the
  record is ordinary from then on.
- A ScalarDB delete of the key removes it, when it can run at all. A delete of a record ScalarDB read
  nothing for is prepared as a state 2 reservation: a key-only insert with "not exists" on the data side
  and an upsert on the metadata side, and its record commit deletes from both source tables. On an
  imported table whose data columns are NOT NULL the key-only insert fails with a NOT NULL violation and
  the ScalarDB delete fails non-retryably. That is pre-existing ScalarDB behaviour for any absent key on
  such a table, unrelated to the tombstone, and is listed with the ScalarDB-side limits of section 1.

The one path that behaves differently is the one that must: the update prepared after reading the row as
deemed committed, whose condition is the existence test the tombstone defeats. A metadata row without a
data row is not a new shape for ScalarDB either; a native delete on the data table produces exactly the
same thing, and ScalarDB tolerates it for the reasons above. The tombstone creates it on purpose.

**The driver's side.** The driver's own INSERT of the key must expect the tombstone: its data insert
succeeds, its metadata insert collides, and the collision means "reuse this row", so the driver updates
the tombstone in place with the new write's stamp through the CAS of 4.3 instead of reporting a
duplicate key (4.3, INSERT). The case arises only on imported tables, where every driver DELETE leaves
a tombstone; the cost is one metadata row per deleted key until the key is inserted again.

### 4.6 ORM compatibility

An ORM needs a `DataSource` that behaves like the vendor's, and the driver gives it one: `getMetaData` is
forwarded, so dialect resolution sees PostgreSQL, MySQL, MariaDB, SQL Server or Oracle and picks the
right dialect, which a wrapper that did not forward `getMetaData` would force the application to set by
hand with `spring.data.jdbc.dialect`; `@Transactional` maps onto `setAutoCommit(false)`,
`commit`, `rollback` and `setTransactionIsolation`, all forwarded; and a connection pool wraps the driver
like any other, with `isValid` forwarded.

**Spring Data JDBC.** Every statement it generates is a shape this document already handles:

| What the repository does | Statement shape | Rule |
|---|---|---|
| `findById`, `findAll`, derived queries, `Pageable` | single-table SELECT with quoted, qualified, aliased columns, `IN (...)`, `ORDER BY`, and paging in the dialect's own spelling, `LIMIT`/`OFFSET` or SQL Server's `OFFSET ... FETCH` | attributable: the two-arm form, ordering and paging re-emitted as given on top (4.2); at SNAPSHOT and SERIALIZABLE a page past the first, with its `OFFSET`, takes the per-table check |
| `count()`, `existsById` | single-table aggregate | the two-arm form with the aggregate on top (4.2); at SNAPSHOT and SERIALIZABLE the per-table check |
| `save` of a new entity | INSERT with generated keys, `RETURN_GENERATED_KEYS` or key column names | `getGeneratedKeys` served from the `T` insert, key reused for `T_meta` (4.3) |
| `save` of an existing entity with `@Version` | `UPDATE ... WHERE id = ? AND version = ?` | key plus a condition: predicate DML on one key with the predicate re-applied; zero rows on a version mismatch, so Spring raises `OptimisticLockingFailureException` as expected; counts must mean rows matched (section 1, per-engine table) |
| `deleteById` | DELETE by key | 4.3 |
| `deleteAll` | DELETE with no WHERE | predicate DML with no predicate: one write per key, correct but slow on a large table; use passthrough or a truncate for that |
| aggregate roots with child collections | separate per-table SELECTs by default; one joined SELECT with single-query loading enabled | per-table: two-arm form; joined: the split read, anchoring every joined table, under READ COMMITTED, the coarse check under the other levels (4.2) |
| `@Lock(PESSIMISTIC_WRITE)` | `FOR UPDATE` appended; on SQL Server a table hint, `WITH (UPDLOCK, ROWLOCK)` | the locking-clause rule (4.2); on SQL Server the table hint is stripped before parsing and re-applied as the separate locking re-read, because JSQLParser 5.4 does not parse `WITH (UPDLOCK, ROWLOCK)` (4.7); refused on the join shapes |
| `insertAll`, batches | batched INSERT with generated keys | individual statements (4.4, Batches) |

**Spring Boot 3 and 4.** Spring Boot 3 requires Java 17 and Spring Boot 4, on Spring Framework 7,
requires 17 or later, usually 21; the Java 11 driver jar loads on all of them (section 1, Repository and
build). Two practices from that generation are built in regardless of the Java target. A driver-decided
abort is thrown as `SQLTransactionRollbackException` (4.4): Spring's default translator tries the JDBC 4
subclass mapping first, so that exception becomes a `ConcurrencyFailureException` subclass on every
engine without any vendor-code lookup, which is what a retry template keys on, and it is what MySQL's own
driver throws for deadlocks. And the driver uses no `synchronized` on hot paths: Spring Boot enables
virtual threads with one property, and on Java 21 a `synchronized` block that blocks on I/O pins the
carrier thread, which both pgjdbc and HikariCP had to rework; a `ReentrantLock` where a lock is needed at
all, which should be almost nowhere, since one connection serves one thread at a time.

The acceptance matrix is Spring Boot 3 on Java 17 and Spring Boot 4 on Java 21. The first suite is the
repository's `samples/crud-spring-data-jdbc` application pointed at the driver's URL: written with derived
repository methods only and no handwritten SQL, which is exactly the surface to prove, and it asserts the
exception translation above.

**JPA and Hibernate, MyBatis.** The same rules apply statement by statement. Hibernate emits key-based
UPDATE and DELETE with `@Version` conditions, INSERT with identity keys through `getGeneratedKeys` or
`RETURNING` and with sequence keys through a separate `nextval` SELECT that passes through, `FOR UPDATE`
for pessimistic locks, and JPQL joins that take the split read; its batched inserts fall under the batch
rule. MyBatis emits whatever the mapper says, so each mapped statement is classified on its own, and a
mapper that mixes plain and ScalarDB tables in one DML is refused like any other such statement. The
repository's `samples/crud-jpa` and `samples/crud-mybatis` applications sit beside the Spring Data JDBC
one, and with `samples/crud-jdbc` the four are the acceptance suite.

### 4.7 Vendor hints and unparsed vendor syntax

Measured on JSQLParser 5.4, the parser the driver uses, on 2026-10-09; the refusals below are covered by
`StatementShapeTest` and `SqlAstTest`.

**What the parser keeps.** A `/*+ ... */` or `--+` hint that immediately follows `SELECT`, `UPDATE`,
`DELETE`, `INSERT` or `MERGE` becomes an `OracleHint` node on the statement and on each `PlainSelect`,
subqueries included; the same node holds MySQL optimizer hints and pg_hint_plan hints, which use the same
syntax. MySQL `USE`, `FORCE` and `IGNORE INDEX` become a `MySQLIndexHint` on the table reference, and
`STRAIGHT_JOIN` a flag. SQL Server `WITH (NOLOCK)` and `WITH (INDEX(...))` become `SQLServerHints` on the
table reference, and `OPTION (...)` parses; `WITH (UPDLOCK, ROWLOCK)`, in any position, and
`UPDATE T WITH (ROWLOCK)` do **not** parse. Oracle `FOR UPDATE OF col`, `NOWAIT`, `WAIT n` and
`SKIP LOCKED` parse. A hint anywhere else, after the select list or inside `WHERE`, is a plain comment to
the parser, and the engines ignore it there as well. Oracle's `--+` form is accepted: the
executable-comment rule of 4.1 is per engine, and only MySQL and MariaDB read `--x` as code. Because the rewrite re-joins the original tokens
(`SqlAst.joinTokens`), every hint survives in the emitted text wherever it stood; only its meaning after
the rewrite is in question. The parser's own regeneration of a hinted `DELETE` drops the space before
the hint, `DELETE/*+ ... */`, which Oracle accepts; the token join does not have this problem.

**Plain tables.** Forwarded verbatim, hints and all. Nothing below applies.

**ScalarDB tables.** A hint never changes a statement's shape: a hinted statement takes the route its
unhinted form takes (4.1). Today the shape reader refuses an optimizer hint on SELECT, INSERT, UPDATE and
DELETE and a table hint on the table (`StatementShape`), explicitly, so a hinted read falls to the
split read without an anchor and a hinted write is refused. That is safe, and is to be lifted by
dropping those refusals, with the rules below in place.

- **Statement hints that name no table** (`FIRST_ROWS(n)`, `ALL_ROWS`, `PARALLEL`,
  `MAX_EXECUTION_TIME`, `OPTION (MAXDOP n)`, `RECOMPILE`) stay at the outermost `SELECT` of the rewritten
  statement. For a predicate DML they move to the key-selection read; by-key statements drop them, since a
  primary-key lookup has nothing to steer.
- **Hints that name the table** (`INDEX(t idx)`, `FULL(t)`, `NO_INDEX(t ...)`, `LEADING(t ...)`,
  `USE_NL(t)`, `IndexScan(t idx)`, `USE INDEX`, `WITH (INDEX(...))`): after the rewrite the alias names a
  view or a derived table, which Oracle and PostgreSQL ignore and MySQL rejects, since the view has no
  such key. So the driver writes the **first arm over the base tables**, `T t LEFT JOIN T_meta m`, the
  same inlining the locking-clause rule uses, keeps the user's alias on `T`, and attaches the hint to
  that query block at its own `SELECT`, so no global `@qb` syntax is needed. The second arm never
  carries a user hint; it has its own index (section 3). In the shapes served through `rc_T` (4.2) the
  ScalarDB table stays a view: a comment hint on it is left in place and ignored by the engine, a MySQL index hint on it is
  removed.
- **Locking hints.** Oracle `FOR UPDATE OF col`, `NOWAIT`, `WAIT n` and `SKIP LOCKED` are carried onto
  the separate base-table `FOR UPDATE` statement of 4.2, the column requalified to `T`. Until then
  `NOWAIT`, `WAIT` and `SKIP LOCKED` are refused on a ScalarDB table, and `FOR UPDATE OF` is accepted
  only when it names the table by its alias or name, PostgreSQL's form; Oracle's `OF col`, which names a
  column, is refused. SQL Server
  `WITH (UPDLOCK ...)` does not parse, so the driver strips SQL Server table hints from the statement
  text before parsing, in a token pass, and re-applies an `UPDLOCK` or `HOLDLOCK` intent as the separate
  locking re-read of 4.2; this is what `@Lock(PESSIMISTIC_WRITE)` needs on SQL Server (4.6).
- **Refused on a ScalarDB table**, because they change what the rules depend on: Oracle `APPEND` and
  `APPEND_VALUES`, a direct-path insert that takes a table lock blocking every ScalarDB prepare until
  commit and then raises ORA-12838 on any later read or write of `T` in the same transaction, the
  driver's own steps included; Oracle `IGNORE_ROW_ON_DUPKEY_INDEX` and `CHANGE_DUPKEY_ERROR_INDEX`, MySQL
  `INSERT IGNORE` and `REPLACE`, which alter the duplicate-key outcome 4.3 classifies on; SQL Server
  `NOLOCK` and `READUNCOMMITTED`, which read the after image of a prepare in progress. Every other hint
  is at worst a slower plan: Oracle, PostgreSQL and MySQL never raise an error for an unusable comment
  hint.

**Unparsed vendor syntax.** JSQLParser 5.4 does not parse Oracle `RETURNING ... INTO`, flashback
`AS OF TIMESTAMP` and the `MODEL` clause, nor the SQL Server lock hints above. Under 4.1 such a statement
is refused even when it touches plain tables only, since the driver cannot tell. The relaxation of
passing through a statement whose token stream contains none of the catalog's names is **deferred**: a
user-defined view over a ScalarDB table would slip through it (section 7), and a synonym would too,
since a parsed name is refused as a synonym only after the engine has resolved it. The parser's
coverage of each engine's syntax is therefore part of the certification of that engine.

---

## 5. Isolation levels

One setting, the native connection's isolation level, selects everything else.

Built so far: all three levels on PostgreSQL, MySQL and MariaDB, the latter two's writes and locking
reads as on PostgreSQL; READ COMMITTED on SQL Server and Oracle. Elsewhere the
levels are accepted by `setTransactionIsolation`, and every statement on a ScalarDB table is refused
with `0AN01`; the locking re-reads, Oracle's refusal of SERIALIZABLE and the translation of
`TRANSACTION_REPEATABLE_READ` below come with milestone 8.

SNAPSHOT on MySQL and MariaDB needs two things PostgreSQL gives natively. A locking read there returns
the rows committed now, not the snapshot's, and raises nothing for a row changed since: the driver
runs the read again without the lock, in the snapshot, and aborts with `40001` unless it returns the
same keys at the versions read under the lock, the metadata rows locked too; under `SKIP LOCKED`,
whose `ORDER BY` and `LIMIT` would pick in the snapshot the rows it skipped, each row locked must be
in the snapshot at the version locked (todo 221). And the driver's own key lookups, under a lock, in
an `INSERT ... SELECT` or an `UPDATE`, take next-key locks at REPEATABLE READ: the keys are a table
that drives a join on the primary key, `(SELECT ? AS k0 UNION ALL ...) u STRAIGHT_JOIN data d ON
d.k = u.k0`, each key a lookup that locks its row alone, where a semi-join or an OR of the keys may
scan, and lock, the whole table when they are a large share of it (todo 220); concurrent INSERTs
then neither wait on nor deadlock with them. A connection's level is changed between transactions
only: these engines' `SET SESSION TRANSACTION` applies to the next one (todo 219).

SERIALIZABLE on MySQL and MariaDB, as built (milestone 8b2): the connection runs at REPEATABLE READ and
`getTransactionIsolation` reports SERIALIZABLE; a level set in SQL through passthrough is followed the
same way (todo 232). Every SELECT block of a statement, its subqueries, derived tables and CTEs
included, gets `LOCK IN SHARE MODE` unless it has a locking clause, since a clause on the outer SELECT
does not lock them; tables ScalarDB does not manage are so locked as the engine's SERIALIZABLE would
lock them, and a set operation must put its operands in parentheses, the only place each takes a
clause of its own (todo 229). A write's own reads take next-key locks at REPEATABLE READ. A read of one ScalarDB table row by row runs as that
read under `FOR SHARE`, by the path of a user's locking read: the predicate locked on the data table,
each row's metadata row locked again by primary key, and the rows compared with the same read in the
snapshot, a row in flight that the predicate matches aborting it; the keys are held in memory, with
no row cap yet, and the in-flight check takes the predicate without its `LIMIT`. Any other read takes
the coarse path, with two changes from 4.2: the statement runs on the data tables, not through `rc_T`,
whose union is a derived table a locking clause does not reach, the in-flight check having left only
committed rows there; and it need not be the transaction's first statement, every SELECT of it being
read under locks, for which an older snapshot does not matter. A table in a derived table or a
set operation is refused. An UPDATE or DELETE also reads its predicate on the data table under a
shared lock, before any plain read so that a first statement takes its snapshot under it (todo 236),
the metadata rows of what it matches locked by primary key, and aborts unless that is what its read in
the snapshot found; its next-key locks hold off a ScalarDB insert into the range.
`SerializableIntegrationTest` runs the cycles on both engines, ScalarDB's prepare failing on its short
lock wait where it waits on the driver.

| Level | Native connection | In-flight row on read | In-flight row on write | CAS anchor | Read locks | ScalarDB side |
|---|---|---|---|---|---|---|
| READ COMMITTED | READ COMMITTED | return the before image | at the state read of 4.3 step 1: abort, or bounded wait; at the CAS: always abort | identity of the version this transaction read, which for an in-flight row is its before image's `(before_tx_id, before_tx_version)`; else a read now | none | any |
| SNAPSHOT | REPEATABLE READ, or the engine's equivalent (engine mapping below) | abort, retry | abort, retry | version the snapshot showed | none | any |
| SERIALIZABLE | SERIALIZABLE (engine mapping below) | abort, retry | abort, retry | version the snapshot showed | none on PostgreSQL, where SSI does the work; elsewhere a read lock on every row of the first arm, held to commit: a separate locking re-read by primary key on MySQL, MariaDB and SQL Server (4.2); Oracle refuses the level | SERIALIZABLE; on PostgreSQL its storage connections at SERIALIZABLE too |

- **Why SNAPSHOT holds without the coordinator.** By fact 4, in any database snapshot a ScalarDB
  transaction's rows are either all at their pre-transaction committed version, or a mix of state 1/2 and
  committed-after. The driver refuses state 1/2, so a transaction that completes sees that ScalarDB
  transaction either nowhere or everywhere it looked. If the ScalarDB commit point precedes the snapshot,
  every prepare does too, so the driver either aborts or sees it: no committed ScalarDB transaction is
  missed. First-committer-wins comes from the anchored CAS: on PostgreSQL the UPDATE itself raises a
  serialization error when the row moved after the snapshot; on MySQL the WHERE finds a different version
  and returns zero rows.
- **Why SERIALIZABLE holds on PostgreSQL.** SSI does the work, given that the driver's connection,
  ScalarDB's storage connections and ScalarDB's transactions all run at SERIALIZABLE. SSI makes the
  history of storage transactions serializable. A driver transaction `D` is one storage transaction. A
  ScalarDB transaction `S` is several: its reads, its prepare `Sp` (one storage transaction, fact 3),
  one validation read per get or scan (`Snapshot.toSerializable`, run in parallel), the coordinator
  write and the record commit. It is enough that `S` can be placed at one point of the serial order,
  `Sp`'s, without changing what anyone read:
  - **`S`'s reads.** Validation runs after `Sp` and finds every get and scan unchanged, by `tx_id` and by
    set membership (fact 7). A key `S` also writes is not validated, but `Sp`'s condition on `tx_id`
    checks it inside `Sp` itself. One-phase commit, which has no separate prepare, is off whenever
    validation is needed (`canOnePhaseCommit`).
  - **`S`'s writes.** The driver sees them only in state 3, after the record commit. Before that it
    reads the prepared row and aborts (4.2), or fails the CAS (4.3).
  - **A `D` inside `S`'s window**, between its first read and its validation, either has no direct
    conflict with `S`, or conflicts with it: it aborts on a prepared row or at the CAS; or
    its committed write to `S`'s read set fails `S`'s validation; or it read something `Sp` wrote and wrote
    something a validation read `Sv` missed. The last is `Sv -rw-> D -rw-> Sp` with `Sp` committed
    before `Sv` took its snapshot, the dangerous structure SSI aborts, also when `Sv` is read-only. A
    phantom is that case, `D`'s read covering the range `Sp` inserts into, and so is write skew.
  - **A read-only `S`** has no `Sp`. Take the serial order SSI gives the storage transactions. Each
    validation `Sv(x)` read the version `Sr(x)` read, so every writer of `x` that changes its `tx_id` is
    ordered before `Sr(x)` or after `Sv(x)`; a record commit or a roll-forward, which keeps `tx_id`, may
    land in between and changes nothing `S` compared. Writers here are prepares. `S` fits at one point
    unless a writer `W` of some `y`, ordered after `Sv(y)`, precedes a writer `W'` of some `x` ordered
    before `Sr(x)`. `W'` committed before `Sr(x)` took its snapshot, so before every validation began.
    Where `W -> W'` directly, the edge is `rw`, since a `ww` or `wr` edge would make `W` commit before
    `W'` and so be visible to `Sv(y)`; then `Sv(y) -rw-> W -rw-> W'` with `W'` committed first and
    before `Sv(y)`'s snapshot is the dangerous structure, which PostgreSQL aborts also when `Sv(y)` is
    read-only.
  - **What is assumed.** The cases above are direct conflicts; the read-only structure applies to a
    read-write `S` the same way, through its validations. A chain through third transactions, in
    the window or between `W` and `W'`, rests on SSI's theorem applied with `S`'s storage transactions
    as if they were one: every cycle has a dangerous structure that SSI aborts. `S`'s reads, prepare and
    validations are separate storage transactions, so this is an argument, not a proof; the read-only
    step has the least margin. With ScalarDB's two-phase commit, every participant on the database
    prepares in its own storage transaction, and the argument assumes every prepare commits before any
    validation begins, which holds when the application validates only after every participant
    prepared, as the interface's order of calls asks. `Sp` is one storage transaction only where
    ScalarDB has this one JDBC storage; with several storages it prepares once per storage, the same
    assumption.

  `SerializableIntegrationTest` checks it: a phantom and write skew at seven timings around ScalarDB's
  prepare and validation, and Fekete's read-only anomaly, each with the driver reading row by row,
  through a join and through the per-table check, in a native SERIALIZABLE transaction. With
  ScalarDB's storage at REPEATABLE READ the same schedules commit each anomaly, because SSI does not
  see ScalarDB's statements; at SERIALIZABLE the driver aborts with `40001` or ScalarDB fails to
  prepare or validate. A failure of a ScalarDB *read* is not reported as a conflict: ScalarDB 3.19's
  `JdbcDatabase.get` and `scan` map no SQLState, so an SSI abort of a read or a validation read reaches
  the application as `CrudException`, `ValidationException` or `CommitException`, retryable only by
  the cause's `40001`. The cost is SSI's false positives on both sides, ScalarDB's own reads
  included, more so where PostgreSQL coarsens its predicate locks to pages or relations.
- **Why SERIALIZABLE holds on the other engines.** The driver is strict two-phase locking on rows: shared on read, exclusive
  through the CAS on write, all held to commit. A ScalarDB prepare is an upsert on the data row followed by
  an UPDATE on the meta row, so it is an exclusive-lock request that the driver's locks block, on the data
  row alone where the driver locks only that side; ScalarDB's prepares precede its validation; its
  validation re-reads everything it read. So if the driver read `r` and ScalarDB writes `r`, ScalarDB's
  validation runs after the driver's commit and sees any `s` the driver wrote. Write skew between the two
  needs both edges and the first forces the second to be caught. Between driver transactions, see the
  engine mapping below.
- Against a ScalarDB transaction running at SNAPSHOT or READ_COMMITTED, the driver gets SNAPSHOT at best,
  because that transaction never re-reads.

**Engine mapping.** The "native connection" column above is PostgreSQL's vocabulary. Per engine:

| Driver level | PostgreSQL | MySQL, MariaDB | SQL Server | Oracle |
|---|---|---|---|---|
| READ COMMITTED | READ COMMITTED | READ COMMITTED | READ COMMITTED with `READ_COMMITTED_SNAPSHOT` on | READ COMMITTED |
| SNAPSHOT | REPEATABLE READ | REPEATABLE READ; the anchored CAS supplies first-committer-wins | SNAPSHOT (`ALLOW_SNAPSHOT_ISOLATION` on; JDBC constant `TRANSACTION_SNAPSHOT`); first-committer-wins is native, error 3960 | SERIALIZABLE, which is snapshot isolation with first-committer-wins, ORA-08177; Oracle has no REPEATABLE READ |
| SERIALIZABLE | SERIALIZABLE, no read locks; ScalarDB's storage connections at SERIALIZABLE | REPEATABLE READ plus the locking re-read of 4.2, whose next-key locks cover the predicate range; the engine's SERIALIZABLE level is not used | SNAPSHOT plus the locking re-read of 4.2 `WITH (HOLDLOCK)`, whose key-range locks cover the predicate range; the engine's SERIALIZABLE level is not used | refused, `0AN01` |

- **Between driver transactions** the strength at the SERIALIZABLE level is: true serializability on
  PostgreSQL through SSI; on MySQL, MariaDB and SQL Server strict two-phase locking by the driver's own
  locking re-reads, row locks plus range locks on every predicate read, and on the coarse path the
  user's statement run as a locking read, on the data tables on MySQL and MariaDB, through `rc_T` on SQL
  Server (4.2), since the engine's SERIALIZABLE level is not used there. Oracle refuses the level.
- **Against ScalarDB transactions** on the engines with read locks, the argument of this section needs
  only that a prepare blocks behind the driver's read lock until commit. On MySQL,
  MariaDB and SQL Server the lock must reach the row or the `T_meta` row, not only a covering index
  entry, which the primary-key re-read of 4.2 guarantees. Phantoms from ScalarDB inserts are closed by SSI
  on PostgreSQL and by the engine's range locks on MySQL, MariaDB and SQL Server.
- **SQL Server's REPEATABLE READ is lock-based, not a snapshot**, so it is not used; SNAPSHOT is the level
  with a transaction-wide snapshot. Its READ COMMITTED must run with `READ_COMMITTED_SNAPSHOT`, or a single
  statement over the two tables does not come from one snapshot (section 1).
- **Oracle's SNAPSHOT is its SERIALIZABLE, and the driver's SERIALIZABLE is refused there.** Oracle
  has neither SSI nor gap locks, so the driver could build serializability only from `FOR UPDATE` on
  every read plus a table lock for every predicate read, and JDBC lets a driver substitute only a
  stricter level for one it lacks, never a weaker. `setTransactionIsolation(TRANSACTION_SERIALIZABLE)`
  throws `SQLFeatureNotSupportedException` (`0AN01`) naming `TRANSACTION_REPEATABLE_READ`, and
  `DatabaseMetaData.supportsTransactionIsolationLevel` reports the level unsupported. An Oracle
  application used to SERIALIZABLE meaning snapshot isolation switches to
  `TRANSACTION_REPEATABLE_READ`, which gives exactly that. ORA-08177 is retryable, as ScalarDB's own
  adapter treats it, and ScalarDB requires SERIALIZABLE on Oracle for its own consistent virtual-table
  reads for the same reason.
- `Connection.setTransactionIsolation` keeps the JDBC constants: the driver translates
  `TRANSACTION_REPEATABLE_READ` to SNAPSHOT on SQL Server and to Oracle's SERIALIZABLE on
  Oracle, so an application uses one constant across engines.

---

## 6. In-flight rows, waiting, retries

- **Detection**: `tx_state IN (1, 2)` on any row a statement would return, anchor, or match through
  `P_before`.
- **READ COMMITTED read**: no wait; the two-arm form or the split read of 4.2 returns the before image, a
  committed value.
- **READ COMMITTED write**: the in-flight check is step 1 of 4.3, a plain non-locking read before any
  DML, so a wait there holds no lock on the row. State 1 or 2 at that point: default abort with SQLSTATE
  `40001`, or optionally, with `scalardbnative.inflightWaitMs` set, read the keys of step 1 again about
  every 20 ms up to that limit and the statement's query timeout, then continue with the same anchor
  for a key this transaction read, or a fresh one for a key it never read; on timeout abort. For a key already read, the wait only helps when the
  ScalarDB transaction rolls back, since a commit keeps the writer's `tx_id` and the anchored CAS fails
  anyway. A zero-row CAS in step 3 is always an abort with `40001`, never a wait: either the row moved
  after this transaction read it, a prevented lost update, or it went in flight after step 1; waiting
  there would hold the `T` row lock that ScalarDB's rollback and recovery, and on every engine but Oracle
  its record commit, need, so the row could
  never settle and the ScalarDB transaction would stall with it. The wait must be bounded: this
  transaction may hold locks
  from earlier statements that the ScalarDB transaction it is waiting for needs for one of its own
  prepares.
- **SNAPSHOT and SERIALIZABLE**: abort immediately with `40001`, reads, writes and locking reads alike;
  `scalardbnative.inflightWaitMs` applies to READ COMMITTED only. A fixed snapshot cannot be refreshed by
  waiting. The retry helper may poll the meta row before re-running the transaction so the retry does not
  land on the same row.
- **Who settles a row**: ScalarDB's own record commit (normal path, milliseconds); a later SNAPSHOT or
  SERIALIZABLE ScalarDB read of the row, or a READ_COMMITTED read-write one, which starts a background,
  best-effort roll at once if the coordinator holds a decision and otherwise after the 15 s lifetime by
  aborting the writer (fact 6). Without the coordinator the driver cannot settle a row itself. **ScalarDB ships no periodic recovery job.** Core exposes a per-record
  `ConsensusCommitManager.recoverRecord` API with the same lifetime guard. The driver does not call it,
  not even from a tool: it would need ScalarDB's configuration and the coordinator at run time, and the
  driver depends on neither. A job that settles every prepared record whose transaction is decided or
  expired is ScalarDB's to provide (todo 084); the driver only reports such rows (the stuck-row check
  of 4.4). Until ScalarDB has one, a crashed ScalarDB transaction, or a record commit that failed, blocks
  driver writes on those keys, and aborts every SNAPSHOT or SERIALIZABLE driver read of them, until some
  ScalarDB read happens to touch them.
- **ScalarDB waiting on the driver**: a prepare blocked by a `FOR SHARE` or CAS lock waits until the
  driver's transaction ends, then re-evaluates its condition. Deadlocks are detected by the database, and
  ScalarDB's JDBC adapter maps them, on its writes, to a retryable conflict (reads are not mapped,
  section 5) (`RdbEngine.isConflict`: PostgreSQL `40001`
  and `40P01`; MySQL and MariaDB error 1213; SQL Server 1205; Oracle ORA-00060, ORA-08177, ORA-08176).
  MySQL's lock wait timeout, error 1205, is mapped too. PostgreSQL's `lock_timeout` (`55P03`) and SQL
  Server's `LOCK_TIMEOUT` (error 1222) are **not** mapped, so a prepare that times out there surfaces as
  a non-retryable commit failure: leave those timeouts unset on ScalarDB's connections, or extend the
  mapping first. On SQL Server the reverse also happens: ScalarDB's own gets and scans hold shared locks
  for the duration of the call (fact 8), so a driver write can wait behind a ScalarDB scan, and the two
  can deadlock, detected and retryable on both sides. A wait cycle across the two sides is broken only by
  deadlock detection or these timeouts.
- **Application contract**: retry the whole transaction on SQLSTATE `40001`, on PostgreSQL's deadlock
  state `40P01`, and on MySQL error 1205, lock wait timeout, whose SQLSTATE is `HY000` (MySQL reports
  deadlock 1213 as `40001`). Retrying a statement inside the transaction re-runs against the same
  snapshot.

---

## 7. Guarantees and limits

**Atomicity and durability**: one native commit covers plain tables and ScalarDB tables. Constraints
between `T` and plain tables are the database's own.

**Isolation**

| Level | Between driver transactions | Against ScalarDB transactions |
|---|---|---|
| READ COMMITTED | the database's READ COMMITTED | each statement sees committed values only, no snapshot across statements; a write to a key this transaction read is first-updater-wins through the anchor, so a lost update becomes a `40001` abort, against driver and ScalarDB writers alike; reads that take no anchor (subqueries, grouped and DISTINCT reads, the `rc_T` shapes of 4.2) protect nothing, and a locking clause is refused on them; lock the keys first with an attributable `SELECT ... FOR UPDATE` |
| SNAPSHOT | the database's REPEATABLE READ / SNAPSHOT | consistent snapshot, ScalarDB transactions visible atomically, first-committer-wins; write skew possible, as snapshot isolation allows |
| SERIALIZABLE | PostgreSQL's SERIALIZABLE; on MySQL, MariaDB and SQL Server the driver's own locking reads (4.2, section 5); refused on Oracle (section 5) | serializable **if** the ScalarDB transaction runs at SERIALIZABLE, and on PostgreSQL ScalarDB's storage connections too, under the assumptions of section 5 (an argument, not a proof; with two-phase commit, every prepare before any validation) |

**Limits**

- **No SERIALIZABLE on Oracle.** Oracle has neither SSI nor gap locks, so the driver refuses the level
  there (section 5); SNAPSHOT is Oracle's strongest.
- **ScalarDB's reads fail non-retryably under SSI.** ScalarDB does not map a serialization failure of a
  read to a conflict (section 5): its applications retry `CrudException`, `ValidationException` and
  `CommitException` whose cause has SQLState `40001`.
- **ScalarDB's storage level on PostgreSQL.** With ScalarDB's storage connections below SERIALIZABLE,
  SSI does not see ScalarDB's statements, and the driver's SERIALIZABLE is snapshot isolation against
  ScalarDB: write skew, phantoms and the read-only anomaly all commit (section 5). The driver cannot
  read ScalarDB's configuration, so this is a documented requirement, not a check.
- **Recency in one failure path.** A ScalarDB record commit that failed after the coordinator write leaves
  rows in state 1; with ScalarDB's storage connections at SERIALIZABLE on PostgreSQL, SSI's false
  positives make such failures more frequent, since ScalarDB logs and ignores them; the driver returns the before image (READ COMMITTED) or retries (other levels) until
  recovery runs, although ScalarDB told its client "committed". On Oracle this path is not rare: at the
  SERIALIZABLE level that ScalarDB requires there for decoupled tables, a hard parse reads `SYS.USER$` at
  the transaction's snapshot, every logon writes the user's last login time into `USER$`, and the read then
  fails with ORA-08177, which ScalarDB's record commit does not retry (todos 105, 083). Oracle's hidden
  `_disable_last_successful_login_time`, set by a DBA, and long-lived ScalarDB connection pools make it
  rarer; neither removes the failure path.
- **Liveness depends on ScalarDB-side recovery** for rows left by crashed ScalarDB transactions or failed
  record commits, and ScalarDB has no job that runs it on its own (section 6, todo 084); the driver
  reports such rows but cannot settle them.
- **Retry rate** under the two stronger levels rises with the number of in-flight rows encountered. The
  coarse per-table check of 4.2 raises it further on busy tables.
- **ScalarDB's own semantics are unchanged.** Its SNAPSHOT keeps its documented read skew; the driver cannot
  make the other side stronger than it is.
- **Anything bypassing both** (a native writer on `T` without the metadata upkeep, a trigger) is invisible
  to both sides.
- **User-defined views over a ScalarDB table** are invisible to the catalog, which knows the names
  ScalarDB registered: a statement through a user view of `T` passes through as a plain-table statement
  and bypasses both sides like a native writer. This is also why the pass-through relaxation for
  unparsed statements is deferred (4.7). Synonyms are not this gap: the engine reports them and the
  driver refuses them, whatever they stand for, so an application that reaches any table through a
  synonym must name the table.
- **A table registered after the catalog was loaded is plain** until the TTL expires or the catalog is
  refreshed (4.4).
- **Non-ASCII column names on Oracle** are folded by the driver, code point by code point, not by
  Oracle; table and schema names beyond ASCII are folded by Oracle itself.
- **Unparsed vendor syntax is refused even on plain tables** (4.7); the parser's coverage of an engine's
  syntax is part of that engine's certification.

---

## 8. Optional coordinator-aware variants

Not required. They trade a dependency on the coordinator's encoding (group commit keys the coordinator by
the parent id of a `parent$child` transaction id, one-phase commit skips the coordinator) for fewer
retries.

- **SNAPSHOT**: `LEFT JOIN coordinator c ON c.tx_id = m.tx_id` inside the views; a state 1/2 row with
  `c.tx_state = COMMITTED` shows the after image (hidden for state 2), any other in-flight row the before
  image. Both views and the coordinator are read in the same snapshot, so the snapshot contains exactly the
  ScalarDB transactions whose commit point precedes it. Removes the retries on committed-but-lagging rows and
  the recency gap of section 7.
- **SERIALIZABLE**: an undecided in-flight row in the read set can be decided by inserting its `tx_id` as
  ABORTED into the coordinator **inside the driver's transaction**. ScalarDB's own coordinator insert then
  fails on the unique key. If ScalarDB got there first, the driver's insert fails and the driver aborts and
  retries, now seeing COMMITTED. Two guards are mandatory, copied from `RecoveryHandler`: only abort a
  writer whose `tx_prepared_at` is older than the 15 s lifetime, otherwise abort the driver transaction
  instead, because aborting healthy writers on every driver read would be hostile; and re-read the row
  after the coordinator write, because a coordinator cleanup process can remove a committed state, so an
  absent state does not prove "never committed".

---

## 9. Anomaly checklist

| Anomaly | Scenario | Blocked by |
|---|---|---|
| Dirty read of a ScalarDB after image | row in state 1, driver reads `T` | committed copies exclude rows in state 1/2, in-flight copies read their before image (READ COMMITTED); refuse state 1/2 (others) |
| Before image used as a result | an in-flight row returned because its `before_x` matches | before image consulted only for state 1/2, and only as a detector or as the READ COMMITTED committed image |
| Driver overwrites an in-flight row | ScalarDB prepared, driver updates | CAS requires `tx_state = 3` |
| Aborted ScalarDB after image becomes permanent | driver stamps state 3 over a prepared row, ScalarDB then rolls back and no-ops | same |
| ScalarDB overwrites a driver write | ScalarDB read `(T0, v)`, driver commits `(N, v+1)`, ScalarDB prepares | ScalarDB's prepare condition (I2) |
| Half-applied ScalarDB commit visible | crashed commit, one row rolled forward, one still state 1 | refuse state 1/2 + fact 4 (SNAPSHOT, SERIALIZABLE) |
| Lost update, driver vs driver or vs ScalarDB | both read `r` at the same version, both write it | anchored CAS at every level for keys read through `cc_T`; an attributable `FOR UPDATE` for reads that take no anchor |
| Write skew, driver vs ScalarDB | driver reads `r` writes `s`; ScalarDB reads `s` writes `r` | SSI on PostgreSQL (section 5); elsewhere the read lock on `r` delays ScalarDB's prepare past the driver's commit, and ScalarDB's SERIALIZABLE validation then sees `s` |
| Read-only anomaly, the reader ScalarDB's | driver reads `x`, `y`; ScalarDB writes `x`; a read-only ScalarDB transaction sees the new `x` and the old `y`; driver writes `y` from what it read | SSI on PostgreSQL (section 5); elsewhere the read lock on `x` delays ScalarDB's write past the driver's commit |
| Write skew, driver vs driver | same shape | PostgreSQL's SERIALIZABLE; the driver's locking reads on MySQL, MariaDB and SQL Server |
| Phantom from a ScalarDB insert | ScalarDB inserts a row matching the driver's predicate after the read | the locking re-read's next-key or key-range locks on MySQL, MariaDB and SQL Server; SSI on PostgreSQL |
| Phantom from a driver insert against a ScalarDB scan | driver inserts into ScalarDB's scanned range | ScalarDB's SERIALIZABLE re-scan |
| NULL-metadata row vanishes | ScalarDB rolled back a prepare over a row that had no metadata, leaving `tx_id`, `tx_version` and `tx_state` NULL | NULL `tx_state` read as 3 in every rule (fact 2); the NULL-pair CAS of 4.4 |
| In-flight row pinned by a user lock | user `FOR UPDATE` under READ COMMITTED on a row in flight; ScalarDB's settle blocks behind it | the lock is pushed into the first arm only (4.2) |
| Deleted row resurrected | driver deletes a row without metadata on an imported table; a ScalarDB transaction that read it as deemed committed prepares it with "metadata does not exist" | the tombstone of 4.5 makes that prepare fail |
| Lock through a covering index misses the row | MySQL, MariaDB, SQL Server: a shared lock via a covering secondary index locks the index entry only, so a prepare changing other columns is not blocked | at READ COMMITTED on MySQL and MariaDB the versions read under the lock lock the rows again through the primary key; elsewhere the locking re-read joins `T_meta` by primary key (4.2) |

---

## 10. Changes from the original proposal

1. `WHERE x = 'x1' OR before_x = 'x1'` is dropped as a result rule: it returns in-flight rows whatever
   their outcome, with a visible value that does not satisfy the predicate, and it cannot tell a
   committed-but-unfinished writer from an undecided one. An earlier review also claimed it matched
   committed history because `before_*` persisted; current ScalarDB nulls `before_*` at commit, so that
   part was wrong. It survives only as the in-flight detector of 4.2, conditioned on state.
2. The plain join view is replaced by `rc_T` (committed image) and `cc_T` (raw plus metadata).
3. Writes never touch a row in state 1 or 2. Every write is preceded by a CAS on `T_meta` that requires
   state 3. The stamp (fresh `tx_id`, `tx_version + 1`, state 3) is as proposed.
4. Predicate DML is decomposed into key selection on the read surface plus per-key writes.
5. The connection's isolation level selects the read surface, the in-flight policy, the anchor and the
   locks. Waiting is a READ COMMITTED convenience, not the lever between levels.
6. After independent review: under READ COMMITTED an in-flight row is anchored on its before image's
   identity, the in-flight wait happens before any DML and never after a zero-row CAS, and a zero-row
   per-key DML reads the state before deciding it was a legitimate skip.
7. Engine scope is the five engines ScalarDB runs on, with a per-engine level mapping in section 5;
   Oracle has no driver SERIALIZABLE and SQL Server needs `READ_COMMITTED_SNAPSHOT`.
8. A NULL `tx_state` is state 3 everywhere. A ScalarDB rollback over a row without metadata restores
   all-NULL metadata, which the earlier `tx_state = 3` predicates and the 4.4 CAS could neither read nor
   write; `rc_T` became a left outer join and the imported-table locking read locks the data table side
   only. The backfill stays required, for ScalarDB's own liveness (section 1) and to end the window in
   which the NULL rules apply; with the DELETE tombstone of 4.5 the driver's correctness does not depend
   on it.
9. A user locking clause under READ COMMITTED is pushed into the first arm only, so the driver never pins
   an in-flight row while ScalarDB needs to settle it.
10. Locking clauses never go through a view with an outer join; the driver rewrites them onto the base
    tables with `FOR ... OF t` (4.2). A row that vanished between 4.3 steps 1 and 2 is an abort, a
    single-table aggregate keeps the two-arm form, and a driver-decided abort is enforced as
    rollback-only on every engine.
11. After the second independent review: a DELETE of a metadata-less row leaves a tombstone and an
    INSERT takes an orphan metadata row over; on MySQL, MariaDB and SQL Server the SERIALIZABLE level
    runs at the engine's snapshot level with a separate locking re-read joined to `T_meta` by primary
    key, because a shared lock through a covering index does not reach the row and the engine's
    SERIALIZABLE level would gap-lock the in-flight index against every prepare; the coarse path aborts a
    transaction that already ran a statement; `NOT COALESCE(P, false)` has a CASE form for SQL Server and
    Oracle; ScalarDB's split-put order, lazy-recovery behaviour and SQL Server read locks are stated
    precisely.
12. After the third independent review: a write to a key that has no data row returns zero rows instead
    of aborting, so a tombstone never wedges an ORM update; the SERIALIZABLE locking re-read is a LEFT
    JOIN compared with NULLs as values and merged by primary key; the coarse check is the first statement
    on every engine, and on MySQL, MariaDB and SQL Server the user's statement runs as a locking read
    through `rc_T` for serializability between driver transactions; the setup command owns `rc_T`, the
    in-flight index and the backfill; the build section matches the repository's plugin and CI.
13. Vendor hints and unparsed vendor syntax (4.7), measured on JSQLParser 5.4: a hint never changes a
    statement's shape; a hint that names the table makes the first arm inline the base tables; Oracle
    `APPEND`, the duplicate-key hints and SQL Server `NOLOCK` are refused on a ScalarDB table; SQL Server
    `WITH (UPDLOCK, ROWLOCK)` does not parse and is stripped before parsing and re-applied as the locking
    re-read, which corrects the 4.6 assumption; Oracle `RETURNING ... INTO`, `AS OF` and `MODEL` are
    refused under the never-guess rule; user views over `T` are a recorded gap.
14. From the two code reviews of the implementation (2026-10-09): names resolve through the engine's own
    catalog under per-engine identifier rules measured on all five engines ("Identifiers per engine");
    synonyms are refused; the catalog is one per database and credentials, loaded whole on its own
    connection, trusted for a TTL, matched without regard to case where the database compares table
    names that way, and an error unless allowed when absent or unreadable; `T_tx_metadata` is refused
    like `T_data`; the session state the proxy captures is the schemas and the double-quote setting;
    "the parser can interpret" is defined without a node-type list, and executable comment forms are
    refused (4.1).
15. Milestone 7 (2026-10-16) and its review: on PostgreSQL, SNAPSHOT and SERIALIZABLE read row by row
    with each row's state where every ScalarDB table returns rows, aborting on a row in flight, and
    take the per-table check elsewhere: a subquery's table, an aggregate, an outer join, an `OFFSET`, a
    scrollable result set. The check runs before the read wherever it falls in the transaction, once per
    table, not as the transaction's first statement as items 11 and 12 had it. SERIALIZABLE there is
    PostgreSQL's SSI with ScalarDB's storage connections at SERIALIZABLE, without read locks (section
    5). A write aborts on a row in flight its predicate matches by either image. Oracle's refusal of
    SERIALIZABLE is designed (section 5), built with milestone 8. A grouped or DISTINCT read takes the
    per-table check too.
16. Milestone 8a (2026-10-17): writes, anchored reads and locking reads on MySQL and MariaDB at READ
    COMMITTED. A user locking clause is not kept on the outer statement but moved into the first arm
    and checked there row by row, and a shared one locks each row again by key (4.2); a write runs
    under a savepoint (4.3).

## References in ScalarDB (`core/src/main/java/com/scalar/db`; verified on `v3.19.1` and on the 4.0 line, identical in every cited behaviour)

- `transaction/consensuscommit/PrepareMutationComposer.java`: prepare condition on `tx_id` only;
  `before_tx_version = 0` for a NULL `tx_id`.
- `transaction/consensuscommit/CommitMutationComposer.java`, `RollbackMutationComposer.java`,
  `OnePhaseCommitMutationComposer.java`: before image set to NULL.
- `transaction/consensuscommit/TransactionResult.java`, `hasBeforeImage`.
- `transaction/consensuscommit/Snapshot.java`, `isChanged`: validation compares `tx_id`;
  `toSerializable`: one validation read per get and scan, run in parallel.
- `transaction/consensuscommit/CommitHandler.java`, `commit`: prepare, then validation, then the
  coordinator write and the record commit; the coordinator write is omitted for a read-only
  transaction by default (`write_omission_on_read_only`). `TransactionContext.isValidationRequired`:
  SERIALIZABLE only, read-only transactions included, and not when every get is in the write set and
  there is no scan.
- `transaction/consensuscommit/ParticipantCommitHandler.java`, `canOnePhaseCommit`: off when
  validation is required; `validateRecords`.
- `storage/jdbc/JdbcDatabase.java`, `get` and `scan`: each a storage transaction of its own at
  `scalar.db.jdbc.isolation_level`, an SQL failure wrapped in a plain `ExecutionException`, no
  conflict mapping (only `mutate` checks `isConflict`).
- `transaction/consensuscommit/ConsensusCommitUtils.java`, `convertConjunctions`, and
  `CrudHandler.requiresBeforeIndexCheck`: ScalarDB's own before-image read and its index split.
- `transaction/consensuscommit/RecoveryHandler.java`, `RecoveryExecutor.java` and
  `CrudHandler.executeRecovery`: decided value returned at once and the physical roll handed to a
  background task, READ_COMMITTED reads returning the before image, 15 s lifetime guard before aborting
  an undecided writer, re-read before ABORTED; `ConsensusCommitManager.recoverRecord`.
- `storage/jdbc/JdbcCrudService.java`, `mutate`: batchable statements grouped by SQL text first,
  "not exists" inserts last.
- `transaction/consensuscommit/ConsensusCommitAdmin.java`: `_data`, `_tx_metadata`, `_scalardb`, INNER
  for created and LEFT OUTER for imported tables; `throwIfTransactionMetadataDecouplingApplied`.
- `storage/jdbc/JdbcDatabase.java`: `dividePutForSourceTables` (data table first), `mutate` (one
  database transaction, conflict mapping); `RdbEnginePostgresql.isConflict`, `RdbEngineMysql.isConflict`;
  `getMinimumIsolationLevelForConsistentVirtualTableRead`.
