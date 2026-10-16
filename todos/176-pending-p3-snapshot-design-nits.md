---
status: pending
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
