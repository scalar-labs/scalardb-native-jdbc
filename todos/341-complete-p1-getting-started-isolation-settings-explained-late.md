---
status: complete
priority: p1
issue_id: "341"
tags: [code-review, docs, getting-started, isolation]
dependencies: []
---

# Getting started: the three isolation settings are explained after the reader has used them

## Problem Statement

Step 2 sets `scalar.db.jdbc.isolation_level=REPEATABLE_READ`; step 4 sets the snsql session to REPEATABLE READ and
explains the same-level rule only after the transcript; the reader never sees
`scalar.db.consensus_commit.isolation_level`, the setting that must match, and the order service is not configured in
the guide. SNAPSHOT and REPEATABLE READ are used interchangeably without saying so first. First-time-user review item 1.

## Proposed Solutions

1. A short box before step 4 naming the three settings (the driver's level, ScalarDB's transaction level, ScalarDB's connection level), which two must match, and why the guide uses SNAPSHOT.
2. A sentence in "The scenario" that the order service runs ScalarDB at its default, SNAPSHOT.

## Recommended Action

## Acceptance Criteria

- [x] A reader can say, before step 4, which setting in the order service matches the program's level.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
- 2026-10-27: fixed. Step 2 names both ScalarDB isolation settings, where they go (every ScalarDB application
  writing these tables, the order service's included), which must match the driver's level, and that the guide
  uses SNAPSHOT, REPEATABLE_READ in JDBC; step 4 refers back to it and says each snsql session starts at READ
  COMMITTED.
