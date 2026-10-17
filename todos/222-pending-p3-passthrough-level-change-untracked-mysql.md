---
status: pending
priority: p3
issue_id: "222"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# A level changed through passthrough is not re-read off PostgreSQL

## Problem Statement

Router.ran() ~1572-1580 re-reads the level only when engine == POSTGRESQL (todo 184). Verified
(probe T, MySQL): at RR, passthrough `SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED`; the
vendor connection reports 2, innodb_trx READ COMMITTED, Router keeps 4 and takes SNAPSHOT paths.

## Proposed Solutions

### Option 1
Re-read the level on every engine; make SETS_LEVEL match MySQL spellings (isolation, tx_isolation).

## Acceptance Criteria

- [ ] Router's level follows a passthrough change on MySQL/MariaDB

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).
