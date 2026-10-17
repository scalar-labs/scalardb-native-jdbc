---
status: complete
priority: p2
issue_id: "215"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# Some FLOAT key values abort with 40001 on every attempt

## Problem Statement

Writes.java ~892 `Keys.read` uses `rs.getObject` on the read-back/RETURNING key; the server sends a
FLOAT as 6-digit text ("0.12"), so 0.120000005f comes back as 0.11999999731779099, the stamp's
`IN (...)` matches no row and `stamp` throws 40001 "metadata in flight" (~1429). Retries never
succeed. 0.1f, 0.2f, 0.5f, 1.7f, 1.12f work. Verified on MySQL and MariaDB (rv8). Pre-existing.

## Proposed Solutions

### Option 1
Read FLOAT key columns with getFloat (exact to the stored float) and bind as its exact double.

## Acceptance Criteria

- [ ] setFloat(0.1f + 0.02f) on a FLOAT key INSERT works on MySQL and MariaDB (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of b963158 (the fixes of todos 207-211).

### 2026-10-17 - Fixed

**By:** Claude Code
- getFloat cannot help (six digits are lost on the wire). Key columns of ScalarDB FLOAT type (from the metadata table, TableInfo.floatColumns) are selected as (k + 0E0) on MySQL and MariaDB wherever the driver reads keys: Names.readColumns and Router.versions. Test: INSERT, locking read, UPDATE, DELETE of 0.1f + 0.02f.
