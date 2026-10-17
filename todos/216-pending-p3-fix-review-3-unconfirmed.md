---
status: pending
priority: p3
issue_id: "216"
tags: [code-review]
dependencies: []
---

# Suspicions from the focused review of b963158, not confirmed

## Problem Statement

- PostgreSQL: the information_schema query on every INSERT plan, on the user's connection, may fix
  a REPEATABLE READ/SERIALIZABLE snapshot at prepare time; in an aborted transaction it fails at
  prepareStatement rather than at execute.
- MySQL 8.0.22+: FOR SHARE needs SELECT plus UPDATE, DELETE or LOCK TABLES; an INSERT+SELECT-only
  user may fail every ScalarDB INSERT (moot if 214 removes FOR SHARE).
- SQL Server's deadlock 1205 falls to `instanceof SQLTransactionRollbackException`; mssql-jdbc may
  raise a plain SQLServerException (matters for 8c).
- isCodeAt reads `--` without a following space as a comment, which MySQL does not.
- The comment that NO_BACKSLASH_ESCAPES is not tracked: Session reads @@SESSION.sql_mode already;
  scanning by the actual mode would be simpler.

## Proposed Solutions

### Option 1
Probe each and file or rule out.

## Acceptance Criteria

- [ ] Each item reproduced and filed, or ruled out

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of b963158 (the fixes of todos 207-211).
