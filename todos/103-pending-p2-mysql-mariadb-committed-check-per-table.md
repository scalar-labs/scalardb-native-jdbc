---
status: pending
priority: p2
issue_id: "103"
tags: [split-read, performance, mysql, mariadb]
dependencies: ["096"]
---

# MySQL and MariaDB: choose the committed check per table

## Problem Statement

On MySQL and MariaDB the split read excludes in-flight rows from a committed table with the DISTINCT
set: `LEFT JOIN (SELECT DISTINCT <pk> FROM T_meta WHERE tx_state IN (1, 2)) f ... WHERE f.<pk> IS NULL`.
The set is built from every in-flight key on each execution, so even a key lookup grows with the
in-flight count (MySQL 1.0x at 100 rows in flight, 1.6x at 1k, 3.6x at 3k, 7.9x at 10k). A per-row
check, a left join on `T_meta`'s key with `COALESCE(tx_state, 3) = 3`, stays flat for tables reached
through a key or an indexed column, but loses 1.4-5x on scans at every count.

## Findings

docs/benchmarks/read-overhead.md, "ScalarDB's own before-image indexes, and where the per-row check
pays": the per-row check equals the set at 100 rows in flight on selective shapes and wins from 1k.
Plain `NOT EXISTS` is no per-row check on MySQL (it becomes a materialized anti-join, 88x on the IN
subquery at 100); `/*+ NO_SEMIJOIN() */` makes it one on MySQL only.

## Proposed Solutions

### Option 1: per table and copy, by access path and in-flight count (recommended)
Per-row check for a table the statement reaches through its key or an indexed column (an equality or
range on them, or a join on them from another table) when the table has more than a threshold of rows
in flight (about 500; measured crossover between 100 and 1k); the DISTINCT set otherwise. The count is
read through the in-flight index and cached for about a second per table.
- **Effort:** Medium. **Risk:** a cached count can be stale for a second; only the plan changes, not
  the result.

### Option 2: by count only
Simpler; scans lose 1.4-2x above the threshold.

## Acceptance Criteria

- [ ] The emitted form follows the rule; unit tests for the access-path classification.
- [ ] Benchmark at 100 and 10k rows in flight: no selective shape worse than either fixed form by more
      than 20%.

## Work Log

### 2026-10-12 - Recorded

**By:** Claude Code
- From the 096 threshold sweep (100, 1k, 3k, 10k rows in flight).
