---
status: pending
priority: p3
issue_id: "129"
tags: [code-review, quality, simplification]
dependencies: ["099"]
---

# Simplify the 3c split code

## Problem Statement

About 150 lines of the 3c code (9a00150) and a7ea1c1 could go. One item changes behavior.

## Findings

Line numbers at 9a00150.
- **Behavior:** `SplitRead.java:108` `check(engine, places.size(), anyInFlight)` now counts
  IN_EXPRESSION refs too; before, it counted the tables taking pieces. On SQL Server this moves the
  `tables <= 2 ? NOT_EXISTS : DISTINCT_SET` boundary (speed only). Pass `refs.size()` if unintended.
- Copy enumeration is written in both `SplitRead.java:68-145` and the SplitTest helper (~480-520), so the
  test checks a copy of production logic. Move it into SqlAst (e.g. `Split.copies(max)`) and shrink
  `Copy` to a fragment function. About 50 lines.
- Emitter keeps `from`/`to`/`splices` and two constructors; one `Edits` field would do
  (`SqlAst.java:1180-1230`). `joined()` (`:883-890`) repeats `edits(Rewrite)` (`:2812-2820`).
- Test-only overloads: `emitSplit(List<Naming>, Map)` (`:2502-2523`, plus the `Copy` defaults it needs),
  `reachedByIndex(Map, boolean)` (`:2027-2045`), `limitsEachCopy()` (`:3177-3185`).
- `countInner` has one caller (`:2677`); inner-join checks duplicated in `splittableRefs` and `subquery`
  (`:1437-1447`, `:1791-1802`); loop-invariant null check in `subquery()` (`:1808-1822`).
- Router `:368-380` null checks on `scalarDb.get(r)` that cannot be null; `Proxies` `handedOut()` hook
  used by one subclass (`:128-130`, `:309-312`); `Route.with`'s five parameters (optional).
- Stale comments: `subquery()` javadoc names a `place` parameter (`:1730`); `columnsReadFrom`,
  `reachedByIndex`, `emitSplit` @params still say `splittableRefs()` (`:1987`, `:2038`, `:2510`);
  `Copy.fragment` javadoc omits the unmatched NOT EXISTS (`:1494-1500`).

## Proposed Solutions

### Option 1
- Do the behavior item and the mechanical ones; the enumeration move with the next split change.
- **Effort:** Medium. **Risk:** Low, except the enumeration move (public SqlAst API and tests).

## Recommended Action

## Acceptance Criteria

- [ ] `:driver:check` passes; integration suite unchanged.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the simplicity review of d719647..9a00150.
