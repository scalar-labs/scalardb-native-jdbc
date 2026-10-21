---
status: complete
priority: p2
issue_id: "304"
tags: [read, metadata, mysql, mariadb]
dependencies: []
---

# BOOLEAN columns read as TINYINT through the split read on MySQL and MariaDB

## Problem Statement

A `BOOLEAN` column, MySQL's `TINYINT(1)`, comes back through the driver as JDBC `TINYINT` (-6), and
`getObject` returns an `Integer`, where the database alone reports `BIT` (-7) and returns a
`Boolean`. Connector/J and MariaDB Connector/J treat a `TINYINT` as boolean only when its display
width is 1 (`tinyInt1isBit`), and MySQL's `UNION ALL` reports width 4 even when both arms are
`TINYINT(1)`. The split read and the `rc_T` views are such unions. An ORM mapping `getObject` to a
boolean field, or code switching on `getColumnType`, sees another type than on the database.

## Findings

- Probe, MySQL 8 (m8-mysql), `SELECT vip FROM b` reports TINY length 1; `SELECT vip FROM b UNION ALL
  SELECT before_vip FROM bm` reports TINY length 4, both columns `BOOLEAN`.
- The write corpus (testing plan T3), 2026-10-21: every table comparison of the corpus's `c` on
  MySQL and MariaDB, `[vip:-6]` against `[vip:-7]`; the read corpus's `columns.sql` (T1).
- No CAST target gives `TINYINT(1)`.

## Proposed Solutions

### Option 1: the driver's ResultSetMetaData and getObject answer for the source column
The driver knows a ScalarDB table's columns; for a column of the result that is a plain reference
to a `TINYINT(1)` column, report `BIT` and return `Boolean`. Shared with todos 302 and 303
(nullability, types). **Effort:** Medium.

### Option 2: document
Read booleans with `getBoolean` on MySQL and MariaDB. **Effort:** Small.

## Recommended Action

## Acceptance Criteria

- [x] The read corpus's `columns.sql` passes on MySQL and MariaDB, or the difference is documented.

## Work Log

### 2026-10-21 - Found by the write corpus

**By:** Claude Code
- `WriteCorpusIntegrationTest` (T3), first run; confirmed by a probe of `UNION ALL` on MySQL 8.

## Resources

- docs/plans/testing.md, milestones T1 and T3; todos 302, 303.

### 2026-10-21 - Fixed (Option 1)

- `SqlAst.selectSources` names the table column each returned column plainly is (a column
  reference, `*`, `t.*` of a table in the FROM clause of a plain SELECT); `Router` keeps the
  positions of ScalarDB `BOOLEAN` columns on MySQL and MariaDB (`Route.booleans`), with a read of one
  such column (`WHERE 1 = 0`) whose metadata the vendor reports as for the column itself, read once
  per connection before a statement runs. `Proxies.BooleanColumns` answers `getObject` with a
  `Boolean` and the metadata as for the column.
- Left as `TINYINT`: a column through a derived table, a CTE, or a `*` that also covers a plain
  table, whose columns the driver does not know (README).
- Read corpus (T1, T2), write corpus (T3) and the full integration suites pass on MySQL and MariaDB.
