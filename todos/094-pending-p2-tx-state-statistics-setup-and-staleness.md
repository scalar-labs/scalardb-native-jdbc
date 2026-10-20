---
status: pending
priority: p2
issue_id: "094"
tags: [code-review, performance, setup, oracle, mysql]
dependencies: []
---

# Histogram setup: MySQL errors go unread, cost and staleness unmeasured

## Problem Statement

`Setup.gatherHistogram` runs on every setup run for every table and records whatever is in flight at that
moment, usually nothing (it runs right after the backfill). The plans it is meant to fix depend on it.

## Findings

- Oracle (reasoned, not measured): with only `tx_state = 3` in a frequency histogram, a missing value is
  estimated at half the least popular frequency, about N/2, so `IN (1, 2)` comes out near N: the
  "half the table" estimate behind the measured 22x. The benchmark gathered after seeding 100 in-flight
  rows, so it did not see this. The nightly auto-stats job also regathers at its own moment.
- MySQL: histograms never refresh automatically; one gathered while many rows were in flight keeps the
  measured 12-69x anti-join and scan plans until setup reruns. `ANALYZE TABLE ... UPDATE HISTOGRAM`
  reports failures as result rows (`Msg_type = 'error'`), which Setup does not read, so it may report a
  histogram that does not exist.
- Cost: Oracle `GATHER_TABLE_STATS` reads the whole metadata table and may cascade to its indexes;
  MySQL before 8.0.24 may stall new statements behind long queries. Not measured.
- `Setup.java:280-282, 325-350`.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Partly refuted.
- **Refuted for Oracle:** after setup with 50k rows and none in flight, `tx_state` has a FREQUENCY
  histogram with one value; `tx_state IN (1, 2)` is estimated at **1 row**, and the plan is INLIST
  ITERATOR over an INDEX RANGE SCAN of the in-flight index. The feared N/2 estimate does not happen.
- **Confirmed for MySQL:** `ANALYZE TABLE ... UPDATE HISTOGRAM ON no_such_column` raises no exception; it
  returns a row `histogram / Error / The column 'no_such_column' does not exist.`, and `Setup.execute`
  ignores result rows, so a failed histogram is reported as gathered.
- MariaDB does not support the syntax; Setup does not gather there (by design).
- Not measured: the cost on large tables, and MySQL staleness when setup runs with many rows in flight.

## Proposed Solutions

### Option 1: stop depending on fresh statistics
Emit an optimizer hint in the fragments (index on the in-flight index, or `CARDINALITY`), or set fixed
column statistics (`DBMS_STATS.SET_COLUMN_STATS` plus a `METHOD_OPT` preference). **Effort:** Medium.

### Option 2: make the gathering correct and optional
Skip when a histogram exists, pass `cascade => FALSE`, read MySQL's result rows, add an option to skip.
**Effort:** Small.

## Recommended Action

Option 1 on Oracle only, as fixed statistics rather than hints: the setup sets a frequency histogram
on `tx_state` assuming 10,000 rows in flight (at most half the table) and locks the table's
statistics, so that neither the nightly job nor a manual gather replaces them. MySQL keeps the
gathered histogram: its plans did not depend on the moment it was gathered.

## Technical Details

See Findings.

## Acceptance Criteria

- [x] Measured: gather with 0 rows in flight, then put 100 in flight, rerun the Oracle suite; same after
      the auto job. MySQL: gather with 10% in flight, commit all, rerun.
- [ ] Oracle: fixed, locked statistics measured at 0, 100 and 10,000 rows in flight, and after the
      nightly job.
- [ ] Inline tables on Oracle: the statistics are set but not locked, since the lock would freeze the
      application's own; the nightly job may replace them.
- [x] Setup fails or warns when MySQL reports a histogram error.

## Work Log

### 2026-10-20 - Measured; fixed, locked statistics on Oracle

**By:** Claude Code
- docs/benchmarks/read-overhead.md, "Statistics, open transactions and paging": Oracle with
  statistics gathered while nothing is in flight and 10,000 in flight later: up to 1654x ("4 tables:
  one region"), NOT EXISTS 60x, LEFT JOIN 17x; gathered at 10,000: 0.7-2.9x; gathered at 10,000 and
  measured at 100: little change. The nightly regather: "IN subquery selecting a non-key" 3.0x to
  17.5x. MySQL: the same either way.
- The setup on Oracle sets the histogram (1: 10,000 or half the rows, 2: 1, 3: the rest) and locks
  the table's statistics; teardown unlocks them. The benchmark's regather skips locked tables
  (ORA-20005), as the nightly job does.

### 2026-10-12 - MySQL error rows read

**By:** Claude Code
- `gatherHistogram` reads ANALYZE's result rows and fails the table's setup on `Msg_type = Error`.
  Open: the cost on large tables and staleness.

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

### 2026-10-21 - Fixed statistics measured and reviewed; not committed

**By:** Claude Code
- `SetupIntegrationTest` on Oracle passes: setup sets and locks a frequency histogram (3 rows:
  endpoints 1:1 2:2 3:4), teardown unlocks it.
- Benchmark on Oracle, the points of the staleness run, three runs each, DRIVER / BASE, before ->
  after: set up at 0 in flight then 10,000 in flight, "4 tables: one region" 1654x -> 0.8x, NOT
  EXISTS 60.5x -> 2.9x, LEFT JOIN 17.3x -> 1.2x, plain JOIN on key 11.5x -> 1.0x; after the
  nightly job, "IN subquery selecting a non-key" 17.5x -> 1.6x. Regressions: "hot: COUNT(*)" 2.7x
  -> 4.3x and "plain table JOIN on non-key" 1.0x -> 1.4x at 100 in flight (the histogram assumes
  up to half a 20k-row table in flight). Paging unchanged (todo 296). Results:
  `.claude/worktrees/stats-results8` against `stats-results2`.
- Review (code-review, high) found, against committing as is:
  1. The in-flight figure is capped at half the row count at setup and then locked: a table created
     empty gets 1 row in flight for good, and with thousands in flight later it is the 1654x plan
     again, which the nightly job can no longer correct.
  2. Locking freezes every statistic of the metadata table (rows, blocks, other columns, indexes)
     at its setup-time values.
  3. On an inline table, `force => TRUE` overrides a lock the user put on the application's table.
  4. Smaller: buckets total n + 1 (`n - f` should be `n - f - 1`); `nullcnt => 0` though inline
     rows written outside ScalarDB have NULL metadata; teardown unlocks but leaves the synthetic
     statistics; `bound()` does not check its placeholder count or escape quotes; no test for the
     empty table or the inline path.
  5. Altitude: a cardinality hint (`OPT_ESTIMATE` / `CARDINALITY`) on the in-flight copy of the split
     read fixes only the statements that need it and leaves the real statistics alone.
- Decision for the user: (a) fix 1-4 and keep fixed, locked statistics (1 needs an absolute
  in-flight figure with row counts that follow the table, which locking prevents: so (a) means not
  locking, and protecting the histogram some other way); or (b) the hint in the split read,
  measured the same way, which touches `SplitRead` (milestone 10's other session). The change stays
  uncommitted in the working tree until then.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.

### 2026-10-12 - Unstable PostgreSQL plan for a split IN subquery

**By:** Claude Code
- "IN subquery on non-key", SPLIT_DRV on PostgreSQL at 100 rows in flight, same code, fresh
  container each run: 1.3x, 1.4x, 4.6x and 5.3x of BASE (0.9 ms). SPLIT_DHD gave 1.1x in one run
  and 4.7-5.0x in others. Likely the random sample of `ANALYZE` choosing between two plans; not
  investigated. Capture both plans (`-Dnativejdbc.bench.explain='^IN subquery'`) when this todo is
  worked.
