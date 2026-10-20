---
status: complete
priority: p3
issue_id: "290"
tags: [code-review, inline, setup, performance]
dependencies: []
---

# The backfill of an inline table write-locks the application's table

## Problem Statement

The stamp UPDATE runs over the whole table in one statement; on an inline table that is the application's
data table, every row write-locked until it ends, escalated to a table lock on SQL Server. Before 10a only
the separate metadata table was locked. Code trace (review of 10a).

## Proposed Solutions

1. Document it: the backfill belongs in a quiet window, as design section 1 says (small).
2. Batch by key range (medium; the existing ponytail note).

## Recommended Action

## Acceptance Criteria

- [x] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10a (26d93a2).
- 2026-10-20: fixed. documented at the backfill (the ponytail note); batching stays for when a large table needs it. The design already puts the backfill in a quiet window.
