---
status: pending
priority: p3
issue_id: "321"
tags: [read, result-metadata, mysql, mariadb]
dependencies: []
---

# A few integer results report another type through the driver on MySQL and MariaDB

## Problem Statement

On MySQL, `SELECT DISTINCT - 9 DIV col2 AS col2 FROM tab1` reports BIGINT through the driver where
the plain table gives INT; on MariaDB, `SELECT MAX( - 71 ) ...` and `MAX(82) * 3` report INT where
the plain table gives BIGINT, and the reverse for `MAX(...) DIV 89`. The values match.

## Findings

- T4 sqllogictest, 2026-10-22: 4 queries on MySQL, 6 on MariaDB, in random/aggregates and
  random/select.
- Likely the read views' UNION ALL or the split read's lift deriving the type anew; not traced.

## Proposed Solutions

### Option 1: trace the route of each and cast where the type is known
**Effort:** Medium.

### Option 2: leave it
`getLong`/`getInt` read either. **Effort:** none.

## Acceptance Criteria

- [ ] The ten queries report the plain table's type, or the case is documented.

## Work Log

### 2026-10-22 - Split out of todo 320

**By:** Claude Code

## Resources

- docs/plans/testing.md, milestone T4; todo 320.
