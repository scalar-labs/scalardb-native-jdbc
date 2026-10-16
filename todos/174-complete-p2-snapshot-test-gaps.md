---
status: complete
priority: p2
issue_id: "174"
tags: [code-review, testing]
dependencies: []
---

# The milestone 7 tests miss the shapes and paths the review found broken, and some assertions are loose

## Problem Statement

Neither SerializableIntegrationTest nor SnapshotIntegrationTest writes by predicate at the snapshot levels
(todo 166), runs a LEFT JOIN (167) or an OFFSET (168), or aborts a row-by-row read in autocommit (169). The
SSI workloads read only through the row-by-row path, never through the per-table check or a join. The
read-only SSI case passes if the read-only ScalarDB transaction aborts for any reason; `run()` counts any
TransactionException as an abort. Missing needs-SSI timings: `s.prepare d.write s.validate s.commit d.commit`
and `s.validate d.write d.commit s.commit`. D_FIRST, D_COMMITS_BEFORE_VALIDATION and IN_FLIGHT test ScalarDB's
validation and the in-flight abort, not SSI; label them.

## Proposed Solutions

### Option 1
Add the cases with the fixes of 166-169; in SerializableIntegrationTest also run each workload with the
driver reading through `SELECT COUNT(*)/SUM(v) ... WHERE g = ?` and through a join; assert the read-only
trace exactly and require conflict exceptions; add the two timings.

## Acceptance Criteria

- [ ] Each fix of 166-169 has a failing-first integration case.
- [ ] The SSI scenarios run through both read paths, with exact assertions.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.

### 2026-10-17 - Fixed

**By:** Claude Code
- Predicate writes, LEFT JOIN, OFFSET, scrollable and autocommit aborts: SnapshotIntegrationTest
  (745cc84, bfe1b57).
- SerializableIntegrationTest: every scenario runs through the three read paths (row by row, a join,
  the per-table check); two more needs-SSI timings (D_WRITES_IN_BETWEEN, D_WRITES_AFTER_VALIDATION);
  only ScalarDB's conflict exceptions count as aborts, in the scenarios and the read-only case; the
  timings that do not test SSI are labeled.
