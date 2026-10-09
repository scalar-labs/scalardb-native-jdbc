---
status: pending
priority: p3
issue_id: "031"
tags: [code-review, testing, simplification]
dependencies: []
---

# Several tests assert the same fact twice

## Problem Statement

A handful of unit tests cover one behaviour under two or three names, and the integration test rebuilds the
same `Catalog` in every method. Duplicate tests cost maintenance on every API change (029 will touch most
of them) without adding a failure mode.

## Findings

- `NativeJdbcUrlTest:11-34` has three tests of "strip prefix" where the parser only looks at the first colon;
  one `@ParameterizedTest` covers them.
- `SqlAstTest:42-43` asserts `cteRefs` and `cteNames` (the same fact; `cteNames` is also slated for removal
  in 029).
- `SqlAstTest:187-194` and `:47-61` test `resolve` twice (static and via `TableRef`).
- `StatementShapeTest` `select_byKey` `:26-27`, `keyEquality_` `:59-73` and `delete_byKey` `:103` all test
  `isKeyEquality`; keep it in `keyEquality_` only.
- `CatalogIntegrationTest` builds `new Catalog(DEFAULT_METADATA_SCHEMA)` seven times; one static field.
- `NativeJdbcDriverTest:28` vs `NativeJdbcUrlTest:49` look duplicate but test the SQLSTATE mapping layer:
  keep both.

## Proposed Solutions

### Option 1: Dedupe when 029 lands

**Approach:** Fold the duplicates as part of the `sql` surface cleanup, since those tests change anyway;
parameterise the URL tests; add the static `Catalog` field.

**Pros:**
- One pass over the test files instead of two.

**Cons:**
- Waits on 029.

**Effort:** 30 minutes on top of 029

**Risk:** Low

---

### Option 2: Dedupe now

**Approach:** Same edits as a standalone change.

**Pros:**
- Independent of other work.

**Cons:**
- The same files are edited again by 029.

**Effort:** 30 minutes

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/test/java/com/scalar/db/nativejdbc/NativeJdbcUrlTest.java:11-34`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java:42-43, 47-61, 187-194`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java:26-27, 59-73, 103`
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java`

**Related components:**
- 029 removes `cteNames`, which deletes one of the duplicates by itself.

**Database changes:** No

## Resources

- Found by: code-simplicity-reviewer (P3-18).

## Acceptance Criteria

- [ ] Each listed behaviour is asserted by exactly one test (or one parameterised test).
- [ ] `CatalogIntegrationTest` constructs its `Catalog` once.
- [ ] The two SQLSTATE-mapping tests are kept.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the code-simplicity-reviewer report (P3-18) of the seven-agent review of main @ 06c137f.

**Learnings:**
- Unit suites run in well under a second each; the cost here is maintenance, not time.

### 2026-10-09 - New duplicates in 75106c2

**By:** Claude Code

**Actions:**
- Listed in todo 050: the 100-term predicate built in four test files, ModulePathCheck re-asserting five StatementShapeTest facts, columnNames_followTheEngineRule's six blocks, CatalogIntegrationTest re-asserting CatalogTest's classify fields on every engine, SqlAstTest.identifiers_foldAndResolveLikePostgresAndMySql duplicating EngineTest.

### 2026-10-09 - Status

**By:** Claude Code

**Actions:**
- Removed: SqlAstTest.identifiers_foldAndResolveLikePostgresAndMySql (duplicate of EngineTest, with 038).
- Still open: the other duplicates listed here and in 050.

## Notes

- Do not remove the regression cases that other todos add (001, 002, 006, 007, 013, 014); this item is about
  duplicates of existing assertions only.
