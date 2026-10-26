---
status: pending
priority: p2
issue_id: "337"
tags: [performance, postgresql, read-committed, decoupled, design]
dependencies: ["336"]
---

# PostgreSQL, decoupled layout, READ COMMITTED: three candidates for the server time left after todo 336

## Problem Statement

After todo 336 (`faae2ed`), the local TPC-C run, decoupled READ COMMITTED (todo 336's log,
2026-10-26), spends 862 µs of server execution a commit through the driver against the vendor's
462. The statements behind most of that gap, from `pg_stat_statements` of the same run:

| statement | driver, µs a call | vendor, µs a call | gap, µs a commit |
|---|---|---|---|
| the decoupled lock's second statement, the versions read | 15.7 | none | 70 |
| the item read (ten a NewOrder) | 15.2 | 7.3 | about 36 |
| StockLevel's `COUNT(DISTINCT ...)` over order_line and stock | 1097 | 844 | about 8 |

The rest of the gap is in the writes the protocol requires (stamps, metadata rows): Delivery's
order_line write 142 µs against the vendor's 45, most of it the ten stamps.

These are an implementing session's observations (Opus), measured on the laptop only; none is
designed. Each goes to the design (Fable) for a decision before any builder, as todos 334 and 336
did. Suggested order: wait for todo 336's AWS cells first; candidate 1 also bears on the item the
design asked to watch there (the versions read rising from 5.4 to 15.7 µs as the lock's first
statement fell from 31.6 to 16.4).

## Candidates

### 1. The lock's versions read from the metadata table alone

The one-round-trip decoupled lock applies only to pure predicates on key columns
(`Router.lockingRead`, `fastRc`). Its second statement today joins the data table to the metadata
table:

```sql
SELECT d.k, m.tx_id, m.tx_version, m.tx_state,
       (m.before_tx_id IS NOT NULL OR m.before_tx_version IS NOT NULL)
FROM (SELECT x.k FROM T x WHERE <P>) d LEFT JOIN T_meta m ON m.k = d.k
```

Since `<P>` names key columns only, and `T_meta` has the same key columns, it could read
`T_meta` alone: `SELECT m.k, m.tx_id, ... FROM T_meta m WHERE <P>`, one probe per key instead of
two. What changes: a locked key whose data row has no metadata row (an imported table before its
backfill) no longer shows in the second result; `LockPlan.applyFastRcDecoupled` already falls back
to the steps for a locked key the read does not show, so the outcome would be the steps', as for
today's NULL join. To decide: whether that fallback is the wanted outcome for such a key, and
whether reading `T_meta` by the key while the data rows are locked keeps the design's argument (a
prepare that has not committed cannot change the metadata row, since it needs the data row's
lock). Expected: up to half of the 70 µs a commit; to measure on AWS beside todo 336's cells.

### 2. The item read

`SELECT I_PRICE, I_NAME, I_DATA FROM item WHERE I_ID = ?` costs 15.2 µs a call through the driver
against 7.3 for the vendor, ten a NewOrder. It reads the committed copy with a left join to
`item_tx_metadata` and its two version columns (the anchors of 4.2). TPC-C never writes `item`,
but the driver cannot know that when it plans the read. Only the measurement is here: whether a
cheaper text exists at READ COMMITTED for a key read on a decoupled table (one probe instead of the
join and the `COALESCE` state filter, as form 1 did for the lock) is the design's question.

### 3. The stock-level count

StockLevel's join over order_line pays the committed copy's filter, one `NOT EXISTS` probe of
`order_line_tx_metadata` a row, 1097 µs a call against the vendor's 844. At READ COMMITTED every
statement takes its own snapshot, so the clear-table memory of SNAPSHOT does not apply; a
one-probe check of whether the table has any row in flight at all, inside the statement (as the
`states` form of todo 329 does for its state column), might replace the per-row probe when the
table is clear. Small per commit (StockLevel is 4% of the mix), large per call.

## Decisions (the design, 2026-10-27)

Measured on the laptop's `bb-pgss` container (TPC-C, decoupled, scale 4, nothing in flight),
pgbench prepared mode, two clients, 8 s a variant, server time per statement from
`pg_stat_statements` (mean / min µs, shared blocks a call); the variants are in the design
session's scratchpad, `pgb4/`.

| statement | as built | the form decided | vendor |
|---|---|---|---|
| the lock's versions read (1) | 9 / 5, 8 blocks | 7 / 3, 3 blocks | none |
| the lock itself, for scale | 14 / 6, 5 blocks | unchanged | |
| the item read (2) | 8 / 4, 7 blocks | 8 / 4, 6 blocks (not taken) | 5 / 2, 3 blocks |
| the item read without an anchor (autocommit, read-only) | 8 / 4 | 6 / 3 by (3) | 5 / 2 |
| StockLevel's count (3), execution | 706 / 296, 800 blocks | 623 / 262, 785 blocks | 616 / 284, 776 blocks |
| StockLevel's count (3), planning, µs a plan | 1,091 | 705 | 125 |

1. **Taken: the versions read from the metadata table alone** (design 4.3, the decoupled locking
   read). `SELECT m.<key>, m.tx_id, m.tx_version, m.tx_state, (m.before_tx_id IS NOT NULL OR
   m.before_tx_version IS NOT NULL) FROM T_meta m WHERE <P>`, the predicate as written, which names
   key columns only and resolves against the metadata table's own. The argument is unchanged: a
   prepare that has not committed cannot change the metadata row while the driver holds the data
   row's lock, whichever table the read starts from. A locked key the read does not show has no
   metadata row and takes the NULL-metadata anchor of 4.4, as the join's NULL row gave it today;
   **not** the steps: the fallback in `applyFastRcDecoupled` becomes that anchor. A key shown that
   the lock did not return (a tombstone, a row inserted and committed between the two snapshots)
   keeps today's rule: in flight with a before image it aborts, else nothing. Gain: 2 µs a lock
   warm, about 20 µs a NewOrder; the benchmark's state inflates every statement alike, so expect
   the same share of the 15.7 there.
2. **Not taken: the item read.** The gap is the metadata row's probe, which the anchor needs; the
   in-flight arm costs a probe of an empty partial index, nothing. A one-copy form, `T LEFT JOIN
   T_meta` with a `CASE` per non-key column choosing the before image for a row in flight, measured
   the same 8 / 4 and would hide the non-key columns behind expressions, which section 3 rejected
   for the view. So no cheaper anchored key read exists at this level; recorded in design 4.2. The
   same one-copy form on StockLevel's two tables (no anchor there) measured 1,785 / 936 with 2,284
   blocks: a probe of the metadata row for every row the range reads. Reads that take no anchor get
   candidate 3's gate instead, 8 to 6 µs on a key read.
3. **Taken: the per-row in-flight probe behind one probe of the index** (design 4.2, "How a
   committed table excludes its rows in flight"). On PostgreSQL a committed copy that probes the
   in-flight index per row, a decoupled table without own versions, takes `NOT EXISTS (SELECT 1
   FROM T_meta WHERE tx_state IN (1, 2)) OR NOT EXISTS (SELECT 1 FROM T_meta q WHERE q.<pk> =
   t.<pk> AND q.tx_state IN (1, 2))`. The planner reads the first once (InitPlan) and makes the
   second a hashed set of the keys in flight, never built while the first finds nothing (EXPLAIN:
   `(NOT $0) OR (NOT (hashed SubPlan n))`, the SubPlan "never executed"); past `work_mem` it keeps
   the EXISTS as the probe per row, by its rule for EXISTS it can hash: the builder confirms both
   plans. Spelled as EXISTS, not as `NOT IN` over the key list, for that fallback; the two measured
   the same (623 against 623). Not the inline layout, whose copy reads the row's own state; not
   Oracle, whose read side is todo 334's session's. In a read of several tables each copy carries
   its own InitPlan, one block each.

The builder's order, as the implementing session suggested: after todo 336's AWS cells. Tasks: (1)
in `Router.lockingRead`'s versions statement and `applyFastRcDecoupled`'s missing-key branch, with
a test locking a key that has no metadata row (an imported table after the setup, a native insert)
and one with a tombstone under the predicate; (3) in `SplitRead.committed` for `Check.NOT_EXISTS`
on PostgreSQL, the existing tests of rows in flight covering the set, plus an EXPLAIN check of the
two plans in the log. Then the local TPC-C run at READ COMMITTED, decoupled, and the AWS cell.

## Log

### 2026-10-27: written up (Opus), sent to the design session

From todo 336's local run (`faae2ed`, the after tree), at the user's request.

### 2026-10-27: decided (the design session)

As above. The item read closed; the versions read and the gated probe to build after the AWS
cells.
