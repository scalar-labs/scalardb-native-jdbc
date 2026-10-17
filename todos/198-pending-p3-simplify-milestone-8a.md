---
status: pending
priority: p3
issue_id: "198"
tags: [code-review, quality, simplicity]
dependencies: []
---

# Simplifications the milestone 8a review found

## Problem Statement

Behavior-preserving, from the simplicity pass:

1. Router ~727: drop the dead `afterLast("LOCK") - 4` branch (also 189).
2. Router ~741-773: do the clause stripping under `if (inner != null)`, starting from `text.sql()`,
   instead of the `emitted`/`own`/`at` sentinels and the nested condition.
3. Router ~717-734: one `share` spelling and an `update` flag for `inner` and `relock`.
4. Writes ~610-626: `Run.prepareGenerated` duplicates `prepare(Step, key, scrollable)`; thread a
   generated-keys option through and fold `limit()` back in.
5. Writes ~1010: `InsertPlan.generated` is `n.engine == MYSQL && readBack == null`; drop the field.
6. Writes ~1537-1550: alias only the first UNION ALL row's columns once, outside the loop.
7. Writes ~436: compute the `from` index inside the MariaDB DELETE branch only.

Take them with the fixes above where they touch the same code.

## Acceptance Criteria

- [ ] Each simplification applied or rejected with a reason

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
