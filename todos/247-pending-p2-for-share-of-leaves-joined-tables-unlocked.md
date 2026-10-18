---
status: pending
priority: p2
issue_id: "247"
tags: [code-review, mysql, serializable, locking]
dependencies: []
---

# `FOR SHARE OF t` / `FOR UPDATE OF t` leaves the block's other tables unlocked at SERIALIZABLE on MySQL

## Problem Statement

`SqlAst.lockingEverySelect` (~1018-1022) skips every block whose `getForMode()` is set. MySQL 8's
`OF tbl_name` limits a locking clause to the named tables, so in
`SELECT * FROM t JOIN u USING (a) FOR SHARE OF t` the rows of `u` are read in the REPEATABLE READ
snapshot, unlocked. The engine's own SERIALIZABLE locks `u` there, so the driver's SERIALIZABLE is
weaker than the README's "every SELECT ... takes shared locks held to commit, as the engine's own
SERIALIZABLE would".

## Findings

- SqlAst probe (scratchpad `LockProbe`, MYSQL): `SELECT * FROM t JOIN u USING (a) FOR SHARE OF t`,
  `... FOR UPDATE OF t` and `... FOR SHARE OF t, u` are all routed unchanged.
- MySQL 8.4 (m8-mysql), session A runs `BEGIN; SELECT * FROM t JOIN u USING (a) FOR SHARE OF t;` and
  holds; session B runs `UPDATE u SET a=a WHERE a=1` with `innodb_lock_wait_timeout=1`:
  - A at REPEATABLE READ (what the driver runs SERIALIZABLE at): B succeeds, `u` is not locked.
  - A at native SERIALIZABLE: B fails with 1205, `u` is locked.
- MariaDB has no `OF` form, so this is MySQL only.

## Proposed Solutions

### Option 1: refuse a locking clause with `OF` at SERIALIZABLE on MySQL
When `getForMode() != null` and the clause names tables (JSqlParser's `getForUpdateTable`/`OF` list)
that do not cover every table of the block, return null. Small, fails closed.

### Option 2: add the missing tables to the `OF` list
Rewrite `OF t` to `OF t, u` (with the block's own aliases). More code; the spelling must follow the
aliases, and a mixed `FOR UPDATE OF t FOR SHARE OF u` (which JSqlParser refuses today) stays out.

## Acceptance Criteria

- [ ] At SERIALIZABLE on MySQL, `SELECT * FROM t JOIN u USING (a) FOR SHARE OF t` either locks `u`
      or is refused with 0AN01
- [ ] SqlAstTest covers `FOR SHARE OF` and `FOR UPDATE OF`

## Work Log

- 2026-10-18: filed by the focused review of 45afd86 and e24a850 (probe on MySQL 8.4).
