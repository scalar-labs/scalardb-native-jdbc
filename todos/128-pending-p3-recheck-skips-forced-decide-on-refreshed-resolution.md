---
status: pending
priority: p3
issue_id: "128"
tags: [code-review, correctness, routing, mysql, mariadb]
dependencies: ["111", "114"]
---

# A due recheck takes a changed table resolution for a count change

## Problem Statement

1a2b7c9 lets `Router.current()` skip the forced re-decide when the catalog generation is unchanged,
taking that to mean only the counts moved (`Router.java:762`). But `Catalog.resolve` refreshes a
resolution after its TTL without bumping `generation` (`Catalog.java` ~470-500). If another client
created a table that shadows the name, a prepared per-row split read whose recheck falls due gets a route
marked `countsOnly` that is not. The proxy then re-prepares silently onto the new resolution (equal
parameter sources) or `keepText`s the old text for one more TTL. Before the change it raised "prepare it
again".

## Proposed Solutions

### Option 1
- Keep the forced check also when unchanged; it runs at most once a second, when counts are due.
- **Effort:** Small. **Risk:** Low.

### Option 2
- Bump `generation` when a refreshed resolution differs.

## Recommended Action

## Acceptance Criteria

- [ ] A test shadowing a table name during a prepared statement's life gets "prepare it again" (or the
      documented behavior), not a silent switch.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of 1a2b7c9 (reading the code; not reproduced).
