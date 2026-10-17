---
status: pending
priority: p2
issue_id: "203"
tags: [code-review, correctness, postgresql, mariadb]
dependencies: []
---

# On PostgreSQL and MariaDB an INSERT without a column list loses duplicate classification

## Problem Statement

StatementShape.java:338, Writes.java ~315: since todo 188, no column list means no key list, so
the in-flight, committed and repeats steps are skipped and every duplicate is rethrown as the
application's. Verified on MariaDB: with a key in flight, `INSERT INTO q (k, x) VALUES (7, 1)`
gives 40001, `INSERT INTO q VALUES (7, 1)` gives 23000, a permanent error for a retryable
conflict. PostgreSQL goes the same way (at SNAPSHOT a key committed after the snapshot too).

## Proposed Solutions

### Option 1
Read the data table's real column order (information_schema ordinal_position / pg_attribute) for
the no-column-list case, into TableInfo or asked once per plan, and use it.

### Option 2
Refuse an INSERT without a column list on every engine. Simpler, less compatible.

## Acceptance Criteria

- [ ] A duplicate of a key in flight without a column list gives 40001 on PostgreSQL and MariaDB (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 4a62f9e (the fixes of todos 186-199).
