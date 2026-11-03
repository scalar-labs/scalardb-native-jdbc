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

## Acceptance Criteria

- [ ] An autocommit read past the split at SNAPSHOT runs as written once its checks passed, on
      PostgreSQL and Oracle, with the tests above
- [ ] Full check passes on all engines
- [ ] 28's TPC-H autocommit run repeated from the build: Q5-Q9 at the in-transaction figures

## Work Log

- 2026-11-03: filed from session 28's TPC-H smoke run on PostgreSQL; the cause read from `Proxies`
  and `Router` as above; the design written into 4.2 beside "A table found clear". Awaiting the
  user's word on who builds it.
