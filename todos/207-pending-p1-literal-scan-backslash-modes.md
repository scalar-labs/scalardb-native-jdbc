---
status: pending
priority: p1
issue_id: "207"
tags: [code-review, correctness, data, mysql, mariadb]
dependencies: []
---

# The literal scan of the LOCK IN SHARE MODE rewrite misreads backslashes under NO_BACKSLASH_ESCAPES and ANSI_QUOTES

## Problem Statement

SqlAst.java ~317-360 (`isCodeAt`): backslash is always taken as an escape, so where the server does
not take it so, quote pairing shifts by one and the next literal's content reads as code; the
javadoc's "only ever reads more text as a literal" is wrong. Verified through the driver (scratchpad
rv6x):

- NO_BACKSLASH_ESCAPES, both engines: `UPDATE t SET x = 'a\', y = 'LOCK IN SHARE MODE #' WHERE k = 1`
  stores y = `FOR SHARE          #`;
- ANSI_QUOTES on MySQL: `SELECT k AS "a\" FROM t WHERE y <> '" LOCK IN SHARE MODE #'` returns a row
  natively excluded (inside `"` an identifier, backslash is not special).

## Proposed Solutions

### Option 1
No backslash escape inside `"` when ANSI_QUOTES is on (known at analyze); for single quotes, refuse
the rewrite when a backslash inside a literal precedes the match, or require the scan to agree with
and without backslash escapes.

### Option 2
Read sql_mode (NO_BACKSLASH_ESCAPES) with the ANSI_QUOTES setting and scan accordingly.

## Acceptance Criteria

- [ ] Both repros leave the literal and predicate unchanged (tests)
- [ ] A real trailing clause still rewrites under each mode

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 8f902dc (the fixes of todos 200-206).
