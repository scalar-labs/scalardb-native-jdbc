---
status: complete
priority: p2
issue_id: "255"
tags: [code-review, sqlserver, tests]
dependencies: []
---

# The interleaving test accepts error 1205 as an expected abort on every engine

## Problem Statement

`ScalarDbActor.aborts` (InterleavingIntegrationTest.java:739-755) accepts a `CrudException` with
error code 1205 anywhere in its chain. On SQL Server 1205 is a deadlock; on MySQL/MariaDB it is a
lock wait timeout, which used to fail the test and now counts as an expected abort, weakening
MySQL/MariaDB coverage.

## Proposed Solutions

1. Also require SQLState 40001 (MySQL 1205 is HY000) (small).
2. Pass the engine to the actor and gate to SQL Server.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
- 2026-10-19: fixed. `deadlock` also requires SQLState 40001 (MySQL's 1205 is HY000).
