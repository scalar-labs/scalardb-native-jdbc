---
status: pending
priority: p2
issue_id: "083"
tags: [upstream, scalardb, oracle, consensus-commit]
dependencies: ["082"]
---

# Upstream request to ScalarDB: retry ORA-08177 in the record commit

This is a change to ask of ScalarDB, not driver work. File it in ScalarDB when ready.

## Problem Statement

ScalarDB 3.19.1 `ParticipantCommitHandler.commitRecords` catches any failure, logs "Committing records
failed" at INFO and returns. The client is told "committed" while the records stay prepared (state 1)
until lazy recovery reads them. On Oracle at SERIALIZABLE, which ScalarDB requires for decoupled tables
(`RdbEngineOracle.getMinimumIsolationLevelForConsistentVirtualTableRead`), this mutation fails now and
then with `ORA-00604` / `ORA-08177`, a serialization failure that is not a real conflict:

- A hard parse inside the transaction reads `SYS.USER$` at the transaction's snapshot (`select user#,
  password, ... from user$ where name = :1`).
- Every logon writes the user's last login time into `USER$`, so a logon after the snapshot makes the
  consistent read fail.

ScalarDB's admin already retries ORA-08177 on its metadata inserts ("Retrying a conflicting statement"
in `JdbcAdmin`); the record commit does not.

For ScalarDB's own readers this costs only a lazy recovery. For anything that reads the records without
the coordinator (the driver), the committed rows look uncommitted until something recovers them. See todo
082 for the evidence: 4 of 20 complete integration runs failed this way.

## Proposed Changes

1. Retry the record-commit mutation on errors the storage classifies as retryable serialization
   failures (Oracle 8177, and the equivalents on other engines), with a small bounded count. The mutation
   is conditional on `tx_id` and state, so a retry is idempotent.
2. Log a record-commit failure at WARN, not INFO: the outcome is "committed but not visible to
   non-ScalarDB readers until recovery".
3. Optional: expose a counter or event for record-commit failures, so operators can see them.

## Acceptance Criteria

- [ ] Raised with ScalarDB (issue or PR link here).
- [ ] With a fix released, the driver's Oracle test container no longer needs
      `_disable_last_successful_login_time` (todo 082) to pass 20 consecutive complete runs.

## Work Log

### 2026-10-12 - Recorded

**By:** Claude Code
- Written from the diagnosis in todo 082; the ScalarDB code references are from the v3.19.1 tag.
