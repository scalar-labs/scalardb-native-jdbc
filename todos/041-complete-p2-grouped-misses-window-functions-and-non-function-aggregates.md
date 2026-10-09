---
status: complete
priority: p2
issue_id: "041"
tags: [code-review, correctness, sql]
dependencies: []
---

# `grouped()` is false for window functions and the aggregate forms that are not `Function` nodes

## Problem Statement

`StatementShape.grouped()` tells the read path whether the user's projection must be applied on top of the two-arm union rather than pushed into each arm. It is derived from `containsFunction`, which looks only for JSQLParser's `Function` class. Window functions and two engine-specific aggregate forms are different classes, so a select over them reports `grouped() == false`; a rewrite that trusts that computes the window or aggregate per arm and returns wrong rows.

## Findings

- `StatementShape.java:586-597` (`containsFunction`): iterates the select item's tree values and matches `node instanceof Function` only; used at `:468` to set `functions`, which with `DISTINCT`, `GROUP BY` and `HAVING` makes `grouped`.
- In JSQLParser 5.4 source: `row_number() over (...)`, `sum(x) over ()` and every `... OVER (...)` parse as `AnalyticExpression`, which `extends ASTNodeAccessImpl implements Expression` and is not a `Function`; MySQL `GROUP_CONCAT(...)` is `MySQLGroupConcat`; `JSON_ARRAYAGG` / `JSON_OBJECTAGG` are `JsonAggregateFunction`.
- `StatementShapeTest.java:241` accepts `select x, row_number() over (order by y) from t where id = ?` but asserts only that the shape is present, never `grouped()`.
- The design (4.2) says grouping, aggregation and window evaluation sit above the union; the shape's Javadoc says the same. By class hierarchy reading; the runtime result was not executed, and the read rewrite that would consume it does not exist yet.

## Proposed Solutions

### Option 1: Treat every non-column select item as grouped

**Approach:** In `select()`, set `functions = true` whenever a select item is anything but a plain `Column` or `*` (the `plain = false` branch already exists at `:465`). Hoisting the projection above the union is always correct; it only costs the anchor the design uses for key reads.

**Pros:**
- One line; fails closed for every present and future expression type, which is the same stance the commit took for modifiers.
- No dependency on JSQLParser class names.

**Cons:**
- `select x + 1 from t where id = ?` loses its key anchor needlessly.

**Effort:** 30 minutes plus tests

**Risk:** Low

---

### Option 2: Name the three classes

**Approach:** Extend `containsFunction` to `Function`, `AnalyticExpression`, `MySQLGroupConcat` and `JsonAggregateFunction`.

**Pros:**
- Keeps the anchor for arithmetic and `CASE` projections.

**Cons:**
- A fourth aggregate-like class in a future JSQLParser (or an engine-specific one such as Oracle `LISTAGG`, which may parse as a `Function` today but need not) slips through again; the list must be maintained against every parser upgrade.

**Effort:** 30 minutes plus tests

**Risk:** Low to medium

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:455-470` - `select()` item loop, `functions`/`grouped`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:586-597` - `containsFunction`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java:241` - accepts the window function without asserting `grouped()`

**Related components:**
- The read path of design 4.2 (projection hoisted above the two-arm union), the anchor rule of 4.4.

**Database changes:** None.

## Resources

- `docs/design.md` sections 4.2 and 4.4.
- Found by: feature-dev:code-reviewer (P2).

## Acceptance Criteria

- [ ] `grouped()` is true for `row_number() over (...)`, `sum(x) over ()`, `group_concat(x)` and `json_arrayagg(x)` projections.
- [ ] `grouped()` is still true for `count(*)`, `DISTINCT`, `GROUP BY` and `HAVING`, and false for `select id, x from t`.
- [ ] If Option 1: `grouped()` is true for any non-column projection and the test states that as the rule.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer checked the JSQLParser 5.4 class hierarchy of `AnalyticExpression`, `MySQLGroupConcat` and `JsonAggregateFunction` against the `instanceof Function` test and found the window-function test case asserting nothing about grouping.

**Learnings:**
- Removing the node-type allowlist (todo 003/011) made such expressions reach `select()`; the shape's own checks now carry the whole burden.

### 2026-10-09 - Not reproducible; closed

**By:** Claude Code

**Actions:**
- Probed on the current build with Engine.MYSQL: row_number() over (...), sum(x) over (), group_concat(x) and count(*) all give grouped() == true. The window and aggregate classes carry a Function node in their syntax subtree, so containsFunction finds it. The finding was from reading the class hierarchy without running it. No change needed; a regression test asserting grouped() for these forms is still worth adding when StatementShapeTest is next touched.

## Notes

- Option 1 is in the spirit of the "assume the worst, it only costs the anchor" comment already in `containsFunction`.
