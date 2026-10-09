---
status: pending
priority: p3
issue_id: "024"
tags: [code-review, architecture, testing, simplification]
dependencies: ["004", "012"]
---

# Public API leaks JSQLParser types, and the catalog layer cannot be unit-tested without Docker

## Problem Statement

The `sql` package half-hides JSQLParser: `Naming` abstracts it away, but three public accessors return
JSQLParser types while the build declares JSQLParser as an `implementation` dependency, so a consumer
cannot even compile against them. On the catalog side, `TableInfo` can only be constructed inside its own
package and `Catalog`'s classification rules are reachable only through the PostgreSQL container, so the
part of the code that will change most (the layout rules) has no fast unit tests. `TableInfo` also lacks
the accessors each layout's rewrite will need, and there is no home yet for the per-engine strategy the
design describes.

## Findings

- `StatementShape.where()` returns `Expression` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:247`),
  `lockMode()` returns `ForMode` (`:238`), `SqlAst.statement()` returns `Statement`
  (`driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:324`), yet `driver/build.gradle.kts`
  declares `implementation(libs.jsqlparser)`. Verified by reading the build file and the signatures.
- JSQLParser is unshaded. JSQLParser 4.x (MyBatis-Plus, PageHelper) on the same classpath conflicts.
- `TableInfo`'s constructor is package-private (`driver/src/main/java/com/scalar/db/nativejdbc/catalog/TableInfo.java:20`),
  so rewrite unit tests in another package cannot build fixtures. `Catalog`'s steps 1-4, `columns()` and the
  refusals are only exercised by `CatalogIntegrationTest` against a container.
- `TableInfo` lacks `committedView()` (virtual table for decoupled, the table itself for classic), `rawView()`,
  `metadataColumns()` (`TRANSACTION_COLUMNS` is private at `Catalog.java:46-57`; needed for classic
  `SELECT *` filtering and "UPDATE naming a metadata column is refused"), and a "verified at connect" state.
- `Layout` has four values. If the design's "five layouts" means classic-created vs classic-imported, the
  catalog cannot tell them apart (ScalarDB stores no import flag) and no rule differs; the doc should say so.
- No home for the per-engine strategy: quoting is fetched from `DatabaseMetaData` in `Catalog.java:111`
  although the design makes it an engine property; `NativeJdbcUrl.SUB_PROTOCOLS`
  (`driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcUrl.java:22-23`) will duplicate the engine enum.
- Simplicity reviewer: the partition/clustering split in `TableInfo` (`TableInfo.java:16-17, 112-123`) is the
  sweeper's concern (`recoverRecord` in `tools` has ScalarDB's API); every driver rule uses the whole key, and
  `keyColumns()` (`:130-134`) allocates a list per call. Keep `Layout` with all four values: imported vs
  created differ in outer-join locking and tombstones.

## Proposed Solutions

### Option 1: Decide the boundary once, then open up testability

**Approach:** Either the rewrite uses JSQLParser directly (drop `Naming` as an abstraction and declare
JSQLParser as `api`) or `sql` returns fragments/handles only (ties to 012) and the three accessors go. Add a
public static factory on `TableInfo`; split `Catalog` into a JDBC fetch (three result sets to rows) and a
pure classifier (rows to `TableInfo`) with unit tests on rows; add `committedView()`, `rawView()`,
`metadataColumns()`; collapse the key into one `keyColumns` field (partition then clustering) plus a
precomputed `nonKeyColumns`; create a leaf `engine` package (java.sql only) that `catalog` and the proxies
depend on, with identifier rules as pure strings in `sql` (see 004).

**Pros:**
- Layout rules get fast unit tests before they start changing.
- One place for engine variance, which 004 and 005 also need.
- Removes about 30 lines from `TableInfo`/`Catalog`.

**Cons:**
- Touches every package at once.
- The `api` vs fragments decision has to be made before the rewrite exists.

**Effort:** 1 day

**Risk:** Low

---

### Option 2: Testability only, defer the API decision

**Approach:** Public factory on `TableInfo`, split `Catalog` into fetch and classify, unit tests on rows.
Leave the JSQLParser-typed accessors and the key split as they are until the rewrite lands and shows what it
needs.

**Pros:**
- Smallest change; no speculative API.
- Unit tests for the catalog rules immediately.

**Cons:**
- The `implementation` vs `api` inconsistency remains visible to consumers.
- Engine strategy still has no home when 004 needs one.

**Effort:** Half a day

**Risk:** Low

---

### Option 3: Shade JSQLParser and expose nothing of it

**Approach:** Relocate JSQLParser into the driver jar (Shadow plugin), return only driver-owned types from
`sql`, and keep the rest as Option 1.

**Pros:**
- No classpath conflict with JSQLParser 4.x in MyBatis-Plus/PageHelper users.
- Public API fully driver-owned.

**Cons:**
- Adds a build plugin and a larger jar; relocation complicates stack traces and Dependabot updates.
- Premature while the preview has no external consumers.

**Effort:** 1-2 days

**Risk:** Medium

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:238, 247`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:324`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/TableInfo.java:16-20, 112-134`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:46-57, 111, 219-224`
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcUrl.java:22-23`
- `driver/build.gradle.kts` (`implementation(libs.jsqlparser)`)

**Related components:**
- The rewrite API (012) and the engine identifier rule (004) decide what `sql` must expose.

**Database changes:** No

## Resources

- `docs/design.md` section 1 (Architecture, "Where engine variance lives", Layouts) and section 4.3.
- Found by: architecture-strategist (P2-8, P3-1, P3-2, P3-3), feature-dev:code-reviewer (17),
  code-simplicity-reviewer (item 8).

## Acceptance Criteria

- [ ] `TableInfo` fixtures can be built from another package without a database.
- [ ] Catalog classification (four layouts, `_data` / `_scalardb` / `_tx_metadata` refusals, `columns()`) has
      unit tests on in-memory rows.
- [ ] Public methods of `sql` either return driver-owned types or JSQLParser is declared `api`; no mixed state.
- [ ] `TableInfo` exposes what the layout rules need by name (committed view, raw view, metadata columns).
- [ ] The design doc states whether classic-created and classic-imported differ and that the catalog cannot
      distinguish them.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the architecture-strategist (P2-8, P3-1, P3-2, P3-3), feature-dev:code-reviewer (17) and
  code-simplicity-reviewer (item 8) reports of the seven-agent review of main @ 06c137f.
- Confirmed the `implementation` declaration and the three JSQLParser-typed accessors by reading the sources.

**Learnings:**
- The layering today is clean (`sql` has no `java.sql`, `catalog` has no `sql`); the issue is what crosses the
  boundary, not the dependency direction.

### 2026-10-09 - Partly resolved by the catalog rework

**By:** Claude Code

**Actions:**
- The catalog's classification is now a pure function of loaded rows (Catalog.Snapshot.classify) and is unit-tested without a database (CatalogTest); Engine.QualifiedName is public. The engine package exists as the leaf the design wanted. Still open: TableInfo's public factory, committedView/metadataColumns accessors, and the JSQLParser types in the sql API.

### 2026-10-09 - Status

**By:** Claude Code

**Actions:**
- Done: the catalog's classification is unit-tested without Docker (CatalogTest on Catalog.Snapshot); Engine.QualifiedName is public; the engine package exists as the leaf the design wanted.
- Still open: JSQLParser types in the sql package's public API (statement(), where(), lockMode()), TableInfo's public factory and the committedView/metadataColumns accessors.

## Notes

- Decide this together with 012 (fragment emitter) since both fix what `sql` hands to the rewrite.
- Keep `Layout` with four values; the simplicity reviewer confirmed each one changes a rule.
