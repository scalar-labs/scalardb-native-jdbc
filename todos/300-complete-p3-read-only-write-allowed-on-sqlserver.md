---
status: complete
priority: p3
issue_id: "300"
tags: [code-review]
dependencies: []
---

# A write in a read-only transaction is refused on four engines, allowed on SQL Server

## Problem Statement

`setReadOnly(true)` (Spring's `@Transactional(readOnly = true)`) makes a write of a ScalarDB table fail
with 25006 on PostgreSQL, MySQL, MariaDB and Oracle; on SQL Server it succeeds, as mssql-jdbc ignores
read-only. Consistent with the vendor, inconsistent across engines (review of 9c).

## Proposed Solutions

1. Refuse it on SQL Server too, as todo 160's guard does where the vendor enforces it (small).

## Acceptance Criteria

- [x] Done

## Work Log

- 2026-10-20: found in the review of 9c.
- 2026-10-24: fixed with todo 324: the driver tracks `setReadOnly` itself, so a read-only
  connection anchors nothing and writes no ScalarDB table on SQL Server either (25006);
  `MoreWritesIntegrationTest` and `WriteIntegrationTest` now run the read-only cases there.
