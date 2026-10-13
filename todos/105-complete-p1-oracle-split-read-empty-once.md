---
status: complete
priority: p1
issue_id: "105"
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

Cause found, outside the driver (work log): every Oracle logon writes the user's last login time
into `SYS.USER$`; a hard parse inside a SERIALIZABLE transaction reads `USER$` at the transaction's
snapshot and fails with ORA-08177 when a logon changed the block since. ScalarDB requires SERIALIZABLE
on Oracle for decoupled tables (its default is the database's, READ COMMITTED, where ORA-08177 cannot
occur), and its record commit ignores the failure, so the rows stay prepared and the
driver returns their before images, as design section 7 ("Recency in one failure path") says it will.

Test fix: the Oracle test container sets `_disable_last_successful_login_time = TRUE` (as SYSDBA),
and `ReadIntegrationTest` checks after each ScalarDB commit that no records were left prepared, so a
recurrence names its cause.

Open for production, not done: any ScalarDB deployment on Oracle with connection churn can leave
committed rows prepared this way, invisible to the driver until ScalarDB's lazy recovery reads them,
and ScalarDB has no job that recovers them (section 6). Options: document the parameter (hidden, needs Oracle Support's
blessing) or long-lived pools; have ScalarDB settle such rows with a recovery job of its own (todo 084), while the driver
only reports them (todo 085); ask ScalarDB to retry
ORA-08177 in the record commit. The upstream request is todo 083. Design section 7 names this Oracle failure mode (2026-10-12).

## Acceptance Criteria

- [x] The cause found and fixed, or ruled outside the driver with evidence.
- [x] 20 consecutive complete runs pass (2026-10-12, with the fix; 4 of 20 failed without it).

## Work Log

### 2026-10-11 - Found while testing milestone 3b

**By:** Claude Code
- Two failures, logged SQL correct, 13 passes since; see Findings.

### 2026-10-11 - Cause found

**By:** Claude Code
- ScalarDB 3.19.1 `ParticipantCommitHandler.commitRecords` catches any failure, logs "Committing
  records failed" at INFO and returns: the client is told "committed" while the records stay in
  state 1. For inserted rows (no before image) the driver then returns nothing, the observed symptom.
- Added `assertSettled` to `ReadIntegrationTest`. 20 complete runs: 4 Oracle failures, 3 of them the
  check (3 records of `made` left prepared, log `Committing records failed ... ORA-00604: Error
  occurred at recursive SQL level 1 ... ORA-08177`), 1 the same error in the test's 2PC `prepare`.
- First guess, deferred segment creation, was wrong: with it off, 5 of 10 runs failed, one on a
  ScalarDB read.
- An `errorstack` event on 8177, read back through `v$diag_trace_file_contents`, caught the
  recursive statement: `select user#, password, ... from user$ where name = :1`, failing in a
  consistent read (`kcbgtcr -> ktrexc`). A probe showed each logon moving SYSTEM's `last_login`, and
  `_disable_last_successful_login_time = TRUE` (SYSDBA only) stopping it.

### 2026-10-13 - Renumbered and closed

**By:** Claude Code
- Renumbered from 082, which another todo also used (MySQL through MariaDB Connector/J).
- Closed: the test fix holds, and what remains for production belongs to todos 083 (ScalarDB
  retrying ORA-08177), 084 (ScalarDB's recovery job) and 085 (the driver's stuck-row report).
