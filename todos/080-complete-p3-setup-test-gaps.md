---
status: complete
priority: p3
issue_id: "080"
tags: [code-review, testing]
dependencies: []
---

# Setup test gaps

## Problem Statement

The setup test proves less than it claims.

## Findings

- It never asserts that the in-flight index exists or is partial.
- Only the `before_tx_id` arm of the view's WHERE is exercised: no state 2, no `before_tx_version` arm (the NULL-row prepare of todo 069), no composite key, no key-only table, no LOB column.
- `contains("3 rows")` and `contains(" 0 rows")` also match 13 and 10.

## Proposed Solutions

### Option 1: Tighten

**Approach:** Assert the index via `Engine.setupObjects`; add the missing view cases on every engine; match the report exactly (or use todo 076's results).

**Pros:** Proves its claims.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. SetupIntegrationTest asserts the index through SetupObjects, covers key-only, composite-key and created tables, states 1 and 2, the NULL-row prepare and an in-flight insert, exact rows-stamped counts, and teardown.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] Each view case and the index are asserted on all six configurations.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
