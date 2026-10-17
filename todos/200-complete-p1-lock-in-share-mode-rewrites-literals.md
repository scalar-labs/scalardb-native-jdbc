---
status: complete
priority: p1
issue_id: "200"
tags: [code-review, correctness, data, mysql, mariadb]
dependencies: []
---

# The LOCK IN SHARE MODE rewrite changes string literals on MySQL and MariaDB

## Problem Statement

SqlAst.java:306-314: todo 194 let `TRAILING` match `#` and `-- ` comments, and the regex also matches
inside a literal: when a literal holds `LOCK IN SHARE MODE` followed by `#` or `-- ` and only the
rest of that line follows, the literal is rewritten to a padded `FOR SHARE`, and `ast.source()` is
what the driver sends for ScalarDB tables. Verified on both engines (scratchpad rv4_*):
`UPDATE t SET x = 'LOCK IN SHARE MODE #' WHERE id = 5` stores `FOR SHARE          #`; a SELECT
returns the altered literal; `WHERE x <> 'LOCK IN SHARE MODE -- z'` runs a different predicate.

## Proposed Solutions

### Option 1
Find the clause by tokens: parse fails today, so tokenize (JSQLParser's token manager, or a small
scanner that skips literals, quoted identifiers and comments) and rewrite only real keyword tokens
at the end.

### Option 2
Require the text after the match to hold no quote character (and back to end-of-text only with
`/* */` comments). Smaller, still regex.

## Acceptance Criteria

- [ ] A literal containing LOCK IN SHARE MODE followed by # or -- is never changed (unit and integration test)
- [ ] A real trailing comment after the clause still parses

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 4a62f9e (the fixes of todos 186-199).

### 2026-10-17 - Fixed

**By:** Claude Code
- Matches inside literals, quoted names and comments are skipped (SqlAst.isCodeAt); unit and integration tests.
