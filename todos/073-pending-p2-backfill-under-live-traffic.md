---
status: pending
priority: p2
issue_id: "073"
tags: [code-review, performance, setup, operations]
dependencies: []
---

# Backfill under live traffic

## Problem Statement

The backfill runs each step as one statement over the whole table, retries the whole statement on any 23xxx error, and on MySQL and SQL Server takes locks that stall ScalarDB and the application for the duration.

## Findings

- Measured on PostgreSQL (5M rows): INSERT 20 s, UPDATE 4.5 s, COUNT 2 s; about 7 min, 1.5 min and 40 s per loop at 100M, in one transaction (performance review).
- MySQL at the default REPEATABLE READ: INSERT ... SELECT takes shared next-key locks on the data table; the unindexed `UPDATE ... WHERE tx_id IS NULL` locks every scanned row. SQL Server escalates to a table lock. Large binlog/redo/WAL.
- The 23xxx catch also swallows NOT NULL and check violations that repeat every pass; deadlocks and lock timeouts (40001, 40P01, 1205, 1213) abort the setup instead of being retried.
- The loop re-runs both full scans and a three-scan COUNT; the deadline is checked only after a full pass.

## Proposed Solutions

### Option 1: Small now, chunking only if needed

**Approach:** Setup session at READ COMMITTED; catch only unique violations (23505; 23000 with vendor codes 1062, 2627/2601, ORA-00001) and retry lock conflicts; poll only the in-flight term in the wait loop (served by the in-flight index), with one final pass; document a maintenance window or tables under about 10M rows. Chunk by key range (about 150 lines) only if online backfill of large tables becomes a requirement.

**Pros:** Honest and cheap.

**Cons:** Large tables still need a quiet window.

**Effort:** Small

**Risk:** Low

## Recommended Action

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [ ] Setup session at READ COMMITTED on every engine.
- [ ] A NOT NULL violation surfaces at once; a deadlock is retried.
- [ ] Design section 1 states the operating window for the backfill.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Reproduced on MySQL 8.4 at the default REPEATABLE READ: an application UPDATE of a data row timed out (1205) while the backfill's INSERT ... SELECT was open. The 23xxx catch and the missing lock-conflict retry are confirmed in the code; the throughput numbers are the reviewer's measurement.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
