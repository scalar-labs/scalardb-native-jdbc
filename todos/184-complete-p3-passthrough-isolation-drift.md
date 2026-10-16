---
status: complete
priority: p3
issue_id: "184"
tags: [code-review, jdbc, docs]
dependencies: []
---

# Passthrough can move the session's isolation level unseen

## Problem Statement

Passthrough statements skip parsing, so `RESET ALL`, `DISCARD ALL` and `set_config` of the level run;
`Router.ran` marks the session stale but never re-reads the level. Passthrough is autocommit-only and an
escape hatch, but the design does not say the level can drift through it.

## Proposed Solutions

### Option 1
After a passthrough route runs, re-read `vendor.getTransactionIsolation()` in `ran`.

### Option 2
Document it next to passthrough in design 4.1 and the README.

## Acceptance Criteria

- [ ] Either the level is re-read after passthrough, or the drift is documented.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.

### 2026-10-17 - Fixed

**By:** Claude Code
- Router.ran: after a passthrough statement whose text mentions isolation, set_config, RESET or
  DISCARD, the level is read back from the session (Option 1). Design 1 (driver properties) says so;
  SnapshotIntegrationTest covers set_config through passthrough.
