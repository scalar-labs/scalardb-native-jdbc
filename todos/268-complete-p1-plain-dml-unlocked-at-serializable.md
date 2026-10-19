---
status: complete
priority: p1
issue_id: "268"
tags: [code-review, sqlserver, serializable, isolation]
dependencies: []
---

# Plain-table DML reads unlocked at SERIALIZABLE on SQL Server: write skew between drivers

## Problem Statement

Router.java ~l.509 adds HOLDLOCK only for `kind == SELECT`; its reason is MySQL's (a write's reads take
next-key locks at REPEATABLE READ). At SNAPSHOT SQL Server reads a DML statement's subqueries, joins
and INSERT ... SELECT sources from row versions, unlocked. README 117-118 claims every read is held.

## Findings

Through the driver, two connections at SERIALIZABLE, autocommit off, `pp.p` = (1,1),(2,1):
- `UPDATE pp.p SET v = 0 WHERE id = 1 AND (SELECT SUM(v) FROM pp.p) >= 2` / the same with id = 2:
  neither blocks, both commit, p = 1→0, 2→0.
- `DELETE ... WHERE id = n AND (SELECT COUNT(*) FROM pp.p) = 2`: both commit, table empty.
- `UPDATE p ... FROM pp.p p JOIN pp.q q ...` mirrored: both commit.
- `INSERT INTO pp.q SELECT 10/11, 1 WHERE NOT EXISTS (SELECT 1 FROM pp.q WHERE id >= 10)`: both commit.
SQL Server's own SERIALIZABLE blocks or deadlocks in all four. A DML on a ScalarDB table is
single-table and unaffected. No test covers DML in sqlServerPlainTables.

## Proposed Solutions

1. HOLDLOCK on every table a plain DML reads other than its target (the target's own predicate is
   read under U/X locks, but its range is not: HOLDLOCK on the target too, or UPDLOCK+HOLDLOCK) —
   probe what SQL Server's SERIALIZABLE takes for each form (medium).
2. Refuse plain DML that reads another table at this level (small, restrictive).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 5acbf90 (8c3), verified as stated above.
- 2026-10-19: fixed. Plain DML at SERIALIZABLE on SQL Server goes through holdingEveryTable too (target and every table it reads; probed: every UPDATE/DELETE/INSERT form takes the hint, and the mirrored statements then block). Tested in SnapshotIntegrationTest.sqlServerPlainTables (three mirrored write pairs between two drivers; fails without the fix) and TableHintsTest.
