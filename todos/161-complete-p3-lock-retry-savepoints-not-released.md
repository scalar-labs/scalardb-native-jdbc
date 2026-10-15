---
status: complete
priority: p3
issue_id: "161"
tags: [code-review, performance, jdbc, locking]
dependencies: []
---

# A locking read's failed attempts leave their savepoints open

## Problem Statement

`LockPlan.apply` rolls back to the attempt's savepoint (Writes.java:1079) but never releases it, so each
retried attempt leaves a subtransaction open until the transaction ends (up to 10 per locking read).
Locks are released correctly; past 64 subtransactions PostgreSQL slows down (subxid cache overflow).

## Proposed Solutions

### Option 1
`run.v.releaseSavepoint(sp)` after `rollback(sp)`.

## Acceptance Criteria

- [x] No savepoint is left behind by a retried locking read.

## Recommended Action

Option 1.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.

### 2026-10-16 - Fixed

**By:** Claude Code
- `releaseSavepoint(sp)` after the rollback to it in `LockPlan.apply`'s retry path. No test of its
  own: the retry tests of todo 151 run the path.
