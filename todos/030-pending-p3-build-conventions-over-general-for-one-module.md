---
status: pending
priority: p3
issue_id: "030"
tags: [code-review, build, simplification]
dependencies: []
---

# Build conventions are layered for a multi-module repo that has one module

## Problem Statement

The `build-logic` conventions, the name derivation and two CI details are general enough for several
modules and several version branches, but the repository has one module and no version branch yet. Some
of that generality is a deliberate mirror of the sibling repositories; some of it (SpotBugs on test
sources, a second compile pass nobody gates on) is just cost.

## Findings

- `base-conventions` (6 lines, one assignment) could fold `group` into `java-conventions`;
  `java11-conventions` (16 lines) could fold into `java-conventions` until a module needs a different
  `release` (no samples module exists yet).
- Derived names `scalardb-native-jdbc-${project.name}` in
  `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:19` and
  `scalardb-native-jdbc.publishing-conventions.gradle.kts:19-23, 36`, plus the three-way override in
  `driver/build.gradle.kts:12-25` that undoes the derivation for the one module that exists.
- `config/spotbugs/exclude.xml` (28 lines, all test-class exclusions) could become
  `tasks.named("spotbugsTest") { enabled = false }` and the same for `spotbugsIntegrationTest`.
- `.github/workflows/auto-pr.yml` backports to version branches that do not exist and needs an org secret;
  add it with the first version branch (its `@main` pin is 023).
- `ci.yml:43-44` runs a second no-cache compile pass so that warnings print in a log nobody gates on: either
  `-Werror` and drop the pass, or drop the pass.
- Caveat for triage: the multi-file convention layout deliberately mirrors the scalardb-postgres-frontend and
  scalardb-saga repositories at the maintainer's request, so the layering may be kept on purpose. The SpotBugs
  and `ci.yml` points stand on their own.

## Proposed Solutions

### Option 1: Keep the layering, take the free wins

**Approach:** Leave `base`/`java11`/`publishing` conventions and the name derivation as they are (they match
the sibling repos); disable `spotbugsTest`/`spotbugsIntegrationTest` and delete `exclude.xml`; drop the
second compile pass or make warnings errors; leave `auto-pr.yml` but note it is inert until a version branch
exists.

**Pros:**
- No divergence from the sibling repositories.
- Faster `check` and CI immediately.

**Cons:**
- The one-module override in `driver/build.gradle.kts` stays.

**Effort:** 30 minutes

**Risk:** Low

---

### Option 2: Collapse to one convention plugin

**Approach:** Fold `base` and `java11` into `java-conventions`, drop the name derivation (the module sets
its names once), plus everything in Option 1.

**Pros:**
- About 50 fewer lines of build logic; no derivation-then-override.

**Cons:**
- Diverges from the sibling repositories the maintainer asked to follow; re-split when a second module
  (`tools`, samples) appears.

**Effort:** 1-2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `build-logic/src/main/kotlin/scalardb-native-jdbc.base-conventions.gradle.kts`
- `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:19, 66-83`
- `build-logic/src/main/kotlin/scalardb-native-jdbc.java11-conventions.gradle.kts`
- `build-logic/src/main/kotlin/scalardb-native-jdbc.publishing-conventions.gradle.kts:19-23, 36`
- `driver/build.gradle.kts:12-25`
- `config/spotbugs/exclude.xml`
- `.github/workflows/ci.yml:43-44`, `.github/workflows/auto-pr.yml`

**Related components:**
- 021 (SpotBugs on test source sets, `check` needs Docker) and 023 (`auto-pr.yml` pin) overlap.

**Database changes:** No

## Resources

- `docs/design.md` section 1 (Repository and build).
- Sibling repositories whose conventions this build mirrors: scalardb-postgres-frontend, scalardb-saga.
- Found by: code-simplicity-reviewer (P3-17).

## Acceptance Criteria

- [ ] SpotBugs no longer runs on test source sets, or does so at default effort by decision.
- [ ] CI has one compile pass, with warnings either gated or not printed twice.
- [ ] Triage has recorded whether the convention layering is kept to match the sibling repositories.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the code-simplicity-reviewer report (P3-17) of the seven-agent review of main @ 06c137f.

**Learnings:**
- The convention layout was chosen on instruction to follow the sibling repositories, so this is a triage
  decision, not an oversight.

## Notes

- Caveat: the convention layout mirrors the sibling repositories at the maintainer's request; do not collapse
  it without that decision being revisited.
