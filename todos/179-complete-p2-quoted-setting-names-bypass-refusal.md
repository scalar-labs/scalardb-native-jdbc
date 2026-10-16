---
status: complete
priority: p2
issue_id: "179"
tags: [code-review, correctness, jdbc, sql]
dependencies: []
---

# A quoted setting name gets past the transaction-mode refusal

## Problem Statement

`SqlAst.setsTransactionMode` compares the name as the parser gives it, quotes included.
`RESET "default_transaction_isolation"`, `SET "default_transaction_isolation" = 'read committed'` and
`SET SESSION "transaction_isolation" = ...` are accepted; PostgreSQL accepts quoted setting names. The
session then drifts off the level the driver assumes: what todo 172 closed. `RESET "default_transaction_isolation"`
verified accepted through the driver at both snapshot levels (probe, 2026-10-17).

## Proposed Solutions

### Option 1
Apply the existing `unquoted()` inside `setsTransactionMode`, so SET, RESET and set_config all get it;
add the quoted forms to SqlAstTest.

## Acceptance Criteria

- [ ] The quoted forms above are refused with 0AN01.
- [ ] `SET "search_path" = ...` still runs.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.

### 2026-10-17 - Fixed

**By:** Claude Code
- SqlAst.setsTransactionMode unquotes the name; SqlAstTest and SnapshotIntegrationTest cover the
  quoted forms.
