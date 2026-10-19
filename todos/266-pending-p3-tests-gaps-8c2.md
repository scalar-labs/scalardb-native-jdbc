---
status: pending
priority: p3
issue_id: "266"
tags: [code-review, sqlserver, tests]
dependencies: []
---

# 8c2 test gaps: 3960 not asserted; rollback before a level change untested

## Problem Statement

- SnapshotIntegrationTest 526-549 assert SQLState 40001 only; the driver's own `Aborted` gives that
  too, so removing `asRetryable` from `Writes.within` (l.819) or Proxies (l.854) may still pass.
  Assert the error code 3960 (or the cause) for the UPDATE case.
- The `vendor.rollback()` in `setTransactionIsolation` (Proxies 260-265, all engines with autocommit
  off) has no test of the 3951 case: a driver lookup opening the transaction, then a switch to
  REPEATABLE READ on SQL Server and a statement.

## Proposed Solutions

1. Add both assertions/tests (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 51e1c51 (8c2), verified as stated above.
