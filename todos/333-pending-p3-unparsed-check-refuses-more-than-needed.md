---
status: pending
priority: p3
issue_id: "333"
tags: [code-review, unparsed, usability, postgresql]
dependencies: ["314"]
---

# The check for unparsed statements refuses some ordinary plain-table statements; long names on PostgreSQL

## Problem Statement

The check for statements the parser cannot read (todo 314, `SqlWords.whyNotAsWritten`) errs the safe
way. A few ordinary forms on plain tables are refused that could be sent, and one PostgreSQL rule
is not followed.

## Findings

From the review of todo 314 (read in the code):

- The word checks run over the whole text, literals and comments included. A `CREATE`, `ALTER` or
  `DROP` holding `event`, `system`, `body`, `local`, `global`, `rule`, `session`, `trigger` or
  `execute` anywhere is refused, for example `CREATE TABLE t (event text)`.
- A `;` in a trailing comment is refused: `SELECT 1; -- done`.
- Modifiers not in `DDL_MODIFIERS` read as the kind: Oracle `FORCE`, `EDITIONABLE`, `NONEDITIONABLE`;
  PostgreSQL `RECURSIVE`; MySQL `ALGORITHM=`, `DEFINER=`, `SQL SECURITY`.
- PostgreSQL truncates an identifier to 63 bytes, so a longer word that starts with a 63-byte ScalarDB
  table name names that table. This probably holds for the parsed path too.

## Proposed Solutions

### Option 1: widen the lists, word checks outside literals
Add the modifiers; run the code-or-session word check over the text without literals and comments;
match 63-byte prefixes on PostgreSQL. **Effort:** Small to Medium.

### Option 2: leave as is
Each case is refused with its reason, which a user can see. **Effort:** none.

## Recommended Action

## Acceptance Criteria

- [ ] `SqlWordsTest` covers each case above.

## Work Log

### 2026-10-25 - Found in the review of todo 314

**By:** Claude Code
- Read in `SqlWords.whyNotAsWritten`.
