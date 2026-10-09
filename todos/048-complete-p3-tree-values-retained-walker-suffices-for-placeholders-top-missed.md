---
status: complete
priority: p3
issue_id: "048"
tags: [code-review, performance, sql, quality]
dependencies: []
---

# `treeValues` is retained on every parsed statement, the placeholder union adds nothing, and `SELECT TOP ?` is missed by both walks

## Problem Statement

`SqlAst` keeps the identity set of every syntax-tree node value alive on each parsed statement, although it is needed only during `parse` and by one test. That bounds the statement cache of todo 008: a thousand cached statements from a batch-insert workload could pin close to a gigabyte. At the same time the simplicity reviewer's probe shows the visitor walk alone finds every placeholder the reflective oracle finds, so the tree union is redundant, and there is one placeholder neither finds: the `?` in `SELECT TOP ?`. Finally, `whereColumns` is built from the columns `TablesNamesFinder` visited rather than from the tree, and the oracle test does not check that the two agree.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:141, 215, 247, 378-381`: `treeValues` field, built by `valuesUnder(root)` at parse and exposed for `SyntaxTreeOracleTest`.
- Retained size per `SqlAst`, measured by performance-oracle holding 50 instances: about 905 KB for the 400-row INSERT (AST + token chain + ~72 KB of `treeValues`), 85 KB for the 100-term OR chain, 30 KB for the 20-column `IN (50 ?)` select.
- `SqlAst.java:226-237`: placeholders are the identity union of tree values and `walker.parameters`. code-simplicity-reviewer's probe (`review2/simple/probe/Probe.java`): the walker's deduplicated list equals the reflective count on every statement tried; the tree is always a subset (it misses `?::int` and `-?`), so the union adds nothing.
- `select top ? * from t`: reflection finds the placeholder, walker and tree both give 0; absent from the oracle corpus. `StatementShape` refuses `TOP`, so the single-table path is safe; the `rc_T` route would re-bind one short.
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:567-584` (`predicate`) and `resolveColumnQualifiers` iterate `ast.columnRefs()`, which are only the columns `TablesNamesFinder` visited; in 5.4 it skips, for example, an `ArrayExpression` index with no slice start and `Function` order-by and filter parts. `SyntaxTreeOracleTest` asserts each WHERE column is a tree node, not that it is in `columnRefs` (feature-dev:code-reviewer).

## Proposed Solutions

### Option 1: Check during the walk, count from the walker, build WHERE columns from the tree

**Approach:** Perform the Table and placeholder checks inside `valuesUnder` during `parse` and drop the `treeValues` field (the oracle test calls the package-private `valuesUnder(root)` itself). Take `parameterCount` from the walker alone, with `Top`'s expression added to the walker. Build `whereColumns` from the `Column` values in `valuesUnder(where)`. Add `TOP ?` and the `ArrayExpression`/`Function` cases to the oracle corpus and assert that every `Column` in the tree is in `columnRefs`.

**Pros:**
- Removes the retained set and a redundant pass; closes the `TOP ?` gap; the oracle guards both walks.

**Cons:**
- Three small changes in `SqlAst`, `StatementShape` and the oracle test.

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Drop the field only

**Approach:** Keep the union and the walker-based `whereColumns`; just stop retaining `treeValues` after parse and have the oracle recompute it.

**Pros:**
- Smallest change for the memory concern.

**Cons:**
- Leaves the `TOP ?` placeholder and the `whereColumns` gap in place.

**Effort:** 1 hour

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:141, 215-237, 247, 378-381, 957-978`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:567-584`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SyntaxTreeOracleTest.java`

**Related components:**
- Todo 008 (statement cache: cache rewrite outputs, not `SqlAst` objects), todo 003 (the walk this refines).

**Database changes:** None.

## Resources

- Found by: performance-oracle (P3, retained-size measurement), code-simplicity-reviewer (P3, walker-versus-tree probe), feature-dev:code-reviewer (P3, `columnRefs` coverage).

## Acceptance Criteria

- [ ] `SqlAst` holds no node-value set after `parse` returns.
- [ ] `parameterCount` of `select top ? * from t` is 1 and the oracle corpus contains it.
- [ ] The oracle test asserts every `Column` reachable by reflection under WHERE is in `whereColumns`.
- [ ] Retained size of the 400-row INSERT `SqlAst` drops by the size of the former set.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- performance-oracle measured retained sizes; code-simplicity-reviewer compared walker, tree and reflection on the corpus; feature-dev:code-reviewer read the `columnRefs` dependency of `whereColumns`.

**Learnings:**
- The visitor is the complete enumerator for placeholders except for `TOP`; the tree is the complete enumerator for tables; keep each for what it is complete at.

### 2026-10-09 - Verified

**By:** Claude Code

**Actions:**
- "select top ? * from t where id = ?" gives parameterCount() == 1 for two placeholders, also on a join, so the rc_T route would re-bind one short. The parenthesised "top (?)" is counted correctly (2).

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- The retained treeValues set is gone; the oracle test computes it from a package-private root(). TOP's placeholder is collected by the walker (neither the tree nor the default visitor sees it), so "select top ? * from t where id = ?" counts 2. Every Column in the syntax tree that the walk skipped is added to columnRefs, so whereColumns and the qualifier check see them; the oracle test now asserts that every Column the parser built is a column reference, and the corpus gained TOP ? and FOR UPDATE OF.

## Notes

- Do before 008 lands, so the cache never stores `SqlAst` objects.
