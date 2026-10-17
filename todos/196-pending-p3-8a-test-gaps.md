---
status: pending
priority: p3
issue_id: "196"
tags: [code-review, testing]
dependencies: []
---

# Milestone 8a tests: weakened PostgreSQL assertions and untested paths

## Problem Statement

- WriteIntegrationTest ~272 and MoreWritesIntegrationTest ~231-232 changed `23505` to
  `startsWith("23")` for every engine: assert 23505 on PostgreSQL, 23000 plus error 1062 elsewhere.
- MoreWrites ~521 asserts SQLState HY000 only; assert error codes 3572 (MySQL) and 1205 (MariaDB).
- Untested: the savepoint undo of a part-applied multi-key write followed by a commit (timeoutMidWrite
  ends rollback-only); NULL/0 AUTO_INCREMENT keys (186); useAffectedRows end to end (193); MariaDB
  LOCK IN SHARE MODE NOWAIT/SKIP LOCKED (189).

## Acceptance Criteria

- [ ] PostgreSQL duplicate assertions are exact again
- [ ] NOWAIT error codes asserted
- [ ] A test where a multi-key write fails part way on MySQL, the transaction continues and commits, and no partial write remains

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
