---
status: pending
priority: p1
issue_id: "157"
tags: [code-review, correctness, jdbc, writes]
dependencies: []
---

# A query timeout or cancel between a write's DML and its CAS leaves an unstamped, partial write the application can commit

## Problem Statement

`Writes.within` marks the transaction rollback-only only when the failure is `Aborted`. A driver-side
57014 from `Run.prepare` (deadline passed) or from `checkCancelled()` can come after `KeyedPlan.apply`
has run the DML (Writes.java:647) but before the CAS (:656). PostgreSQL never errored, so the
transaction is live; the application may catch the timeout and commit. The row then holds the new data
under its old metadata (breaks I5), and a writer anchored on that version passes its CAS: a lost update.
Other keys of the same statement were never written, so the statement also applied in part.

## Findings

- Probe (PostgreSQL): autocommit off, `setQueryTimeout(2)`, `UPDATE rvp.imp SET x='d' WHERE id IN (1,2)`;
  key 2 prepared by ScalarDB and rolled back at t+1.5 s, key 1 held FOR UPDATE by a raw connection until
  t+2.2 s. The update failed with 57014 after 2269 ms; `commit()` succeeded; key 1 had x=d with its
  tx_id, version 2 and state 3 unchanged; key 2 untouched.
- The in-flight wait's sleep does not count against the deadline, which widens the window.
- Same root in `LockPlan.apply`: a driver-side timeout or cancel after the lock statement returned (from
  `lockedVersions` or the post-check `matchesInFlight`, :1072) leaves savepoint `sp` and its row locks
  in place.
- Predates this range (todo 136's timeout/cancel work).

## Proposed Solutions

### Option 1: rollback-only on any failure in a transaction
In `within`, with autocommit off, run `rollbackOnly` on any `SQLException`/`RuntimeException`. Simple;
stricter than native PostgreSQL only where it would not abort the transaction itself.

### Option 2: a savepoint per write
Run each write under a savepoint and roll back to it on any failure: the statement is all or nothing,
as natively, and the transaction stays usable. One extra round trip per write.

## Acceptance Criteria

- [ ] The probe above: after the timeout, either commit fails or key 1 is unchanged.
- [ ] A locking read that times out after its lock statement holds no row locks afterwards.

## Recommended Action


## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.
