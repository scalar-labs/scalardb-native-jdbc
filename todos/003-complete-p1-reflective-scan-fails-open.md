---
status: complete
priority: p1
issue_id: "003"
tags: [code-review, security, correctness, sql]
dependencies: []
---

# The reflective AST scan fails open: on the module path, beyond depth 64, and on any reflection error

## Problem Statement

Every fail-closed check in the sql package (`unknownNodes`, `parameterCount`, `whereColumns`, `containsFunction`) is built on a reflective walk over JSQLParser's private fields. That walk silently returns partial results in three situations, and in each one the driver accepts a statement it has not fully understood: the "unknown node" refusal is disabled, placeholders are undercounted so re-binding mis-binds parameters, and WHERE columns go missing from the predicate substitution.

## Findings

All in `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:1002-1073` (`scan`, `scanNodes`, `nodesUnder`).

- **(a) JPMS.** `setAccessible(true)` is called per field (`:1059-1070`); `InaccessibleObjectException` is a `RuntimeException` and is swallowed at `:1067` (`continue`). `jsqlparser-5.4.jar` ships `module-info.class` (`module net.sf.jsqlparser`, `exports` only, zero `opens`). **Verified** with `java -p jsqlparser-5.4.jar --add-modules net.sf.jsqlparser`: `parameterCount()` = 0 for 10 placeholders; `unknownNodes()` = [] for a statement containing `RegExpMatchOperator` (reported correctly on the classpath); `whereColumns` = []; `StatementShape.of` accepts the statement. Any application that puts the driver and JSQLParser on the module path (jlink images, modular apps) runs with every scan-based check disabled. Classpath applications are unaffected.
- **(b) Depth cap.** `if (o == null || depth > 64) return` (`:1022`). AND/OR chains are left-deep, so the first terms of a conjunction of 64 or more terms sit below the cap. **Verified**: 100 ORed `a=?` terms give `parameterCount()` = 61 (60 terms give 60); `where c0 % 2 = 0 and c1 = ? ... and c69 = ?` gives a present `StatementShape`, empty `unknownNodes()` although `Modulo` is untaught and is refused in the 63-term version, and `whereColumns` 63 of 70. Hibernate composite-key batch fetches on dialects without row-value IN and MyBatis `foreach` OR chains reach 64 terms. Once placeholders are re-bound at new positions (design.md section 1, "Placeholders"), parameters are mis-bound.
- **(c)** Any other `RuntimeException` from `setAccessible`/`get` is swallowed at `:1067` and the walk continues.

## Proposed Solutions

### Option 1: Make every incomplete walk a refusal and remove the depth cap

**Approach:** Replace the recursive walk with an explicit `ArrayDeque`; keep the identity set for node cycles and bound only plain collections. On `InaccessibleObjectException` or any reflection failure, throw a dedicated unchecked exception that `parse` turns into `Optional.empty()` (or add a `scan-error` entry to `unknownNodes`). No silent `continue`.

**Pros:**
- Smallest change that restores fail-closed behaviour in all three cases.
- Keeps the reflective cross-check on `TablesNamesFinder` (the `table:` guard).

**Cons:**
- Module-path applications are refused outright rather than supported (they need `--add-opens` or a visitor-based walk).
- Reflection cost remains (see todo 020).

**Effort:** 2-3 hours

**Risk:** Low

---

### Option 2: Replace the reflective walk with JSQLParser visitors

**Approach:** `TablesNamesFinder` is an `ExpressionVisitor`, so `where.accept(new Walker(), null)` yields the WHERE columns directly (drop `isAmong` and `nodesUnder`); `containsFunction` becomes an `ExpressionVisitorAdapter` overriding `visit(Function)`; count placeholders with a `JdbcParameter` visit. Keep `scan` only as the `table:` cross-check that distrusts the library's own finder, with a javadoc saying so, or delete it (about -75 lines).

**Pros:**
- No `setAccessible`, so it works on the module path and is faster.
- Fails closed at compile time for new node types.
- Less code.

**Cons:**
- Visitors do not fail closed for new fields added to existing node types; if the reflective walk is dropped entirely, that cross-check goes with it (performance-oracle's caveat).
- Larger change touching `StatementShape.predicate` and `containsFunction`.

**Effort:** 1 day

**Risk:** Medium

---

### Option 3: Option 1 now, Option 2 later with the reflective walk kept as a test oracle

**Approach:** Ship Option 1 immediately; migrate the production checks to visitors when the rewrite builder lands, and keep the reflective walk in tests to assert the visitors see every node the reflection sees on the corpus.

**Pros:**
- Immediate safety, long-term simplicity, and the oracle survives where it is cheap.

**Cons:**
- Two steps.

**Effort:** 2-3 hours now, 1 day later

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:1002-1073` - `scan`, `scanNodes`, `nodesUnder`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:370-378, 389-423` - `parameterCount`, `unknownNodes`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:514-537` - `predicate`, `containsFunction`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java` - deep-predicate and module-path tests
- `driver/build.gradle.kts` - a module-path test run if Option 1 or 3 is chosen

**Related components:**
- Placeholder re-binding (design.md section 1), the two-arm read (4.2), the `P_before` substitution.
- Todo 011 (unknown-node allowlist) and todo 020 (scan cost) touch the same code.

**Database changes:** None.

## Resources

- `docs/design.md` section 1 ("Placeholders"), section 4.2.
- JSQLParser 5.4 `module-info`: `module net.sf.jsqlparser` with no `opens`.
- Found by: security-sentinel (P1-3, P2-1), performance-oracle (P1-2), feature-dev:code-reviewer (P2-10), code-simplicity-reviewer (P2-3, visitor option).

## Acceptance Criteria

- [ ] A 100-term OR predicate reports 100 placeholders and all 100 columns.
- [ ] A 70-term AND predicate whose first term uses `%` is refused (unknown node reported) exactly like the short form.
- [ ] Running `SqlAstTest` with JSQLParser on the module path either passes every assertion or refuses every statement; it never accepts a statement with an empty scan.
- [ ] No `catch (RuntimeException)` that continues the walk remains in `scan`.
- [ ] The depth cap is gone or is a refusal, never a truncation.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- security-sentinel ran the sql package on the module path and showed every scan-based check returning empty.
- performance-oracle and security-sentinel measured the depth-cap truncation on 60-100 term predicates.
- feature-dev:code-reviewer flagged the swallowed `RuntimeException` from a static read.
- code-simplicity-reviewer proposed the visitor-based replacement.

**Learnings:**
- A "fail-closed" check that can return an empty result on error is fail-open; the walk must throw.
- JSQLParser's own visitors cover most of what the reflection is used for.

### 2026-10-09 - Live verification

**By:** Claude Code

**Actions:**
- Ran the built classes on the classpath and on the module path (`java -p jsqlparser-5.4.jar --add-modules net.sf.jsqlparser`).
- Module path: `select * from t where c ~ 'x' and a = ? and b = ?` gives `unknownNodes=[]`, `parameterCount=0` (expected 2) and `StatementShape.of` accepts it; on the classpath the same statement is refused with the RegExpMatchOperator reported.
- Depth cap on the classpath: an OR chain with 60 terms is refused (regex operator reported, 59 parameters); with 100 terms `parameterCount=62`, `unknownNodes=[]` and the statement is ACCEPTED, so the cap turns a refusal into an acceptance.

**Learnings:**
- Both failure modes are real and silent; nothing in the API distinguishes "no unknown nodes" from "could not look".

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- Removed private-field reflection from SqlAst entirely (scan, scanNodes, nodesUnder, nodeTypes, the depth cap and the swallowed exceptions). Every check now reads JSQLParser's public syntax tree: parse() reaches the root from any node, walks it iteratively with no depth limit, refuses the statement when the tree holds a Table the reference walk did not visit, and counts placeholders as the identity union of tree values and visitor visits (a placeholder that is the operand of a :: cast or a sign has no node of its own, found by the oracle test).
- StatementShape reads WHERE columns and functions through the expression's own subtree (SqlAst.valuesUnder); a clause without a tree refuses the shape.
- Test oracle: ReflectiveNodes (test-only reflection that throws on an inaccessible field) and SyntaxTreeOracleTest compare the tree against the parser's private fields over the corpus plus 27 structural cases; parse_refusesAStatementWithATableNodeTheWalkDoesNotVisit shows the cross-check on FOR UPDATE OF.
- Module-path guard: the modulePathCheck Gradle task runs ModulePathCheck with JSQLParser as a named module and is part of check.
- Verified: 165 unit tests, modulePathCheck, full ./gradlew check green; the live probe gives identical answers on the classpath and the module path, 99 of 99 placeholders and 100 of 100 columns at 100 terms.

**Learnings:**
- The oracle test caught a real gap on its first run (placeholders under :: casts), which is the argument for keeping it.

## Notes

- Related: todo 011 (the allowlist built on this scan) and todo 020 (memoising the scan).
