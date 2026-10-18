---
status: complete
priority: p2
issue_id: "234"
tags: [code-review, tests]
dependencies: []
---

# SERIALIZABLE tests miss the write lock abort and driver-vs-driver

## Problem Statement

- No test fires Writes.KeyedPlan.lockedRows's changed/added aborts (Writes.java:1013-1027); the
  first-committer-wins block is skipped under coarse (SnapshotIntegrationTest ~L440). WritesTest.java:65 only passes locks=false.
- No driver-vs-driver write skew / phantom test at MySQL/MariaDB SERIALIZABLE (design.md:1553, 1726 claim 2PL); see 229.
- P3: read-only anomaly on MySQL checks the outcome only (SerializableIntegrationTest:287): add trace contains "deposit aborts".
- P3: path assertion is PG-only (SerializableIntegrationTest:242-250); enum comments wrong off PG (JOIN goes coarse).
- P3: SnapshotIntegrationTest:446 asserts TransactionException; assert a conflict and that row 4 still reads "a".
- P3: the coarse flag (SnapshotIntegrationTest:139) means locking SERIALIZABLE; rename locking.
- P3: untested: FIRST rule satisfied via passed, coarse subquery read in autocommit, UNION/WITH refusal.

## Proposed Solutions

### Option 1
Add the tests listed, using a short innodb_lock_wait_timeout for driver connections.

## Acceptance Criteria

- [ ] Each listed path has a test that fails if the mechanism is removed

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
- 2026-10-18: partly done: the write-lock abort is tested (fails with the abort disabled), and plain-table locking against another connection; driver-vs-driver and the P3 items remain.
- 2026-10-18: a two-driver write-skew test on plain and ScalarDB tables would have caught 239; still worth adding.
- 2026-10-18: two driver transactions at SERIALIZABLE: each one's read holds off the other's write, on a plain and a ScalarDB table. P3 sub-items left as minor.
