---
status: complete
priority: p3
issue_id: "137"
tags: [code-review, jdbc, writes]
dependencies: []
---

# Smaller JDBC details of the write path

## Problem Statement

Four small deviations from what JDBC or the design says, found in the review of 4a.

## Findings

- A write on a `Statement` leaves its earlier `ResultSet` open (probe confirmed); an execute closes it.
- The INSERT savepoint is not released after `rollback(sp)` on a duplicate, nor on other errors
  (reasoning); a loop catching duplicates piles up subtransactions.
- In `Writes.run`, a failing `setAutoCommit(true)` in the `finally` replaces the original exception, or
  fails a write that already committed (reasoning).
- While rollback-only, SQL `ROLLBACK` is refused with 40001 (probe confirmed): design 4.4 forwards
  rollback; only `Connection.rollback()` gets out.
- Note: a write switches autocommit off on the shared vendor connection for its duration; another
  thread's statement on the same connection then joins it (only for apps sharing a connection).

## Proposed Solutions

### Option 1
Close the statement's current result set before a write; release the savepoint in a finally; attach a
restore failure as suppressed; skip the rollback-only check for Kind.ROLLBACK and clear the flag in
`ran()`; a `ponytail:` note on the shared-connection window.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] Each item fixed or noted, with a probe or unit test where cheap.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).

### 2026-10-14 - Fixed

**By:** Claude Code
- The statement's current result set is closed before a write; the INSERT savepoint is released in a finally; a failing autocommit restore is suppressed onto the write's own failure; SQL ROLLBACK and ROLLBACK TO SAVEPOINT pass while rollback-only, and ROLLBACK clears it; ponytail note on the shared-connection window. Test "137" for the SQL ROLLBACK and the closed result set.
