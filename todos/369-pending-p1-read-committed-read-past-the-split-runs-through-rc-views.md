---
status: pending
priority: p1
issue_id: "369"
tags: [design, postgresql, oracle, read-committed, performance]
dependencies: []
---

# A READ COMMITTED read past the split runs through `rc_T`, in a transaction and in autocommit

## Problem Statement

At READ COMMITTED a read the split does not cover, past its limit of pieces or of a shape it does
not take, has no checks, no own transaction and nothing to remember across statements, so its
route is the `rc_T` fallback in a transaction and in autocommit alike: every ScalarDB table of the
statement through the committed view, a UNION ALL no engine joins by key (section 3). Todo 367
fixed the same cliff at SNAPSHOT in autocommit through the own transaction's plain statement; at
READ COMMITTED that remedy has no form, each statement having its own snapshot ("Why one
statement, not two", design 4.2). This is the "second step" todo 367 left, now with the
transaction case shown.

## Findings (session 28, 2026-11-04)

Local functional step the user asked for: main at 9befeab, 28's own `postgres:16` container
(port 55435, `/dev/shm` 1 GB, `statement_timeout` 120 s so a cliff cancels instead of hanging),
TPC-H at scale factor 0.1, runs a and c, `tpchCheck` 1, the driver and the vendor at
READ_COMMITTED.

- BenchBase (c): throughput 0.0, p95 120 s, both layouts; the statements cancelled by the timeout.
- The check, the same in a transaction and in autocommit, both layouts: Q5, Q8 and Q9 time out at
  120 s; Q7 412-1,004x (about 55 s against 57-132 ms); Q22 134-246x, Q2 37-121x, Q11 87-115x, Q20
  48-88x, Q18 26-31x, Q16 15-18x decoupled (1.5x inline), Q1 4-6x; Q21 fails with "could not
  resize shared memory segment ... 256 MB" under the 1 GB `/dev/shm`. Every query that completed
  returned the vendor's rows.
- Near the vendor: Q3, Q4, Q10, Q14, Q6 (0.6-2x).
- Reports in 28's scratchpad `step1/pg-rc-{decoupled,inline}.md`.

## Design (4.2 "The read past the split at READ COMMITTED behind whole-table gates", 2026-11-04)

Such a read records no anchor in either mode, so it takes the gated form of todo 334, on
PostgreSQL as on Oracle, with whole-table gates: the statement as written over the data tables
with, per ScalarDB table, `CASE WHEN EXISTS (SELECT 1 FROM T_meta WHERE tx_state IN (1, 2)) THEN
1 ELSE 0 END` on PostgreSQL (over `T` itself for an inline table), an initplan evaluated once as an
index-only probe of the in-flight index; on Oracle the existing gate without its range conjuncts.
Why whole-table: a statement past the split rarely carries a key range for every table (a joined
table's `t2.k = t1.k` is none), and the range gate's reason for declining a range-less table, the
split read being cheaper than a second round trip, does not hold where there is no split read,
only `rc_T`.

Exactness: one statement, one snapshot; a gate of 0 says no row of its table was in flight at the
snapshot, so the data table's rows are the committed images `rc_T`'s first arm returns, its second
arm empty (a prepare writes the data row and the metadata row in one database transaction,
section 1, facts 2 and 3). The first row's gates decide; any 1, or no row at all, runs the `rc_T`
text as today, exact in its own snapshot, the rows never mixed; the gate columns are hidden; an
aggregate without GROUP BY is decided by its one row.

Keeps `rc_T`: a `*` over an inline table (its metadata columns would show), a set operation at the
top (no one select list), a locking read, a scrollable or unwrapped result, an `execute` other than
`executeQuery`, a bound stream; the gated form's own rules.

Build:

- `Router.gated`: admit PostgreSQL; on both engines, a read past the split (the `rc_T` fallback
  route) takes whole-table gates (`SqlAst.gated` with `ranged` false); the Oracle rule for reads
  within the split at READ COMMITTED (range gates, a range-less table keeping the split) stays.
- The gate's PostgreSQL spelling beside Oracle's `SELECT COUNT(*) FROM dual WHERE EXISTS (...)`
  with its index hint (`SqlAst.KeyRange`'s prefix and suffix carry it).
- `Proxies.gated` is engine-neutral already (no engine test in it); unchanged.
- `transactions.md`, the READ COMMITTED reads sentence: "Where a quick check shows nothing in
  flight in the statement's key range" gains the whole table for a statement too large to rewrite.

Expected: the queries above at the vendor's cost plus a probe a table, Q21's segment failure with
them; a gate closed by a write in flight on any of its tables costs the plain statement's time
over `rc_T`'s, no worse in order than today.

Tests to add: on PostgreSQL a six-table join (past the limit of 16 pieces) over a decoupled and an
inline table at READ COMMITTED, in a transaction and in autocommit, the text run as written with
its gates (by `current_query()`) and the vendor's rows; the same with a row in flight, the `rc_T`
text and the committed rows, no abort; the no-row case; a `*` over an inline table and a set
operation keeping `rc_T`; on Oracle, the five-way key join of todo 367's probe at READ COMMITTED
taking the gated statement in place of `RC_ACSD` x5. What must keep holding: todo 334's gated-read
tests on Oracle (`OracleOltpIntegrationTest`, the range gates), `DecoupledReadCommittedIntegrationTest`.

## Not taken

- The own transaction at REPEATABLE READ for the READ COMMITTED autocommit read (todo 367's
  "second step" as first sketched): three round trips where the gate is one, and no form for a
  transaction.
- The checks before the plain statement inside a READ COMMITTED transaction: two snapshots.
- Unifying SNAPSHOT's own-transaction path (checks as a statement, then the plain text) onto the
  gate, which would save two round trips there: a later item if ever wanted; the cliff is not there.

## Open, not this item

Q1 at 4-6x within the split (a whole-table aggregate of lineitem, two pieces) and Q16 inline at
1.5x. Two candidate causes for Q1, to settle by EXPLAIN ANALYZE of the split text against the plain
one: the parallel workers switched off for a split whose generic plan the driver could not make
(`Router.plainPlanIsParallel` answers no when `EXPLAIN (GENERIC_PLAN)` fails, as on a parameter
whose type it cannot infer), or the committed copy's anti-join against the metadata table.

## Acceptance Criteria

- [ ] A READ COMMITTED read past the split runs as written behind whole-table gates on PostgreSQL
      and Oracle, in a transaction and in autocommit, with the tests above
- [ ] Full check passes on all engines
- [ ] 28's READ COMMITTED TPC-H step repeated from the build: Q5, Q7, Q8, Q9 and Q21 complete at
      about the vendor's cost

## Work Log

- 2026-11-04: filed from session 28's READ COMMITTED TPC-H step; the design written into 4.2 as a
  paragraph after the gated form's. Awaiting the user's word on who builds it.
