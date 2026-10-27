---
status: pending
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

## Acceptance Criteria

- [ ] getMajorVersion/getMinorVersion match the version's line, with a unit test.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
