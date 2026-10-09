---
status: complete
priority: p2
issue_id: "013"
tags: [code-review, correctness, sql]
dependencies: []
---

# A CTE named like a base table hides the base table from the rewrite

## Problem Statement

`SqlAst` classifies every table reference whose name appears in the statement's CTE names as a CTE reference, including references inside the CTE's own body. In `WITH orders AS (SELECT * FROM orders WHERE id = 1) SELECT * FROM orders`, the inner `orders` is the real table (a non-recursive CTE body cannot see its own name), but the driver files it under `cteRefs` and never rewrites it. A read through the `SqlAst`-based route would then return the in-flight after image of a ScalarDB table, violating invariant I4. `StatementShape` refuses every CTE today, so only `SqlAst`-based rewrites of SELECTs are affected.

## Findings

Lines refer to commit 06c137f.

- `sql/SqlAst.java:257`: table nodes whose resolved name is in `cteNames` go to `cteRefs`, without regard to position.
- Verified by the lead against the built classes: `with orders as (select * from orders where id = 1) select * from orders` yields `tableRefs=[]` and `cteRefs=[orders, orders]`; `StatementShape.of` refuses it (CTE).
- In SQL, a non-recursive `WITH` item's body is resolved before the item's name is in scope, so the inner reference is the base table on every supported engine. Only `WITH RECURSIVE` (and SQL Server's implicit recursion) lets a body refer to its own name.
- Impact today: none on the `StatementShape` route. Impact once the `rc_T` read route uses `SqlAst.joinTokens` on statements with CTEs: the ScalarDB table inside the CTE body is forwarded as the raw table.

## Proposed Solutions

### Option 1: Scope CTE names positionally in the Walker

**Approach:** In the `Walker`, while visiting a `WithItem`'s body, exclude that item's own name from the CTE name set (and the names of items declared after it, which are not yet in scope), unless the statement is `WITH RECURSIVE`. Table nodes in the body that match the item's name become ordinary `tableRefs`. Add tests for the shadowing form and for `WITH RECURSIVE`.

**Pros:**
- Matches SQL scoping; fixes the root cause.
- Small change confined to the walker.

**Cons:**
- SQL Server treats any self-reference as recursive without the keyword; the per-engine rule has to say which form applies (refuse self-referencing bodies on SQL Server is the safe default).

**Effort:** Small (1-2 hours with tests)

**Risk:** Low

---

### Option 2: Refuse any statement whose CTE name collides with a referenced table name

**Approach:** If a CTE name equals the resolved name of any table node in the statement (anywhere), return empty from `SqlAst.parse`. The user renames the CTE.

**Pros:**
- Trivial; fail-closed.

**Cons:**
- Refuses legitimate SQL that ORMs may emit (recursive CTEs for tree fetches by design use the same name).

**Effort:** Small

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:257` - CTE classification
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java` (`Walker extends TablesNamesFinder`) - where `WithItem` bodies are visited
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java` - add the shadowing and recursive cases

**Related components:**
- The `rc_T` read route of design.md 4.2, which will consume `SqlAst` for statements `StatementShape` cannot anchor

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` 4.2 "Reads" (the `rc_T` route), invariant I4
- Found by: feature-dev:code-reviewer (P2-4)

## Acceptance Criteria

- [ ] `with orders as (select * from orders where id = 1) select * from orders` yields `tableRefs=[orders]` (the inner one) and `cteRefs=[orders]` (the outer one)
- [ ] `with recursive t as (select ... from t ...) select * from t` keeps the inner `t` as a CTE reference
- [ ] A statement with two CTEs where the second refers to the first classifies the reference as a CTE
- [ ] Unit tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by feature-dev:code-reviewer (P2-4) during the `/ce:review` run on commit 06c137f
- Reproduced by the lead with a probe against the built classes

**Learnings:**
- `TablesNamesFinder` collects CTE names statement-wide; positional scoping has to be layered on top

---

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- The walker records, for each table node, the non-recursive CTEs whose bodies contain it (the traversal visits a body inside its WITH item, duplicate visits included); a reference inside the body of a CTE of the same name denotes the table, not the CTE. WITH RECURSIVE keeps the self-reference as a CTE reference. Names compare with the engine's rules (038).
- Test: SqlAstTest.cteBody_seesTheTableOfTheSameName_unlessRecursive (the shadowing statement gives one table and one CTE reference; a chain a, b gives t as the only table; the recursive CTE gives no table).

## Notes

- Decide the SQL Server self-reference rule in the per-engine table of design.md section 1 when implementing.
