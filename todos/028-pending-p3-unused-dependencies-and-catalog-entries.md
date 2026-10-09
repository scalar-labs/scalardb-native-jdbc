---
status: pending
priority: p3
issue_id: "028"
tags: [code-review, build, simplification]
dependencies: []
---

# Version catalog declares dependencies nothing uses

## Problem Statement

The version catalog and the test configurations declare libraries no module or test imports. Each one costs
a weekly Dependabot pull request and a line in the lock state for nothing, and the unused vendor drivers
also inflate the unit-test classpath (see 021).

## Findings

- `gradle/libs.versions.toml:10, 13-16, 43, 45-48, 52-55` declare picocli, mysql, mariadb, mssql, oracle and
  testcontainers-mysql / mariadb / mssqlserver / oracle-free; no module references them. Verified by grepping
  the build files and sources.
- mockito (`libs.versions.toml:19, 60-61` and
  `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:161-162`) is used by no test.
- jcip (`libs.versions.toml:32, 68` and `java-conventions.gradle.kts:157`): no annotation from it is used
  anywhere.
- The vendor drivers and containers were pre-declared for the planned five-engine matrix; triage may prefer
  to keep them if the matrix lands within weeks.

## Proposed Solutions

### Option 1: Delete, re-add with first use

**Approach:** Remove the eleven unused catalog entries and the mockito/jcip wiring; add each back with the
module or test that first needs it.

**Pros:**
- No Dependabot churn for unused artifacts.
- Smaller, honest catalog.

**Cons:**
- Someone re-adds the four vendor drivers in a few weeks.

**Effort:** 30 minutes

**Risk:** Low

---

### Option 2: Keep the vendor drivers, drop the rest

**Approach:** Delete picocli, mockito and jcip; keep the four vendor drivers and four containers because the
engine matrix is scheduled, but move them out of the unit-test configuration (021).

**Pros:**
- Matrix work starts without touching the catalog.

**Cons:**
- Dependabot still bumps eight unused artifacts weekly until then.

**Effort:** 20 minutes

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `gradle/libs.versions.toml`
- `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:157, 161-162`
- `.github/dependabot.yml` (grouping, if entries are removed)

**Related components:**
- 021 (build and test wiring) moves the remaining test dependencies to `integrationTest`.

**Database changes:** No

## Resources

- `docs/design.md` section 1 (Repository and build).
- Found by: code-simplicity-reviewer (P2-4).

## Acceptance Criteria

- [ ] Every entry in `libs.versions.toml` is referenced by at least one build script.
- [ ] No test configuration declares a library no test imports.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the code-simplicity-reviewer report (P2-4) of the seven-agent review of main @ 06c137f.

**Learnings:**
- The catalog mirrors the sibling repositories' shape, which pre-declares the full engine set; this repo has
  one engine so far.

### 2026-10-09 - Decision

**By:** Claude Code

**Actions:**
- The vendor drivers and Testcontainers modules for MySQL, MariaDB, SQL Server and Oracle are now used by the integration tests (preview scope is all five engines), so they stay. picocli, mockito and jcip remain unused.

## Notes

- Dependabot grouping in `.github/dependabot.yml` partly hides the churn but does not remove the lock updates.
