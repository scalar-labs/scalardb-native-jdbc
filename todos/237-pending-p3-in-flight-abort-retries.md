---
status: pending
priority: p3
issue_id: "237"
tags: [code-review, performance, mysql, mariadb]
dependencies: []
---

# A row put in flight after the snapshot retries 11 times before aborting

## Problem Statement

Writes.java:1668-1713: matchesInFlight is a snapshot read, so at RR the loop runs to attempt >= 10.
Probe: 40001 after 83 ms (MySQL), 49 ms (MariaDB); correct but not "at once" as the README says.

## Proposed Solutions

### Option 1
At REPEATABLE READ/SERIALIZABLE abort on the first unsettled attempt.

## Acceptance Criteria

- [ ] The abort comes on the first attempt

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
