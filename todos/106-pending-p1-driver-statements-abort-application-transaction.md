---
status: pending
priority: p1
issue_id: "106"
tags: [code-review, correctness, postgresql, mysql]
dependencies: []
---

# The driver's own statements on the application's connection can fail or abort its transaction

## Problem Statement

Routing now runs statements of its own on the application's connection. These are the PostgreSQL
`EXPLAIN (GENERIC_PLAN)`, `SHOW` and `SET max_parallel_workers_per_gather`, and the MySQL/MariaDB
in-flight `COUNT`. On PostgreSQL a failure inside a transaction aborts the application's
transaction, for a statement that would have run fine. All four reviewers who looked found the
`EXPLAIN` case.

## Findings

- **EXPLAIN (verified), P1.** `jdbc/Router.java:397-428`.
  - `EXPLAIN (GENERIC_PLAN)` cannot infer untyped parameters, so `WHERE ? IS NULL OR c = ?` or
    `SELECT ?, id` fail with 42P18. The real statement works, because pgjdbc sends the types.
  - With autocommit off the code rethrows (line 423). The application's transaction is aborted,
    earlier work in it is lost, and the error names `$n`, which the application never wrote.
  - The failure is not cached, so every retry fails the same way. A permission error or
    `lock_timeout` on the data table does the same.
- **SET/SHOW before transaction control.** `Router.beforeRun` runs before the application's statement.
  - In an aborted transaction it fails with 25P02 before the application's own SQL `ROLLBACK` or
    `ROLLBACK TO SAVEPOINT` is sent. Only `Connection.rollback()` gets out.
  - Not verified: whether those statements route through `beforeRun` at all (SqlAst may refuse
    them).
- **MySQL COUNT, P3.** With a Connector/J streaming ResultSet open, a `prepareStatement` that used
  to work now fails ("Streaming result set ... is still active"). It happens only when the cached
  count is stale. MariaDB Connector/J buffers the rest of the stream instead.

## Proposed Solutions

### Option 1: never let a planning statement fail the application
- **EXPLAIN:** run it only in autocommit, or under `SAVEPOINT` / `ROLLBACK TO SAVEPOINT`. On any
  error, cache `false`.
- **SET/SHOW:** skip them for transaction-control statements and in a failed transaction
  (pgjdbc's transaction state).
- **COUNT:** on failure, fall back to the cached choice or the DISTINCT set.
- **Effort:** Small. **Risk:** Low.

### Option 2: run planning statements on the catalog's own connection
Autocommit, separate from the application. **Effort:** Medium. **Risk:** another connection per
driver.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] In a transaction on PostgreSQL 16, `SELECT ... WHERE ? IS NULL OR id = ?` over a ScalarDB table
      prepares and runs, and the transaction's earlier insert commits.
- [ ] After a failed statement in a transaction, `execute("ROLLBACK")` works.
- [ ] A failed probe is cached.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

### 2026-10-13 - Reproduced

**By:** Claude Code
- `ReviewFindingsIntegrationTest` (uncommitted until the fixes) asserts the correct behaviour, on
  all six configurations.
- **Confirmed: EXPLAIN.** On PostgreSQL, `? IS NULL OR id = ?` in a transaction failed with "could
  not determine data type of parameter $1", and the transaction's earlier insert was lost.
- **Confirmed: COUNT.** With a Connector/J streaming result open, `prepareStatement` on a ScalarDB
  table failed with "Streaming result set ... is still active".
- **Not reproduced: SET/SHOW before an SQL `ROLLBACK`.** After a failed statement in a
  transaction, `execute("ROLLBACK")` worked.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".
