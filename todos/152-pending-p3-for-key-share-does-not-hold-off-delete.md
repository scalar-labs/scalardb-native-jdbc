---
status: pending
priority: p3
issue_id: "152"
tags: [code-review, correctness, jdbc, locking]
dependencies: []
---

# FOR KEY SHARE does not hold off a ScalarDB delete

## Problem Statement

ScalarDB's delete prepare writes T with a non-key UPDATE, which takes FOR NO KEY UPDATE, compatible
with KEY SHARE. A driver `FOR KEY SHARE` therefore lets a ScalarDB delete prepare and commit at the
coordinator while it holds the lock; natively a DELETE waits behind KEY SHARE.

## Findings

- Probe (locking reviewer): with `SELECT x FROM wb.imp WHERE id = 9 FOR KEY SHARE` held, a ScalarDB
  get+delete of id 9 prepared (state 2) and committed (state 3); only the record delete of T waited.
  With FOR SHARE the delete blocked.
- Router.lockingRead passes the lock mode through.

## Proposed Solutions

### Option 1
Emit FOR SHARE for FOR KEY SHARE (and FOR UPDATE for FOR NO KEY UPDATE if the same holds for updates):
stronger than asked, safe.
### Option 2
Refuse FOR KEY SHARE.

## Recommended Action


## Acceptance Criteria

- [ ] Under the driver's FOR KEY SHARE a ScalarDB delete of the row waits until the driver's transaction ends.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of cdfc58a (milestone 4c).
