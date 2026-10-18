---
status: complete
priority: p1
issue_id: "239"
tags: [code-review, mysql, mariadb, serializable]
dependencies: []
---

# SELECTs that still run unlocked at SERIALIZABLE on MySQL/MariaDB

## Problem Statement

SqlAst.lockingEverySelect visits PlainSelect only, and places the clause by token, so:
- MySQL `TABLE p` (and `SELECT * FROM (TABLE q) d`) runs unchanged and unlocked; MySQL accepts
  `TABLE p LOCK IN SHARE MODE`. Probe: another session's UPDATE of p went through.
- A trailing `#` comment: `SELECT v FROM p WHERE id = 1 # tail` becomes `... # tail LOCK IN SHARE MODE`,
  the clause commented out; unlocked on both engines (probe). Only plain tables: the coarse path's
  joinTokens already returns null on a `#` (SqlAst.hasMisreadHashComment), so it refuses.

## Proposed Solutions

### Option 1
lockingEverySelect returns null on hasMisreadHashComment(); TableStatement gets the clause, or any Select that is neither PlainSelect nor a set operation is refused.

## Acceptance Criteria

- [ ] TABLE and # forms are locked or refused; tests for both

## Recommended Action

## Work Log

- 2026-10-18: filed by the focused review of 77e2295 (probes on MySQL 8.4 and MariaDB 11.4).
- 2026-10-18: fixed: a # comment is refused (hasMisreadHashComment); a top-level TABLE takes the clause, a nested one or any other Select kind is refused; tested.
