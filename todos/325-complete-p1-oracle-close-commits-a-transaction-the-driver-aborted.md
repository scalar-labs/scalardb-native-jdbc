---
status: complete
priority: p1
issue_id: "325"
tags: [correctness, jdbc, oracle, writes]
dependencies: []
---

# On Oracle, `close()` commits a transaction the driver aborted

## Problem Statement

After an abort the driver decides (a zero-row CAS, a row in flight at SNAPSHOT, a timed-out wait, a
timeout or cancel between a key's DML and its stamp), the driver marks the transaction rollback-only
and refuses `commit()` (design 4.4). `Connection.close()` was forwarded as it is, and Oracle's JDBC
driver commits an open transaction at close. An application whose try-with-resources closes the
connection when the `40001` propagates, without a rollback, so committed what the driver refused:
its earlier writes, and after a timeout between a key's DML and its stamp, the row's new data under
its old metadata, which a later writer anchored on that version passes its CAS against (todo 157's
lost update, by another way). The other engines roll back an open transaction when the session ends.

## Findings

- Found while writing T5's faults (the driver node closes its connection after a failed statement).
- `CloseIntegrationTest`, before the fix: at SNAPSHOT, `UPDATE ... WHERE id = 2`, then an update of a
  row ScalarDB holds prepared fails with `40001`; `close()`; ScalarDB then reads row 2 as `y`, not
  `a`. On Oracle only; PostgreSQL passed.

## Proposed Solutions

### Option 1: roll back at close when the transaction is rollback-only
Small; an open transaction the driver did not abort keeps the vendor's behaviour at close.

### Option 2: roll back every open transaction at close
The same on every engine, but it changes what Oracle applications that rely on the commit at close
get.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] `CloseIntegrationTest` passes on every engine.

## Work Log

### 2026-10-23 - Found and fixed
- Option 1 in `Proxies.ConnectionHandler`; `CloseIntegrationTest` added.
