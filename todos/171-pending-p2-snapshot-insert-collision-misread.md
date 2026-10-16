---
status: pending
priority: p2
issue_id: "171"
tags: [code-review, correctness, jdbc, isolation]
dependencies: []
---

# At REPEATABLE READ an INSERT colliding with a ScalarDB insert in flight reports the application's duplicate

## Problem Statement

On 23505 the insert plan counts the key's metadata rows in flight (Writes.java ~866-876). At REPEATABLE
READ that count runs in the transaction's snapshot, which cannot see a key ScalarDB prepared after it,
while the 23505 came from the latest committed row. The count is 0 and the driver rethrows 23505,
non-retryable, where design 4.3 makes a key in flight a retryable conflict.

## Proposed Solutions

### Option 1
At the snapshot levels, a 23505 whose key the snapshot does not show as committed is 40001.

### Option 2
Read the state outside the snapshot (not possible in one transaction on PostgreSQL), so Option 1.

## Acceptance Criteria

- [ ] An INSERT at REPEATABLE READ of a key a ScalarDB transaction prepared after the snapshot aborts
  with 40001.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.
