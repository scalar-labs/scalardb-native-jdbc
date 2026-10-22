---
status: complete
priority: p1
issue_id: "316"
tags: [read, catalog, split-read, rc-views, import]
dependencies: []
---

# `SELECT *` lists an imported table's key columns first, not in the table's order

## Problem Statement

For a table imported into ScalarDB whose primary key is not its leading column,
`SELECT * FROM t` through the driver returns the key columns first: a table
`t1(a1 INT, ..., x1 VARCHAR(30), slt_rowid INT PRIMARY KEY)` reads as
`[slt_rowid, a1, ..., x1]` where the database returns `[a1, ..., x1, slt_rowid]`. An application
reading columns by position (`getInt(1)`), `INSERT ... SELECT *`, or a tool mapping by index reads
the wrong values. Both layouts, nothing in flight.

## Findings

- T4 sqllogictest (`SqlLogicIntegrationTest`) on PostgreSQL, first run: every `SELECT *` of a
  table given its key last failed so (86 of 1,500 queries, 100 per file).
- `TableInfo.dataColumns()` is documented "in ordinal order", but the order is that of ScalarDB's
  metadata table (`ordinal_position` in `Catalog`), which lists the partition key, then the
  clustering key, then the other columns. ScalarDB creates its own tables in that order, so they
  agree; an imported table keeps its own order.
- The split read emits its copies with that column list (`SplitRead.columnsRead`, `emitSplit`), and
  `Setup` creates the `rc_T` views with it (`Setup.java` near line 480), so both routes reorder.
- Writes are not affected: an INSERT without a column list reads the table's own columns
  (`Writes.columns(vendor, ...)`) in the decoupled layout and is refused in the inline layout.

## Proposed Solutions

### Option 1: the catalog reads the data table's column order
`Catalog` reads each ScalarDB data table's columns in their ordinal order
(`DatabaseMetaData.getColumns` per namespace, or the engine's catalog views) and orders
`dataColumns` so. `Setup.viewMatches` then sees views made in the old order as different:
recreate them (setup already replaces a view it owns), and say so in the upgrade notes.
**Effort:** Medium.

### Option 2: keep ScalarDB's order, map `*` back
Leave the views; wrap `*` reads with an outer select in the table's order. **Effort:** Medium;
more rewriting, every route.

## Acceptance Criteria

- [x] `SELECT *` of an imported table with its key not first returns the table's order, through the
  split read and `rc_T`, both layouts, all engines (sqllogictest).
- [x] sqllogictest with `slt_rowid` last again. A T1 corpus table with its key last is left to the
  corpus's owner.
- [x] Existing setups: a read view in the key-first order is the setup's own; the setup drops and
  recreates it (`SetupReviewIntegrationTest.todo316_...`). Until the setup runs again the table
  counts as not set up: the next release notes must say to run it.

## Work Log

### 2026-10-22 - Found by T4 sqllogictest

**By:** Claude Code
- `SqlLogicIntegrationTest` puts `slt_rowid` first for now, so that the rest of its findings show.

### 2026-10-22 - Fixed (option 1)

**By:** Claude Code
- `Engine.addColumnOrder` lists the columns of each table statements name (the data table, the
  virtual table, the inline table) in the engine's catalog with the setup's objects;
  `SetupObjects.order` sorts `dataColumns` so in `Catalog`.
- `Setup`: a view holding the table's columns in another order is dropped and recreated (CREATE OR
  REPLACE cannot reorder on PostgreSQL).
- sqllogictest puts `slt_rowid` last again: no `SELECT *` failure on any engine.

## Resources

- docs/plans/testing.md, milestone T4.
