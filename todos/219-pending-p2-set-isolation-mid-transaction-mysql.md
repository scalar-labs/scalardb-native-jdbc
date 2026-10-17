---
status: pending
priority: p2
issue_id: "219"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# setTransactionIsolation inside an open transaction desynchronises Router from the engine on MySQL/MariaDB

## Problem Statement

Proxies.java:229-237, Router.setIsolation. Autocommit off, a read at RC, then
setTransactionIsolation(REPEATABLE_READ): both connectors send SET SESSION TRANSACTION, which applies
to the next transaction; Router takes the SNAPSHOT paths now and reports 4 while innodb_trx shows
READ COMMITTED. Verified (probe Q "mid") on both engines: the same range read returned 0, then 99
after a ScalarDB commit, inside one driver transaction claiming RR. The reverse switch runs RC paths
on an RR engine transaction. pgjdbc refuses the call mid-transaction, so PostgreSQL is safe. New in
8b1: before it RR refused every ScalarDB statement on these engines.

## Proposed Solutions

### Option 1
Refuse setTransactionIsolation when autocommit is off and the transaction has run a statement (as
pgjdbc).
### Option 2
Apply the Router level from the next transaction only.

## Acceptance Criteria

- [ ] A mid-transaction level change cannot make Router and the engine disagree
- [ ] Test in SnapshotIntegrationTest on MySQL and MariaDB

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).
