---
status: complete
priority: p2
issue_id: "362"
tags: [mysql, mariadb, setup, backfill, decoupled, imported, statistics, performance]
dependencies: ["361"]
---

# MySQL: the metadata table's statistics recounted after the backfill

## Problem Statement

On MySQL, right after the setup backfills an imported decoupled table, the metadata table's InnoDB
persistent statistics still say 0 rows. Until InnoDB recounts the table on its own, reads scan the
metadata table. Measured by `BackfillPlansBenchmark` (todo 361, container, 2026-10-29) on a
300,000-row table:

- a point read: 26.8 ms and 300,007 handler reads, against 1.2 ms and 7;
- an UPDATE by key: 119 ms.

This is the default path, because the setup backfills an imported decoupled table by default
(design section 3, "The backfill as an option"). The setup's `ANALYZE TABLE ... UPDATE HISTOGRAM ON
tx_state` builds only the histogram; it doesn't update the table's or the indexes' statistics.

## Proposed Solution

After the backfill on MySQL (and MariaDB, if its engine takes the same path), run a plain
`ANALYZE TABLE` of the metadata table before the histogram, in the setup and in the `backfill`
command. The failure comes back as a row, as for the histogram. Add a sentence to design section 3.

## Acceptance Criteria

- [x] A plain `ANALYZE TABLE` of the metadata table after the backfill, in `setup` and `backfill`,
      and in their `--dry-run` output.
- [x] `BackfillPlansBenchmark` on MySQL: the backfilled table right after the setup reads like the
      one InnoDB has recounted (the point read about 1.2 ms and 7 handler reads).
- [x] A check of whether MariaDB has the same gap, with the fix there if so.
- [x] Design section 3's MySQL bullet says what the setup does.

## Work Log

- 2026-10-29: found by c7 while measuring todo 361; opened at the user's request.
- 2026-10-29: built on branch mysql-analyze (c7): `Setup.recount`, a plain `ANALYZE TABLE` of the
  metadata table after the backfill on MySQL, in `setup` and `backfill` and their dry runs.
  `BackfillPlansBenchmark` on MySQL right after the setup: the backfilled table's InnoDB row count
  298,700 (was 0), a point read 1.6 ms and 7 handler reads (was 26.8 ms and 300,007), an UPDATE by
  key 3.9 ms (was 119). MariaDB: its statistics also say 0 rows, but its plans don't suffer (a
  point read 0.85 ms on the backfilled table, 1.02 on the other), so no fix there.

- 2026-10-29: landed on main as bd71d2e (the user's word). Closed.
