---
status: pending
priority: p3
issue_id: "162"
tags: [code-review, testing, interleaving]
dependencies: []
---

# Interleaving harness: a failure can hang, diagnostics are thin, one scenario can be vacuous

## Findings

- **A hang hides the failure** (:501-505, `DriverActor.close` :615-619): on failure actors are closed
  in order; pgjdbc serializes `rollback` behind a running statement and ignores interrupts, so an actor
  blocked on a lock held by a later actor waits forever and the AssertionError is never reported.
  Fix: `Connection.abort(executor)` or `pg_terminate_backend` first, or a `lock_timeout` on the actors.
- **Diagnostics** (:440-446): pids cannot be mapped to actors (no `ApplicationName`), there is no step
  trace, and the diagnostic connection leaks. Schedules are not replayable from the seed (step timeouts
  change readiness), so the `SuppressFBWarnings` justification "a failing one replays" (:69-71)
  overstates it. Fix: per-actor `ApplicationName`, a step trace in every failure message,
  try-with-resources.
- **"Why one statement" can be vacuous** (:350-364): `Thread.sleep(300)` assumes the read took its
  snapshot; on a slow machine the rollback lands first. Fix: poll `pg_stat_activity` for
  `wait_event = 'PgSleep'` before the rollback.
- **SKIP LOCKED scenario** (:337-342) fails as a bare TimeoutException without its rule name. Fix:
  `assertThat(future).as(...).succeedsWithin(...)`.
- **Cross-system aborts are not asserted** (:203-204): "both sides abort" passes on same-system
  conflicts alone.
- Write skew and read skew are outside a per-row invariant (and allowed at these levels): say so in the
  class Javadoc.

## Acceptance Criteria

- [ ] A forced assertion failure mid-schedule is reported, not hung.
- [ ] A failure message names the actor, the step and the trace.
- [ ] The one-statement scenario proves the read was in progress before the rollback.

## Recommended Action


## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.
