---
status: pending
priority: p3
issue_id: "244"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# A passthrough SELECT naming isolation changes the reported level

## Problem Statement

Router.java ~1710: `SELECT @@transaction_isolation = 'REPEATABLE-READ'` through passthrough turned
getTransactionIsolation from 8 to 4 on both engines, nothing set (probe).

## Proposed Solutions

### Option 1
Follow only statements that start with SET (or RESET).

## Acceptance Criteria

- [ ] The probe keeps 8

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
