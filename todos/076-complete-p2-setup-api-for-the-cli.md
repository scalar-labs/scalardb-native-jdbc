---
status: complete
priority: p2
issue_id: "076"
tags: [code-review, architecture, setup]
dependencies: []
---

# Setup api for the cli

## Problem Statement

`Setup.run` returns lines of text and stops at the first failing table, losing the report for the tables already done, and reads a catalog snapshot that may be up to a TTL old; milestone 9's command line needs per-table results.

## Findings

- A name over the limit or a backfill timeout on one table leaves the following tables untouched and the earlier results unreported (architecture).
- `decoupledTables()` serves the cached snapshot, so a long-lived Catalog misses tables created in the last TTL (correctness).
- Errors use plain `SQLException` with 55000, 42622 and 57014, against design section 1's rule that driver errors are standard subclasses; an incomplete backfill shares 55000 with "not in autocommit".
- Todo 026 already covers CLI arguments, dry run and check mode.

## Proposed Solutions

### Option 1: Per-table results

**Approach:** `run(catalog, connection, tables)` returns a result per table (actions, rows stamped, error) and continues after a failure; invalidate the catalog first; `SQLSyntaxErrorException` for a name too long, `SQLTransientException` for an incomplete backfill.

**Pros:** A base the CLI can build on without changing the API.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. `Setup.run(catalog, connection, which, wait)` returns a `Result` per table (actions, rows stamped, error), continues after a failure, and reloads the catalog first; errors are SQLSyntaxErrorException (name too long), SQLTransientException (incomplete backfill), SQLNonTransientException (not autocommit, foreign view).

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] A failure on one table does not stop the others; each table's outcome is reported.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Confirmed in the code: Setup.run returns strings, stops at the first exception, and decoupledTables() serves the cached snapshot.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
