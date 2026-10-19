---
status: complete
priority: p2
issue_id: "284"
tags: [code-review, oracle, isolation]
dependencies: []
---

# ALTER SESSION SET ISOLATION_LEVEL is accepted outside passthrough on Oracle

## Problem Statement

`SqlAst.kind()` (947) classes AlterSession as SESSION; `transactionModeSet` covers SET statements only,
and `Router.ran` re-sets the level for passthrough only (1870). So `ALTER SESSION SET
ISOLATION_LEVEL = SERIALIZABLE` runs, reaching the SERIALIZABLE the driver refuses, while
getTransactionIsolation reports the driver's level; ojdbc caches the level it last set, so a later
`setTransactionIsolation(READ_COMMITTED)` sends nothing and does not repair it.

## Findings

Autocommit at READ COMMITTED and at REPEATABLE READ: after the ALTER, reads stay stale (ScalarDB
committed twice, the driver kept reading the old value until a driver write committed). At READ
COMMITTED in a transaction, reads became repeatable, also after commit + setTransactionIsolation(RC).

## Proposed Solutions

1. Refuse ALTER SESSION SET ISOLATION_LEVEL outside passthrough on Oracle with 0AN01, as the
   transaction mode set in SQL is elsewhere (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-20: found in the review of d18b58c (8d2), verified as stated above.
- 2026-10-20: fixed. SqlAst refuses an ALTER SESSION naming ISOLATION_LEVEL with the transaction-mode message (0AN01); other ALTER SESSIONs pass. Unit test in SqlAstTest; fails without the fix.
