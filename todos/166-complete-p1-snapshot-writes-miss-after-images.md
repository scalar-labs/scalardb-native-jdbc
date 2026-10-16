---
status: complete
priority: p1
issue_id: "166"
tags: [code-review, correctness, jdbc, isolation]
dependencies: []
---

# At SNAPSHOT and SERIALIZABLE a write skips rows in flight that match only by their after image

## Problem Statement

A predicate UPDATE or DELETE picks its keys from `SELECT keys FROM rc_T WHERE P` (Writes.java:180-207,
`n.view`), the committed image, at every level. Milestone 7 opened writes to REPEATABLE READ and
SERIALIZABLE (Router.java `write()`) without changing it; only the locking read got the after-image check
(`Router.inFlightStep`). Design 4.2 (SNAPSHOT bullet), 4.3 step 1 and section 6 require an abort when a row
in flight matches by either image, and the section 5 argument assumes the driver "reads the prepared row and
aborts".

- Fractured view (SNAPSHOT): ScalarDB S sets k.x 1 -> 2 and j.y = 'S', coordinator-committed, j
  record-committed, k still state 1. The driver reads j ('S'), runs `DELETE FROM t WHERE x = 2`: k is not
  found, 0 rows, commits. It saw S on j but not on k.
- Cycle under SSI (both sides SERIALIZABLE): S scans group g for a marker (none), updates row 1 x 'a' -> 'b',
  prepares (Sp commits). The driver, snapshot after Sp, runs `UPDATE t ... WHERE g = ? AND x = 'b'` (0 rows,
  rc_T shows 'a') and inserts the marker; S validates before the driver commits and misses it. Edges
  Sp -wr-> D, Sv -rw-> D, D -rw-> Sr: no dangerous structure, both commit.
- The same on a key: `UPDATE t ... WHERE id = 50`, 50 a prepared ScalarDB insert, returns 0 rows.

## Proposed Solutions

### Option 1
At the snapshot levels, an abort check before the keys step: the predicate over the after images of the
rows in flight, `SplitRead.afterImages`, as `inFlightStep` does for a locking read (the before images are
already caught, since `rc_T` returns them in state 1/2 and `inflightWait` is zero).

### Option 2
Choose the keys through the detector form itself: raw `T` plus the before-image copy, carrying state.

## Acceptance Criteria

- [ ] A predicate UPDATE/DELETE matching a row in flight only by its after image aborts with 40001 at both
  snapshot levels.
- [ ] A key UPDATE of a prepared ScalarDB insert aborts.
- [ ] The SSI cycle above, through the driver, never commits both sides.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.

### 2026-10-17 - Fixed

**By:** Claude Code
- Writes: at SNAPSHOT and SERIALIZABLE a KeyedPlan first runs the predicate over the after images
  of the rows in flight (`SplitRead.afterImages`) and aborts with 40001 on a match; covers a key write
  of a prepared insert. `Writes.plan` takes the level.
- SnapshotIntegrationTest covers it at both levels.
