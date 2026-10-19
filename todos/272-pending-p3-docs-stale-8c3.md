---
status: pending
priority: p3
issue_id: "272"
tags: [code-review, sqlserver, serializable, docs]
dependencies: []
---

# Docs and comments stale after 8c3

## Problem Statement

- design.md 1052-1061: the coarse path's first-statement rule and `rc_T`, its 'as built' exception
  naming MySQL and MariaDB only; l.1634 'through rc_T on SQL Server'. As built, SQL Server runs on the
  data tables, every read held, no first-statement rule.
- Router.java:1141 coarseRead javadoc: MySQL/MariaDB and LOCK IN SHARE MODE only.
- SnapshotIntegrationTest:140 and SerializableIntegrationTest:46-48 say MySQL and MariaDB.
- README 117-118 'every table each read reaches' (until 268 is fixed).
- 8c3 fixed 267.3 and part of 267.5 (update that todo).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 5acbf90 (8c3), verified as stated above.
