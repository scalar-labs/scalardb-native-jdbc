---
status: complete
priority: p2
issue_id: "289"
tags: [code-review, sqlserver, inline, setup]
dependencies: []
---

# SQL Server: the filtered in-flight index on an inline table breaks writers without the SET options

## Problem Statement

Setup's in-flight index on SQL Server is filtered (`WHERE tx_state IN (1, 2)`). On an inline table it sits on
the application's own table, and SQL Server fails DML on a table with a filtered index unless the session
has QUOTED_IDENTIFIER, ANSI_NULLS, ANSI_PADDING, ANSI_WARNINGS, ARITHABORT, CONCAT_NULL_YIELDS_NULL on and
NUMERIC_ROUNDABORT off (error 1934): sqlcmd without -I, a procedure created with QUOTED_IDENTIFIER OFF.
Before 10a only ScalarDB and the driver wrote the filtered table. Documented SQL Server rule (review of 10a).

## Proposed Solutions

1. An unfiltered `(tx_state, key)` index on inline tables on SQL Server, as on MySQL (small).

## Recommended Action

## Acceptance Criteria

- [x] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10a (26d93a2).
- 2026-10-20: fixed. an unfiltered (tx_state, key) in-flight index on inline tables on SQL Server; InlineIntegrationTest writes from a QUOTED_IDENTIFIER OFF session after setup, and fails with error 1934 under the filtered index (mutation checked).
