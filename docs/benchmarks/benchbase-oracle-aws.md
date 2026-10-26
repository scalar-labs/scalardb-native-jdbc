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
