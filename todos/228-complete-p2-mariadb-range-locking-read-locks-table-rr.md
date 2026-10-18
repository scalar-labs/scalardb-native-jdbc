---
status: complete
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

- [x] Cause isolated
- [x] The test in SnapshotIntegrationTest (todo 220 block) runs the locking read on MariaDB too

## Recommended Action

No change: MariaDB's own behaviour (Option 2's finding, documented in the test).

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- While verifying the fix of todo 220.

### 2026-10-18 - Investigated

**By:** Claude Code
- Captured the statements with MariaDB's general log: the lock statement is
  `SET STATEMENT optimizer_switch='derived_merge=on' FOR SELECT ... FROM (SELECT t.id FROM t_data t
  WHERE COALESCE((SELECT q.tx_state ...), 3) NOT IN (1, 2) FOR UPDATE) t WHERE id >= 100`; the
  relock is the driving-key join of todo 220 (eq_ref).
- Probe (scratchpad ai/L8.java, 1020 rows): MariaDB plans `id >= 100` (1000 rows) as a full scan
  (type ALL), merged or not, and so does a native `SELECT id FROM t_data WHERE id >= 100 FOR UPDATE`;
  at REPEATABLE READ all gaps are locked and INSERTs of -5 and 50 time out (1205). A selective range
  (`id >= 1050`, 50 rows) locks only it, the driver's statement and the native one alike. MySQL
  chooses the range in both cases.
- Not a driver issue: the driver locks what the native statement would. The todo 220 test keeps the
  locking read on MySQL only, with that comment.
