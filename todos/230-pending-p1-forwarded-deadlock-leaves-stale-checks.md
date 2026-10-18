---
status: pending
priority: p1
issue_id: "230"
tags: [code-review, correctness, mysql, mariadb, serializable]
dependencies: []
---

# A deadlock on a forwarded statement leaves Router.passed stale (dirty read)

## Problem Statement

Proxies.java:813-815: a vendor error from forward(...) sets neither rollbackOnly nor transactionEnded;
only Writes.within and an Aborted check do. MySQL/MariaDB 1213 rolls the whole transaction back and the
session continues in a new implicit one. The next coarse read skips its in-flight check (Router.java:1240,
passed.contains) and reads ns.data, which holds ScalarDB's uncommitted after-images.
Verified by probe on both engines: d1, d2 coarse read SELECT a.v FROM pz.t a JOIN pz.n n ... ([1]); both
UPDATE pz.n id=1, d2 gets 40001/1213; ScalarDB updates the row to 99 and prepares; d2 repeats the read
without rollback and gets [99], never committed. Anchors and ranInTransaction are stale the same way
(the anchor part affects 8b1 too).

## Proposed Solutions

### Option 1
In the execute and batch paths, outside autocommit, when Writes.endsTransaction(vendor, e) is true set owner.rollbackOnly = true (and clear passed).

### Option 2
Call router.transactionEnded() on such errors and let the session continue.

## Acceptance Criteria

- [ ] After a 1213 on a forwarded statement, a later statement fails (rollback-only) or re-runs its checks; the probe no longer returns 99

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
