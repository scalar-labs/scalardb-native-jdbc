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

## Fourth run: the order_line batch in the bulk block, the plans (2026-10-27)

Run `20261027-1617` (`SOURCE=bdbdd21`: a double's bulk element typed from ojdbc's resolved
`SetFloatAndDoubleUseBinary`, 65d83c3, and the harness's plans, cursors and statistics,
804b9e8), decoupled at READ COMMITTED and SNAPSHOT, statement cache 128, fast network, REPS 2,
no flight recording. About 50 minutes of instances.

| (c)/(a), TPC-C / YCSB | third run | fourth run |
|---|---|---|
| READ COMMITTED, decoupled | 0.51 / 0.64 | 0.51 / 0.78 |
| SNAPSHOT, decoupled | 0.63 / 0.89 | 0.62 / 0.75 |

YCSB p50, µs, vendor → driver:

| | read | update | insert | read-modify-write | scan |
|---|---|---|---|---|---|
| READ COMMITTED, decoupled | 189 → 214 (+13%) | 352 → 485 (+38%) | 377 → 438 (+16%) | 477 → 621 (+30%) | 3919 → 4845 |
| SNAPSHOT, decoupled | 190 → 227 (+19%) | 345 → 503 (+46%) | 371 → 436 (+18%) | 466 → 621 (+33%) | 4072 → 4670 |

**The order_line batch now takes the bulk block, but TPC-C did not move.** In the SNAPSHOT run's
first TPC-C execution, `INSERT INTO ORDER_LINE` ran 52,592 times for 513,762 rows (about ten a
call; third run 432,875 calls for 432,346 rows), parsed 973 times (432,798). All statements
together parsed 105,782 times against 967,549. Server CPU fell to 2.68 ms a commit (third run
3.20; the vendor 1.56), and DB time to 3.01 ms (3.37; the vendor 1.49). Throughput stayed at 0.62.
TPC-C p50, vendor → driver: NewOrder 3,299 → 4,696 µs, Payment 1,059 → 1,617, Delivery 8,331 →
14,071, OrderStatus 525 → 875, StockLevel 1,046 → 1,254. The time that remains is DB time, not
CPU. Payment's district and warehouse reads, plain key reads, take 840 and 265 µs elapsed a call
(2.5 buffer gets, 13 µs of CPU), against the vendor's 400 and 260. Both sides wait there on the
hot blocks of scale 4: buffer busy waits are 55 s a run for (c) and 49 s for (a). The driver's
transactions are longer and hold those blocks longer.

**The design session's two questions, from the new sections:**
- **The READ COMMITTED decoupled scan** is a hash join of two full scans: `TABLE ACCESS FULL` of
  USERTABLE and of USERTABLE_tx_metadata, 1,660 buffer gets and 3.65 ms of CPU a call. The
  statistics at the run's start were gathered after the load: 1,000 rows and 244 blocks for
  USERTABLE, 1,000 rows and 13 blocks for the metadata table, which has its statistics locked.
  The table grows with the runs' inserts after that.
- **The vendor's scan flips plans between cells.** Its one text (`cucxy6uzcz35q`) is an index
  range scan in one cell (104 gets, 788 µs a call) and a full scan of USERTABLE in the other
  (1,560 gets, 4.9 ms). The plan is chosen at the first hard parse by the binds peeked there;
  each run starts with a flushed shared pool, so each run draws again. This is the vendor YCSB
  drift seen since the first run.
- **The SNAPSHOT scan** has two children: child 0 is not shareable, at 110 gets a call, and
  child 1 is bind-aware (adaptive cursor sharing), at 1,856 gets a call.
- **The decoupled update's MERGE stamp** (`MERGE INTO USERTABLE_tx_metadata`) ran 177,137 times
  with 78,779 parse calls, on one child, 1 load and no invalidation. These are soft parses of the
  nested cursor, not reloads: the PL/SQL cursor cache overflowing (`session_cached_cursors`, 50),
  as the design session found for the batch blocks.

## Fifth run: YCSB A, C, E and F at 1M rows, the statement first at READ COMMITTED (2026-10-27)

Run `20261027-1948`, the first from a plan: `bench-env/plans/ycsb-single-key.json` with a client
flight recording added (the copy that ran is in the results as `plan.json`). Main `5cfffc3`: the
keyed write at READ COMMITTED as one statement first, the block on a miss, and every block's nested
statements named apart (todo 335). YCSB at 1M rows (scale 1000), 8 loader threads, 60 s a run,
REPS 2, 4 terminals, statement cache 128 on both sides, fast network. All four cells ran, about
1 h 50 min of instances.

Throughput, (c)/(a):

| workload | RC decoupled | RC inline | SNAPSHOT decoupled | SNAPSHOT inline |
|---|---|---|---|---|
| A, read/update | 0.82 | 0.90 | 0.76 | 0.88 |
| C, read | 0.84 | 0.86 | 0.86 | 0.86 |
| E, scan/insert | 0.68 | 0.76 | 0.89 | 0.86 |
| F, read/read-modify-write | 0.78 | 0.89 | 0.79 | 0.90 |

p50, µs, vendor → driver:

| | RC decoupled | RC inline | SNAPSHOT decoupled | SNAPSHOT inline |
|---|---|---|---|---|
| read (C) | 93 → 110 (+18%) | 91 → 106 (+16%) | 167 → 194 (+16%) | 169 → 197 (+17%) |
| update (A) | 327 → 418 (+28%) | 307 → 336 (+9%) | 301 → 418 (+39%) | 301 → 333 (+11%) |
| read-modify-write (F) | 420 → 551 (+31%) | 421 → 470 (+12%) | 421 → 547 (+30%) | 412 → 443 (+8%) |
| scan (E) | 2031 → 2979 (+47%) | 2113 → 2768 (+31%) | 2177 → 2451 (+13%) | 2161 → 2487 (+15%) |
| insert (E) | 443 → 564 (+27%) | 429 → 565 (+32%) | 435 → 526 (+21%) | 425 → 539 (+27%) |

The vendor's reads at SNAPSHOT (its SERIALIZABLE) take about 168 µs against about 92 at READ
COMMITTED. At 1M rows the scan plans held: index range scans on both sides in every cell.

- **The statement first at READ COMMITTED.** The inline update is +9%, against +30% in the third
  run, and read-modify-write +12% (+23%). In RC inline's YCSB A, the update is one plain
  `UPDATE ... SET "tx_id" = :1 ...`: 445,338 executions, 4 parse calls, 65 µs elapsed and 22 µs
  CPU each, with no keyed block among the top statements. The decoupled update is +28% (+34–38%).
- **Inline meets 10–20%** for reads, updates and read-modify-write at both levels, and for scans
  at SNAPSHOT.
- **The decoupled update stays at +28–39%**, the second table's write in a block (design 4.3, the
  floor).
- **The READ COMMITTED scan.** Decoupled, it is +47%. Server: 1,307 against 748 µs elapsed, 621
  against 306 µs CPU, 119 against 104 gets a call. That is the metadata join and the in-flight arm
  of the union (todo 334, fb12c98). Client: 1.50 against 1.10 ms of JVM CPU a transaction (flight
  recording), +400 µs. Of the driver's CPU-time samples, the anchor path (`Anchors.*`,
  `Writes.readKey`) is about 7%. Most of the 94% under `Proxies$AnchoredHandler.handle` is ojdbc
  and the proxied result-set calls. At SNAPSHOT the scan is +13–15%.
- **The client CPU of YCSB A**, per transaction (half reads, half updates), is +9 µs inline and
  +12 µs decoupled over the vendor's 32–34 µs.
- **YCSB E's inserts** are +21–32% in every cell. The INSERT statement accounts for +66 µs
  (decoupled `INSERT ALL`) and +31 µs (inline), and no other statement runs once an insert. GC
  pauses are the vendor's too: about 0.4 s a run, longest 7 ms, on both sides. The rest is open
  (todo 334, eba2477).

## Sixth run: overnight, TPC-C scale 32 and YCSB at 1M rows, fast and slow (2026-10-28)

Run `20261028-0143-ora` from `bench-env/plans/overnight-oracle.json` on main `db0bf06`. It is the
first with all three of the night's items:
1. the inline INSERT without generated keys unless asked, `0cf9ac0`;
2. the result-set fast path, `5be09a9`;
3. the lazy anchor map, `e8f3436`.

TPC-C scale 32 and YCSB A, C, E, F at 1M rows. READ COMMITTED and SNAPSHOT, both layouts, the fast
network and then 250 µs more a round trip. 60 s a run, REPS 2, 4 terminals, statement cache 128, a
client flight recording of every run. It ran on its own stack (`STACK=ora`) beside PostgreSQL's.
All 8 cells passed, 40–49 min each.

Throughput, (c)/(a):

| cell | TPC-C | A | C | E | F |
|---|---|---|---|---|---|
| RC decoupled, fast | **0.13** | 0.80 | 0.84 | 0.66 | 0.77 |
| RC inline, fast | 0.54 | 0.90 | 0.86 | 0.77 | 0.88 |
| SNAPSHOT decoupled, fast | 0.63 | 0.76 | 0.85 | 0.88 | 0.79 |
| SNAPSHOT inline, fast | 0.62 | 0.97 | 0.87 | 0.84 | 0.92 |
| RC decoupled, +250 µs | **0.16** | 0.92 | 0.94 | 0.82 | 0.91 |
| RC inline, +250 µs | 0.81 | 0.96 | 0.97 | 0.84 | 0.98 |
| SNAPSHOT decoupled, +250 µs | 0.86 | 0.90 | 0.97 | 0.93 | 0.92 |
| SNAPSHOT inline, +250 µs | 0.88 | 0.95 | 0.96 | 0.92 | 0.96 |

YCSB p50 overhead, fast network (fifth run in brackets):

| | read (C) | update | RMW | scan | insert |
|---|---|---|---|---|---|
| RC decoupled | +21% (+18) | +31% (+28) | +33% (+31) | +52% (+47) | +19% (+27) |
| RC inline | +16% (+16) | +9% (+9) | +12% (+12) | +29% (+31) | **+15% (+32)** |
| SNAPSHOT decoupled | +18% (+16) | +40% (+39) | +31% (+30) | +13% (+13) | +21% (+21) |
| SNAPSHOT inline | +15% (+17) | +6% (+11) | +7% (+8) | +19% (+15) | +25% (+27) |

The vendor's YCSB E insert ran at 483–540 µs here, against 425–443 in the fifth run.

**TPC-C at READ COMMITTED decoupled collapses at scale 32 (0.13 fast, 0.16 slow).** The cause is
Oracle's dynamic sampling at every hard parse of the metadata tables' keyed statements. The setup
gathers and locks statistics on `tx_state` alone, so the key columns have none (todo 334, ac5eeec
and 144c623). Measured from the live dictionary during the slow cell's driver run:
- 361 `DS_SVC` sampling cursors, 3,280 executions and 100.5 s within the run. Each counts the key
  predicate through `STOCK_INFLIGHT`: about 320 ms and 12,853 gets.
- 392 SQL plan directives on `STOCK_tx_metadata`.
- The setup's statistics operations completed in order before the schema gather. Every metadata
  table has statistics on `tx_state` alone.

A one-line fix (gather all columns, `SIZE REPEAT`, before the fixed histogram) is proposed. The
RC block of entries' own 33–55 ms a call is the same sampling (todo 334, 018283c). Oracle counts a
hard parse's sampling queries, run as recursive SQL, in the parsed cursor's own CPU and gets, so
each nested statement carries its parse's sampling, averaged over its calls:
- the CAS on STOCK_tx_metadata: 1.4 ms CPU and 58 gets a call, by its primary key;
- step 1's read: 0.7 ms and 33.5 gets.
On the design session's container, with STOCK's shape at 3.2M rows, the same plans cost 8.6–12.6
gets and 83–91 µs a call under the proposed gather, with no sampling cursors. So without the
sampling the cell should move toward the other TPC-C cells (0.54–0.63 fast), not the 0.19 first
estimated (ac5eeec). The merged form's item remains for the 9–13 gets an entry that stay. SNAPSHOT's TPC-C (bulk blocks) is 0.63 fast, as at scale 4, and
0.86–0.88 slow.

**The night's three items, from the flight recordings** (RC decoupled, YCSB E, driver run):
- **Item 1:** the inline INSERT ends at `VALUES (...)`, with no `RETURNING ROWID`. The RC inline
  insert's p50 overhead is +15%, from +32%.
- **Item 2:** the samples under `Method.invoke` fell from 82.3% to 2.5%.
- **Item 3:** `Anchors.*` plus `Writes.readKey` fell from 6.7% to 4.4%. `getMetaData` and
  `getColumnTypeName` are about 0.
- **The scan's excess did not shrink here, and that is the run, not the code** (todo 334, 80b4de6).
  JVM CPU a transaction is 1.71 ms for the driver against 1.10 ms for the vendor, +610 µs, where the
  fifth run had +395 µs; the RC decoupled scan p50 is +52% (+47%). But between the runs everything got
  slower:
  - Client CPU a scan: the vendor's +10% (697/670 → 767/745 µs over the two repetitions), the
    driver's +13% (1,078/1,092 → 1,266/1,189).
  - Server CPU a call: the driver's scan statement 621 → 700 µs, with the same plan and the same 119
    gets; the vendor's 306 → 304.
  - Rows, fetches, network waits, the client's load (12% of 16 vCPUs), GC and deoptimizations are
    alike in both runs.
  - Repetitions of one cell differ by up to 6%. The driver's excess grew by about 70 µs a scan, under
    twice that.
  By category, the driver's own code fell 248 → 205 µs a scan, as designed. What grew is ojdbc's work
  under the proxy: the application's 5,000 getString calls a scan went 450 → 563 µs, where the
  vendor's own stayed at 412. On the design session's container, main is cheaper than the fifth run's
  code in every configuration: BenchBase's result-set shape, 1 thread, +611 → +244 µs a scan over the
  vendor; 4 threads +359 → +278 and +420 → +352. The proof would be the two builds back to back on
  one stack (YCSB E, RC decoupled, about 10 minutes).

At +250 µs every YCSB cell is 0.82–0.98.

## Seventh run: the metadata statistics fix, TPC-C RC decoupled at scale 32 (2026-10-28)

Run `20261028-1118-ora` from `bench-env/plans/oracle-rc-tpcc-stats.json` on main `2ba29f2`. That
main has `110a623`: the setup gathers every column of an Oracle metadata table (`FOR ALL COLUMNS
SIZE REPEAT`) before the fixed `tx_state` histogram. One cell: TPC-C scale 32, READ COMMITTED
decoupled, the fast network, 60 s × 2, statement cache 128. About 25 minutes of instances.

| RC decoupled TPC-C, scale 32 | sixth run | seventh run |
|---|---|---|
| (c)/(a) | 0.13 | **0.45** (734 against 1,640/s) |
| `DS_SVC` sampling, a run | 371 texts, 120 s | **0** |
| the stock block of entries, a call | 33–55 ms | 1.6–2.6 ms CPU, 161–241 gets |
| the CAS and step 1's read on STOCK_tx_metadata | 1.4 / 0.7 ms CPU a call | below the top 80 statements |
| server CPU / active a commit | — | 4.94 / 4.65 ms (vendor 1.62 / 1.29) |
| redo a commit | — | 22.4 KB (7.8) |

- The metadata tables now have statistics on their key columns (STOCK_tx_metadata.S_I_ID 100,824
  distinct, S_W_ID 32), and no hard parse samples.
- TPC-C p50, vendor → driver: NewOrder 3,436 → 7,454 µs, Payment 1,043 → 1,698, Delivery 8,594 →
  25,816, OrderStatus 519 → 835, StockLevel 1,106 → 1,545.
- The cell is still below SNAPSHOT decoupled (0.63) and RC inline (0.54). What remains is the RC
  block of entries: one PL/SQL text per batch size, each about 2 ms of CPU and 160–240 gets a call,
  where SNAPSHOT's bulk form takes the batch in one statement a shape. That is the merged form's
  item at READ COMMITTED (design 4.4). Delivery, whose order_line update and stamps run through it,
  is 3 times the vendor's.
- This run's harness predates `d0fd2c2`, so its per-commit CPU still includes BenchBase's startup
  statistics gathering (9 `GATHER_TABLE_STATS` calls).
