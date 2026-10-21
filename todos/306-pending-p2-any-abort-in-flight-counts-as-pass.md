---
status: pending
priority: p2
issue_id: "306"
tags: [code-review, testing, write, in-flight]
dependencies: []
---

# With rows in flight, any class-40 error from any statement counts as a pass: false conflicts go undetected

## Problem Statement

In the in-flight phase a statement that aborts is a pass, "aborted for a row it reads", whether or
not it could read a row in flight. A driver that aborts every write while any row of the table is in
flight, or that checks conflicts against the whole table instead of the rows the statement matches,
passes the phase entirely: every statement is counted in `aborted`, nothing in `failures`. Design
section 6 aborts a write only for a row in flight that its predicate matches (by before or after
image); `UPDATE {c} SET region = 5 WHERE id = 1` with only `c` 3 held must succeed. The check also
accepts any SQLState starting `40` (40P01 deadlock, 40002, 40003), not the documented `40001`.

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:180`: `abort = actual.state != null && actual.state.startsWith("40")`.
- 187-190: `if (abort && inFlight)` sets a pass outcome unconditionally; `reached.isEmpty()` only
  changes the wording.
- 204-205: counted in `aborted`; nothing bounds it, and no statement is required to succeed.
- Key-only statements in `resources/writes/writes.sql` that can read no other row: lines 14, 16,
  17, 26, 28, 32, 33 (by key), 2-5 and 35-36 (inserts of keys never held).

## Proposed Solutions

### Option 1: the keys a statement may read, from the twin
Before the statement, run its predicate as a `SELECT` of the keys on the twin (both images matter:
also mark the statement "may read in flight" when its target or subquery tables hold a key in flight
that the predicate's before-image would match). Simpler: a statement whose `WHERE` is a key
equality (or an `INSERT ... VALUES`) and whose keys are not held must not abort. **Effort:** Medium.

### Option 2: a corpus marker
`-- reads only its keys` above such statements; for them an abort is a failure. **Effort:** Small.

In both, require SQLState `40001` exactly.

## Acceptance Criteria

- [ ] A key-only write of a key not in flight that aborts fails the test.
- [ ] Only `40001` counts as the abort for a row in flight.

## Work Log

### 2026-10-21 - Found by the review of T3

**By:** Claude Code

## Resources

- docs/plans/testing.md, milestone T3; docs/design.md section 6.
