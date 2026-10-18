---
status: pending
priority: p3
issue_id: "246"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# Stale comment and test timing after 77e2295

## Problem Statement

- Catalog.java:69-70 still says "before the statement the design requires to come first".
- SnapshotIntegrationTest.plainTables: the deadlock case sleeps 300 ms against a 1s lock wait; on a
  loaded machine the other side can time out first and ds reports 1205, not 40001. Poll
  performance_schema.data_lock_waits / information_schema.innodb_lock_waits instead.
- No tests for TABLE, #, MariaDB LOCK IN SHARE MODE, information_schema, prepared across levels (239-243).

## Proposed Solutions

### Option 1
Fix the comment; poll for the wait; add the tests with 239-243.

## Acceptance Criteria

- [ ] Comment current; deadlock test waits on the lock, not a sleep

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
