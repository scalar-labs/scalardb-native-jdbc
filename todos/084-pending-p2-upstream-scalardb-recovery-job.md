---
status: pending
priority: p2
issue_id: "084"
tags: [upstream, scalardb, consensus-commit, liveness]
dependencies: ["082"]
---

# Upstream request to ScalarDB: a job that recovers prepared records on its own

This is a change to ask of ScalarDB, not driver work. File it in ScalarDB when ready.

## Problem Statement

A record stays prepared (state 1 or 2) after a ScalarDB process crashes between prepare and record
commit, or when the record commit fails: ScalarDB 3.19.1 logs that at INFO and still reports the commit
(todo 083). ScalarDB settles such a record only when one of its own reads meets it (lazy recovery), and
ships no job that settles them otherwise. The per-record `ConsensusCommitManager.recoverRecord` exists,
but nothing finds the records and calls it.

ScalarDB's own clients barely notice, since their reads recover on contact. Readers that do not consult
the coordinator do: the driver shows the before image at READ COMMITTED, retries or aborts at the
stronger levels, and blocks its writes on those keys, for as long as no ScalarDB read touches the row.
The driver cannot settle a row itself, because whether the transaction committed is known only to the
coordinator, and it depends on neither the coordinator nor ScalarDB's runtime (design section 6).

## Proposed Change

A recovery job in ScalarDB, run in-process (a background task of the transaction manager) or as a
ScalarDB tool:

- Find records in state 1 or 2 per table; for a decoupled table, scan `<table>_tx_metadata` (the
  driver's setup adds an index on `tx_state` that serves this scan).
- Roll forward or back those whose transaction is decided, at once; abort and roll back those undecided
  past the transaction lifetime, with the same guard as `RecoveryHandler`.
- Safe to run concurrently with itself and with lazy recovery, as `recoverRecord` already is.
- Configurable interval, and a metric or log line per pass: records found, recovered, not yet expired,
  failed.

## Acceptance Criteria

- [ ] Raised with ScalarDB (issue or PR link here).
- [ ] With a release that has it, the driver's documentation names the setting to enable it, and design
      section 7's liveness limitation points to it.

## Work Log

### 2026-10-12 - Recorded

**By:** Claude Code
- Decided with the user that the driver will not depend on the coordinator, even through ScalarDB's
  API, so the sweeper planned in design 4.4 and section 6 moved to ScalarDB. The driver keeps only a
  check that reports stuck rows (todo 085).
