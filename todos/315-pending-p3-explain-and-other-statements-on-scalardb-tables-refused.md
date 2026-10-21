---
status: pending
priority: p3
issue_id: "315"
tags: [refusal, postgresql]
dependencies: []
---

# EXPLAIN, ANALYZE, views and cursors over ScalarDB tables are refused

## Problem Statement

Statements that name a ScalarDB table other than reads and the supported writes are refused:
`EXPLAIN` (199 statements in T4 on PostgreSQL), `CREATE VIEW` over a ScalarDB table (18),
`ANALYZE t` (11), `DECLARE ... CURSOR` over one, and a function whose body or row type names one
("the statement's table references cannot be enumerated", 75, mostly PL/pgSQL bodies). Some could
be served: `EXPLAIN` of the rewritten statement, `ANALYZE` of the data table, a cursor over the
rewritten read.

## Findings

- T4, PostgreSQL REL_16_15: the report's refusals by reason.

## Proposed Solutions

### Option 1: serve EXPLAIN and ANALYZE
`EXPLAIN <read>` explains the read as the driver rewrites it; `ANALYZE t` analyzes the data (and
metadata) table. **Effort:** Small to Medium.

### Option 2: document the rest
Views, cursors and functions over ScalarDB tables are refused (README, "Preview scope").
**Effort:** Small.

## Acceptance Criteria

- [ ] Decided which to serve; the README lists the rest.

## Work Log

### 2026-10-21 - Found by T4

**By:** Claude Code
