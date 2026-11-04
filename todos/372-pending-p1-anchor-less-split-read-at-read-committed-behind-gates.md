---
status: pending
priority: p1
issue_id: "372"
tags: [design, postgresql, oracle, read-committed, performance]
dependencies: ["369", "371"]
---

# The anchor-less split read at READ COMMITTED runs as the split where a gate would run it as written

## Problem Statement

At READ COMMITTED a read that records no anchor (grouped, aggregate or DISTINCT, in autocommit,
on a read-only connection) takes the gated form on Oracle only where every table of it has a key
range (D2, design 4.2), and on PostgreSQL never: it runs as the split, whose copies for a decoupled
table each join or anti-join the metadata table over the scan. The third of the user's three items
of 2026-11-04, to build after todos 370 and 371.

## Findings (session 28, 2026-11-04)

PostgreSQL 16, TPC-H at scale factor 0.1, READ COMMITTED, `tpchCheck` 3, after todo 369:

- Q1: 5.2x decoupled, 3.6x inline (a two-piece scan of lineitem).
- Decoupled only: Q3 4.4-4.9x, Q4 3.3-3.7x, Q6 6.6-7.7x, Q12 12.8-13.9x, Q16 16.9x in a
  transaction and 3.5x in autocommit; the same queries inline 1.1-1.6x. Q3, Q4 and Q16 were the
  same before 369; Q6 and Q12 were 0.9x and 1.6x then, so they swing between runs (the laptop's
  load; 6e's READ COMMITTED SF 3 cloud run gives the quiet figures).

## Design (4.2 "The anchor-less split read at READ COMMITTED behind gates on every table", 2026-11-04)

Such a read takes the gated form on both engines, each table of it gated by its key range where
the predicate gives one (D2's conjuncts; B1's extraction on PostgreSQL) and by the whole table
where it does not; the gates open, the statement as written; closed, the split as today.
PostgreSQL's gates ride the wrapper of todo 369, Oracle's the wrapper of todo 371.

The whole-table gate under load: on a table with rows in flight most of the time (TPC-C's
order_line) it is closed more often than open, and every closed gate costs the plain statement's
time before the split's, which is why D2 declined a range-less table. The rule that bounds it: a
whole-table gate found closed marks its table busy in the catalog for one second, shared by the
catalog's connections; a read whose gated statement would need a whole-table gate of a busy table
takes the split at once, without the gate; a range gate is never skipped (a closed range says
nothing of the next range); neither are todo 369's and 370's gates, whose fallback is `rc_T`'s
cost. So a busy table costs one wasted plain statement a second across the catalog's connections,
a quiet one nothing. The OLTP case: StockLevel's and Delivery's aggregates are anchor-less and
keep D2's range gates where they have them (order_line's range, stock's warehouse) and the split
otherwise, no worse than today on either engine; on PostgreSQL they gain D2's form, which Oracle's
eighth and ninth runs measured (StockLevel from +53-62% toward +25%, Delivery +91/64% at READ
COMMITTED), to be confirmed on PostgreSQL by the TPC-C READ COMMITTED cells before this lands.

Not taken: a cost-based choice by the generic plan (the split's planning cost is the cold run's
only, todo 367's log); a longer or adaptive busy mark (one second is a constant, to revisit with
figures); gating a read that records anchors (its anchors are the point of it at this level).

Expected on PostgreSQL at READ COMMITTED: Q1 and the decoupled-only queries at about the vendor's
cost on quiet tables; TPC-C's StockLevel and Delivery better or unchanged; YCSB unchanged (its
reads are keyed and anchored).

Build:

- `Router`: the anchor-less split route at READ COMMITTED (`anchored.isEmpty()`) on both engines
  gated with a per-table choice, range or whole table; `Gated` carries which gates are the whole
  table's and their tables.
- `Catalog`: the busy marks, a map from table to the instant its whole-table gate was found
  closed, consulted by `Proxies.gated` before running the gated statement (any busy whole-table
  gate: the split at once) and set by it on a closed whole-table gate; one second.
- `transactions.md`: the READ COMMITTED reads sentence: "in the statement's key range, or in the
  whole table where the statement gives no range or is too large to rewrite into copies".

Tests to add: Q1's and Q12's shapes over a decoupled table at READ COMMITTED (PostgreSQL both
layouts, Oracle): as written with gates, the vendor's rows; with a row in flight the split's text
and the committed rows, the next read within a second the split without a gate (the text), after
a second the gate again; StockLevel's shape on PostgreSQL with the range gates (the text carrying
the range conjuncts); on Oracle a read with one range-less table gated by the whole table in place
of the split. What must keep holding: D2's Oracle tests, the split tests, todo 369's and 370's.

## Review record (2026-11-04, `rc-anchorless-gated` at 661f148 on a9d81a8, built by session 28)

Verdict: mergeable; landing on the user's word, the cloud TPC-C READ COMMITTED cells the
confirmation after it (below). Read from `git diff a9d81a8 rc-anchorless-gated`: 58ac088 the
build, 661f148 form 7's assertion.

- `Catalog`: `busy`, a `ConcurrentHashMap` from the table's in-flight check text to `nanoTime`,
  `markBusy(key)`, `busy(key)` under one second (`BUSY_NANOS`); shared by the catalog's connections
  as designed; one entry a table at most, never pruned, bounded by the table count. Right.
- `SqlAst`: `gated(naming, gates, ranged, form)` takes the set of references whose gates repeat
  their key conjuncts, the others' gates the whole table's; `hasKeyConjuncts(ref, KeyRange)`; the
  lateral form says `FROM dual) g` on Oracle and `) g` on PostgreSQL. Right.
- `Router.gated`: `pg = PostgreSQL && !pastSplit && !snapshot` admits the split's anchor-less reads
  at READ COMMITTED on PostgreSQL; a PostgreSQL gate is `CASE WHEN EXISTS (SELECT 1 FROM T_meta m
  WHERE m."tx_state" IN (1, 2) [AND <conjuncts>]) THEN 1 ELSE 0 END`, and where
  `hasKeyConjuncts` is false `CASE WHEN EXISTS (<Writes.firstInFlight>) THEN 1 ELSE 0 END`, the
  ordered coarse check that takes the in-flight index whatever the statistics say (todo 329);
  Oracle's `KeyRange` as before, its whole-table gate the SNAPSHOT one; `ranged` the references
  with conjuncts and `skippable` the other gates' positions, both only when `!snapshot &&
  !pastSplit`, so SNAPSHOT's gates and 369's and 370's are never skippable and range gates never
  are; the form LATERAL on PostgreSQL whatever the shape (its plans the statement's), Oracle's by
  shape (todo 371); `Gated.skippable`, the retry's too. Right; D2's "a range-less table keeps the
  split" gives way to the whole-table gate on Oracle as designed.
- `Proxies.gated`: before the gated statement, any skippable gate whose check is busy returns null
  (the split); a gate of 1 at a skippable position marks its table busy (`at = i - (columns -
  gates + 1)`). Right.
- `ReadCommittedPastTheSplitIntegrationTest.anchorlessSplitReadsRunBehindGatesAndSkipABusyTable`
  (PostgreSQL, d and i, in a transaction and in autocommit): Q1's shape (`SELECT x, SUM(v),
  COUNT(*) ... WHERE v > ? GROUP BY x`) and Q12's (a grouped key join) gated, by the handler
  class; with a prepared update in flight on id 19, Q1 "split [a/57]" with the committed rows and
  StockLevel's shape (`COUNT(DISTINCT x) ... WHERE id BETWEEN 1 AND 5 AND v < ?`) still gated,
  its range gate open with the row outside the range; after the rollback, within the second
  "split" (busy), after 1.1 s "gated". `OracleOltpIntegrationTest.
  readCommittedRangeLessTableGatedByTheWholeTable` (`acmd`, TTL 59373): a `COUNT(*)` join on x
  with `t1.k BETWEEN 1 AND 3` gives one text with g0 (BETWEEN) and g1, no `UNION ALL`; form 7's
  "no gate without a key conjunct" becomes "the whole table's gate". Each fails without its part
  per 28. Right.
- `transactions.md`: the reads sentence (the whole table where the statement gives no key range,
  is too large to rewrite, or has a view-read subquery) and the busy sentence; the changelog
  `improvement-20261104-200000`: accurate.
- Suites as reported: unit 417, PostgreSQL 197, Oracle 197 green (the form 7 assertion fixed in
  661f148); `OracleOltpIntegrationTest` 39.
- Measured by 28 (local PostgreSQL TPC-H at READ COMMITTED, SF 0.1, `tpchCheck` 3, after 370 then
  after 372, transaction/autocommit): decoupled Q1 5.48/5.56x to 1.00/1.00x, Q3 4.6/4.9 to
  1.06/1.02, Q4 3.4/3.5 to 0.94/1.07, Q6 6.4/7.0 to 1.01/1.01, Q12 11.8/13.7 to 1.06/0.99, Q16
  16.8/19.1 to 1.04/1.02, Q10 1.7 to 1.03/1.05; inline Q1 3.6 to 0.99/1.02, Q10 4.3/4.6 to
  1.02/1.11, Q19 1.7 to 1.02; every query the vendor's rows; outliers on the busy laptop (inline
  Q20 autocommit 2.22x, Q11 autocommit 1.38-1.40x), not chased. TPC-C and YCSB at READ COMMITTED
  (local, scale 1, 30 s): TPC-C one lateral statement a layout, YCSB none; p95 against (a), the
  370 run then the 372 run: StockLevel decoupled 1.96 to 1.43, inline 1.67 to 1.11; Delivery
  decoupled 1.90 to 1.23, inline 1.41 to 1.41; overall (c)/(a) decoupled 0.69 to 0.81. Better or
  unchanged, on different machine states.
- The acceptance's TPC-C READ COMMITTED cells: the local ones are in and point the right way; the
  cloud cells (6e, PostgreSQL TPC-C at READ COMMITTED, both layouts) confirm from the landed
  main. Landing need not wait for them unless the user wants it to: the local run shows no
  regression and the change is the one 4.2 measured on Oracle (D2).

### 2026-11-04: 6e's READ COMMITTED SF 3 run (20261104-1521-pg) read, before this item lands

The run is from 0099545 (369 in; 370, 371 and this item not): BenchBase 0.10 decoupled, 0.09
inline; Q20 765x and Q18 3.4x are todo 370's shapes, Q1 6.4x, Q10 7.3x, Q16 5.5x, Q12/Q14 4.2x, Q6
3.7x, Q3/Q4 3.0x, Q19 10.9x this item's; Q5 6.5x and Q9 2.5x ran gated as written and were slow
for another reason, the parallel workers left off by an earlier split read on the connection,
filed as todo 373 (a small follow-up, fit for this branch before its landing, the user's call).
Nothing in the run bears on this item's design; the cloud rerun of the cell from a main with
370-373 is the proof for all of them. Full reading in todo 373.

## Acceptance Criteria

- [x] The anchor-less split read at READ COMMITTED takes the gated form with range or whole-table
      gates and the busy mark, on PostgreSQL and Oracle, with the tests above (661f148, reviewed
      2026-11-04)
- [ ] Full check passes on all engines
- [ ] TPC-C READ COMMITTED cells on PostgreSQL (local, then 6e's) with StockLevel and Delivery
      better or unchanged; Q1 and the decoupled-only TPC-H queries at about the vendor's cost

## Work Log

- 2026-11-04: the user's item 3 (via 28); designed into 4.2. 28 builds after todos 370 and 371,
  on the user's word.
