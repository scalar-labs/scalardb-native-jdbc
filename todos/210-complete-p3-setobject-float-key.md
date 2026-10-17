---
status: complete
priority: p3
issue_id: "210"
tags: [code-review, correctness, mysql]
dependencies: []
---

# setObject(i, Float) on a FLOAT key is still refused on MySQL

## Problem Statement

Proxies.java ~934 matches the method name `setFloat` only; `setObject(1, 0.2f)` on a FLOAT key
INSERT gives 0AN01 "reads back as 0 rows". Verified (rv7). `setObject(i, Float, type)` likely too.

## Proposed Solutions

### Option 1
Convert any bound `Float` value (setFloat, setObject with or without a type), with 208's
column-type check.

## Acceptance Criteria

- [ ] setObject(Float) on a FLOAT key INSERT works on MySQL (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 8f902dc (the fixes of todos 200-206).

### 2026-10-17 - Fixed

**By:** Claude Code
- Any bound Float (setFloat, setObject) is converted for those placeholders. Test: setObject on a FLOAT key.
