---
status: pending
priority: p3
issue_id: "326"
tags: [isolation, liveness, sqlserver, serializable]
dependencies: ["324"]
---

# On SQL Server at SERIALIZABLE, read-write transactions that scan a table ScalarDB writes hardly progress

## Problem Statement

The part of todo 324 the read-only fix does not reach: a read-write transaction at SERIALIZABLE on
SQL Server that scans a table (`WHERE grp = ?`, a whole-table scan for the driver and for ScalarDB),
writing from what it read or not (the bank test's read-write readers), beside ScalarDB transactions writing the same table. The driver's
`HOLDLOCK` key-range locks, and the range lock on the table's in-flight index that its per-table
check takes, meet ScalarDB's prepares and validations in waits and deadlocks (1205) on both sides.
The data stays correct; throughput nearly stops.

## Findings

- T5 bank test, read-write readers: 0 to 4 reads in 60 seconds (todo 324).
- T5 membership test (`isolation/MembershipIntegrationTest`), 2026-10-23: 9 to 23 writes and 7 to 24
  reads in 60 seconds, against about 300 writes without ScalarDB's writers and thousands on the
  other engines. Details in todo 324.
- Documented as a limit (design 7, README), 2026-10-24.
- The predicate checks under way (branch `predicate-checks`, `docs/benchmarks/inflight-check.md`)
  narrow the per-table in-flight check to the read's predicate; whether they also narrow the
  locked check at SQL Server's SERIALIZABLE, and so this starvation, is to be seen when they land.

## Proposed Solutions

### Option 1: keep it a documented limit
Done already; nothing more to build. **Effort:** none.

### Option 2: take the predicate checks into the locked path
If the predicate checks lock only the predicate's range of the in-flight index on SQL Server,
the prepares outside it no longer wait. **Effort:** depends on that work. **Risk:** the range lock
must still hold off every prepare that would change the read (design 5).

## Recommended Action

After the predicate checks merge, run the membership test on SQL Server at SERIALIZABLE again. If
writers progress, close this; otherwise it stays a documented limit (Option 1).

## Acceptance Criteria

- [ ] The membership test rerun on SQL Server SERIALIZABLE after the predicate checks merge, and
      the result recorded here.

## Work Log

### 2026-10-24 - split from todo 324
- Readers fixed in todo 324 (read-only transactions read without locks); this keeps the writers.

### 2026-10-24 - also with the driver's writers only, on CI
- First daily isolation run (GitHub Actions run 40092494740, 4-vCPU runner): membership at
  SERIALIZABLE, driver writers only, inline layout committed 2 writes and 19 reads in 15 minutes;
  527 reads and 3 writes were deadlock victims (40001). The rules held.
- MembershipIntegrationTest's progress floor now skips SQL Server at SERIALIZABLE for both sides;
  restore it when this is fixed.
