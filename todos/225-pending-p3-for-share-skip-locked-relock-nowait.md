---
status: pending
priority: p3
issue_id: "225"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# FOR SHARE SKIP LOCKED fails instead of skipping (relock NOWAIT)

## Problem Statement

Router.java:739-743: the relock gets NOWAIT under SKIP LOCKED. Another tx holds row 5 FOR UPDATE;
`SELECT k FROM t FOR SHARE SKIP LOCKED` reads the covering index ix, which is not locked, so 5 is
not skipped; the PK relock with NOWAIT then fails (MySQL 3572, MariaDB 1205) instead of [100].
Verified (S4) at RC and RR; present since 8a.

## Proposed Solutions

### Option 1
Under SKIP LOCKED, relock with SKIP LOCKED and treat keys missing from the relock as skipped.

## Acceptance Criteria

- [ ] FOR SHARE SKIP LOCKED returns the unlocked rows on both engines

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).
