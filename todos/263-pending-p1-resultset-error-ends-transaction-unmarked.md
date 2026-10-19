---
status: pending
priority: p1
issue_id: "263"
tags: [code-review, sqlserver, correctness]
dependencies: []
---

# An error at ResultSet.next() that ends the transaction neither marks it rollback-only nor maps 3960

## Problem Statement

SQL Server streams results, and a 3960 (or a 1205 deadlock) can come at `next()` after rows were
sent. The rollback-only marking and the 3960 → 40001 mapping wrap only the execute
(Proxies.java:840-854); result sets go through `ChildHandler` (Proxies.java:1465-1490), which forwards
`next()` as is. SQL Server has already rolled the transaction back, and with implicit transactions
the next statement opens a new one: a partial commit.

## Findings

Driver at REPEATABLE READ, autocommit off, plain tables:
1. `INSERT INTO dbo.plog VALUES (1)`
2. `SELECT id FROM big WITH (UPDLOCK) ORDER BY id` over 20000 rows, id 19999 changed after the
   snapshot: `next()` throws S0002/3960 after 19998 rows, not 40001
3. `INSERT INTO dbo.plog VALUES (2)`; `commit()` succeeds
4. plog holds only [2]: row 1 lost, row 2 committed; anchors and passed checks carry over.

Same as todo 230, fixed at the execute only; a deadlock at `next()` predates 8c2. ScalarDB-table
locking reads are fetched whole inside `Writes.within` and are covered.

## Proposed Solutions

1. Route errors from result-set (and getMoreResults/getResultSet) calls through the same
   endsTransaction → rollbackOnly and asRetryable path (small–medium).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 51e1c51 (8c2), verified as stated above.
