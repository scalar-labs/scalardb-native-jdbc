---
status: pending
priority: p2
issue_id: "173"
tags: [code-review, documentation]
dependencies: []
---

# README is out of date on isolation levels and ScalarDB's settings

## Problem Statement

README lines 12-13 claim SERIALIZABLE "with no change to ScalarDB", but the driver's SERIALIZABLE needs
ScalarDB's transactions and `scalar.db.jdbc.isolation_level` at SERIALIZABLE on PostgreSQL, and Oracle
refuses the level. Line 54 lists `REPEATABLE_READ (or SERIALIZABLE)` without saying which the driver's
SERIALIZABLE needs. Line 80, "any other isolation level ... refused with 0AN01", describes alpha.1 only.

## Proposed Solutions

### Option 1
Update the overview and Getting started; keep the alpha.1 scope section as the release's, with a line that
main now serves the snapshot levels on PostgreSQL.

## Acceptance Criteria

- [ ] README states the ScalarDB settings each driver level needs, per engine.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.
