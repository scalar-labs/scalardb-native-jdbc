---
status: complete
priority: p2
issue_id: "330"
tags: [passthrough, safety, postgresql, mysql]
dependencies: ["323"]
---

# Passthrough's name check misreads a comment start and misses a ScalarDB table after it

## Problem Statement

Passthrough refuses a statement that names a ScalarDB table (todo 323) by the words of its text,
comments left out (`SqlWords.find`). The comment remover blanks from `--` to the end of the line
where the engine does not start a comment there, so a name after it is not seen and the statement
runs as written on a ScalarDB table, outside ScalarDB's protocol.

## Findings

Measured with `SqlWords.find` on `origin/main` 02bf7d3, names `[orders]`:

- PostgreSQL, a dollar-quoted string: `SELECT $$--$$, x FROM orders` finds nothing. The lexer knows
  `'`, `"` and backticks, not `$$` or `$tag$`.
- MySQL and MariaDB, where `--` starts a comment only before a space or control character:
  `SELECT 1--1, x FROM orders` (1 minus -1) finds nothing.
- Oracle `q'[--]'` is read correctly (found).

Not affected: the check for statements the parser cannot read (todo 314,
`SqlWords.whyNotAsWritten`), which reads the text with its comments kept.

## Proposed Solutions

### Option 1: keep comments in passthrough's check too
Match the words of the whole text, as todo 314's check does. A name in a comment then refuses the
statement, which errs the safe way. **Effort:** Small (one call and tests).

### Option 2: teach the lexer dollar quotes and MySQL's `-- ` rule
Keeps comments out, at the cost of following each engine's lexing exactly. **Effort:** Medium.

## Recommended Action

Option 1: passthrough matches names over the whole text, comments kept (`SqlWords.named`), a word also split at `#`, `@` and `$`.

## Acceptance Criteria

- [x] Both statements above are refused in passthrough on their engines.
- [x] `SqlWordsTest` covers them.

## Work Log

### 2026-10-25 - Found while implementing todo 314

**By:** Claude Code
- Reproduced with `SqlWords.find` as above.

### 2026-10-25 - Fixed

**By:** Claude Code
- Passthrough uses `SqlWords.named`; a name in a comment now refuses the statement. `SqlWordsTest.named_readsCommentsAsWords_soNoLexingOfThemHidesAName`. The tools' `check` keeps `find` for function bodies.
