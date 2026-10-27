---
status: complete
priority: p1
issue_id: "340"
tags: [code-review, docs, getting-started]
dependencies: []
---

# Docs disagree on where testers get the driver, which Schema Loader and which Java

## Problem Statement

- getting-started.md "What you need" says the program gets the driver from Maven Central and points to the
  Compatibility table for the Schema Loader version (3.20, unreleased); README.md "Getting it" says the jar is on
  the GitHub release; development-builds.md says neither is published and to use Schema Loader 3.19.1.
- A copy of samples/getting-started outside the repository falls back to 3.20.0-beta.1 from Maven Central, which
  does not exist yet.
- Java: getting-started says Java 17+ for the example, development-builds JDK 25 to build and 17+ for Gradle; README
  says nothing.
- README lists "driver jar, JSQLParser 5.4, slf4j-api" as classpath needs; the other pages say the POM brings them.
From the docs reviews (accuracy item 3; first-time-user items 4, 5, 6).

## Proposed Solutions

1. Put the pre-release path first: a note at the top of getting-started "What you need" sending preview testers to development-builds.md and back; README "Getting it" says the same; one Java table (build / Gradle / runtime) in README; README's classpath sentence split into Maven/Gradle vs bare jar.
2. Wait until the release and fix the pages then (none now). Leaves testers stuck.

## Recommended Action

## Acceptance Criteria

- [x] A preview tester reading README then getting-started reaches development-builds.md before any download.
- [x] No page names an unpublished artifact or ScalarDB version as available.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: fixed. README "Getting it" leads with the pre-release path, splits the classpath sentence
  (Maven/Gradle vs the bare jar) and carries one Java table; getting-started "What you need" opens with a
  pre-release note to development-builds.md, which gives the Schema Loader version, and says to keep
  samples/getting-started inside the repository; snsql.md and setting-up-tables.md point to development
  builds where they name the zips.
