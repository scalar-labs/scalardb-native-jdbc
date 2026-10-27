# BenchBase on AWS, Oracle 23ai, tier A (2026-10-27)

Run `20261027-0133` of `bench-env/run.sh` (`TFVARS=bench-ora.tfvars SOURCE=71e53d5 BENCHBASE=1
REPS=2 BB_ISOLATIONS="READ_COMMITTED REPEATABLE_READ" NET_DELAYS="0 250"`), the first BenchBase
run on Oracle (todo 329). Both layouts, two levels, two round trips: 8 cells, none failed.

- **Database:** `m6id.2xlarge` (8 vCPU, 32 GB), Oracle AI Database 26ai Enterprise Edition
  23.26.1.0.0 in Oracle's container image with host networking, `_disable_last_successful_login_time`
  set (the logon's own update otherwise raises ORA-08177 under SERIALIZABLE).
- **Client:** `m6i.4xlarge`, cluster placement group; JDK 25, BenchBase `33c00473`. Both runs log in
  as `BBRUN` (DBA, a logon trigger sets `CURRENT_SCHEMA` to the benchmark's schema).
- **Workloads:** TPC-C scale 4 (weights 45,43,4,4,4) and YCSB (weights 50,5,15,10,0,20), 4
  terminals, 120 s a run, 2 repetitions (medians), assertions off.
- **Levels:** READ COMMITTED (both drivers READ COMMITTED) and SNAPSHOT (the driver's
  REPEATABLE_READ; the vendor at SERIALIZABLE, Oracle's snapshot isolation). The driver refuses
  SERIALIZABLE on Oracle, so there is no third level.
- **Round trips:** `SELECT 1 FROM dual` 80 µs median fast, 340 µs with 250 µs of netem (TCP connect
  113 → 375 µs).
- **Server stats:** `V$SYSSTAT` (user commits, redo, packets), `V$SYS_TIME_MODEL` (DB time as the
  active time), `V$OSSTAT` (the host's busy CPU), `V$SQL` and `V$SYSTEM_EVENT` deltas per run.
  Commits are Oracle's "user commits": transactions that wrote, so read-only ones are not counted.
- About 2 h 50 min of instances plus the database's first start, about $4.

## Summary

TPC-C, (c)/(a) throughput, and the time each transaction adds (4/tp(c) − 4/tp(a)):

| level, layout | fast | slow |
|---|---|---|
| READ COMMITTED, decoupled | 0.41 (+4.58 ms) | 0.58 (+6.04 ms) |
| READ COMMITTED, inline | 0.47 (+3.59 ms) | 0.65 (+4.50 ms) |
| SNAPSHOT, decoupled | 0.47 (+3.71 ms) | 0.61 (+5.07 ms) |
| SNAPSHOT, inline | **0.69** (+1.56 ms) | **0.74** (+2.83 ms) |

The vendor's TPC-C rate held across the cells: 1168–1280/s fast, 476–499/s slow.

YCSB, (c)/(a):

| level, layout | fast | slow |
|---|---|---|
| READ COMMITTED, decoupled | 0.20 | 0.70 |
| READ COMMITTED, inline | 0.62 | 1.09 |
| SNAPSHOT, decoupled | 0.86 | 0.87 |
| SNAPSHOT, inline | 0.59 | 0.85 |

The vendor's YCSB rate did not hold: 6973/s in the first cell, then 4485, 3278, 2905 fast, with its
server CPU per commit 0.93 → 1.88 → 2.70 → 3.16 ms for the same workload; the slow cells 1792–2227/s.
The YCSB ratios are therefore indicative only, apart from the lock waits below, which are the
driver's own.

What the server counted per commit, TPC-C, fast:

| | active ms | redo KB | server CPU ms | packets |
|---|---|---|---|---|
| vendor (a), the four cells | 1.65–2.26 | 7.8–8.1 | 1.93–2.07 | 21.4–22.0 |
| READ COMMITTED, decoupled | 4.68 | 23.7 | 4.99 | 26.9 |
| READ COMMITTED, inline | 4.17 | 13.8 | 4.02 | 26.9 |
| SNAPSHOT, decoupled | 4.71 | 22.3 | 4.62 | 27.2 |
| SNAPSHOT, inline | 3.08 | 15.1 | 3.38 | 29.7 |

## Findings

**A caveat on the per-statement figures (`V$SQL`)**, found by the design session: the driver's
deltas carry calls from earlier runs. The SNAPSHOT decoupled cell's first run lists the vendor's
` SELECT S_QUANTITY ... FOR UPDATE` with the vendor run's own count to the call (614,539), and in
its second run the warehouse block and the item read show about twice their first run's calls. A
cursor seems to leave `V$SQL` between the start's reading and the end's and come back with its
counters. The host's figures (CPU, redo, commits, packets, DB time) are not affected; the
statement CPU below is an upper bound, and the stock read counted 10.4 times a commit is this
artifact (the driver's locks are one gated and nine plain a NewOrder). The harness now flushes the
shared pool before each run's start reading.

1. **On Oracle, the TPC-C overhead is the server's CPU, not round trips.** Unlike PostgreSQL, where
   the extra round trips explained most of the gap, here the database host spends 1.3–3.1 ms more
   CPU per commit than for the vendor's statements, and the active time grows by the same amount.
   On the fast network the added CPU is most of the added time (SNAPSHOT decoupled: +2.6 ms of
   CPU against +3.7 ms a transaction; 4 terminals on 8 vCPU, so not saturated). The extra 5–8
   packets per commit account for the rest, and for most of the growth on the slow network.
2. **Where the CPU goes (V$SQL, SNAPSHOT decoupled, a commit):** the statements' own CPU is
   about 1.8 ms against the vendor's 0.41 ms. NewOrder's order lines dominate: per stock row
   the vendor runs a locking read and an update, the driver the locking read, the
   `scalardb_native_lock` read, the versions join, the update, the `STOCK_tx_metadata` update,
   and per order line the `ORDER_LINE_tx_metadata` insert besides the row's. The two metadata
   writes cost about 0.23 ms of CPU a commit together, each as much as the vendor's two heaviest
   statements (the stock and item reads) combined. The batched PL/SQL write blocks come in many
   texts (one per row count: `l_rows_1`, `l_rows_2`, ...), each called a few hundredths of a time
   a commit: 80 statement texts against the vendor's 51. Inline halves the statements' CPU (0.84 ms) and redo (15 KB against 22–24).
3. **The host's CPU outside the statements also grows:** about 1.65 ms a commit for the vendor,
   2.5–3.1 ms for the driver. Every call is a parse call on both sides (BenchBase keeps no
   statement cache, so each `prepareStatement` reaches the server as a soft parse), and the
   driver's top-level calls are about as many (21–36 a commit against 21–22), so the difference
   is mostly the commit's work: redo is 2–3 times the vendor's, and the PL/SQL blocks run their
   nested statements inside the call.
4. **Decoupled READ COMMITTED waits on row locks in YCSB.** In its fast cell, 813 `enq: TX - row
   lock contention` waits took 316 s of the run's 480 terminal-seconds (about 390 ms each), so
   active time is 6.99 ms a commit against 1.74 ms of CPU, and the ratio 0.20. Both repetitions
   and the slow cell show it (810 waits, 339 s; 783 waits, 176 s); the vendor's runs and every
   other layout and level wait under 1 s, except SNAPSHOT inline fast (585 waits, 45 s, with 17 s
   of buffer busy waits), the cell that also reads 0.59. The waits fall on the writes: the lock
   read (3.2 ms elapsed a commit against 0.1 ms CPU), the write block and the metadata update.
   They are deadlocks: each of those runs logs about 25 `ORA-00060: deadlock detected` (13–14 a
   run slow), no other cell any. Oracle breaks a deadlock only after about 3 s, and the other
   terminals queue behind the two sessions meanwhile. One side is ReadModifyWriteRecord's locking
   read, `SELECT ... FROM (usertable t LEFT JOIN USERTABLE_tx_metadata m) ... FOR UPDATE`, which
   locks the row in both tables; the other is the write block (versions read, `UPDATE usertable`,
   then the `USERTABLE_tx_metadata` update), which locks the data row first and the metadata row
   second. Two transactions on one key can take the two rows in opposite orders. TPC-C at READ
   COMMITTED shows no such waits (5 or fewer); its locking reads are `scalardb_native_lock`
   statements on the data table alone.
5. **SNAPSHOT inline is the best cell, 0.69 fast and 0.74 slow,** well short of 0.9: even with the
   metadata in the row, its server CPU is 1.6 times the vendor's.

## Reports

`bench-env/results/20261027-0133/` (git-ignored): `a/bb[-inline][-rc]-d{0,250}-benchbase-oracle.md`
and each cell's runs (`*-runs.tgz`, with each run's `server.txt`: `V$SQL` per statement and the wait
events).

## Second run: the statement cache on both sides, the deadlock fix (2026-10-27)

Run `20261027-0858` (`SOURCE=1219aac`: the decoupled READ COMMITTED lock `FOR UPDATE OF` the data
row, `d56e87d`, and the shared pool flushed before each run's counters; `BB_JVM_ARGS=
-Doracle.jdbc.implicitStatementCacheSize=128`, which reaches the vendor's connections and the
driver's own ojdbc ones alike), fast network only, REPS 2. The READ COMMITTED decoupled cell
failed before its first run: the logon worked while the database was still being created, before
its USERS tablespace existed (ORA-00959 on ScalarDB's quota); the harness now waits for the
tablespace (`0298611`). That cell, the deadlock fix's proof, is still to run.

| (c)/(a), TPC-C / YCSB | first run, no cache | this run, cache 128 |
|---|---|---|
| READ COMMITTED, inline | 0.47 / 0.62 | **0.66 / 0.90** |
| SNAPSHOT, decoupled | 0.47 / 0.86 | **0.64 / 1.04** |
| SNAPSHOT, inline | 0.69 / 0.59 | **0.76 / 0.61** |

The vendor gained too (TPC-C 1168–1206/s → 1446–1604/s), and its YCSB rate held this time
(5234–5422/s; scans 3.7–3.9 ms p50 in every cell), so this run's YCSB ratios can be read.

YCSB medians, µs, vendor → driver:

| | read | update | insert | read-modify-write |
|---|---|---|---|---|
| SNAPSHOT, decoupled | 199 → 237 (+19%) | 341 → 477 (+40%) | 354 → 449 (+27%) | 464 → 601 (+30%) |
| SNAPSHOT, inline | 202 → 227 (+12%) | 343 → 372 (+8%) | 356 → 412 (+16%) | 468 → 1381 |
| READ COMMITTED, inline | 198 → 215 (+9%) | 361 → 441 (+22%) | 380 → 424 (+12%) | 490 → 575 (+17%) |

- The cache took: the vendor's TPC-C parses fell to 250–13,878 for 3.3–3.6 M calls. The driver's
  top-level statements are cached too, but the nested statements of NewOrder's batch blocks still
  parse on every call (the order-line inserts, their metadata inserts, the stock versions read,
  update and metadata update, about 525,000 calls and as many parses each in a run).
- Decoupled update +136 µs, near the +105–120 the container's measurements predicted.
- SNAPSHOT inline YCSB still waits on row locks (1,440 waits, 98 s, and 36 s of buffer busy waits
  in a run; no ORA-00060): its read-modify-write at 1381 µs is the cell's 0.61.
- Server CPU per commit, TPC-C: 3.00 ms decoupled against the vendor's 1.28, 2.12 inline against
  1.26; packets are now equal (22.0 against 21.9; inline 23.5).

## Third run: the deadlock fix, the inline whole-row lock, INSERT ALL, the bulk block (2026-10-27)

Run `20261027-1344` (`SOURCE=2162759`, main with `d56e87d`, `1070d98`, `INSERT ALL` 71277c0…16c9611
and the bulk batch block 2660f5a…15d29a5), the statement cache at 128 on both sides, a client
flight recording of every run (`-XX:StartFlightRecording`, CPU-time samples), fast network,
REPS 2. All four cells ran; about 1 h 30 min of instances.

| (c)/(a), TPC-C / YCSB | first run | second run (cache) | third run |
|---|---|---|---|
| READ COMMITTED, decoupled | 0.41 / 0.20 | (failed at setup) | **0.51 / 0.64** |
| READ COMMITTED, inline | 0.47 / 0.62 | 0.66 / 0.90 | 0.62 / (1.29) |
| SNAPSHOT, decoupled | 0.47 / 0.86 | 0.64 / 1.04 | 0.63 / 0.89 |
| SNAPSHOT, inline | 0.69 / 0.59 | 0.76 / 0.61 | 0.74 / **0.86** |

The vendor's YCSB rate drifted again from cell to cell (6333, 2773, 4748, 4503/s; its scan p50
2.3 to 7.8 ms), so the YCSB ratios are indicative only. The READ COMMITTED inline one is not
usable: the vendor's scans took 7.8 ms there. The single-key medians are the measure.

YCSB p50, µs, vendor → driver:

| | read | update | insert | read-modify-write | scan |
|---|---|---|---|---|---|
| READ COMMITTED, decoupled | 207 → 231 (+12%) | 391 → 525 (+34%) | 420 → 479 (+14%) | 532 → 675 (+27%) | 2306 → 4010 |
| READ COMMITTED, inline | 199 → 228 (+15%) | 383 → 498 (+30%) | 420 → 485 (+15%) | 521 → 643 (+23%) | 7795 → 4411 |
| SNAPSHOT, decoupled | 200 → 242 (+21%) | 357 → 521 (+46%) | 383 → 447 (+17%) | 488 → 648 (+33%) | 4453 → 4821 |
| SNAPSHOT, inline | 203 → 236 (+16%) | 365 → 390 (**+7%**) | 400 → 448 (+12%) | 500 → 522 (**+4%**) | 5509 → 3050 |

- **The deadlock fix holds.** READ COMMITTED decoupled YCSB has no ORA-00060 in either run, and
  its row-lock waits are 0.26–0.28 s a run, against 316 s in the first run. YCSB is 0.20 → 0.64,
  held back now by its scans (1.7 times the vendor's p50, server CPU 1.91 against 1.00 ms a
  commit).
- **The inline whole-row lock** (`1070d98`): SNAPSHOT inline read-modify-write is 522 µs against
  the vendor's 500, down from 1,381 against 468. The cell's row-lock waits went from 98 s to 0.1 s
  a run, and its YCSB from 0.61 to 0.86.
- **INSERT ALL:** the decoupled insert is +14% (READ COMMITTED) and +17% (SNAPSHOT), against +27%
  in the second run.
- **The bulk block took NewOrder's stock batch but not its order_line batch.** At SNAPSHOT
  decoupled, the stock block ran 43,241 times for 431,673 rows, its nested UPDATE and MERGE
  parsed 4 times in all. The ORDER_LINE insert and its metadata insert still ran per row
  (432,875 and 432,384 calls, as many parse calls), through the block of entries (40,038
  single-entry and 3,970 multi-entry calls). BenchBase binds OL_AMOUNT with `setDouble`, which
  design 4.4 kept off the bulk block while the driver did not know the connection's
  `SetFloatAndDoubleUseBinary` (found by the builder). The design now reads ojdbc's effective
  setting (4935bf4); a rerun of this cell is the batch item's proof. TPC-C stays at 0.63, server
  CPU 3.20 against 1.57 ms a commit.
- The decoupled update is still +34–46%, the largest single-key gap.
- TPC-C's buffer busy waits are the vendor's too (45–54 s a run for (a), 45–86 s for (c)): the
  hot district and warehouse rows at scale 4.

**Where the driver's client CPU goes** (flight recording, SNAPSHOT decoupled, YCSB, first
repetition). The JVM used 72.4 s of CPU for the vendor's run and 89.7 s for the driver's: 0.127
against 0.177 ms a transaction, about +50 µs. Of the driver run's CPU-time samples:
- 83% are in ojdbc (95% for the vendor), which is about +26 µs a transaction more ojdbc work:
  longer texts, callable blocks and their binds.
- 14% are the driver's own frames. Most of these, about 12% of the run (about 21 µs a
  transaction), are the reflection under the driver's `java.lang.reflect.Proxy` wrappers:
  `Proxies$Handler.invoke`, `Proxies$AnchoredHandler.handle`, `Invokers.checkCustomized`,
  `AccessibleObject.verifyAccess` and `DirectMethodHandleAccessor.checkReceiver`, on every call
  the application makes on a wrapped statement or result set.
- The SQL rewrite itself is small: `Router.route` 0.4%, jsqlparser 0.1%.
The design session measured what that 12% is (todo 334's log, "the third run read"). A
reflective proxy call over ojdbc's `ResultSet` costs 21 ns against 18 ns direct on JDK 25, so the
samples reflect the number of calls, not the cost of reflection. A 500-row scan makes about 6,000
proxied calls. Through the driver on the container, such a scan costs the client 150–350 µs more
than raw ojdbc at SNAPSHOT, and 360–840 µs at READ COMMITTED, where the anchors are read and
recorded per row. That is about the SNAPSHOT decoupled scan's whole +368 µs here; a key read's
twenty calls cost under a microsecond. Delegating classes in place of the proxies would therefore
help scans by about 5–8% and single-key operations not at all, and are not proposed.

The design session's other readings of this run:
- **The READ COMMITTED decoupled scan** uses 3.5 times the vendor's server CPU. Its plan could not
  be explained from the container: there, under the harness's statistics, the join of the
  metadata rows plans as a hash join with about the vendor's reads.
- **The decoupled update's MERGE stamp** parsed on 44,232 of its 99,150 executions, with 588 ms of
  `cursor: pin S wait on X`, where the container parses it once.
- For both, the harness's `server.txt` now also records the top ten statements' cursors per child
  (executions, parse calls, loads, invalidations, bind sensitivity, shareability), their plans
  (`DBMS_XPLAN.DISPLAY_CURSOR`), buffer gets per call, and the schema's table and key-column
  statistics at the run's start.
