---
status: complete
priority: p1
issue_id: "069"
tags: [code-review, data, setup, testing]
dependencies: []
---

# Backfill waits on every in flight insert

## Problem Statement

The backfill's completion test counts every in-flight ScalarDB insert, so on a table with insert traffic, or with one orphaned prepared insert, the setup never completes; the setup test leaves such a row behind, which can fail other test classes.

## Findings

- `Setup.java` `remaining`, third term: `tx_state IN (1, 2) AND before_tx_id IS NULL`. An insert prepare writes no `before_*` columns, so every in-flight insert matches.
- Verified in ScalarDB 3.19.1 `PrepareMutationComposer.createBeforeColumns`: when the current `tx_version` is NULL, the prepare writes `before_tx_version = 0`. So a prepare over a deemed-committed NULL row, the case the design means, is exactly `before_tx_id IS NULL AND before_tx_version IS NOT NULL`.
- ScalarDB recovers lazily (on read), so an orphaned prepared insert blocks the backfill indefinitely; every run ends in 55000.
- `SetupIntegrationTest` ends with row 4 of `su.imp` in state 1 and `before_*` NULL. `RoutingIntegrationTest` runs `Setup.run` over every table of the shared database in `@BeforeAll`; if it runs after the setup test, it waits 60 s and fails. Order-dependent (security and correctness reviews).

## Proposed Solutions

### Option 1: Narrow the condition, scope the tests

**Approach:** Third term becomes `... AND before_tx_id IS NULL AND before_tx_version IS NOT NULL`. `Setup` accepts the tables to set up (default: all decoupled tables); tests pass their own. The setup test cleans up its in-flight rows. Add a test with an in-flight insert that does not block the backfill.

**Pros:** Exact, small.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. The wait condition is now `tx_state IN (1, 2) AND before_tx_id IS NULL AND before_tx_version IS NOT NULL`; `Setup.run` takes a predicate for the tables; the tests set up only their own schema and clean up in-flight rows. SetupReviewIntegrationTest.todo069 and SetupIntegrationTest (NULL-row prepare waited for, in-flight insert not) pass.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] An in-flight ScalarDB insert does not delay the backfill (integration test).
- [x] Setup can run on a given list of tables; tests do not touch other classes' tables.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Reproduced on PostgreSQL 16 (SetupReviewIntegrationTest.todo069...): one in-flight insert with no before image makes Setup fail with 55000. The cross-class test order dependence is confirmed by reading (SetupIntegrationTest leaves row 4 in flight; RoutingIntegrationTest runs Setup over the shared database), not reproduced.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
