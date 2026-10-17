---
status: complete
priority: p2
issue_id: "204"
tags: [code-review, correctness, mysql]
dependencies: []
---

# A MySQL lock wait timeout during a write now makes the transaction rollback-only

## Problem Statement

Writes.java ~536, `within`: todo 199 marks rollback-only on any SQLTransactionRollbackException.
Connector/J raises MySQLTransactionRollbackException (40001, 1205) for a lock wait timeout, which
with the default innodb_rollback_on_timeout=OFF rolls back only the statement, already undone by the
write's savepoint. Verified: in a transaction, UPDATE id=2 succeeds, UPDATE id=1 times out; the next
UPDATE and commit fail "aborted by an earlier statement", and the id=2 write is lost. MariaDB raises
HY000 1205 and goes on. Not documented.

## Proposed Solutions

### Option 1
On MySQL and MariaDB mark rollback-only for 1213 (deadlock) only, and for 1205 when
`@@innodb_rollback_on_timeout` is on (read once per connection).

## Acceptance Criteria

- [ ] A lock wait timeout leaves the transaction usable on MySQL (test)
- [ ] A deadlock still marks it rollback-only

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 4a62f9e (the fixes of todos 186-199).

### 2026-10-17 - Fixed

**By:** Claude Code
- A class-40 error 1205 on MySQL marks rollback-only only when @@innodb_rollback_on_timeout is on (read on that error); test on MySQL and MariaDB.
