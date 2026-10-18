---
status: complete
priority: p2
issue_id: "221"
tags: [code-review, liveness, mysql, mariadb]
dependencies: []
---

# SKIP LOCKED with LIMIT at RR always aborts while the head row is locked

## Problem Statement

Writes.java:1640-1658 (asInSnapshot) with Router.java:812 (the snapshot step is the same text,
LIMIT included). Another tx holds row 5 FOR UPDATE; `SELECT k FROM t ORDER BY k LIMIT 1 FOR UPDATE
SKIP LOCKED` locks 100, the snapshot read returns 5, seen != locked.size() -> 40001, and every retry
fails while 5 is held. Verified (S4) on both engines for FOR UPDATE and FOR SHARE/LOCK IN SHARE MODE;
RC returns [100]; without LIMIT RR returns [100].

## Proposed Solutions

### Option 1
Under SKIP LOCKED, check only the locked keys: re-run the predicate without ORDER BY/LIMIT,
restricted to those keys, and compare versions.
### Option 2
Refuse SKIP LOCKED with LIMIT at RR (0AN01) for now.

## Acceptance Criteria

- [x] The queue pattern returns the next unlocked row at RR, or is refused clearly

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).

### 2026-10-18 - Fixed

**By:** Claude Code
- Fixed (variant of Option 1): under SKIP LOCKED, asInSnapshot reads the locked keys' versions in the snapshot and requires each at the version locked, instead of rerunning the statement with its LIMIT. Test in SnapshotIntegrationTest, all three engines.
