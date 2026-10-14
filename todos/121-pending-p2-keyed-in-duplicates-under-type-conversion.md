---
status: pending
priority: p2
issue_id: "121"
tags: [code-review, correctness, split-read, mysql, sqlserver, oracle]
dependencies: []
---

# A keyed IN placed INNER returns a row twice when conversion makes two keys equal

## Problem Statement

An IN subquery that selects the single-column key is placed INNER (two copies, no check between them),
on the claim that each key value is in exactly one piece. That holds for the key values, not for what
they compare equal to: MySQL, SQL Server and Oracle compare a character key with a number as numbers,
so '1' (committed) and '01' (in flight) both equal `c.x = 1`, and the outer row comes back from both
copies. The IN would return it once.

## Findings

- `select c.id from c where c.x in (select o.id from o where o.s = 1)`, `o.id` VARCHAR key, `c.x` INT
  (or a placeholder bound as an int).
- Emitted: `... in(select o.id from c_o o ...) UNION ALL ... in(select o.id from f_o o ...)`.
- MySQL 8.4 run: original returns `1` once, the rewrite `1, 1`.
- PostgreSQL rejects text = int, so it is not affected.
- Also on MySQL: a string or float `x` against BIGINT keys above 2^53 collides through double
  conversion.
- Placement: `SqlAst.java:1837-1843`; `selectsKey` at `:1933`.

## Proposed Solutions

### Option 1: INNER only for safe key types
- Place a keyed IN INNER only when the key column is an integer type (or always on PostgreSQL), else
  SEMI_JOINED. The correlated NOT EXISTS uses the same comparison as the IN, so it stays exact (checked
  on MySQL).
- **Effort:** Small (the key's type is in TableInfo). **Risk:** Low; a little slower for text keys.

### Option 2: always SEMI_JOINED
- Simplest; loses the INNER speed-up measured in 3c (PostgreSQL key IN).

## Recommended Action

## Acceptance Criteria

- [ ] A text-keyed IN is not placed INNER on MySQL, MariaDB, SQL Server and Oracle.
- [ ] Integration case with '1' committed and '01' in flight returns the row once.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of 9a00150; reproduced on MySQL 8.4 against logical tables.
