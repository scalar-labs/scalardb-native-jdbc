---
status: pending
priority: p1
issue_id: "376"
tags: [correctness, postgresql, transactions, insert, duplicate-key]
dependencies: []
---

# On PostgreSQL, a duplicate key on a ScalarDB table's INSERT leaves the transaction committable

## Problem Statement

On PostgreSQL a failed statement aborts the transaction: a later `COMMIT` rolls it all back. A
duplicate key on an INSERT into a ScalarDB table through the driver does not: the driver rolls
back to its own savepoint to classify the duplicate, rethrows `23505`, and the transaction goes on.
A `COMMIT` after the error then commits the transaction's earlier writes without the failed INSERT.
An application written for PostgreSQL that commits after an error it did not handle, as a psql
script does, gets a partial commit it never gets on PostgreSQL itself; the same transaction on an
ordinary table rolls back whole.

## Findings (2026-11-17, from the samples/demo rehearsal)

In `snsql` on the demo's point database, after the import and the setup, at the default SNAPSHOT:

```sql
BEGIN;
UPDATE point.point_account SET points = points + 1 WHERE customer_id = 1;
INSERT INTO point.point_history VALUES ('h1', 1, 1, 'dup');  -- h1 exists: 23505
COMMIT;                                                       -- prints COMMIT
```

`points` went from 750 to 751: the UPDATE was committed. The user's rehearsal did it three times
(+150 points, no history rows). The same shape on an ordinary table through the driver
(`public.plain`, UPDATE then a duplicate INSERT, COMMIT) left the row unchanged: pgjdbc's COMMIT on
an aborted transaction rolls back.

The cause is `Writes.applyFused` (and the same catch about 160 lines below it): on a duplicate with
an in-flight check, `ROLLBACK TO SAVEPOINT scalardb_native_write; RELEASE SAVEPOINT ...`, commented
"the transaction goes on, as on the engines where a failed statement does not end it", then
`throw duplicate(run, e)`. The rollback to the savepoint is needed: `duplicate` reads `tx_state` to
tell an application duplicate from a key in flight (`40001`), which an aborted transaction cannot.
But `Writes.within` marks the application's transaction rollback-only only where
`endsTransaction` says the vendor ended it, which is false here, since the savepoint undid the
abort. `Plan.savepointed()` already keeps PostgreSQL out of the statement-level undo for this
reason ("not on PostgreSQL, where a failed statement aborts the transaction"); the fused INSERT's
duplicate path is the exception.

## Proposed fix

After the classification, mark the application's transaction rollback-only on PostgreSQL, so the
next statement or `commit()` fails with the driver's `Aborted` ("the transaction was aborted by an
earlier statement") and `rollback()` clears it, as PostgreSQL's own aborted state behaves. Either
`within` treats any vendor error on PostgreSQL as ending the transaction, or the two catches run
the rollback-only hook themselves. Check the other driver-internal savepoint rollbacks on
PostgreSQL (`TOGETHER`, `scalardb_native_f`) for the same gap. Tests: the shape above through the
driver on PostgreSQL commits nothing, and its `COMMIT` fails; on MySQL, MariaDB, SQL Server and
Oracle the transaction goes on, as each engine's own.

## Notes

- Released: the two catches came with todo 328 (0d82392), in v3.20.0 and v3.20.1, and are on the
  `3` and `3.20` branches as on main. The fix goes to all three (a 3.20.2 patch).

- Not the demo's fault. The rehearsal hit the duplicate because an `h1` row from the earlier run
  sheet (+100, `h1`) was still there; `./demo.sh reset` clears it.
