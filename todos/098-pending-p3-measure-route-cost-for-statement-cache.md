---
status: pending
priority: p3
issue_id: "098"
tags: [code-review, performance]
dependencies: ["008"]
---

# The driver's per-statement parse and emit cost is unmeasured, and the cache decision depends on it

## Problem Statement

The plan brings the statement cache (todo 008) forward "if the splitter's parse and emit cost exceeds
the target", but the cost is not measured. A split statement now pays the analyze parse, `emitSplit`,
and a validating parse of a copy several times the user's length; a declined split also runs
`joinTokens` and parses again.

## Findings

- `SqlAst.java:1423, 1467`; read-overhead.md:64-65 still lists the driver's cost as not measured.
- `SplitRead.emit` rebuilds the same three fragments per table in every copy (SplitRead.java:64-78).
- Binding scans all `parameterSources` per setter, with one reflective call per copy (Proxies.java:342-360).

## Proposed Solutions

### Option 1: microbenchmark Router.route
1/2/4-table shapes with 5 and 50 columns, with and without ORDER BY; decide 008 from it; then build
fragments once per table and precompute vendor indexes per user index in `Route`. **Effort:** Small.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] Route cost recorded and compared with 008's target.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
