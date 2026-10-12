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

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] Measured: gather with 0 rows in flight, then put 100 in flight, rerun the Oracle suite; same after
      the auto job. MySQL: gather with 10% in flight, commit all, rerun.
- [x] Setup fails or warns when MySQL reports a histogram error.

## Work Log

### 2026-10-12 - MySQL error rows read

**By:** Claude Code
- `gatherHistogram` reads ANALYZE's result rows and fails the table's setup on `Msg_type = Error`.
  Open: the cost on large tables and staleness.

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

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
