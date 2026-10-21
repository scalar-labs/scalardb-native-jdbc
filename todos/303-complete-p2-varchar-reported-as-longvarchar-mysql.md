---
status: complete
priority: p2
issue_id: "303"
tags: [read, metadata, mysql, mariadb]
dependencies: []
---

# VARCHAR columns come back as LONGVARCHAR on MySQL and MariaDB

## Problem Statement

`SELECT id, name FROM c ORDER BY id`, where `name` is `VARCHAR(20)`, returns `name` with JDBC type
`LONGVARCHAR` (-1) through the driver and `VARCHAR` (12) on the plain table. ScalarDB stores the
before image of a text column as `LONGTEXT`, and the split read's `UNION ALL` of the data column and
the before-image column takes the wider type. Rows are equal; the type is not. Code that switches
on `getColumnType`, or an ORM mapping by it, sees another type than on the database alone.

## Findings

- The read corpus (testing plan T1), 2026-10-21: 16 of 166 comparisons on MySQL and on MariaDB,
  every statement that selects a `VARCHAR` column of a ScalarDB table, on both layouts.
- PostgreSQL, Oracle and SQL Server report the same type on both sides.

## Proposed Solutions

### Option 1: CAST the before image to the data column's type
In the in-flight copy, `CAST(m.before_name AS CHAR(20))` (MySQL's spelling of a VARCHAR cast)
gives the union the data column's type. The column's declared type is in the catalog.
**Effort:** Small; check the plans do not change (the CAST is in the select list only).

### Option 2: report the data column's type from the driver's metadata
As in todo 302. **Effort:** Medium.

## Recommended Action

Option 1, with the column's character set and collation: `CAST(m.before_c AS CHAR(n) CHARACTER SET cs) COLLATE coll`,
in the in-flight copy and in `rc_T`. A `CHAR(n)` column still reads as `VARCHAR`: MySQL has no cast to a
fixed-length CHAR.

## Acceptance Criteria

- [ ] The corpus passes on MySQL and MariaDB with column types equal to the plain table's.

## Work Log

### 2026-10-21 - Found by the read corpus

**By:** Claude Code
- `CorpusIntegrationTest` (T1), first run on the five engines.

## Resources

- docs/plans/testing.md, milestone T1; todo 302.

### 2026-10-21 - Fixed

**By:** Claude Code
- The catalog lists each `CHAR`/`VARCHAR` column's length, character set and collation on MySQL and
  MariaDB (`Engine.addBeforeTypes`); `TableInfo.beforeImage` casts the before image to it.
- The read corpus passes on MySQL and MariaDB, both layouts, nothing in flight and rows in flight.
  Existing `rc_T` views keep the old type until the setup is run again.
