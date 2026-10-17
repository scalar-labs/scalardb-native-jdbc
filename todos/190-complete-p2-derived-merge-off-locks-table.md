---
status: complete
priority: p2
issue_id: "190"
tags: [code-review, correctness, performance, mysql, mariadb]
dependencies: []
---

# With derived-table merging off, a MySQL/MariaDB locking read locks the whole table

## Problem Statement

The inner locking clause (4.2) relies on the engines merging the committed copy, or pushing the
predicate into it. Verified through the driver: with `optimizer_switch='derived_merge=off,
derived_condition_pushdown=off'` (MySQL) or `derived_merge=off,condition_pushdown_for_derived=off`
(MariaDB), `SELECT x FROM t WHERE id = 2 FOR UPDATE` locked every committed row (MySQL data_locks:
PRIMARY 1-6; MariaDB trx_rows_locked 6). Default switches lock row 2 only.

## Proposed Solutions

### Option 1
MySQL: `/*+ MERGE(alias) */` on the outer SELECT; MariaDB: `SET STATEMENT
optimizer_switch='derived_merge=on' FOR ...`. Both verified to bring the lock back to row 2.

### Option 2
Document the switches as required. Weaker.

## Acceptance Criteria

- [x] Locking reads lock only matching rows with merging switched off in the session (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.

### 2026-10-17 - Fixed

**By:** Claude Code
- MERGE(alias) hint on MySQL, SET STATEMENT optimizer_switch='derived_merge=on' FOR on MariaDB; tested with merging and pushdown off in the session (another row stays unlocked).
