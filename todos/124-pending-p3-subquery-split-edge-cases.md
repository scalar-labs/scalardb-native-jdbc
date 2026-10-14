---
status: pending
priority: p3
issue_id: "124"
tags: [code-review, correctness, split-read, mysql, postgresql]
dependencies: []
---

# Subquery split: three rare shapes return wrong rows

## Problem Statement

Three uncommon subquery shapes are split when they should be left on `rc_`. Each returns wrong rows.

## Findings

1. **`selectsKey` folds the qualifier's case.** `select O.id from c O where O.x in (select O.id from o
   where o.s = 1)`: on MySQL with case-sensitive aliases (and PostgreSQL with a quoted `"O"`), `O.id` is
   the outer c's column, but `lowerUnquoted` lower-cases it and `selectsKey` (`SqlAst.java:1945`) takes
   it for the key. Placed INNER; the subquery yields the outer value in both copies. MySQL run: `1, 2`
   becomes `1, 2, 1, 2`. In `correlated()` the same folding only falls back to the CASE form (safe).
2. **MySQL `!` binds tighter than IN.** `where ! c.x in (select o.cid from o)` is `(!c.x) IN (...)` on
   MySQL, but JSqlParser builds NOT(IN), and the IN_EXPRESSION splice writes `!(c.x in(...) OR c.x
   in(...))`. MySQL run: `1, 2` becomes `2`. Same family: `NOT x IN` under `HIGH_NOT_PRECEDENCE`.
   (`SqlAst.java:1746`, splice at `:2742`.)
3. **DISTINCT ON in an IN subquery (PostgreSQL).** `where c.x in (select distinct on (o.a) o.cid from
   o)` is split per piece; the original picks one row per `a` from the union. Run: `1` becomes `1, 2`.
   Top-level DISTINCT ON is refused (`:1397`), the subquery's is not (`:1774-1786`).

## Proposed Solutions

### Option 1
- 1: compare the qualifier with the engine's case rule, or require it written exactly as the alias.
- 2: on MySQL/MariaDB refuse the split when the IN's left operand is preceded by `!` (or splice only
  the subquery, keeping `(x)` outside).
- 3: refuse an IN subquery with `getDistinct().getOnSelectItems() != null`. EXISTS is unaffected.
- **Effort:** Small. **Risk:** Low.

## Recommended Action

## Acceptance Criteria

- [ ] SplitTest cases for each shape: not split (or, for 1, not placed INNER).

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of 9a00150; each reproduced against logical tables (1, 2 on MySQL 8.4; 3 on PostgreSQL).
