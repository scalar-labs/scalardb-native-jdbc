---
status: pending
priority: p3
issue_id: "223"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# SKIP LOCKED silently drops a row deleted after the snapshot

## Problem Statement

Writes.java:1648 (`continue; // SKIP LOCKED`). After T1's snapshot another tx deletes row 5 and
commits; `SELECT ... FOR UPDATE SKIP LOCKED` in T1 returns [100] without error (S3b, both engines).
Without SKIP LOCKED it aborts with 40001 (S3). PostgreSQL behaviour not tested.

## Proposed Solutions

### Option 1
Skip a snapshot row only if it is locked now (re-probe with FOR SHARE SKIP LOCKED); a key that no
longer exists aborts.

## Acceptance Criteria

- [ ] A row deleted after the snapshot aborts under SKIP LOCKED too, or the behaviour is documented

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).
