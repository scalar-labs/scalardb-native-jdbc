---
status: pending
priority: p3
issue_id: "299"
tags: [code-review]
dependencies: []
---

# Writes.unquoted accepts mismatched quotes and keeps doubled ones

## Problem Statement

`Writes.unquoted` (milestone 9c) strips any of `"`, `[`, `` ` `` at the ends, also mismatched (`"x]`), on
every engine, and does not undo a doubled quote, so `"a""b"` asks for column `a""b`, not `a"b`. Exotic;
no injection, as the name is quoted again (review of 9c).

## Proposed Solutions

1. Strip the engine's own quote pair only, and halve doubled closing quotes.

## Acceptance Criteria

- [ ] Done

## Work Log

- 2026-10-20: found in the review of 9c.
