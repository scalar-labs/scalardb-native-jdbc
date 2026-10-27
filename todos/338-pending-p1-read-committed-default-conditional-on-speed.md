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
