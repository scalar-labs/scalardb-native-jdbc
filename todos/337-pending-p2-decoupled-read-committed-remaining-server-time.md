---
status: pending
priority: p2
issue_id: "337"
tags: [performance, postgresql, read-committed, decoupled, design]
dependencies: ["336"]
---

# PostgreSQL, decoupled layout, READ COMMITTED: three candidates for the server time left after todo 336

## Problem Statement

After todo 336 (`faae2ed`), the local TPC-C run, decoupled READ COMMITTED (todo 336's log,
2026-10-26), spends 862 µs of server execution a commit through the driver against the vendor's
462. The statements behind most of that gap, from `pg_stat_statements` of the same run:

| statement | driver, µs a call | vendor, µs a call | gap, µs a commit |
|---|---|---|---|
| the decoupled lock's second statement, the versions read | 15.7 | none | 70 |
| the item read (ten a NewOrder) | 15.2 | 7.3 | about 36 |
| StockLevel's `COUNT(DISTINCT ...)` over order_line and stock | 1097 | 844 | about 8 |

The rest of the gap is in the writes the protocol requires (stamps, metadata rows): Delivery's
order_line write 142 µs against the vendor's 45, most of it the ten stamps.

These are an implementing session's observations (Opus), measured on the laptop only; none is
designed. Each goes to the design (Fable) for a decision before any builder, as todos 334 and 336
did. Suggested order: wait for todo 336's AWS cells first; candidate 1 also bears on the item the
design asked to watch there (the versions read rising from 5.4 to 15.7 µs as the lock's first
statement fell from 31.6 to 16.4).

## Candidates

### 1. The lock's versions read from the metadata table alone

The one-round-trip decoupled lock applies only to pure predicates on key columns
(`Router.lockingRead`, `fastRc`). Its second statement today joins the data table to the metadata
table:

```sql
SELECT d.k, m.tx_id, m.tx_version, m.tx_state,
       (m.before_tx_id IS NOT NULL OR m.before_tx_version IS NOT NULL)
FROM (SELECT x.k FROM T x WHERE <P>) d LEFT JOIN T_meta m ON m.k = d.k
```

Since `<P>` names key columns only, and `T_meta` has the same key columns, it could read
`T_meta` alone: `SELECT m.k, m.tx_id, ... FROM T_meta m WHERE <P>`, one probe per key instead of
two. What changes: a locked key whose data row has no metadata row (an imported table before its
backfill) no longer shows in the second result; `LockPlan.applyFastRcDecoupled` already falls back
to the steps for a locked key the read does not show, so the outcome would be the steps', as for
today's NULL join. To decide: whether that fallback is the wanted outcome for such a key, and
whether reading `T_meta` by the key while the data rows are locked keeps the design's argument (a
prepare that has not committed cannot change the metadata row, since it needs the data row's
lock). Expected: up to half of the 70 µs a commit; to measure on AWS beside todo 336's cells.

### 2. The item read

`SELECT I_PRICE, I_NAME, I_DATA FROM item WHERE I_ID = ?` costs 15.2 µs a call through the driver
against 7.3 for the vendor, ten a NewOrder. It reads the committed copy with a left join to
`item_tx_metadata` and its two version columns (the anchors of 4.2). TPC-C never writes `item`,
but the driver cannot know that when it plans the read. Only the measurement is here: whether a
cheaper text exists at READ COMMITTED for a key read on a decoupled table (one probe instead of the
join and the `COALESCE` state filter, as form 1 did for the lock) is the design's question.

### 3. The stock-level count

StockLevel's join over order_line pays the committed copy's filter, one `NOT EXISTS` probe of
`order_line_tx_metadata` a row, 1097 µs a call against the vendor's 844. At READ COMMITTED every
statement takes its own snapshot, so the clear-table memory of SNAPSHOT does not apply; a
one-probe check of whether the table has any row in flight at all, inside the statement (as the
`states` form of todo 329 does for its state column), might replace the per-row probe when the
table is clear. Small per commit (StockLevel is 4% of the mix), large per call.

## Decisions (the design, 2026-10-27)

Measured on the laptop's `bb-pgss` container (TPC-C, decoupled, scale 4, nothing in flight),
pgbench prepared mode, two clients, 8 s a variant, server time per statement from
`pg_stat_statements` (mean / min µs, shared blocks a call); the variants are in the design
session's scratchpad, `pgb4/`.

| statement | as built | the form decided | vendor |
|---|---|---|---|
| the lock's versions read (1) | 9 / 5, 8 blocks | 7 / 3, 3 blocks | none |
| the lock itself, for scale | 14 / 6, 5 blocks | unchanged | |
| the item read (2) | 8 / 4, 7 blocks | 8 / 4, 6 blocks (not taken) | 5 / 2, 3 blocks |
| the item read without an anchor (autocommit, read-only) | 8 / 4 | 6 / 3 by (3) | 5 / 2 |
| StockLevel's count (3), execution | 706 / 296, 800 blocks | 623 / 262, 785 blocks | 616 / 284, 776 blocks |
| StockLevel's count (3), planning, µs a plan | 1,091 | 705 | 125 |

1. **Taken: the versions read from the metadata table alone** (design 4.3, the decoupled locking
   read). `SELECT m.<key>, m.tx_id, m.tx_version, m.tx_state, (m.before_tx_id IS NOT NULL OR
   m.before_tx_version IS NOT NULL) FROM T_meta m WHERE <P>`, the predicate as written, which names
   key columns only and resolves against the metadata table's own. The argument is unchanged: a
   prepare that has not committed cannot change the metadata row while the driver holds the data
   row's lock, whichever table the read starts from. A locked key the read does not show has no
   metadata row and takes the NULL-metadata anchor of 4.4, as the join's NULL row gave it today;
   **not** the steps: the fallback in `applyFastRcDecoupled` becomes that anchor. A key shown that
   the lock did not return (a tombstone, a row inserted and committed between the two snapshots)
   keeps today's rule: in flight with a before image it aborts, else nothing. Gain: 2 µs a lock
   warm, about 20 µs a NewOrder; the benchmark's state inflates every statement alike, so expect
   the same share of the 15.7 there.
2. **Not taken: the item read.** The gap is the metadata row's probe, which the anchor needs; the
   in-flight arm costs a probe of an empty partial index, nothing. A one-copy form, `T LEFT JOIN
   T_meta` with a `CASE` per non-key column choosing the before image for a row in flight, measured
   the same 8 / 4 and would hide the non-key columns behind expressions, which section 3 rejected
   for the view. So no cheaper anchored key read exists at this level; recorded in design 4.2. The
   same one-copy form on StockLevel's two tables (no anchor there) measured 1,785 / 936 with 2,284
   blocks: a probe of the metadata row for every row the range reads. Reads that take no anchor get
   candidate 3's gate instead, 8 to 6 µs on a key read.
3. **Taken: the per-row in-flight probe behind one probe of the index** (design 4.2, "How a
   committed table excludes its rows in flight"). On PostgreSQL a committed copy that probes the
   in-flight index per row, a decoupled table without own versions, takes `NOT EXISTS (SELECT 1
   FROM T_meta WHERE tx_state IN (1, 2)) OR NOT EXISTS (SELECT 1 FROM T_meta q WHERE q.<pk> =
   t.<pk> AND q.tx_state IN (1, 2))`. The planner reads the first once (InitPlan) and makes the
   second a hashed set of the keys in flight, never built while the first finds nothing (EXPLAIN:
   `(NOT $0) OR (NOT (hashed SubPlan n))`, the SubPlan "never executed"); past `work_mem` it keeps
   the EXISTS as the probe per row, by its rule for EXISTS it can hash: the builder confirms both
   plans. Spelled as EXISTS, not as `NOT IN` over the key list, for that fallback; the two measured
   the same (623 against 623). Not the inline layout, whose copy reads the row's own state; not
   Oracle, whose read side is todo 334's session's. In a read of several tables each copy carries
   its own InitPlan, one block each.

The builder's order, as the implementing session suggested: after todo 336's AWS cells. Tasks: (1)
in `Router.lockingRead`'s versions statement and `applyFastRcDecoupled`'s missing-key branch, with
a test locking a key that has no metadata row (an imported table after the setup, a native insert)
and one with a tombstone under the predicate; (3) in `SplitRead.committed` for `Check.NOT_EXISTS`
on PostgreSQL, the existing tests of rows in flight covering the set, plus an EXPLAIN check of the
two plans in the log. Then the local TPC-C run at READ COMMITTED, decoupled, and the AWS cell.

## Log

### 2026-10-27: written up (Opus), sent to the design session

From todo 336's local run (`faae2ed`, the after tree), at the user's request.

### 2026-10-27: decided (the design session)

As above. The item read closed; the versions read and the gated probe to build after the AWS
cells.

### 2026-10-27: built (Opus), at the user's request before the AWS cells

**(1) The lock's versions read from the metadata table alone.** `Router.lockingRead`'s decoupled
READ COMMITTED versions statement reads `T_meta` under the name the statement exposes, its
predicate as written:

```sql
SELECT imp."id", imp."tx_id", imp."tx_version", imp."tx_state",
       (imp."before_tx_id" IS NOT NULL OR imp."before_tx_version" IS NOT NULL)
FROM "rcd"."imp_tx_metadata" imp WHERE id = ?
```

`LockPlan.applyFastRcDecoupled` gives a locked key the read does not show the NULL-metadata anchor
(`Anchors.Version(null, null)`) in place of the steps. Tests,
`DecoupledReadCommittedIntegrationTest.todo337_theLocksVersionsReadFromTheMetadataTableAlone`: the
text; a key the application inserted after the setup, locked, its anchor equal to NULLs, its write
after the lock 1 and its metadata row then at version 1, state 3; a tombstone under the predicate
(an imported table's DELETE through the driver keeps the metadata row), the lock returning the
other key alone and its write going through.

**(3) The per-row in-flight probe behind one probe of the index.** `SplitRead.committed`, for
`Check.NOT_EXISTS` on PostgreSQL and a decoupled table: `WHERE NOT EXISTS (SELECT 1 FROM T_meta
WHERE tx_state IN (1, 2)) OR NOT EXISTS (<the per-row probe as before>)`. The plans,
`todo337_thePerRowProbeBehindOneProbeOfTheIndex` on a 5,000-row imported table:

```
-- nothing in flight, EXPLAIN ANALYZE
Bitmap Heap Scan on big t  Filter: ((NOT $0) OR (NOT (hashed SubPlan 3)))
  InitPlan 1 (returns $0) -> Seq Scan on big_tx_metadata (rows=0)
  SubPlan 3 -> Bitmap Heap Scan on big_tx_metadata q (never executed)
-- 3,000 in flight, work_mem 64kB: still (NOT $0) OR (NOT (hashed SubPlan 3)), the set fits
```

and on a throwaway PostgreSQL 16, a 50,000-row table:

```
-- nothing in flight
Index Only Scan using p337_pkey on p337 t  Filter: ((NOT $0) OR (NOT (hashed SubPlan 3)))
  InitPlan 1 -> Index Only Scan using p337_if on p337_m
-- 30,000 in flight, work_mem 64kB: the per-row probe
Index Only Scan using p337_pkey on p337 t  Filter: ((NOT $0) OR (NOT (SubPlan 2)))
  SubPlan 2 -> Index Only Scan using p337_if on p337_m q  Index Cond: (id = t.id)
-- the old text there: Hash Anti Join over a Seq Scan of p337_m
```

So both plans of the design hold: the set never built while the table is clear, the probe per row
by key once the set passes work_mem. One observation: on the 5,000-row test table the InitPlan
reads `big_tx_metadata` by a sequential scan (5,000 rows filtered, 0.28 ms), not by the partial
in-flight index, which the planner takes on the 50,000-row table; a small table's cost, to watch in
the benchmark's `server.txt`. The test asserts the gate and the count with rows in flight.

Gates: unit tests 397; the PostgreSQL suite 139 tests, 88 run, all passing; SpotBugs main clean,
the integration tests' remaining findings the benchmark classes'. The other engines' suites and the
local measurement next.

### 2026-10-27: todo 337 measured locally, decoupled READ COMMITTED (Opus)

TPC-C on `bb-pgss`, 4 terminals, 60 s, scale 4, decoupled, READ COMMITTED; `cb16d83^` (todo 336
built) and `cb16d83` exported and run back to back, after first, once no other container was busy:

| | before (todo 336) | after (todo 337) |
|---|---|---|
| (c) vs (a) | 0.85 (583.7/s against 685.2) | 0.88 (591.3/s against 674.0) |
| server CPU ms/commit, run (c) | 2.10 | 2.06 |
| active ms/commit | 1.39 | 1.30 |
| server exec ms in all | 29866 | 29385 |
| the stock lock's versions read, µs a call | 14.8 | 12.0 |
| the district lock's versions read | 8.4 | 8.9 |
| StockLevel's count | 1051 | 980 |
| the item read (unchanged by design) | 14.8 | 15.0 |

The two changed statements move as the design measured with pgbench (the versions read about 3 µs
a call, StockLevel 7%); the throughput difference, 3 points, is inside the laptop's run-to-run
noise (the same todo 336 tree measured 0.80 a day earlier). The InitPlan's sequential scan seen on
the small test table does not show here: TPC-C's metadata tables are large enough for the partial
index (checked: `EXPLAIN` of the probe on `bb-pgss` gives `Index Only Scan using
order_line_inflight on order_line_tx_metadata`). The AWS cells decide, with todo 336's.

### 2026-10-27: cb16d83 reviewed (the design); one follow-up, the probe's spelling and the setup's ANALYZE

The three changes match the design: the versions read under the exposed name with the predicate as
written, a missing key the NULL-metadata anchor, the gate on PostgreSQL's decoupled copies only;
the tests cover what was asked.

The follow-up comes from the note that the 5,000-row test table's InitPlan was a sequential scan.
On PostgreSQL the whole-table probe's plan depends on the metadata table's statistics. Measured on
a 200,000-row metadata table with its partial index and nothing in flight: unanalyzed, as a table
is from the setup's backfill until autoanalyze reaches it, `NOT EXISTS (SELECT 1 FROM T_meta WHERE
tx_state IN (1, 2))` and the coarse check's `... LIMIT 1` are planned as a sequential scan, 13 ms a
probe; after `ANALYZE`, the index, 0.05 ms; after a burst of 30% in flight was analyzed and then
cleared, stale until the next autoanalyze, the sequential scan again, 23 ms. The READ COMMITTED
gate pays that per statement and copy, the SNAPSHOT state and coarse check per transaction, the
write gates per write. Spelled as the first row in the index's order, `SELECT 1 FROM T_meta WHERE
tx_state IN (1, 2) ORDER BY <key> LIMIT 1`, the partial index is taken under every one of those
statistics (0.02-0.05 ms; 3 ms with the burst's dead entries until vacuum), and inside the gate and
the state the plan stays the InitPlan and the hashed SubPlan "never executed".

Decided (design section 3 and 4.2): every whole-table probe of the in-flight index on PostgreSQL
takes that spelling, alone as the coarse check, `(...) IS NULL` as a gate and in the state's CASE;
and the setup on PostgreSQL runs `ANALYZE T_meta` after the backfill, as it gathers the histogram
on MySQL and Oracle. Tasks for the builder: `Writes.inFlightCheck`, `Writes.clearGate`,
`Router.versions` (the states form), `SplitRead.committed` (the gate), the Oracle branches
untouched; `Setup.gatherHistogram`'s PostgreSQL branch, dry run included; tests: each probe's
EXPLAIN on an unanalyzed metadata table shows the partial index (the 5,000-row test table's
InitPlan goes from the sequential scan to the index), and the setup's plan lists the ANALYZE.

### 2026-10-27: the other engines' suites (Opus)

MySQL, MariaDB, SQL Server and Oracle from an export of HEAD (`2301048` and before it `cb16d83`),
Testcontainers: 304 tests, 208 run, all passing (40 min); the changes are PostgreSQL's alone, as
built.

### 2026-10-27: the eleventh AWS run, from faae2ed (Opus)

Run 20261026-2334 (3c13fa8, by the AWS session), decoupled TPC-C at READ COMMITTED, against the
tenth run: the stock lock 24.6 → 10.0 µs and its versions read 6.2 → 12.4 µs, so the pair is
30.8 → 22.4 µs. The second statement grew by less than the first shrank, so it needs no
send-back. The versions read is this todo's target: cb16d83 reads it from T_meta alone (locally
14.8 → 12.0 µs), and its AWS cell waits for the user's approval through the AWS session.

### 2026-10-27: the eleventh run's lock pair read against the tenth (the design)

The versions read's text is the same in both runs: `816f506` (tenth) and `faae2ed` (eleventh)
build it alike, the data table's predicate in a derived table left-joined to the metadata table
(`git diff 816f506 faae2ed -- Router.java`: the hunk changes the lock from the version-looking-up
form to the keys-only form and leaves the versions statement as it was). So 6.2 → 12.4 µs is not
the statement growing: the metadata row's first touch moved out of the lock, whose correlated
subqueries into `stock_tx_metadata` read it in the tenth run (24.6 µs), into the second statement,
which read it warm there (6.2) and cold here (12.4). The pair is what counts, 30.8 → 22.4 µs,
against SNAPSHOT's lock alone at 10. `cb16d83` drops the second statement's probe of the data table,
the part the lock has just warmed, so its cell should show the second statement near 9–10 µs and
the pair near 20; the 10 µs that stays above SNAPSHOT is the anchor's metadata probe (the second
decision above: no cheaper anchored key read at this level), about 45 µs a commit over 4.5 calls.

What remains between READ COMMITTED's 790 µs of statement time a commit and SNAPSHOT decoupled's
613 (the ninth run's figure) is 177 µs. The write-up's items account for about half: the lock pair
101 against 45 a commit (about 90 with `cb16d83`), Delivery's `order_line` write 168 against 143 a
call (about 10 a commit), the merged stock batch 218 a call against SNAPSHOT's 207 (closed), the
new-order read 63 → 28. About 100 µs a commit is not in the write-up: the keyed writes (district,
customer, warehouse), which the tenth run put at about 125 a commit against the vendor's statements
at SNAPSHOT and todo 336 moved onto the clear-table statement with the CAS in its stamp, and
StockLevel (77 against 40 a commit in the tenth; `cb16d83`'s gate). Server CPU a commit is 0.36 ms
above SNAPSHOT where statement time is 0.18, so part of the excess lies outside the statements'
execution: planning, or the second message of each lock pair. Asked the AWS session for the keyed
writes' and StockLevel's per-commit figures and the repetition's planning time at both levels;
whether a design item beyond this todo exists waits on them.

### 2026-10-27: the three figures; nothing of this todo's size is left at READ COMMITTED (the design)

From the AWS session, run 20261026-2334 fast, repetition 2 of each cell, READ COMMITTED against
SNAPSHOT on the decoupled layout, per commit over each cell's median commits:

- The keyed writes (district, customer, warehouse), the keys-CTE form at READ COMMITTED against
  the clear-table form at SNAPSHOT: 88.0 against 81.9 µs a commit, +6 (the tenth run's write-up
  had put them about 125 apart). Closed; the form stays as todo 336 decided it for anchored
  tables. The oorder and new_order keyed writes add 14.5 and 12.1 a commit at READ COMMITTED.
- StockLevel, the derived-table form: 1,093 µs executing and 776 planning a call against
  SNAPSHOT's 974 and 117 on the vendor's text (the vendor 819 and 116); 45.1 + 31.8 against 38.4 +
  4.6 a commit. Both levels plan on nearly every call (5,530 plans in 6,593 calls: the plancache
  keeps custom plans for the ranged predicate); the split form's plan costs 7× the vendor's.
  `cb16d83`'s gate takes the execution to the vendor's (706 → 623 against 616 locally) and the plan
  from 1,091 to 705 µs against the vendor's 125 (the table above): the four copies of a two-table
  split read are still planned, their probes merely not executed. About 15 µs a commit of planning
  stay above SNAPSHOT after the gate. A generic plan would remove them, but forcing one is a session
  setting that reaches every statement; not taken at this size.
- Planning a repetition: the vendor 1.21 s (5.4 µs a commit), SNAPSHOT 2.80 (15.6), READ COMMITTED
  7.13 (44.6), of which StockLevel 5.11 s; the keys-CTE statements plan alike at both levels (1.20
  against 1.29 s). So READ COMMITTED's extra planning, 29 µs a commit, is StockLevel's, and about
  half of it goes with `cb16d83`.
- Execution a commit: READ COMMITTED 745, SNAPSHOT 630, the vendor 360–368 (the 790 of the entry
  above was execution with planning).

The ledger after `cb16d83`, against SNAPSHOT: about 100 µs of execution a commit, of which the
lock pair's anchor probe 45 (accepted, the second decision), Delivery's `order_line` write 10, the
new-order read 7, the keyed writes 6, the batch 5, the rest in single digits across the remaining
statements; about 15 of planning (StockLevel); and, outside `pg_stat_statements`, the lock pair's
second message, 4.5 a commit, where server CPU a commit exceeds statement time by 0.2 ms. Folding
the versions read into the lock's statement would need a read that takes its snapshot after the
lock inside one statement, which only a VOLATILE function evaluated above a locking subquery gives
(`SELECT l.*, f(l.key) FROM (... FOR UPDATE) l`): a function the setup would create, a per-row call
dearer than the message it saves on a one-row lock, and a PL/pgSQL body since an inlined SQL body
would take the statement's snapshot again; not taken. Nothing of todo 336's or this todo's size is
left at READ COMMITTED on the decoupled layout: its cell should land near SNAPSHOT's, about 0.1–0.15
ms a transaction above it at the fast round trip. What remains to the vendor from there is the
layout's own cost, shared with SNAPSHOT (0.79 fast; execution 630 against 365 µs a commit, server
CPU 1.35 against 0.87 ms), a question for todo 329, not this one.

### 2026-10-27: the follow-up built, every whole-table probe ordered and the setup's ANALYZE (Opus)

As decided above (bc2077f), with `Writes.firstInFlight(n)`, `SELECT 1 FROM T_meta WHERE tx_state IN
(1, 2) ORDER BY <key> LIMIT 1`, on PostgreSQL:
- the coarse check: `Writes.inFlightCheck`, the probe itself;
- the write gate: `Writes.clearGate`, `(...) IS NULL`;
- the committed copy's gate: `SplitRead.committed`, `(...) IS NULL OR NOT EXISTS (<per-row>)`;
- the state's CASE at SNAPSHOT: `Router.versions`, `CASE WHEN (...) IS NULL THEN 0 WHEN ...`.
One place more than the list: `Router`'s batched coarse checks, which wrapped each check in
`EXISTS (...)`. PostgreSQL drops a subquery's ORDER BY and LIMIT under EXISTS and plans it anew,
so they are now `(<check>) IS NOT NULL`. Oracle's and the other engines' spellings are untouched.
`Setup.gatherHistogram` on PostgreSQL runs `ANALYZE <metadata table>` (`execute`, so the dry run
lists it), reported as "analyzed <table>".

Tests: `DecoupledReadCommittedIntegrationTest.todo337_everyProbeOfTheIndexTakesItUnanalyzed`
copies big's metadata table into a never-analyzed table (200,000 rows, nothing in flight,
autovacuum off). It plans each text the driver sends against the copy as a generic plan: the
coarse check, the READ COMMITTED read's gate, the SNAPSHOT read's state and the SNAPSHOT write's
gate. Each takes `Index Only Scan using unanalyzed_id_idx`, with no Seq Scan of the copy. The same
probe without the order is a `Seq Scan on unanalyzed`, so the check is not vacuous. The setup's
plan lists the ANALYZE. In `todo337_thePerRowProbeBehindOneProbeOfTheIndex` the gate's filter is
now `($0 IS NULL) OR (NOT (SubPlan 2))`, with the InitPlan a Limit over the in-flight index, and
the per-row probe still "never executed" with nothing in flight. Two unit tests' gate texts were
updated. Unit tests 397, the PostgreSQL suite 142 (89 run), all passing; SpotBugs clean.

Open, for the design: one whole-table probe of the in-flight index is not in the list, Router's
plan-choosing count `SELECT COUNT(*) FROM (SELECT 1 FROM T_meta WHERE tx_state IN (1, 2) LIMIT
301) x`. It is cached per table with a TTL rather than run per statement, but under default
statistics it could scan the table too. Left as it is.

### 2026-10-27: 62f0a59 reviewed (the design); the plan-choosing count takes the spelling too

Read from the commit. `Writes.firstInFlight(n)` is the decided text, `SELECT 1 FROM T_meta WHERE
tx_state IN (1, 2) ORDER BY <key columns> LIMIT 1`, and it stands where bc2077f put it: the
coarse check (`inFlightCheck`) as itself, the clear gate and the committed copy's gate as `(...)
IS NULL`, the states CASE as `CASE WHEN (...) IS NULL THEN 0 ...`; the other engines' texts are
untouched; the setup's PostgreSQL branch runs `ANALYZE <meta>` through `execute`, so the dry run
lists it. The batched coarse checks, which wrapped each check in `EXISTS (...)`, are `(<check>)
IS NOT NULL` on PostgreSQL: right, since the planner drops a subquery's ORDER BY and LIMIT under
EXISTS and would have undone the spelling; a scalar subquery keeps them. The tests decide what the
design asked: the generic plan of each text the driver sends, on a never-analyzed 200,000-row copy
with nothing in flight, is an Index Only Scan of the partial index with no Seq Scan, while the
same probe without the order is a Seq Scan; the earlier plan test's filter is `($0 IS NULL) OR
(NOT (SubPlan 2))` with the per-row probe never executed. Matches; nothing to send back.

The open case, the plan-choosing count `SELECT COUNT(*) FROM (SELECT 1 FROM T_meta WHERE tx_state
IN (1, 2) LIMIT 301) x`, cached per table under a TTL: it takes the spelling too, `ORDER BY <key
columns>` before the `LIMIT 301`. It is a whole-table probe of the in-flight index like the
others, and under default statistics it scans like them; that it runs once per TTL per table
makes each scan rarer, not smaller, and on a large metadata table (TPC-C's order lines) a scan is
tens of milliseconds to seconds. The order costs nothing: the partial index is in key order, and
the limit reads at most 301 entries. 4.2's rule, "every probe of the whole in-flight index on
PostgreSQL is spelled so", covers it without a change of text. For the builder, with a plan test
on the unanalyzed copy as for the four texts.
