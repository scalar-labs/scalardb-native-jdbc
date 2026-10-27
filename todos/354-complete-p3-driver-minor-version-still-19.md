---
status: complete
priority: p3
issue_id: "354"
tags: [code-review, driver, versioning]
dependencies: []
---

# Driver.getMinorVersion() returns 19 on the 3.20 line

## Problem Statement

NativeJdbcDriver.MINOR_VERSION is 19 while gradle.properties is 3.20.0-beta.1-SNAPSHOT; README and the design say
the driver's major.minor is the certified ScalarDB line. Accuracy review item 13; confirmed.

## Proposed Solutions

1. Set MINOR_VERSION to 20, or derive it from the build's version (a generated constant or the manifest).

## Recommended Action

Solution 1, the constant: MINOR_VERSION = 20, guarded by a unit test against the build version.

## Acceptance Criteria

- [x] getMajorVersion/getMinorVersion match the version's line, with a unit test.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-28: done. NativeJdbcDriver.MINOR_VERSION is 20. driver/build.gradle.kts passes the project
  version to the unit tests as nativejdbc.version, and NativeJdbcDriverTest's
  majorAndMinorVersion_areTheBuildVersionsLine checks "major.minor." against it, so the next line's
  version bump fails the test until the constant follows. Ran: the test passes; spotlessCheck clean.
