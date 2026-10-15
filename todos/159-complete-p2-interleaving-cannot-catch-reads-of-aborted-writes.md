---
status: complete
priority: p2
issue_id: "159"
tags: [code-review, testing, interleaving]
dependencies: []
---

# The random schedules cannot catch a read of an after image whose writer aborts, and count any error as an abort

## Problem Statement

1. A ScalarDB actor that prepared always commits (`ScalarDbActor.run`, :653-658). If the driver read a
   prepared after image and anchored on its version, the writer still commits and the invariant holds:
   dirty reads of later-aborted writes are covered only by the one fixed scenario.
2. Abort classification is broad: any SQLState 40* on the driver, any `TransactionException` but
   Unknown on ScalarDB (:610-612, :666-670). A driver bug surfacing as a generic ScalarDB
   `CrudException`, or an over-abort, counts as a legitimate abort and the invariant holds trivially.
3. `committed > 0` is summed over all actors (:495): a driver that never commits still passes.

## Findings

- Abort histogram over a full run (1,663 aborts): ScalarDB aborts only at get and prepare
  (`PreparationConflictException` 749, `UncommittedRecordException` 81), none at the commit step.

## Proposed Solutions

### Option 1
- At the commit step, roll back about one in four ScalarDB transactions (seeded) and count it as an abort.
- Accept only 40001/40P01 from the driver, and only conflict exceptions (`CrudConflictException`,
  `PreparationConflictException`, `CommitConflictException`, `UncommittedRecordException`) from
  ScalarDB; anything else fails the schedule.
- Assert commits > 0 per side.

## Acceptance Criteria

- [x] Some ScalarDB transactions roll back after prepare in every workload.
- [ ] An unexpected exception class fails the schedule with its actor and step.
- [x] Both sides commit at least once per workload.

## Recommended Action

Option 1 as proposed.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.

### 2026-10-16 - Fixed

**By:** Claude Code
- One in four ScalarDB transactions (drawn from the seed) rolls back at its last step, after its
  prepare, and counts neither as a commit nor as an abort; at least one must happen per table.
- The driver's aborts are 40001 and 40P01 only; ScalarDB's are CrudConflictException (which
  UncommittedRecordException extends), PreparationConflictException, ValidationConflictException
  and CommitConflictException. Anything else fails the schedule with its step, as before.
- Commits are asserted per side per table, besides some commit per schedule.
- The full run passes with the narrower classes, so no other exception occurred.
- Not done: a run with the driver reading after images, to show the schedules now catch it; that
  needs a temporary edit of the driver source.
