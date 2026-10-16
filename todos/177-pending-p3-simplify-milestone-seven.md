---
status: pending
priority: p3
issue_id: "177"
tags: [code-review, quality, simplification]
dependencies: []
---

# Simplifications the milestone 7 review found

## Problem Statement

- Router's three 21-argument copy methods (`anchored`, `checked`, `with`): one private copy (~40 lines).
- `covers()` inline; `checks()` through a package-private `SplitRead.meta`; `snapshot()` read once per
  route decision; `versions()`'s column list; `afterImages`/`none` share the column loop; `Anchored.width()`
  for the `states ? 3 : 2` written twice.
- Engines.scalarDb: `p.putIfAbsent(...)` instead of the three-line override.
- Tests: String.format for the scenario labels; `rows(Connection, String)` copied into six test files and
  the create-plus-Setup block into twelve: move both into testFixtures.

## Proposed Solutions

### Option 1
Make the behavior-identical cuts; the test-fixture moves as a separate change.

## Acceptance Criteria

- [ ] Same behavior, fewer lines; the full check passes.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.
