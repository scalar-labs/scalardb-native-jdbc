---
status: pending
priority: p1
issue_id: "167"
tags: [code-review, correctness, jdbc, isolation]
dependencies: []
---

# At the snapshot levels a LEFT JOIN's NULL-extended row can vanish behind a row in flight without an abort

## Problem Statement

With `raw`, the committed fragment of the precise path is the whole table, rows in flight included
(SplitRead.java `Check.NONE`). The unmatched piece's `NOT EXISTS (SELECT 1 FROM <committed fragment> b
WHERE <ON>)` (SqlAst copyEdits) now sees after images too, and its NOT EXISTS over the in-flight fragment
sees before images. A row in flight that matches ON by either image suppresses the `(a, NULL)` row, while
the matched row it produces instead can be filtered by WHERE, so no returned row carries state 1 or 2.

`SELECT a.id FROM a LEFT JOIN b ON b.a_id = a.id WHERE b.id IS NULL`; a = {1}, b holds only a prepared
ScalarDB insert b(7, a_id 1), no before image. The committed copy gives (1, b7), filtered by WHERE; the
in-flight copy is empty; the unmatched copy is suppressed. Result empty, no abort; if S rolls back the
answer is {1}. The mirror case (before image matches ON, after image not) and `ORDER BY b.x NULLS FIRST
LIMIT 1` fail the same way. Verified on the emitted SQL; one reviewer traced the path as correct, the
counterexample shows otherwise.

## Proposed Solutions

### Option 1
Send a statement with a NULLABLE place to the per-table check at the snapshot levels (`covers` false).

### Option 2
Give the unmatched piece the non-raw committed fragment and a hidden state for the unmatched row:
`CASE WHEN EXISTS (<after images> b WHERE <ON>) OR EXISTS (<before images> b WHERE <ON>) THEN 1 END`.

## Acceptance Criteria

- [ ] The anti-join above aborts at both snapshot levels.
- [ ] Integration cases: the nullable side in flight with only the after image matching ON, only the
  before image, neither (no abort).

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.
