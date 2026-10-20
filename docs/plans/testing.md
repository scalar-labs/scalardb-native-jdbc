# Plan: SQL coverage, isolation and performance testing

Status: accepted 2026-10-20, with the decisions under "Decisions". Nothing here is implemented yet.

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
- **sqllogictest**, for the other four engines, which have no public suite PostgreSQL's size (MySQL's
  and MariaDB's `mysql-test` may follow). Its SELECT corpus is loaded into ScalarDB tables and run in
  the same way, with the expected results ignored.
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
- **The checker must catch something.** Two runs must fail: the driver with passthrough on (`setClientInfo`
  `scalardbnative.passthrough`, no protocol at all), and SERIALIZABLE without the ScalarDB settings the README requires. If they pass,
  the workload is too weak to show anything.

### Milestone T6: performance, with BenchBase

BenchBase (CMU) is JDBC-based and speaks all five engines. Its configuration takes the
`jdbc:scalardb-native:` URL with the driver and vendor jars on its classpath.

- **Workloads.** TPC-C (writes, contention), YCSB (key operations, tunable skew), TPC-H (joins,
  aggregates, subqueries). TPC-H's 22 queries are also a coverage corpus for T1: queries over many
  ScalarDB tables meet the split's copy limit (todo 119).
- **Loading.** BenchBase creates and loads its tables. They are then imported into ScalarDB and Setup
  runs, before `--execute`.
- **Targets.** Against run (a), throughput and p95 latency within **10% for simple statements**
  (YCSB, and TPC-C's transactions) and **20% for complex ones** (TPC-H), for runs (b), (c) and (d).
  TPC-C is measured per transaction type and TPC-H per query, not as a geometric mean, which would
  hide one query at 10x. Run (e) is reported without a target: with rows in flight, waiting and
  `40001` are what design section 6 prescribes. These targets are well below what the
  per-statement benchmark measured locally (`read-overhead.md`: one-table shapes 1.0-1.7x, joins of
  two tables 0.4-2.6x). A workload adds network and commit time to every statement, so its overhead
  is lower, but a miss is likely, and then T6 becomes optimisation work, one todo per shape.
- **Runs, per workload and engine.** (a) the vendor URL; (b) the driver with every table plain, which
  is the cost of routing; (c) the driver with every table on ScalarDB; (d) half the tables on
  ScalarDB; (e) (c) with ScalarDB's own load on the same tables, so rows are in flight. For each run:
  throughput, p50/p95/p99 latency, abort rate. In (e) also ScalarDB's own throughput against a run
  without the driver: the driver's locks must not starve ScalarDB.
- **Where.** Local containers for smoke runs only; SQL Server runs under x86 emulation there. The
  numbers come from a cloud environment (see "Cost"), one database per engine, and go to
  `docs/benchmarks/` with their configuration, as `read-overhead.md` does.
- **Publishing.** The licences of Oracle and SQL Server restrict publishing benchmark results without
  the vendor's permission. Their numbers stay internal until that is checked.

## Cost

The budget is not fixed; nothing is provisioned before its cost is agreed. A rough estimate, from
memory of AWS on-demand prices, to be replaced by a quote at current prices before provisioning:

| Item | Assumption | Estimate |
|---|---|---|
| PostgreSQL, MySQL, MariaDB, SQL Server | per engine a database VM (8 vCPU, 32 GB, gp3 SSD) and a client VM (8 vCPU) for BenchBase and ScalarDB's load, self-managed; SQL Server Developer edition | about $0.75 per hour per engine |
| Oracle | RDS for Oracle, Standard Edition 2, licence included, plus a client VM; Oracle Free's limits (2 CPUs, 2 GB) make its numbers meaningless | about $1.5-2.5 per hour |
| Hours | T6: loading, five runs of three workloads, three repetitions, setup and failed runs, about 40 hours per engine; T5's long runs, about 24 hours per engine on the same VMs | about 64 hours per engine |
| Total | the five engines, storage and transfer, once | about $400-800 |

VMs are stopped between runs. A second round after optimisation work costs about the same again.

## Decisions (2026-10-20)

1. **No ORMs in T4.** PostgreSQL's regression tests, sqllogictest for the other engines.
2. **Overhead targets:** 10% for simple statements, 20% for complex ones (T6, "Targets").
3. **A cloud environment** for T5's long runs and T6, budget not fixed, the cost agreed in advance
   ("Cost").
4. **Engine order:** PostgreSQL, Oracle, MySQL, MariaDB, SQL Server.

Open: which cloud and account.



## Ownership

This plan is run by its own agent, beside the work on milestone 9 of `proxy-step.md`. It owns this plan,
the test fixtures (`Engines`, `Rows`), the integration test wiring in `driver/build.gradle.kts` and
`build-logic`, `ci.yml`, and the suites and tools it adds. A failure the driver causes is filed as a todo,
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
