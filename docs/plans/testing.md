# Plan: SQL coverage, isolation and performance testing

Status: accepted 2026-10-20, with the decisions under "Decisions". T0 to T3 done, T4 but for random statements, T5's list-append and rw-register (see each milestone).

## Goal

The features are close to done. This step tests what the driver does to statements on ScalarDB tables,
widely and at volume, before any further features:

1. **SQL coverage.** Every statement on a ScalarDB table that the driver accepts returns what the
   database would return over the committed image, and writes what the database would write. A
   statement it does not accept is refused cleanly. For each statement, the route it took is recorded.
2. **Isolation.** Driver transactions and ScalarDB transactions on the same rows, run concurrently and
   with faults, produce histories that satisfy the level design section 5 claims.
3. **Performance.** Whole workloads through the driver, compared with the vendor driver on the same
   database, with and without ScalarDB traffic on the same tables.

Pass-through is out of scope as a subject: statements on plain tables reach the database as written. What
is in scope is the routing decision in front of it. A ScalarDB table taken for a plain one (through a
synonym, a view or a function) reads uncommitted rows, and the in-flight runs of milestone T2 catch that
without a suite of their own.

## What exists and what this step uses

| Piece | State | Used for |
|---|---|---|
| `testing.Engines` | five engines in containers, started serially, run in one task; no external database (todo 052) | every milestone |
| `ReadIntegrationTest.sameAsPlain` | runs a statement through the driver over a ScalarDB table and directly over a plain table holding its committed image, compares labels and rows; about 37 statements, one table, one prepared transaction | grows into the T1/T2 comparator |
| `testing.Rows` | rows as strings; LIMIT and locking clauses rewritten for T-SQL and Oracle | the comparator's row form |
| `Router.route(...).sql` | the rewritten statement | the route of each statement (`rc_` is a fallback) |
| `InterleavingIntegrationTest`, `SerializableIntegrationTest` | targeted interleavings, predicate reads included | kept; Elle does not model predicates |
| `bench.ReadOverheadBenchmark`, `bench.RouteCostBenchmark`, `bench.DelayProxy` | per-shape read overhead through the vendor drivers; routing cost; a delaying proxy | kept; T6 adds whole workloads |

## Milestones

Each milestone ends with `./gradlew check` green and a commit. The suites of T1 to T5 are integration
tests, off in `check` beyond a small smoke set and run in full with `-Dnativejdbc.coverage=true` or
`-Dnativejdbc.isolation=true`, the way the benchmarks take `-Dnativejdbc.bench=true`. PostgreSQL goes
first in every milestone, then Oracle, MySQL, MariaDB and SQL Server, in that order.

### Milestone T0: the harness

**Harness and CI done 2026-10-20** (todos 021, 052): per-engine tasks three at a time, about 13 minutes
for the driver's suite locally against 45; the external database; the CI matrix. The seed comes with
its first user, T2. Todo 094 (2026-10-21): Oracle's fixed, locked statistics measured and reviewed,
not committed; the review's findings wait for a decision (the todo's work log).

- **Todo 052.** Start the containers in parallel (`Startables.deepStart`); honour
  `-Dnativejdbc.<engine>.url/username/password` and skip the container when set; set up SQL Server's
  database with the snapshot settings; give `admin()` an isolation parameter; an Oracle application
  user besides `SYSTEM`. The external override is what T5 and T6 run against.
- **Todos 021 and 052, from milestone 9a of `proxy-step.md`, moved here.** One integration test task
  per engine, run side by side, so a full check costs about the slowest engine rather than the sum
  (about 45 minutes in one task today); `-Dnativejdbc.engines` still narrows it. CI: the unit tests and
  static checks in one job, the integration tests in a matrix job per engine. To measure first: that
  Gradle runs the per-engine tasks of one project in parallel (it does with the configuration cache on),
  and the memory of five containers at once against Docker's 7.75 GB, capping it at two or three
  engines at a time if needed. SQL Server's snapshot settings are already set (milestone 8c).
- **Todo 094.** Setup gathers the `tx_state` statistics. Without the histogram, Oracle and MySQL scan
  (`docs/benchmarks/read-overhead.md`, conclusion 3), and T6's numbers would measure that.
- **A seed.** Every randomised suite takes `-Dnativejdbc.seed`, prints the seed it used, and reproduces
  a run from it.

### Milestone T1: differential reads, nothing in flight

**First pass done 2026-10-21** (`coverage/CorpusIntegrationTest`, `-Dnativejdbc.coverage=true`): the
schema in both layouts, the small and the large data set, 90 statements of the shared corpus plus 4-6 of each
engine's row limits, the comparator (rows, labels and types, SQLState of a failure, refusals apart,
nullability reported apart), and `build/coverage/<engine>[-large].md` with each statement's route.
`VARCHAR` came back as `LONGVARCHAR` on MySQL and MariaDB (todo 303, fixed: the before image is cast
to the column's type, and a `DATETIME`'s to its precision); nullability differs on PostgreSQL and
Oracle (todo 302). `columns.sql` (2026-10-21) reads every column by name and by `*`: on MySQL and
MariaDB a `BOOLEAN` came back as `TINYINT` (todo 304, fixed: the driver reports a column the select
list returns as it is with the column's own type, and returns a `Boolean`). About a quarter of the
statements fall back to `rc_T`: right and full joins, CTEs, window functions, GROUP BY an expression,
a derived table with DISTINCT. Open: decimals and CHAR, the locking clauses, JSON functions, more
statements per area.

With nothing in flight, the data of a ScalarDB table is its committed image. So each statement runs
directly through the vendor driver and through the driver, on the same tables, and the two results must
be the same.

- **Schema.** One fixed schema per engine: ScalarDB tables in both layouts, created and imported, with
  single and composite keys, the column types the driver binds (integers, decimal, float, char and
  varchar, date and timestamp, boolean where the engine has one), nullable columns, and plain tables
  beside them. Two data sets: a small one built for semantics (NULLs, duplicates, ties, empty groups,
  rows with no match), and a few thousand rows, so that the engines choose other plans.
- **Corpus.** Plain `.sql` files under `driver/src/integrationTest/resources/corpus/`, one per area,
  shared across the engines, with a directory per engine for that engine's spellings. Areas: the
  SQL-92 query constructs (joins of each kind, subqueries in each position, set operations, CASE,
  CAST, aggregates, GROUP BY and HAVING, DISTINCT, ORDER BY, NULL and three-valued logic), then what
  applications use beyond SQL-92 (CTEs, window functions, `LIMIT`/`OFFSET`/`FETCH FIRST`, locking
  clauses with `NOWAIT` and `SKIP LOCKED`, JSON functions), written for what T4's suites do not
  reach. NULL handling gets more cases than its share: `NOT IN`
  with a NULL, outer joins, `COUNT(col)` are where a split's `UNION ALL` or anti-join would change the
  result quietly.
- **Comparator.** `sameAsPlain` generalised: rows as multisets unless the `ORDER BY` fixes a total
  order. A statement with `LIMIT` must have a deterministic `ORDER BY`, and the corpus check refuses
  one that does not. Floating-point columns are compared to a relative tolerance. Labels, types and
  nullability of `ResultSetMetaData` must match. If the direct statement fails, the driver must fail
  with the same SQLState. A statement the driver refuses that the database accepts is recorded as a
  refusal, not a pass.
- **Report.** Per engine, `build/coverage/<engine>.md`: for each statement, its route (split, `rc_T`
  fallback, per-row check, refused with its reason) and its outcome. The share of fallbacks is the
  performance risk the corpus measures: a fallback costs 24-1800x on joins.

### Milestone T2: differential reads, rows in flight

**Done 2026-10-21**, as a second phase of `CorpusIntegrationTest` (`coverage/InFlight`): the plain
schema is the copy, since the ScalarDB tables are imported from identical ones and nothing is
committed to them. Three two-phase-commit transactions per layout update, delete and insert over
every key of the small set and a tenth of the large one (about 1,900 rows, above `PER_ROW_ABOVE`),
from `-Dnativejdbc.seed`, prepared and left so while the corpus runs, then rolled back; the test
asserts rows are in flight. Every engine passes as in T1. On Oracle a prepare can fail with ORA-08177
(SERIALIZABLE storage); that transaction is rolled back and the rest stay in flight. The
coordinator-decided states stay with `ReadIntegrationTest`.

Prepared rows do not change the committed image. So the plain copies of the ScalarDB tables taken while
nothing is in flight stay the expected answer however many ScalarDB transactions are then prepared:

> copy the tables (vendor driver, nothing in flight, `assertSettled`) → prepare random ScalarDB
> transactions over several tables → run the T1 corpus through the driver and directly over the copies
> → compare → roll the transactions back

- The random transactions update, insert and delete a share of the keys of each table, several of
  them prepared together and on different tables, so that the copies of a split differ from each
  other. One run with every key in flight, as the benchmark's worst case.
- The corpus is T1's, with the copies' names swapped in. The ScalarDB-table-taken-for-plain cases
  (synonyms, views, functions) are added here: with rows in flight they return the wrong rows.
- **Coordinator decided, rows still prepared.** A transaction whose coordinator record says committed
  or aborted while its records are still prepared (design section 7) is not covered by the copy. These
  few states keep hand-written expectations, as `ReadIntegrationTest` has them now.
- MySQL and MariaDB's per-row check above `Router.PER_ROW_ABOVE` rows in flight is a separate route,
  so one run puts more rows than that in flight.

### Milestone T3: differential writes

**Nothing in flight done 2026-10-21** (`coverage/WriteCorpusIntegrationTest`, `resources/writes/`): 36
shared statements and each engine's own (upserts, `MERGE`, joins in `UPDATE` and `DELETE`, `RETURNING`
and `OUTPUT`), in order, through the driver on each layout and directly on a plain twin; update
counts or returned rows and then the three tables compared after each statement; a statement the
driver refuses rolled back on the twin. At the end ScalarDB reads every row and must see the twin's,
and a ScalarDB transaction writes every row and commits. Every engine passes; `build/coverage/
<engine>-writes.md` lists 13 to 19 refusals per layout, the documented write scope: `INSERT ...
SELECT`, a subquery or join reading another table or the target, the upserts (`ON CONFLICT`, `ON
DUPLICATE KEY UPDATE`, `INSERT IGNORE`, `REPLACE`, `MERGE`), `UPDATE` and `DELETE` with `LIMIT`,
`RETURNING` on `UPDATE` and `DELETE`, a key's update.

**Rows in flight done 2026-10-21**: the same test again on fresh schemas (x*) with `InFlight` holding
three-tenths of the keys prepared (some in every table) and `inflightWaitMs` 0. The keys a
statement reaches are those its `VALUES` name or its `WHERE` matches on the twin, read before it
runs, and those whose rows it changes: one that reaches a key in flight must abort with `40001`,
which 8 to 19 statements per engine do; any other must match the twin, and an abort there fails.
After every statement, aborted and refused ones too, all three tables match; after the rollback
ScalarDB reads and writes every row. A statement outside the write scope is marked `-- refused`, and
a refusal of any other fails, in the read corpus too. Every engine passes (review todos 305-311).

- **Nothing in flight.** Each DML statement runs through the driver on the ScalarDB tables and directly
  on their plain copies. Compare the update counts, the generated keys and the final data. Then read
  every row the driver wrote through the ScalarDB API and compare again, which shows that the metadata
  it left behind is valid. The direct comparison alone cannot. The corpus: single and multi-row
  `INSERT`, `INSERT ... SELECT`, `UPDATE` and `DELETE` with joins and correlated subqueries, each
  engine's upsert and `MERGE`, `RETURNING`/`OUTPUT`, batches.
- **Rows in flight.** A write that reaches only keys not in flight must match the copy. A write that
  reaches a key in flight must behave as design section 6 says: wait, then `40001`. This is asserted,
  not compared.

### Milestone T4: growing the corpus

The corpus comes from the engines' own test suites rather than from ORMs: they are far wider than any
application's SQL, and the comparator needs no expected results, so their expected outputs are
ignored. What ORMs would add beyond SQL is how they use the JDBC API (batches, generated keys, fetch
sizes, savepoints), which the integration tests cover now. An ORM is added when a user's ORM hits a
problem.

- **PostgreSQL's regression tests.** `src/test/regress/sql/` at a pinned PostgreSQL tag, fetched at
  test time rather than vendored. The tables of `test_setup.sql` (`onek`, `tenk1`, `int4_tbl` and the
  rest) are created twice, in a plain schema and in a schema imported into ScalarDB, with the same
  definition on both sides. Where ScalarDB cannot import a table as it is, the change is made on both
  sides: a primary key on a unique column (`unique1`), a column type ScalarDB cannot import (`name`)
  as the nearest one it can. A table with no unique column is left plain. Each query-oriented file
  (`select*`, `join`, `subselect`, `aggregates`, `union`, `case`, `window`, `with`, `limit`,
  `groupingsets`, `insert`, `update`, `delete`, `merge` and their kin) runs statement by statement on
  two sessions, one direct with `search_path` on the plain schema and one through the driver with it
  on the ScalarDB schema. Every statement is compared as in T1, in T2 with rows in flight, and the
  final data as in T3. Left out: files about DDL, PL/pgSQL, types, catalogs and `EXPLAIN`, whose
  output the rewrite changes on purpose. A table a file creates for itself stays plain, so its
  statements only test the routing.
**PostgreSQL started 2026-10-21** (`RegressIntegrationTest`, `-Dnativejdbc.regress=true`, about
two minutes per layout): 21 query files of REL_16_15, 4,989 statements per layout. `BEGIN`, `COMMIT`
and `ROLLBACK` run as JDBC calls, as an application makes them; a statement the driver refuses that
names no imported table runs in the driver's own session directly, as with the passthrough
setting, so that what it creates exists for the next; a failure that follows from a refusal (an
object not created, a table not written, a transaction whose mode or a statement was refused) is
counted with the refusals; `EXPLAIN` is compared by whether it runs. Result: 4,243 passed, 745
refused, 1 failed, the same in both layouts. An outer aggregate written inside a subquery returned
a row per copy of the split read (todo 312, P1, fixed: such a statement is read through `rc_T`).
The failure left: a whole-row reference reports `record` (todo 313). Refusals: `EXPLAIN` of ScalarDB tables (199), statements the parser cannot read
even without a ScalarDB table (about 300: cursors, `RELEASE SAVEPOINT`, `ORDER BY ... USING`,
`JOIN ... USING ... AS`, nested grouping sets; todo 314), PL/pgSQL bodies, views and `ANALYZE` over
ScalarDB tables (todo 315). In flight, a second run per layout holds a tenth of the imported
tables' keys (at least one row each, about 1,200 in `tenk1`) in prepared ScalarDB transactions, and
the driver set to abort at once on a row in flight: a write or locking read of an imported table that
aborts so is counted as such and not run on the plain side, nor is the rest of its transaction; a
statement with a window function is compared by its number of rows, since the committed image is
read by another plan and a window that does not order its rows sees them in another order. Result:
the same as settled, over several seeds (`-Dnativejdbc.seed`), a write aborted in some.

- **sqllogictest**, for the other four engines, which have no public suite PostgreSQL's size (MySQL's
  and MariaDB's `mysql-test` may follow). Its SELECT corpus is loaded into ScalarDB tables and run in
  the same way, with the expected results ignored.
**Every engine run 2026-10-22** (`SqlLogicIntegrationTest`, `-Dnativejdbc.slt=true`; the first
1,000 queries of each of 15 files, `-Dnativejdbc.slt.queries`): select1-5, `index/*/10` and
`random/*`, fetched at a pinned commit of its GitHub mirror. Each file's tables are made in a plain
schema and in one imported into ScalarDB, in each layout; a table without a primary key gets
`slt_rowid` as its last column, on both sides; the engine's types and `skipif`/`onlyif` labels
apply, and on Oracle a table's alias loses its `AS`. Per layout, the same in both:

| engine | passed | refused | failed |
|---|---|---|---|
| PostgreSQL | 13,275 | 1,457 | 0 |
| SQL Server | 13,278 | 1,454 | 0 |
| Oracle | 13,252 | 1,454 | 26 |
| MySQL | 12,542 | 1,454 | 4 |
| MariaDB | 12,540 | 1,454 | 6 |

Every refusal is a sign after a sign, `- - 27`, which the parser cannot read (todo 317, P3). The
failures match in values: on MySQL and MariaDB an integer result reports another integer type
(todo 321, P3), on Oracle `SELECT *` of a self-join labels its columns `QCSJ_C...` (todo 322, P3).
Before, an unaliased expression's label differed on every engine but PostgreSQL, the driver
re-spacing its text or renaming a table in it (MySQL 3,033 failures, MariaDB 3,035, Oracle 1,444,
SQL Server 45): such an item now keeps its text (todo 320, P2, fixed). Found and fixed: `SELECT *` of an imported table listed its key first
(todo 316, P1; the setup must run again after an upgrade), and a table named in a query and in its
subquery was refused on the read views (todo 318). select5, joins of up to 17 tables, is skipped on
MySQL and MariaDB, whose join-order search takes minutes a query over the read views (todo 319).
- **Random statements.** Later, and only once the above runs clean: a generator over the T1 schema in
  the manner of SQLancer, its statements kept when the database accepts them. Both T1 and T2 run its
  output, from a seed.

### Milestone T5: isolation, with Maelstrom and Elle

Maelstrom runs a node as any program that speaks JSON on stdin and stdout, generates the
transactions, records the history, treats timeouts and unknown outcomes as indeterminate, and checks the
history with Elle. Our part is one Java node. No Clojure.

- **The node.** A main class in `integrationTest`, started by a script that a Gradle task writes with
  its classpath. With `--node-count 2`, `n0` runs each transaction through the driver and `n1` through
  ScalarDB, on one ScalarDB table `iso.kv(k, v)`, against an engine given by T0's external override.
  A `40001` is a definite abort (Maelstrom's `txn-conflict`); a commit whose outcome is unknown
  is indefinite (`crash`).
- **Workloads.** `txn-list-append`: an append through the driver is the one-statement
  `UPDATE ... SET v = v || ?` (spelled per engine), through ScalarDB a Get and a Put. Every read
  returns the whole list, so Elle sees each key's version order. `txn-rw-register`: `SELECT` and blind
  `UPDATE`/`INSERT`, which take other paths in `Writes` and in ScalarDB's conflict checks. Elle infers
  less from it, so it complements list-append rather than replacing it.
- **Bank.** Not a Maelstrom workload; a Java integration test. Transfers between accounts through
  both clients, and concurrent driver reads of `SELECT SUM(balance)` and grouped sums, which must stay
  constant. Elle's models read and write single keys; this is the only check here on the split's
  aggregate path under concurrency.
- **Faults.** The ScalarDB node abandons a share of its transactions after prepare (left to lazy
  recovery) and replies indefinite. The driver node closes its connection mid-transaction, and during
  commit, at a given rate. A long run restarts the database from outside.
- **Matrix.** Each level design section 5 lists as built, per engine, with the ScalarDB settings the
  README requires. Each history is checked against the weaker of the two claimed levels.
- **The checker must catch something.** Two runs must fail: a plain vendor connection in the driver's
  place (no protocol at all), and SERIALIZABLE without the ScalarDB settings the README requires. If they pass,
  the workload is too weak to show anything.

**List-append done 2026-10-23** (`isolation/MaelstromIntegrationTest`, `-Dnativejdbc.isolation=true`;
Maelstrom v0.2.4 fetched and checked by its hash; the node is `isolation/MaelstromNode`, started with
the test's database as the fixtures' external one). Per engine and level, in both layouts, two runs:
driver and ScalarDB (`n0`, `n1`), checked against the weaker level, and the driver on both nodes,
checked against its own. ScalarDB's SNAPSHOT is read-committed snapshot isolation, which allows read
skew (its documentation; a SQL Server run found one inside a ScalarDB transaction), so a mixed
SNAPSHOT run is checked as Elle's cursor stability, read committed without lost updates; a
driver-only one as snapshot isolation. Result, 20 seconds a run, SERIALIZABLE 60: every run valid,
about 800 transactions committed a run (2,200 at 60 seconds), a tenth aborted. The two that must
fail do, on every engine: a plain vendor connection in the driver's place (G0, and G1c and
incompatible orders on SQL Server), and SERIALIZABLE without ScalarDB's settings (G2-item, and
G-single-item on MariaDB and SQL Server, every cycle with driver and ScalarDB transactions in it; on
SQL Server only at 60 seconds, which is why those runs are as long). Passthrough was the first
control, until the discussion it started made the driver refuse it on ScalarDB tables (todo 323).
**Rw-register done 2026-10-23**, the same test, the same runs: every run valid on every engine (about
800 transactions committed a run, 2,400 at 60 seconds). The two controls pass with the register on
all engines but one (SERIALIZABLE without ScalarDB's settings on SQL Server, G-single-item): with blind
writes Elle cannot order a key's versions, so it misses what list-append finds. The controls
therefore run with list-append only, and a valid register run adds less than a list-append one.
Left: the bank test, the faults, and the long runs in the cloud.

### Milestone T6: performance, with BenchBase

BenchBase (CMU) is JDBC-based and speaks all five engines. Its configuration takes the
`jdbc:scalardb-native:` URL with the driver and vendor jars on its classpath.

- **Workloads.** TPC-C (writes, contention), YCSB (key operations, tunable skew), TPC-H (joins,
  aggregates, subqueries). TPC-H's 22 queries are also a coverage corpus for T1: queries over many
  ScalarDB tables meet the split's copy limit (todo 119).
- **Loading.** BenchBase creates and loads its tables. They are then imported into ScalarDB and Setup
  runs, before `--execute`.
- **Targets.** Against run (a), throughput and p95 latency within **10% for simple statements**
  (YCSB, and TPC-C's transactions) and **20% for complex ones** (TPC-H), for runs (b) and (c).
  TPC-C is measured per transaction type and TPC-H per query, not as a geometric mean, which would
  hide one query at 10x. These targets are well below what the
  per-statement benchmark measured locally (`read-overhead.md`: one-table shapes 1.0-1.7x, joins of
  two tables 0.4-2.6x). A workload adds network and commit time to every statement, so its overhead
  is lower, but a miss is likely, and then T6 becomes optimisation work, one todo per shape.
- **Runs, per workload and engine.** (a) the vendor URL; (b) the driver with every table plain, which
  is the cost of routing; (c) the driver with every table on ScalarDB, which is the cost of the
  rewrite. For each run: throughput, p50/p95/p99 latency, abort rate. No ScalarDB transactions run in
  T6: what it measures is the rewrite. The driver's own transactions still leave rows in flight for
  each other, which TPC-C's contention shows in (c). Decided 2026-10-24: half the tables on ScalarDB,
  and (c) with ScalarDB's own load on the same tables, are left out. How driver and ScalarDB traffic
  share rows is in `inflight-check.md` and T5.
- **Isolation.** BenchBase's `<isolation>`, which it passes to `setTransactionIsolation`. Run (a) takes
  the vendor's matching level, not the same constant: for SNAPSHOT (`TRANSACTION_REPEATABLE_READ`
  through the driver) Oracle's `TRANSACTION_SERIALIZABLE` and SQL Server's `TRANSACTION_SNAPSHOT`.
  Oracle refuses SERIALIZABLE through the driver, BenchBase's default for TPC-C. SNAPSHOT on every
  engine and workload; TPC-C also at SERIALIZABLE on PostgreSQL, MySQL and MariaDB; YCSB once at READ
  COMMITTED. Without ScalarDB transactions the ScalarDB settings of the README's table do not apply,
  but for PostgreSQL's `scalar.db.jdbc.isolation_level=SERIALIZABLE` at import.
- **Where.** Local containers for smoke runs only; SQL Server runs under x86 emulation there. The
  numbers come from a cloud environment (see "Cost" and `benchmark-environment.md`), one database
  per engine, and go to `docs/benchmarks/` with their configuration, as `read-overhead.md` does.
- **Publishing.** The licences of Oracle and SQL Server restrict publishing benchmark results without
  the vendor's permission. Their numbers stay internal until that is checked.

## Cost

The budget is not fixed; nothing is provisioned before its cost is agreed. A rough estimate, from
memory of AWS on-demand prices, to be replaced by a quote at current prices before provisioning:

| Item | Assumption | Estimate |
|---|---|---|
| PostgreSQL, MySQL, MariaDB, SQL Server | per engine a database VM (8 vCPU, 32 GB, gp3 SSD) and a client VM (8 vCPU) for BenchBase, self-managed; SQL Server Developer edition | about $0.75 per hour per engine |
| Oracle | RDS for Oracle, Standard Edition 2, licence included, plus a client VM; Oracle Free's limits (2 CPUs, 2 GB) make its numbers meaningless | about $1.5-2.5 per hour |
| Hours | T6: loading, three runs of three workloads, three repetitions, setup and failed runs, about 25 hours per engine (40 with the five runs first planned); T5's long runs, about 24 hours per engine on the same VMs | about 49 hours per engine |
| Total | the five engines, storage and transfer, once | about $400-800 |

VMs are stopped between runs. A second round after optimisation work costs about the same again.

## Decisions (2026-10-20)

1. **No ORMs in T4.** PostgreSQL's regression tests, sqllogictest for the other engines.
2. **Overhead targets:** 10% for simple statements, 20% for complex ones (T6, "Targets").
3. **A cloud environment** for T5's long runs and T6, budget not fixed, the cost agreed in advance
   ("Cost").
4. **Engine order:** PostgreSQL, Oracle, MySQL, MariaDB, SQL Server.
5. **T6 measures the rewrite only** (2026-10-24): runs (a), (b) and (c), no ScalarDB transactions.

Open: which cloud and account.



## Ownership

This plan was run by its own agent, beside the work on milestone 9 of `proxy-step.md`. Since 2026-10-21
the SQL coverage and isolation milestones, T1 to T5, are run by the driver's agent, and the testing
agent keeps T6 and the benchmarks (`benchmark-environment.md`, `paging-route.md`, todo 094). The
fixtures (`Engines`, `Rows`), the integration test wiring in `driver/build.gradle.kts` and
`build-logic`, and `ci.yml` stay with the testing agent; a change to them is agreed first. A failure the driver causes is filed as a todo,
with the statement and its route, and fixed in `driver/src/main` by the milestone 9 work, which owns it.

## Risks

- **Flaky comparisons.** Ties under `LIMIT`, float rounding and engine-chosen orders give false
  failures that train people to ignore the suite. The comparator's rules in T1 exist for this. A
  failure reproduces from its seed, or it is a bug in the harness.
- **Lazy recovery during T2.** A committed ScalarDB transaction that left records prepared changes
  what the driver reads (design section 7). `assertSettled` before the copies are taken.
- **Elle's blind spot.** Elle does not model predicates. Phantoms and range reads stay with the
  targeted tests and with T2.
- **The corpus finds more than can be fixed.** Each failure becomes a todo, with its route and
  statement. Refusals are counted, not fixed one by one, until the regression suites show which ones matter.
