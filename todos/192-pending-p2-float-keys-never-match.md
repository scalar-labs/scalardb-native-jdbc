---
status: pending
priority: p2
issue_id: "192"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# FLOAT keys never match the uncast UNION ALL key list: an endless 40001

## Problem Statement

`Names.keyValues` on MySQL/MariaDB binds keys uncast (Writes.java ~1538). A FLOAT key read as Java
Float 0.1f binds as 0.1 and compares as a double, missing the stored float. Verified on both engines
with an imported `f (k FLOAT PRIMARY KEY)`: `UPDATE f SET x = 2 WHERE x = 0` and `DELETE FROM f WHERE
x >= 0` abort with 40001 ("deleted after it was read"), so a retrying application loops forever.
PostgreSQL casts each value and is fine.

## Proposed Solutions

### Option 1
Cast each placeholder to the column's MySQL type in the UNION ALL form (CAST supports a limited set:
use `CAST(? AS FLOAT)`/`DOUBLE`/`DECIMAL(p,s)`/`DATETIME(n)` etc. from the result metadata).

### Option 2
Refuse FLOAT/REAL key columns on MySQL/MariaDB (ScalarDB discourages approximate keys).

## Acceptance Criteria

- [ ] UPDATE/DELETE of a FLOAT-keyed table works or is refused up front, never a 40001 loop

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
