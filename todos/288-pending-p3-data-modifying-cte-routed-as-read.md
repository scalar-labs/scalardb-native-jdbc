---
status: pending
priority: p3
issue_id: "288"
tags: [code-review, postgresql, routing]
dependencies: []
---

# A data-modifying CTE on PostgreSQL is routed as a read

## Problem Statement

`WITH q AS (DELETE FROM t WHERE id = 1 RETURNING id) SELECT ...` (INSERT and UPDATE alike) on a ScalarDB
table is classified SELECT and rewritten: the DELETE runs against the read view `rc_t`, which PostgreSQL
refuses (55000, not auto-updatable). Safe only because the view is a UNION ALL; the driver should refuse it
with 0AN01 itself. Both layouts; not new in 10a. Verified by a probe (row count unchanged).

## Proposed Solutions

1. Refuse a statement whose WITH holds a DML (SqlAst classification) with 0AN01 (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10a (26d93a2).
