---
status: complete
priority: p2
issue_id: "208"
tags: [code-review, correctness, mysql, regression]
dependencies: []
---

# setFloat on a DOUBLE key INSERT is refused on MySQL since todo 205

## Problem Statement

Proxies.java ~934: every `matchesKeys` step binds `setFloat` as `setDouble((double) f)` whatever
the column type. Connector/J stores `setFloat(0.1f)` into DOUBLE as 0.1, so the read-back
`k = 0.10000000149011612` finds no row: `INSERT INTO dbl (k, x) VALUES (?, 1)` with setFloat(0.1f)
gives 0AN01 "reads back as 0 rows"; it worked before 8f902dc. Verified on m8-mysql (rv7).

## Proposed Solutions

### Option 1
Convert only for a key column whose type is FLOAT/REAL (from the catalog or information_schema,
asked with the column order), per bind position.

## Acceptance Criteria

- [ ] setFloat on a DOUBLE key and on a FLOAT key INSERT both work on MySQL (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 8f902dc (the fixes of todos 200-206).

### 2026-10-17 - Fixed

**By:** Claude Code
- Column types read from information_schema with the column order at plan time; only a placeholder that alone gives a FLOAT/REAL key is bound as the float's exact double. Test: setFloat on a DOUBLE key.
