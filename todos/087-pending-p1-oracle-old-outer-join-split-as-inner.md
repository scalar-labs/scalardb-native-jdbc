---
status: pending
priority: p1
issue_id: "087"
tags: [code-review, correctness, split-read, oracle]
dependencies: []
---

# Oracle's (+) outer join is split as an inner join

## Problem Statement

Comma joins count as inner joins in `splittableRefs`, and nothing checks for Oracle's old outer-join
syntax. In `SELECT a.x, b.y FROM a, b WHERE a.id = b.aid(+)` both tables are split. In the copy where
`b` is in flight, every committed `a` row without an in-flight `b` match comes back NULL-extended, so
rows appear more than once and with spurious NULLs: rows the original query does not return.

## Findings

- `SqlAst.java:1229-1246` (`splittableRefs`).
- Probe (confirmed): both statements `... FROM a, b WHERE a.id = b.aid(+)` and `... a JOIN b ON a.id =
  b.aid(+)` split into 4 copies on ORACLE.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Confirmed on Oracle: `SELECT a.x, b.x FROM rd."made" a, rd."made" b WHERE a.id = b.id(+) ORDER BY
a.id` returned `[a/null, a/a, b/b, b/null, c/null, c/c]` instead of `[a/a, b/b, c/c]`.

## Proposed Solutions

### Option 1: refuse the split
Return no splittable refs when any expression has `getOldOracleJoinSyntax() != NO_ORACLE_JOIN` (WHERE,
ON, select list); the statement reads through rc_T, which is correct. **Effort:** Small. **Risk:** Low.

### Option 2: treat the (+) table as a nullable side
Keep the preserved table splittable and the (+) table on rc_T. **Effort:** Medium. **Risk:** Medium.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] SplitTest: both `(+)` forms produce no split.
- [ ] Oracle integration case with a `(+)` join and rows in flight returns the native result.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
