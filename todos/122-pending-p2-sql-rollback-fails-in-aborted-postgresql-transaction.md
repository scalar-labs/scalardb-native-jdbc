---
status: pending
priority: p2
issue_id: "122"
tags: [code-review, reliability, postgresql, transactions]
dependencies: ["106", "107"]
---

# SQL ROLLBACK / ROLLBACK TO SAVEPOINT fails in an aborted PostgreSQL transaction

## Problem Statement

After a statement fails in a PostgreSQL transaction, the application's own `ROLLBACK` or `ROLLBACK TO
SAVEPOINT s` sent as SQL can fail with 25P02, because the driver runs its own statement first on the
aborted transaction. The application cannot recover the transaction (or its savepoint retry breaks).
Happens with plain tables only.

## Findings

- JSqlParser classifies `ROLLBACK`, `COMMIT`, `SAVEPOINT s`, `ROLLBACK TO SAVEPOINT s` as `Kind.OTHER`,
  so `Router.decide` (`Router.java:291`) gives them `plansParallel = true`, and `beforeRun`
  (`:636-656`) runs `SHOW` or `SET max_parallel_workers_per_gather` before them.
- d33605e made `beforeRun` skip writes, so a failing DML no longer reads the setting first; the
  ROLLBACK is the first statement to touch it.
- Scenario C (reproduced): fresh connection or after `Connection.rollback()`; autocommit off; INSERT
  hits a duplicate key; `execute("ROLLBACK")` fails with 25P02 from the driver's SHOW.
- Scenario A (reproduced on a driver Router): a short split read sets 0; a DML fails;
  `execute("ROLLBACK TO SAVEPOINT s")` fails on the driver's `SET ... = 2`.
- Scenario B (reproduced; the part of todo 106 closed as not reproduced): `SELECT 1`; a failing
  `CREATE TABLE` marks the session stale in `ran()` although it failed; `decide("ROLLBACK")` refreshes
  the session with `SELECT current_schemas(false)` (`Session.java:131-153`, `Router.java:259`), which
  fails.
- `ran()` never calls `rolledBack()` / `transactionEnded()` for SQL ROLLBACK/COMMIT, so `workersOff` is
  wrong afterwards (performance only).
- `ReviewFindingsIntegrationTest`'s 106 case runs a plain SELECT before the failure, which hides this.

## Proposed Solutions

### Option 1
- Treat `Commit`, `RollbackStatement`, `SavepointStatement` (and RELEASE) as transaction control: no
  parallel planning, no session refresh, and `ran()` calls the transaction-end hooks.
- Make `beforeRun` best effort: on SQLException set `workersOff = null` and go on; the setting only
  affects speed.
- Refresh the session only for statements with table references.
- **Effort:** Small to Medium. **Risk:** Low.

## Recommended Action

## Acceptance Criteria

- [ ] Scenarios A, B and C as integration tests on PostgreSQL; the application's ROLLBACK succeeds.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of d719647..9a00150 (regression from d33605e for A and C).
