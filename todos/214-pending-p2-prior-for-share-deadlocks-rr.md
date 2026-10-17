---
status: pending
priority: p2
issue_id: "214"
tags: [code-review, concurrency, mysql, regression]
dependencies: []
---

# The prior read's FOR SHARE deadlocks concurrent INSERTs at REPEATABLE READ on MySQL

## Problem Statement

Writes.java ~417: at REPEATABLE READ `LIMIT 1 FOR SHARE` takes a gap lock; a concurrent INSERT
into the same gap waits on it, the classic S-gap-then-insert deadlock. Verified (scratchpad rv8):
d1 inserts 10, d2 inserts 11 (blocks), d1 inserts 12 -> d2 1213; 8 threads x 100 autocommit
INSERTs of distinct keys: 57-73 of 800 deadlock, 0 without FOR SHARE. READ COMMITTED: none.
Pre-existing and smaller: without FOR SHARE d2 still blocks until d1 commits at RR (something else
in the write path locks the gap).

## Proposed Solutions

### Option 1
FOR SHARE only at READ COMMITTED; at REPEATABLE READ/SERIALIZABLE gate MySQL writes until 8b, which
designs the snapshot levels anyway.

### Option 2
Another latest-committed read at RR without gap locks (none known on MySQL).

## Acceptance Criteria

- [ ] Concurrent INSERTs of distinct keys do not deadlock (test)
- [ ] 209's case still passes or is gated with a clear message

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of b963158 (the fixes of todos 207-211).
