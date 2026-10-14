---
status: pending
priority: p3
issue_id: "146"
tags: [code-review, performance, anchors]
dependencies: []
---

# Anchors grow with every row a transaction reads

## Problem Statement

Each row a transaction moves to is kept until it ends (about 150-250 bytes per int-keyed row, some
200 MB per million). A streaming export under `@Transactional(readOnly = true)` with `setFetchSize`
used to run in flat memory.

## Findings

- By reasoning (lost-update reviewer); the ponytail note in Anchors.java names the ceiling.

## Proposed Solutions

### Option 1
Skip recording on a read-only connection (`isReadOnly()`), where nothing can be written; document the
ceiling. Dropping entries would drop protection.

## Recommended Action

## Acceptance Criteria

- [ ] A read-only transaction records nothing; the ceiling documented.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of ef68593 and b312ced (milestone 4b).
