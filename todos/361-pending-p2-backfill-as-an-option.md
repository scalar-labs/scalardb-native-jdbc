---
status: pending
priority: p2
issue_id: "361"
tags: [setup, tools, backfill, imported, decoupled, inline, oracle, mysql]
dependencies: ["360"]
---

# The backfill as an option of the setup, with a `backfill` command and a `check` for ScalarDB's stuck rows

## Problem Statement

The setup backfills every imported table, decoupled and inline. The driver's correctness doesn't
depend on it (design section 7, item 8). With todo 360, its speed doesn't either. What remains is
ScalarDB's own defect on imported decoupled tables: a rollback over a row with no metadata row
leaves an all-NULL metadata row that ScalarDB can't prepare again (section 1; left to ScalarDB,
2026-10-29).

The backfill itself costs: one transaction over every row and its locks. On an inline table it
rewrites the application's rows. (The ninth Oracle run's buffer busy waits, first read as rows that
rewrite migrated, show in the vendor's own runs too: todo 334's log, 2026-10-29.)

## Proposed Solution

Design: docs/design.md section 3, "The backfill as an option".

- `setup`: backfill by default only for imported decoupled tables; `--backfill` / `--no-backfill`
  override the default.
- A new `backfill` command, which also repairs rows ScalarDB's defect left.
- `check` counts metadata rows with `tx_state` NULL on imported decoupled tables, and exits 1 when
  it finds any.
- On Oracle, a metadata table not backfilled gets statistics as if it were (from the data table's),
  then locked. To be measured on the container first, with MySQL's histogram.
- The decoupled default flips to off once ScalarDB ships the fix; the docs name the version.

## Acceptance Criteria

- [x] The container measurement of plans with and without the backfill (Oracle, MySQL), before
      the statistics part is built.
- [x] `setup` defaults and flags, its output line for a table not backfilled.
- [x] The `backfill` command.
- [x] `check`'s count and exit code.
- [x] Oracle's statistics as if backfilled (MySQL's not needed, the measurement says).
- [x] The tests listed in the design paragraph.
- [x] getting-started.md: the setup's flags, and when to run the backfill.

## Work Log

- 2026-10-29: designed, at the user's request, in parallel with todo 360.
- 2026-10-29: measured (`BackfillPlansBenchmark`, figures in design section 3): on Oracle the
  metadata table's statistics locked at 0 rows made the predicate block's 1,000-key read 3,730 ms
  and 549,847 gets (an INDEX FULL SCAN of the in-flight index per data row); with the statistics
  set as if backfilled, 1.2 ms and 11. MySQL's plans don't suffer; its histogram stays. Built on
  branch backfill-option (c7): setup's default by layout and `--backfill` / `--no-backfill`, the
  `backfill` command (imported decoupled tables only, 6e's call), check's NULL-metadata count, the
  Oracle statistics, the tests, getting-started.md and setting-up-tables.md.
- Found while measuring, not in scope: on MySQL a backfilled metadata table's InnoDB statistics
  still say 0 rows right after the setup, and reads scan it (26.8 ms, 300,007 handler reads for a
  point read) until InnoDB recounts it; an `ANALYZE TABLE` after the backfill would fix it.
