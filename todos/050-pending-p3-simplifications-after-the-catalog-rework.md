---
status: pending
priority: p3
issue_id: "050"
tags: [code-review, simplification, quality]
dependencies: []
---

# Simplifications left by the catalog rework and the scanner replacement

## Problem Statement

Commit 75106c2 removed the allowlist and the reflective scan and rebuilt the catalog, but it also added new weight of the kind todos 029 and 031 track: a second locking layer and two private interfaces in `Catalog`, members with no caller, a fallback parse that only `COMMIT`-like text reaches, a stale comment, and test code that repeats itself across four files and five engines. None of it is wrong; all of it is more to read at 3am.

## Findings

Main sources:
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java`: `lookup` takes the lock twice (`snapshot()` :200-212, `resolve()` :214-228); the private `Query<T>` (:230-232) and `withConnection` (:235-252) exist for two call sites; `present` (:175-177, :267, :315, :348) only short-circuits a lookup that returns PLAIN anyway because the maps are empty; `engine()` (:154-156) has no caller; the three-argument constructor (:120-122) serves tests only; `MetadataColumn` plus `Snapshot.columns()` (:335-344, :444-471) could be `Columns` built row by row in `load` (about -20 lines).
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java`: `Engine.of` (:114-122) has no main caller while `NativeJdbcUrl.java:23-31` rebuilds the same set by hand with an inline `new java.util.HashSet<>()`; `parse` should call `Engine.of` and store the `Engine` (about -10). `Engine.sameColumn` (:193-195) and `QualifiedName.toString` (:82-85) are unused (-13).
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:277-281`: `rootOf`'s fallback parse is reachable only for statements with no statement node, no table and no column (`COMMIT`/`ROLLBACK`-like text per the simplicity probe) and costs a second full parse: make `root` nullable and `joinTokens` return null (about -10).
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:444`: the comment "invisible to the node-type scan" refers to the allowlist this commit deleted. The nine `x != null && !x.isEmpty()` checks want a `static boolean nonEmpty(List<?>)` (-6). The per-class modifier refusals cannot be expressed once: `PlainSelect`, `Insert`, `Update`, `Delete` share no interface for the getters in JSQLParser 5.4, and a text check would break "decide by the tree".
Tests and build:
- The 100-term predicate is built in four files (`SqlAstTest:202`, `StatementShapeTest:249`, `SyntaxTreeOracleTest:32`, `ModulePathCheck:45`): one `static String wide()` in the test package (-12).
- `ModulePathCheck` (62 lines) re-asserts five `StatementShapeTest` facts; only `parameterCount` and `whereColumns` on a cast placeholder could differ under JPMS (-35 if cut to those two). `parse_refusesAStatementWithATableNodeTheWalkDoesNotVisit` belongs in `SqlAstTest`.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/testing/Engines.java`: `url()` (:147-149) and `useSchema()` (:189-199) have no caller; `Started` (:85-95) duplicates the container's own `getJdbcUrl/getUsername/getPassword`; the SQL Server `CREATE DATABASE appdb` (:55-64) could be a `withInitScript` (about -30).
- `CatalogIntegrationTest` repeats `CatalogTest`'s engine-independent `classify` assertions per engine (`:113-124` vs `CatalogTest:84-93`, `:167-180` vs `:70-82`, `:196-209` vs `:95-101`, the four refusals `:286-295` vs `:111-127`): keep `layout()`, `namespace()`, `table()` in the stored spelling and the resolution cases, drop the field assertions, keep one refusal (about -25). `StatementShapeTest.columnNames_followTheEngineRule` (`:37-62`) is six copies of one assertion: a loop over `Engine.values()` with an expected map (-20).
- The `integrationTestCompileOnly(libs.spotbugs.annotations)` line and the `@SuppressFBWarnings` on `Engines.execute` exist only because SpotBugs runs on test source sets (todo 030).

## Proposed Solutions

### Option 1: One cleanup pass after the P1/P2 items of this review

**Approach:** Do the deletions and merges above in one commit once 032-042 have settled the shape of `Catalog` and `Engine`, so nothing is simplified twice. Reshape `Catalog.lookup` into one `synchronized` block with one `try/catch` that drops the connection, `connection()`/`dropConnection()` helpers, and `classify`/`plain` outside the try (a 42000 refusal must not drop the connection), which also removes `Query`, `withConnection`, `present` and `engine()` (about -45).

**Pros:**
- About -150 main and test lines in one reviewable commit; no churn against the in-flight fixes.

**Cons:**
- Waits on 035 and 037, which change the same lock and connection code.

**Effort:** Half a day

**Risk:** Low

---

### Option 2: The safe deletions now, the reshaping later

**Approach:** Delete the unused members, the stale comment, the duplicate test code and the `Engines` leftovers now; leave `Catalog`'s structure to 035/037.

**Pros:**
- Immediate, independent of the lock rework.

**Cons:**
- Two passes.

**Effort:** 2 hours now, 2 hours later

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java`
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java`
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcUrl.java:23-31`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:277-281`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:444`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/*Test.java`, `ModulePathCheck.java`
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/testing/Engines.java`, `catalog/CatalogIntegrationTest.java`

**Related components:**
- Todos 029, 030, 031 (the earlier simplification items), 035 and 037 (lock and connection, which decide `Catalog`'s final shape), 048 (`treeValues`).

**Database changes:** None.

## Resources

- Found by: code-simplicity-reviewer (P2, P3), performance-oracle (P3, `rootOf` cost).

## Acceptance Criteria

- [ ] No public or package member in `Catalog`, `Engine`, `SqlAst` or `Engines` without a caller in main or a test that needs it.
- [ ] `NativeJdbcUrl` obtains the engine through `Engine.of`.
- [ ] The 100-term predicate is built once in the test sources.
- [ ] `CatalogIntegrationTest` asserts per engine only what differs per engine.
- [ ] `./gradlew check` green.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- code-simplicity-reviewer grepped main for callers of every public member of the changed files and probed JSQLParser for the `rootOf` fallback set.
- performance-oracle measured the fallback parse (+11 µs for `COMMIT`).

**Learnings:**
- Each fix added one helper for one caller; the sum is the weight.

## Notes

- The token-join machinery, `ConnectionSupplier`, the `Clock` parameter, `QualifiedName`, `isMissingTable`'s per-engine codes and the oracle test are genuinely needed and stay (simplicity report, "should stay").
