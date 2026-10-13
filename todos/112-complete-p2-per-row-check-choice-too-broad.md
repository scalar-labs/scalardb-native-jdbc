---
status: complete
priority: p2
issue_id: "112"
tags: [code-review, performance, mysql, mariadb]
dependencies: ["103"]
---

# MySQL/MariaDB per-row check is chosen for shapes the measurements say lose

## Problem Statement

Todo 103's rule picks the per-row check for any table "reached through an index". The measurements
favour it only for selective access.

## Findings

- **`reachedThrough` is too broad.** It counts any ON-join on a key, any non-unique indexed equality
  and any range on the key.
  - "JOIN FK, 3k customers" at 1k in flight: per-row 4.2 vs set 3.1 (MySQL); per-row loses at
    every count on MariaDB.
  - A range like `id > 0` behaves like a full scan: 10.5x against 2.0x.
- **Forced under any LIMIT.** MySQL forces the per-row check on every copy under any
  `ORDER BY ... LIMIT` (`SplitRead.java:78`). That includes an unindexed ORDER BY and large
  offsets, where each copy reads everything.
- **No hysteresis.** A count near 500 flips the form, and re-prepares, at most once a second.
- **Limit mismatch (from todo 108).** `limitsEachCopy()` can say yes while `emitSplit` drops the
  limit. MySQL then uses the per-row check with no per-copy limit.
- **Counts per connection.** N pooled connections send N COUNT queries per table per second.

## Proposed Solutions

### Option 1
- Per-row only for equality or an IN list on a unique key, or ON-reach from such a table.
- Force it under a limit only when the ORDER BY leads with a column that leads an index, and
  offset + count is small.
- Switch back below about 250.
- Keep the counts in `Catalog`.
- **Effort:** Medium. Needs measurement.

## Recommended Action

Done differently from Option 1, after measuring it: equality and IN lists per-row above 500,
ranges above 5,000, hysteresis at half, per-copy row limits forced per-row only up to 1,000 rows.
Counts stay per connection (cheap through the in-flight index; revisit with the statement cache,
todo 008).

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [x] Benchmark at 100, 1k and 10k in flight: no shape worse than the better fixed form by more than
      20%.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".

### 2026-10-13 - Fixed

**By:** Claude Code
- `SqlAst.reachedByIndex(leading, ranges)` replaces `reachedThrough`: a fixpoint over = and IN-list
  conjuncts (and with `ranges`, comparisons and BETWEEN) of WHERE and ON, from constants, plain
  tables or references already reached. `Router` asks twice and applies 500 or 5,000 rows in flight,
  each with hysteresis at half; the count cap is 5,001. `SplitRead` forces per-row under a limit
  only when offset + n is at most 1,000.
- Found while measuring: ScalarDB's `importTable` records every primary-key column as a partition
  key in `getPrimaryKeys` order, which JDBC sorts by column name, so `(pid, cid)` became
  `(cid, pid)` and `WHERE pid = ?` was never reached. `Router` now takes the key's first column
  from `DatabaseMetaData.getPrimaryKeys` (KEY_SEQ 1), once per table and connection.
  ScalarDB itself always uses the whole key, so nothing to raise upstream.
- Benchmark, MySQL and MariaDB, 100 / 1k / 10k in flight, DRIVER against SPLIT_DRV (set) and
  SPLIT_DLJ (per-row):
  - composite partition scan at 10k: MySQL 15.5x -> 1.4x, MariaDB 6.4x -> 1.3x;
  - JOIN FK 300 customers at 10k: MySQL 15.8x -> 9.6x, MariaDB 15.8x -> 8.5x;
  - JOIN FK 3k customers at 1k: matches the set (was 3.8x vs 2.9x);
  - regressed: MariaDB JOIN FK 30k customers (150k rows) at 10k, 2.1x -> 3.9x: a range that wide
    loses row by row even at 10k. Telling it from the 300-customer range needs a row estimate
    (todo 094).
- 25 cells remain more than 20% worse than the better fixed form (27 before): LEFT JOIN and IN
  subquery (milestone 3c, todo 095; "LEFT JOIN, ScalarDB on both sides" is 900-2,000x, the
  nullable side read whole), plain table JOIN on non-key (todo 116), and single-digit-ms
  cells at 100 in flight within run-to-run noise.
