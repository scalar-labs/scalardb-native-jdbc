---
status: pending
priority: p2
issue_id: "364"
tags: [performance, oracle, postgresql, design]
dependencies: []
---

# OLTP proposals made and not yet taken (from todo 334)

## Problem Statement

Todo 334 closed on 2026-10-29 with every Oracle form it designed built. These proposals from its log
and the reviews around it were made but not taken; each waits on the user's word. Details are in
todo 334's log and the design sections named.

## The proposals

1. **Oracle's one-second buffer busy waits.** Read from the next full run's diagnostics: each
   repetition's stall count (55048da) and the V$SESSION poller's `waits.txt` (7ce0408). Then decide
   whether anything on the driver's side is involved (the vendor's runs have them too).
2. **D1's gate, one per statement.** The SNAPSHOT inline state column's gate costs two INLIST probes a
   UNION ALL branch (16 of 27.7 gets in the ninth run); one gate per statement, or `BETWEEN 1 AND 2`.
3. (Four items listed here at first were already built and on main before this todo was written,
   each on the user's word, 2026-10-28: the READ COMMITTED decoupled bulk blocks, d10c269; Oracle's
   inline `NO_INDEX_SS`, 3ebf06b; PostgreSQL's metadata-side key range, 99e9c86; the setup's indexes
   in the data key's order, 98f8d47. Struck 2026-10-29.)

## Acceptance Criteria

- [ ] Each proposal taken or dropped with the user, the taken ones as todos of their own.

## Work Log

- 2026-10-29: collected from todo 334 when it closed (the user's go-ahead).
- 2026-10-29 (item 1, Fable): the harness's poller (7ce0408) validated on the local container against
  `V$ACTIVE_SESSION_HISTORY` (scratchpad `ora/poll.jsh`, outputs `poll2.txt` to `poll4.txt`): 8
  sessions updating their own rows of one block of the scratch table `SYSTEM.OLT` (file 21, block
  269281) in autocommit for 2 minutes, the waiters query every 500 ms. ASH recorded two episodes of
  buffer busy waits of 900 ms or more (2,078 and 2,086 ms, three and two waiters); the poller caught
  both at about 800 ms elapsed with sid, `sql_id`, p1/p2/p3 (class 1, a data block), and
  `DBA_EXTENTS` mapped the block to the table. The holder on the container sits in `resmgr:cpu
  quantum` for 0.8 to 1.3 s (Free's two-CPU cap) with the pin held, the chain two deep in ASH. Three
  changes sent to 6e: one SELECT of every active session per sample, decided client-side (a second
  snapshot query lags past the episode: it listed one session in `log file sync` while ASH had six in
  `resmgr:cpu quantum` at that second); no `type = 'USER'` filter (one episode's only other active
  sessions were LGWR in `log file parallel write` and two in `log file sync`); `blocking_session` a
  hint only (present on 3 of 5 waiter rows in `V$SESSION`, on every ASH sample). And the AWS stalls
  are not slow holders: at 10 ms resolution every long read of the ninth run is in the 1,000 to
  1,009 ms bin (READ COMMITTED inline driver 82 of 82, SNAPSHOT inline vendor 38 of 38, driver 49 of
  49), where the container's waits run past the mark (2,078 ms as one wait); so the waiter sleeps
  Oracle's full buffer-busy timeout and wakes to a free buffer, a lost post. Expected in `waits.txt`:
  the waiter at 300 to 1,000 ms with no holder in the sample; the object and class say whether the
  block is a hot data block, an index leaf (the in-flight index at `tx_state` 1) or space management.
- 2026-10-29 (item 1, Fable): 6e's local smoke run of the harness (Oracle Free, TPC-C scale 1, 30 s,
  READ COMMITTED inline, no backfill, from bfa20aa): `table fetch continued row` grew by 225 over the
  vendor's run and by 11,521 over the driver's. That is the inline layout's row growth: a row's first
  write adds its metadata (`tx_id` and the four stamps) for good and its before image for the length
  of the transaction (section 1, items 2 and 5); a stock or customer row's before image is about the
  row's size, a block's PCTFREE 10 reserve (about 800 bytes) holds the growth of a couple of its
  rows, so most rows written migrate, and every later fetch of a migrated row by rowid costs a get
  more. With the backfill the migration happened at setup (the ninth run's inline tables at 2 to 6x
  the vendor's blocks); without it, it happens during the run, so tonight's inline cells carry the
  migration's writes as well as its reads, a baseline the ninth run's inline cells did not have. To
  read beside the counter: the driver's key reads' gets a call against the vendor's in the harness's
  `V$SQL` figures, about one more per migrated row fetched. The cost is the layout's (ScalarDB's own
  tables grow the same way); a remedy, if the counter says it matters, is the setup's import
  rebuilding an inline table with a larger PCTFREE (`ALTER TABLE ... MOVE PCTFREE n`, offline, its
  indexes rebuilt), on request as the backfill is, the user's call. The poller's keep-and-write path
  stays untested end to end (no local wait over 300 ms).
