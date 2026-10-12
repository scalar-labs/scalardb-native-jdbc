---
status: complete
priority: p2
issue_id: "092"
tags: [code-review, correctness, split-read]
dependencies: []
---

# Lifting changes labels and result-set metadata

## Problem Statement

The head of a lifted statement is built with `String.join(" ", "SELECT", prefix, outer).replace("  ", "
")`, and the replace runs over the user's labels and prefix tokens too. Labels and metadata also differ
from the original in smaller ways.

## Findings

- Confirmed by probe: `SELECT x AS "a  b" FROM a ORDER BY x` returns the label `"a b"`. The prefix
  (TOP expressions, hint comments) is rewritten the same way (SqlAst.java:1463-1465).
- SQL Server, suspected: `SELECT NAME FROM t` labels the column `name` natively but `NAME` after `AS NAME`.
- On the lifted path `getTableName`, `getSchemaName` and nullability come from the derived table `v`, so
  they are empty or unknown (the rc_T route has the same property).

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Partly confirmed.
- The double-space label change is real on all five engines: `x AS "a  b"` (backticks on MySQL and
  MariaDB) came back labelled `a b`.
- **Not reproduced:** the SQL Server label case. `SELECT X FROM rd.made ORDER BY id` is labelled `X` both
  through the driver and natively. Dropped.
- The ResultSetMetaData table-name point was not tested (rc_T has the same property).

## Proposed Solutions

### Option 1: join only non-empty parts
Build the head from the non-empty parts instead of post-editing it; for unaliased Column items on SQL
Server, consider leaving the label to the engine (`v.cN` with the original name). **Effort:** Small.
**Risk:** Low.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [x] SplitTest: a quoted alias with two spaces keeps its label.
- [x] ~~The SQL Server label case is checked by an integration assertion~~: not reproduced (`X`
      both ways), dropped.

## Work Log

### 2026-10-12 - Fixed

**By:** Claude Code
- The head is built from its non-empty parts; the double-space replace is gone. SplitTest and the
  probe: `"a  b"` keeps its label on all five engines.
- Not changed: the SQL Server label case (not reproduced) and ResultSetMetaData table names (rc_T has
  the same property).

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
