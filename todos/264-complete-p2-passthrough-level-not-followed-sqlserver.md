---
status: complete
priority: p2
issue_id: "264"
tags: [code-review, sqlserver, isolation]
dependencies: []
---

# A level set in SQL through passthrough is never followed on SQL Server

## Problem Statement

mssql-jdbc's `getTransactionIsolation()` returns the value cached by `setTransactionIsolation`,
never the server's. `Router.ran` (Router.java:1777-1780) reads it back after a passthrough statement
that may set the level, so on SQL Server it never sees a change. Design (~l.393) says the level is
read back from the session.

## Findings

Through the driver: `setTransactionIsolation(REPEATABLE_READ)`, passthrough
`SET TRANSACTION ISOLATION LEVEL READ COMMITTED`, passthrough off: `getTransactionIsolation()` says
4, `sys.dm_exec_sessions` says 2. Autocommit off, read id=1 → a; a ScalarDB tx sets id 1 and 2 to b
and commits; read id=2 → b, id=1 again → b: read skew in a transaction the driver routes as SNAPSHOT
(and caches its in-flight checks per snapshot). The reverse direction is missed too.
SnapshotIntegrationTest's passthrough block is gated to MySQL/MariaDB (l.374).

## Proposed Solutions

1. On SQL Server read `transaction_isolation_level` from `sys.dm_exec_sessions` (1 RU, 2 RC,
   3 RR, 4 SER, 5 SNAPSHOT) after such a statement, and map it (small).
2. Or set the vendor level again after it (as MySQL does) — loses the user's intent.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 51e1c51 (8c2), verified as stated above.
- 2026-10-19: fixed. Router.ran reads the level from sys.dm_exec_sessions on SQL Server, resets the vendor driver to it, and maps lock-based REPEATABLE READ to TRANSACTION_NONE, which refuses ScalarDB statements (getTransactionIsolation reports REPEATABLE READ, true for JDBC). Tested in SnapshotIntegrationTest.sqlServerSession; fails without the fix.
