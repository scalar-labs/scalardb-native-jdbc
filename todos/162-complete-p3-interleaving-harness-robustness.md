---
status: complete
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

- [x] A forced assertion failure mid-schedule is reported, not hung.
- [x] A failure message names the actor, the step and the trace.
- [x] The one-statement scenario proves the read was in progress before the rollback.

## Recommended Action

All but the cross-system assertion.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.

### 2026-10-16 - Fixed

**By:** Claude Code
- Actors are named (d0, d1 also as ApplicationName; s2, s3); every failure carries the schedule's
  steps (actor:step, blocked, committed, aborted, rolled back); the blocked diagnostic shows
  application_name and closes its connection.
- A driver actor closes with `Connection.abort`, so a statement blocked on another actor's lock no
  longer holds the failure back. Checked by treating 40001 as a fault for one run (reverted): it
  failed in 42 s with "seed 1, d0 step 1" and its steps.
- The one-statement scenario waits until a backend is in `PgSleep` before the rollback; SKIP LOCKED
  uses `succeedsWithin` with its rule; the class Javadoc says write and read skew are out of reach;
  the SpotBugs justification no longer claims schedules replay exactly.
- Not done: asserting aborts between the two systems; the per-side commits and aborts of todo 159
  and the mutation checks cover the cases that matter.
