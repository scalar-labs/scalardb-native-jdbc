---
status: complete
priority: p2
issue_id: "202"
tags: [code-review, correctness, compatibility, mysql]
dependencies: []
---

# MySQL locking reads with leading whitespace or a scalar subquery are refused

## Problem Statement

Router.java ~776-782: the MERGE hint goes after `read.afterLast("SELECT")`, checked by
`regionMatches` of the emitted text against `read.source()`. The emitter drops leading whitespace
and re-spaces tokens, so on MySQL `"  \n SELECT id FROM t WHERE id = 1 FOR UPDATE"` (MyBatis XML
always starts so) and `SELECT (SELECT 1) AS one, id ... FOR UPDATE` / `WHERE id = (SELECT 3) FOR
UPDATE` are refused (0AN01); MariaDB runs them. `afterLast` also points at the last SELECT, an inner
one, where the hint would be ignored. Verified on m8-mysql (rv4_p2).

## Proposed Solutions

### Option 1
Find the first SELECT token of the emitted text itself (parse it, or have SplitRead.emit report the
offset of its outer SELECT), and put the hint there.

## Acceptance Criteria

- [ ] Leading whitespace and newlines work on MySQL (test)
- [ ] A scalar subquery in the select list or WHERE works, with the hint on the outer SELECT

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 4a62f9e (the fixes of todos 186-199).

### 2026-10-17 - Fixed

**By:** Claude Code
- The MERGE hint goes after the first SELECT of the emitted text, parsed again; leading whitespace and a scalar subquery tested.
