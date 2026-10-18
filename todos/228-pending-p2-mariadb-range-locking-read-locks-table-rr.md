---
status: pending
priority: p2
issue_id: "228"
tags: [code-review, concurrency, mariadb]
dependencies: []
---

# A MariaDB locking read of a key range locks below the range at REPEATABLE READ

## Problem Statement

Found while testing todo 220. In SnapshotIntegrationTest (MariaDB, RR) a driver transaction runs
`SELECT id FROM sn.t WHERE id >= 100 FOR UPDATE` over 1000 rows (ids 1-20 and 100-1099); another
connection's `INSERT INTO sn.t_data (id, ...) VALUES (-5, ...)` then waits and fails with 1205.
After the same transaction's 1000-row INSERT alone the far INSERT succeeds, and on MySQL it
succeeds after the locking read too, so the driver's key lookups (220) are not the cause. EXPLAIN
of the view (sn.t) on MariaDB shows a full index scan of the metadata table's in-flight index
(`t_inflight`, ~1020 rows) driving an eq_ref on the data table; the lock statement's own plan was
not captured. Likely the user predicate's statement (first arm) scans and locks beyond the range.

## Proposed Solutions

### Option 1
Capture the lock statement's plan on MariaDB (EXPLAIN of LockPlan.lock) and make it range-scan the
data table's primary key (index hint or join order).

### Option 2
Document that a MariaDB range locking read at RR may lock the whole table.

## Acceptance Criteria

- [ ] Cause isolated
- [ ] The test in SnapshotIntegrationTest (todo 220 block) runs the locking read on MariaDB too

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- While verifying the fix of todo 220.
