---
status: pending
priority: p2
issue_id: "104"
tags: [routing, parser, postgresql, shell]
dependencies: []
---

# PostgreSQL's `SET name TO value` is refused as unparsable

## Problem Statement

Through the driver, `SET search_path TO sh, public` fails with 0AN01, "the statement cannot be parsed:
unexpected <S_IDENTIFIER> at line 1, column 20", while `SET search_path = sh, public` routes as a
session change. `TO` is PostgreSQL's documented spelling and the one psql users type, so in snsql the
common way to change the search path fails.

## Findings

Found by the shell's integration test (milestone 2, completion after a schema change), which uses the
`=` form until this is fixed. `SqlAstTest` covers only `SET search_path = a, b`. Likely JSQLParser's
`SET` grammar taking `TO` with a single value only, or not at all after a list.

## Proposed Solutions

### Option 1: read `SET name TO list` as `SET name = list` before parsing (recommended)
The driver needs only that the statement is a session change; rewriting the one keyword for the
parser, not for the database, keeps the statement sent unchanged.
- **Effort:** Small. **Risk:** low; `SET LOCAL` and `SET SESSION` take the same form.

## Acceptance Criteria

- [ ] `SET search_path TO a, b`, `SET LOCAL search_path TO a` and `SET SESSION search_path TO a` route as
      session changes on PostgreSQL, and the session's schemas are read again.
- [ ] The shell's integration test uses the `TO` form.

## Work Log

- 2026-10-13: raised from snsql milestone 2.
