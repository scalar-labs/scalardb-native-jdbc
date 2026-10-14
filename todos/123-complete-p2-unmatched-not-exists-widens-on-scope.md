---
status: complete
priority: p2
issue_id: "123"
tags: [code-review, correctness, split-read]
dependencies: []
---

# The unmatched piece moves the ON clause into the WHERE's wider scope

## Problem Statement

The UNMATCHED copy repeats the LEFT JOIN's ON condition inside `NOT EXISTS (...)` in the WHERE. The WHERE
sees every table of the FROM; the ON saw only the tables joined so far. An unqualified column that is
unique in the ON's scope can become ambiguous, so a valid query fails.

## Findings

- `select c.id, o.id, g.id from c left join o on o.cid = c.id and k = 2 join g on g.id = c.id`, with
  `k` in `c` and `g`, not in `o`. Valid; 13 rows on PostgreSQL and MySQL.
- Rewritten unmatched copy: `... WHERE NOT EXISTS (SELECT 1 FROM c_o o WHERE o.cid = c.id and k = 2)
  AND NOT EXISTS (... f_o ...)`.
- PostgreSQL: `column reference "k" is ambiguous`; MySQL: error 1052.
- Same with MySQL comma joins (`from g, c left join o on ... and k = 2`).
- No case found where the wider scope silently picks a different column: errors, not wrong rows.
- `SqlAst.java:2700-2715` (copies the ON), `:2780-2800` (appends to the WHERE).

## Proposed Solutions

### Option 1: require qualified ON columns
- Split NULLABLE only when every column in its ON is qualified (or no table follows the LEFT JOIN and
  no comma join precedes it). Otherwise leave on `rc_`.
- **Effort:** Small. **Risk:** Low.

### Option 2: keep the condition in the ON's scope
- Put the unmatched test into the ON (e.g. a derived table); larger rewrite.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] The statement above runs and matches the plain read.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of 9a00150; reproduced on PostgreSQL and MySQL.

### 2026-10-14 - Fixed

**By:** Claude Code
- A LEFT JOIN is split only when every column under its ON clause, subqueries included, is
  qualified; otherwise its table reads through `rc_`.
- SplitTest cases in `split_leavesOtherShapesOnTheView`; ReviewFindingsIntegrationTest "123" (failed
  with "Column 'n' ... is ambiguous" before the fix).
