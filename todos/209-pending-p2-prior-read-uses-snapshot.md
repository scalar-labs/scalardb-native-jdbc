---
status: pending
priority: p2
issue_id: "209"
tags: [code-review, correctness, mysql, regression]
dependencies: []
---

# The pre-INSERT check of todo 201 reads the snapshot at REPEATABLE READ on MySQL

## Problem Statement

Writes.java ~1203: MySQL writes are allowed at REPEATABLE READ/SERIALIZABLE (Router ~525); there
`prior` is a consistent read of the snapshot. Verified (rv7): d at RR reads, another connection
deletes k=6 and commits, d's `INSERT (k, x) VALUES (6, 1)` is refused "stored other key values".
The snapshot also widens the race the ponytail comment admits to the whole transaction. MariaDB
runs it fine.

## Proposed Solutions

### Option 1
Make `prior` a locking read (`FOR SHARE`), which reads the latest committed rows (and closes the
race too, at the cost of a shared lock on the matched rows or gap).

### Option 2
Gate MySQL writes to READ COMMITTED until milestone 8b.

## Acceptance Criteria

- [ ] The repro succeeds at REPEATABLE READ (test)
- [ ] The trigger case of 201 is still refused

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 8f902dc (the fixes of todos 200-206).
