---
status: complete
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

### 2026-10-17 - Fixed

**By:** Claude Code
- Route: `anchored`, `checked` and `with` share one private `copy`.
- Router: `covers()` inlined; `checks()` through `SplitRead.meta`; `snapshot()` read once in `read()`;
  `versions()`'s column list in one line; `Anchored.width()` used by Router and Proxies.
- SplitRead: `none` and `afterImages` share a `columns` helper.
- Engines.scalarDb: `putIfAbsent` for the storage isolation level.
- Tests: `Rows.rows(Connection, String)`, `rows(PreparedStatement)`, `rows(ResultSet)` in testFixtures
  replace the copies in seven test classes; SerializableIntegrationTest labels use String.format (f7ddd58).
- Not done: the create-plus-Setup blocks differ per class (catalog reuse, results inspected, extra
  tables), so a shared helper would not shrink them much.
