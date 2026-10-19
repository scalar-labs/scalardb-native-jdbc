---
status: pending
priority: p3
issue_id: "286"
tags: [code-review, oracle, simplification]
dependencies: []
---

# 8d2 cleanup: level mapping, comments, docs, test gaps

## Problem Statement

Code:
1. Proxies 275-279: router.setIsolation now runs before vendor.setTransactionIsolation (all
   engines); a failing vendor call leaves them apart. `sessionLevel(level, autocommit)` and the
   original order.
2. One Router.sessionLevel for all engines instead of vendorLevel + oracleSessionLevel + a ternary.
3. Proxies 372: an ALTER SESSION on every setAutoCommit on Oracle, also when it does not change.
4. Router.ran 1874-1883 computes driverLevel before the Oracle branch that ignores it; the catch at
   1923 hides a failing ALTER.
5. Writes.asRetryable 774-783: two branches building the same exception.
Comments: endsTransaction javadoc (729-734; on Oracle it now means rollback-only, not ended);
vendorLevel javadoc (1311-1314); Proxies 284; SnapshotIntegrationTest 624 label.
Docs: design 426 ("SERIALIZABLE on Oracle once milestone 8 builds it"); 1661 and 1677-1686 (READ
COMMITTED in autocommit, 08176, rollback-only); 1757 (Oracle's snapshot level is its SERIALIZABLE);
README 125 (Oracle text appended to the SQL Server paragraph, 154 columns).
Tests: rollback-only after 08177 not asserted; 08176 never produced; the Oracle passthrough re-set
untested; a connection that sets RR and stays in autocommit not covered by the freshness check;
the Oracle refusal belongs in its own test method.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-20: found in the review of d18b58c (8d2), verified as stated above.
