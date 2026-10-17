---
status: pending
priority: p3
issue_id: "224"
tags: [code-review, concurrency, mysql, mariadb]
dependencies: []
---

# Locking reads gap-lock the metadata table for rows without metadata at RR

## Problem Statement

Router.java:739-743 drops OF d at snapshot; Writes.java:1697 relock `LEFT JOIN meta m ... FOR
UPDATE|FOR SHARE`. A missing metadata row probed under a lock at RR takes a GAP lock on the next
metadata record (S9: `nm_tx_metadata PRIMARY X,GAP 400` after `WHERE k=300 FOR UPDATE`). An UPDATE
of k=310 elsewhere fails 1205 on both engines; two FOR UPDATEs on disjoint rows each followed by an
UPDATE deadlock 1213 (S9b; MariaDB also logs "SAVEPOINT does not exist"). RC succeeds (S9c).
Affects imported tables before backfill.

## Proposed Solutions

### Option 1
Lock d with OF d and lock meta by primary key only for keys that had metadata in the snapshot; a key
that gained metadata since aborts.
### Option 2
Document that RR needs the backfill to have completed.

## Acceptance Criteria

- [ ] Disjoint rows without metadata do not wait on or deadlock with each other at RR

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).
