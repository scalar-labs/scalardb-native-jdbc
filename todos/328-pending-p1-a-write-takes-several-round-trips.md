---
status: pending
priority: p1
issue_id: "328"
tags: [performance, writes, postgresql, benchbase]
dependencies: []
---

# A write takes several round trips where the vendor driver takes one

## Problem Statement

BenchBase run (c) of milestone T6 (every table on ScalarDB, no ScalarDB transaction running)
measured TPC-C at 0.19x and YCSB at 0.70x the vendor URL on PostgreSQL. The driver's CPU time and
the database's execution time are not the cause. Round trips are: each write through the driver
took 4 to 6 statements where the vendor driver takes one, and a locking read took about 6.
The user's requirement (2026-10-24): a write is one round trip, as through the vendor driver.

## Findings

Measured with `pg_stat_statements` and JFR (laptop Docker, PostgreSQL 16, 4 terminals,
`driver/build/bench/benchbase-postgresql.md`):

- TPC-C, per committed transaction: 79.5 statements in (c) against 26.8 in (a): 37.5 the
  application's, 21.2 driver reads of keys and metadata, 14.8 metadata writes, 6.0 savepoints.
  At about 0.21 ms a round trip under load that is about 17 ms of (c)'s 25 ms per transaction,
  against 5.6 ms in (a). Database execution: 1.06 ms against 0.35 ms per transaction. JFR: about
  2% of samples in driver code, the threads otherwise waiting on the socket.
- Per write before this todo (`Writes.KeyedPlan`, `Writes.InsertPlan`):
  - UPDATE or DELETE at SNAPSHOT on PostgreSQL: after-image check, key and metadata read, DML,
    CAS: 4 round trips.
  - INSERT on PostgreSQL: SAVEPOINT, INSERT, RELEASE, metadata insert: 4.
  - MySQL, MariaDB and SQL Server add a savepoint around each write (`Writes.apply`): 2 more.
- Per locking read on PostgreSQL (`Writes.LockPlan`): in-flight check, SAVEPOINT, the lock, the
  in-flight check again, versions under the lock, RELEASE: 6. TPC-C's NewOrder takes one per
  stock row.
- A JDBC batch of writes runs entry by entry (4.4, Batches), one write's round trips each; the
  vendor driver sends the whole batch in one.
- One TPC-C warehouse with 4 terminals makes every NewOrder wait on the same district row, so
  longer transactions also wait longer on each other's locks: 0.33x becomes 0.19x.

## Proposed Solutions

Fold each write into one statement (design 4.3, "One round trip per write"): the checks run in the
same statement as the writes, which are gated on them, and the driver decides from what the
statement returns. Every check either aborts the transaction, which undoes the writes, or, at READ
COMMITTED, waits for a row in flight, which the gate keeps unwritten.

Per engine:

- PostgreSQL: data-modifying CTEs; the savepoint an INSERT needs, in the same round trip.
- MySQL, MariaDB: a multi-statement batch (`allowMultiQueries`), or a multi-table
  `UPDATE T STRAIGHT_JOIN T_meta` with the CAS in its WHERE.
- SQL Server: one T-SQL batch with `OUTPUT`, variables, `SAVE TRAN` and `THROW`.
- Oracle: one anonymous PL/SQL block, results through OUT binds.

A single statement or batch undoes its own work on failure, which also drops the per-write
savepoints there.

## Work Log

### 2026-10-24: PostgreSQL UPDATE, DELETE and INSERT

- `Writes.fusedKeyed`, `KeyedPlan.applyFused`: an UPDATE or DELETE on a decoupled table is one
  statement on PostgreSQL. The steps one by one remain for a key without a metadata row, for the
  inline layout, and on the other engines.
- `Writes.fusedInsert`, `InsertPlan.applyFused`: an INSERT is one round trip, the savepoint
  included; the tombstone takeover and the duplicate's classification are extra round trips only
  when they happen.
- Prototyped first by hand on PostgreSQL 16: normal key, key in flight (nothing written), missing
  key, and a concurrent ScalarDB-style prepare at READ COMMITTED (CAS fails, driver aborts) and
  REPEATABLE READ (PostgreSQL's 40001).

- `LockPlan.applyFused`: at SNAPSHOT and SERIALIZABLE on PostgreSQL a locking read is its
  in-flight check and the lock, sent in one round trip. Every statement there reads the
  transaction's one snapshot, so the check after the lock and the versions read under it saw
  nothing more; a row in flight aborts at once at those levels.
- Two integration tests assumed the separate steps and now expect the PostgreSQL outcome:
  todo 157's timeout between a key's DML and its CAS (that point no longer exists), and todo
  134's prepare-and-rollback around the write (the statement writes the restored row, which the
  todo's acceptance criterion allows). The PostgreSQL integration suite passes.

### Results (BenchBase on PostgreSQL 16, laptop Docker, 4 terminals, 60 s)

TPC-C at 4 warehouses; YCSB with weights 50,5,15,10,0,20 (no deletes). Throughput/s, and
against (a), the vendor URL:

| workload | (a) | (c) before | (c) writes fused | (c) locking reads fused too |
|---|---|---|---|---|
| YCSB | 3,899 | 2,053 (0.53) | 2,314 (0.59) | 2,941 (0.75) |
| TPC-C | 739 | 149 (0.20) | 256 (0.35) | 433 (0.59) |

p50 per transaction, (a) against the last column: YCSB Update 1.02 / 1.21 ms, Insert 1.01 /
1.15, RMW 1.33 / 1.58, Read 0.50 / 0.60, Scan 1.72 / 3.23; TPC-C NewOrder 7.7 / 14.4 ms,
Payment 2.3 / 2.8, Delivery 17.6 / 26.6. Raw reports and `pg_stat_statements` dumps were in the
session's scratchpad.

## Remaining

- [ ] Batches: a batch's entries run one by one, a round trip each; pgjdbc sends the vendor's
      batch in one. TPC-C's NewOrder batches its stock updates and order lines, about half of the
      remaining NewOrder gap (4 round trips per item against 2). One statement over all of a
      batch's rows, or the entries' fused statements pipelined.
- [ ] ScanRecord's read is 2x the vendor's in execution, not round trips: the rewritten range read
      looks up each row's metadata by three correlated subqueries. A read-rewrite matter, todo of
      its own.
- [ ] The locking read at READ COMMITTED on PostgreSQL, still its steps.
- [ ] MySQL, MariaDB, SQL Server, Oracle.
