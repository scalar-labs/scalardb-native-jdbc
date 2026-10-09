---
status: pending
priority: p3
issue_id: "021"
tags: [code-review, build, testing]
dependencies: []
---

# Build and test wiring: `check` needs Docker, ScalarDB on the unit-test classpath, per-class containers, SpotBugs on test sources

## Problem Statement

The build runs the integration suite inside `check`, so every local `./gradlew check` needs Docker, and the one integration class starts its own PostgreSQL container and ScalarDB schema. That is fine for seven tests today but does not scale to the design's matrix of five engines times several interleaving classes, where SQL Server and Oracle containers take 30-120 seconds each to start. Separately, the unit-test source set carries the whole ScalarDB dependency tree and a real pgjdbc, which no unit test uses, slows `DriverManager`-touching tests by two orders of magnitude, and is the reason slf4j is pinned to 1.7. SpotBugs at maximum effort also runs over test sources, where its findings are noise.

## Findings

All line numbers refer to commit 06c137f.

- `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:147-166`: `check` depends on `integrationTest` and `javadoc`, so every `check` needs Docker. CI (`.github/workflows/ci.yml:38`) runs the same.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java:31-82`: starts its own `PostgreSQLContainer` plus the ScalarDB admin schema in `@BeforeAll`; hardcodes `postgres:16` at `:36`. Measured 4.8 s for 7 tests. The design's matrix (five engines × interleaving classes) scales as classes × engine start (PostgreSQL 2-5 s, SQL Server ~30 s, Oracle Free 60-120 s). No `-Dengine=` selection and no external-database property, although `docs/design.md` promises "a system property to point at an external database".
- `driver/build.gradle.kts:33-37`: `testImplementation(libs.scalardb)` and `testRuntimeOnly(postgresql, logback)` on the unit-test source set put ScalarDB's 27 direct dependencies (Cassandra driver, Cosmos, DynamoDB, jOOQ, Spanner JDBC, ojdbc8, mssql, sqlite, db2, …) on the unit-test and `spotbugsTest` classpaths. No unit test imports ScalarDB (grepped). **Measured**: `NativeJdbcDriverTest` 0.88 s (`DriverManager`'s `ServiceLoader` initialises every vendor driver on the classpath) versus 0.009 s for `NativeJdbcUrlTest`. ScalarDB's tree is why `slf4j` is pinned to 1.7 (`gradle/libs.versions.toml:7-8`).
- `java-conventions.gradle.kts:66-83`: SpotBugs `Effort.MAX` with FindSecBugs runs on all three source sets; `config/spotbugs/exclude.xml` exists only to silence test classes.
- `slf4j-api` is declared in `driver/build.gradle.kts:29` but unused in main sources.
- No shared test-fixtures source set; `java-conventions.gradle.kts:137-143` links only `project()`, so helpers cannot be shared between `test` and `integrationTest` once the matrix needs them.

## Proposed Solutions

### Option 1: Singleton containers, engine tags, lean unit classpath (recommended)

**Approach:** One lazily started static container per engine per JVM behind a JUnit extension keyed by an engine system property (optionally `withReuse(true)`), per-class schemas; `@Tag` the suites by engine so `check` runs PostgreSQL only and CI runs the matrix; `integrationTest.onlyIf { docker available or CI }`; add the external-database property the design promises; move `scalardb`, `postgresql` and `logback` to `integrationTest*` configurations only; disable `spotbugsTest` / `spotbugsIntegrationTest` (or drop them to default effort) and delete `exclude.xml`; add `java-test-fixtures` when the first helper is shared.

**Pros:**
- `check` works without Docker; the matrix cost becomes one container start per engine per JVM
- Unit tests become hermetic and fast; the slf4j pin can be revisited

**Cons:**
- A JUnit extension to write; `withReuse` needs a Testcontainers property on developer machines

**Effort:** 3-4 hours

**Risk:** Low

---

### Option 2: Keep per-class containers, only fix the classpath and SpotBugs

**Approach:** Move the ScalarDB and vendor dependencies to `integrationTest`, disable SpotBugs on test sources, and leave the container wiring until the second engine lands.

**Pros:**
- Smallest change with the clearest payoff (hermetic unit tests)

**Cons:**
- `check` still needs Docker; the container cost resurfaces with the first SQL Server test

**Effort:** 1 hour

**Risk:** Low

---

### Option 3: Split integration tests out of `check` entirely

**Approach:** Remove `integrationTest` from `check`, run it from a separate CI job only, and keep everything else as is.

**Pros:**
- Trivial

**Cons:**
- Developers stop running the integration suite locally; regressions reach CI first

**Effort:** 15 minutes

**Risk:** Medium

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:66-83, 137-166`
- `driver/build.gradle.kts:29, 33-37`
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java:31-82`
- `config/spotbugs/exclude.xml`
- `gradle/libs.versions.toml:7-8`
- `.github/workflows/ci.yml:38`

**Related components:**
- Todo 030 (build conventions over-general): overlaps on the SpotBugs test-source point
- Todo 028 (unused dependencies): the vendor driver and Testcontainers entries for the other engines
- `docs/design.md` section 1, "Repository and build" and the integration test matrix

**Database changes:** none

## Resources

- `docs/design.md` section 1 (Repository and build), external-database property
- Testcontainers 2.x singleton container pattern and `testcontainers.reuse.enable`
- Review agents: performance-oracle (P3-3), architecture-strategist (P3-4, P3-5), feature-dev:code-reviewer (17)

## Acceptance Criteria

- [ ] `./gradlew check` succeeds on a machine without Docker (integration suite skipped with a visible message)
- [ ] One container per engine per JVM; a second integration class on PostgreSQL adds no container start
- [ ] An engine system property selects the suite; an external-database property bypasses Testcontainers
- [ ] Unit-test classpath no longer contains `scalardb` or a vendor driver; `NativeJdbcDriverTest` runs in well under 0.1 s
- [ ] SpotBugs runs on main sources only, or at default effort on tests, and `exclude.xml` is removed or shrunk accordingly
- [ ] CI still runs the full integration suite

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated findings from performance-oracle (P3-3), architecture-strategist (P3-4, P3-5) and feature-dev:code-reviewer (17)
- The 0.88 s versus 0.009 s unit-test timing and the 4.8 s integration run were measured by the performance reviewer from the Gradle test reports

**Learnings:**
- The slf4j 1.7 pin is a consequence of ScalarDB being on the unit-test classpath, not a driver requirement

---

### 2026-10-09 - Partly resolved by the catalog rework

**By:** Claude Code

**Actions:**
- Engines (integrationTest, package testing) starts one container per engine per JVM lazily and reaps it at exit; -Dnativejdbc.engines selects a subset and "all" is the default; CatalogIntegrationTest is parameterised over the selected engines. The four extra vendor drivers and Testcontainers modules are on the integrationTest classpath, with ScalarDB's ojdbc8 excluded in favour of ojdbc11. Still open: check runs every engine locally (no tag-based subset), the unit suite still carries scalardb and pgjdbc, SpotBugs still runs on the test source sets.

## Notes

- The build layout mirrors the sibling repositories at the maintainer's request; keep the convention-plugin structure and change only the wiring described here.
