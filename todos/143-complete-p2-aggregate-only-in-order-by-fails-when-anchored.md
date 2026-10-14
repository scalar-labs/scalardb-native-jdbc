---
status: complete
priority: p2
issue_id: "143"
tags: [code-review, correctness, regression, anchors]
dependencies: []
---

# A read with an aggregate only in ORDER BY fails since 4b

## Problem Statement

`SELECT 1 FROM t ORDER BY count(*)` is anchored: `SqlAst.rowPerRow` looks at the select list only,
while the split read's `isGrouped` also looks at ORDER BY and lifts it as grouped. The hidden columns are
appended to an aggregated select list on top, and PostgreSQL fails with 42803. It worked before 4b.

## Findings

- Probe (SQL reviewer) on PostgreSQL, autocommit and in a transaction: 42803 `column
  "v.scalardb_native_x0" must appear in the GROUP BY clause`; the plain query returns `[1]`.
- Checked: `isGrouped` visits `selectAndOrderBy(s)`, `rowPerRow` only `getSelectItems()`.

## Proposed Solutions

### Option 1
`rowPerRow()` as `PlainSelect && getDistinct() == null && !isGrouped(s)`: the split already refuses
unknown, window and volatile functions, so the RowWise pass adds nothing (about 12 lines shorter).

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] The statement returns the plain query's rows through the driver; a SplitTest case.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of ef68593 and b312ced (milestone 4b).

### 2026-10-15 - Fixed

**By:** Claude Code
- `rowPerRow` is `!isGrouped` and no DISTINCT, as the split decides grouping, ORDER BY included. Test "143".
