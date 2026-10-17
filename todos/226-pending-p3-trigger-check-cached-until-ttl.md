---
status: pending
priority: p3
issue_id: "226"
tags: [code-review, mysql]
dependencies: []
---

# A BEFORE INSERT trigger created by another session goes unseen until the catalog TTL

## Problem Statement

storesOtherKeys' result is fixed in the cached Route; invalidation is by session generation,
catalog generation or trustedUntil (60s default). A trigger created elsewhere is unseen for up to
60s on a connection with the route cached. Code reasoning, not run. Same class as todo 212.

## Proposed Solutions

### Option 1
Document it next to the trigger rule (part of the TTL contract).

## Acceptance Criteria

- [ ] README states the trigger rule follows the catalog TTL

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).
