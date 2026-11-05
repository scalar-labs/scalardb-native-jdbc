---
status: pending
priority: p1
issue_id: "373"
tags: [design, postgresql, read-committed, snapshot, performance]
dependencies: ["369", "372"]
---

# On PostgreSQL a gated or plain wide read runs without parallel workers once a split read switched them off

## Problem Statement

On PostgreSQL the driver runs a split read with `max_parallel_workers_per_gather = 0`, since the
split's inflated estimates make the planner start workers (about 10 ms) for statements that take
1 ms, and switches the setting only when the next statement needs the other one (design 4.2;
`Router.beforeRun`). A gated statement runs with the setting as the previous statement left it
(`Proxies.gated` calls `beforeRun(vendor, List.of())`), and a plain statement keeps it too unless
its own plan was found parallel by `EXPLAIN (GENERIC_PLAN)`, which fails on a parameter whose type
it cannot infer (todo 106) and then answers no. So after any split read in a session, a gated or
plain statement that is the vendor's own text, planned parallel by PostgreSQL at scale, runs
without workers. At scale factor 0.1 nothing is parallel, so nothing showed locally.

## Findings (6e's run 20261104-1521-pg, read 2026-11-04)

PostgreSQL 16, TPC-H at scale factor 3, READ COMMITTED, main at 0099545 (todo 369 in; 370, 371 and
372 not), reports in `bench-env/results/20261104-1521-pg/a/`, the driver run's statements in
`benchbase-postgresql/tpch-c-1/server.txt` inside the runs archive (texts cut at 400 characters):

- BenchBase (c)/(a) 0.10 decoupled, 0.09 inline (SNAPSHOT's run 20261104-0948-pg: 0.97, 0.86).
  Every check execution the vendor's rows; a transaction and autocommit alike.
- The slowest driver statements are the split read with a subquery's table through `rc_T` (Q20
  at 248 s a call, 765x; Q18 30 s, 3.4x) and the anchor-less split (Q1 15 s, 6.4x; Q10 7.3x; Q16
  5.5x; Q12, Q14 4.2x; Q6 3.7x; Q3, Q4 3.0x; Q19 10.9x decoupled), todos 370's and 372's shapes,
  not in this run.
- Q5 (6.5x, 5.0 s against 0.77) and Q9 (2.5x) ran as written behind the lateral gates (`SELECT
  s.*, g.* FROM (SELECT CASE WHEN EXISTS ...`), todo 369's form, and were still slower; Q8, Q21
  and Q22 1.3-2.2x; Q7, Q11, Q13, Q2 at 1.0x.
- In both driver runs the statements include `SET max_parallel_workers_per_gather = 0` (one call
  a connection) and `SHOW` of it, and no `SET` back to a positive value. In the SNAPSHOT run no
  `SET ... = 0` was issued at all: no split read ran there (the checks and the plain statement
  served every query), and the gated and plain statements kept the session's workers, 0.97-1.05x.
- So Q5's and Q9's gated statements ran with the workers off, the setting left by an earlier
  split read on the connection. Session 28 suspected a lost parallel plan under the lateral
  join; the lateral form keeps the plan (todo 369's EXPLAIN), the setting is what changed.

## Design (4.2 "Wide reads run with the session's workers", 2026-11-04)

A gated statement any gate of which is the whole table's (todo 369's and 370's, todo 372's for a
range-less table) runs with the session's own workers: `Proxies.gated` asks the router to switch
them on before it where they are off (`switchWorkers(vendor, false)`, one SET, as a split read
whose plain plan is parallel does today). A gated statement with range gates only (StockLevel's
and Delivery's shapes) keeps the setting as it is, so TPC-C's alternation of split and gated
reads costs no round trip. The plain statement of a route past the split (the `rc_T` route found
clear at SNAPSHOT) or whose gates are the whole table's switches them on the same way; the plain
statement of a route within the split keeps 4.2's rule (as it is, unless its own plan is
parallel), since at SNAPSHOT such statements alternate with the state-column split reads in
every transaction. The split read itself keeps 4.2's rule; a wide split (Q1's two pieces over
lineitem) with workers would be a separate measurement.

Why by the gate's kind: a whole-table gate marks a statement that scans or joins without a key
range, where workers decide the time at scale; a range gate marks an OLTP shape whose plan is
serial anyway. It replaces no probe: `plainPlanIsParallel` stays for the split read.

Build: `Gated` carries whether any gate is the whole table's (369's and 370's always; 372's by
`ranged`); `Router.beforeGated(vendor, wholeTable)` → `switchWorkers(vendor, false)` where
`wholeTable`; the plain path's `beforeRun` call gains the past-the-split and whole-table cases.
Tests (PostgreSQL, `ReadCommittedPastTheSplitIntegrationTest`): with the session's workers at a
positive value, a keyed split read (which switches them off, `SHOW` 0 after it), then a six-way
join past the split selecting `current_setting('max_parallel_workers_per_gather')` (the function
sends it past the split, so the gated form) returns the session's value; then StockLevel's shape
(range gates) after a split read reads 0 through the same function; at SNAPSHOT, after a split
read, a plain past-the-split read returns the session's value. The TPC-C check of todo 372 again
(no new SET in TPC-C's statements).

Expected on 6e's READ COMMITTED SF 3 run from a main with 370-373: Q5, Q9, Q8, Q21 and Q22 at
about 1x with the rest; the cloud rerun of that cell is the proof.

## Review record (2026-11-04, 51a27c9 on `rc-anchorless-gated`, built by session 28 on 372's commits)

Verdict: mergeable; with 372's two commits the branch (58ac088, 661f148, 51a27c9 on a9d81a8) is
mergeable as a whole, landing on the user's word after a rebase onto main's head. Read from `git
show 51a27c9`.

- `Router`: `Gated.wholeTable`, true for 369's and 370's lateral gates, `gates.size() >
  ranged.size()` for 372's, the retry's too; `beforeGated(vendor, wholeTable)`: PostgreSQL only
  and only with a whole-table gate, `switchWorkers(vendor, false)` under the lock with
  `beforeRun`'s exception handling (the setting is speed only). Right.
- `Proxies`: the gated statement calls `beforeGated(vendor, g.wholeTable)` in place of
  `beforeRun(vendor, List.of())`; the plain path calls `beforeGated(vendor, true)` where `plain &&
  (one.parameterSources == null || one.gated.wholeTable)`, a route past the split or one with a
  whole-table gate, and keeps `beforeRun` otherwise, so the plain statement within the split and
  the split read keep 4.2's rule. Right.
- `ReadCommittedPastTheSplitIntegrationTest.wholeTableReadsRunWithTheSessionsWorkers`: the
  setting read on the vendor connection (the handler's `vendor` field by reflection, since a
  statement through the driver on a plain table runs `beforeRun` and restores the value); at READ
  COMMITTED in a transaction a keyed split read gives 0, 369's six-way join the session's 2, a
  keyed read 0 and then StockLevel's range-only shape still 0, 372's Q1 shape 2; at SNAPSHOT a
  keyed read 0, then the six-way join (checks and the plain statement past the split) 2. With
  `beforeGated` a no-op the gated read past the split reads 0. Right.
- Changelog `bugfix-20261104-210000`: accurate.
- Suites as reported: unit 417, PostgreSQL 198, Oracle 198 green. Local TPC-H at READ COMMITTED
  (SF 0.1, `tpchCheck` 3): every query at or below 1.25x but decoupled Q11 at 1.32x, every
  execution the vendor's rows; no parallel plan at this scale, so no regression is all it shows.
  TPC-C at READ COMMITTED: no line with `max_parallel_workers_per_gather` in either layout, so no
  SET from this item; p95 against (a) StockLevel 1.03 decoupled and 1.41 inline, Delivery 1.22 and
  1.26. YCSB's two lines are the existing split reads' `SET 0` and `SHOW` a connection.
- The cloud READ COMMITTED SF 3 cell from the landed main is the proof (Q5, Q9, Q8, Q21, Q22).

### 2026-11-04: on main (87c0b2b; the branch as 3be5c21 + 7c01374 + 87c0b2b)

Landed by 28 on the user's "land it" typed in 28's session, rebased onto a22856c; my range-diff of
a9d81a8..51a27c9 against a22856c..87c0b2b shows all three commits identical. Nothing pushed. Left:
the push's CI; the cloud READ COMMITTED SF 3 cell from this main, the proof for 370-373 together.

### 2026-11-04: the proof, 6e's PostgreSQL READ COMMITTED SF 3 rerun from 87c0b2b (20261104-2248-pg, decoupled cell, relayed by 28)

BenchBase (c)/(a) 0.97 (0.10 from the build with todo 369 alone). The check in a transaction:
every query 0.98-1.03x but Q1 at 1.16x; Q20 1.00x (was 765x), Q19 1.01x, Q5 1.01x, Q10 1.00x. In
autocommit 0.99-1.05x, Q1 1.10x. Every execution the vendor's rows. The inline cell runs next; the
reports are fetched with it (`a/results/bb-rc-d0-benchbase-postgresql.md` on the client). Q1's
1.10-1.16x is the one residual above noise: its gated statement is the whole-table lateral form
over lineitem's 18M rows; an EXPLAIN ANALYZE of the plain and the wrapped text at that scale is
the next look if it holds in the inline cell.

### 2026-11-05: the proof run complete, both cells fetched and read (`bench-env/results/20261104-2248-pg/a/`)

From the reports: decoupled BenchBase (c)/(a) 0.97, inline 0.93 (0.10 and 0.09 from the build
with todo 369 alone); the check in a transaction, decoupled Q1 1.16x, Q5 1.01x, Q9 1.00x, Q20
1.00x, in autocommit Q1 1.10x, Q5 1.01x; inline Q1 1.05x and 0.99x, Q5 1.01x, Q9 1.00x, Q20 1.02x;
every row "yes" on both. BenchBase's own single runs: decoupled Q1 1.13, Q9 1.29 (one cold run,
the check's three at 1.00x), inline Q1 1.04, Q9 1.01. Q1's 1.10-1.16x is decoupled only and does
not show inline, so it is the decoupled data table's or its gate's at that scale, not the form's;
small, left as the one residual to read with an EXPLAIN ANALYZE if it matters later. The stack is
torn down; the Oracle rerun from 87c0b2b (READ COMMITTED both layouts and SNAPSHOT decoupled, for
Q16) is planned at bdcd5f3 and waits on the user's apply.

### 2026-11-05: a spotbugs follow-up, 84eef8f on `spotbugs-gated`

Session a8 found `check` failing on main in `spotbugsMain`: an unneeded
`@SuppressFBWarnings(SQL_INJECTION_JDBC)` on `Proxies$StatementHandler.gated`'s dispatcher, left
by 87c0b2b's split of the method (the dispatcher runs no text). 28's 84eef8f (on 1dbb213) drops
the five lines of that annotation and keeps the one on the method that runs the text; spotbugs
(main, test, integrationTest), spotless and the unit tests green per 28. Reviewed from `git show`:
the diff is the five deleted lines, nothing else. Mergeable; landing on the user's word.

The spotbugs fix on main as 6a40dc6 (2026-11-05, the user's "land it" in 28's session; my range-diff
against 84eef8f identical); `check` passes `spotbugsMain` on main again; a8 told.

## Acceptance Criteria

- [x] A gated statement with a whole-table gate and a plain statement past the split run with the
      session's workers on PostgreSQL, range-gated ones unchanged, with the tests above (51a27c9,
      reviewed 2026-11-04)
- [ ] Full check passes on all engines
- [x] 6e's READ COMMITTED SF 3 cell from the landed main: Q5 and Q9 at about 1x (Q5 1.01x, Q9
      within 0.98-1.03x, decoupled, 2026-11-04)

## Work Log

- 2026-11-04: read from 6e's run (relayed by 28, who suspected a lost parallel plan); the cause
  found in `Proxies.gated`'s `beforeRun(vendor, List.of())` and the SET statements of the run;
  designed into 4.2 after todo 372's paragraph. 28 builds; small enough to go on 372's branch
  before its landing, the user's call.
