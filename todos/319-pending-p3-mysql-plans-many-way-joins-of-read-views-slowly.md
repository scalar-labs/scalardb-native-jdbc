---
status: pending
priority: p3
issue_id: "319"
tags: [performance, mysql, mariadb, read-views]
dependencies: []
---

# MySQL spends minutes planning a join of many read views

## Problem Statement

sqllogictest's select5 joins up to 17 tables. Read through the driver on MySQL, each table is its
read view `rc_T`, a `UNION ALL`, and the server stays in the `statistics` state for minutes per
query at full CPU: the join-order search (`optimizer_search_depth` 62 by default) grows
exponentially with the tables. PostgreSQL switches to its genetic optimizer from 12 tables and
finished select5 in minutes.

## Findings

- MySQL 8.4, T4 run 2026-10-22: one query in `statistics` for 163 s, the select5 file running for
  over six hours.
- Not measured: MariaDB (assumed alike, skipped too); the plain query's planning time.

## Proposed Solutions

### Option 1: document it
Joins of many ScalarDB tables on MySQL and MariaDB: set `optimizer_search_depth` low (or 0, auto).
**Effort:** Small.

### Option 2: lower `optimizer_search_depth` for the statement
A `SET_VAR` hint on MySQL past some table count. **Effort:** Small; changes plans.

## Acceptance Criteria

- [ ] Measured against the plain query, and documented or handled.

## Work Log

### 2026-10-22 - Found by T4 sqllogictest

**By:** Claude Code

- The harness skips select5 on MySQL and MariaDB.

## Resources

- docs/plans/testing.md, milestone T4.
