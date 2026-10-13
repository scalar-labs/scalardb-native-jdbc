---
status: complete
priority: p2
issue_id: "096"
tags: [code-review, performance, split-read, benchmark]
dependencies: []
---

# The split's cost with many rows in flight, wide tables and composite keys is unmeasured

## Problem Statement

The benchmark held 100 rows in flight per table, 2-3 non-key columns and single-column keys. Real
ScalarDB tables often have 30-100 columns and partition plus clustering keys, and stuck rows (todo 085)
or large ScalarDB batches can put 10k-1M rows in flight.

## Findings

- DISTINCT_SET (MySQL, MariaDB, SQL Server 3+ tables) builds the full in-flight key set per committed
  table per copy, with no join predicate pushed in (SplitRead.java:124-141); up to 32 materializations
  per 4-table execution. In-flight copies filter on `m.before_*`, which has no index (157-179).
- Every fragment lists every data column (108-113, 158-163): about 100 KB of SQL for a 4-table
  statement over wide tables, beyond MySQL Connector/J's `prepStmtCacheSqlLimit` default (256) and
  costly for pgjdbc's statement cache; PostgreSQL already needs 14 ms to plan the narrow 4-table split.
- Composite keys (`keyJoin`, DISTINCT over several key columns) are not measured.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

A measurement gap, not a confirmed defect: nothing in the repository measures these shapes. The
column-list growth and the Connector/J `prepStmtCacheSqlLimit` default (256) are facts; their effect is
not measured.

## Proposed Solutions

### Option 1: measure, and project only what is referenced
Run key lookup, non-key filter and 4-table join with 100, 10k and 100k rows in flight (HD vs AJ on
MySQL/MariaDB, MH vs MX on SQL Server), a 50-column variant, and a 2-column key. Project only referenced
columns plus keys in the fragments (keep the full list under `*`). **Effort:** Medium.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [x] Results recorded in docs/benchmarks/read-overhead.md.
- [x] Fragments project only referenced columns unless `*` is used, if the measurement supports it.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.

### 2026-10-12 - Part 1: rows in flight measured

**By:** Claude Code
- 100, 10k and 100k rows in flight per table, all five engines, the driver form against NOT EXISTS,
  the DISTINCT set, MX and MH: docs/benchmarks/read-overhead.md, "Many rows in flight". Every mode
  returned BASE's rows.
- The in-flight copies read every in-flight row (no index on `before_*`; SQL Server scans the whole
  metadata table), and the DISTINCT set is built in full per execution. At 10k: 3-6x on PostgreSQL
  and Oracle, 5-107x on MySQL and MariaDB, 32-375x on SQL Server for selective shapes.
- Candidate fixes recorded there (before-image indexes from setup; NOT EXISTS on MySQL and MariaDB
  once the in-flight count is large); not implemented. Parts 2 (wide tables) and 3 (composite keys)
  next.

### 2026-10-12 - Parts 2 and 3: wide tables and composite keys measured

**By:** Claude Code
- docs/benchmarks/read-overhead.md, "Wide tables and composite keys": a 50-column table and a
  `(pid, cid)` key, the driver form against `SPLIT_DRVN` (referenced columns only), prepared and
  fresh (`-Dnativejdbc.bench.fresh=true`). Every mode returned BASE's rows.
- Width matters on MySQL and MariaDB on every execution (client-side prepare: 4-table join 5.9 ms
  vs 2.3 ms narrow) and on every fresh parse elsewhere (Oracle 189 vs 55 ms); not with a cached plan.
- Composite keys cost what single keys do.
- The measurement supports projecting only referenced columns (second acceptance criterion);
  not implemented yet.

### 2026-10-12 - Independent of the in-flight count: per-row check and before-image indexes

**By:** Claude Code
- docs/benchmarks/read-overhead.md, "Making the split independent of the in-flight count": with
  before-image indexes (setup) and a per-row committed check (MySQL, MariaDB), the selective shapes
  at 10k in flight return to their 100-row ratios on all five engines; no measurable write cost.
  SQL Server can instead include the before image in its in-flight index for most of the gain.
- The per-row check loses on scans and large joins at small counts on MySQL and MariaDB (10x vs
  2.1x on a 100k-row count), so the committed form there needs a per-statement choice; not
  designed. Open at 10k: IN subquery, joins returning many rows, full scans.

### 2026-10-12 - ScalarDB's index shape and the MySQL/MariaDB threshold

**By:** Claude Code
- ScalarDB's own plain `before_x` indexes serve the in-flight copy like the filtered ones; the
  driver creates none, the setup reports gaps (todo 102). MySQL and MariaDB: the per-row committed
  check wins from about 1k rows in flight on selective shapes, the set on scans (todo 103).

### 2026-10-13 - Referenced columns only

**By:** Claude Code
- `SqlAst.columnsReadFrom` and `SplitRead.columnsRead`: each copy's fragment reads only the columns
  qualified by its table and every unqualified one; all of them under `*`, `t.*`, NATURAL or USING.
- All six integration configurations pass.
