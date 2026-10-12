---
status: pending
priority: p2
issue_id: "095"
tags: [code-review, performance, split-read]
dependencies: []
---

# Shapes that fall back to rc_T keep the costs the split was built to remove

## Problem Statement

The plan accepts reading through rc_T for tables on the nullable side of a LEFT JOIN and inside IN or
EXISTS subqueries "at the costs of section 3", but the repository's own numbers for exactly these shapes
are large: LEFT JOIN with ScalarDB on both sides, 1093-1495x on MySQL and 360-856x on MariaDB through
the view, against 1.7-2.5x split; IN subquery on a non-key, 109-478x and 852-1201x, against 1.7-3.7x
split. Optional `@ManyToOne` fetch joins and `IN (SELECT ...)` are routine ORM output.

## Findings

- `SqlAst.java:1183-1253`; docs/plans/proxy-step.md:175; design 786-796.
- Inside a split statement an rc_T reference is repeated in every copy, so a subquery over the view is
  evaluated 2^n times (SplitRead.java:64-78).
- A fifth table sends the whole join to rc_T (measured 24-1800x), while four split tables measure
  0.7-5.2x (SplitRead.java:24, 61).

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Confirmed from docs/benchmarks/read-overhead.md: through the view, LEFT JOIN with ScalarDB on both
sides costs 1093-1495x on MySQL and 376-856x on MariaDB, IN subquery on a non-key 109-1070x on
MySQL/MariaDB (and up to 364x elsewhere); PostgreSQL's view path for the LEFT JOIN is only 1.2-1.8x, so
the cost is engine-specific. The 5-table and per-copy rc_T costs were not measured.

## Proposed Solutions

### Option 1: schedule the measured rewrites
Plan the nullable-side rewrite (per-copy inner join plus the unmatched piece) and the top-level split of
IN subqueries, which the benchmark already measured, in milestone 3b or the next one. Measure a 5-table
join split into 32 copies against rc_T, and 4 split plus 1 on rc_T. **Effort:** Large.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] The plan schedules (or explicitly defers with numbers) the nullable-side and IN-subquery rewrites.
- [ ] The 5-table measurements are recorded.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
