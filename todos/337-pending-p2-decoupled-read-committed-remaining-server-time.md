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

## Log

### 2026-10-27: written up (Opus), sent to the design session

From todo 336's local run (`faae2ed`, the after tree), at the user's request.
