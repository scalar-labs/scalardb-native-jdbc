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

## Acceptance Criteria

- [ ] The anchor-less split read at READ COMMITTED takes the gated form with range or whole-table
      gates and the busy mark, on PostgreSQL and Oracle, with the tests above
- [ ] Full check passes on all engines
- [ ] TPC-C READ COMMITTED cells on PostgreSQL (local, then 6e's) with StockLevel and Delivery
      better or unchanged; Q1 and the decoupled-only TPC-H queries at about the vendor's cost

## Work Log

- 2026-11-04: the user's item 3 (via 28); designed into 4.2. 28 builds after todos 370 and 371,
  on the user's word.
