---
status: pending
priority: p3
issue_id: "206"
tags: [code-review, investigation, mysql]
dependencies: []
---

# Suspicions from the focused review of 4a62f9e, not confirmed

## Problem Statement

- The MERGE hint names the alias as written; under ANSI_QUOTES a double-quoted alias may not be read
  by the hint parser (then only over-locking with derived_merge off).
- The rewrite's comment matching may interact with MySQL executable comments (`/*! ... */`).
- `autoIncrement()` with lower_case_table_names=1 (stored spellings vs information_schema).
- Test 196 relies on InnoDB scanning keys in primary-key order, and does not assert row 1's
  metadata version.

## Acceptance Criteria

- [ ] Each item reproduced and filed, or ruled out

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the focused review of 4a62f9e (the fixes of todos 186-199).
