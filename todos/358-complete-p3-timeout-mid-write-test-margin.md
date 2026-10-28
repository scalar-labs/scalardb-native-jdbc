---
status: complete
priority: p3
issue_id: "358"
tags: [test, flaky-test, postgresql, timeout]
dependencies: []
---

# `timeoutMidWrite` left half a second for a ScalarDB rollback to show

## Problem Statement

The integration suite at 5de26b9 (2026-10-29, all six engines, two containers at a time):
`MoreWritesIntegrationTest.batchesKeysWaitsAndLocks` failed on PostgreSQL in its `timeoutMidWrite`
scenario (todo 157) with `SQLTimeoutException: the statement's query timeout passed`, thrown from
`Writes$Run.sleep` in `KeyedPlan.applyFused`: the driver was still waiting for row 81 to settle when
the 2 s query timeout passed. The test alone passed three times; the full run at 1e9f967 passed.

## Findings

- The scenario's timings: query timeout 2 s, row 81 held in flight by a ScalarDB 2PC transaction
  rolled back at 1.5 s, row 80 locked by a plain connection until 2.2 s. On PostgreSQL and Oracle
  the UPDATE is one statement and must succeed; elsewhere it must time out after 80's DML.
- So the rollback had 0.5 s to show before the timeout; under load it did not.
- The vendor statement gets what is left of the timeout rounded up to whole seconds
  (`Writes$Run.track`), so the lock on 80 must end after the timeout and before the vendor
  statement's own deadline: unlock in (T, p + ceil(T - p)), p being when 81 is seen settled.
- None of the commits from 1e9f967 to 5de26b9 changes PostgreSQL's one-statement UPDATE.

## Recommended Action

Done: timeout 3 s, 81 rolled back at 1.5 s (1.5 s of slack, was 0.5 s), 80 unlocked at 3.25 s.
While 81 is seen settled within 0.5 s of its rollback, the vendor statement has until about 3.5 s.

## Acceptance Criteria

- [x] The scenario's slack for the rollback widened, the other bounds kept
- [x] `batchesKeysWaitsAndLocks` passes with the new timings on PostgreSQL, Oracle, MySQL, MariaDB
      and SQL Server (the last on the second try, todo 359)

## Work Log

### 2026-10-29

- Found in the run at 5de26b9; reruns alone passed three times. Widened the timings; the test
  passed on PostgreSQL and MySQL, and on Oracle and MariaDB. SQL Server failed once in `batches`,
  an earlier scenario, and passed twice when run again (todo 359).
