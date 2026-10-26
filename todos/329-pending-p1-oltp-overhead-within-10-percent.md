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

### 2026-10-25: review of the batch savepoint, the merged write and the slim write

`/code-review high` over `92dacfc..main` (the driver's changes since step 2's review), each finding
checked:

- Fixed: the slim write accepted a committed metadata row with nothing written as a count of 0;
  the fused statement tells a row deleted since it was read (an abort) from one never there. Slim
  now takes only a row written and stamped; anything else runs the fused statement, which it
  leaves nothing to undo for.
- Checked, not a divergence: a metadata row in a state other than 1, 2, 3 or NULL is no committed
  image, and the fused statement counts 0 as slim did; slim now hands it to the fused one anyway.
  `MoreWritesIntegrationTest` covers it (state 4: 0 rows, nothing written).
- Fixed: the batch's savepoint was marked set before the text was sent, so a bind that failed
  (a missing value) left the next batch's text releasing a savepoint never set: 3B001, the
  application's transaction lost. It is marked as the text is sent now; the new test fails without
  the fix ("savepoint does not exist").
- Fixed: the merged keyed write took an entry whose key it found no row for as a count of 0; its
  VALUES type the placeholders apart from their columns, so a CHAR key's trailing blanks against a
  text value missed the row. Any entry without a row now runs the entries one by one.
- Fixed: after a merged statement failed, the next window merged again over entries it had failed
  for, up to four times for 64 entries; not again now up to where it failed.
- Fixed: the in-flight check is prepared only on PostgreSQL, where pgjdbc keeps its plan; other
  drivers would pay a prepare and a close per call.
- Done: one builder for the CAS and stamp CTEs of the fused, merged and slim statements;
  `placeholdersIn` reads `placeholderSpans`.
- Left, as trade-offs: a write slim does not take pays one more statement before the fused one
  (keys without metadata, in flight, missing); and each plan's merged form has a text per entry
  count from 2 to 64, twice (with and without the release), so pgjdbc's cache of 256 statements
  per connection can fill for an application with many batched statements. Fixed sizes, padded,
  would bound it.

### 2026-10-25: stamping at commit, weighed and set aside

Run `20261025-1806`'s statements per TPC-C commit, slim build: 882 µs of statement time against the
vendor's 360. The single-row writes cost about +16 to +33 µs over the vendor's now (pgbench: slim
update +32, a fused INSERT +21 and +33 under its savepoint, of which the metadata row alone is about
16), so a stamp moved to the commit would still write every metadata row and save only what is
left around it, which the merged and slim forms already removed. The decoupled layout's floor is the
second row per write, about 17 per TPC-C transaction; ScalarDB's inline layout has none, and its
one-statement forms (todo 328's, the merged and slim writes) are the lever left for tables that can
use it. Smaller items left: the INSERT's savepoint at SNAPSHOT (about 12 µs), the read path (5-10 µs
a read), the slim write by a prefix of the key (about 1% of TPC-C), StockLevel.

### 2026-10-25: the inline layout's one-statement forms

With the metadata in the data row, pgbench measured the vendor at 102 µs of server CPU for a keyed
update, the decoupled layout at 132 and the inline one at 112 (an INSERT 103 / 137 / 107, a read
75 / 92 / 87). Two CTEs cannot modify the same row in one PostgreSQL statement, so the inline
table needs its own forms, which `Writes` now builds on PostgreSQL when no locking clause is used:

- by the full key (slim): the user's UPDATE with the stamp's SET items appended, its CAS on
  `tx_id` and `tx_version` in the WHERE; a DELETE takes the row and its metadata at once;
- a batch by the key (merged): the entries' VALUES, each key looked up by itself, the gate on
  state and duplicates, one UPDATE stamping as it writes;
- an INSERT carries its metadata columns, and a merged batch of them reads one stamp bound first
  (`Step.stampFirst`, `Merge.stampFirst`);
- beyond the key (fused): the stamp bound first, the keys and gate as the decoupled form, and the
  CAS folded into the version, -1 where the anchor moved, which the statement returns as
  `scalardb_native_ok = false` and the driver treats as the decoupled form's unstamped row.

A local TPC-C before the fused form ran Delivery's prefix writes one key at a time (4.46x); the
fused form is for that. `timeoutMidWrite` now expects one statement for the inline tables on
PostgreSQL as for the decoupled ones. The CAS of neither inline form had a test: mutated away, the suite
passed, as the anchor is read in the statement that writes and moves only while it waits on the
row's lock. `committedWhileItWaits` makes that happen (the row read first, another writer committing
it while the write waits) and fails with either CAS removed; every layout and path aborts. Not read
first, the inline slim write misses the moved row and the fused statement writes on the committed
one, as the vendor's would, while the decoupled slim write aborts. Next, the cloud: both layouts on the same machines
(`BB_LAYOUTS="decoupled inline"`).

### 2026-10-26: the seventh run, and an inline INSERT without a column list

Run `20261025-2251`, the layouts side by side (benchbase-postgresql-aws.md): TPC-C 0.82 decoupled,
0.85 inline, +1.75 then +1.44 ms a transaction. YCSB's inline run is void: its InsertRecord,
`INSERT INTO usertable VALUES (...)`, was refused (0AN01). Such an INSERT now gets the data
table's first columns in its own order, as many as a row has values, when they are all the user's,
and is planned as if it listed them; a row reaching a metadata column is still refused.
`InlineIntegrationTest` covers both on the five engines. Next: YCSB again on both layouts.

### 2026-10-26: the vendor's statements on a table the transaction found clear

The user's framing: at a long round trip the driver is within 20% of the vendor, at a short one
over 30%, and the target is 10%. What the driver adds is per statement, not per round trip, so
it shows most where the round trip is short. Local baseline (laptop Docker, round trip 183 µs,
inline layout, 60 s): TPC-C (c)/(a) 0.83, +1.19 ms a transaction; server CPU +0.93 ms a commit,
client CPU +0.28 ms; the stock lock's in-flight check 15.7 µs ten times a NewOrder, the item read
13.0 against 7.1, the merged stock update 225 µs against ten plain updates' 81, Delivery's
`order_line` update 145 against 44, StockLevel 1.12 + 0.65 ms of planning against 0.82 + 0.12.

The observation behind the change: at SNAPSHOT and SERIALIZABLE on PostgreSQL every statement of a
transaction reads one snapshot, so a table with no row in flight in it at one statement has none
at any later one, and on such a table every check the driver adds is vacuous. A transaction now
remembers each table it found clear (`Router.passed`, cleared at the transaction's end, nothing in
autocommit), and runs on it the vendor's statements (design 4.2 "A table found clear", 4.3 "The
vendor's statement with the stamp"):

1. A plain read's state column probes the in-flight index once per statement before the per-row
   set: `CASE WHEN NOT EXISTS (in flight) THEN 0 WHEN key IN (in flight) THEN 1 END`; a 0 marks
   the table clear as the rows are read (`AnchoredHandler`). The hashed set is never built when
   the probe finds nothing.
2. A write by any predicate, once the table is clear, is the user's UPDATE or DELETE with the
   stamp: inline, the stamp in its SET list (a DELETE needs none); decoupled, the DML returning
   its keys and a second CTE stamping their metadata rows (an UPDATE on a created table, a DELETE
   there, an upsert on an imported one, which also writes a missing row and a tombstone). Before
   the table is known clear the same statement carries the gate `AND NOT EXISTS (in flight)`;
   nothing written asks the coarse check, and the slim and fused statements take over when it
   finds a row. `KeyedPlan.applyFast`, `Writes.fastKeyed`, `metaStamp`.
3. A batch's entries of such a write, merged: the same statement joined to a VALUES list of the
   entries, returning each entry's number (`fastMerged`, `mergedFast`); an entry without its
   number, or a stamp short, undoes the batch's savepoint and runs the entries as before.
4. A locking read on a clear table is the statement as written on the data table (marked with a
   leading comment, since a text equal to the application's own prepared statement makes
   pgjdbc's statement cache drop the server-side plan at every call, which cost 21.6 µs of
   planning per lock in the first measurement); on a table not yet clear, after the coarse check
   in one round trip, which marks it (`LockPlan.applyPlain`).
5. A read on clear tables, any shape, is the statement as written on the data tables
   (`Route.plainSql`, `withPlain`), run on a second vendor statement the handler keeps beside the
   rewritten one, settings and parameters replayed as it becomes the target
   (`StatementHandler.switchTo`); not for an inline table read whole. A read's pending per-table
   checks run as one statement (`Router.check`).
6. The write's `tx_id` comes from a per-thread `SecureRandom` DRBG, seeded once from the system's
   source (`Writes.freshTxId`): `UUID.randomUUID` shares one generator that reads that source
   under a lock on every call. (A `SplittableRandom` was tried first; the id needs uniqueness,
   not secrecy, but a DRBG costs the same and leaves no question.)

Local TPC-C, inline, 60 s, the same session's (a) each time:

| build | (c)/(a) | added per transaction | server CPU per commit (a → c) | notes |
|---|---|---|---|---|
| baseline `49900d5` | 0.83 | +1.19 ms | 1.26 → 2.19 ms | |
| 1, 2, 4, 6 | 0.88 | +0.82 ms | 1.28 → 2.05 ms | the lock's plan dropped per call (fixed in 4) |
| + 3 | 0.91 | +0.60 ms | 1.27 → 1.69 ms | merged stock update 87 µs against 81 |
| + 5, 6 | 0.86 | +0.97 ms | 1.25 → 1.63 ms | a `SET` of the workers each way at every switch, 1.5 a transaction |
| + 5 without the SET | **0.96** | **+0.26 ms** | 1.31 → 1.53 ms | packets per commit equal (19.7 against 19.6) |

p95 against (a) at the last row: Delivery 1.01, NewOrder 1.04, Payment 1.08, StockLevel 1.19,
OrderStatus 1.23; p50 added: NewOrder +412 µs, Payment +164, OrderStatus +148, StockLevel +385,
Delivery +943. Per statement, (c) against (a): the stock lock 15.4 against 12.9 µs, the item read
as written 9.3 against 7.1, the merged stock update 87 against ten updates' 81, StockLevel's join
as written 881 + 102 of planning against 818 + 124, Delivery's `order_line` update 56 against 44,
the customer updates 47 and 30 against 36 and 23, the district update 31 against 15, the oorder
insert 51 against 36, the new_order insert 35 against 25, the customer read by last name 69
against 42 (first touch, the rewritten text). Client CPU 0.68 ms a commit against 0.48.

What the first-touch statements still cost is the probe and the rewritten shape (the item read
10.2 against 7.1 rewritten, 9.3 as written); what the writes cost is the inline row's width
(before images beside the columns, WAL 16 KB a commit against 9) and the stamp's five columns.

The same build on the other workloads, locally, 60 s: YCSB inline (c)/(a) **0.98**, p95 against
(a) Insert 1.04, Update 1.01, read-modify-write 1.02, Read 1.05, Scan 0.99, +9 µs an operation;
TPC-C decoupled **0.92**, +0.53 ms a transaction, p95 Delivery 1.21, NewOrder 1.08, OrderStatus
1.24, Payment 1.11, StockLevel 1.02: the decoupled layout's second row per write, about 17 a
transaction, is what is left there. Integration suite (PostgreSQL), unit tests, SpotBugs and
Spotless pass; `WritesTest` covers the fast texts.

Left: the first-touch reads' rewritten shape (OrderStatus +23% at p95, three reads of three
tables each touched once); the inline INSERT's CTE and savepoint (about 15 µs); the merged
INSERT (about 90 µs a NewOrder over ten plain inserts); the inline row's width. On AWS the
ratios will be lower than here at a shorter round trip and higher at a longer one, as before;
the time added per transaction is the number to read.

### 2026-10-26, late: READ COMMITTED, the default level, given the same treatment

The user's point: a fast path that serves SNAPSHOT and SERIALIZABLE alone leaves the default level,
READ COMMITTED, as the slow one, and ScalarDB's READ COMMITTED does less than its SNAPSHOT, so the
driver's should be the cheapest level. Every run of this todo had been at REPEATABLE READ, the
harness's default, so the level had never been measured. Measured, same laptop, inline layout,
60 s, the build of the entry above:

| | (a) | (c) | (c)/(a) | packets per commit (a → c) |
|---|---|---|---|---|
| TPC-C at REPEATABLE READ | 670/s | 642/s | 0.96 | 19.6 → 19.7 |
| TPC-C at READ COMMITTED | 703/s | 299/s | **0.43** | 19.5 → 44.3 |
| YCSB at REPEATABLE READ | 3412/s | 3343/s | 0.98 | 11.0 → 11.2 |
| YCSB at READ COMMITTED | 3340/s | 2381/s | **0.71** | 11.2 → 12.9 |

TPC-C p95 against (a) at READ COMMITTED: NewOrder 3.03, StockLevel 2.16, OrderStatus 1.67,
Delivery 1.56, Payment 1.28; YCSB read-modify-write 1.83, Scan 1.47. Where it went, per statement
(`pgss-sum.py`, `runs/rc-tpcc-inline`): the locking read is four statements over several round
trips, the lock with two anchor subqueries per row (28.5 µs against the vendor's 12.2), the
versions read again after it (9.6), the before-image check twice (2.6 each), forty statements a
NewOrder for the vendor's ten; every read looks its anchors up by two subqueries per row (the item
read 17.3 against 6.6, the customer by last name 91 against 38); the keyed writes run the keys-CTE
form (29 to 63 µs against 21 to 33). The earlier write-side work (merged batches, the slim and
fused forms, the inline forms) was there at every level; what was bound to SNAPSHOT and
SERIALIZABLE was reads without anchors, the fused locking read of todo 328, and the entry above.

Nothing can be remembered across statements at this level, each having its own snapshot; but the
same idea holds within one statement, and PostgreSQL re-checks a row's own columns on its newest
version after a lock wait (EvalPlanQual), which a subquery's snapshot does not follow. Hence, on
PostgreSQL (design 4.2, 4.3):

1. **Reads** carry their anchors as columns of each copy instead of two lookups per row: an inline
   table's row gives `tx_id` and `tx_version` itself, and its committed copy is kept by
   `COALESCE(tx_state, 3) = 3` on the row rather than a probe of the in-flight index per row; a
   decoupled table's by a left join to its metadata row; the in-flight copy gives its before
   image's. A `*` over such a copy is spelled out as the table's columns (`SplitRead`,
   `SqlAst.emitSplit`, `Router.versions`). Both layouts.
2. **A locking read by key columns** (predicate and ORDER BY on key columns only, no SKIP LOCKED,
   inline layout, no wait configured) is one statement: the statement as written over the rows as
   they are, each with its key, version and state. The lock's re-read gives the newest version, the
   anchor the level wants; a row in flight aborts, or fails as NOWAIT fails, since the lock on it
   must not be held while waiting (`LockPlan.applyFastRc`).
3. **Writes keep their forms.** A write at this level CASes on the version the statement's own
   snapshot read of a key the transaction never read before (4.3 step 1), which is what the slim
   form's keys CTE gives, and ScalarDB's own prepare does the same. The vendor's statement with
   the stamp and the row's own state, tried first, wrote such a key at the version the lock
   re-read instead, as PostgreSQL's READ COMMITTED does: a row changed under the lock by another
   writer and still matching was written rather than aborted (`InlineIntegrationTest`, todo 292,
   caught it). An exact form needs the snapshot's version and a count beside the write, which is
   the slim form again, so it was taken out. The slim write costs about 9 µs more than the
   SNAPSHOT fast form per statement, under 1% of a TPC-C transaction.

Measured, same laptop, inline layout, 60 s, the two levels back to back in one machine state (a
busier one than the entry above's: the vendor itself ran at 600/s rather than 670-700):

| | (a) | (c) | (c)/(a) | added per transaction | packets per commit (a → c) | server CPU per commit (a → c) |
|---|---|---|---|---|---|---|
| TPC-C at REPEATABLE READ | 601/s | 548/s | 0.91 | +0.64 ms | 19.4 → 19.5 | 1.68 → 2.10 ms |
| TPC-C at READ COMMITTED, before | 703/s | 299/s | 0.43 | +7.7 ms | 19.5 → 44.3 | 1.20 → 3.37 ms |
| TPC-C at READ COMMITTED, after | 591/s | 507/s | **0.86** | **+1.11 ms** | 19.4 → 19.8 | 1.42 → 2.20 ms |
| YCSB at READ COMMITTED, before | 3340/s | 2381/s | 0.71 | +121 µs | 11.2 → 12.9 | |
| YCSB at READ COMMITTED, after | 2671/s | 2833/s | 1.06 | | 11.2 → 11.6 | 0.44 → 0.42 ms |

The YCSB ratio above 1 is the machine's load moving between the two runs; read it as parity, each
operation's p95 within 15% of the vendor's and below it in that run. TPC-C p95 against (a) at READ
COMMITTED, after: NewOrder 1.12 (3.03 before), Payment 1.16, OrderStatus 1.17, Delivery 1.37,
StockLevel 1.50. Per statement, (c) against (a) in the same state: the stock lock is one statement,
25.5 µs against the vendor's 16.5 (the four of before: 28.5 + 9.6 + 2.6 + 2.6 over several round
trips); the item read 17.5 against 9.0, unchanged by its own version columns.

Why the read still costs: `EXPLAIN ANALYZE` on the benchmark tables shows the two-arm read's plan
as an Append over the committed arm's index scan with its state filter and the in-flight arm's
scan of the in-flight partial index, which is empty, so the arm's own cost is nothing; the
difference to the vendor is the executor's setup of the extra nodes (Append, a subquery scan, the
filter) and the row's three extra columns. A one-time filter on the in-flight arm (`AND EXISTS
(SELECT 1 FROM T WHERE tx_state IN (1, 2))`, an InitPlan) was tried and taken out: it replaces one
scan of the empty index with another. The lock's derived table is pulled up into the vendor's own
plan (LockRows over the primary key scan); its extra cost is the five columns and the wider row.

Left at READ COMMITTED, in order of size: Delivery's `order_line` write beyond the key, the fused
form (169 µs against 52 for ten rows, 0.15 ms a commit); the two-arm shape of a key read on an
inline table, which one scan with `CASE WHEN tx_state IN (1, 2) THEN before_x ELSE x END` per
column could replace, the key columns needing none, so the index stays (about 7 µs a read, 0.13 ms
a commit over 21 reads); the slim write's keys CTE (about 9 µs a write).

**Run (d), the layout's cost alone** (`BenchBaseBenchmark`, runs `a,c,d`): the vendor's own driver
on the tables of (c), imported into ScalarDB and set up, bypassing the protocol: a measurement
only, sound because no ScalarDB transaction runs beside the benchmark, since the vendor's driver
stamps no metadata and would read rows in flight. I had written
that the inline row's width, WAL 17 KB a commit against 7, was ScalarDB's layout and larger than
anything left; (d) says otherwise. Local, inline, REPEATABLE READ, 60 s, one machine state:

| TPC-C | (a) vendor, plain tables | (d) vendor, ScalarDB's tables | (c) the driver |
|---|---|---|---|
| throughput/s, vs (a) | 658, 1.00 | 662, **1.01** | 608, 0.92 |
| WAL KB per commit | 10.1 | 11.7 | 15.8 |
| server CPU ms per commit | 1.28 | 1.43 | 1.62 |
| statement time per kind, (d) and (c) against (a) | | reads 1.29, inserts 1.22, updates 1.37 | |

YCSB: (d) 0.98, (c) 0.98. The vendor on ScalarDB's tables pays 20 to 37% more server time per
statement for the wider tuples, and nothing in throughput at four terminals, the run being bound by
round trips; its rows' metadata columns stay NULL, since no ScalarDB transaction touches them. The
driver's rows are wider because the driver writes the stamp into every row, `tx_id` a 36-character
text among five columns, which is the protocol's cost, not the layout's: WAL 15.8 against 11.7,
and (c) against (d) is +0.54 ms a transaction, 0.92. Per statement at REPEATABLE READ, (c) against
(d): the stock lock 18.5 against 17.3 µs, the item read 10.2 against 8.6 (the first-touch probe),
the oorder insert 52 against 45, the customer update 56 against 51, Delivery's order_line update
64.5 against 60.9, the merged order_line insert of 15 rows 319 against 15 × 23, the customer read
by last name 82 against 68. Of the stamp, only `tx_prepared_at` and `tx_committed_at` (16 bytes)
are open to question; `tx_id`, `tx_state` and `tx_version` are what ScalarDB reads. The target
remains (c) against (a); (d) is a diagnostic for attributing the gap, run when that question
comes up, not a reference.

The integration suite: it passes in full against an external PostgreSQL (a throwaway container,
`-Dnativejdbc.postgresql.url`). Under Testcontainers four passthrough tests (`RoutingIntegrationTest`
three, `UnparsedIntegrationTest` one) failed in three runs of four with "names p, which ScalarDB
manages", and pass alone; the word `p` is a ScalarDB table of `WriteIntegrationTest.anchorsFromJoins`
(`aj.p`), which that test now drops at its end, though ScalarDB's drop leaves the imported table's
metadata behind, and the same leftover was present in the runs that passed. Not resolved; it does
not touch the changed paths.

### 2026-10-26, night: the READ COMMITTED items, measured before building

Each candidate shape was timed on the server itself before any code: pgbench in prepared mode, one
client, on the TPC-C tables in the inline shape, the mean execution time from `pg_stat_statements`
(`scratchpad/pgb2`). Against the vendor's statement, in one machine state:

| statement, 1 client | vendor | the driver's form | candidates |
|---|---|---|---|
| item read by key | 8.3 µs | two arms, own versions (RC): 8.6 | one scan with CASE per column: 8.5; the RR probe form: 7.9 |
| the same, 4 clients | 8.0 | 9.1 | one scan: 9.6; probe: 9.2 |
| customer read by key, RR first touch | 13.5 | probe form: 9.5 | |
| district update by key | 14.1 | slim (RC): 22.8; RR fast: 20.5 | slim with the keys joined: 29.8 |
| order_line update by key prefix, 10 rows | 44 to 50 | fused inline (RC): 101 to 111 | keys from the table, stamp joined, written by LEFT JOIN: 75 to 90 |

So the two-arm read costs within a microsecond of the vendor's in isolation and under four
clients, the one-scan form would gain nothing, and the slim write is already near its floor: the
items I had listed from the benchmark's per-statement times were mostly not real. What the local
benchmark shows as a per-statement cost in (c) is the laptop: the client runs on the same eight
cores as the server, and the driver's client takes 0.86 ms of CPU a commit against the vendor's
0.53 (JFR, `jfr-cpu.py`), at both levels alike, so even the plain item text the driver sends at
REPEATABLE READ measured 10.2 µs against 8.6 in (a). Per-statement server times from the laptop's
runs are not to be read as statement costs; the AWS runs, with the client on another machine, are.

Built: the inline fused write beyond the key reads its keys from the table itself (the committed
rows with their versions and the rows in flight whose before image matches, which close the gate)
rather than through the view and a join back, takes the stamp from its CTE by a join rather than
three lookups per row, keeps the gate as one condition of the DML, and reads what the DML wrote by
a left join rather than two EXISTS per key: 101 to 90 µs for Delivery's ten rows, the same result
rows as before (`fusedKeyedInline`). The write's `tx_id` now comes from a kilobyte of the DRBG's
output per thread, sliced per statement (`freshTxId`), which was 6% of the driver's client CPU.

The client's CPU is the driver's largest own cost at both levels, 0.33 ms a commit here: by JFR,
80% of it inclusive under the dynamic proxies' `invoke` and `forward` (reflection on every JDBC
call), binding 8%, routing 10% (`Router.route`, `decide`, `current`), the anchored result handler
8% at READ COMMITTED. A hand-written delegation in place of `java.lang.reflect.Proxy` would be the
structural change; not taken here.

### 2026-10-26, later: the AWS matrix's first cell, READ COMMITTED on the decoupled layout

The 6e session's first cell on AWS (cluster placement group, TPC-C, decoupled, READ COMMITTED):
vendor 1,749 and 1,769/s, driver 658 and 676/s, 0.38, about 3.7 ms added per transaction. Not the
fixes failing at a short round trip and not a setup problem: READ COMMITTED on the decoupled layout
is the one cell the entry of 2026-10-26 did not cover, and it was never measured locally (READ
COMMITTED was measured on inline, decoupled only at REPEATABLE READ). The one-statement locking
read at that level was gated to the inline layout, since a decoupled table's state is in another
row the lock's re-read does not reach, so a decoupled table at READ COMMITTED still took the steps:
the before-image check, a savepoint, the lock, the check again, the versions under the lock, the
release, six round trips per locking read; NewOrder locks ten stock rows, sixty round trips, about
6 ms at a 100 µs round trip, which over the mix is the 3.7 ms measured. The signature in a run's
server statistics: SAVEPOINT and RELEASE near ten a NewOrder, the before-image statement near
twenty.

The fix: on a decoupled table at READ COMMITTED, with no wait configured and a predicate and
ORDER BY on key columns, the lock and then a read of the matched rows' keys, versions and states
from the data table joined to its metadata table, two statements in one round trip, the second in
a snapshot taken after the lock (design 4.3). pgjdbc keeps the first result set open and
scrollable across `getMoreResults(KEEP_CURRENT_RESULT)` (checked on 42.7.12), so the lock's rows
go to the application and the versions to the anchors from one execution. A row in flight among
the matched rows with a before image, or among the locked rows, aborts, as the steps' two checks
would; a locked key the second read does not show falls back to the steps.

Measured locally, TPC-C, decoupled, READ COMMITTED, 60 s, the previous commit run from an exported
copy of its tree and the fix from the working tree, minutes apart:

| | (a) | (c) | (c)/(a) | added per transaction | packets per commit (a → c) | NewOrder p95 vs (a) |
|---|---|---|---|---|---|---|
| before (`3486935`) | 624/s | 272/s | 0.44 | +8.3 ms | 19.5 → 44.3 | 2.70 |
| after | 688/s | 566/s | **0.82** | **+1.25 ms** | 19.5 → 19.9 | 1.31 |

The integration suite passes (92 tests). What is left on this layout at this level is the second
row per write and the slim write's keys CTE, as on inline, plus StockLevel's join (1.84 at p95).

### 2026-10-26, evening: the AWS matrix, three levels, both layouts, two round trips

Run `20261026-1433` from `6c19378`: PostgreSQL, (a) and (c), TPC-C and YCSB, both layouts, READ
COMMITTED, SNAPSHOT and SERIALIZABLE, at a cluster placement group's ~70 µs round trip and again on
the same instances with 250 µs added by netem (~330 µs). Tables in
`docs/benchmarks/benchbase-postgresql-aws.md`, "Ninth run". The bench-env knobs it needed:
`placement_strategy` (`49900d5`), `NET_DELAYS` and `BB_ISOLATIONS` (`24b80ee`, `6c19378`), and
`SOURCE=<commit>` for run.sh (`84df1e3`), since this checkout's working tree carries other sessions'
edits.

TPC-C (c)/(a), added per transaction, fast then slow:

| | decoupled | inline |
|---|---|---|
| READ COMMITTED | 0.38 (+3.66 ms), 0.42 (+10.1 ms) | 0.78 (+0.65 ms), 0.89 (+0.87 ms) |
| SNAPSHOT | 0.78 (+0.61 ms), 0.93 (+0.60 ms) | 0.87 (+0.34 ms), 0.95 (+0.41 ms) |
| SERIALIZABLE | 0.79 (+0.67 ms), 0.85 (+1.39 ms; goodput 0.75) | 0.93, 1.07 (goodput 1.05, 1.15) |

YCSB, slow: 0.56, 0.94; 0.92, 0.95; 0.92, 0.97 (fast 0.33, 0.86; 0.85, 0.88; 0.86, 0.89).

Open after it, for the driver sessions:

- Decoupled READ COMMITTED: `816f506`'s run follows (decoupled, READ COMMITTED, both round trips,
  from that commit alone).
- Decoupled SERIALIZABLE at the long round trip: NewOrder's serialization failures doubled against
  the vendor's (90,394 retries against 45,735), goodput 0.75; inline had far fewer than the vendor.
- `SHOW max_parallel_workers_per_gather` at SERIALIZABLE on decoupled tables, about 0.76 a TPC-C
  commit (111,003 in a fast repetition, 37,500 slow), a round trip each: `Router.switchWorkers`
  reads the setting again whenever `workersOff` is unknown, as after a rollback.
- With round trips equal to the vendor's, what is left is server time per statement: e.g. the
  inline write beyond the key, 200 µs a call (28.2 s of 98.2 s in a fast READ COMMITTED repetition).

### 2026-10-26, night: no SHOW after a rollback; the decoupled SERIALIZABLE retries

From the ninth run's two findings for the driver side (above).

**The SHOW.** `Router.switchWorkers` read `max_parallel_workers_per_gather` again whenever its
state was unknown, and `rolledBack()` made it unknown after every rollback, since a rollback undoes a
SET made in its transaction. Per repetition of the fast decoupled SERIALIZABLE cell the count of the
SHOW equalled the count of rejected transactions (45,662 / 45,324; 111,003 / 111,425; 27,136 /
26,926): one round trip per retry, 2 µs on the server. At the slow round trip (37,500 in a
repetition of 13,800 commits, 330 µs each) it is about 0.9 ms of the 1.39 ms that cell adds per
transaction. The router now keeps the value the transaction began with (`workersOffAtStart`, taken
at every transaction end and at a switch made in autocommit) and restores it on a rollback of the
whole transaction; a rollback to a savepoint after the driver's own SET in that transaction still
makes it unknown, and an unknown state with a known session value is set directly, one round trip,
instead of read and then set. `ReadIntegrationTest` asserts one SET and no SHOW after the rollback
through a counting proxy of the connection. Verified in a local TPC-C run (decoupled,
SERIALIZABLE, 60 s, from a copy of the tree with these hunks alone on `cb59f57`): 0 SHOW in the
server statistics against 42,010 rejected transactions (before: one each).

**The retries.** The lock graphs under SSI are the same for the vendor and the driver, so the
doubling is not a form's doing. PostgreSQL seq-scans a one-page table whatever the predicate (checked
on one-page copies of `district` and `district_tx_metadata`: `Seq Scan` for the keyed `FOR UPDATE`
read and for the in-flight probe), and a seq scan at SERIALIZABLE takes a relation-level SIREAD lock,
so on TPC-C's warehouse and district tables every NewOrder and Payment conflicts with every other for
the vendor too; the driver's probes of the one-page metadata tables add locks on the same pairs, and
its probes of the larger ones scan the empty partial index, which it never writes to. The per-call
times agree (vendor's district `FOR UPDATE` 12.8 µs, the driver's 10.5 µs plus the probe's 12.1 µs,
against 2.9 µs for the index-only probe of `stock_tx_metadata`). What differs is transaction length:
the driver's decoupled transaction holds its locks longer (active + idle per commit 10.45 ms against
8.09 at the slow round trip), the round trip per retry above lengthening it further, and in a regime
where every overlapping pair conflicts the abort rate climbs steeply with length. The fix above is
the lever the driver has; the rest of the length is the stamp's second row per write (WAL 24.7 KB a
commit against 9.0). To be measured again on AWS: decoupled SERIALIZABLE, both round trips, from
this commit.

Found on the way, not changed here: a clean SpotBugs run of `cb59f57` fails in `Writes.java` at
`b0b46ef`'s `Run.prepare(String, boolean, String[])` (OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE:
the statement it returns) and reports the suppression on `applyFastRcDecoupled` as unnecessary;
reported to the Oracle session.

### 2026-10-26, night: `816f506` on AWS, decoupled READ COMMITTED

Run `20261026-2036`, `816f506` alone (`SOURCE=`), decoupled, READ COMMITTED, both round trips;
tables in `docs/benchmarks/benchbase-postgresql-aws.md`, "Tenth run". TPC-C 0.38 → 0.66 fast
(+3.66 → +1.19 ms a transaction), 0.42 → 0.83 slow (+10.1 → +1.48 ms); YCSB 0.33 → 0.66 fast
(+825 → +214 µs), 0.56 → 0.81 slow. Packets per TPC-C commit 44.5 → 20.0 (vendor 19.7). Against
SNAPSHOT decoupled (+0.61 ms, +73 µs) the rest is server time: 970 against 613 µs of statements a
commit. The stock lock is 24.7 + 6.1 µs a row against 10 (its text carries correlated subqueries
for the anchor columns, which the second statement reads again); Delivery's beyond-key write 244
against 143 µs; the keys-CTE keyed writes about 125 µs a commit where SNAPSHOT sends the vendor's
statements; the item read 53 against 21, the merged batch 149 against 93, StockLevel 77 against 40.

### 2026-10-26, late: decoupled READ COMMITTED, what is left and the forms for it (todo 336)

The tenth run (`816f506` alone, run `20261026-2036`): decoupled READ COMMITTED TPC-C 0.38 → 0.66
fast (+3.66 → +1.19 ms), 0.42 → 0.83 slow (+10.1 → +1.48 ms); YCSB fast 0.33 → 0.66, slow 0.56 →
0.81; packets a commit 44.5 → 20.0 (vendor 19.7). Against decoupled SNAPSHOT (+0.61 ms fast) the
rest is server time per statement, 970 against 613 µs a commit: the lock's unused version lookups
(24.7 against 10 µs a call), the keyed writes' slim keys-CTE form (42.6 to 54.9 against the
clear-table statement's 29.4 to 43.4), Delivery's fused write beyond the key (239 against 143),
the merged batch (201 against 120 for five). Candidate texts timed on the server by
`pg_stat_statements` minimum and mean (todo 336's table): reshaping the keys-CTE statements gains
nothing, the clear-table statement with the CAS folded into its stamp is within 1 to 2 µs of the
floor. Designed in `docs/design.md` 4.3 as "The keyed write at READ COMMITTED on a decoupled
table" and "The write beyond the key at READ COMMITTED on a decoupled table", the lock's paragraph
amended; the implementing brief is todo 336.
