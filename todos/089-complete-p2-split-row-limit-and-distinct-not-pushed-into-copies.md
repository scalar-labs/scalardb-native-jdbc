---
status: complete
priority: p2
issue_id: "089"
tags: [code-review, performance, split-read]
dependencies: []
---

# ORDER BY with a row limit, and DISTINCT, apply only on top of the copies

## Problem Statement

`lift` keeps ORDER BY, LIMIT/OFFSET/FETCH/TOP and DISTINCT only over `(copies) v`; the copies carry none
of them. The benchmark's only measured case filtered down to about 5 rows, so lifting cost nothing
there. For pagination (`... ORDER BY id DESC LIMIT 20`, `OFFSET 10000 ... FETCH NEXT 20`), which ORMs
emit constantly, a native plan stops after 20 rows of an ordered index scan; over a UNION ALL derived
table MySQL and MariaDB materialize every matching row and filesort, Oracle may compute ROW_NUMBER over
the whole view. DISTINCT on top similarly loses MySQL's loose index scan.

## Findings

- `SqlAst.java:1407-1470`, `lift` at 1505. Performance reviewer's estimate: 100-1000x on large tables
  for MySQL/MariaDB; PostgreSQL (Merge Append) and SQL Server (Top over Merge Concatenation) may stop
  early. Not measured.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Confirmed. `SELECT x FROM big ORDER BY id DESC LIMIT 20` (FETCH FIRST on Oracle), best of 7, driver
against the same query on the data table:
- PostgreSQL 200k rows: 42.1 ms vs 0.4 ms (100x)
- MySQL 200k rows: 84.0 ms vs 0.8 ms (111x)
- MariaDB 200k rows: 177.9 ms vs 0.5 ms (352x)
- Oracle 50k rows: 13.2 ms vs 1.2 ms (11x)
- SQL Server not run (no bulk load in the probe).
The split's other per-row work is included in these numbers; a per-copy limit has not been tried yet.

## Proposed Solutions

### Option 1: measure, then push down
Add benchmark shapes: ORDER BY id LIMIT 20 over 500k rows; a non-key filter matching 15k rows with
ORDER BY, LIMIT 20, OFFSET 0 and 10000; DISTINCT over an indexed column with 100k rows. Compare BASE,
the current lift, and lift plus a per-copy `ORDER BY ... LIMIT offset+n` (and per-copy DISTINCT) on all
five engines. **Effort:** Medium. **Risk:** Low.

## Recommended Action

Measured 2026-10-12 (docs/benchmarks/read-overhead.md, "Row limits and DISTINCT inside each copy").
Push ORDER BY and a limit of offset + n into each copy, per engine:

- PostgreSQL, MariaDB: the current committed form, the limit inside each copy (1.2-2.5x).
- MySQL: a per-row committed form under a limit, the left join on the metadata or NOT EXISTS with
  `NO_SEMIJOIN` (1.3-5.7x); the DISTINCT set and plain NOT EXISTS materialize and stay 70-360x.
- Oracle: `WHERE ROWNUM <= n` around each ordered copy (1.1-2.1x; FETCH FIRST does not stop early).
- SQL Server: nothing (already 1.0-4.1x).
- DISTINCT: push only on Oracle; MySQL and MariaDB stay 14-23x, a separate problem.

Not yet implemented in the driver. Open: whether MySQL's choice should be the left join (what the
mixed SQL Server form already uses) or the hint, and large offsets (3-18x outside PostgreSQL).

## Technical Details

See Findings.

## Acceptance Criteria

- [x] Benchmark results for the shapes above recorded in docs/benchmarks/read-overhead.md.
- [x] Per-copy limits or DISTINCT emitted on the engines where they measure better.

## Work Log

### 2026-10-12 - Measured

**By:** Claude Code
- New shapes and modes in ReadOverheadBenchmark (SPLIT_DRV, SPLIT_DRVP and per-form variants); results
  and MySQL's plan in the benchmark document.

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.

### 2026-10-13 - Implemented

**By:** Claude Code
- What the copies do:
  - Each copy sorts by the statement's ORDER BY in its own column names and keeps offset + n rows.
  - That is `LIMIT` on PostgreSQL, MySQL and MariaDB, and `ROWNUM` on Oracle; SQL Server needs
    nothing.
  - MySQL uses the left join on the metadata in every copy.
- Where it applies: literal offset and count, or a count placeholder without offset. DISTINCT and
  aggregates stay on top only.
- Not done:
  - DISTINCT inside the copies on Oracle, the only engine it helped (1.8x down to 1.2x);
  - DISTINCT on MySQL and MariaDB, which stays 14-23x and has no form found.
- Tests: unit emission tests; integration pagination with rows in flight on every engine.

