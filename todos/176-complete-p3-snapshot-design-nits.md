---
status: complete
priority: p3
issue_id: "176"
tags: [code-review, documentation]
dependencies: []
---

# Design text the milestone 7 review found loose or stale

## Problem Statement

- Section 5, read-only step: argue through the serial order (every read's snapshot precedes every
  validation's, so the SSI proof applies) rather than real time; the "moves out of the window" wording covers
  direct conflicts only.
- Section 5: ScalarDB two-phase commit with several participants on one database has several prepare
  transactions; the argument assumes every prepare precedes every validation. State it.
- Section 7: with ScalarDB's storage at SERIALIZABLE its record commits can fail with SSI false positives,
  logged and ignored, leaving rows in state 1 until lazy recovery: the recency path.
- 4.6 ORM table says "the two-arm form" for count()/existsById; at the snapshot levels they take the
  per-table check. Section 10 item 12 is false on PostgreSQL now, and no item records milestone 7's changes.
- References: CommitHandler.commit, ParticipantCommitHandler.canOnePhaseCommit/validateRecords,
  TransactionContext.isValidationRequired, Snapshot.toSerializable, JdbcDatabase.get/scan.

## Proposed Solutions

### Option 1
Edit the sections as listed.

## Acceptance Criteria

- [ ] Each item edited.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.

### 2026-10-17 - Fixed

**By:** Claude Code
- Section 5: the read-only step argued through SSI's serial order (direct case: the dangerous structure
  `Sv(y) -rw-> W -rw-> W'`); a "What is assumed" item states that chains through third transactions rest
  on SSI's theorem applied as if S were one transaction, and that with two-phase commit every prepare
  precedes every validation; the window bullet says "no direct conflict"; the test description updated.
- Section 7: SSI false positives make failed record commits, the recency path, more frequent.
- 4.6: OFFSET pages, count() and existsById take the per-table check at the snapshot levels.
- Section 10: item 15 records milestone 7 and supersedes "the first statement" of items 11-12 on
  PostgreSQL.
- References: Snapshot.toSerializable, CommitHandler.commit, TransactionContext.isValidationRequired,
  ParticipantCommitHandler.canOnePhaseCommit/validateRecords, JdbcDatabase.get/scan.
