---
status: pending
priority: p2
issue_id: "012"
tags: [code-review, architecture, sql]
dependencies: []
---

# The rewrite API is a whole-statement substitution; sections 4.2 and 4.3 need fragments with placeholder ordinals and INSERT values

## Problem Statement

`SqlAst.Naming` and `joinTokens` replace table names (and optionally column names) across the whole statement and hand back one string. The read and write rewrites the design specifies are compositions of fragments: the two-arm UNION ALL of 4.2 needs the user's WHERE emitted twice (once as `P`, once as `P_before` with `before_` columns) inside a new statement, with the user's projection, ORDER BY and LIMIT placed on top as a derived table, and the placeholders of `P` duplicated and re-bound. The write path of 4.3 needs the SET list, the WHERE re-applied per key, each INSERT row's key values, and the classic-layout stamp spliced in. None of this is reachable through the current API: there is no way to emit a subtree, nothing reports which `?` ordinals fall inside a fragment, and the INSERT shape exposes only column names and a row count.

## Findings

Lines refer to commit 06c137f.

- `sql/SqlAst.java:90-124` (`Naming`) and `:467-512` (`joinTokens`) emit the whole statement; `:546-564` applies `columnReplacementFor` to every `ColumnRef`, projection and ORDER BY included. Under `columnReplacementFor`, `select x from t where x = ?` becomes `select before_x ... where before_x = ?`, which is not the second arm the design wants (the arm projects `before_x AS x` and substitutes only in WHERE).
- `:370-378` exposes only `parameterCount()`; placeholder positions are not exposed, so re-binding (design.md 1, "Placeholders") is impossible as it stands (also noted by feature-dev:code-reviewer, item 16).
- `StatementShape.java:441-474` (`insert`) exposes `insertColumns()` and `insertRowCount()`, nothing per value. The `T_meta` insert needs each row's key values (placeholder ordinal or literal text); multi-row VALUES multiplies this.
- The simplicity reviewer adds (item 14) that `Naming.columnReplacementFor` plus `plan.columnNames` (`SqlAst.java:121-123, 558-563, 627`) is whole-statement column renaming justified only by `SqlAstTest:143`; and (item 7) that `ordered()`, `limited()`, `insertRowCount()`, `returning()` and `selectColumns()` (`StatementShape.java:200, 219, 228, 276, 294`) are booleans or names-only lists where the two-arm builder needs the clause nodes or token ranges (`selectColumns` goes empty on any alias).
- JSQLParser gives `jjtGetFirstToken()` / `jjtGetLastToken()` per `ASTNodeAccess` node; the code already uses them per `Column` (`nameEndOf`), so a fragment emitter is a generalisation of existing code.
- Verified: by code reading against the design; this is a readiness gap for the next step, not a runtime defect today.

## Proposed Solutions

### Option 1: Add a fragment emitter to SqlAst and per-row INSERT values to StatementShape

**Approach:** Add `fragment(Object node, Naming naming)` returning the fragment's text (token-joined, with table and column substitution scoped to that subtree) plus the ordinals of the `?` placeholders inside it, in order. Expose per-row INSERT values aligned with `insertColumns()` (or `TableInfo.dataColumns()` when the column list is absent), each as a fragment with its placeholder ordinals. Keep `Naming` for whole-statement table mapping (the `rc_T` route and the classic `T -> T_data` mapping). Drop `columnReplacementFor` from `Naming` once the fragment emitter carries column substitution.

**Pros:**
- Directly provides what 4.2 and 4.3 consume; the builder composes strings and ordinal lists.
- Builds on the token-join machinery that already preserves comments and hints.

**Cons:**
- Placeholder ordinals must be tracked through the token chain; a bug here mis-binds parameters silently (test heavily).
- The shape flags that duplicate the AST (`ordered`, `limited`, `returning`, `selectColumns`) become dead once the builder reads clause nodes; remove them in the same change or in todo 029.

**Effort:** Medium to Large (1-2 days with tests)

**Risk:** Medium

---

### Option 2: Let the builder use JSQLParser directly for fragments and keep SqlAst for whole-statement routes

**Approach:** Expose `ast().statement()` (already public) and have the two-arm builder call `jjtGetFirstToken()`/`jjtGetLastToken()` itself for WHERE, SET, ORDER BY and LIMIT, re-joining tokens through a small shared helper. SqlAst keeps only table-name substitution. Column substitution for `P_before` is done by the builder on the WHERE token range.

**Pros:**
- Smallest change to `sql`; the builder owns its own needs.
- Avoids designing a general fragment API before its consumer exists.

**Cons:**
- JSQLParser types spread into the rewrite layer (todo 024 asks for a decision on this); two token-join implementations unless the helper is shared.
- Placeholder ordinal tracking still has to be written somewhere.

**Effort:** Medium

**Risk:** Medium

---

### Option 3: Defer until the connection proxies exist, then design the API against the real consumer

**Approach:** Record the required capabilities (fragment text, placeholder ordinals, per-row INSERT values, scoped column substitution) in design.md 4.4 "Builder notes" and implement them when writing the 4.2 read builder, choosing between Option 1 and 2 at that point.

**Pros:**
- No speculative API; the shape of the builder decides the shape of the emitter.

**Cons:**
- The classifier's current flags and `columnReplacementFor` stay as misleading surface in the meantime.

**Effort:** Small now

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:90-124` - `Naming`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:370-378` - `parameterCount`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:467-512,546-564,627` - `joinTokens`, column replacement
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:200,219,228,276,294` - flags that duplicate the AST
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:441-474` - `insert`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java:143` - the only test of `columnReplacementFor`
- `docs/design.md` 4.4 "Builder notes"

**Related components:**
- Todo 008 (statement cache stores the emitted fragments and ordinals)
- Todo 024 (JSQLParser types in the public API)
- Todo 029 (simplify `StatementShape` flags)

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` 1 "Placeholders"; 4.2 "Reads" (two-arm UNION ALL forms); 4.3 "Writes"; 4.4 "Builder notes"
- Found by: architecture-strategist (P2-1, P2-2), feature-dev:code-reviewer (16), code-simplicity-reviewer (items 7, 14)

## Acceptance Criteria

- [ ] A WHERE clause can be emitted as text with `before_` substitution applied to it alone, leaving the projection untouched
- [ ] The emitter reports the placeholder ordinals inside each fragment, and a test with duplicated `?` positions (e.g. `where a = ? and b = ?` emitted twice) yields the expected binding map
- [ ] INSERT exposes per-row values aligned with the column list, including multi-row VALUES and the no-column-list form
- [ ] `columnReplacementFor` is either scoped to fragments or removed, and `SqlAstTest:143` updated accordingly
- [ ] design.md 4.4 lists the builder's inputs and the emitter that provides them
- [ ] Unit tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by architecture-strategist (P2-1, P2-2) during the `/ce:review` run on commit 06c137f; the placeholder-position gap also by feature-dev:code-reviewer (16) and the speculative column renaming by code-simplicity-reviewer (14)
- Classified as P2 architecture readiness: nothing is wrong at runtime today, but the next step cannot be built on the current API

**Learnings:**
- The token-join machinery already has per-node token ranges (`nameEndOf`), so a fragment emitter is an extension rather than a rewrite

---

## Notes

- Whatever option is chosen, keep the token join (not `Statement.toString()`), since hints and comments must survive in place.
