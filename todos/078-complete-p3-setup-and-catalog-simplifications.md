---
status: complete
priority: p3
issue_id: "078"
tags: [code-review, quality, simplicity]
dependencies: []
---

# Setup and catalog simplifications

## Problem Statement

Name and key building is duplicated, and several constructors and overloads exist only for convenience.

## Findings

- The index key is built in `Setup.table` and `Snapshot.marked` separately (fold into todo 070's shared key function).
- `Engine.setupQueries` returns a `String[]`; per-engine format strings would remove the switch.
- The legacy `Snapshot` constructor hard-codes PostgreSQL and an empty set, so `CatalogTest` never tests the set-up path; pass the engine at the three call sites.
- `TableInfo`'s copy constructor and `withSetUp` exist to change one flag; compute it before construction.
- `isSetUp()` defaults to true for classic ScalarDB tables; default to `!isScalarDb()` when classic support arrives.
- `Setup`: repeated quoting of fixed columns, two builds of the same five-column list, `metadataTable` null check that cannot fire, unused three-argument `run` (keep if todo 076 uses it), public `BACKFILL_TX_ID` used only inside.

## Proposed Solutions

### Option 1: Apply with 070 and 076

**Approach:** Most items touch the same lines.

**Pros:** Less code.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. Key building lives only in `SetupObjects`; the legacy Snapshot constructor and TableInfo's copy constructor are gone (set-up flag computed before construction); naming moved to `Engine.readViewName/inflightIndexName`; Setup quotes its fixed columns once and uses format templates; `BACKFILL_TX_ID` package-private. Classic tables still report set up until classic support adds their setup.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] No duplicated key building; `CatalogTest` covers a set-up table.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
