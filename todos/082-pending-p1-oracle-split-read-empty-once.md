---
status: pending
priority: p1
issue_id: "082"
tags: [correctness, oracle, split-read]
dependencies: []
---

# Oracle: a split read returned no rows, twice, not reproduced since

## Problem Statement

In two complete integration runs on 2026-10-11, the first read of `ReadIntegrationTest` on Oracle,
`SELECT "x" FROM rd."made" ORDER BY "id"` through the driver, returned no rows where the committed
image holds a, b, c. Every other engine passed in both runs. A wrong result from a read is a P1
until explained.

## Findings

- The split statement the driver emits for it, logged in a passing run, is correct: run through a
  plain Oracle connection it returns a, b, c; so do each of its pieces and the `rc_made` view.
- Failed: 2 of the first 3 complete runs (one before the `tx_state` histogram step existed, so the
  histogram is not the cause). Passed: 13 genuine complete runs since (`--rerun`; earlier "passes"
  without it were Gradle skipping an up-to-date task), with and without logging; the Oracle class
  alone passed whenever run.
- The SQL of a failing run was never captured. One run also failed inside ScalarDB's own prepare with
  ORA-00604 (recursive SQL error), message truncated; Oracle Free's resource limits are a suspect for
  both, not established.
- Not known: whether the milestone 3 `rc_T` route can do the same; whether the driver's per-JVM
  catalog, shared with the earlier test classes, routed the failing statement differently.

## Proposed Solutions

### Option 1: Capture on failure

**Approach:** On a wrong first result, print the routed SQL, rerun it through the driver and a plain
connection, and dump the metadata rows (the check used while investigating); loop complete runs.

**Effort:** Small, runs take 2.5 min each. **Risk:** Low.

### Option 2: Oracle plan check

**Approach:** Capture `DBMS_XPLAN.DISPLAY_CURSOR` for the statement in a failing run; look for a
known wrong-result issue with NOT EXISTS in an inline view under UNION ALL in 23ai Free.

## Recommended Action

## Acceptance Criteria

- [ ] The cause found and fixed, or ruled outside the driver with evidence.
- [ ] 20 consecutive complete runs pass.

## Work Log

### 2026-10-11 - Found while testing milestone 3b

**By:** Claude Code
- Two failures, logged SQL correct, 13 passes since; see Findings.
