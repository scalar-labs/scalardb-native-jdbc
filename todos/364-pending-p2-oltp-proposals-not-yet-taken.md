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
- 2026-10-30 (item 1, Fable): the tenth run read (`bench-env/results/20261029-2333-ora` and `-pg`,
  from bfa20aa; 60 s runs, two repetitions, four terminals, both layouts, fast and +250 µs).
  - *The stalls are the server's, on both drivers.* The poller's distinct waits (sid, seq#) equal
    the harness's one-second socket read count in 31 of the 32 Oracle TPC-C runs (the other: 1
    against 2). All 231 waiter rows are class 1 data blocks (p3 65537, two rows p3 1), none has a
    blocking session, and the longest sampled wait is 999 ms where the container's held waits ran to
    2,078: the waiter sleeps Oracle's timeout and wakes to a free buffer. The blocks are the WAREHOUSE
    and DISTRICT tables' (for the decoupled driver, `WAREHOUSE_tx_metadata` and
    `DISTRICT_tx_metadata`), the statements Payment's reads of the warehouse and district rows: the
    vendor's own texts (0rbx2ssrazhyx, b5jhcpzgtrwpa) in its SERIALIZABLE runs, the driver's reads at
    both levels. Per repetition: READ COMMITTED vendor 0 in all eight runs, driver 0 to 4;
    SERIALIZABLE/SNAPSHOT vendor 0 to 30, driver 0 to 30 fast and 0 to 10 slow. A stall is one
    terminal-second of 240 a repetition, so 30 stalls are 12.5% of one (SNAPSHOT decoupled driver:
    1,115.7/s with 0 stalls, 1,034.8 with 30), which the per-repetition counts now let a reader take
    out. Nothing in this is the driver's; the diagnostics did their job and can stay in the harness.
  - *Headline, TPC-C (c)/(a), fast / +250 µs, the ninth run's fast figure in brackets.* READ
    COMMITTED decoupled 0.59 / 0.83 [0.61]; READ COMMITTED inline 0.71 / 0.88 [0.63 with 82 stalls];
    SNAPSHOT decoupled 0.67 / 0.85 [0.67]; SNAPSHOT inline 0.83 / 0.94 [0.82]. Oracle YCSB fast: A
    0.75 to 0.88, C 0.84 to 0.86, E 0.74 to 0.92, F 0.79 to 0.88; slow 0.83 to 0.96. PostgreSQL
    TPC-C fast, decoupled / inline: READ COMMITTED 0.69 / 0.78, REPEATABLE READ 0.80 / 0.86,
    SERIALIZABLE 0.84 / 0.93; slow 0.89 / 0.91, 0.92 / 0.94, 0.81 / 1.01; YCSB fast 0.71 to 0.94,
    the READ COMMITTED decoupled scan (E) lowest at 0.71.
  - *`table fetch continued row` per 60 s run.* Vendor 2,000 to 2,700 fast; driver decoupled 1,500 to
    2,200; driver inline 138,000 to 175,000 fast and 48,000 to 66,000 slow; YCSB inline about 0 (a
    1 KB row absorbs the 61 bytes of metadata within PCTFREE 10), but YCSB A and F decoupled 86,000
    and 53,000: the metadata rows the harness backfilled grow at their first real write, the
    backfill's `tx_id` being 24 characters and a transaction's 36. The migrated rows' reads: the
    as-written DISTRICT and WAREHOUSE reads at 2.6 gets a call against the vendor's 2.0. The
    transition itself, inside the measured minute (the row's growth plus the in-flight index entry
    moving from (NULL, key) to (3, key)): the stock UPDATE of ten rows 71.2 gets now, 53.5 in the
    ninth run on backfilled tables, the vendor's 40.2; Delivery's order_line UPDATE 70.3, 46.5,
    13.9; the oorder UPDATE 17.9, 8.5, 4.0.
  - *The in-flight index is the inline layout's largest remaining excess on Oracle.* It is a plain
    index on (`tx_state`, key) over every row (section 3), so every INSERT carries it (new_order 7.6
    gets against the vendor's 4.4, oorder 10.5 against 7.3), every DELETE too, every first write
    moves an entry, and the split read's in-flight arm and the gates probe a 3- or 4-level index
    twice, once for state 1 and once for 2 (`INLIST ITERATOR`): the item read 7.0 gets against 3.0
    at READ COMMITTED, the customer read 10.1 against 4.0 and 16.1 at SNAPSHOT with the gate's two
    probes more. To measure on the container, then the user's call: a function-based index that is
    NULL on committed rows, `(CASE WHEN tx_state IN (1, 2) THEN tx_state END, CASE WHEN tx_state IN
    (1, 2) THEN <key column> END, ...)`, Oracle's form of the partial index PostgreSQL has, which
    holds only the rows in flight: a one-block index, each probe one get, no maintenance on a
    committed row's write, insert or delete; the in-flight predicates spelled on the expression so the
    optimizer matches it, the `INDEX` and `NO_INDEX_SS` hints as they are, the setup's assumed-10,000
    statistics and the check's column-order rule adapted. Expected on inline TPC-C: 70 to 90 gets
    fewer of about 300 a NewOrder, the index entry moves of the first write gone with them.
  - *The row growth, the user's call.* (a) The backfill stamps a 36-character `tx_id` so a backfilled
    metadata row never grows, at no cost. (b) An imported inline table's rows grow by their metadata
    at the first write and migrate under the application's PCTFREE; the setup cannot resize existing
    rows without `ALTER TABLE ... MOVE`, so either an option that rebuilds the table with a larger
    PCTFREE on request, or a documented note.
