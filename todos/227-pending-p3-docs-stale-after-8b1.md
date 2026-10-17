---
status: pending
priority: p3
issue_id: "227"
tags: [code-review, docs]
dependencies: []
---

# Docs stale after 8b1

## Problem Statement

- README.md:100-101 still says "SNAPSHOT and SERIALIZABLE on PostgreSQL came after it", no
  MySQL/MariaDB SNAPSHOT.
- design.md:800-802 (§4.2): the relock locks "not its metadata row on MySQL" holds at RC only; at RR
  the code relocks under FOR UPDATE too and drops OF d (Router.java:736-743).
- design.md §4.4 lacks the AUTO_INCREMENT 0 caveat (todo 218).

## Proposed Solutions

### Option 1
Update the three places.

## Acceptance Criteria

- [ ] README and design match the code

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).
