---
status: pending
priority: p3
issue_id: "195"
tags: [code-review, quality, mariadb]
dependencies: []
---

# MariaDB DELETE rewrite finds FROM by lastIndexOf, which a comment can hold

## Problem Statement

Writes.onDataTable (~371-378): `DELETE FROM /* from cache */ made WHERE id = 1` on a created table
becomes `DELETE FROM /* made from cache */ ns.data AS made ...`, which MariaDB rejects (syntax
error; checked). Fails loudly, no wrong write.

## Proposed Solutions

### Option 1
Take the FROM keyword's position from the parser tokens (SqlAst), not the text.

## Acceptance Criteria

- [ ] A comment containing "from" before the table name does not break the DELETE (test)

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.
