---
status: pending
priority: p3
issue_id: "258"
tags: [code-review, sqlserver, usability]
dependencies: []
---

# Function args and column lists holding only hint-named words are refused

## Problem Statement

The legacy-hint rule (TableHints.java:110-115) treats `(` after any word as a hint list.
`SELECT MAX(snapshot) FROM plain`, `WITH x (snapshot) AS (...)` and `FROM (...) AS d (nowait)`
are refused at analyze ("a table hint list that follows no table"), even on plain-only statements.
`INSERT INTO plain (snapshot) SELECT ... FROM <scalardb table>` is refused by `hintsOnOthers` with
a misleading message. SQL Server accepts such columns.

## Proposed Solutions

1. Only take a legacy list after a table name position (after FROM/JOIN/UPDATE/INTO item, or its
   alias), not after a function name; the 251/252 table-function refusal makes a narrower rule
   safe (medium).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
