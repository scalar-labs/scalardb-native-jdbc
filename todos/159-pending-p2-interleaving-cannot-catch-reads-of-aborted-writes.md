---
status: pending
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

- [ ] Some ScalarDB transactions roll back after prepare in every workload.
- [ ] An unexpected exception class fails the schedule with its actor and step.
- [ ] Both sides commit at least once per workload.

## Recommended Action


## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.
