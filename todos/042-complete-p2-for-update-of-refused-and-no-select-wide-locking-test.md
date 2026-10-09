---
status: complete
priority: p2
issue_id: "042"
tags: [code-review, correctness, sql, design]
dependencies: []
---

# `FOR UPDATE OF alias` is refused as unparseable even on plain tables, and nothing detects a locking or INTO clause for the `rc_T` route

## Problem Statement

Two gaps around locking clauses. First, `SqlAst.parse` refuses `select ... for update of o` outright because the parser builds a table node for the `OF` target that the reference walk never visits; that is fail-closed, but it refuses the exact statement ORMs emit for pessimistic locking, and it refuses it even for plain tables. Second, the single-table shape checks lock options only for itself, while the design routes every other SELECT shape to `rc_T` by name substitution, and a lock or `INTO` anywhere in such a statement makes that substitution unsafe. `SqlAst` offers nothing the proxy could ask.

## Findings

- `SqlAst.java:230-233`: a `Table` node in the parser's tree that is neither a visited reference nor a column qualifier makes `parse` return empty. `Select.getForUpdateTable()` is such a node; `SyntaxTreeOracleTest.java:104-109` asserts the refusal deliberately (`parse_refusesAStatementWithATableNodeTheWalkDoesNotVisit`). Per design 4.1 an empty parse is refused on every route, plain tables included.
- `StatementShape.select` (`StatementShape.java:435-446`, `:478`) refuses `FOR UPDATE` variants, `SKIP LOCKED`, `NOWAIT`, `WAIT` and `INTO` only when the statement is the single-table shape. Design 4.2 says a locking clause on an `rc_T` shape is refused and that PostgreSQL rejects a lock through an outer-join view; a subquery's `FOR UPDATE`, a `FOR UPDATE OF t` naming the replaced table, `SELECT ... INTO`, or a set operation with a lock would be substituted and either fail at the engine or lock the wrong relation.
- `SqlAst` exposes `kind()`, `tableRefs()` and `statement()` only (todo 024), so the proxy would have to walk JSQLParser types itself to find out.
- By code reading (two reviewers); the read route is not implemented yet, so this is about the contract it needs.

## Proposed Solutions

### Option 1: Account for the OF target and add one SELECT-wide flag

**Approach:** In `parse`, add `Select.getForUpdateTable()` to the accounted set (and handle it in `collectReplacements` as a qualifier, so a rewrite re-points it at the replaced table's alias), so `FOR UPDATE OF` parses and the single-table shape decides what to do with it. Add `SqlAst.hasLockingOrIntoClause()`, computed in `parse` by visiting every `PlainSelect` (the `Walker` already rides the traversal) and recording `getForMode()`, `isSkipLocked()`, `isNoWait()`, `getWait()` and `getIntoTables()`; the read path refuses when it is true and the shape is not single-table. Replace the oracle test's refusal assertion with one that the OF target is accounted for.

**Pros:**
- Unblocks the common pessimistic-lock statement on plain tables and gives the proxy a one-method answer for the `rc_T` route.
- Small: one `accounted.add`, one boolean collected during a traversal that already exists.

**Cons:**
- The OF target names an alias; the rewrite must map it to the substituted table's alias, which touches `planRewrite` (todo 014 territory).

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Keep refusing `FOR UPDATE OF`, add only the flag

**Approach:** Leave `parse` as it is and add `hasLockingOrIntoClause()` for the other shapes.

**Pros:**
- Smallest change; the refusal stays fail-closed.

**Cons:**
- ORMs using `FOR UPDATE OF` cannot use the driver even on plain tables; the design must then document the divergence.

**Effort:** 2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:210-249` - `parse`, the accounted set at `:230-233`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:477-530` - `planRewrite` / `collectReplacements` (the OF target as a qualifier)
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java` (`Walker`) - where the new flag is collected
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:435-446, 478` - the single-table lock checks
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SyntaxTreeOracleTest.java:104-109` - asserts the current refusal
- `docs/design.md` 4.2 - "locking clause on an rc_T shape" needs the detection it assumes

**Related components:**
- Todo 014 (alias planning), todo 024 (JSQLParser types in the API), todo 006 (the modifier refusals this extends).

**Database changes:** None.

## Resources

- `docs/design.md` 4.1 (routing), 4.2 (locking clause rule, PostgreSQL and outer-join views), 4.6 (`@Lock(PESSIMISTIC_WRITE)` row).
- Found by: feature-dev:code-reviewer (P2), architecture-strategist (P2).

## Acceptance Criteria

- [ ] `SqlAst.parse("select * from t o where o.id = ? for update of o")` is present; the single-table shape reports `lockMode() == UPDATE`.
- [ ] `hasLockingOrIntoClause()` is true for a lock in a subquery, a `FOR UPDATE OF`, a `SELECT ... INTO`, and a set operation with a lock, and false for a plain select.
- [ ] The oracle test's corpus includes `FOR UPDATE OF` and the OF target is a tree node accounted for.
- [ ] The rewrite of a `FOR UPDATE OF alias` statement names the alias of the substituted table (or the statement is refused on the rewrite route until todo 014 lands).

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer traced the refusal to the unaccounted `getForUpdateTable()` node and the deliberate test.
- architecture-strategist read the design's `rc_T` route against `StatementShape.select` and found no SELECT-wide locking detection for the proxy to call.

**Learnings:**
- The syntax-tree cross-check of todo 003 is doing its job here; the fix is to teach the walk one more node, not to weaken the check.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- SqlAst.parse collects every SELECT of the statement (walk plus tree values plus the root) and accounts the FOR UPDATE OF targets as known tables, so "for update of o" parses. hasLockingOrIntoClause() reports FOR UPDATE/SHARE, SKIP LOCKED, NOWAIT, WAIT and any INTO anywhere in the statement, for the read route to refuse on non-single-table shapes. joinTokens refuses a rewrite that renames a table FOR UPDATE OF names by its name (the target would name a table no longer present); by alias it rewrites. StatementShape.select accepts an OF target only when it names this table by its alias or name.
- Tests: SqlAstTest.forUpdateOf_parses_andARewriteRenamingItsTargetByNameIsRefused and lockingOrIntoClauses_areSeenWhereverTheyAreNested; the OF statement joins the oracle corpus.

## Notes

- The reviewer cited a design section 4.7 for Oracle `FOR UPDATE OF`; that section exists only in the uncommitted working-tree draft, not at 75106c2.
