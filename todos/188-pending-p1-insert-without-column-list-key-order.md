---
status: pending
priority: p1
issue_id: "188"
tags: [code-review, correctness, jdbc]
dependencies: []
---

# An INSERT without a column list takes key positions from ScalarDB's column order, not the table's

## Problem Statement

8a changed `StatementShape.insertKeyValues` to use `t.dataColumns()` when the INSERT has no column
list (StatementShape.java:343, Writes.java:267). For an imported table ScalarDB's metadata puts the
key first (`ordinal_position` k=1), which need not be the data table's physical order.

Verified on m8-mysql (rv2 case p, rerun by the reviewer of record): `CREATE TABLE p (x INT, k INT
PRIMARY KEY)` with row (0, 5); `INSERT INTO p VALUES (5, 1)` inserts k=1, but the driver took the key
as 5: row 1 has no metadata, existing row 5 was restamped to version 2, count 1 (looks correct).

On PostgreSQL and MariaDB (RETURNING) the same wrong key list drives the in-flight, committed and
repeats steps of the duplicate classification (reasoned, not run): a wrong 23505 vs 40001 decision.
This is a regression from 758043b; before it, no column list meant no key list.

## Proposed Solutions

### Option 1
Revert to `null` without a column list; on MySQL refuse such an INSERT (0AN01) unless the key is
generated (187).

### Option 2
Read the data table's real column order (information_schema ORDINAL_POSITION / pg_attribute) into
TableInfo and use that.

## Acceptance Criteria

- [ ] INSERT without a column list never stamps a row it did not write, on any engine
- [ ] Duplicate classification on PostgreSQL uses correct keys or none
- [ ] Test with a table whose key is not its first column

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
