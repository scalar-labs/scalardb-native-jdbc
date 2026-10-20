---
status: pending
priority: p3
issue_id: "295"
tags: [inline, mysql, locking, docs]
dependencies: []
---

# MySQL: on an inline table the in-flight index's gap locks can block other clients' raw inserts

## Problem Statement

On an inline table the in-flight index `(tx_state, key)` is on the application's own table. At
REPEATABLE READ and SERIALIZABLE on MySQL (and MariaDB), a driver read of the in-flight range takes gap
locks there; a row another client inserts directly, its metadata NULL, sorts before the range and can
fall in the gap, so it waits until the driver's transaction ends. On a decoupled table the same insert
goes to the data table and does not wait. ScalarDB's own inserts wait on both layouts, as intended.
Found by SnapshotIntegrationTest's todo-220 check, intermittent with the plan MySQL picks (10c).

## Proposed Solutions

1. Document it: writes that bypass ScalarDB and the driver are not supported on a ScalarDB table, and
   on an inline one they can wait on the driver's locks (small).

## Acceptance Criteria

- [ ] Documented

## Work Log

- 2026-10-20: found in the full check of 10c; the check runs on decoupled tables only.
