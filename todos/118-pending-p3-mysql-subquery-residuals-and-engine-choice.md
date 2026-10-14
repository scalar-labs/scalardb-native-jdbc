---
status: pending
priority: p3
issue_id: "118"
tags: [performance, mysql, sqlserver, oracle, split-read]
dependencies: ["095"]
---

# Subqueries through the driver: MySQL residuals, and SQL Server and Oracle against the view

## Problem Statement

Milestone 3c splits LEFT JOINs and subqueries; most shapes are within a few times the plain query,
but some are not, or are slower than the view they replaced (`docs/benchmarks/read-overhead.md`,
last section):

- MySQL at 10,000 rows in flight: the IN over the key 16.3x (10.1x through the view before), over a
  non-key 26.7x. The hand-written form is as slow (16x).
- MySQL NOT EXISTS at 100 rows in flight: 13.6x. With `NO_SEMIJOIN` each probe reads every row in
  flight through the in-flight index, not the before image's index.
- SQL Server and Oracle at 100 rows in flight: the non-key IN 5.0x and 5.4x, against 3.5x and 2.8x
  through the view; SQL Server's LEFT JOIN 2.1-2.2x against 1.1x.
- PostgreSQL's key IN at 10,000 rows in flight: 5.1x against 3.1x.

## Findings

- Not explained yet for the MySQL IN: likely the outer table, reached only through the IN, which
  `reachedByIndex` does not follow, so it keeps the DISTINCT set of 10,000 keys.
- `-Dnativejdbc.bench.explain` shows the driver's SQL for `DRIVER` (through `Connection.nativeSQL`).

## Proposed Solutions

1. Let `reachedByIndex` follow `x IN (subquery)` from a reached subquery table to x's table.
2. On MySQL, steer the in-flight probe to the before image's index (an index hint naming it).
3. Per engine, keep a subquery's table on `rc_T` where the view measured better (SQL Server,
   Oracle), if that holds at more shapes than one.

## Acceptance Criteria

- [ ] The MySQL plans at 10,000 rows in flight read and the cause recorded.
- [ ] Each residual fixed or recorded as accepted, with numbers.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the milestone 3c benchmark runs.
