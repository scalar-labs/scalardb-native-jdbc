---
status: complete
priority: p2
issue_id: "243"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# Common set operations are refused at SERIALIZABLE

## Problem Statement

`SELECT 1 UNION SELECT 2` and `WITH RECURSIVE r AS (SELECT 1 UNION ALL SELECT n+1 FROM r ...)` get
0AN01 on both engines. Both accept `(SELECT ... LOCK IN SHARE MODE) UNION (...)`; the recursive form
with parenthesized parts ran on both (probe).

## Proposed Solutions

### Option 1
Add the parentheses: '(' before each bare operand and ' LOCK IN SHARE MODE)' after it.

### Option 2
Skip operands with no base table; document the rest.

## Acceptance Criteria

- [ ] Both forms run and lock their tables

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
- 2026-10-18: fixed: set-operation operands are put in parentheses, ending before the operation's ORDER BY/LIMIT the parser gives the last operand; UNION ... ORDER BY and WITH RECURSIVE tested.
