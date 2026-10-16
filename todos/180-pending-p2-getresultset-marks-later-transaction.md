---
status: pending
priority: p2
issue_id: "180"
tags: [code-review, correctness, jdbc]
dependencies: []
---

# A row-by-row read fetched after setAutoCommit(false) marks a transaction that read nothing

## Problem Statement

StatementHandler recomputes `inTransaction = !owner.vendor.getAutoCommit()` on every call that returns
a result set, while `at` (the anchors epoch) is the field set at execute. `setAutoCommit(false)` does not
bump the epoch. So: autocommit, `st.execute(<row-by-row read>)`, `setAutoCommit(false)`,
`st.getResultSet()`, a row in flight aborts the read and marks the new transaction rollback-only; the
next statement fails "aborted by an earlier statement". Verified on PostgreSQL at both levels (probe,
2026-10-17).

## Proposed Solutions

### Option 1
Store `inTransaction` in a field next to `epoch`, set at execute only.

### Option 2
Bump the anchors epoch when autocommit goes off as well.

## Acceptance Criteria

- [ ] The scenario leaves the new transaction usable.
- [ ] Integration case in SnapshotIntegrationTest.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.
