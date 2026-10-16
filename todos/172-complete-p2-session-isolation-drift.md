---
status: complete
priority: p2
issue_id: "172"
tags: [code-review, correctness, jdbc, isolation]
dependencies: []
---

# SQL can move the session off the isolation level the driver believes it has

## Problem Statement

Router's level comes from `setTransactionIsolation` only. `SELECT set_config('default_transaction_isolation',
'read committed', false)`, `RESET ALL`, `RESET default_transaction_isolation` and `DISCARD ALL` are not
refused (SqlAst `transactionModeSet` covers SetStatement only), and revert the session characteristics
pgjdbc set. The driver then runs SNAPSHOT logic on a READ COMMITTED session: no snapshot across
statements, while the per-table checks are cached for the transaction. Predates milestone 7, matters now.

## Proposed Solutions

### Option 1
Refuse `set_config` and RESET of `transaction_isolation`/`default_transaction_*`, and RESET ALL/DISCARD ALL
outside passthrough.

### Option 2
Re-read the level after a session statement and follow it.

## Acceptance Criteria

- [ ] None of the statements above leaves the driver and the session at different levels.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.

### 2026-10-17 - Fixed

**By:** Claude Code
- SqlAst refuses set_config of a transaction setting or of a non-literal name, RESET ALL and RESET of a
  transaction setting (Option 1). README: a user function that changes the level is not detected.
- SnapshotIntegrationTest covers it at both levels.
