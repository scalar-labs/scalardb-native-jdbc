---
status: pending
priority: p2
issue_id: "253"
tags: [code-review, sqlserver, correctness]
dependencies: []
---

# A DATETIME key whose milliseconds end in 3 or 7 can never be written

## Problem Statement

Keys are read back as `LocalDateTime` (`readKey`, Writes.java:1030) and bound uncast
(`bind` 931-941, `keyValues` 2111-2125; CAST only on PostgreSQL). mssql-jdbc binds them as
`datetime2`; at compatibility level 160 `datetime` .003 widens to .0033333, so `d.k = u.k`
matches nothing. `INSERT INTO ns.dt (k, x) VALUES ('2024-01-01T00:00:00.003','n')` gets 40001
"a key the statement inserts has metadata in flight" on every retry. Fails closed (the
savepoint undoes the row), but two of three `datetime` values can't be written. Setup's
backfill fails the same way on such a table.

## Findings

- JDBC probe: `datetime` 0 matches; smalldatetime, datetime2, char, uniqueidentifier, real,
  float, decimal, money, nvarchar, datetimeoffset matched.

## Proposed Solutions

1. On SQL Server, CAST bound keys to the column's full type (needs length/precision) (medium).
2. Bind `datetime` keys with an explicit SQL type (small). See also 254.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
