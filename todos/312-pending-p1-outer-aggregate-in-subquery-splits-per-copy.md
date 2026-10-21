---
status: pending
priority: p1
issue_id: "312"
tags: [read, split-read, aggregates, postgresql]
dependencies: []
---

# An outer aggregate written inside a subquery returns one row per copy of a split read

## Problem Statement

In SQL an aggregate whose arguments name only columns of an outer query belongs to that outer
query: `select (select max((select i.unique2 from tenk1 i where i.unique1 = o.unique1))) from
tenk1 o` returns one row, the maximum over all of `tenk1 o`. Through the driver it returns two:
`[[9999], [null]]`. The split read repeats the statement per copy of `tenk1 o` (committed rows,
rows in flight) and joins the copies with UNION ALL; each copy aggregates its own rows, and the copy
without rows still returns its row of NULL. Wrong rows, with nothing in flight.

## Findings

- T4, PostgreSQL's `aggregates.sql` (REL_16_15), both layouts, nothing in flight:
  `select (select max((select i.unique2 from tenk1 i where i.unique1 = o.unique1))) from tenk1 o`
  and the same with `filter (where o.unique1 < 10)`.
- `SqlAst.RowWise`: "A subquery is its own scope and is not looked into" — an aggregate of the outer
  level written inside a subquery is not seen, so the statement counts as row-wise and is split
  without the aggregates applied on top of the copies.

## Proposed Solutions

### Option 1: a subquery holding an aggregate is not row-wise
`RowWise` looks into subqueries for aggregate calls; one that holds any aggregate whose arguments
are not all columns of the subquery's own FROM makes the statement non-splittable (read through
`rc_T`). Conservative: any aggregate inside a subquery of the select list, HAVING or ORDER BY.
**Effort:** Small.

### Option 2: resolve the aggregate's level
Resolve each column of the aggregate's arguments to its scope; the aggregate belongs to the
innermost level of those columns. **Effort:** Medium.

## Acceptance Criteria

- [ ] Both statements pass in T4 (`-Dnativejdbc.regress=true`), and a unit test in `SplitTest`.

## Work Log

### 2026-10-21 - Found by T4

**By:** Claude Code
- `RegressIntegrationTest`, first runs.

## Resources

- docs/plans/testing.md, milestone T4.
