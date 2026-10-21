---
status: pending
priority: p3
issue_id: "313"
tags: [read, split-read, metadata, postgresql]
dependencies: []
---

# A whole-row reference of a split table reports another type

## Problem Statement

`select * from int4_tbl i left join lateral (select coalesce(i) from int2_tbl j where i.f1 = j.f1)
k on true` returns the column `coalesce` as JDBC type 1111 (OTHER, PostgreSQL's anonymous `record`)
through the driver, and 2002 (STRUCT, the table's own composite type `int4_tbl`) on the database.
The derived table that replaces `int4_tbl i` has no named row type. Values match.

## Findings

- T4, PostgreSQL's `join.sql` (REL_16_15), both layouts.
- Related: todo 110 (an unqualified table name read as its whole row).

## Proposed Solutions

### Option 1: document
A whole-row reference of a ScalarDB table is a `record`. **Effort:** Small.

### Option 2: cast back
Where the split read replaces a table whose whole row the statement references, cast the
reference to the table's type: `ROW(...)::int4_tbl`. **Effort:** Medium.

## Acceptance Criteria

- [ ] The statement passes in T4, or the README states the difference.

## Work Log

### 2026-10-21 - Found by T4

**By:** Claude Code
