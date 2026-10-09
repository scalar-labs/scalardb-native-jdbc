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
   take no locks. On SQL Server they do: ScalarDB's connections must run at REPEATABLE READ or higher
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
  there can pair a new `T` row with an old `T_meta` row and leak an after image. ScalarDB applies the same
  rule to itself: its JDBC adapter requires at least REPEATABLE READ, SERIALIZABLE on Oracle, for
  consistent virtual-table reads (`getMinimumIsolationLevelForConsistentVirtualTableRead`).
- On an imported table a row of `T` may have no metadata row, or one whose metadata is all NULL; a created
  table always has a metadata row with a real `tx_id`. ScalarDB's `importTable`
  creates an **empty** `_tx_metadata`, and a ScalarDB rollback over a row without metadata leaves a
  metadata row with `tx_id`, `tx_version` and `tx_state` all NULL (fact 2). ScalarDB reads both as
  committed, and so does the driver: every state 3 test in this document is `COALESCE(tx_state, 3) = 3`,
  `rc_T` is a left outer join (section 3), and 4.4 defines the CAS for a NULL or missing metadata row and
  the tombstone a DELETE of such a row must leave.
  Run a backfill before the driver is enabled: a sentinel `tx_id`, version 1, state 3 and non-null
  timestamps for every row whose `tx_id` is NULL or whose metadata row is missing, repeated until no such
  row remains and no in-flight row has a NULL `before_tx_id`. After that the NULL case cannot recur, since
  every later before image carries a real `tx_id`; the driver's NULL handling covers the window before
  the backfill completes. The backfill also protects ScalarDB's own liveness, because of a ScalarDB-side
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
- For SERIALIZABLE between the two sides, ScalarDB transactions must run at SERIALIZABLE (section 5).

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
| Coarse SERIALIZABLE path, sweeper, in-flight index | on `T_data` and `T_meta` | on `T` |
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
  values at their new positions.
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
  after a `SET` inside the transaction (`SET LOCAL` reverts). DDL invalidates the catalog's resolutions.
  A re-read is a SELECT; inside a transaction it takes the first-statement slot the coarse SERIALIZABLE
  path of 4.2 needs, so a session-changing statement inside a SERIALIZABLE transaction forces the
  statement-level path. Not supported: temporary tables named like a ScalarDB table (PostgreSQL
  `pg_temp` is searched first and is not in `current_schemas(false)`; MySQL temporary tables are not in
  `information_schema`), and SQL Server `USE` of another database than the catalog's.
- **No ScalarDB API.** The driver never calls ScalarDB and knows nothing of Cluster. ScalarDB
  transactions run through ScalarDB's own JDBC storage adapter on their own connections. The two are
  independent clients of one database, coordinated only through the row metadata (section 2) and row
  locks.
- **Per-engine strategy**, selected from the URL, for the SQL the driver itself emits:

  | Concern | PostgreSQL | MySQL, MariaDB | SQL Server | Oracle |
  |---|---|---|---|---|
  | Vendor driver, prefix | pgjdbc, `jdbc:postgresql:` | Connector/J `jdbc:mysql:`; MariaDB Connector/J `jdbc:mariadb:` (ScalarDB itself reaches MySQL through the MariaDB driver) | mssql-jdbc, `jdbc:sqlserver:` | ojdbc, `jdbc:oracle:` |
  | Statement snapshot at READ COMMITTED | yes | yes | only with `READ_COMMITTED_SNAPSHOT` on, checked at connect | yes |
  | Read lock at SERIALIZABLE (4.2) | `FOR SHARE` inside the first arm's derived table; on an imported table on the base tables, `FOR SHARE OF t` | a separate locking re-read of the first arm's predicate, LEFT JOINed to `T_meta` by primary key, `LOCK IN SHARE MODE` on both engines, since MariaDB rejects `FOR SHARE` and `OF t`; the engine's SERIALIZABLE level is not used | the same separate re-read `WITH (HOLDLOCK)`; the engine's SERIALIZABLE level is not used | no shared row lock: `FOR UPDATE` in a separate statement on the keys read; `FOR UPDATE` is not allowed on a UNION or in an inline view |
  | Insert that may collide | `INSERT ... ON CONFLICT DO NOTHING`, check the row count | catch error 1062; the transaction survives. Not `INSERT IGNORE`, which also downgrades data errors such as a NOT NULL violation to warnings | catch 2627 or 2601; the transaction survives with `XACT_ABORT` off, which the driver requires | catch ORA-00001; statement-level rollback keeps the transaction |
  | Update count | rows matched | `useAffectedRows=false`, the default on both drivers, pinned so the count is rows matched | rows matched | rows matched |
  | In-flight index | partial index `WHERE tx_state IN (1, 2)` | index on `(tx_state, <pk>)` | filtered index `WHERE tx_state IN (1, 2)` | index on `(tx_state, <pk>)` |
  | Retryable errors | SQLSTATE `40001`, `40P01` | SQLSTATE `40001` (error 1213), error 1205 (SQLSTATE `HY000`) | 1205 deadlock victim (`40001`), 3960 snapshot update conflict, 1222 if `LOCK_TIMEOUT` is set | ORA-08177 cannot serialize, ORA-00060 deadlock, ORA-08176; ScalarDB maps the same three |
  | Duplicate key | `23505` | error 1062 | 2627, 2601 | ORA-00001 |
  | Coarse SERIALIZABLE path (4.2) | `LOCK TABLE T_data IN SHARE MODE` as the first statement, then the coarse check, then the statement | as the first statement, the coarse check as a locking read of the in-flight index, `LOCK IN SHARE MODE`, whose gap lock blocks every prepare on the table until commit; the user's statement then runs as a locking read through `rc_T` | the same `WITH (HOLDLOCK)` | `LOCK TABLE T_data IN SHARE MODE` as the first statement, as PostgreSQL |
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
  | Read lock at SERIALIZABLE | **no** | `FOR SHARE` in the first arm on PostgreSQL; a separate locking re-read by primary key on MySQL, MariaDB and SQL Server, because a shared lock taken through a covering index locks only the index entry there; `FOR UPDATE` on Oracle |
  | Phantoms against ScalarDB inserts | **no** | gap or range locks on MySQL, MariaDB and SQL Server; none on PostgreSQL and Oracle |
  | Serializability between driver transactions | **no** | SSI, locking, or on Oracle snapshot isolation plus the driver's row locks |
  | Failed-statement handling, error codes, lock timeouts | plumbing | codes, whether the transaction survives, whether ScalarDB maps the timeout as retryable |

### URL scheme

```
jdbc:scalardb-native:<vendor sub-protocol>://<vendor URL remainder>[?vendor and driver properties]

jdbc:scalardb-native:postgresql://host:5432/db              ->  pgjdbc,              jdbc:postgresql://host:5432/db
jdbc:scalardb-native:mysql://host:3306/db                   ->  Connector/J,         jdbc:mysql://host:3306/db
jdbc:scalardb-native:mariadb://host:3306/db                 ->  MariaDB Connector/J, jdbc:mariadb://host:3306/db
jdbc:scalardb-native:sqlserver://host:1433;databaseName=db  ->  mssql-jdbc,          jdbc:sqlserver://host:1433;databaseName=db
jdbc:scalardb-native:oracle:thin:@host:1521/db              ->  ojdbc,               jdbc:oracle:thin:@host:1521/db
```

- The driver accepts a URL by its prefix, strips the prefix, and hands the remainder to `DriverManager`,
  which finds the vendor driver through its `ServiceLoader` registration. The sub-protocol token selects
  the per-engine strategy, the way ScalarDB's `RdbEngineFactory` picks an engine by URL prefix. The same
  trick is used by p6spy and the AWS Advanced JDBC Wrapper.
- Accepted sub-protocols: `postgresql`, `mysql`, `mariadb`, `sqlserver`, `oracle`, the engines ScalarDB
  itself runs on. Anything else is refused at `connect`, not guessed at. Certification order: PostgreSQL,
  then MySQL and MariaDB together, then SQL Server, then Oracle. At connect the driver sets the session
  isolation level explicitly, and on SQL Server checks `sys.databases.is_read_committed_snapshot_on` and
  `snapshot_isolation_state`, refusing the connection without the first and SNAPSHOT without the second.
- Vendor properties pass through untouched. Driver properties are namespaced and few:
  `scalardbnative.inflightWaitMs`, the bounded wait of section 6 under READ COMMITTED, 0 to abort at
  once; `scalardbnative.passthrough`, settable **only** through `setClientInfo`, never from the URL or
  the connect properties, since it turns the protection off; `scalardbnative.metadataSchema`,
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
- **Errors.** Every error the driver raises itself is a standard JDBC subclass with a fixed SQLSTATE,
  so a caller can tell what to do without parsing messages; a message never contains the URL.

  | SQLSTATE | Exception | Meaning | What the caller does |
  |---|---|---|---|
  | `40001` | `SQLTransactionRollbackException` | a driver-decided abort: a conflict with a ScalarDB or driver transaction (4.4, section 6) | roll back and retry the transaction |
  | `0A000` | `SQLFeatureNotSupportedException` | a statement the driver does not handle on a ScalarDB table, with the reason (4.1); `READ_UNCOMMITTED` | rewrite the statement; never retry |
  | `42000` | `SQLSyntaxErrorException` | a name a statement must not use: `T_data`, `T_tx_metadata`, `T_scalardb`, a synonym; the message names the table to use | rewrite the statement |
  | `42501` | `SQLSyntaxErrorException` | ScalarDB's catalog exists but this user cannot read it | grant SELECT on the catalog tables |
  | `08004` | `SQLNonTransientConnectionException` | refused at connect: no ScalarDB catalog (unless allowed), a table without the setup of section 3, SQL Server without `READ_COMMITTED_SNAPSHOT` | fix the configuration or run the setup |
  | `08001` | `SQLException` | a malformed URL or driver property, a missing vendor driver | fix the URL or the class path |

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
  settings.gradle.kts           rootProject.name; pluginManagement { includeBuild("build-logic") }; include driver, tools, samples
  build.gradle.kts              no source; a repositories block that exists for Dependabot only
  gradle.properties             version=3.19.0-alpha.1-SNAPSHOT; org.gradle.parallel, caching, configuration-cache
  gradle/libs.versions.toml     every version, including those used only as strings, so Dependabot can bump them
  .java-version                 25.0
  build-logic/src/main/kotlin/  scalardb-native-jdbc.{base,java,java11,publishing,license-report}-conventions
  config/spotbugs/exclude.xml
  driver/                       the published driver: com.scalar-labs:scalardb-native-jdbc, package com.scalar.db.nativejdbc
  tools/                        setup command and sweeper: com.scalar-labs:scalardb-native-jdbc-tools; depends on scalardb at runtime; added with the first tool
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
  Testcontainers images of the five engines, PostgreSQL first, with a system property to point at an
  external database instead.
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
command-line tool that should run wherever the driver does. The `samples` are Spring Boot 3 on Java 17
and Spring Boot 4 on Java 21, each with its own `release`.

**Dependencies**, all from the catalog:

| Module | Runtime | Compile-only | Test |
|---|---|---|---|
| `driver` | `jsqlparser`, `slf4j-api`. **No ScalarDB dependency.** | the five vendor JDBC drivers, which the application supplies | `scalardb` (newest 3.19 patch), Testcontainers, the vendor drivers, JUnit, AssertJ, Mockito, `logback-classic` |
| `tools` | `scalardb`, `picocli`, `slf4j-api`, `logback-classic` | | as above |
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
at connect the driver only verifies that the view and the index exist and refuses the table with a clear
error if they do not. Example for `T(id, x, y)`; a composite key joins on every key column.

```sql
-- cc_T: raw columns + metadata + before image. The driver's surface.
CREATE VIEW cc_T AS
SELECT t.id, t.x, t.y,
       m.tx_id, m.tx_version, m.tx_state,
       m.before_tx_id, m.before_tx_version, m.before_x, m.before_y
FROM T t JOIN T_meta m ON m.id = t.id;

-- rc_T: the committed image. READ COMMITTED surface, also safe for any native reader.
CREATE VIEW rc_T AS
SELECT t.id,
       CASE WHEN m.tx_state IN (1, 2) THEN m.before_x ELSE t.x END AS x,
       CASE WHEN m.tx_state IN (1, 2) THEN m.before_y ELSE t.y END AS y
FROM T t LEFT JOIN T_meta m ON m.id = t.id              -- never unmatched for a created table
WHERE COALESCE(m.tx_state, 3) = 3                       -- NULL metadata is committed (fact 2)
   OR (m.tx_state IN (1, 2)
       AND (m.before_tx_id IS NOT NULL OR m.before_tx_version IS NOT NULL)); -- an in-flight insert has no committed image
```

A row in state 2 keeps its before image and stays visible until ScalarDB's record commit removes it.
For an imported table the ScalarDB surface is the LEFT OUTER view `T_scalardb`; a row without metadata
shows a NULL `tx_state` there and counts as committed (fact 2). Neither `rc_T` nor `T_scalardb` accepts a
locking clause on PostgreSQL, because both have an outer join; the driver rewrites locking reads onto the
base tables (4.2, locking clauses).

**Indexes.** The reads of 4.2 are a UNION ALL of two arms. The first arm uses the user's own index on
`T(x)`. The second arm needs only one index per table over the in-flight rows: on PostgreSQL a partial
index, `CREATE INDEX ON T_meta (<pk>) WHERE tx_state IN (1, 2)`, and a filtered index of the same shape on SQL
Server; on MySQL, MariaDB and Oracle, which have no partial indexes, a plain index on `(tx_state, <pk>)`. At any instant there are very few in-flight rows, so scanning them all
and filtering `P_before` in SQL is cheap for any predicate, with no `before_*` index per column. ScalarDB
creates a `before_x` index whenever `x` is one of its secondary indexes, unless eventually consistent
index reads are enabled; if those stay, make them partial, on PostgreSQL and SQL Server, with
`WHERE before_x IS NOT NULL`, which keeps them small since `before_*` is NULL on every state 3 row.
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
| SELECT over plain and/or ScalarDB tables | each ScalarDB table name replaced by the level's read surface (4.2); one native statement, except the coarse-check path of 4.2, which sends the check, and on PostgreSQL and Oracle the table lock, first, and the SERIALIZABLE locking re-read of 4.2 on MySQL, MariaDB, SQL Server and Oracle |
| INSERT / UPDATE / DELETE on exactly one ScalarDB table | 4.3 |
| DML on several tables, INSERT ... SELECT, MERGE, DML mixing plain and ScalarDB tables, UPDATE of a key column, anything the parser cannot interpret | **refuse** with a clear error; never guess. 4.7 lists the vendor syntax the parser does not interpret and the hints refused on a ScalarDB table |
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
`SKIP LOCKED`, `INTO`) is refused on the `rc_T` route, and so is a rewrite that would rename a table a
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
  the writer's pair would let the CAS succeed after the writer committed, a lost update. Every other
  shape (joins, subqueries, a predicate that cannot be attributed to one table) runs against `rc_T` as
  written and takes no anchor; a single-table aggregate, DISTINCT or GROUP BY over an attributable `P`
  keeps the two-arm form with the aggregate applied on top of the union; the CASE columns of `rc_T`
  cannot use the index on `T(x)`, so selective predicates belong in the form above. A
  user `SELECT ... FOR UPDATE` or `FOR SHARE` keeps this form with the lock pushed into the **first
  arm's** derived table only, as in the SERIALIZABLE bullet, because PostgreSQL rejects a locking clause
  on the union itself; the lock lands on the rows of `T` and `T_meta`, or on `T` alone where the
  locking-clause rule below rewrites it, and blocks a ScalarDB prepare on them until commit. On MySQL
  and MariaDB a user `FOR UPDATE` stays in the first arm, since an exclusive lock reaches the row; a user
  `FOR SHARE`, and on SQL Server any user locking clause, is taken as the separate primary-key re-read of
  the SERIALIZABLE bullet, since a shared lock through a covering index would not reach the row. A user
  locking clause on a shape that runs against `rc_T` is refused, because the lock cannot be rewritten
  through an arbitrary join. The second
  arm is never locked: its rows are in flight, a lock on them would block
  the ScalarDB commit, rollback or recovery that has to settle them, and a later write to such a key by
  this transaction would then wait, in 4.3 step 1, on a row that cannot settle while this transaction
  holds the lock. Rows from the second arm keep the in-flight rules of 4.3 and section 6, unlocked.
- **SNAPSHOT**: the detector `P OR (tx_state IN (1,2) AND P_before)`, in the same two-arm form:
  ```sql
  SELECT <user columns>, tx_state FROM cc_T WHERE P                              -- index on T(x)
  UNION ALL
  SELECT <user columns>, tx_state FROM cc_T
  WHERE tx_state IN (1, 2) AND P_before AND NOT COALESCE(P, false);              -- in-flight index
  ```
  Any returned row with `COALESCE(tx_state, 3) <> 3` aborts the transaction (section 6). For a key
  lookup `P_before` is just the key. Rows in state 3, or with NULL metadata, return their raw columns,
  which are committed. `NOT COALESCE(P, false)` keeps the arms disjoint, so UNION ALL needs no dedup; on
  SQL Server and Oracle, which have no boolean expression in this position, it is spelled
  `CASE WHEN P THEN 1 ELSE 0 END = 0`. Plain `NOT (P)` is wrong: it drops the UNKNOWN rows the clause
  exists for.
- **Why one statement, not two.** Under READ COMMITTED every statement has its own snapshot. A row in
  flight with `x = x2` and `before_x = x1` fails the `P` arm; if its writer rolls back before a second
  statement runs, the row is committed with `x = x1` and no before image, and fails the in-flight arm
  too. Both arms miss a committed match. One statement reads both arms from one snapshot and cannot.
  Pipelining or a multi-statement string still gives two snapshots. Under SNAPSHOT and SERIALIZABLE the
  snapshot is transaction-wide, so two statements would be safe there and the single statement is only
  cheaper.
- **SERIALIZABLE**: the SNAPSHOT form plus a read lock on every row of the first arm, held to commit.
  The second arm is never locked: its rows abort the transaction anyway, and a lock on them would block
  the ScalarDB commit, rollback or recovery that has to settle them. How the lock is taken differs by
  engine:
  - **PostgreSQL**: `FOR SHARE` inside the first arm's derived table,
    `SELECT * FROM (SELECT ... FROM cc_T WHERE P FOR SHARE) a UNION ALL ...`, since a locking clause is
    not allowed on a UNION or on its inputs. On the LEFT OUTER `T_scalardb` view of an imported table the
    lock is rewritten onto the base tables, `FOR SHARE OF t`, by the locking-clause rule below. A locking
    read of a row that changed after the snapshot raises `40001`, which detects a prepare that landed
    between the snapshot and the lock.
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
  - **Oracle**: a separate `SELECT ... FOR UPDATE` on the keys of the first arm, joined to `T_meta` by
    primary key; `FOR UPDATE` is not allowed on a UNION or in an inline view. Under Oracle's SERIALIZABLE,
    which is snapshot isolation, a `FOR UPDATE` of a row changed since the snapshot raises ORA-08177, so
    the statement is both the lock and the detector.
  The transaction-wide snapshot of every engine at these levels is what makes a second statement safe.
- **Locking clauses never go through a view with an outer join, on PostgreSQL.** PostgreSQL refuses
  `FOR UPDATE` and `FOR SHARE` on the nullable side of an outer join, and a locking clause on a view
  applies to every table in the view. `rc_T` is a left join, and so is the `T_scalardb` view of an
  imported table. The driver therefore rewrites every locking read on PostgreSQL, its own under
  SERIALIZABLE and a user's `FOR UPDATE` or `FOR SHARE` at any level, onto the base tables:
  `FROM T t LEFT JOIN T_meta m ON ... FOR SHARE OF t`, or `FOR UPDATE OF t`, locking the data-table side
  only; `OF t` does not reach through a view, so the base tables are inlined. On PostgreSQL that lock
  alone blocks a ScalarDB prepare, which writes `T` first, and it covers rows that have no metadata row.
  The inner-join views of a created table may take the clause directly, and there the lock lands on both
  `T` and `T_meta`. On MySQL, MariaDB and SQL Server a lock on the data table alone is not enough when the
  read is served by a covering index, which is why those engines lock `T_meta` by primary key in the
  SERIALIZABLE bullet; MariaDB also rejects `FOR SHARE` and the `OF t` form and uses
  `LOCK IN SHARE MODE`. Oracle accepts `FOR UPDATE` on an outer-join view. A user locking clause on a
  shape that runs against `rc_T`, joins and subqueries, is refused, since the lock cannot be rewritten
  through an arbitrary join. Sections 7 and 9 rely on this rule wherever they say `FOR UPDATE`.
- **Shapes where `P` cannot be attributed to one table** (joins, subqueries, and an aggregate, DISTINCT
  or GROUP BY whose predicate spans tables; a single-table aggregate keeps the two-arm form):
  under SNAPSHOT run a coarse check first, `SELECT 1 FROM T_meta WHERE tx_state IN (1, 2) LIMIT 1` per
  ScalarDB table, abort if it returns a row, then run the statement against `rc_T`. Under SERIALIZABLE, on
  PostgreSQL, the sequence is `LOCK TABLE T_data IN SHARE MODE` on the physical data table, then the
  coarse check, then the statement, because locking clauses are not allowed with aggregates. The lock
  must be the transaction's **first statement**: the snapshot is taken by the first query, and a prepare
  that commits between the snapshot and the lock would be invisible to the check yet already applied;
  rows prepared before the lock are in the snapshot and the check catches them. If the transaction has
  already run a statement, the driver aborts it with `40001`; the application can retry with this
  statement first, or take `LOCK TABLE T_data IN SHARE MODE` itself at the start, which passes through.
  The lock stalls every ScalarDB prepare, commit, rollback and recovery on the table until the driver
  commits, and every other driver write to the table, which is what makes the path serializable between
  driver transactions as well. The same holds on Oracle. On MySQL, MariaDB and SQL Server the coarse
  check is instead a **locking read** of the in-flight index, `LOCK IN SHARE MODE` or `WITH (HOLDLOCK)`,
  whose gap or range lock blocks every later prepare on the table until commit, the same cost as the
  table lock. It too must be the transaction's **first statement**: a locking read does not establish the
  snapshot on these engines, so when it comes first the snapshot is taken afterwards, under the lock, and
  nothing can go in flight before commit; when a statement ran earlier, a ScalarDB transaction that
  prepared and record-committed between that snapshot and the check is invisible to both, and the driver
  aborts with `40001` as on PostgreSQL. Driver writes keep `tx_state = 3` and never touch the in-flight
  index, so that lock does nothing between driver transactions; the user's statement itself is therefore
  run as a locking read through `rc_T`, `LOCK IN SHARE MODE` or `WITH (HOLDLOCK)`, which these engines
  apply to the base rows and ranges behind the view, giving two-phase locking between driver
  transactions. Coarse, correct, and it also closes the phantom gap of section 7.
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
SAVEPOINT. On MySQL catch error 1062; the transaction survives it. Do not use `INSERT IGNORE`, which also
turns data errors such as a NOT NULL violation into warnings. The `T` insert decides the classification.
If it failed, read `T_meta.tx_state` for `k`: state 3 or NULL is an application duplicate; state 1 or 2
is an in-flight ScalarDB insert or delete, a retryable conflict. If it succeeded and the `T_meta` insert
collided, the metadata row is an **orphan**, a tombstone left by a driver DELETE of a metadata-less row
(4.4) or by a native delete; take it over with the CAS of step 3 on its current `(tx_id, tx_version)`,
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
   latest row. State 1 or 2 here: section 6; under READ COMMITTED **this is the only point where a wait
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
   `T` row goes in step 2 and the `T_meta` row here, in the same transaction, **except** when the row had
   no metadata or NULL metadata: then the DELETE leaves a stamped state 3 metadata row behind as a
   tombstone (4.5). Without it, a ScalarDB transaction that read the row as deemed committed would prepare
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
  accept further statements and a commit.
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
- **RETURNING and generated keys** are served by the user's statement on `T`; the `T_meta` statements
  return nothing. `getGeneratedKeys` comes from the `T` insert.
- **Return values.** `executeUpdate` of a predicate DML is the sum of the per-key counts; an INSERT
  returns 1.
- **Batches.** `addBatch` pre-processes each statement at `addBatch` time, inside the same transaction and
  ahead of `executeBatch`; a predicate DML expands to its
  per-key statements, and a batched INSERT whose key is generated runs as individual statements, because
  each `T_meta` insert needs its key first. Multi-key driver writes and multi-key ScalarDB prepares can
  deadlock across keys whatever the per-key order; the database detects it and the retry contract of
  section 6 covers it.
- **Predicate shapes for the two-arm read.** `P` must reference one table, be deterministic (it is
  evaluated twice through `NOT COALESCE(P, false)` or its CASE form), and contain no subquery over `T`. `LIKE`, `IN`,
  ranges and `IS NULL` rewrite by column substitution, as ScalarDB's `convertConjunctionToBeforeImage`
  does; `c IS NULL` also matches in-flight inserts in the second arm, a harmless extra abort under
  SNAPSHOT and SERIALIZABLE.
- **Composite keys.** `k = :k` everywhere means all key columns; the anchor map is keyed by all of them.
- **Timestamps** in the metadata are epoch milliseconds, as ScalarDB writes them.
- **Sweeper contract.** Scan `T_meta WHERE tx_state IN (1, 2) AND tx_prepared_at < now_ms - 15000`,
  served by the in-flight index, and call `ConsensusCommitManager.recoverRecord(namespace, table, pk,
  ck)` with the **virtual table name** (`T` or `T_scalardb`), not `_data`. It runs inside a ScalarDB
  process, because it needs the coordinator, and returns false for a writer not yet expired, so the row
  is revisited on the next pass.
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

**The rule.** When the driver deletes a row that has no metadata row, or one whose metadata is all NULL,
it writes the stamp of I5 into a metadata row anyway, fresh `tx_id`, state 3, version 1, timestamps,
`before_*` NULL, and deletes only the data row. The metadata row stays behind without a data row: the
tombstone. ScalarDB's stale update now finds that a metadata row exists, its "insert if none exists"
fails, and it aborts, as first-committer-wins requires.

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
  key does not exist. The sweeper of 4.4 ignores it too, since it is state 3.
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
duplicate key (4.3, INSERT). The case arises only on imported tables and only for rows the backfill has
not reached; once every row has a real `tx_id`, a deleted row's metadata row is deleted with it, because
ScalarDB's condition for such a row is `tx_id = <value>`, which a missing row fails on its own.

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
| `findById`, `findAll`, derived queries, `Pageable` | single-table SELECT with quoted, qualified, aliased columns, `IN (...)`, `ORDER BY`, and paging in the dialect's own spelling, `LIMIT`/`OFFSET` or SQL Server's `OFFSET ... FETCH` | attributable: the two-arm form, ordering and paging re-emitted as given on top (4.2) |
| `count()`, `existsById` | single-table aggregate | the two-arm form with the aggregate on top (4.2) |
| `save` of a new entity | INSERT with generated keys, `RETURN_GENERATED_KEYS` or key column names | `getGeneratedKeys` served from the `T` insert, key reused for `T_meta` (4.3) |
| `save` of an existing entity with `@Version` | `UPDATE ... WHERE id = ? AND version = ?` | key plus a condition: predicate DML on one key with the predicate re-applied; zero rows on a version mismatch, so Spring raises `OptimisticLockingFailureException` as expected; counts must mean rows matched (section 1, per-engine table) |
| `deleteById` | DELETE by key | 4.3 |
| `deleteAll` | DELETE with no WHERE | predicate DML with no predicate: one write per key, correct but slow on a large table; use passthrough or a truncate for that |
| aggregate roots with child collections | separate per-table SELECTs by default; one joined SELECT with single-query loading enabled | per-table: two-arm form; joined: `rc_T` with no anchor under READ COMMITTED, the coarse check under the other levels (4.2) |
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
for pessimistic locks, and JPQL joins that route to `rc_T`; its batched inserts fall under the batch
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
`rc_T` route without an anchor and a hinted write is refused. That is safe, and is to be lifted by
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
  carries a user hint; it has its own index (section 3). In the `rc_T` shapes the ScalarDB table stays a
  view: a comment hint on it is left in place and ignored by the engine, a MySQL index hint on it is
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

| Level | Native connection | In-flight row on read | In-flight row on write | CAS anchor | Read locks | ScalarDB side |
|---|---|---|---|---|---|---|
| READ COMMITTED | READ COMMITTED | return the before image | at the state read of 4.3 step 1: abort, or bounded wait; at the CAS: always abort | identity of the version this transaction read, which for an in-flight row is its before image's `(before_tx_id, before_tx_version)`; else a read now | none | any |
| SNAPSHOT | REPEATABLE READ, or the engine's equivalent (engine mapping below) | abort, retry | abort, retry | version the snapshot showed | none | any |
| SERIALIZABLE | SERIALIZABLE (engine mapping below) | abort, retry | abort, retry | version the snapshot showed | a read lock on every row of the first arm, held to commit: `FOR SHARE` in the arm on PostgreSQL, a separate locking re-read by primary key on MySQL, MariaDB and SQL Server, `FOR UPDATE` on Oracle (4.2) | SERIALIZABLE |

- **Why SNAPSHOT holds without the coordinator.** By fact 4, in any database snapshot a ScalarDB
  transaction's rows are either all at their pre-transaction committed version, or a mix of state 1/2 and
  committed-after. The driver refuses state 1/2, so a transaction that completes sees that ScalarDB
  transaction either nowhere or everywhere it looked. If the ScalarDB commit point precedes the snapshot,
  every prepare does too, so the driver either aborts or sees it: no committed ScalarDB transaction is
  missed. First-committer-wins comes from the anchored CAS: on PostgreSQL the UPDATE itself raises a
  serialization error when the row moved after the snapshot; on MySQL the WHERE finds a different version
  and returns zero rows.
- **Why SERIALIZABLE holds.** The driver is strict two-phase locking on rows: shared on read, exclusive
  through the CAS on write, all held to commit. A ScalarDB prepare is an upsert on the data row followed by
  an UPDATE on the meta row, so it is an exclusive-lock request that the driver's locks block, on the data
  row alone where the driver locks only that side; ScalarDB's prepares precede its validation; its
  validation re-reads everything it read. So if the driver read `r` and ScalarDB writes `r`, ScalarDB's
  validation runs after the driver's commit and sees any `s` the driver wrote. Write skew between the two
  needs both edges and the first forces the second to be caught. Between driver transactions the database's
  own SERIALIZABLE does the work.
- Against a ScalarDB transaction running at SNAPSHOT or READ_COMMITTED, the driver gets SNAPSHOT at best,
  because that transaction never re-reads.

**Engine mapping.** The "native connection" column above is PostgreSQL's vocabulary. Per engine:

| Driver level | PostgreSQL | MySQL, MariaDB | SQL Server | Oracle |
|---|---|---|---|---|
| READ COMMITTED | READ COMMITTED | READ COMMITTED | READ COMMITTED with `READ_COMMITTED_SNAPSHOT` on | READ COMMITTED |
| SNAPSHOT | REPEATABLE READ | REPEATABLE READ; the anchored CAS supplies first-committer-wins | SNAPSHOT (`ALLOW_SNAPSHOT_ISOLATION` on; JDBC constant `TRANSACTION_SNAPSHOT`); first-committer-wins is native, error 3960 | SERIALIZABLE, which is snapshot isolation with first-committer-wins, ORA-08177; Oracle has no REPEATABLE READ |
| SERIALIZABLE | SERIALIZABLE plus explicit `FOR SHARE` in the first arm | REPEATABLE READ plus the locking re-read of 4.2, whose next-key locks cover the predicate range; the engine's SERIALIZABLE level is not used | SNAPSHOT plus the locking re-read of 4.2 `WITH (HOLDLOCK)`, whose key-range locks cover the predicate range; the engine's SERIALIZABLE level is not used | SERIALIZABLE (snapshot isolation) plus `FOR UPDATE` on every row read |

- **Between driver transactions** the strength at the SERIALIZABLE level is: true serializability on
  PostgreSQL through SSI; on MySQL, MariaDB and SQL Server strict two-phase locking by the driver's own
  locking re-reads, row locks plus range locks on every predicate read, and on the coarse path the
  user's statement run as a locking read through `rc_T` (4.2), since the engine's SERIALIZABLE level is
  not used there. Oracle's
  engine gives only snapshot isolation; the driver's `FOR UPDATE` on reads turns it into two-phase locking
  on rows, which is serializable except for phantoms, since Oracle has no gap locks. Those need
  `LOCK TABLE T_data IN SHARE MODE` for predicate reads, or an admitted gap, exactly as on PostgreSQL.
- **Against ScalarDB transactions** the argument of this section needs only that a prepare blocks behind
  the driver's read lock until commit. A shared lock on the row does that on PostgreSQL; on MySQL,
  MariaDB and SQL Server the lock must reach the row or the `T_meta` row, not only a covering index
  entry, which the primary-key re-read of 4.2 guarantees; on Oracle the exclusive `FOR UPDATE` does the
  same, at the cost of serializing driver readers of one row. The phantom gap of section 7 exists on PostgreSQL and Oracle and is closed by the engine's
  range locks on MySQL, MariaDB and SQL Server.
- **SQL Server's REPEATABLE READ is lock-based, not a snapshot**, so it is not used; SNAPSHOT is the level
  with a transaction-wide snapshot. Its READ COMMITTED must run with `READ_COMMITTED_SNAPSHOT`, or a single
  statement over the two tables does not come from one snapshot (section 1).
- **Oracle's SNAPSHOT is its SERIALIZABLE**, so the two driver levels differ on Oracle only by the
  `FOR UPDATE` reads. ORA-08177 is retryable, as ScalarDB's own adapter treats it, and ScalarDB requires
  SERIALIZABLE on Oracle for its own consistent virtual-table reads for the same reason. Oracle is the
  weakest SERIALIZABLE of the five and the document says so.
- `Connection.setTransactionIsolation` keeps the JDBC constants: the driver translates
  `TRANSACTION_REPEATABLE_READ` to SNAPSHOT on SQL Server and to SERIALIZABLE without read locks on
  Oracle, so an application uses one constant across engines.

---

## 6. In-flight rows, waiting, retries

- **Detection**: `tx_state IN (1, 2)` on any row a statement would return, anchor, or match through
  `P_before`.
- **READ COMMITTED read**: no wait; the two-arm form of 4.2, or `rc_T`, returns the before image, a
  committed value.
- **READ COMMITTED write**: the in-flight check is step 1 of 4.3, a plain non-locking read before any
  DML, so a wait there holds no lock on the row. State 1 or 2 at that point: default abort with SQLSTATE
  `40001`, or optionally poll `SELECT tx_state FROM T_meta WHERE k = :k` about every 20 ms up to a
  configured limit, then continue with the same anchor for a key this transaction read, or a fresh one
  for a key it never read; on timeout abort. For a key already read, the wait only helps when the
  ScalarDB transaction rolls back, since a commit keeps the writer's `tx_id` and the anchored CAS fails
  anyway. A zero-row CAS in step 3 is always an abort with `40001`, never a wait: either the row moved
  after this transaction read it, a prevented lost update, or it went in flight after step 1; waiting
  there would hold the `T` row lock that ScalarDB's rollback and recovery, and on every engine but Oracle
  its record commit, need, so the row could
  never settle and the ScalarDB transaction would stall with it. The wait must be bounded: this
  transaction may hold locks
  from earlier statements that the ScalarDB transaction it is waiting for needs for one of its own
  prepares.
- **SNAPSHOT and SERIALIZABLE**: abort immediately with `40001`. A fixed snapshot cannot be refreshed by
  waiting. The retry helper may poll the meta row before re-running the transaction so the retry does not
  land on the same row.
- **Who settles a row**: ScalarDB's own record commit (normal path, milliseconds); a later SNAPSHOT or
  SERIALIZABLE ScalarDB read of the row, or a READ_COMMITTED read-write one, which starts a background,
  best-effort roll at once if the coordinator holds a decision and otherwise after the 15 s lifetime by
  aborting the writer (fact 6). Without the coordinator the driver cannot settle a row itself. **ScalarDB ships no periodic recovery job.** Core exposes a per-record
  `ConsensusCommitManager.recoverRecord` API with the same lifetime guard; a sweeper that scans `T_meta`
  for states 1 and 2 older than the lifetime and calls it has to be written. Without it a crashed ScalarDB
  transaction blocks driver writes on those keys, and aborts every SNAPSHOT or SERIALIZABLE driver read of
  them, until some ScalarDB read happens to touch them.
- **ScalarDB waiting on the driver**: a prepare blocked by a `FOR SHARE` or CAS lock waits until the
  driver's transaction ends, then re-evaluates its condition. Deadlocks are detected by the database, and
  ScalarDB's JDBC adapter maps them to a retryable conflict (`RdbEngine.isConflict`: PostgreSQL `40001`
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
| READ COMMITTED | the database's READ COMMITTED | each statement sees committed values only, no snapshot across statements; a write to a key this transaction read is first-updater-wins through the anchor, so a lost update becomes a `40001` abort, against driver and ScalarDB writers alike; reads that take no anchor (the `rc_T` shapes of 4.2) protect nothing, and a locking clause is refused on them; lock the keys first with an attributable `SELECT ... FOR UPDATE` |
| SNAPSHOT | the database's REPEATABLE READ / SNAPSHOT | consistent snapshot, ScalarDB transactions visible atomically, first-committer-wins; write skew possible, as snapshot isolation allows |
| SERIALIZABLE | PostgreSQL's SERIALIZABLE; on MySQL, MariaDB and SQL Server the driver's own locking reads (4.2, section 5); on Oracle snapshot isolation plus the driver's `FOR UPDATE` reads (section 5, engine mapping) | serializable **if** the ScalarDB transaction runs at SERIALIZABLE; see the phantom limit below |

**Limits**

- **Phantoms from ScalarDB inserts on PostgreSQL and Oracle.** Row locks lock rows, not gaps, and
  PostgreSQL's SSI ignores ScalarDB's autocommit statements. MySQL's and MariaDB's next-key locks and SQL
  Server's key-range locks cover an indexed predicate range. On PostgreSQL and Oracle either take
  `LOCK TABLE T_data IN SHARE MODE` for predicate reads under SERIALIZABLE, or document that predicate
  reads are serializable against ScalarDB updates and deletes but not inserts.
- **Recency in one failure path.** A ScalarDB record commit that failed after the coordinator write leaves
  rows in state 1; the driver returns the before image (READ COMMITTED) or retries (other levels) until
  recovery runs, although ScalarDB told its client "committed".
- **Liveness depends on ScalarDB-side recovery** for rows left by crashed ScalarDB transactions, and that
  sweeper has to be built (section 6).
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
| Dirty read of a ScalarDB after image | row in state 1, driver reads `T` | `rc_T` CASE (READ COMMITTED); refuse state 1/2 (others) |
| Before image used as a result | an in-flight row returned because its `before_x` matches | before image consulted only for state 1/2, and only as a detector or as the READ COMMITTED committed image |
| Driver overwrites an in-flight row | ScalarDB prepared, driver updates | CAS requires `tx_state = 3` |
| Aborted ScalarDB after image becomes permanent | driver stamps state 3 over a prepared row, ScalarDB then rolls back and no-ops | same |
| ScalarDB overwrites a driver write | ScalarDB read `(T0, v)`, driver commits `(N, v+1)`, ScalarDB prepares | ScalarDB's prepare condition (I2) |
| Half-applied ScalarDB commit visible | crashed commit, one row rolled forward, one still state 1 | refuse state 1/2 + fact 4 (SNAPSHOT, SERIALIZABLE) |
| Lost update, driver vs driver or vs ScalarDB | both read `r` at the same version, both write it | anchored CAS at every level for keys read through `cc_T`; an attributable `FOR UPDATE` for reads that take no anchor |
| Write skew, driver vs ScalarDB | driver reads `r` writes `s`; ScalarDB reads `s` writes `r` | `FOR SHARE` on `r` delays ScalarDB's prepare past the driver's commit; ScalarDB's SERIALIZABLE validation then sees `s` |
| Write skew, driver vs driver | same shape | PostgreSQL's SERIALIZABLE; the driver's locking reads on MySQL, MariaDB and SQL Server; `FOR UPDATE` reads plus the table lock on Oracle |
| Phantom from a ScalarDB insert | ScalarDB inserts a row matching the driver's predicate after the read | the locking re-read's next-key or key-range locks on MySQL, MariaDB and SQL Server; `LOCK TABLE ... SHARE` or accepted on PostgreSQL and Oracle |
| Phantom from a driver insert against a ScalarDB scan | driver inserts into ScalarDB's scanned range | ScalarDB's SERIALIZABLE re-scan |
| NULL-metadata row vanishes | ScalarDB rolled back a prepare over a row that had no metadata, leaving `tx_id`, `tx_version` and `tx_state` NULL | NULL `tx_state` read as 3 in every rule (fact 2); the NULL-pair CAS of 4.4 |
| In-flight row pinned by a user lock | user `FOR UPDATE` under READ COMMITTED on a row in flight; ScalarDB's settle blocks behind it | the lock is pushed into the first arm only (4.2) |
| Deleted row resurrected | driver deletes a row without metadata on an imported table; a ScalarDB transaction that read it as deemed committed prepares it with "metadata does not exist" | the tombstone of 4.5 makes that prepare fail |
| Lock through a covering index misses the row | MySQL, MariaDB, SQL Server: a shared lock via a covering secondary index locks the index entry only, so a prepare changing other columns is not blocked | the locking re-read joins `T_meta` by primary key (4.2) |

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
   Oracle's SERIALIZABLE is the weakest and SQL Server needs `READ_COMMITTED_SNAPSHOT`.
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

## References in ScalarDB (`core/src/main/java/com/scalar/db`; verified on `v3.19.1` and on the 4.0 line, identical in every cited behaviour)

- `transaction/consensuscommit/PrepareMutationComposer.java`: prepare condition on `tx_id` only;
  `before_tx_version = 0` for a NULL `tx_id`.
- `transaction/consensuscommit/CommitMutationComposer.java`, `RollbackMutationComposer.java`,
  `OnePhaseCommitMutationComposer.java`: before image set to NULL.
- `transaction/consensuscommit/TransactionResult.java`, `hasBeforeImage`.
- `transaction/consensuscommit/Snapshot.java`, `isChanged`: validation compares `tx_id`.
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
