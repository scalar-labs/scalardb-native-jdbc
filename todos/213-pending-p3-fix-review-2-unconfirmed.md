---
status: pending
priority: p3
issue_id: "213"
tags: [code-review]
dependencies: []
---

# Suspicions from the focused review of 8f902dc, not confirmed

## Problem Statement

- MariaDB INVISIBLE columns: the column count differs from the VALUES row, so an INSERT without a
  column list runs without duplicate classification again (MySQL refuses, which is safe).
- PostgreSQL: information_schema.columns lists only columns the user has a privilege on; a missing
  one loses classification the same way. columnOrder in an aborted transaction fails at plan time.
- PostgreSQL: a NUMERIC key bound with setFloat is now compared as double in the in-flight and
  committed checks, which may classify a key in flight as a duplicate.
- statementOnly reads @@innodb_rollback_on_timeout on every 1205; it is static, so once per
  connection would do.
- A second connection's DELETE waited behind an INSERT of another key at REPEATABLE READ: gap locks
  from the stamp or the INSERT, not investigated.

## Proposed Solutions

### Option 1
Probe each and file or rule out.

## Acceptance Criteria

- [ ] Each item reproduced and filed, or ruled out

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 8f902dc (the fixes of todos 200-206).
