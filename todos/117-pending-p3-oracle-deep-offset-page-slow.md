---
status: pending
priority: p3
issue_id: "117"
tags: [performance, oracle, row-limit]
dependencies: []
---

# Oracle: a page after a deep offset is about 20x slower through the driver

## Problem Statement

On Oracle, "page: ORDER BY key DESC, 20 rows after 10k" takes 61.2 ms through the driver against
2.95 ms for the plain statement (20.8x). The same page at offset 0 is 1.2x, and PostgreSQL is 1.2x at
10k. The rows are correct (no differing rows); only the time is off. It was the same before the
review fixes (todos 106-115), so it is not a regression.

## Findings

- Source: `driver/build/bench/read-overhead-ORACLE-COUNT_SUM_GROUP_DISTINCT_page_AVG_MIN_MAX-beforeplain.md`.
- Each copy is limited to offset + n rows (`ROWNUM` on Oracle), so each copy reads about 10,020 rows
  before the merge. Whether Oracle keeps the index-order stop key inside the copies, or sorts, is not
  checked yet: the cause is not known.

## Proposed Solutions

1. Read the plan (`EXPLAIN PLAN` / `DBMS_XPLAN`) of the rewritten statement and see where the time goes.
2. Depending on the plan: use `FETCH FIRST` / `OFFSET` instead of `ROWNUM` inside the copies on 12c+,
   or add an index hint so each copy stops after offset + n rows.

## Acceptance Criteria

- [ ] Cause recorded from the plan.
- [ ] The 10k page on Oracle within a few times the plain statement, or the reason it cannot be.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- Found while checking todo 114's benchmark run.
