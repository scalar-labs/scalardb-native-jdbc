---
status: pending
priority: p1
issue_id: "338"
tags: [performance, read-committed, isolation, default, postgresql, oracle]
dependencies: ["329", "334", "337"]
---

# READ COMMITTED stays the driver's default only if it runs about as fast as SNAPSHOT

## Problem Statement

The driver starts every connection at READ COMMITTED (design 4.1, the connection methods); ScalarDB
starts at SNAPSHOT. The user's decision (2026-10-27): READ COMMITTED may stay the default, but only
once it is no longer visibly slower than SNAPSHOT, since a user who benchmarks with the default
setting meets READ COMMITTED first. If the gap cannot be closed, the default is reconsidered.

Separately, users are to be told to set the same level in the driver and in ScalarDB
(`scalar.db.consensus_commit.isolation_level`) for applications writing the same tables: a weaker
level on one side breaks the invariants the other side's level protects (a SNAPSHOT driver
transaction's write skew against SERIALIZABLE ScalarDB; at READ COMMITTED, a write following a read
that records no anchor can overwrite a ScalarDB commit). On Oracle, ScalarDB at SERIALIZABLE has no
matching driver level. That rule belongs in `docs/transactions.md`, which another session is
writing at the time of this todo.

## The gap, driver against vendor, TPC-C, fast round trip (about 70 µs)

| engine, layout | READ COMMITTED | SNAPSHOT | source |
|---|---|---|---|
| PostgreSQL, decoupled | 0.71 | 0.79 | eleventh run, faae2ed (todo 336) |
| PostgreSQL, inline (ScalarDB's default layout) | 0.78 | 0.87 | ninth run, 6c19378 |
| Oracle, decoupled | 0.51 | 0.63 | third Oracle run, 2162759 |
| Oracle, inline | 0.62 | 0.74 | third Oracle run, 2162759 |

YCSB, (c)/(a), fast round trip:

| engine, layout | READ COMMITTED | SNAPSHOT | source |
|---|---|---|---|
| PostgreSQL, decoupled | 0.66 | 0.85 | tenth run, 816f506, before todo 336; no YCSB cell since |
| PostgreSQL, inline | 0.86 | 0.88 | ninth run, 6c19378 |
| Oracle, decoupled | 0.64 | 0.89 | third Oracle run; the vendor's rate drifted, indicative only |
| Oracle, inline | not usable | 0.86 | third Oracle run; the vendor's scans 7.8 ms in the RC cell |

On Oracle the single-key medians are the measure (third run, p50 above the vendor's): READ
COMMITTED decoupled read +12%, update +34%, insert +14%; SNAPSHOT decoupled +21%, +46%, +17%;
READ COMMITTED inline +15%, +30%, +15%; SNAPSHOT inline +16%, +7%, +12%. Decoupled READ COMMITTED's
ratio is held back by its scans (1.7 times the vendor's p50). The next PostgreSQL run should include
YCSB for decoupled READ COMMITTED.

## Per operation, from the runs' own files

BenchBase writes a latency series per transaction type (`execute/*.results.<Type>.csv` in each
run's `*-benchbase-runs.tgz`), so the stored runs give a per-operation breakdown without a new run.
Below: the throughput-weighted median latency over the run's windows, the median of the
repetitions, vendor (a) then driver (c), µs, fast round trip. "Share" is the operation's weight in
the mix times its added latency, so the shares add up to the "added" figure of the run's write-up.

YCSB (weights: read 50, scan 15, read-modify-write 20, update 10, insert 5):

| PostgreSQL | read | update | insert | read-modify-write | scan |
|---|---|---|---|---|---|
| decoupled RC, tenth run (816f506) | 179 → 201 | 253 → 323 | 248 → 311 | 352 → 470 | **1325 → 2434** |
| decoupled SNAPSHOT, ninth run | 193 → 216 | 261 → 323 | 253 → 316 | 358 → 440 | 1231 → 1505 |
| inline RC, ninth run | 192 → 194 | 260 → 303 | 253 → 294 | 360 → 411 | 1236 → 1550 |
| inline SNAPSHOT, ninth run | 193 → 206 | 263 → 280 | 256 → 300 | 365 → 410 | 1243 → 1447 |

- Decoupled RC: the scan's share is about 166 of the 214 µs added per operation; the single-key
  operations add 21–117 µs each, near SNAPSHOT's 23–82. The scan is the item, at 1.84 times the
  vendor's, and todos 336 and 337 did not touch it.
- Inline RC: reads add 2 µs (SNAPSHOT 13); updates 43 against 17; the scan 314 against 204.

TPC-C, per transaction:

| PostgreSQL, added µs (times the vendor's) | NewOrder | Payment | Delivery | StockLevel | OrderStatus |
|---|---|---|---|---|---|
| decoupled RC, eleventh run (faae2ed) | +1030 (1.33) | +347 (1.41) | +4198 (1.58) | +1265 (2.02) | +151 (1.38) |
| decoupled SNAPSHOT, eleventh run | +669 (1.22) | +268 (1.32) | +2928 (1.41) | +165 (1.12) | +143 (1.37) |
| inline RC, ninth run | +649 (1.20) | +287 (1.33) | **+4344 (1.58)** | +747 (1.56) | +111 (1.27) |
| inline SNAPSHOT, ninth run | +463 (1.14) | +155 (1.17) | +808 (1.11) | +295 (1.22) | +155 (1.37) |

- Decoupled RC over SNAPSHOT, about 290 µs a transaction by share: NewOrder 163, Delivery 51,
  StockLevel 44, Payment 34 (before `cb16d83`, which takes StockLevel's probes and part of its
  planning).
- Inline RC over SNAPSHOT, about 300 µs: Delivery 142 (its write beyond the key, +3.5 ms a call
  over SNAPSHOT's), NewOrder 84, Payment 56, StockLevel 18.

Oracle, third run, YCSB: the scan is behind the vendor only at decoupled RC (2307 → 4020); the
update adds 136–164 µs on decoupled at both levels and 115 inline RC, against 26 inline SNAPSHOT.

Oracle, third run, TPC-C, added µs a transaction (times the vendor's), and the share of the
transaction's added time by mix weight:

| Oracle | NewOrder | Payment | Delivery | StockLevel | OrderStatus | added, by share |
|---|---|---|---|---|---|---|
| decoupled RC | +3212 (1.92) | +792 (1.74) | +11200 (2.28) | +550 (1.52) | +271 (1.52) | 2.27 ms |
| decoupled SNAPSHOT | +2044 (1.60) | +633 (1.57) | +6335 (1.73) | +223 (1.20) | +365 (1.67) | 1.47 ms |
| inline RC | +1683 (1.46) | +505 (1.45) | +10006 (2.09) | +998 (1.88) | +196 (1.35) | 1.42 ms |
| inline SNAPSHOT | +1339 (1.40) | +239 (1.22) | +2060 (1.24) | +312 (1.30) | +369 (1.69) | 0.82 ms |

NewOrder is 53–64% of the added time in every cell (its order_line inserts still per row in this
run, the bulk block's double fixed after it, 4834c21, not yet measured). At RC, Delivery is
2.1–2.3 times the vendor's on both layouts, about 0.4 ms a transaction by share: the write beyond
the key, as on PostgreSQL inline.

Server time per call on PostgreSQL, YCSB fast, from each run's `server.txt` (`pg_stat_statements`):

| statement, µs a call | vendor | decoupled SNAPSHOT | decoupled RC (tenth) | inline SNAPSHOT | inline RC |
|---|---|---|---|---|---|
| scan, 500 rows | 952 | 1003 | **1812** | 929 | 935 |
| keyed update | 14.6 | 34.5–39.2 | 34.5 | | |

So the decoupled RC scan's gap is mostly the server's (about 860 µs a scan, the metadata row
joined for each of 500 rows, where SNAPSHOT checks the table once), and the rest of every RC scan's
added latency is the client's (inline RC: the server equal to the vendor's, the latency +314 µs: the
three hidden columns and an anchor recorded per row, through the proxies). The decoupled update's
server share is about 20–25 µs of its +62; the rest is the client and the commit.

Candidates, none designed:

1. **Scan, server, decoupled RC:** where the transaction can take no anchor (autocommit, a
   read-only transaction), SNAPSHOT's once-gated form (the table checked once for rows in flight,
   the data rows read plain) instead of the per-row join; elsewhere a cheaper join plan for a key
   range. On Oracle, the plan first (todo 334's third-run note: stale metadata statistics give
   nested loops, 592 reads against 82).
2. **Scan, client, RC both layouts:** the per-row hidden columns and anchors, and the reflection
   under the proxies (12% of the driver's client samples on Oracle; a fast path in the handler for
   `getXxx(int)`, or delegating classes). Todo 334 left it as not serving key operations; it serves
   the scan.
3. **Update, client:** a profile of the write path on both engines (the driver's 35–50 µs on
   Oracle); the server's share is near the layout's floor (a second row, and on Oracle the block).
4. **Update, Oracle:** the stamp's re-parsing seen only at AWS (todo 334), from the next run's
   per-child `V$SQL`.

The mixed runs keep contention and cache effects in these figures. A run with one operation at a
time (YCSB with one weight at 100) would isolate each; the per-type series above already point at
the items: the decoupled RC scan, the inline RC write beyond the key, the update on Oracle.

## Where each row stands

1. **PostgreSQL, decoupled.** Todo 337's design reading expects `cb16d83` to bring READ COMMITTED to
   about 0.1–0.15 ms a transaction above SNAPSHOT, with nothing of that todo's size left. Needs the
   AWS cell of `cb16d83` (or later), which waits on the user's approval of a run.
2. **PostgreSQL, inline.** Not analyzed since the ninth run. Todo 329's log names one statement: the
   inline write beyond the key, about 200 µs a call (28.2 s of 98.2 s in a fast READ COMMITTED
   repetition). This is the layout a new user gets by default, so it matters most for the user's
   concern. Needs a per-statement reading against SNAPSHOT inline, as todo 337 did for decoupled.
3. **Oracle, both layouts.** READ COMMITTED trails SNAPSHOT by about 0.12 on both. Todo 334's
   work so far (the deadlock fix, INSERT ALL, the bulk block) served both levels; no reading of
   READ COMMITTED against SNAPSHOT on Oracle exists yet.

## Acceptance

- A target agreed with the user. Proposed: READ COMMITTED within 0.05 of SNAPSHOT's ratio, TPC-C and
  YCSB, fast round trip, on PostgreSQL inline and decoupled; Oracle's own target set once its
  SNAPSHOT gap to the vendor is understood.
- Measured on AWS, both layouts, both levels back to back.
- If the target is not met: the default reconsidered with the user (SNAPSHOT, or a connection
  property for the starting level).
- The same-level rule written in `docs/transactions.md`.

## Log

### 2026-10-27: opened (Fable 10)

From the user's question whether SNAPSHOT should be the default like ScalarDB's. Recommended keeping
READ COMMITTED (JDBC applications expect their database's default and seldom retry `40001`); the
user agreed on the condition above. Rows 2 and 3 need a design session's reading; row 1 needs an
AWS run.
