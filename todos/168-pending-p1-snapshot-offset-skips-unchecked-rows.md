---
status: pending
priority: p1
issue_id: "168"
tags: [code-review, correctness, jdbc, isolation]
dependencies: []
---

# At the snapshot levels an OFFSET or a scrollable jump passes over rows in flight unchecked

## Problem Statement

The precise path checks the state of the rows the application is positioned on (Proxies.AnchoredHandler).
Rows an OFFSET skips, and rows `absolute`, `relative` or `last` jump over, still count toward the position
but are never checked. A row in flight before the page shifts it, and an update in flight whose both
images match appears twice in the union.

`SELECT id FROM t ORDER BY id LIMIT 10 OFFSET 10`, committed ids 1-30 plus a prepared insert id 5: the
driver returns 10-19 instead of 11-20, no abort. Design 4.2's "a row in flight that neither image brings
into the result, past a row limit included, changes nothing" is false for an offset.

## Proposed Solutions

### Option 1
Take the per-table check for a statement with an OFFSET, and for a result set type other than forward only.

### Option 2
Check every row passed over: the offset applied by the driver, `absolute`/`relative` stepping row by row.

## Acceptance Criteria

- [ ] The OFFSET case aborts, or takes the per-table check.
- [ ] A scrollable result set never positions past an unchecked row in flight.
- [ ] Design 4.2's sentence corrected.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.
