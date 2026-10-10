---
status: complete
priority: p2
issue_id: "074"
tags: [code-review, performance, setup, operations]
dependencies: []
---

# Index ddl blocks writes

## Problem Statement

The in-flight index is built with a plain CREATE INDEX, which blocks ScalarDB's writes to the metadata table for the whole build on PostgreSQL and SQL Server, and can copy the table on MySQL.

## Findings

- PostgreSQL: SHARE lock for the build. `CREATE INDEX CONCURRENTLY` works in autocommit; a failed build leaves an INVALID index (todo 070 checks `indisvalid`).
- SQL Server: offline build takes a table S lock; `WITH (ONLINE = ON)` needs Enterprise/Azure (`SERVERPROPERTY('EngineEdition') IN (3, 5, 8)`). A filtered index also makes every session that writes the metadata table need QUOTED_IDENTIFIER, ANSI_NULLS, ANSI_PADDING, ANSI_WARNINGS ON (error 1934 otherwise).
- Oracle: `ONLINE` (Enterprise only). A function-based index on `CASE WHEN tx_state IN (1, 2) THEN id END` would behave as a partial index.
- MySQL/MariaDB: append `ALGORITHM=INPLACE, LOCK=NONE` and set a short `lock_wait_timeout`.
- PostgreSQL: the partial index's predicate on `tx_state` disables HOT for ScalarDB's state changes (measured: 0 HOT updates of 200k versus 114,912 without); unavoidable, record it.

## Proposed Solutions

### Option 1: Online forms per engine

**Approach:** CONCURRENTLY on PostgreSQL; ONLINE where the edition allows on SQL Server and Oracle; INPLACE/LOCK=NONE on MySQL/MariaDB; document the SQL Server session options and the PostgreSQL HOT cost in design section 3.

**Pros:** No write outage on a live system.

**Cons:** Edition checks.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. CONCURRENTLY on PostgreSQL (dropping an invalid leftover first), ALGORITHM=INPLACE LOCK=NONE on MySQL/MariaDB, ONLINE on SQL Server editions 3/5/8, ONLINE with a fallback on ORA-00439 on Oracle; design section 3 records the SQL Server session options and the HOT cost.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] Index creation does not block concurrent writes to the metadata table (PostgreSQL test).
- [x] Design section 3 records the SQL Server session options and the HOT cost.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Reproduced on PostgreSQL 16: an insert into the metadata table waited on CREATE INDEX and failed with lock timeout (in use the block lasts for the build). The SQL Server, Oracle and MySQL online forms and the HOT cost are from the reviewers, not reproduced.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
