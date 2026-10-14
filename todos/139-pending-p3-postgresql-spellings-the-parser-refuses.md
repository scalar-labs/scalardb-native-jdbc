---
status: pending
priority: p3
issue_id: "139"
tags: [code-review, parser, postgresql]
dependencies: []
---

# PostgreSQL spellings the driver refuses although they are valid

## Problem Statement

Statements PostgreSQL accepts but JSqlParser cannot parse, or the write shape refuses, fail with 0AN01.
They fail safe, nothing wrong is written, but applications using them hit the refusal.

## Findings

- `SET search_path TO x` (and `TO public, app`) cannot be parsed; `SET search_path = x` can (checked).
  Affects every statement path, not only writes; predates 4a.
- In writes: `UPDATE ONLY` / `DELETE FROM ONLY`, `UPDATE t * SET`, `WHERE CURRENT OF`, `$tag$` strings,
  `E'...\'...'`, adjacent string literals, `??` (pgjdbc's escaped jsonb `?`).

## Proposed Solutions

### Option 1
For `SET ... TO`, a token-level rewrite to `=` before parsing, or treat it as a session statement by
its first tokens; the write spellings when an application needs them.

## Recommended Action

## Acceptance Criteria

- [ ] `SET search_path TO x` runs and marks the session changed.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).
