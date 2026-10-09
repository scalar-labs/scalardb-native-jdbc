---
status: pending
priority: p3
issue_id: "019"
tags: [code-review, sql, quality]
dependencies: []
---

# StatementShape smaller points: scalar functions count as grouped, TOP refused, no hook for the deterministic-predicate rule

## Problem Statement

`StatementShape` makes several small classification choices that are safe (each one fails closed or loses an anchor rather than misrouting a write) but needlessly narrow or inconsistent: any scalar function in the select list marks the statement as grouped and discards the anchor, SQL Server's `TOP` is refused while the equivalent `LIMIT` / `FETCH` is accepted, a parenthesised key predicate is not recognised, and nothing reports the function names inside `WHERE`, so the design's rule that the predicate must be deterministic has no hook to enforce it. Together they shrink the set of ORM statements that take the anchored route.

## Findings

All line numbers refer to commit 06c137f; paths are under `driver/src/main/java/com/scalar/db/nativejdbc/sql/`.

- `grouped` is true for any function in the select list (`StatementShape.java:530`). **Verified** against the built classes: `select upper(x) from t where id = ?` reports `grouped=true`, although the design says aggregates and `GROUP BY`; the anchor is lost for no reason.
- `select top n` is refused (`:404`, `Top` is outside the node allowlist) while `LIMIT` / `OFFSET` / `FETCH` set `limited`. **Verified**: `select top 5 * from t` is refused. Inconsistent for SQL Server, where `TOP` is the idiom.
- `(id = ?)` wrapped in parentheses is not flattened by `flattenAnd` (`:336`), so a parenthesised key predicate is not recognised as key equality. Fail-closed only.
- `FOR UPDATE OF alias` (what Hibernate emits) is likely refused, since the alias appears as a second table node or an unknown table. Unverified.
- `UPDATE t SET t.x = 1` (qualified SET target) is probably refused as `table:t` in `unknownNodes`. Unverified.
- Multi-row `VALUES` is accepted and only counted (`insertRowCount`), so the caller must refuse or expand it; the shape does not decide.
- The deterministic-predicate rule of `docs/design.md` section 4.4 has no hook: nothing reports the function names inside `WHERE` (`:514-528`; `containsFunction` serves `grouped` only), so a volatile function such as `random()` or `now()` cannot be refused or routed differently.
- `flattenAnd` and the `Walker` recurse without a bound (relevant only together with todo 003).

## Proposed Solutions

### Option 1: Fix each point in the shape (recommended)

**Approach:** Distinguish aggregates from scalar functions (an allowlist of aggregate names, plus `AnalyticExpression` and `Function.isAllColumns`); treat `Top` as `limited`; flatten parenthesised terms in `flattenAnd`; expose `whereFunctions()` listing the function names in `WHERE` so the router can apply the deterministic rule; decide multi-row `VALUES` handling in the shape (refuse, or expose per-row values together with todo 012).

**Pros:**
- Each fix is a few lines with a direct test
- Widens the anchored route for real ORM output

**Cons:**
- The aggregate allowlist must be maintained per engine dialect

**Effort:** 2-3 hours

**Risk:** Low

---

### Option 2: Fold into the shape simplification of todo 029

**Approach:** Do these fixes while restructuring `StatementShape` (dropping `Builder` and the boolean flags), so the aggregate test and the function hook are written once against the new shape.

**Pros:**
- Avoids touching the same code twice

**Cons:**
- Delays small, independent fixes behind a larger refactor

**Effort:** included in todo 029

**Risk:** Low

---

### Option 3: Leave as is, document the limits

**Approach:** Record in `docs/design.md` that scalar functions in the select list and `TOP` take the un-anchored route for now.

**Pros:**
- No code change

**Cons:**
- Loses anchors on common statements; the deterministic rule stays unenforceable

**Effort:** 30 minutes

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:336, 404, 514-537`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java`

**Related components:**
- Todo 011 (node allowlist refuses everyday SQL): `Top` is one of the missing types
- Todo 012 (per-row INSERT values)
- Todo 029 (StatementShape simplification)

**Database changes:** none

## Resources

- `docs/design.md` sections 4.1 (routing), 4.2 (anchors, `grouped`), 4.4 (deterministic predicate rule)
- Review agents: feature-dev:code-reviewer (15), architecture-strategist (P3-8)

## Acceptance Criteria

- [ ] `select upper(x) from t where id = ?` reports `grouped=false`; `select count(*) from t` reports `grouped=true`
- [ ] `select top 5 * from t` is accepted with `limited=true`
- [ ] `where (id = ?)` reports `isKeyEquality([id]) = true`
- [ ] A `whereFunctions()` accessor (or equivalent) lists function names found in `WHERE`
- [ ] Multi-row `VALUES` handling is decided in the shape and tested
- [ ] `FOR UPDATE OF alias` and `SET t.x = 1` are probed and either accepted or refused by an explicit test
- [ ] Regression tests keep `DEFAULT VALUES`, `INSERT ... SET` and table-less subqueries refused
- [ ] `./gradlew check` passes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated findings from feature-dev:code-reviewer (15) and architecture-strategist (P3-8)
- Verified `grouped` for a scalar function and the `TOP` refusal against the built classes; the `FOR UPDATE OF` and qualified `SET` cases remain unverified

**Learnings:**
- The shape's `grouped` flag is the only place where "function" and "aggregate" are conflated; the rest of the design distinguishes them

---

## Notes

- Decide the multi-row `VALUES` question together with todo 012, which needs per-row values for the metadata insert anyway.
