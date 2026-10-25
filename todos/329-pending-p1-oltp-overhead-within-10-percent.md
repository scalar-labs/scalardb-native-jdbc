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

### 2026-10-24: review of step 1

- No lost update: only `KeyedPlan` consults `anchors.unchanged`, and at PostgreSQL's REPEATABLE
  READ the anchor it compared always equalled its own keys step's version; the SKIP LOCKED read
  keeps its versions; the other engines are unchanged.
- Fixed: a read without versions dropped its anchoring mode, so a statement prepared at REPEATABLE
  READ and run at READ COMMITTED was refused instead of prepared again (`Proxies.preparedWith`).
  `RoutingIntegrationTest` covers it, in autocommit and in a transaction; it fails without the fix.
- Added to `SnapshotIntegrationTest` on PostgreSQL: after a read, a ScalarDB commit or a prepare
  then rollback of the row aborts a DELETE, an UPDATE by key and one by predicate; a lock then an
  update succeeds.

### 2026-10-24: step 2, a batch's entries in one round trip on PostgreSQL

- `Writes.applyTogether`, `Plan.together`: consecutive entries whose write is one statement (a
  keyed UPDATE or DELETE, an INSERT without RETURNING) go as one text, up to 16, after `SAVEPOINT
  scalardb_native_batch`; the next round trip releases it, the last one stays to the transaction's
  end. Outcomes are read before any is recorded; when one needs more than its statement (in flight,
  a tombstone, a skipped key, an abort, an error) the entries are rolled back to the savepoint and
  run one by one, which keeps the outcomes and `BatchUpdateException`'s counts as they were.
- `MoreWritesIntegrationTest.batches`: 20 entries, entries that see the ones before, a tombstone
  taken over in a batch, a duplicate as the 19th entry in a transaction. Design 4.4, Batches. The
  PostgreSQL integration suite, SpotBugs and Spotless pass.
- Warm-up: every chunk size is another text and every position its own server statement, so each
  connection plans about 110 positions some 6 times before the generic plan (6,100 plans of the
  fused stock update in a 60 s run, 2.2 s of planning). If it matters, fixed sizes (8, 4, 2) bound
  it at the cost of a round trip.

BenchBase, same session:

| | (a) | (c) | (c)/(a) | before step 2 |
|---|---|---|---|---|
| TPC-C throughput | 745 | 573 | 0.77 | 0.59 |
| NewOrder p50 µs | 7,629 | 9,727 | +27% | |
| Payment p50 | 2,283 | 2,729 | +20% | |
| OrderStatus p50 | 1,015 | 1,134 | +12% | |
| Delivery p50 | 17,521 | 26,087 | +49% | |
| StockLevel p50 | 1,521 | 2,740 | +80% | |
| YCSB throughput (c) | | 3,637 | 0.93 of the earlier (a), 3,910 | 0.89 |

What is left in TPC-C: the writes' execution (step 3: Delivery's multi-row UPDATE, the stamp) and
StockLevel's per-call planning (step 5).

### 2026-10-25: review of step 2

- Fixed (data loss): in a transaction the last savepoint stayed set, and a later batch whose own
  `SAVEPOINT` never took effect (a transaction already failed, a cancel on it) was undone to it,
  reviving the transaction and dropping the writes since. `Writes.releaseTogether` now releases it
  at the batch's end, also on failure: one round trip per batch in a transaction. The new test
  (25P02 after a failed statement) passes either way, as the batch's planning reads the catalog
  first there; the path is reached where it does not.
- Fixed: a timeout (57014) or a failed transaction (25P02) ends the batch without the replay, which
  gave each entry a fresh deadline.
- Fixed (performance): an entry's stamp is the transaction's own once read, so two entries on one
  key the transaction read no longer fall back; an undone stamp is on no row.
- Checked fine: the recorded state, counts, read-only connections, the bounded wait, the INSERT's
  own savepoint inside the text, parameter positions, `preferQueryMode=simple`.

### 2026-10-25: step 3, measurements (nothing changed in the driver)

TPC-C after step 2 and its review, same session: (a) 737/s, (c) 548/s, 0.74x (runs vary by about
10% between sessions: (a) 617 to 752).

Where a transaction's extra time goes, per committed transaction (`pg_stat_database`):

| | (a) | (c) | difference |
|---|---|---|---|
| server active | 0.79 ms | 1.39 ms | +0.60 ms |
| idle in transaction (round trips, client) | 4.12 ms | 4.66 ms | +0.54 ms |

- Server side: execution of the rewritten statements, and 0.1 ms of planning: the batch texts'
  warm-up (the fused stock update, 13 µs of planning per call on average) and StockLevel's join,
  622 µs of planning per call. Largest by cost per transaction: the fused stock update +218 µs,
  the fused order_line insert +68 µs, Delivery's order_line update +55 µs, the stock lock's
  in-flight check +40 µs, StockLevel +28 µs.
- Client side: JFR puts the driver's CPU at about 0.17 ms per transaction (reflection in
  `Proxies`, `HashMap`, pgjdbc's `CompositeQuery` work per execute of a multi-statement text,
  `SecureRandom` behind `UUID.randomUUID` per write); the batch's RELEASE about 0.1 ms.
- WAL is 4x per transaction (26 KB against 6.6 KB) but costs no time: about one sync per commit
  either way.

Write execution, pgbench, prepared, 4 clients:
- Delivery's 10-row order_line update: the stamp costs 112 µs of the fused statement's extra 190;
  `tx_prepared_at` and `tx_committed_at` cost nothing; the after-image CTE 23 µs.
- fillfactor 80 on the metadata tables: the stamp becomes HOT (14% to 85–100% in TPC-C) and costs
  47 µs instead of 112 in pgbench, but TPC-C does not move (511/s against 530/s, within the noise)
  nor does any statement's mean time. Not pursued.
- The stock update, one row by its full key: plain 23.6 µs, fused 63.1 µs; without the after-image
  CTE and with the keys read by one join of the data and metadata tables, 56.8 µs. The rest is the
  metadata row written and the executor's work for a plan of several data-modifying CTEs.

Conclusion: within the protocol, a write costs at least one more row written per key, about the
plain write again; for TPC-C on this laptop that floor and the driver's CPU are each a few percent of
a transaction. The rest, roughly: planning 0.1 ms, client CPU 0.17 ms, the RELEASE 0.1 ms, the
keys and after-image CTEs about 0.03 ms.

### 2026-10-25: cheap wins, first set: the batch's release, autosave, the coarse check

- The savepoint of a batch's entries sent together stays set to the next such text, which releases
  it first in the same round trip, instead of a `RELEASE` of its own at each batch's end (review fix
  1 of step 2). A commit, a rollback, an application's savepoint set, released or rolled back to, or
  a statement of a kind other than SELECT, DML or SESSION makes the connection forget it: it stays
  below theirs, never undone to, since only a failure one of the text's statements raises (classes
  22, 23, 40, 42, P0) is undone to the savepoint and replayed; any other may have come before the
  text's own savepoint was set, and ends the batch. Without the forgetting, the next batch's release
  released the application's savepoint (the new test fails at `rollback(mine)` then).
- Under pgjdbc's `autosave` (read with `PGConnection.getAutosave` by reflection before each batch),
  entries run one by one: the release would release pgjdbc's savepoint of the statement too, so its
  rollback after an error would undo statements before the text. This held for step 2's texts after
  the first already.
- The coarse in-flight check runs as a prepared statement: planned 2,661 and 1,362 times a run
  before (188 ms), 24 times now (2 ms).
- The driver does not take SQL `RELEASE SAVEPOINT` (it cannot parse it); `Connection.releaseSavepoint`
  is the way, as before.

BenchBase, same session, 60 s: TPC-C (a) 703.7, (c) 541.5, **0.77** (0.74 before); 180 s: (a) 711.8,
(c) 555.4, **0.78**. YCSB (a) 3,818, (c) 3,434, 0.90. Per committed transaction in the 180 s run:
server active +0.42 ms, idle in transaction +0.13 ms (+0.54 before).

- The batch texts' planning is warm-up: 5,280 plans of the fused stock update in 60 s, 6,601 in
  180 s (pgjdbc's 4 unnamed executions, then PostgreSQL's 5 custom plans, per position and
  connection). StockLevel's join is planned at every call by the vendor too (custom plans); the
  driver's four arms take 3x its planning, about 18 µs a transaction.
- Not the driver's CPU: JFR's samples put the workers' Java time at about 1% of a run in both runs,
  the rest waiting on the socket; the parser's waits (`CCJSqlParserUtil` on its executor) come only
  at warm-up and when the catalog's 60 s TTL expires, about 450 ms a run.
- Round trips are equal: 19.8 (a) and 20.3 (c) packets from the server per transaction (the
  container's `/proc/net/dev`).
- The server's CPU per transaction doubles: the container's cgroup counts 1.35 ms (a) and 2.84 ms
  (c), of which pg_stat_database's active time shows 0.79 and 1.23; the rest is the protocol, the
  WAL writer and autovacuum of the second row per write. Sampling `pg_stat_activity` every
  millisecond: idle after COMMIT 4.0% (a) and 3.3% (c) of the benchmark's backends' time, so the
  driver adds no time between transactions (a difference computed from pg_stat_database's totals
  said +0.9 ms, counting other sessions).
- The laptop's noise (the vendor's run alone from 655 to 737/s in this session) is as large as what
  is left to measure: further steps are measured on AWS (`docs/plans/benchmark-environment.md`).

### 2026-10-25: BenchBase on AWS, tier A

`matrix.sh BENCHBASE=1` and the harness's server counters (`93f5217`), run `20261025-1006`:
`docs/benchmarks/benchbase-postgresql-aws.md`. TPC-C 0.76 (baseline `ef77549` 0.59), YCSB 0.92
(0.70): the laptop's ratios hold with a network between client and server. Round trips equal; the
server's CPU per TPC-C commit triples (0.90 to 2.82 ms) and its active time is +0.53 ms, idle in
transaction +0.37 ms, of a transaction 2.3 ms longer at the client: about 1.4 ms is unaccounted for,
the driver's client side or time outside the server's transactions. Next: run (b) and a profile of
the client. The client's BenchBase build failed at first on its `-Werror` under JDK 25 (`a4642e3`
fixes `matrix.sh`); the working tree then ran after the baseline from a waiter on the client, with
`detached.sh` paused until it finished.

### 2026-10-25: BenchBase on AWS, runs (a), (b), (c) with the client profiled

Run `20261025-1135`, `docs/benchmarks/benchbase-postgresql-aws.md` ("Second run"): (b), the driver
with every table plain, is at 0.99 on TPC-C and YCSB, so routing costs about 1%; (c) 0.71 and 0.89.
Per TPC-C commit, (c) against (a): server statements +0.72 ms, the driver's client CPU +0.40 ms
(`LockPlan.applyFused`, `applyTogether`, `KeyedPlan.applyFused`, `Run.prepare`, `Run.<init>`), and
about 1.2 ms of server CPU outside the statements (protocol, commit, WAL writer, autovacuum), of a
transaction 2.3 ms longer. Next, if pursued: the server's CPU per process (backends against
background), to tell how much of the 1.2 ms is on the transaction's path.

### 2026-10-25: BenchBase on AWS, the server's CPU per process

Run `20261025-1253`, `docs/benchmarks/benchbase-postgresql-aws.md` ("Third run"): of the extra
1.89 ms of server CPU a TPC-C commit, 1.84 ms is the client backends' own, on the transaction's path;
background processes +0.05 ms. The vendor ran at 528, 711 and 1,151/s in the three runs, (c) at 0.76,
0.71 and 0.63: about 2 ms a transaction whatever the round trip. pgbench, 15 keyed stock updates in
one round trip: plain 1.59 ms, 15 fused 2.44 ms, one fused statement over the 15 keys
(`= ANY(ARRAY[...])`) 1.51 ms. Candidate: a batch's entries of one plan merged into one statement.

### 2026-10-25: a batch's entries of one prepared write as one statement

`8599a2c`: an UPDATE or DELETE by the key alone over a numbered VALUES list of the entries, an
INSERT of one row once per entry, up to 64, under the batch's savepoint, undone and sent together
or one by one when an entry needs more. AWS run `20261025-1419`, the same machines: TPC-C (c)
930/s to 1,065/s (+14.4%; 0.52 to 0.60 of a vendor at 1,786/s), NewOrder p50 6.13 to 4.84 ms, the
server's CPU per commit 2.81 to 2.27 ms. Left: the single-row writes, a fused statement each
(Payment +76%, Delivery +126% at p50), and StockLevel.

### 2026-10-25: BenchBase on AWS, tier A and RDS, round trips and a profile

Run `20261025-1558`, `docs/benchmarks/benchbase-postgresql-aws.md` ("Fifth run"): TPC-C tier A 0.64
at a round trip of 222 µs, RDS 0.69 at 114 µs, the commit's WAL flush to EBS in every transaction
there. Tier A's (c) took 1.1 ms more idle in transaction than in the fourth run, not explained by
the client's CPU (+0.33 ms); this run ran both tiers' JVMs on one client and sampled their CPU every
millisecond. perf (flat, no call stacks) grows the executor's setup per execution from 4.2% to 7.2%
of the backends' samples. Next: one tier at a time without CPU sampling; the time each fused
statement costs around its execution, measured locally against pg_stat_statements' execution time.

### 2026-10-25: what a fused single-row write costs, part by part (local, pgbench)

TPC-C's stock update by its full key in variants, pgbench inside the container (one client, Unix
socket, `synchronous_commit` off), the container's CPU per transaction against pg_stat_statements'
execution time; two rounds, averaged:

| variant | CPU µs | execution µs | CPU around it µs |
|---|---|---|---|
| plain UPDATE | 102 | 16 | 86 |
| the metadata row's UPDATE alone | 100 | 13 | 87 |
| UPDATE, then the stamp from its keys (one statement) | 118 | 28 | 91 |
| the same as two statements, pipelined | 139 | 28 | 112 |
| fused, without the after image, keys from the data table | 162 | 37 | 125 |
| fused, without the after image | 190 | 44 | 146 |
| fused, as now | 200 | 48 | 152 |

Against the plain UPDATE the fused statement costs +98 µs: the metadata row itself +16, the keys,
gate and CAS structure +44, the keys read through the committed view +28, the after image +10, and
two thirds of it around the execution, which pg_stat_statements does not count. A slim form for a
write by the full key, the key's metadata row read by its key, the DML gated on it being committed,
the CAS on its tx_id and version, costs +32 µs in autocommit and +33 in a transaction against +88
and +85 for the fused one, with no savepoint; an optimistic write under a savepoint, undone when
its stamp misses, costs +53, the savepoint alone about 37.

### 2026-10-25: the slim single-row write on AWS

`7d3abbf` against `f8ce61e`, run `20261025-1806` ("Sixth run"), round trip about 100 µs: TPC-C
+5.1% (0.66 to 0.69), Payment's p50 overhead +52% to +37%; YCSB +2.4% (0.84 to 0.86), updates +39%
to +23%, read-modify-write +34% to +24%. What remains per YCSB operation is about +79 µs, its reads
+11% (the in-flight check); per TPC-C transaction +1.23 ms. Locally, a slim form for a write by a
prefix of the key (Delivery's order_line update), the metadata rows read by the prefix with an
anti-join for a data row without one, costs +117 µs against +179 for the fused form, about 1% of
TPC-C.
