---
status: complete
priority: p3
issue_id: "077"
tags: [code-review, operations, design]
dependencies: []
---

# View drift and scalardb admin operations

## Problem Statement

The view's columns are fixed when it is created, and on PostgreSQL the view blocks ScalarDB's own admin operations on the table; nothing detects the drift or undoes the setup.

## Findings

- ScalarDB `addNewColumnToTable` leaves `rc_T` without the new column while the table still counts as set up; milestone 3 then fails with an unknown column or returns fewer columns (architecture).
- PostgreSQL: the view blocks drop table, drop column, alter column type and `dropNamespace`; `CREATE OR REPLACE VIEW` cannot remove a column. Other engines: the view becomes invalid and fails at run time instead of with 55000.

## Proposed Solutions

### Option 1: Shape check plus teardown

**Approach:** The column comparison of todo 070 catches drift (not set up until the setup runs again). `Setup` gets a teardown (drop view and index) and design section 3 lists the ScalarDB admin operations that need teardown and setup again.

**Pros:** Drift is detected; operators have a way out.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. `Setup.teardown` drops the view (only one with the table's columns) and the index; design section 3 lists the ScalarDB operations that need it (dropTable, dropNamespace on PostgreSQL) and notes that ScalarDB refuses column changes on decoupled tables.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] A column added through ScalarDB makes the table not set up until the setup runs again.
- [x] Design section 3 lists the admin operations and the teardown.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Partly real. PostgreSQL 16 refuses DROP TABLE and DROP COLUMN on a table with the view (reproduced). But drift through ScalarDB cannot happen: ConsensusCommitAdmin in 3.19.1 calls throwIfTransactionMetadataDecouplingApplied in addNewColumnToTable, alterColumnType, dropColumnFromTable, renameColumn, renameTable and repairTable. Drift needs a direct ALTER TABLE; dropTable and dropNamespace through ScalarDB are the operations the view blocks.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
