---
status: pending
priority: p2
issue_id: "233"
tags: [code-review, correctness, api, serializable]
dependencies: []
---

# The first-statement 40001 never succeeds on a plain retry

## Problem Statement

Router.java:1243 aborts a coarse read with a ScalarDB table in a subquery when a statement already ran,
as 40001. The same transaction replayed in the same order fails every time, so a standard retry-on-40001
loop spins. README.md:98-99 also omits the exception: a later statement whose subquery check already passed.

## Proposed Solutions

### Option 1
Report a non-retryable SQLState (e.g. 0A000 / 25000) with a message to move the read first.

### Option 2
Keep 40001 and document that retrying needs reordering.

## Acceptance Criteria

- [ ] A retry loop on 40001 doesn't spin on this rule; README states the rule and its exception

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
