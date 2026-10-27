---
status: complete
priority: p1
issue_id: "288"
tags: [code-review, postgresql, routing]
dependencies: []
---

# A data-modifying CTE on PostgreSQL is routed as a read

## Problem Statement

`WITH q AS (DELETE FROM t WHERE id = 1 RETURNING id) SELECT ...` (INSERT and UPDATE alike) on a ScalarDB
table is classified SELECT and rewritten: the DELETE runs against the read view `rc_t`, which PostgreSQL
refuses (55000, not auto-updatable). Safe only because the view is a UNION ALL; the driver should refuse it
with 0AN01 itself. Both layouts; not new in 10a. Verified by a probe (row count unchanged).

## Proposed Solutions

1. Refuse a statement whose WITH holds a DML (SqlAst classification) with 0AN01 (small).

## Recommended Action

## Acceptance Criteria

- [x] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10a (26d93a2).
- 2026-10-27: not safe on a decoupled table at SNAPSHOT or SERIALIZABLE, raised to p1. On PostgreSQL
  through snsql (3b123eb), `BEGIN ISOLATION LEVEL REPEATABLE READ; WITH u AS (UPDATE warehouse.part SET
  qty = 77 WHERE id = 1 RETURNING *) SELECT * FROM u; COMMIT;` on a decoupled imported table returned the
  row and committed: `part.qty` went 10 -> 77 while `part_tx_metadata` stayed at tx_version 1, tx_state 3,
  so a ScalarDB transaction that read version 1 commits over it. Same at SERIALIZABLE. At READ COMMITTED,
  and on an inline table at every level, the write still lands on `rc_<t>` and fails with 55000.
  Unchecked: SQL Server's updatable CTE (`WITH c AS (SELECT ... FROM t) UPDATE c ...`) and Oracle's
  inline-view DML (`UPDATE (SELECT ... FROM t) SET ...`), which may reach the table the same way.
- 2026-10-27: fixed. `SqlAst.hasDmlInWith()` finds a WITH item that is an INSERT, UPDATE or DELETE,
  nested ones included; the router refuses such a statement with 0AN01 when it names a ScalarDB table,
  before any rewrite, and passes it through on other tables. Unit test in SqlAstTest; integration test
  on PostgreSQL at READ COMMITTED, SNAPSHOT and SERIALIZABLE on the decoupled m1.s
  (`RoutingIntegrationTest.aWithItemThatWrites_isRefusedOnAScalarDbTable_atEveryLevel`). SQL Server's
  and Oracle's DML through a CTE or an inline view still unchecked.
