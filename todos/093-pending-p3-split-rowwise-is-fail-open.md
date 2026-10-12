---
status: pending
priority: p3
issue_id: "093"
tags: [code-review, correctness, split-read, architecture]
dependencies: []
---

# The row-wise check refuses known aggregate node types instead of accepting known row-wise ones

## Problem Statement

`RowWise` refuses `Function` nodes not in `ROW_FUNCTIONS` (71 names), but refuses other aggregate-like
node types only by naming eight visitor overrides. A node type a parser upgrade adds, which aggregates
per copy, would pass and give duplicated or wrong aggregates: the opposite of the "touch nothing unless
everything is understood" rule, given that rc_T is always correct. Conversely, the performance review
notes that the function allowlist sends ordinary ORM SQL (`now()`, `date_format`, JSON functions,
schema-qualified functions) to rc_T, which costs 24-1800x on joins, and nothing reports why a statement
fell back.

## Findings

- `SqlAst.java:1094-1171` (`ROW_FUNCTIONS`), 1255-1330 (`RowWise`).
- The reviews disagree on the function list: shrink it to what is exercised (simplicity) versus widen it
  with per-engine aggregate catalogs (performance). Both agree the node-type check should fail closed.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Not a current bug. Every aggregate form tried is refused: LISTAGG ... WITHIN GROUP,
STRING_AGG, JSON_OBJECTAGG, ARRAY_AGG with ORDER BY, COUNT(*) FILTER, XMLAGG, KEEP (DENSE_RANK ...),
GROUPING and CAST(MULTISET ...), on PostgreSQL, Oracle, MySQL and SQL Server. The only one that split,
`x = ANY(ARRAY(SELECT ...))`, is row-wise and correct. The fail-open structure remains a risk on a
parser upgrade, so this stays as hardening at P3.

## Proposed Solutions

### Option 1: node-type allowlist, measured function list
Accept only known row-wise node types (columns, literals, parameters, arithmetic, Parenthesis, Cast,
CASE, comparisons, `Function` in `ROW_FUNCTIONS`) and refuse the rest. Add a fallback-reason log line or
counter, run an ORM statement corpus through it, and size `ROW_FUNCTIONS` from the result.
**Effort:** Medium. **Risk:** Low.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] An unknown expression node type makes the statement fall back (unit test with a node outside the
      allowlist).
- [ ] Fallback reasons are observable, and the function list is justified by a corpus count.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
