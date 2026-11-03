---
status: pending
priority: p1
issue_id: "367"
tags: [design, postgresql, oracle, autocommit, snapshot, performance]
dependencies: []
---

# An autocommit read past the split runs through `rc_T` though its own transaction found every table clear

## Problem Statement

At SNAPSHOT on PostgreSQL and Oracle a transaction that found every ScalarDB table of a read clear
runs the statement as written over the data tables (design 4.2 "A table found clear", todo 329).
In autocommit a checked read, the `rc_T` fallback of a shape the split does not cover among them,
already runs its coarse checks and the read in a transaction of its own (todo 283), one snapshot for
both, but never takes the plain statement: the plain switch in `Proxies` (around line 1155) asks
`marks`, which is "in the application's transaction at the execute" and false in autocommit, so the
rewritten text runs, every ScalarDB table of it through `rc_T`, a UNION ALL view into which no
engine pushes a join predicate (section 3). A 6-table TPC-H query in autocommit is six `rc_T` views
in one statement.

Verified from the code (2026-11-03): `Router.withPlain` gives the fallback route its `plainSql` and
`clearedBy` in both anchoring modes; `Proxies` opens the own transaction for a checked read in
autocommit, `Router.check` adds each clear table to `passed`, so `allPassed(clearedBy)` holds
inside the own transaction; only `marks` withholds the switch; `endOwn` then clears `passed`.

## Findings (session 28, 2026-11-03)

Local bb-pgss (PostgreSQL 16), BenchBase TPC-H at scale factor 0.1, decoupled layout, main at
8c25ffc. BenchBase's own runs: all 21 queries (Q15 off) complete, the driver within 0.7-1.3x of
the vendor per query; a row check with BenchBase's `Qn.getStatement` and the same seed, inside a
REPEATABLE READ transaction: all 21 return the vendor's rows at 1.0-1.4x cold (Q22 3.5x, Q19 2.2x).

In autocommit: Q5, Q7, Q8 and Q9 (6-8 tables) hit a 30 s timeout against 20-60 ms on the vendor;
Q2 188x, Q11 276x, Q22 312x, Q20 99x, Q18 25x, Q16 13x; Q21 runs the container out of `/dev/shm`.
`pg_stat_statements` shows why for Q5: in a transaction it runs as written over `"bb"."customer"`,
...; in autocommit every table becomes `"bb"."rc_customer"`, ..., six `rc_` views in one
statement. BenchBase never shows it because its workers run in transactions; BI tools and
psql-style clients run in autocommit.

Inline layout (28, the same day, main at 8c25ffc): autocommit is the same cliff, Q5, Q7, Q8 and Q9
time out, Q2 178x, Q11 196x, Q22 326x, Q21 out of `/dev/shm`; in a transaction 20 of 21 queries
within 0.79-1.28x, and Q9's own case in a transaction is todo 368's (the whole-row rule).

## Design (4.2, "The autocommit checked read as written", 2026-11-03)

The own transaction takes the plain statement once its checks passed, as a transaction does. The
argument is the transaction's: the own transaction holds one snapshot (the session's REPEATABLE
READ on PostgreSQL; Oracle's SERIALIZABLE, set for it by todo 283), the checks and the read share
it, and checks that find every table clear vouch for the statement as written over the data
tables exactly as in the application's transaction.

Build, in `Proxies`:

- the plain switch admits the own transaction beside a marking one: `marks || own`;
- the own transaction's whole fetch (fetch size 0, since its end closes a cursor) goes on the
  statement that runs, the one switched to, not only the one the proxy held before the switch;
  the restore at `endOwn` likewise;
- a prepared read's second vendor statement (todo 329's `switchTo`) is switched back at the next
  execute whose checks find a table in flight, which keeps the rewritten text, exact as before;
  `takesSql` replaces the text in place as in a transaction.

Unchanged: the own transaction's level; the Oracle gated form, which runs first and leaves the own
transaction the closed gate's path; a table found in flight, whose execute keeps the rewritten
text (its predicate check and `rc_T`); an inline table read whole, which keeps it too
(`withPlain` has no plain text for it); the cost in round trips, which the own transaction pays
already (the checks' and the commit's).

Expected: 28's in-transaction figures in autocommit, 1.0-1.4x cold per query, in place of the
timeouts.

What must keep holding: `SnapshotIntegrationTest.scrollableInAutocommit` (todo 283) and the
found-clear tests of todo 329. To add: an autocommit read past the split (a CTE or a 5-table
join over a decoupled table) on PostgreSQL and Oracle that runs as written, by the text the
vendor received, and returns the committed rows; the same read with a row in flight, which
returns the committed rows through `rc_T`; a prepared one executed across both states.

## Not taken

- The gate column form on PostgreSQL for autocommit and read-only anchor-less reads (28's first
  candidate): it would save the own transaction's two round trips, nothing of this cliff, which
  is the view's; a separate item if an autocommit OLTP read ever needs it.
- The READ COMMITTED autocommit read past the split, which has no checks and no own transaction
  and keeps running through `rc_T`. One statement in autocommit has no cross-statement meaning,
  so its own transaction could run at the snapshot level on PostgreSQL as it does on Oracle and
  take this form. A second step, the user's call.
- Replacing only the tables found clear, the rest through `rc_T`, where a table is in flight: the
  unmeasured territory of todo 095's "first tables split and the rest through `rc_T`".

## Oracle note (2026-11-03, session 28's container probe on f8f589c's build)

A five-way self join of a 10-row table on its key, `WHERE t1.k <= 3`, named select list, texts
from `v$sql`: inline in autocommit five CASE derived tables without anchors, inline in a
transaction the same with v0/v1 and x0..x14, decoupled in autocommit `RC_ACSD` x5 with no gate,
decoupled in a transaction five metadata-join CASE copies with x0..x14. Every one is the READ
COMMITTED route's form (todo 364's CASE copies, anchored in a transaction; the anchor-less
decoupled read past the split with no range for the joined tables), none the SNAPSHOT route's
(one state column a table, the whole-table gate, the own transaction's plain text). Nothing in
`Router` routes Oracle autocommit as READ COMMITTED (`snapshot()` is the driver's `isolation`,
default REPEATABLE READ; the session re-read runs only for a passthrough statement that sets the
level and returns on Oracle before touching it), so the probe's connection was at READ COMMITTED;
28 asked to confirm from `getTransactionIsolation()`, which on Oracle answers the driver's level.
If it answers REPEATABLE READ with these texts, that is a bug to chase with the probe's source.

At READ COMMITTED this item does not apply by design (no checks, no own transaction), the second
step above. The probe adds an Oracle angle to it: a statement past the split whose joined tables
carry no key range of their own (`t2.k = t1.k`) takes `rc_T` at READ COMMITTED on Oracle, where a
whole-table gate on those tables, one statement and one round trip, would answer it as written
and fall to `rc_T` only when closed; the range gate's reason for declining a range-less table
(the split read being cheaper than a second round trip) does not hold past the split. A
refinement for the second step, the user's call.

## Acceptance Criteria

- [ ] An autocommit read past the split at SNAPSHOT runs as written once its checks passed, on
      PostgreSQL and Oracle, with the tests above
- [ ] Full check passes on all engines
- [ ] 28's TPC-H autocommit run repeated from the build: Q5-Q9 at the in-transaction figures

## Review record (2026-11-03, 9eb01b8 on `autocommit-read-as-written`, built by session 28 on afc68f6)

Verdict: mergeable, with one Javadoc line to fix before landing. Read from `git show`.

- `Proxies`: the plain switch reads `(marks || own)` as designed. The own transaction's whole fetch
  moved from before the checks to just before the forward, on `vendor()` as it then is, the plain
  statement after the switch, and `endOwn` takes that statement and restores its fetch size on it;
  the checks run on statements of their own, so the earlier placement served nothing they needed,
  and a failure before the fetch size is set leaves `fetched` null, nothing to restore. Right.
- The switch back for a prepared read is the existing `switchTo(false, one)` branch; a failure of
  the checks leaves the target as it was (the plain statement after a first pass), which the next
  execute's checks decide again. Right.
- Test `SnapshotIntegrationTest.autocommitPastTheSplit` (PostgreSQL, both layouts, at REPEATABLE
  READ): a five-way self join of `t` in autocommit with `current_query()` in its select list, the
  text run containing the data table and no `rc_`, plain and prepared; with row 7 in flight,
  outside the read's `t1.id <= 3`, both abort with `SQLTransactionRollbackException`; the prepared
  read runs as written again after the rollback. Without the change the text is five `rc_t`.
- The design's expectation "the same read with a row in flight returns them through `rc_T`" was
  wrong for this shape: past the split the table's check aborts before any text is chosen (the
  predicate pairs check is off by default), as it did before the change. Design and this todo
  corrected in the same commit as this record. The test's Javadoc still carries the wrong sentence
  ("with a row in flight in one it runs through rc_T") against what the test asserts: fix it to
  say the read aborts, the table's check coming first.
- No Oracle test: the Oracle autocommit path takes the gated form first, unchanged, and the own
  transaction there follows a closed gate, whose row in flight the check meets too. Accepted.
- Suites as reported: unit 417, PostgreSQL and Oracle integration green; two Oracle tests failed
  once with ORA-12516 (the container out of processes, PR #11's subject) and passed rerun alone.
- Changelog `improvement-20261103-100000`: accurate.

Observation, not this item's: an autocommit read past the split aborts when any of its tables has
a row in flight anywhere, the table's coarse check, where the user docs' SNAPSHOT sentence
("checks whether the statement reaches a row in flight") describes the split shapes. The committed
view would answer such a read exactly, one statement in one snapshot. A BI query over a busy
table meets this; a follow-up if the user wants it.

Follow-up (2026-11-03): the Javadoc fixed as asked in f8f589c on the branch, a comment-only change
(three lines), spotless clean per 28. The branch at f8f589c (9eb01b8 + f8f589c) is mergeable as a
whole; landing on the user's word, the landed head to be range-diffed against it.

## Work Log

- 2026-11-03: filed from session 28's TPC-H smoke run on PostgreSQL; the cause read from `Proxies`
  and `Router` as above; the design written into 4.2 beside "A table found clear". Awaiting the
  user's word on who builds it.
