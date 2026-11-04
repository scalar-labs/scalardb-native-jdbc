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

28's Oracle step at SNAPSHOT (2026-11-04, Oracle 23 free via Testcontainers, SF 0.1, `tpchCheck`
3, after the harness fixes of 44e2dd7): every execution the vendor's rows, the row counts
PostgreSQL's; in a transaction decoupled 1.00-1.34x, inline 0.87-1.35x; autocommit 0.96-1.37x but
Q18 at 2.01x decoupled and 2.10x inline (66-69 against 139 ms), 1.03-1.09x in a transaction. Q18
returns no row at this scale: the gated statement runs, has no row to read its gates from, and
the own transaction runs the checks and the plain statement again (todo 367's switch), twice the
work. The earlier cold autocommit gaps (Q19 5.9 against 187 ms) were empty results from the
CHAR(n) columns plus noise and are gone. BenchBase's 0.86 in both layouts is not readable on the
busy laptop. Reports in 28's scratchpad `step1/ora2-snap-{decoupled,inline}.md`.

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

On PostgreSQL the gates ride a row of their own, not the select list (amended 2026-11-04 after
28's Oracle step): the statement as written, its tables renamed, becomes a derived table on the
nullable side of a lateral join from a one-row relation carrying the gates:

```sql
SELECT g.*, s.*
FROM (SELECT CASE WHEN EXISTS (SELECT 1 FROM T1_meta WHERE tx_state IN (1, 2)) THEN 1 ELSE 0 END AS g0,
             ... one per table ...) g
LEFT JOIN LATERAL (SELECT 1 AS scalardb_native_present, i.* FROM (<statement as written>) i) s ON true
```

A result of no rows still returns one row, the gates set and the marker NULL, where the
select-list form has no row to read its gates from and falls to a second statement: on Oracle at
SNAPSHOT the own transaction's plain statement, which is TPC-H Q18's 2.0x in autocommit at SF 0.1
(it returns no row there) against 1.03-1.09x in a transaction (28's Oracle step); at READ
COMMITTED `rc_T`, the cliff for every empty result. Measured on the container (EXPLAIN, PostgreSQL
16, bb.lineitem at SF 0.1): the wrapped Q1 keeps the plain Q1's plan, Finalize GroupAggregate over
Gather Merge with two workers, under a Subquery Scan; the gates are InitPlans evaluated once; the
join is a Nested Loop Left Join whose outer side is a one-row Result, so the inner's ORDER BY and
LIMIT hold in the output; a RIGHT JOIN form plans the same. A CTE and a set operation wrap as
derived tables too, so on PostgreSQL they take the form. Oracle keeps the select-list form with
whole-table gates and its no-row fallback; an `OUTER APPLY` wrapper from `dual` there is a measured
follow-up, if Oracle keeps the inner's order under it.

Exactness: one statement, one snapshot; a gate of 0 says no row of its table was in flight at the
snapshot, so the data table's rows are the committed images `rc_T`'s first arm returns, its second
arm empty (a prepare writes the data row and the metadata row in one database transaction,
section 1, facts 2 and 3). The first row's gates decide; any 1 runs the `rc_T` text as today, exact in
its own snapshot, the rows never mixed; on PostgreSQL a NULL marker is the empty result, on
Oracle no row at all runs the fallback as before; the gate columns and the marker are hidden; an
aggregate without GROUP BY is decided by its one row.

Keeps `rc_T`: a `*` over an inline table (its metadata columns would show), on Oracle a set
operation at the top (no one select list), a locking read, a scrollable or unwrapped result, an `execute` other than
`executeQuery`, a bound stream; the gated form's own rules.

Build:

- `Router.gated`: admit PostgreSQL; on both engines, a read past the split (the `rc_T` fallback
  route) takes whole-table gates (`SqlAst.gated` with `ranged` false); the Oracle rule for reads
  within the split at READ COMMITTED (range gates, a range-less table keeping the split) stays.
- On PostgreSQL the wrapper over the renamed text (no select-list surgery; `withPlain`'s renaming
  serves), the gates spelled as above; on Oracle `SqlAst.gated` unranged with the existing gate.
- `Proxies.gated` reads the gates and the marker at the front on PostgreSQL and hides them; the
  rest as it is (no engine test in it today).
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
