---
status: complete
priority: p3
issue_id: "165"
tags: [code-review, simplicity]
dependencies: []
---

# Simplify the anchored read setup, the interleaving test and the benchmark

## Findings (behaviour unchanged, about 75-90 lines)

- Router.java:839-845, 871-882: fill `tables` and `keys` in the loop that fills `anchorNames`; collapse
  the if/else-if into one `r.anchored(anchored.isEmpty() ? null : new Anchored(tables, keys), anchoring)`.
- InterleavingIntegrationTest: `Table` as a record (:91-101); drop the `ids` list (:154-163); one loop
  for the committed sum and the abort tally (:480, 492-499); a `v(Connection, Table, int)` helper
  (:586-588, 676-679); one Update builder helper for the actor and `set()` (:646-652, 692-699).
- RouteCostBenchmark: ordered `String[][]` shapes instead of `Map.of` plus a key list (:36-46, 98-99);
  one formatted DDL string (:64-72); inline `defaultCatalogTtl()` (:115-119).
- WriteIntegrationTest.anchorsFromJoins: the update, assert-40001, rollback block appears 7 times; an
  `assertAnchored(Connection, String)` helper saves about 20 lines.

## Acceptance Criteria

- [x] The same tests pass; no behaviour change.

## Recommended Action

All items.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.

### 2026-10-16 - Fixed

**By:** Claude Code
- Router: the anchored tables and key widths are collected in the loop that builds the names; one
  `anchored(...)` call.
- InterleavingIntegrationTest: Table is a record; no ids list; `v()` and `update()` helpers; the
  counts in one loop (with todo 162's restructuring into `run()`).
- RouteCostBenchmark: ordered shape array, one formatted DDL, the TTL inline.
- WriteIntegrationTest.anchorsFromJoins: `assertAborts` replaces seven copies.
