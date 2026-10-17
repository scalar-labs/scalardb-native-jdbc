---
status: pending
priority: p2
issue_id: "191"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# FOR NO KEY UPDATE on MySQL/MariaDB is silently run as a shared lock

## Problem Statement

Router.lockingRead (~718, ~729) maps any non-UPDATE lock mode to the shared spelling. Both engines
reject `FOR NO KEY UPDATE` natively (1064); through the driver it succeeds with S locks (data_locks:
S,REC_NOT_GAP on t:2 and the metadata row), NOWAIT kept. Two transactions then share a lock they
expect to be exclusive and deadlock on their updates.

## Proposed Solutions

### Option 1
Refuse `NO_KEY_UPDATE` off PostgreSQL with 0AN01, as `KEY_SHARE` is refused.

## Acceptance Criteria

- [ ] FOR NO KEY UPDATE is refused on MySQL and MariaDB (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
