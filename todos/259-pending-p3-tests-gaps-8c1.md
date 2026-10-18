---
status: pending
priority: p3
issue_id: "259"
tags: [code-review, sqlserver, tests]
dependencies: []
---

# 8c1 test gaps: XACT_ABORT check untested, READPAST relock untested

## Problem Statement

- MoreWritesIntegrationTest.java:274 asserts `SET XACT_ABORT ON` is refused; it is, only because the
  parser fails. The real guard, the connect-time check (Session.java:164-174, 08004), has no test.
- The READPAST savepoint release (Writes.java:1801-1809, `relockedAsRead`) has no test that fails
  without it: the queue test at MoreWrites 917-923 only rolls `held` back. The `relockedAsRead`
  false path never runs (`lockAfterCheck` is PostgreSQL-only).

## Proposed Solutions

1. Test the connect check with XACT_ABORT on as a server user option on a throwaway container or
   by a login trigger (medium); drop the line-274 assert.
2. While the READPAST read is open, assert that `held` (ScalarDB) can commit (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
