---
status: complete
priority: p3
issue_id: "140"
tags: [code-review, quality, simplification]
dependencies: []
---

# Simplify the 4a write code

## Problem Statement

About 140 lines of `Writes`, `SqlAst` and `Proxies` could go (simplicity review of cd135be).

## Findings

- The metadata stamp SET clause is written three times, the metadata INSERT twice with different
  column orders, the CAS WHERE tail twice: a `Names.stamp(version)` and one insert builder.
- `Plan` has nine fields, half null per kind: an abstract plan with an update/delete and an insert
  subclass, holding `Names` instead of rebuilding it per run.
- Anchors as parallel lists of `Object[]` (`tx_state` slot dead): a small `Row` class.
- `SqlAst.sourceText` repeats the new `span(Token, Token)`; its tab guard is stale (tabs count as one
  column through the driver's parse, checked).
- `Names.keyEquals(q)` is always called with `""`; `Proxies` has an identity ternary (`write =
  prepared`) and two near-identical prepared-execute branches (merge with 130's fix).
- `Writes.plan` threads `tables` for messages; returning `Outcome<Plan>` as `StatementShape.classify`
  does would match the codebase.

## Proposed Solutions

### Option 1
Do it together with the fixes of 130-138, which touch the same code.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] `:driver:check` and the integration suite pass; WritesTest unchanged in substance.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).

### 2026-10-14 - Fixed

**By:** Claude Code
- Writes split into KeyedPlan and InsertPlan with a Row per key, a Run holding the stamp and deadline, Names.stamp/insertStamp/committed shared; SqlAst.sourceText reuses span (its tab guard dropped: tabs count one column through the driver's parse); Proxies' identity ternary gone. Kept: the tables parameter of Writes.plan, since Outcome's factories are package-private to sql.
