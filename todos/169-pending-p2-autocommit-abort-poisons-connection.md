---
status: pending
priority: p2
issue_id: "169"
tags: [code-review, correctness, jdbc]
dependencies: []
---

# A row-by-row abort in autocommit leaves the connection rollback-only for good

## Problem Statement

Proxies passes `() -> owner.rollbackOnly = true` to AnchoredHandler unconditionally. In autocommit a
precise-path read that reaches a row in flight throws 40001 and marks the connection rollback-only;
every later statement then fails "aborted by an earlier statement". `Connection.rollback()` cannot clear
it: pgjdbc throws in autocommit before the proxy resets the flag. Hikari does not roll back an autocommit
connection on return, so the pool hands it on. The checked path guards this (`if (!own)`). Variant: rows
read after their transaction ended mark the next transaction rollback-only.

## Proposed Solutions

### Option 1
Mark rollback-only only when the read ran in a transaction, and only in that transaction: capture autocommit
and the anchors' epoch at execute, mark when not autocommit and the epoch is unchanged.

## Acceptance Criteria

- [ ] A row-by-row abort in autocommit leaves the connection usable.
- [ ] A row read after its transaction ended does not poison the next one.
- [ ] Integration case in SnapshotIntegrationTest.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.
