---
status: complete
priority: p1
issue_id: "251"
tags: [code-review, sqlserver, security]
dependencies: []
---

# A carriage return ends a -- comment for SQL Server and the parser, not for the hint pass

## Problem Statement

`TableHints.tokens` (TableHints.java:221-224) ends a `--` comment only at `\n`. JSqlParser and SQL
Server both end it at `\r`. `SELECT x FROM ns.t --c\r(NOLOCK) WHERE id = 1`: the hint pass sees
`(NOLOCK)` as comment and does not blank it; the parser reads `t(NOLOCK)` as a table function, so
the statement has no table refs and goes out plain; SQL Server runs a dirty read of a ScalarDB
table. `(UPDLOCK)` the same way gives an unlocked read where a lock was asked for.

## Findings

- Through the driver on SQL Server: a row prepared by an uncommitted ScalarDB 2PC transaction read
  back as `dirty`; the plain statement read the committed `a`.
- Plain JDBC on m8-mssql confirmed SQL Server ends `--` at CR.

## Proposed Solutions

1. End `--` at `\r` too (small).
2. Root fix, also covers 252: on SQL Server refuse a FROM item the parser reads as a table function
   whose arguments are all hint words, or whose name resolves to a table (small–medium). Do both.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
- 2026-10-19: fixed. TableHints ends a `--` comment at `\r`; on SQL Server analyze refuses a call whose arguments are all hint words (`SqlAst.isHintList`), whatever lexed it apart. Unit tests in TableHintsTest, driver cases in MoreWrites tableHints.
