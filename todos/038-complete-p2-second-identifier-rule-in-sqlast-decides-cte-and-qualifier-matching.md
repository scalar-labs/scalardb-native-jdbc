---
status: complete
priority: p2
issue_id: "038"
tags: [code-review, correctness, sql, simplification]
dependencies: []
---

# SqlAst keeps a second, PostgreSQL-only identifier rule and still uses it to tell CTE references from tables and to match column qualifiers

## Problem Statement

The catalog rework gave the driver one identifier rule, `Engine.resolveIdentifier`, but `SqlAst` kept its own `fold`/`unquote`/`resolve` trio with PostgreSQL semantics and different escaping. `StatementShape` switched to the engine; `SqlAst.parse` did not. It still decides with the old lower-case fold whether a table reference is a CTE and whether a column qualifier matches a table. On engines where a quoted and an unquoted spelling name different objects, the real table can be filed as a CTE reference and never rewritten. This is the residual of todo 004.

## Findings

- `SqlAst.java:627-674`: `unquote`, `fold`, `isQuoted`, `resolve`. Differences from `Engine.resolveIdentifier` (`Engine.java:152-173`): the engine version requires a matching delimiter pair and undoubles `""` / `]]`; the SqlAst version accepts `"x]` and leaves `a""b` doubled.
- `TableRef.resolvedName` / `quoted` / `resolvedSchema` (`SqlAst.java:714-725, 752-755`) and `ColumnRef.resolvedName` (`:825-827`) have no caller in main after this commit; they are tested as "like PostgreSQL and MySQL" (`SqlAstTest.java:215-222`), a duplicate of `EngineTest:20-37`.
- `fold` is still load-bearing in two places: `parse` splits `tableRefs` from `cteRefs` by comparing the folded table name against the folded CTE names (`:198`, `:909`), and `planRewrite` matches column qualifiers to tables with it (`:477-511`).
- Consequence: on PostgreSQL `WITH "Orders" AS (...) SELECT ... FROM orders`, and on Oracle `WITH "orders" AS (...) SELECT ... FROM orders`, the CTE and the table are two different objects to the engine; `fold` makes them one, so the `FROM` reference is classified as a CTE reference. `StatementShape` refuses every CTE (`cteRefs().isEmpty()`), so writes are safe, but the read path of design 4.2 routes the statement to `rc_T` by name substitution and finds no table to substitute: a raw read of a ScalarDB table, returning in-flight after images (violates invariant I4). Reads only.
- Verified by code reading by two reviewers; no live run (the read rewrite does not exist yet, so the effect is on the contract the proxy will use).

## Proposed Solutions

### Option 1: Give `SqlAst.parse` the engine and delete the old rule

**Approach:** `SqlAst.parse(String sql, Engine engine)`, passed through by `StatementShape.of(sql, engine)`. Replace the four `fold` sites with `engine.resolveIdentifier` and a `sameTable` comparison; delete `unquote`, `fold`, `isQuoted`, `resolve`, `foldedName` (inline), both `resolvedName`s, `resolvedSchema`, `quoted`, and `SqlAstTest.identifiers_foldAndResolveLikePostgresAndMySql`. About -95 lines in main, -10 in tests.

**Pros:**
- One identifier rule, the one measured per engine; closes 004's residual.
- Net deletion; the simplicity reviewer's P1.

**Cons:**
- `SqlAst.parse` grows a parameter; every caller and test changes.
- CTE-name matching on case-insensitive-table engines (SQL Server, MySQL with `lower_case_table_names=1`) needs the engine's table comparison, which todo 032 is adding; until then exact-after-fold is the conservative choice (a CTE that differs only by case is then a table reference, which fails closed).

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Keep `SqlAst` engine-free and make CTE classification conservative

**Approach:** Treat a table reference as a CTE only when its raw spelling (quotes included) equals a CTE's raw alias spelling exactly; otherwise it is a table reference. Keep the public statics for now.

**Pros:**
- No new parameter; fails closed (`WITH "Orders" ... FROM orders` becomes a table reference to `orders`, which the catalog then resolves correctly).

**Cons:**
- Wrong the other way on engines that fold: on PostgreSQL `WITH orders AS (...) SELECT * FROM Orders` is one CTE to the engine and two names to the driver, so the shape is refused needlessly.
- Leaves two rules in main, one of them unused and untested against the engines.

**Effort:** 1-2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:172-249` - `parse`, the CTE split at `:198`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:477-511` - `planRewrite` qualifier matching
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:627-674, 705-725, 752-755, 825-827` - the old rule and its accessors
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:909` - `Walker` CTE-name folding
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:116-118` - `of(String, Engine)` passes the engine through
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java:215-222` - duplicate of `EngineTest`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/ModulePathCheck.java`, `SyntaxTreeOracleTest.java` - callers of `SqlAst.parse`

**Related components:**
- `Engine` in `sql` is not a layering leak: only the pure-string methods are used and `sql` still imports no `java.sql` (architecture-strategist).
- Todo 013 (CTE scoping inside its own body) and todo 018 (unquoting escapes) are the neighbouring items; todo 029 already lists the accessors for deletion.

**Database changes:** None.

## Resources

- `docs/design.md` section 4.2 (reads route by table-name substitution), invariant I4.
- Found by: code-simplicity-reviewer (P1), architecture-strategist (P2).

## Acceptance Criteria

- [ ] `SqlAst` contains one identifier rule, `Engine.resolveIdentifier`; `unquote`, `fold`, `isQuoted`, `resolve` and the `resolved*` accessors are gone.
- [ ] `parse("WITH \"Orders\" AS (...) SELECT * FROM orders", POSTGRESQL)` reports `orders` as a table reference and `"Orders"` as the CTE; the Oracle mirror case likewise.
- [ ] `parse("WITH orders AS (...) SELECT * FROM Orders", POSTGRESQL)` still reports one CTE reference (the engine folds both).
- [ ] `planRewrite` matches a qualifier to its table with the engine rule; the alias test corpus in `SqlAstTest` passes for every engine.
- [ ] `EngineTest` is the only test of identifier folding.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- code-simplicity-reviewer grepped main for callers of every public member of `SqlAst` and found the old rule's accessors callerless while `fold` still decides CTE references and alias planning.
- architecture-strategist traced the consequence through the design's read route: the real table filed as a CTE reference is never substituted.
- By code reading; the read rewrite that would expose it is not yet implemented.

**Learnings:**
- Making `StatementShape` engine-aware while `SqlAst.parse` was not left the two classifications disagreeing about what an identifier means.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- SqlAst.parse(sql, engine) is the public entry point (a package-private parse(sql) with PostgreSQL rules remains for this package's tests); CTE names, the qualifier matching of planRewrite and the FOR UPDATE OF check compare names with engine.nameKey(engine.resolveIdentifier(...)), where nameKey lower-cases on SQL Server only. StatementShape.of(SqlAst) takes the engine from the statement and compares qualifiers the same way. Deleted SqlAst.unquote/fold/isQuoted/resolve, TableRef.foldedName/resolvedName/quoted/resolvedSchema, ColumnRef.resolvedName, Engine.sameColumn and the duplicate identifier test.
- Test: SqlAstTest.cteNames_matchTheWayTheEngineMatchesNames (a quoted CTE "Orders" is not the table orders on PostgreSQL, "orders" is not ORDERS on Oracle, an unquoted c and C match on PostgreSQL, Oracle and SQL Server and not on MySQL).

## Notes

- Coordinate with todo 032: the table comparison on case-insensitive engines is a per-database setting read at catalog load, and the CTE split in `parse` has no catalog; exact-after-fold is the fail-closed choice there.
