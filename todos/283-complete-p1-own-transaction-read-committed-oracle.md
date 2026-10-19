---
status: complete
priority: p1
issue_id: "283"
tags: [code-review, oracle, isolation]
dependencies: []
---

# A scrollable read in autocommit at REPEATABLE READ on Oracle returns uncommitted rows

## Problem Statement

In autocommit the checks and the read share a transaction of their own (Proxies.java:833-839), the
vendor's autocommit turned off directly; on Oracle the session then stays at READ COMMITTED
(Router.oracleSessionLevel, 8d2), so the per-table check and the read are two snapshots, against
design 4.2 ("the checks and the read share one snapshot"). A scrollable result (states on: the
committed copy whole, rows in flight with their after images, checked only as the application moves
to them) relies on the check having found nothing in flight in the same snapshot.

## Findings

Through the driver: REPEATABLE READ, autocommit, TYPE_SCROLL_INSENSITIVE `SELECT id FROM rp.t ORDER
BY id` (20 committed rows), `rs.last()`, `rs.getRow()`, while another thread loops a 2PC insert of
id 0, prepare, rollback (never committed): over 25 s, 69 results of 21 rows, no abort; with
autocommit off, 0. Other engines' autocommit runs at the snapshot level.

## Proposed Solutions

1. On Oracle set the session to SERIALIZABLE around the own transaction (and back) (small).
2. Or route an autocommit read at REPEATABLE READ on Oracle as READ COMMITTED (one statement).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-20: found in the review of d18b58c (8d2), verified as stated above.
- 2026-10-20: fixed. The own transaction of an autocommit checked read sets the Oracle session to SERIALIZABLE, and back to READ COMMITTED at its end. Tested in SnapshotIntegrationTest.scrollableInAutocommit (all engines, an 8 s race); without the fix 20 reads in 8 s counted the uncommitted row.
