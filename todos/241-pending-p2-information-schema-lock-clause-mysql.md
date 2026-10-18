---
status: pending
priority: p2
issue_id: "241"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# Plain information_schema queries fail on MySQL at SERIALIZABLE

## Problem Statement

MySQL rejects a locking clause on information_schema (error 3550). Probe: `SELECT COUNT(*) FROM
information_schema.tables ...` at SERIALIZABLE gives HY000/3550; MariaDB accepts it. ORMs and tools
query information_schema inside transactions.

## Proposed Solutions

### Option 1
Skip blocks whose tables are all in information_schema (and performance_schema, mysql? probe), or skip system schemas.

## Acceptance Criteria

- [ ] information_schema queries run at SERIALIZABLE on MySQL

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
