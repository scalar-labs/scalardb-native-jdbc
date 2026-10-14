---
status: complete
priority: p2
issue_id: "141"
tags: [code-review, correctness, jdbc, anchors]
dependencies: []
---

# A result set records its rows into whatever transaction is current when it is iterated

## Problem Statement

Whether an anchored result set records, and into which store, is fixed at execute; every row moved
to afterwards is recorded into the connection's one `Anchors`, also after `commit()`, `rollback()` or
`setAutoCommit(true)`. A stale version from an earlier transaction then becomes the first read of the
next one, which aborts with 40001 although it read the current row itself; rows iterated in autocommit
after the switch are recorded and leak into the next transaction.

## Findings

- Probe (JDBC reviewer): autocommit off, `SELECT ... id=2`, `commit()`, another connection updates
  id 2, `rs.next()`, then in tx2 read id 2 again and update it: 40001. Same with `getResultSet()`
  after commit, and with `setAutoCommit(true)` mid-iteration followed by updates.
- The pattern "query, then commit per row while iterating" is common; pgjdbc's materialised result
  sets survive the commit.
- Proxies.java (`recording`, `owner.anchors` at execute), Anchors has no notion of transaction.

## Proposed Solutions

### Option 1
Give `Anchors` a transaction epoch, moved by `transactionEnded()`; the result set captures it at execute
and drops a row when it moved. Optionally skip the anchor check in autocommit.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] Both probe scenarios succeed; a row read in an earlier transaction never anchors a later one.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of ef68593 and b312ced (milestone 4b).

### 2026-10-15 - Fixed

**By:** Claude Code
- Anchors has an epoch, moved at every transaction's end; a result set records only while the epoch it was executed in is current. Test "141" (a result set iterated after commit).
