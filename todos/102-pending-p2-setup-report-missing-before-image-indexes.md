---
status: pending
priority: p2
issue_id: "102"
tags: [setup, performance, split-read, indexes]
dependencies: ["096"]
---

# Setup reports data-table indexes with no before-image counterpart

## Problem Statement

The split read's in-flight copy filters on the before image (`before_x`). Without an index serving
that filter it reads every in-flight row of the table, so its cost grows with the in-flight count: at
10k rows in flight 3-6x on PostgreSQL and Oracle, up to 365x on SQL Server. ScalarDB creates a
`before_x` index for each of its own (single-column) secondary indexes, unless index-eventually-
consistent reads are enabled, and with it the selective reads return to their cost at 100 rows in
flight. It does not mirror indexes created outside ScalarDB: multi-column indexes, or native indexes on
an imported table.

## Findings

- ScalarDB 3.19.1: `ConsensusCommitUtils.buildTransactionTableMetadata` and
  `ConsensusCommitAdmin.createIndex` add `before_x` for every indexed non-key `x`, unless
  `isIndexEventuallyConsistentReadEnabled`. Commit and rollback set every `before_*` to NULL
  (`CommitMutationComposer`, `RollbackMutationComposer`), so the plain index holds in-flight rows only.
- Measured: docs/benchmarks/read-overhead.md, "ScalarDB's own before-image indexes, and where the
  per-row check pays". No measurable write cost from the index.

## Proposed Solutions

### Option 1: report, create on request (recommended)
The setup lists each data-table index whose columns have no `before_*` index with the same columns, and
each table whose before-image indexes are missing, with the DDL to add each. An explicit option creates
them; the driver then owns them and drops them on teardown. In the classic layout (`before_*` on `T`)
report only, since section 1 already warns about indexes covering metadata columns there.
- **Pros:** the driver does not change ScalarDB's schema unasked; the operator sees the cost.
- **Effort:** Small to Medium. **Risk:** Low.

### Option 2: always create them
- **Cons:** every index adds write cost to ScalarDB's prepare and commit; the metadata table is
  ScalarDB's.

## Recommended Action

Option 1 (agreed 2026-10-12).

## Acceptance Criteria

- [ ] The setup's report names each uncovered index and prints its DDL, on all five engines.
- [ ] The opt-in creates them online where the engine can, and teardown drops only those it created.
- [ ] Integration test: a native two-column index on an imported table is reported; with the option it
      is created and used by the in-flight copy.

## Work Log

### 2026-10-12 - Recorded

**By:** Claude Code
- From the 096 measurements and the check of ScalarDB's index and commit code.
