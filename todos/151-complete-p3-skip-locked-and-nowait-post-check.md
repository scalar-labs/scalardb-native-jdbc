---
status: complete
priority: p3
issue_id: "151"
tags: [code-review, correctness, jdbc, locking]
dependencies: []
---

# SKIP LOCKED aborts, and NOWAIT answers 40001, when the post-lock check finds a row in flight

## Problem Statement

When PostgreSQL's re-check (EPQ) locks a row a ScalarDB prepare wrote after the statement's
snapshot, the post-lock check aborts the transaction with 40001. Under SKIP LOCKED the row was being
written and should simply be skipped; under NOWAIT the native error is 55P03.

## Findings

- Locking reviewer, by reasoning on the EPQ mechanism the commit's own test demonstrates for the
  waiting case; not raced in a probe.
- Writes.java LockPlan.apply, the check over the locked keys.

## Proposed Solutions

### Option 1
Under SKIP LOCKED drop such rows from the result (and anchor nothing for them): return the locked rows
filtered rather than the scrollable result as is. Under NOWAIT throw 55P03.

## Recommended Action


## Acceptance Criteria

- [x] Under SKIP LOCKED a row prepared after the snapshot is skipped, the transaction usable; under NOWAIT the error is 55P03.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of cdfc58a (milestone 4c).

### 2026-10-15 - Fixed

**By:** Claude Code
- A row found in flight under the lock rolls the attempt back to its savepoint and reads again: at once under SKIP LOCKED, which then skips it; 55P03 under NOWAIT. Test: the queue read with a prepare landing after its snapshot returns the next row.
