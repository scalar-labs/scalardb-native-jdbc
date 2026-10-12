---
status: pending
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

- [ ] Results recorded in docs/benchmarks/read-overhead.md.
- [ ] Fragments project only referenced columns unless `*` is used, if the measurement supports it.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
