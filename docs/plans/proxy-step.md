# Plan: the connection proxy and the first protected path

Status: accepted 2026-10-09, with the three proposals under "Decisions". Nothing here is implemented
yet.

## Goal

Today `NativeJdbcDriver.connect` opens the vendor connection and returns it unwrapped, so every
statement reaches the database as written and a write to a ScalarDB table bypasses the protocol. This
step makes the driver do its job: wrap the JDBC objects, route each statement through the parser, the
shape classifier and the catalog that already exist, and rewrite reads and writes on ScalarDB tables as
`docs/design.md` sections 3, 4 and 6 prescribe.

The first deliverable is **READ COMMITTED on PostgreSQL** for the two decoupled layouts, created and
imported, proven by interleaving tests that run driver transactions against real ScalarDB transactions on
the same rows. Every other level and engine widens that deliverable afterwards, each certified by the same
tests.

## What exists and what this step uses

| Piece | State | Used for |
|---|---|---|
| `NativeJdbcUrl`, `NativeJdbcDriver` | URL parsing and registration; `connect` returns the vendor connection | wrapped in milestone 1 |
| `sql.SqlAst`, `sql.StatementShape` | one parse, engine-aware names, single-table shapes, refusals, `joinTokens` table renaming, placeholder count | routing, the `rc_T` route, the input to the two-arm rewrite |
| `engine.Engine` | per-engine identifier rules, session schemas, name resolution, double-quote setting | session state, catalog |
| `catalog.Catalog` | per database, own connection, TTL, refusals, classification | every table reference |
| integration harness | six engine configurations, ScalarDB admin fixtures | all integration and interleaving tests |

## Milestones

Each milestone ends with `./gradlew check` green and a commit. Milestones 0 to 6 are the first
deliverable; 7 onwards widen it.

### Milestone 0: contracts the proxy depends on

**Done 2026-10-09** (todos 016, 017, 037, 053). The shared `DriverErrors` class is left to
milestone 1, its first user; the contract is design section 1, "Errors".

Small, independent of each other, no proxy code yet.

- **Error contract (todo 016).** `StatementShape` and `SqlAst` return the reason with an empty result (a
  small `Refusal` value: kind, clause, message). A `DriverErrors` class maps kinds to exceptions:
  - `0AN01`, `SQLFeatureNotSupportedException`, for a statement shape the driver does not handle, and for
    `READ_UNCOMMITTED` (never retry; rewrite the statement);
  - `42000` for a name a statement must not use (the catalog's refusals, already in place);
  - `08004`, `SQLNonTransientConnectionException`, for connect-time refusals (no catalog, a missing view
    or index of section 3, SQL Server without `READ_COMMITTED_SNAPSHOT`);
  - `40001`, `SQLTransactionRollbackException`, for every driver-decided abort (4.4).

  Design section 1 gets the table.
- **Driver properties (part of todo 017).** One `DriverProperties` value parsed from the connect
  `Properties`: `metadataSchema`, `catalogTtlMs`, `allowNoCatalog`, `inflightWaitMs`, `passthrough`.
  `getPropertyInfo` lists them; `connect` strips the `scalardbnative.*` keys before calling the vendor
  driver.
- **Catalog ownership (rest of todo 037).** A registry in the driver: one `Catalog` per (engine, vendor
  URL, user, metadata schema), never keyed by password; its supplier opens connections with the connect
  properties; query and network timeouts on the catalog connection; the catalog is loaded eagerly at the
  first connect, so a missing or unreadable catalog fails `connect` with `08004` or `42501` rather than
  the first statement; catalogs are closed on `DriverManager.deregisterDriver` (and `deregister()` is
  added, from 017).

- **Per-engine comment rule (todo 053).** The executable-comment refusal of 4.1 becomes per engine, so
  ordinary comments and Oracle's `--+` hints parse where the engine reads them as comments; the router
  needs the parser to accept them, since a parse refusal applies to plain tables too.

Tests: unit tests for the refusal reasons, the property parsing and the comment rule per engine;
integration tests on PostgreSQL for the eager load and the error codes, and on each engine for the
comment forms.

### Milestone 1: wrapped connection, pass-through, refusals

**Done 2026-10-10** (todo 039), in the `jdbc` package: `Proxies`, `Router`, `Session`, `Errors`.
Two items moved: the rollback-only state of 4.4 goes to milestone 4, since nothing aborts a
transaction before the driver writes; `DriverErrors` is the one-method `Errors`, grown when a milestone
needs more. SQL Server `USE` of another database is documented as unsupported, not detected (milestone 8).

- **Proxies.** `java.lang.reflect.Proxy` over `Connection`, `Statement`, `PreparedStatement`,
  `CallableStatement` (refused for ScalarDB tables, passed through otherwise), `ResultSet` and
  `DatabaseMetaData`, each forwarding every method it does not intercept, as the design's Architecture
  section says. `unwrap`/`isWrapperFor` expose the vendor objects. `getConnection()` and `getStatement()`
  return the proxies, not the vendor objects.
- **Session state (todo 039).** A `Session` per connection holds the schemas and the double-quote
  setting, read at connect, outside a transaction, and marked stale after `setSchema`, `setCatalog`, any
  statement not classified as a SELECT or DML on known tables (which includes every `SET`, `USE`,
  `ALTER SESSION`, `EXECUTE AS`, DDL and passthrough statement), and at `commit`/`rollback` after such a
  statement inside the transaction; re-read lazily before the next lookup. DDL also invalidates the
  catalog's resolutions. `Kind.SESSION` is added to `SqlAst.Kind`.
- **Connection rules.** The isolation level is set to READ COMMITTED at connect; `READ_UNCOMMITTED` is
  refused; `setAutoCommit`, `commit` and `rollback` are forwarded; the rollback-only state of 4.4 lives
  here (after a driver-decided abort every statement and `commit` throw the same `40001` until
  `rollback`); the passthrough flag of `setClientInfo` sends statements verbatim, only in autocommit.
- **Router.** For each statement: parse with the engine and the session's double-quote setting; look up
  every table reference; route to pass-through (plain tables only), refusal (the contract of milestone 0),
  or the ScalarDB route. Until milestones 3 and 4 the ScalarDB route refuses with `0AN01` "not yet
  supported", so the driver is safe from this milestone on: nothing reaches a ScalarDB table unrewritten.
- **`DatabaseMetaData`** is forwarded as is; filtering `_data`, `_tx_metadata` and `virtual_tables` is
  deferred.

Tests (integration, PostgreSQL): plain statements of every JDBC kind pass through with results, update
counts and generated keys intact; every statement on a ScalarDB table is refused with `0AN01`;
`SET search_path` then an unqualified name resolves the new way; `setSchema` likewise; a passthrough
statement outside autocommit is refused; a HikariCP pool over the driver works (connection reset goes
through `setSchema`/`setCatalog`).

### Milestone 2: setup and connect-time verification

**Done 2026-10-10**, on all five engines rather than PostgreSQL only (user's decision; the rest of
milestone 8 stays there). `setup.Setup` lives in the main source set without a command line, which
milestone 9 adds, so the code does not move later. A table without setup is refused with `55000`, not
`08004`: the refusal comes per statement, and HikariCP evicts a connection on an `08` state. Classic
tables are not set up yet; they come with classic support.

- **Setup** (`setup.Setup`, in the driver). Per decoupled table, in order: the in-flight index, without
  blocking writes where the engine can; on an imported table the backfill of section 1, at READ
  COMMITTED, retrying unique violations and lock conflicts only; then the view `rc_T`, which also marks
  a completed backfill: the two arms of 4.2 as a UNION ALL, with the reader's rights where the engine
  has them; last a grant of SELECT on it to the application users given. Idempotent; one result per
  table, a failing table does not stop the others; a teardown drops both objects.
- **Verification at catalog load.** The catalog lists the setup's views (with their columns) and usable
  indexes by name; a table is set up when both exist and the view has the table's columns. A statement
  naming a table that is not set up is refused with `55000`. A listing failure other than a lost
  connection keeps the previous answer.

Tests: on all six configurations, setup on created, imported and key-only tables and a composite key;
re-running changes nothing; the backfill stamps missing and all-NULL metadata and waits only for rows in
flight over a deemed-committed row; the view's committed image for states 1 and 2, the NULL-row prepare
and an in-flight insert; teardown; a table without setup is refused with `55000`. On PostgreSQL: a
non-key filter through `rc_T` uses the table's index, row-level security holds through the view, and a
check violation fails the backfill at once; on MySQL an application user granted the view sees the
table as set up.

### Milestone 3: reads under READ COMMITTED

Done differently from the first plan. Every engine pushes the user's predicate into both arms of `rc_T`
(design section 3), so a read needs no arms of its own: the router renames each ScalarDB table to its
read view with the existing table renaming, and the statement keeps one predicate, its placeholders in
place and the user's own columns. That removes, for reads, the fragment emitter, the placeholder
re-binding and the result stripping; they move to milestone 4 with the anchors, which only writes
consume.

- **Route.** A SELECT naming a ScalarDB table at READ COMMITTED runs with each such table renamed to
  `schema.rc_T`; an alias, or the table's own name where a column qualifier uses it, keeps the
  qualifiers working. The connection's isolation level is tracked through `setTransactionIsolation`,
  and a prepared statement is routed again after it changes.
- **Refused, with 0AN01:** a read at any other isolation level; a locking read or `SELECT ... INTO`
  (milestone 4 places the lock); a table hint, sample clause, PIVOT or database link on a ScalarDB
  table, since a hint such as `NOLOCK` would reach the base tables through the view; a table in the
  classic layout; and every statement on a ScalarDB table that is not a SELECT.
- **Not done:** distributing a join over the arms. No engine pushes a join predicate into a UNION
  (measured later, `docs/benchmarks/read-overhead.md`; the claim above that every engine pushes the
  predicate holds for filters only), which milestone 3b fixes; `ResultSetMetaData.getTableName` names
  the view.

Tests: on all six configurations and both layouts, with a ScalarDB two-phase-commit transaction holding
an update, an insert and a delete prepared: the before images are read and the insert is not; a prepared
statement with a key placeholder; a filter on a non-key column matches the before image; COUNT, ORDER BY
with a row limit, a table name as a column qualifier, and a join of a created and an imported table;
after the commit, the new values. On PostgreSQL the refusals above.

### Milestone 3b: the split read

Milestone 3's renaming to `rc_T` is correct but slow wherever a join meets the view: no engine pushes a
join predicate into it (`docs/benchmarks/read-overhead.md`: 24-236x for a key join from a plain table,
up to 1800x for two ScalarDB tables on MySQL). Reads move to the split read of design 4.2, measured at
1.0-2.6x for one and two tables, before the writes, since both need the fragment emitter and the
re-binding.

- **The fragment emitter and placeholder re-binding** (todo 012): each split table reference replaced
  by a derived table holding its copy's rows under the table's own column names, so the user's
  predicates, joins and select list stay as written (measured equal to editing them per copy, design
  4.2); a map from each emitted placeholder to the user's, applied when the user binds.
- **The splitter**: 2^n copies over the ScalarDB tables inner-joined in the top-level FROM clause (a
  LEFT JOIN's preserved side included), with the committed check per engine (design 4.2); ORDER BY,
  row limits and DISTINCT on top, over the copies as a derived table with the column labels restored.
  ScalarDB tables elsewhere (subqueries, CTEs, the nullable side of an outer join) read through
  `rc_T`. A shape it does not handle, or more split tables than the limit (4), stays on `rc_T`.
- **Setup**: the `tx_state` histogram on Oracle and MySQL.
- **PostgreSQL**: `max_parallel_workers_per_gather = 0` on the session, restored around a split read
  whose plain statement's plan is parallel; the decision cached per statement.
- **Statement cache** (milestone 6, todo 008) brought forward if the splitter's parse and emit cost,
  measured here, exceeds the target there.

Done (2026-10-11): the fragment emitter, the re-binding (`setX` to every copy's placeholder, the
application's count and descriptions from `getParameterMetaData`, a stream refused since each value is
read more than once), the splitter for statements without GROUP BY or aggregates (the select list's
functions must be known row-wise ones), and the histogram. On `rc_T` for now: WITH clauses, window
functions, an unaliased expression in the select
list of a statement with ORDER BY, a limit or DISTINCT (its label differs by engine), and `*` over a
plain table or subquery under ORDER BY. Done (2026-10-13): GROUP BY over columns and COUNT, SUM,
MIN, MAX and AVG, per copy and combined on top except on PostgreSQL (design 4.2), with the labels
the engines give. Done (2026-10-13): each copy reads only the columns used (todo 096), the
committed check chosen per table on MySQL and MariaDB by access path and in-flight count, with
prepared statements prepared again when the count changes their form (todo 103), the row limit in
each copy (todo 089), and split reads without parallel workers on PostgreSQL. Not done: a split read
whose plain plan is parallel keeping its workers (done the same day, with `EXPLAIN (GENERIC_PLAN)`),
the statement cache, which the driver-level benchmark shows is needed (routing costs 0.3-2.6 ms per
uncached statement, todo 098). The driver-level benchmark (`DRIVER` mode, 2026-10-13) meets the
acceptance for the shapes the split covers; the LEFT JOIN's nullable side and the IN subquery wait
for milestone 3c. Todo 105 (formerly 082), an Oracle read that returned no rows in two runs, is
closed: ScalarDB's record commit, todos 083-085.

Tests: the cases of milestone 3 on every engine, each also with a join, an IN subquery, a LEFT JOIN, an
aggregate and three tables, with rows in flight; every split statement's rows equal to the same query
over the committed image computed through ScalarDB. Acceptance: the benchmark's shapes, run through
the driver, within the ratios the benchmark recorded for the chosen forms.

### Milestone 3c: outer joins and subqueries in the split

Milestone 3b leaves a ScalarDB table on the nullable side of a LEFT JOIN or in an IN or EXISTS
subquery on `rc_T`, which costs 376-1495x for the LEFT JOIN and 109-1200x for the IN subquery on MySQL
and MariaDB (`docs/benchmarks/read-overhead.md`); both are routine ORM output (an optional many-to-one
fetch join, `IN (SELECT ...)`). Design 4.2, "Which tables are split", gives the rewrites (todo 095).

- **Nullable tables**: three pieces, the inner join with each copy and the unmatched piece over an
  empty fragment with two `NOT EXISTS`.
- **Semi-joined tables**: IN and EXISTS as top-level conjuncts, two pieces, the first-match `NOT
  EXISTS` in the in-flight piece unless the subquery selects the table's key.
- **In an expression**: NOT IN, NOT EXISTS, and IN or EXISTS elsewhere, written twice inside each
  piece.
- **The limit** counts pieces, set from the measurement of todo 095 (32 pieces against `rc_T` and
  against a partial split).

Tests: each shape with the table's rows committed, in flight with and without a before image, and
with a matching row in both copies (a non-key IN over a value the committed and the in-flight copy
both hold); an ON clause and an IN comparison that are UNKNOWN; rows equal to the statement over the
committed image computed through ScalarDB. Acceptance: the benchmark's LEFT JOIN and IN shapes through
the driver within their recorded ratios; the non-key IN and the expression forms measured and
recorded.

Done (2026-10-14): the three kinds, with a semi-joined IN over a non-key checked by a correlated
`NOT EXISTS` where its outer column cannot be captured, NOT EXISTS written as two, MySQL's in-flight
writing probed per outer row (`NO_SEMIJOIN`), and the per-row committed check of MySQL and MariaDB
extended to nullable and subquery tables. Through the driver the benchmark's LEFT JOIN is 1.2-2.3x
on every engine, against 790-1850x on MySQL and MariaDB before; the IN and NOT EXISTS shapes are
1.1-7.4x except on MySQL (`docs/benchmarks/read-overhead.md`, last section; todo 118). Not done: a
RIGHT JOIN, ANY, SOME and ALL, and subqueries outside the WHERE clause stay on `rc_T`; the limit
stays at 16 copies until the 32-copy measurement (todo 119).

### Milestone 4: writes under READ COMMITTED

Carried over from milestone 3: result stripping and the anchors, for the key read that returns
`(tx_id, tx_version)` and for the write rewrites, on the fragment emitter and re-binding of milestone
3b; a user `FOR UPDATE` or `FOR SHARE` on a ScalarDB table, pushed into the committed copy.

- **INSERT** (4.3): the user's INSERT on `T`, then the `T_meta` insert with the stamp of I5, both
  `ON CONFLICT DO NOTHING` with the row count checked; the duplicate classification (application
  duplicate, in-flight conflict `40001`, orphan take-over); generated keys and `RETURNING` from the `T`
  insert.
- **UPDATE and DELETE by key** (4.3 steps 1 to 4): the state read, the anchor from the map or taken now,
  the user's DML first, the CAS second, the zero-row rules, the NULL-metadata CAS and the tombstone of
  4.4 and 4.5, no second CAS for a row already written in this transaction.
- **By predicate**: key selection through the read path of milestone 3, then per key, re-applying `P`.
- **Autocommit** wraps a write in its own transaction (4.4). **The bounded wait** of section 6 for a row in
  flight at step 1, `inflightWaitMs`, default 0 (abort at once).
- **Batches**: `addBatch` pre-processes each statement; a predicate DML expands per key.
- **Return values**: the user's update count, the sum for a predicate DML.

Tests (integration, both layouts): each write kind on committed rows; a write to a row a ScalarDB
transaction holds prepared aborts with `40001` and leaves the row as ScalarDB left it; the tombstone
blocks a ScalarDB resurrection; the orphan take-over; NULL metadata on an imported table before the
backfill; rollback-only after an abort.

### Milestone 5: interleaving tests

The proof of the deliverable. A small scheduler drives two actors step by step on the same rows, a
driver transaction through the driver's JDBC URL and a ScalarDB transaction through ScalarDB's
`DistributedTransactionManager` over the same database, and checks the outcome after each schedule.

- **Deterministic scenarios**, one per row of design section 9 that applies to READ COMMITTED: dirty
  read of an after image, before image used as a result, driver overwrites an in-flight row, aborted
  after image becomes permanent, ScalarDB overwrites a driver write, lost update in both directions,
  NULL-metadata row vanishes, in-flight row pinned by a user lock, deleted row resurrected; plus the
  "why one statement" case of 4.2 (a rollback between two reads).
- **Randomized schedules**: counters incremented by both sides over many interleavings; the invariant is
  that the final value equals the number of committed increments, which catches lost updates the fixed
  scenarios miss.
- Every scenario names the design rule it proves, so a failure points at a rule.

### Milestone 6: parse once (todo 008)

- A bounded statement cache keyed by (SQL text, engine, double-quote setting, the session's schemas,
  catalog generation) holding
  the route, the rewritten text and the placeholder map; `prepareStatement` fills it, `execute(String)`
  reads it.
- No verification re-parse when nothing was replaced; the parser called directly, without JSQLParser's
  per-call thread.
- **Acceptance**: a benchmark in the repository; a cache hit costs well under 10 µs, a miss stays under
  150 µs for the Hibernate-style key lookup measured in the review (about 340 µs today).

End of the first deliverable: commit, review with `/ce:review`, and a preview build for PostgreSQL at
READ COMMITTED.

### Milestone 7: SNAPSHOT and SERIALIZABLE on PostgreSQL

The detector form of 4.2, abort on any in-flight row; `FOR SHARE` in the first arm for SERIALIZABLE; the
coarse path with `LOCK TABLE` as the first statement, and its `40001` when it is not; the anchors and the
CAS unchanged. The interleaving tests gain the write-skew and phantom rows of section 9.

### Milestone 8: the other engines

In the design's certification order: MySQL and MariaDB together, SQL Server, Oracle. Each adds its row of
the per-engine table to `Engine` (insert collision, locking spellings, row limit and boolean test,
retryable codes, the in-flight index form) and runs the full interleaving suite on its container. SQL
Server adds the `READ_COMMITTED_SNAPSHOT` check at connect, and the token pass of 4.7 that strips its
table hints before parsing; that pass must still refuse `NOLOCK` and `READUNCOMMITTED` on a ScalarDB
table, so it detects them before stripping. Oracle maps SNAPSHOT and SERIALIZABLE as section 5 says.

### Milestone 9: tools and acceptance

The `tools` module with the setup command (`setup`, `check`, `teardown`, `--dry-run`, exit codes, todo
026), a command line over the driver's `setup.Setup`, which stays in the driver, the sweeper calling `recoverRecord`, and the `samples/` applications of design 4.6 (plain JDBC,
Spring Data JDBC, JPA, MyBatis) as the acceptance suite. The CI engine matrix and the nightly (todos 021
and 052).

## Package layout

New packages under `com.scalar.db.nativejdbc`, each depending only on what is listed:

| Package | Contents | Depends on |
|---|---|---|
| `jdbc` | the proxies, `Session`, `Router` and the refusal contract, the per-transaction state | `sql`, `catalog`, `engine` (`rewrite` from milestone 3) |
| `rewrite` | the two-arm read, the `rc_T` renaming, the write plans, the statement cache | `sql`, `catalog`, `engine` |
| `setup` | the setup and teardown of each table's view and index, the backfill | `catalog`, `engine` |
| `sql`, `catalog`, `engine` | as today | as today |

`sql` stays free of `java.sql`; JDBC calls live in `jdbc`, `catalog` and `engine` only.

## Todos this step closes

| Todo | Where |
|---|---|
| 016 error contract | milestone 0 |
| 053 per-engine comment rule | milestone 0 |
| 017 driver entry points, remaining parts | milestone 0 |
| 037 catalog connection, remaining parts | milestone 0 |
| 039 session state | milestone 1 |
| 012 rewrite fragments | milestone 3 |
| 008 statement cache | milestone 6 |
| 026 operator tooling | milestone 9 |
| 021, 052 harness and CI | milestones 5 and 9 |

The cleanup todos (019, 020, 022 to 025, 028 to 031, 050, 051) are taken when a milestone touches the
code they name, not separately.

## Decisions (accepted 2026-10-09)

1. **Classic-layout tables in the first deliverable.** The design covers them, but the user's main case
   is the imported decoupled layout. Proposal: milestones 3 and 4 handle the two decoupled layouts and
   refuse classic tables with `0AN01`; classic support follows in milestone 7 or 8.
2. **Setup as a test helper first.** Proposal: yes, promoted to the `tools` module in milestone 9, so the
   protocol is proven before the operator surface is designed.
3. **The first deliverable stops at milestone 6** with a preview build for PostgreSQL READ COMMITTED,
   before the other levels and engines.

## Risks

- **ScalarDB-side defects** that the tests will meet: the all-NULL metadata row an imported table can
  never re-prepare, and the unmapped lock-timeout codes (design section 1 and 6). The fixtures run the
  backfill and leave lock timeouts unset, as the design prescribes; the tests assert the documented
  behaviour rather than working around it.
- **JDBC surface breadth.** Applications use far more of JDBC than the statements above (scrollable and
  updatable result sets, `getGeneratedKeys` variants, `setFetchSize`, savepoints, multiple result sets).
  The proxies forward by default; for ScalarDB tables anything the rewrite cannot honour, an updatable
  result set for instance, is refused with `0AN01` rather than approximated. The samples of milestone 9
  are the check that common ORM usage is covered.
- **Savepoints.** A user savepoint rolled back to between the driver's DML and its CAS would split a
  driver write. The driver's write steps run inside a savepoint of their own, or user savepoints on a
  connection with a pending driver write are refused; to be decided in milestone 4.
- **The interleaving scheduler** needs ScalarDB transactions paused at precise points (after prepare,
  before commit). ScalarDB's API exposes prepare, validate and commit for two-phase transactions; the
  plan relies on that. If it proves too coarse, the fallback is to drive ScalarDB's prepare state
  directly with the storage API in the fixtures.
- **Performance** before milestone 6 is roughly double a direct key lookup; acceptable for correctness
  work, not for a preview.
