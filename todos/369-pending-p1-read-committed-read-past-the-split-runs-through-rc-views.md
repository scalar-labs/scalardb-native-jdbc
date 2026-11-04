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

## Review record (2026-11-04, 1a6db05 on `rc-gated-past-split`, built by session 28 on 0835b18)

Verdict: mergeable. Read from `git show`: `Router`, `Proxies`, both tests, the docs sentence and
the changelog.

- `Router.gated` takes `pastSplit`: the split route passes false (on PostgreSQL the route returns
  as before; Oracle keeps its ranged gates there), the `rc_T` route true: on Oracle `ast.gated`
  with `ranged = !snapshot && !pastSplit`, whole-table at READ COMMITTED; on PostgreSQL
  `lateralGated` where `!snapshot`. A `*` over an inline table keeps `rc_T` on both engines;
  `ordersPastSelectList`, a locking clause and an empty table set decline as the gate does.
- `lateralGated`: each gate `CASE WHEN EXISTS (<inFlightCheck(t)>) THEN 1 ELSE 0 END AS
  "scalardb_native_gN"`, the coarse check's ordered LIMIT 1 probe under EXISTS, the metadata
  table or, inline, the table itself; the inner text `joinTokens` with the data tables, as
  `withPlain` renames, on lines of its own so a line comment closes nothing, a trailing `;`
  dropped (BenchBase's Q5 ends with one: a syntax error inside the derived table, which in
  autocommit had sent the fallback through `rc_T` to its timeout); the placeholders in source
  order (`placeholdersIn` over the whole text). Column order `SELECT s.*, g.*` with the marker as
  `s`'s last column, so `GatedRows` hides the trailing `gates + 1` columns unchanged. Right; the
  design's `g.*` first was only a spelling, amended to the build.
- `Proxies.gated`: the first row's gates are read first (any 1 returns null for the fallback), then
  the marker (`rs.getObject(columns - gates)` NULL means the empty result), so a gate of 1 on an
  empty result still takes the fallback, which can return before images; right. In the catch, on
  PostgreSQL in a transaction (`aborted`) the error is rethrown as itself, since the transaction is
  aborted and a fallback would surface as 25P02; in autocommit and on Oracle the fallback as before.
- `ReadCommittedPastTheSplitIntegrationTest` (PostgreSQL, layouts d and i, in a transaction and in
  autocommit, the fixture's READ COMMITTED asserted): a six-way key join as written by its own
  `current_query()` (`LEFT JOIN LATERAL`, g0 and g5, no `rc_`), three application columns only;
  `t1.id > 100` empty (one row of NULLs without the marker check); a prepared update in flight on
  id 2 gives the `rc_` text and the committed rows, no abort; `1 / (t1.v - t1.v)` gives 22012 in
  both modes; `t1.*` over the inline table gives `rc_`. `OracleOltpIntegrationTest.
  readCommittedReadsPastTheSplitRunBehindWholeTableGates` (`acr{i,d}`, TTL 59370, a five-way join
  on x, both modes): one text per tag with g0..g4 and no `RC_`. Each fails without its part per 28.
- Suites as reported: unit 417, PostgreSQL 192 green; Oracle green except the ORA-12516 pair that
  pass rerun alone; `OracleOltpIntegrationTest` 36 green.
- `transactions.md`: "or in the whole table for a statement too large to rewrite into copies",
  as asked. Changelog `improvement-20261104-120000`: accurate.

Measured by 28 (local TPC-H at READ COMMITTED, SF 0.1, `tpchCheck` 3): Q2, Q7, Q8, Q9, Q11, Q13,
Q21 and Q22 at 0.6-1.7x from timeouts or 35-1,000x, both layouts and modes; Q21's shared-memory
failure gone; Q5 after the semicolon fix 20-45 ms against the vendor's 21-84 (a direct run);
every completed query the vendor's rows. OLTP regression check at READ COMMITTED, TPC-C scale 1
and YCSB, both layouts: no statement takes the form (0 `LEFT JOIN LATERAL`, 0 `rc_` among the
64/78 and 54/55 server statements). Q6 and Q12 decoupled showed 6.5x and 12.8x this time against
0.9x and 1.6x before; Q6's text is the split read, untouched by this change, read as the
laptop's load; to see in a quiet run.

## Open after this item (READ COMMITTED, within the split; the user's call)

- Q18 at 33-36x and Q20 at 92-118x: their split copies carry a subquery's table through `rc_T`
  (an aggregate subquery's, 4.2's rule) and the reference is repeated in every copy (todo 095).
  Candidate: a statement whose split would read any reference through `rc_T` takes the gated
  form of this item instead, its whole-table gates over every table; the gate open, the vendor's
  cost; closed, today's split.
- Q1 at 3.6-5.3x: a whole-table aggregate of lineitem, two pieces that scan it twice. Candidate: a
  whole-table gate for a scan-shaped anchor-less read (no key conjunct on its table), which 4.2
  declines today for the OLTP case where the gate is closed more often than open; an adaptive
  skip (a table whose gate closed within the last second keeps the split for that second) would
  bound the loss there. Measured items both.

### 2026-11-04: on main (9b63126)

Landed by 28 on the user's "land it" typed in 28's session, rebased onto 05bc303; my range-diff of
0835b18..1a6db05 against 05bc303..9b63126 shows the commit identical. Nothing pushed. Left: the
push's CI for the other engines; the two candidates above, the user's call.

### 2026-11-04: the proof, 6e's PostgreSQL READ COMMITTED SF 3 rerun from 87c0b2b (20261104-2248-pg, decoupled cell, relayed by 28)

BenchBase (c)/(a) 0.97 (0.10 from the build with todo 369 alone). The check in a transaction:
every query 0.98-1.03x but Q1 at 1.16x; Q20 1.00x (was 765x), Q19 1.01x, Q5 1.01x, Q10 1.00x. In
autocommit 0.99-1.05x, Q1 1.10x. Every execution the vendor's rows. The inline cell runs next; the
reports are fetched with it (`a/results/bb-rc-d0-benchbase-postgresql.md` on the client). Q1's
1.10-1.16x is the one residual above noise: its gated statement is the whole-table lateral form
over lineitem's 18M rows; an EXPLAIN ANALYZE of the plain and the wrapped text at that scale is
the next look if it holds in the inline cell.

## Acceptance Criteria

- [x] A READ COMMITTED read past the split runs as written behind whole-table gates on PostgreSQL
      and Oracle, in a transaction and in autocommit, with the tests above (1a6db05, reviewed
      2026-11-04)
- [ ] Full check passes on all engines
- [x] 28's READ COMMITTED TPC-H step repeated from the build: Q5, Q7, Q8, Q9 and Q21 complete at
      about the vendor's cost (0.6-1.7x, 2026-11-04)

## Work Log

- 2026-11-04: filed from session 28's READ COMMITTED TPC-H step; the design written into 4.2 as a
  paragraph after the gated form's. Awaiting the user's word on who builds it.
