---
status: pending
priority: p3
issue_id: "302"
tags: [read, metadata, postgresql, oracle]
dependencies: []
---

# Result columns lose NOT NULL through the split read

## Problem Statement

`ResultSetMetaData.isNullable` reports `columnNullable` (1) or `columnNullableUnknown` (2) through
the driver for a column the database reports as `columnNoNulls` (0) on the plain table, such as a
primary key. The split read's `UNION ALL`, and the `rc_T` views, hide the source column from the
database's own metadata. An application or ORM that generates code or validation from the result's
metadata sees every column as nullable.

## Findings

- The read corpus (testing plan T1), 2026-10-21: PostgreSQL 152 of 184 comparisons, Oracle 112 of
  166; MySQL and MariaDB 12-16; SQL Server none. Rows are equal in every case.
- `build/coverage/<engine>.md` lists each statement with "pass, nullability [...]".

## Proposed Solutions

### Option 1: report the source column's nullability
The driver knows each output column's source when it is a plain column reference of a ScalarDB
table: wrap the vendor's `ResultSetMetaData` and answer `isNullable` from the catalog for those.
**Effort:** Small to Medium.

### Option 2: accept and document
Nullability is advisory in JDBC; document the difference. **Effort:** Small.

## Recommended Action

## Acceptance Criteria

- [ ] The corpus reports no nullability difference for plain column references, or the
      difference is documented.

## Work Log

### 2026-10-21 - Found by the read corpus

**By:** Claude Code
- `CorpusIntegrationTest` (T1), first run on the five engines.

## Resources

- docs/plans/testing.md, milestone T1.
