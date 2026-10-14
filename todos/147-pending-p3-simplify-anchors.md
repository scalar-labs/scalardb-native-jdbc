---
status: pending
priority: p3
issue_id: "147"
tags: [code-review, quality, simplification]
dependencies: []
---

# Simplify the 4b code

## Problem Statement

Leftovers of the two abandoned read forms and duplication, from the review of 4b.

## Findings

- `SqlAst.selectListEnd()` is unused (abandoned design), 17 lines.
- `rowPerRow` via `isGrouped` (with 143).
- The whole-row and schema-qualifier loop in `Router.anchorable` is stale: the extras go on the select
  list now, and the reviewer's runs of `SELECT imp FROM ...` and `ns.t.x` returned the plain rows
  without it (8 lines; the plan's wording too).
- `Router.versions` rebuilds `Writes.Names` (meta, key join, table id); `Anchored`'s table id repeats
  `Names.table`.
- `selectExtra`: `anchored` and a lambda testing it, tested again in SplitRead; pass a nullable function
  or move `versions` into SplitRead. `java.util.function.Function` written in full; missing @params.
- `AnchoredHandler` repeats `HiddenColumns`' index check.

## Proposed Solutions

### Option 1
Do it with the fixes of 141-145, which touch the same code.

## Recommended Action

## Acceptance Criteria

- [ ] `:driver:check` and the integration suite pass.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of ef68593 and b312ced (milestone 4b).
