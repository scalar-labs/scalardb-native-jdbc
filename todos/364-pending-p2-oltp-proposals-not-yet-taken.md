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
- 2026-10-30 (Fable): the function-based in-flight index measured on the container (scratchpad
  `ora/fbi.jsh`, output `fbi.txt`): two copies of the 300,000-row inline scratch table without
  metadata, `TI_P` with the plain index `(TX_STATE, K)` and `TI_F` with `(CASE WHEN TX_STATE IN (1,
  2) THEN TX_STATE END, CASE WHEN TX_STATE IN (1, 2) THEN K END)`, statistics gathered, 5,000
  executions a form after 200 of warm-up, gets and CPU from `V$MYSTAT`. The optimizer matches the
  predicates spelled on the expressions to the index's hidden columns (`SYS_NC00022$ = 1 OR ... = 2`
  in the access predicate), under the same `INDEX` hint; the stored expression reads `CASE
  "TX_STATE" WHEN 1 THEN "TX_STATE" WHEN 2 THEN "TX_STATE" END`.

  | form, none in flight | plain gets | function-based gets |
  |---|---|---|
  | split read, two arms (READ COMMITTED inline) | 9.00 | 4.00 |
  | gate probe alone | 6.00 | 1.00 |
  | gated key read (SNAPSHOT inline) | 9.00 | 4.00 |
  | first write of a row, NULL to 3 | 11.76 (1,133 B redo) | 5.57 (569 B) |
  | second write, 3 to 3 | 5.41 (494 B) | 5.41 (494 B) |
  | insert at state 3 | 6.28 (1,011 B, 22 µs CPU) | 3.17 (674 B, 10 µs) |
  | delete | 8.15 (832 B) | 5.12 (600 B) |
  | split read / gate / gated read, 100 in flight | 9.00 / 3.00 / 6.01 | 5.00 / 1.00 / 4.01 |
  | split read / gate / gated read, 10,000 in flight | 9.05 / 3.00 / 6.01 | 7.05 / 2.00 / 5.01 |

  The plain index is 768 blocks (blevel 2, 710 leaf blocks) for 300,000 rows, the function-based one
  48 blocks allocated and 0 leaf blocks while nothing is in flight. Against the tenth run's
  per-statement gets on the inline cells, the saving a NewOrder is about 95 gets at steady state
  (ten item reads at 5 each, the customer and warehouse reads, thirteen inserts at 3 each) and about
  160 in the first-write transition (the ten stock rows' entry moves at 6 each), of about 300 against
  the vendor's 210; at SNAPSHOT the gates' two probes a statement fall from 8 gets on a 4-level index
  to 2. The redo of a first write halves and an insert's falls by a third, which bears on the inline
  cell's 17.2 KB of redo a commit against the vendor's 7.7. The decoupled layout gains the gates and
  its metadata inserts only, its backfilled rows already at state 3. To design if the user takes it:
  the index's two expressions per key column on Oracle (hidden virtual columns, no storage, absent
  from `ALL_TAB_COLUMNS` and `getColumns`); every in-flight predicate spelled on the expression (the
  split read's in-flight arm with the key conjuncts likewise, the gates, the coarse SERIALIZABLE
  check, the setup's and `check`'s stuck-row counts, the backfill's remaining count); the `INDEX` and
  `NO_INDEX_SS` hints unchanged; the setup's assumed-in-flight statistics moved to the hidden column
  and the index, and the check's column-order rule reading `ALL_IND_EXPRESSIONS` for the key order; a
  table whose in-flight index is the plain one counts as set up but not rebuilt until asked, as the
  key-order rule treats an older order. Sent to 6e for the user 2026-10-30.
- 2026-10-30 (Fable): the backfilled metadata row's growth reproduced on the container (scratchpad
  `ora/growth.jsh`, `growth36.jsh`): two metadata tables of 1,000,000 rows at state 3, version 1,
  one stamped with the backfill's 24-character `tx_id`, the other with a 36-character one; then the
  first real write of the first 200,000 rows (a 36-character `tx_id`, the version and both stamps
  changed) and 50,000 key reads of written rows. With the 24-character sentinel the table grew from
  7,168 to 7,808 blocks, the reads fetched 11,996 continued rows (one in four written rows migrated)
  at 4.24 gets a read against 4.00 on unwritten rows, and the first write cost 5.03 gets and 501 B of
  redo; with the 36-character one the table stayed at 9,216 blocks (12 bytes a row larger at rest),
  the reads fetched 465 continued rows at 4.01 gets, and the first write cost 4.09 gets and 392 B.
  So the backfill stamping a 36-character `tx_id` removes the growth; sent to 6e for the user.
- 2026-10-31 (item 1, Fable): the user took the function-based in-flight index ("yes, have 89 design
  it", via 6e; performance only, Oracle only; the 36-character sentinel, item 2's proposal, taken
  the same day on 6e's record ("yes to all four") and built by c7 on its own branch, reviewed
  separately). Designed in section 3 (the paragraph "Oracle's in-flight index is function-based",
  after the Indexes paragraph; the setup paragraph's three sentences; the engine table's Oracle
  cell) and 4.2 (the spelling rule in the Oracle paragraph, after the `INDEX` hint sentence) and 4.4
  (the stuck-row contract). Verified on the container first (scratchpad `ora/dict.jsh`, `dict2.jsh`,
  outputs `dict.txt`, `dict2.txt`): `ALL_INDEXES.INDEX_TYPE` is `FUNCTION-BASED NORMAL` against
  `NORMAL`; `ALL_IND_COLUMNS` and ojdbc's `getIndexInfo` name the columns `SYS_NC00022$`,
  `SYS_NC00023$`, so today's key-order check would count the index as in another order and the setup
  would rebuild it every run; `ALL_IND_EXPRESSIONS` stores `CASE "TX_STATE" WHEN 1 THEN "K" WHEN 2
  THEN "K" END` whether the DDL spells the simple or the searched CASE, and a predicate in either
  spelling takes the index (access predicates on the hidden columns); the plain spelling against it
  is a full scan of the table, 3,068 gets and 11 ms at 300,000 rows with nothing in flight, hint or
  not, and the expression against the plain index an index full scan; the gather `FOR ALL COLUMNS
  SIZE REPEAT` covers the hidden columns (a frequency histogram on the state expression),
  `DBMS_STATS.SET_COLUMN_STATS` takes a hidden column by its quoted name and `SET_INDEX_STATS` the
  index, and an unlocked regather replaces both, as it does `tx_state`'s today; the hidden columns
  are absent from `getColumns` and `SELECT *`; a composite key's expressions take each column's type
  (VARCHAR2, TIMESTAMP(3), NUMBER); the move, `DROP INDEX` and `CREATE INDEX ... ONLINE` in the
  function-based form, 0.13 + 0.60 s on 300,000 rows; the keyed probe and a range gate on the key
  expression are index range scans on both columns (4 gets with 10,000 in flight), the state
  column's key set with the key expression in its select list is served from the index alone and
  joined to the primary key, and `check`'s stuck count and the backfill's in-flight count hinted
  cost 4,394 gets with 10,000 rows in flight (the rows read for their filter), 1 with none;
  unhinted, the stuck count scans the table (the statistics say 10,000). Design choices: the catalog
  carries the index's form and the driver spells a table's in-flight texts for it, so a table with
  the plain index keeps today's texts until its setup runs again (no flag day; the alternative, the
  expression spelling everywhere, would scan every table not yet rebuilt); the setup replaces a
  plain index as it rebuilds one in another order, and `check` reports the plain form as not set up
  with the reason, as the key order is reported; the `INDEX` and `NO_INDEX_SS` hints stay; the fixed
  statistics keep `tx_state`'s histogram and add the hidden columns' and the index's; both layouts
  and both levels through the same spellings (the index is on whichever table holds the state). Open
  beside it: item 2's `BETWEEN 1 AND 2` would be one token in the same spelling helper (on the
  function-based index it is a single range scan where `IN (1, 2)` is an `INLIST ITERATOR` of two, 2
  gets either way at 10,000 in flight); not taken here, the user's call. Handed to c7 to build on a
  branch; reviewed from `git show`; lands on the user's word; 6e plans the AWS proof run after.
- 2026-10-31 (item 2's proposal, the 36-character backfill tx_id, Fable's review): c7's d39b722 on
  backfill-tx-id-36 (one commit on 12afca4) reviewed from `git show`, mergeable. The constant
  `scalardb-native-backfill-placeholder` is 36 characters (counted), bound only at the backfill's
  stamp; nothing in the driver, the setup, check or the tests matches on the value or its length,
  so rows stamped with the 24-character value work beside the new ones and nothing accepts both;
  the setup test asserts every metadata row carries the constant and that it is a UUID's length;
  design.md names the new value in section 3's setup paragraph and the backfill bullet (lines
  added, no paragraph rewrapped, 38 fences); a changie entry. One nit, not blocking: the backfill
  bullet's replaced line runs to 113 characters; c7 took it, the branch's head now db7f450 (the
  line broken, nothing else, 38 fences). Lands on the user's word, as the others.

- 2026-10-31 (review of c7's a9412f8 on `oracle-fbi-inflight`, from `git show`): mergeable. Checked
  against section 3's paragraph, 4.2's rule, 4.4's contract and the hand-off's site list: the
  catalog's `INDEX_TYPE` column and `SetupObjects.inflightExpressions`, carried on `TableInfo` and
  spelled by `inflight`/`inflightKey` (`Writes.Names` wrapping them); every Oracle in-flight text
  spelled for the form: the split read's keyed `NOT EXISTS` and `DISTINCT` set (the key aliased back
  to its name), the in-flight copies (the condition, and their key listed as the expression, so that
  a conjunct the optimizer pushes into the copy matches the index: the inline SNAPSHOT 10-row read
  8.3 gets from about 11, form 1's probe count 3), the after images' join, the SNAPSHOT state
  column's probe and key set, D2's range gate through `KeyRange`'s spelling function, the batch
  block's gate, the clear gate, the coarse check, the D3 insert's in-flight count (its data-side
  read-back plain, as it must be); the key joins that read other metadata columns and the
  `COALESCE` row filters untouched. The setup: `createIndex` with every column an expression
  (`ONLINE`, the ORA-00439 fallback); the move on `KeyOrder.inflightMismatch` = `PLAIN_FORM` with
  its own message; the view's second arm spelled (the view is replaced on every run, so the move
  re-spells it); the backfill's two counts aliased and hinted; the statistics loop over
  `ALL_IND_COLUMNS` with `EXISTS` in `ALL_IND_EXPRESSIONS`, spliced before the lock so the
  as-if-backfilled `n` applies, the index's statistics only where an expression column was found.
  `KeyOrder.oracleExpressions` reads `ALL_IND_COLUMNS` left-joined to `ALL_IND_EXPRESSIONS` (the
  `LONG` read in order), parses Oracle's stored form and requires it to name `tx_state`; any plain
  column is `PLAIN_FORM`. `check`'s reason in `toString` and `--json`, its stuck count aliased,
  hinted and spelled; teardown by name unchanged; `setting-up-tables.md`, `concepts.md`, the
  changie entry. Noted, no change asked: `--plan` on a table still in the plain form counts the
  backfill's remaining rows with the expression spelling and the hint against the plain index, an
  index full scan, harmless. Design: 4.2's rule gains the copies' select-list spelling and section
  3 the ORA-08177 facts below (this note's commit); c7 rebases onto it and lands on the user's word.
  c7's ORA-08177 finding checked under concurrency on the container (scratchpad `ora/ser8177.jsh`,
  `ser8177b.jsh`, 300,000-row TI_P plain and TI_F function-based, 16 sessions at SERIALIZABLE each
  200 prepare/commit pairs of 10 disjoint rows): prepares failing with ORA-08177, plain 53%,
  function-based 52% (updates); inserts of new rows 60% against 66%; the commits 20–23% either way;
  with the in-flight index dropped 0.4% and 1.6%; `INITRANS 50` on either index no change (57%,
  56%); 4 sessions 52% and 55%. So the hot leaf is any in-flight index's on Oracle, the plain
  index's `(1, k)`/`(2, k)` entries at its left edge as much as the function-based index's whole
  content; the function-based form adds only the sequential empty-leaf split c7 found, retriable.
  Who meets it: ScalarDB's storage connections where they run Oracle's SERIALIZABLE, which ScalarDB
  requires for a virtual table there (`RdbEngineOracle.getMinimumIsolationLevelForConsistentVirtualTableRead`
  = 8) and which raised c7's error; not the driver's own transactions, whose writes touch no
  in-flight entry (a row's state stays 3). Pre-existing, not this commit's; filed as todo 366 for
  the user.

- 2026-10-31 (the branch heads after c7's rebase onto 7f49c7e, checked from git): `oracle-fbi-inflight`
  is 59bfa82, a9412f8's patch with only this log's entries merged (nothing changed in `driver/`,
  `tools/`, `.changes` or the two user docs, `range-diff`); `backfill-tx-id-36` is db934cd and a69a35b,
  patch-identical to d39b722 and db7f450; 38 fences at both heads, `merge-tree` finds no conflict
  between them. Both verdicts stand at these heads; the user's word names them.
