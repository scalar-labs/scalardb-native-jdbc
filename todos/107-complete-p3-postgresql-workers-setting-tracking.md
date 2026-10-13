---
status: complete
priority: p3
issue_id: "107"
tags: [code-review, performance, postgresql]
dependencies: []
---

# PostgreSQL workers setting: lost for good after a SET, toggled around statements that do not need it

## Problem Statement

`Router.beforeRun` keeps `max_parallel_workers_per_gather` at 0 for short split reads and at the
session's value otherwise. Its tracking can lose the session's value permanently. It also switches
around statements that do not care about the setting.

## Findings

- **Lost value (verified).** `Router.java:505, 620`.
  1. A split read sets 0.
  2. Any session-changing statement (`SET search_path`, DDL) makes `ran()` reset `sessionWorkers`
     to -1.
  3. The next `SHOW` returns the driver's own 0, and the guard stores 0 as the session's value.
  4. Parallel query is off for the rest of the connection's life.
- **Needless toggles.** `beforeRun` restores the session's value before every route that is not a
  split read: DML, `SET`, point lookups. A loop of split SELECT and UPDATE pays two `SET`s per
  iteration, about 1 ms each.
- **Wrong guess.** "A 0 with a known session value is ours" is wrong after a rolled-back
  `SET ... = 4` or a `SET LOCAL`.
- **Pooling.** A session-level `SET` leaks to other clients behind a transaction-mode pooler
  (PgBouncer). Pools that roll back on return pay a `SHOW` on each borrow.
- **Generic plans.** They assume default selectivities, which can keep workers on a short read.

## Proposed Solutions

### Option 1: track only what can change it
- Reset `sessionWorkers` only for statements that name the setting, or `RESET` / `DISCARD`.
- Restore the session's value only before a SELECT that is not split.
- **Effort:** Small.

### Option 2: per statement, no session state
`SET LOCAL` inside an explicit transaction around a split read. **Effort:** Medium. **Risk:** changes
transaction boundaries.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] After split read + `SET search_path` + plain full scan, the scan's plan is parallel.
- [ ] No `SET` is sent between a split read and a DML statement.

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
- **Not reproduced: the lost value.** After a split read, `SET search_path`, then `SHOW`, the
  value was still 2. `beforeRun` restores the session's value before the `SET` runs, since a `SET`
  is not a split read, so the driver's 0 is never what `SHOW` sees afterwards.
- **What stands:**
  - the toggles around statements that do not need the setting (performance);
  - the wrong guess after a rolled-back `SET`;
  - the session-level `SET` behind a transaction-mode pooler.
- Lowered to P3.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".

### 2026-10-14 - Fixed

**By:** Claude Code
- Option 1. `Router.ran` forgets the session's value only after a statement that names
  `max_parallel_workers_per_gather` or runs `RESET ALL` / `DISCARD ALL`, and
  `Router.transactionEnded` forgets it at commit or rollback when such a statement ran in the
  transaction (SET LOCAL, rolled-back SET).
- `beforeRun` restores the session's value only before a statement PostgreSQL may run in parallel
  (`Route.plansParallel`: not a write, not a session statement), and reads nothing for those.
- Test: `ReadIntegrationTest`, PostgreSQL block: a SET search_path after a split read keeps 3 for
  the next plain read; a SET of the workers becomes the new value.
- Not fixed, documented in design 4.2: a transaction-mode pooler sees the session-level SET;
  generic plans can misjudge a short read as parallel.
