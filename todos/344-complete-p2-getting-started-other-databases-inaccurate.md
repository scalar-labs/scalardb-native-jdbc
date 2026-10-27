---
status: complete
priority: p2
issue_id: "344"
tags: [code-review, docs, getting-started]
dependencies: []
---

# getting-started "Other databases": wrong or incomplete per-engine notes

## Problem Statement

- SQL Server lists only ALLOW_SNAPSHOT_ISOLATION, for REPEATABLE_READ; connections are refused (08004) without
  READ_COMMITTED_SNAPSHOT ON and with XACT_ABORT on, and SERIALIZABLE needs snapshot isolation too.
- "MySQL and MariaDB ... An INSERT has no RETURNING": MariaDB has it (Writes.java ~4867; sql-support.md says so).
- Unverified by the review: Oracle "ScalarDB stores names in upper case" (the design suggests ScalarDB records the
  spelling given) and SQL Server keys "from a sequence default, not IDENTITY, which ScalarDB cannot write".
Accuracy review items 4, 6 and two unverified claims.

## Proposed Solutions

1. Fix the two wrong notes, link to configuration.md's database settings, and verify or drop the two unverified ones on their containers.

## Recommended Action

Solution 1.

## Acceptance Criteria

- [x] Each note is confirmed in the code or on the engine's container.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: done. MySQL alone lacks RETURNING; SQL Server lists READ_COMMITTED_SNAPSHOT,
  ALLOW_SNAPSHOT_ISOLATION and XACT_ABORT with a link to configuration.md's database settings (all three
  reported by setup/Check.java). The two unverified notes are confirmed by the crud samples' integration
  tests, which run on the engines (JdbcSampleIntegrationTest: SQL Server keys from a sequence since
  ScalarDB's prepare inserts the key explicitly; Oracle's names upper case, NUMBER as BIGINT); the Oracle
  note now says it is the unquoted DDL that makes the names upper case.
