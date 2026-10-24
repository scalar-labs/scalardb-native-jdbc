---
status: pending
priority: p1
issue_id: "329"
tags: [performance, reads, writes, batches, postgresql, benchbase]
dependencies: ["328"]
---

# Every major OLTP statement type within 10% of the vendor URL

## Problem Statement

The user's target (2026-10-24): through the driver, with every table on ScalarDB and no ScalarDB
transaction running (BenchBase run (c)), each major OLTP statement type costs at most 10% more than
through the vendor URL (run (a)): point read, range read, update and delete by key, insert, locking
read, read-modify-write, JDBC batches, and the joins and aggregates of TPC-C. After todo 328 a write
is one round trip on PostgreSQL, but YCSB is at 0.76x and TPC-C at 0.58x.

## Findings

PostgreSQL 16, laptop Docker (8 cores), 4 terminals, 60 s, REPEATABLE READ (the driver's SNAPSHOT).
`pg_stat_statements` with planning tracked; raw files were in the session scratchpad.

- **Planning is not a cause.** Each driver statement is planned about 50 times a run, as the vendor's;
  generic plans are reused. Exception: StockLevel's coarse check and its COUNT(DISTINCT) join are
  planned on every call (0.46 ms of its 2.9 ms).
- **Driver CPU is not a cause.** YCSB reads only: 0.95x, p50 534 against 510 µs; the JFR profile has
  few samples, the threads waiting on the socket.
- **Anchors on reads are the largest cost.** A read in a transaction looks up `tx_id` and `tx_version`
  of every row returned by two correlated subqueries on the metadata primary key (design 4.2). The
  in-flight check beside them is an anti-join against the partial in-flight index, which is empty and
  costs nothing. A 219-row range of `order_line`, warm:

  | read | execution | buffers |
  |---|---|---|
  | vendor | 0.06–0.14 ms | 53 |
  | as now: in-flight check and two anchor subqueries | 0.84 ms | 1,761 |
  | in-flight check, anchors by one LEFT JOIN | 0.34 ms | 883 |
  | in-flight check only | 0.06 ms | 9 |

  YCSB's ScanRecord is 2.4 ms against 1.0 ms in execution, two thirds of YCSB's gap, and its load on
  the database slows the rest: without scans YCSB is at 0.90x, and a read's extra time drops from
  96 µs to 35 µs.
- **Without scans** (weights 60,5,0,10,0,25), p50 against (a): Read +8%, Insert +5%, Update +10%,
  read-modify-write +12%.
- **The write's floor is the metadata stamp.** pgbench, one stock update in a transaction: plain
  196 µs, the fused statement 256 µs, the update and the stamp as two statements 271 µs; the
  after-image check is 6 µs of it. Updates of the metadata tables are already 61–100% HOT.
- **Batches** run entry by entry: NewOrder takes about 46 round trips against about 28.
- **Delivery's multi-row UPDATE** of `order_line` executes in 179 µs against 39 µs, more than the
  ten extra stamps explain.

## Plan

Each step is measured with BenchBase (a) against (c) on YCSB per operation (single-operation
weights) and on TPC-C per transaction. A step is done when the types it targets meet the 10% gate on
p50 and the integration suite passes.

1. **Reads take no anchors at SNAPSHOT and SERIALIZABLE on PostgreSQL.** Every statement of the
   transaction reads one snapshot, so the anchor a later write reads in its own keys step (4.3)
   equals the one the read would have recorded, and a version that moved after the snapshot fails
   the write with PostgreSQL's 40001. This is the argument already made for the locking read in todo
   328. Drop both subqueries from plain and locking reads there; keep the in-flight check. Check
   every consumer of `anchors` first (`anchors.unchanged`, the stepwise path, the split read, the
   per-table check). MySQL and MariaDB still need the anchors: their UPDATE reads the newest
   committed version. Expected: ScanRecord from 3.2 ms to about 1.8 ms, reads near the vendor, YCSB
   about 0.9x or better.
2. **Batches in one round trip.** Send a batch's fused statements as one multi-statement string,
   chunked (for example 16 or 32 entries, to keep the number of statement texts small), each entry
   unchanged and its results read in order. Before that, check how an error in entry k surfaces and
   what `BatchUpdateException` reports. Later, if execution still matters, a set-based form: one
   multi-row INSERT with the stamp over its RETURNING, or an UPDATE driven by a VALUES list when the
   entries are distinct keys of one simple statement. Expected: NewOrder loses about 18 round trips.
3. **Measure, then attack the write's remaining execution.**
   - Find why Delivery's multi-row UPDATE plan costs 4.6x.
   - Check whether the stamp has to write `tx_prepared_at` and `tx_committed_at` on every write
     (ScalarDB's recovery and its readers decide).
   - Try `fillfactor` on the metadata tables for more HOT updates.
   - Drop the after-image CTE where the gate's state check already covers it.

   The floor is one extra row written per key, about 60 µs of database time per update.
4. **READ COMMITTED, PostgreSQL's default level.** Each statement has its own snapshot there, so
   anchors are needed:
   - Take them by one LEFT JOIN rather than two subqueries (0.84 to 0.34 ms above).
   - Evaluate the data row's `xmin` as the anchor, which needs no lookup at all. Its cost: a
     conservative abort after a ScalarDB rollback rewrites a row unchanged. The before image's
     identity stays the anchor for the in-flight arm.
   - Make the locking read one round trip in the common case: `SAVEPOINT`, the check, the lock, the
     re-check with the versions, `RELEASE`, sent together, with a rollback to the savepoint only
     when a check finds a row in flight.
   - Add READ COMMITTED runs to the gate.
5. **Coarse checks and joins.** Server-prepare StockLevel's coarse check and its join, which are
   planned on every call. Measure OrderStatus and StockLevel after step 1.
6. **The other engines** (todo 328's list): MySQL and MariaDB by a multi-statement batch or a
   multi-table UPDATE, SQL Server by one T-SQL batch, Oracle by one PL/SQL block. Each gets the same
   gate.
7. **A remote database.** The laptop's round trip is short. Run the gate once against a database on
   another host, where round trips weigh more and execution less.

Order: 1, 2, 3 on PostgreSQL at REPEATABLE READ first, then 4, 5, 6 and 7.

## Work Log

### 2026-10-24: findings and plan

- Measurements above; plan written. Nothing implemented.

### 2026-10-24: step 1, reads without anchors at SNAPSHOT and SERIALIZABLE on PostgreSQL

- `Router.versionless`, `Router.versions`: a read there appends no key and no version, only each
  table's state, as `CASE WHEN (<key>) IN (SELECT <key> FROM T_meta WHERE tx_state IN (1, 2)) THEN
  1 END`: one hashed set per statement from the in-flight index. A per-row lookup through the
  in-flight index cost a 500-row scan +48% in pgbench, the hashed set +12%, of which about 7% is the
  extra columns and 3% the in-flight arm. Dropping the key column brought YCSB's scans from 1.56x
  to 1.07x the vendor's execution time.
- `Anchored.versions`; `Proxies` records no anchors without versions. The fused locking read
  (`LockPlan.applyFused`) appends nothing either.
- Design 4.2, SNAPSHOT bullet. Unit tests, SpotBugs, Spotless and the PostgreSQL integration suite
  pass.

BenchBase run (c), against the run (a) of the same session (YCSB 3,910/s, TPC-C 736/s):

| | before step 1 | after step 1 |
|---|---|---|
| YCSB throughput | 2,966 (0.76) | 3,466 (0.89) |
| YCSB scans only | 0.86 | 0.95 |
| TPC-C throughput | 429 (0.58) | 433 (0.59) |

p50 against (a) after step 1: Read +9%, Scan +14%, Update +14%, Insert +10%, read-modify-write
+15%; TPC-C unchanged, its gap round trips (batches, step 2) and writes (step 3). Per statement in
TPC-C: item read 17 to 10 µs, the stock lock 20 to 7 µs.
