---
status: complete
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

Option 1. The rewrites are designed in docs/design.md 4.2, "Which tables are split", and scheduled as
milestone 3c of docs/plans/proxy-step.md, after 3b. Still to do before the limit is fixed: the
5-table measurements.

## Technical Details

See Findings.

## Acceptance Criteria

- [x] The plan schedules (or explicitly defers with numbers) the nullable-side and IN-subquery rewrites.
- [x] The 5-table measurements are recorded. Moved to todo 119; the limit stays at 16 copies.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

### 2026-10-13 - Design note

**By:** Claude Code
- Design 4.2 now splits four kinds of reference:
  - inner (2 pieces);
  - nullable (3 pieces: an inner join per copy, plus an unmatched piece over an empty fragment with
    two `NOT EXISTS`);
  - semi-joined IN/EXISTS top-level conjuncts (2 pieces, first match by `NOT EXISTS` unless the
    subquery selects the key);
  - quantifiers elsewhere (NOT IN, NOT EXISTS, IN/EXISTS under OR or NOT), written twice inside
    each piece with AND or OR. These are exact in three-valued logic and add no pieces.
- The limit now counts pieces; its value waits on the 5-table measurement.
- Plan milestone 3c added.
- Not measured yet: the non-key first-match `NOT EXISTS`, and the expression forms.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.

### 2026-10-14 - Milestone 3c implemented

**By:** Claude Code
- `SqlAst.split` places each ScalarDB reference (inner, nullable, semi-joined, in an expression);
  `emitSplit` writes each copy's pieces with splices, runs of the statement's tokens under other
  replacements; `SplitRead` enumerates the pieces, at most 16 copies.
- Forms changed by the benchmark: the semi-joined IN's correlated `NOT EXISTS` (the CASE form
  measured 1205x on PostgreSQL), NOT EXISTS as two (152-175x on MySQL as NOT over an OR),
  `NO_SEMIJOIN` on MySQL's in-flight writing (156x to 13.6x), the per-row check for nullable and
  subquery tables (LEFT JOIN 22.5x to 1.8x on MySQL at 10k).
- Through the driver: LEFT JOIN 1.2-2.3x on every engine; IN and NOT EXISTS 1.1-7.4x except on
  MySQL (todo 118). Rows checked against the committed image on every engine
  (`ReadIntegrationTest.outerJoinAndSubqueryReads`).
- `Connection.nativeSQL` returns the routed statement, which the benchmark's explain uses.
