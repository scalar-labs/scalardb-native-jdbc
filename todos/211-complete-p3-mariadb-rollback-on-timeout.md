---
status: complete
priority: p3
issue_id: "211"
tags: [code-review, correctness, mariadb, docs]
dependencies: []
---

# innodb_rollback_on_timeout=ON is not handled on MariaDB

## Problem Statement

MariaDB Connector/J raises a plain SQLException (HY000, 1205) for a lock wait timeout, so `within`
(Writes.java ~617) never marks rollback-only; with the option on, MariaDB rolls back the whole
transaction and later statements and the commit run in a new one. Predates 8f902dc, but the new
README/design text says the option is handled. Exception class verified; the option itself not
(needs a server restart).

## Proposed Solutions

### Option 1
On MariaDB, for 1205 read @@innodb_rollback_on_timeout and mark rollback-only when on.

### Option 2
Document it as unsupported (leave the option off).

## Acceptance Criteria

- [ ] Handled or documented on MariaDB

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 8f902dc (the fixes of todos 200-206).

### 2026-10-17 - Fixed

**By:** Claude Code
- endsTransaction: 1205 on MySQL or MariaDB ends the transaction only when @@innodb_rollback_on_timeout is on, whatever the exception class. Not tested with the option on (needs a server restart); docs updated.
