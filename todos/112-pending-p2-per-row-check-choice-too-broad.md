---
status: pending
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

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] Benchmark at 100, 1k and 10k in flight: no shape worse than the better fixed form by more than
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
