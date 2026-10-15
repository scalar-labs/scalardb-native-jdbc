---
status: pending
priority: p2
issue_id: "158"
tags: [code-review, testing, interleaving]
dependencies: []
---

# The NULL-metadata and resurrected-row scenarios run on backfilled rows

## Problem Statement

`InterleavingIntegrationTest` runs `Setup.run` (:170-175) before the scenarios, which backfills every
row of the imported table. Rows 8 and 11 therefore carry the backfill stamp (state 3), and ScalarDB's
rollback restores that stamp, not NULLs. "NULL tx_state read as 3", "the NULL-pair CAS" and the
row-without-metadata delete (:304-323) pass on the ordinary state-3 path; the rows design section 9 and
todo 145 care about are not exercised.

## Findings

- Probe: metadata rows 8 and 11 before the scenario and after the rollback were
  `scalardb-native-backfill/3/1/...`, all non-NULL.

## Proposed Solutions

### Option 1
Before the two scenarios, delete (or NULL) the metadata of ids 8 and 11 with raw SQL and assert the
precondition (`tx_state IS NULL`); after the rollback assert tx_id, tx_version and tx_state are NULL.

## Acceptance Criteria

- [ ] Both scenarios assert NULL metadata before they run.
- [ ] With the NULL-as-committed rule broken, the test fails.

## Recommended Action


## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.
