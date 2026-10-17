---
status: pending
priority: p3
issue_id: "212"
tags: [code-review, mysql, postgresql, mariadb]
dependencies: []
---

# A column reorder outside ScalarDB leaves a stale column order in a cached plan

## Problem Statement

Writes.columnOrder is read at plan time and kept with the route: after a native
`ALTER TABLE r MODIFY x INT FIRST`, a cached `INSERT INTO r VALUES (?, ?)` is refused on MySQL
(0AN01 "reads back as 0 rows") until the catalog TTL ends; with catalogTtlMs=0 it works. On
PostgreSQL/MariaDB a duplicate may be misclassified meanwhile. No wrong-data path found (201's check
catches it). Verified (rv6x).

## Proposed Solutions

### Option 1
Document: a table changed outside ScalarDB is seen after the catalog TTL.

### Option 2
Re-plan on the 0AN01 read-back failure once.

## Acceptance Criteria

- [ ] Documented or re-planned

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 8f902dc (the fixes of todos 200-206).
