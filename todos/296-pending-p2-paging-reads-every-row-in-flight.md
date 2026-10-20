---
status: pending
priority: p2
issue_id: "296"
tags: [performance, read, oracle, mysql]
dependencies: []
---

# Paging over a key order reads every row in flight

## Problem Statement

`SELECT ... FROM c a JOIN o b ON b.customer_id = a.id ORDER BY b.id DESC FETCH FIRST 20 ROWS ONLY`
reads about 20 rows as a plain query: the key backwards, stopped at the limit. The split read's
copies with a table in flight start from the in-flight index instead, so each reads every row in
flight, joins and sorts them to keep 20. The cost grows with the rows in flight, whatever the
statistics: 27-29x on Oracle and 105-119x on MySQL at 10,000, over a database that pays about 1x for
the same rows held by open transactions.

## Findings

- docs/benchmarks/read-overhead.md, "Statistics, open transactions and paging" (2026-10-20).
- DRIVER over BASE_OPEN at 10,000 in flight: page JOIN FK 21.9x (Oracle), 104.5x (MySQL); 20 rows of
  500k 25.1x (MySQL); 20 rows after 10k 16.0x and 12.7x, already 13.8x and 64.4x with nothing in
  flight.
- MySQL plan at 10,000: the in-flight pieces of `c` and `o` take 300 ms and 1,000 ms, the committed
  one 0.2 ms.
- The CASE view (VIEW_CASE) keeps the plain plan and stays at 0.9-1.1x at 100 and 10,000 on both
  engines; SPLIT_MX is worse (96-377x), SPLIT_LT generates nothing for the shape.
- The CASE view loses on a filter, join or aggregate over a non-key column, 11-830x: the CASE hides
  the column from its index.

## Proposed Solutions

### Option 1: route key-ordered paging through the per-row form
When the statement orders by one table's key with a row limit, filters on no non-key column of a
ScalarDB table, reaches every other table through that table's key, and has no GROUP BY, DISTINCT or
aggregate: read each ScalarDB table through a key lookup of its metadata per row, the before image
by CASE, instead of the split. **Effort:** Medium.

### Option 2: keep the split, push the order into the in-flight copies
An in-flight index ordered by the key could let a copy stop early when the ordered table is the one
in flight; it does not help the copies where another table is. **Effort:** Medium, partial.

## Recommended Action

## Technical Details

- Router, the split read's emitter; the CASE form of `VIEW_CASE` in ReadOverheadBenchmark.

## Acceptance Criteria

- [ ] VIEW_CASE measured on every shape at 100 and 10,000 rows in flight, to bound the route.
- [ ] The paging shapes through the driver within 1.2x of BASE_OPEN at 10,000 rows in flight on
      Oracle and MySQL, the other shapes unchanged.
- [ ] Rows equal to BASE with rows in flight, deleted rows and in-flight inserts included.

## Work Log

### 2026-10-20 - Found in measurement

**By:** Claude Code
- Measured while working todo 094.

## Resources

- docs/benchmarks/read-overhead.md; docs/plans/testing.md (T6).
