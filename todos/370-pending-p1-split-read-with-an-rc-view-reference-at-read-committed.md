---
status: pending
priority: p1
issue_id: "370"
tags: [design, postgresql, oracle, read-committed, performance]
dependencies: ["369"]
---

# A split read that reads any reference through `rc_T` at READ COMMITTED repeats the view in every copy

## Problem Statement

A statement the split serves with some reference through `rc_T` (a scalar subquery's table, a
subquery holding an aggregate or a row limit, a CTE's, a set operation's branch, a nullable side
that is itself a join; design 4.2's list) repeats that reference in every copy, through a view no
engine joins by key (todo 095). Todo 369 gave the statements past the split the gated form; these
are within the split and keep it. The first of the user's three items of 2026-11-04 (the family:
run as written with the gates riding inside the statement), to build first.

## Findings (session 28, 2026-11-04)

PostgreSQL 16, TPC-H at scale factor 0.1, READ COMMITTED, both layouts, both modes, after todo
369: Q18 33-36x and Q20 92-118x, their aggregate subqueries' lineitem read through `rc_T` inside
each of their 8 and more copies. Every completed query the vendor's rows.

## Design (4.2 "The split read with a reference through `rc_T` at READ COMMITTED", 2026-11-04)

Such a statement takes todo 369's gated form in place of the split: whole-table gates for every
ScalarDB table of it, the subqueries' included, one statement, one snapshot; the gates open, the
statement as written over the data tables; closed, the split as today. The rules and exclusions
of 369 hold (a `*` over an inline table, a locking read, a scrollable or unwrapped result, an
`execute` other than `executeQuery`, a bound stream keep today's route). The gate is always tried:
the fallback is the split with its `rc_T` references, never cheap, so no busy-table skip (todo
372's) applies here.

It records no anchor. Today its split anchors the outer references (Q20's supplier and nation)
while the subquery's table is unguarded anyway; with this item the statement joins the reads that
protect nothing (section 5's READ COMMITTED row: subqueries, grouped and DISTINCT reads, the
`rc_T` shapes). A write after such a read, in the same transaction, to a row it returned is not
checked against the version read by this statement. The user-facing sentence names the shape: a
statement any table of which is read through the committed view. Not taken: carrying the anchors
inside the wrapper (an inline table's own version columns, a decoupled table's metadata by a
per-row subquery), select-list surgery the wrapper was built to avoid, for a shape that is a
report's, not a read-then-write's; the user's call if a read-then-write of that shape turns up.

Nothing changes at SNAPSHOT, where such a statement runs as written once its tables are found
clear, and in autocommit through the own transaction (todo 367). Nothing in TPC-C or YCSB, which
have no such statement.

Build:

- `Router`: at READ COMMITTED, when `ast.split` leaves a ScalarDB reference to the views (the
  statement read through `rc_T` in part), build the route anchor-less (no versions, no
  `Anchored`) and gate it as the `rc_T` route is gated (`gated(r, ast, scalarDb, true)`), the
  split text staying the route's `sql`, the fallback. The route cache key is per anchoring mode
  already.
- Oracle the same with its unranged gates; the wrapper after todo 371.
- `transactions.md` section 5's row and the READ COMMITTED reads sentence: the shape named among
  the reads that record no anchor.

Expected: Q18 and Q20 at about the vendor's cost, both layouts and modes.

Tests to add (PostgreSQL both layouts, Oracle): Q18's shape, a join with `IN (SELECT ... GROUP BY
... HAVING ...)`, at READ COMMITTED in a transaction and in autocommit, the text run as written
with its gates (`current_query()`, `v$sql`) and the vendor's rows; with a row in flight, the
split's text and the committed rows; the read recording no anchor, as the anchor tests check it.
What must keep holding: the split tests of the four subquery kinds, todo 095's and 109's cases,
`ReadCommittedPastTheSplitIntegrationTest`.

## Acceptance Criteria

- [ ] A READ COMMITTED split read with a reference through `rc_T` runs as written behind
      whole-table gates on PostgreSQL and Oracle, anchor-less, with the tests above
- [ ] Full check passes on all engines
- [ ] Q18 and Q20 at about the vendor's cost in 28's local step, and in 6e's READ COMMITTED SF 3
      run from the build

## Work Log

- 2026-11-04: the user's item 1 (via 28); designed into 4.2 after todo 369's paragraph; the
  anchor consequence flagged. 28 builds on the user's word.
