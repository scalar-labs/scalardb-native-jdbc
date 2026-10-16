---
status: complete
priority: p2
issue_id: "170"
tags: [code-review, correctness, jdbc]
dependencies: []
---

# A failed commit leaves the per-table checks, anchors and session state of the ended transaction

## Problem Statement

Proxies `commit` (and `setAutoCommit(true)`) call `router.transactionEnded()`, `anchors.transactionEnded()`
and `session.transactionEnded()` only after the vendor call succeeds. A commit that fails, as SSI's 40001 at
COMMIT does, still ends the PostgreSQL transaction. A retry loop that does not call rollback, which pgjdbc
does not require, then starts a transaction that skips its table checks (`passed`) and reads before images
of rows in flight through the per-table-check path instead of aborting.

## Proposed Solutions

### Option 1
Run the end-of-transaction bookkeeping in a `finally`: a commit attempt always ends the transaction.

## Acceptance Criteria

- [ ] After a commit that fails with 40001, the next transaction checks its tables again.
- [ ] Anchors and the rollback-only flag follow the same rule.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.

### 2026-10-17 - Fixed

**By:** Claude Code
- Proxies: commit and setAutoCommit(true) end the transaction's bookkeeping in a `finally`.
- SnapshotIntegrationTest covers it at both levels.
