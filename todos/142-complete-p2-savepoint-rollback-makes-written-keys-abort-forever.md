---
status: complete
priority: p2
issue_id: "142"
tags: [code-review, correctness, anchors, savepoints]
dependencies: []
---

# After a rollback to a savepoint, a write of a key the transaction read and wrote always aborts

## Problem Statement

`rolledBackToSavepoint()` clears the whole written set; the transaction's surviving own write of a
key then no longer matches the key's first read, and every later write of it aborts with 40001 --
deterministically, so a retry loop repeats it forever. Spring `PROPAGATION_NESTED` (and any retry
inside a savepoint) follows exactly this: load, update, a nested call rolls back to its savepoint, update
the row again.

## Findings

- Verified by both the JDBC and the lost-update reviewer: read id 1, update, `setSavepoint` and
  `rollback(sp)` with nothing in between, update again: 40001. SQL `SAVEPOINT` / `ROLLBACK TO` alike.
- Anchors.java (`rolledBackToSavepoint`), Writes.java (the anchor check).

## Proposed Solutions

### Option 1
Remember the stamp `tx_id` of every write of the transaction (CAS, stamp insert, INSERT plan) until it
ends, and treat S1's `tx_id` in that set as unchanged: such a stamp is the transaction's own,
uncommitted, under its row lock. A write a savepoint undid shows the first read again and passes. The
written set and the savepoint hook then go.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] The probe's sequence succeeds; a row another transaction changed still aborts.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of ef68593 and b312ced (milestone 4b).

### 2026-10-15 - Fixed

**By:** Claude Code
- Anchors keeps the transaction's own write stamps (CAS, stamp insert, INSERT plan) until it ends; a row carrying one counts as unchanged. The written set and the savepoint hooks are gone. Test "142" (JDBC and SQL savepoints).
