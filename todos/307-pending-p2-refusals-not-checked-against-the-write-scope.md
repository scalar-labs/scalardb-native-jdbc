---
status: pending
priority: p2
issue_id: "307"
tags: [code-review, testing, write]
dependencies: []
---

# A refusal counts as correct whatever the statement: a supported write that regresses to 0AN01 passes

## Problem Statement

Any `0AN01` from the driver is "refused", and refusals never fail the test. The plan states the 13
to 19 refusals per layout are the documented write scope, but nothing asserts which statements are
refused or how many. A change that makes the driver refuse `UPDATE {c} SET region = 5 WHERE id = 1`,
or every write, still passes T3 in both phases. `0AN01` is also raised for runtime conditions that
are driver bugs rather than scope: `Writes.java` raises it when the database reports another number
of generated keys than rows, and when Oracle's inserted row is not found by its ROWID, after the
statement ran.

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:315-316` (`compare`): `REFUSED.equals(actual.state)` returns
  "refused" first, before any other check; 202-203 count it; only `failures` is asserted (265).
- 181-182: the twin is rolled back for a refusal; no `sameTables` follows (199-201 run it only on
  `"pass"`), so a refusal that left a partial write is seen only by a later statement's check.
- `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Writes.java:1696-1699` and `:1798`: `new
  SQLException(..., "0AN01")` for a keys mismatch and a missing ROWID.
- The same holds for the read corpus (`CorpusIntegrationTest.compare`, 354-357).

## Proposed Solutions

### Option 1: an expected-refusal marker in the corpus
`-- refused` above each statement outside the write scope (per engine where it differs); a refusal
without the marker, or a marked statement that runs, fails. **Effort:** Small.

### Option 2: check the message
Also match the refusal's message against the documented reasons, so a runtime `0AN01` (keys
mismatch) is a failure; better, give those two runtime errors another SQLState. **Effort:** Small.

## Acceptance Criteria

- [ ] A statement in the write scope that the driver refuses fails T3.
- [ ] The two runtime `0AN01` errors in `Writes` are not counted as refusals.

## Work Log

### 2026-10-21 - Found by the review of T3

**By:** Claude Code

## Resources

- docs/plans/testing.md, milestone T3 (the refusal list).
