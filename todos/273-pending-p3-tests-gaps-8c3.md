---
status: pending
priority: p3
issue_id: "273"
tags: [code-review, sqlserver, serializable, tests]
dependencies: []
---

# 8c3 test gaps

## Problem Statement

1. Hint merge untested: SnapshotIntegrationTest:889 uses UPDLOCK, which holds alone; use a range read
   with a hint that does not hold (`WITH (ROWLOCK) WHERE id BETWEEN 5 AND 9`, then insert 6).
2. SETS_SNAPSHOT and keep-SERIALIZABLE (Router 1858-1862) never exercised: replacing them with
   `isolation = level` passes. At SERIALIZABLE run a passthrough statement naming isolation that
   changes nothing (expect SERIALIZABLE), then `SET ... SNAPSHOT` (expect REPEATABLE READ).
3. SerializableIntegrationTest 265-273: any ScalarDB abort on SQL Server skips the anomaly control;
   record the lock-wait cause and require one timing per read/workload to reach the anomaly.
4. `conflict()` (503-510) accepts 1222 on every engine; on MySQL it is a SQL error. Gate on SQL Server.
5. The coarse check's lock is never tested alone (also MySQL): a ScalarDB prepare outside the read's
   range must wait.
6. The deferred ScalarDB rollback's comment should say SQL Server needs it.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 5acbf90 (8c3), verified as stated above.
- 2026-10-20: partly fixed. 1 (range under ROWLOCK), 2 (passthrough naming isolation, SNAPSHOT at SERIALIZABLE), 4 (1222 gated on SQL Server), 5 (a write outside a coarse read waits), 6 (comment already says it) done. Open: 3.
