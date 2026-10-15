---
status: pending
priority: p2
issue_id: "149"
tags: [code-review, correctness, jdbc, locking]
dependencies: []
---

# A locking read silently omits a row that goes in flight between its check and its lock

## Problem Statement

`LockPlan` checks the predicate over the rows in flight, then runs the committed copy with the lock.
A ScalarDB prepare that commits its PostgreSQL transaction in between moves the row in flight before
the lock statement's snapshot: the committed copy's NOT EXISTS excludes it, so it is neither locked nor
returned, and the post-lock check never sees it. The application gets no row, where native PostgreSQL
would wait for the writer and the design says wait or abort.

## Findings

- Probe (locking reviewer): ran the plan's precheck (empty), `tpc.prepare()` on id 2, then the lock
  SQL: `[]` in 0.8 ms. With the row already in flight the driver aborts as designed; only the window
  is open.
- Writes.java LockPlan.apply, the precheck loop then `run.prepare(lock, null, true)`.

## Proposed Solutions

### Option 1
Run the in-flight check again after the lock statement (not for SKIP LOCKED); a match is 55P03 under
NOWAIT, otherwise back to the bounded wait or 40001. A row that goes in flight after the lock
statement's snapshot updates T, so EPQ and the post-lock check cover it.

## Recommended Action


## Acceptance Criteria

- [ ] A deterministic test: the row goes in flight between the check and the lock; the read waits or aborts, never returns nothing.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of cdfc58a (milestone 4c).
