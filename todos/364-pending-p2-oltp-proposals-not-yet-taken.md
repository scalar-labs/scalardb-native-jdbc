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


- 2026-10-31 (c7, item 1 built on branch `oracle-fbi-inflight`): the catalog reads
  `ALL_INDEXES.INDEX_TYPE` in the listing it reads already and carries the form on `TableInfo`
  (`inflightExpressions`, with `inflight(engine, alias)` and `inflightKey(engine, alias, column)`;
  `Writes.Names` wraps them). Spelled for the form: the split read's keyed `NOT EXISTS` and its
  `DISTINCT` set, the in-flight copies (condition, and the key in their select list, so a key
  conjunct the optimizer pushes into the copy matches the index), the after images, the SNAPSHOT
  state column's probe and key set, D2's range gate (a `KeyRange` spelling for its conjuncts), the
  batch block's gate, the clear gate, the coarse check, the insert's in-flight key count. The setup
  builds the expressions, moves a plain index (`rebuilt in-flight index X in the function-based
  form`), spells the view's second arm and the backfill's two in-flight counts (hinted), and sets
  the hidden columns' and the index's statistics in the same block. `KeyOrder` reads Oracle's index
  from `ALL_IND_COLUMNS` joined to `ALL_IND_EXPRESSIONS`; `check` gives the reason (`in-flight index
  in the plain form` / `in another column order`), also as `reason` in `--json`. On the container
  every Oracle suite text that names the expressions (29) reads the in-flight index, none scans the
  decoupled metadata table; the inline 10-row read at SNAPSHOT with a row in flight elsewhere went
  from about 11 to 8.3 gets, its in-flight arm now reaching the rows by the key through the index.
  Found: one ScalarDB test prepare of 501 inserts at SERIALIZABLE right after a few small
  transactions fails with ORA-08177 every time on the function-based index (10/10 against 0/10
  plain, in plain SQL, scratchpad `s8177*.sql`): it splits the index's single, nearly empty leaf
  that those transactions changed; a retry gets through (third attempt). In a steady stream of 200
  prepares and commits on 2,000 rows the function-based index raised no more than the plain one:
  updates of 1, 10 and 50 rows 0, 0, 0 against 0, 0, 1; inserts of 1, 10 and 50 rows 0, 4, 10
  against 1, 6, 16. The test retries its prepare as ScalarDB's conflicts are retried.

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
- 2026-10-31 (both branches on main, checked from git): c7 fast-forwarded them on the user's "land it";
  main went 2625a84 → 36dfb48 (the 36-character backfill tx_id) → d4cd5eb (its design.md nit) →
  c4a7fcf (the function-based in-flight index). `range-diff` reads 36dfb48 = db934cd, d4cd5eb =
  a69a35b and c4a7fcf = 59bfa82, the reviewed patches unchanged; 38 fences at c4a7fcf; nothing pushed.
  6e told the index is on main for the AWS proof run (the expected figures in design section 3).
- 2026-10-31 13:49 (6e): the Oracle proof run 20261031-1349-ora started on the user's word from c4a7fcf,
  `bench-env/plans/overnight-oracle.json` (the tenth run's matrix), its own stack, expected done about
  19:10; 6e sends the results, the per-statement gets and the continued-row counts for the reading
  against the expectations above.
- 2026-10-31 (the proof run 20261031-1349-ora, c4a7fcf, read against the tenth, 20261029-2333-ora;
  the reading sent to 6e for the benchmark doc's eleventh-run section): the index did on the server
  what the container predicted. Inline RC TPC-C (c), gets a call tenth → eleventh (vendor): the routed
  key reads 7.0 → 4.0 (3.0) on item and 10 → 5 (4) on the 960K-row tables, Delivery's gate probe 6.0 →
  1.0, the stock UPDATE batch 71.2 → 51.4 (40.3), the order_line INSERT batch 12.5 → 7.7 (6.6), the
  inserts and first writes 3 to 6 fewer each (no index maintenance); NewOrder about 246 → 180 gets
  against the vendor's 152, 70% of the excess gone. Redo 17.2 → 11.7 KB a commit (SNAPSHOT inline
  16.9 → 11.3) against 7.7, as section 3 expected; backends' CPU excess a commit 0.81 → 0.60 ms (RC
  inline), 0.51 → 0.24 (SNAPSHOT inline). Throughput ratios RC inline 0.71 → 0.75, SNAPSHOT inline
  0.83 → 0.87, decoupled 0.59 → 0.61 and 0.67 → 0.68, short of the expected 0.8 / 0.9: the vendor
  ran 4 to 10% slower on this stack, the driver flat, so the ratio gains are partly the stack's; the
  gets were a tenth of the driver's 2 ms transaction, and the remaining 0.6 ms a commit of server
  time over the vendor is per-statement CPU at near-equal gets (item read 10.2 vs 4.8 µs at 4 vs 3
  gets, the stock stamp batch 91 vs 41 µs for 10 rows). Continued rows: the 36-character sentinel
  took the decoupled YCSB A / F from 85,880 / 53,304 to 4 / 4 (RC); inline TPC-C 173K unchanged, the
  unbackfilled first writes. Proposed to the user via 6e, in order: the JFR of the RC inline TPC-C run
  for the client's share; the routed read's text cost measured on the container; the RC stamp batch's
  per-element conditions; PCTFREE or the backfill for the inline growth.
- 2026-10-31 (the eleventh run's server excess sized on the container; proposals via 6e, the user's
  call; scratchpad `ora/osa2.jsh`, `osa4.jsh`, `osa5.jsh` and their `.txt`, tables OIT item-shaped 100K
  rows and OLT order_line-shaped 300K rows, function-based in-flight indexes, 50 rows in flight):
  (a) the READ COMMITTED key read on an inline table is the two-arm split, the committed copy UNION
  ALL the in-flight arm through the index, whose arm is an INLIST ITERATOR of two probes (states 1
  and 2) plus a UNION-ALL row source: at AWS 10.2 µs and 4 gets against the vendor's 4.8 and 3 on
  item; on the container (medians, cpu µs / gets a call) the vendor 4.6 / 3.00 (OIT), 5.4 / 4.00
  (OLT); the split as deployed 6.6 / 5.00, 7.2 / 6.00; the arm with BETWEEN 1 AND 2 (item 2 above)
  6.8 / 4.00, 7.1 / 5.00, one probe instead of two but its plan makes the key a filter under the
  state range (`access SYS_NC18$ >= 1 AND <= 2, filter SYS_NC19$ = :2`), so it scans every in-flight
  entry of the table and is not for a keyed arm, and moot for the gate, which stops at its first
  row; the CASE copy, `rc_T`'s form for the inline layout as one derived table, each non-key column
  `CASE WHEN COALESCE(t.tx_state, 3) IN (1, 2) THEN t.before_c ELSE t.c END`, the keys plain, the
  anchors the same CASE on `tx_id` and `tx_version`, `WHERE COALESCE(t.tx_state, 3) NOT IN (1, 2) OR
  t.before_tx_id IS NOT NULL OR t.before_tx_version IS NOT NULL`, 5.2 / 3.00 and 5.6 / 4.00: the
  vendor's gets, within 0.6 µs of its CPU, exact for a predicate that references only key columns
  (there P = P_before, both arms select the same rows by the same key, the arm's filter is the OR
  term, the before image's identity is the anchor as before); the in-flight key's before image read
  alike by both forms. Proposal: on an inline table at READ COMMITTED a read whose predicate
  references only the table's key columns takes the CASE copy in place of the two arms, Oracle first
  (PostgreSQL's inline the same form once measured); item, stock, warehouse, district, customer by
  id, oorder, new_order, order_line by order are such reads, about 20 a NewOrder, 100 of its 200 µs
  of server CPU over the vendor's. (b) The same for an anchor-less aggregate with a key-only
  predicate: Delivery's SUM over an order, the gated statement as written (4.2) on the container
  8.6 µs / 6.15 gets against the vendor's 6.6 / 4.15, the gate evaluated once whether inside MAX()
  or as an outer column (8.3 / 6.15), and a gate of 1 then costs the split read again (8.4 / 10.0, a
  second round trip); the CASE copy 7.2 / 4.15 exact in one statement (223.4 the vendor's sum with
  the in-flight amount, 123.4 the copy's and the split's), no gate, no fallback. At AWS that read
  cost 15.5 gets against the vendor's 4.2 (the tenth 20.2), which the container's gated form does
  not explain: the run's plans section holds the top texts by elapsed only; ask the harness for the
  plans of every driver text above 10 gets a call. (c) The write side: the RC inline stock stamp
  UPDATE has the vendor's plan (INDEX UNIQUE SCAN, TABLE ACCESS BY INDEX ROWID) and costs 51.4 gets
  and 91.5 µs a 10-row batch against 40.3 and 41.3; the anchored WHERE (DECODE and two COALESCEs) and
  the five stamp columns are trivial, so the excess is the first write's row growth on an
  unbackfilled imported row (tx columns NULL to a 36-character tx_id and three numbers, the row
  migrating when its block is full; 173K continued-row fetches a 60 s run, unchanged by the index;
  redo 11.7 against 7.7 KB a commit): proposal, an A/B of the inline cells with the harness's
  backfill on, as it backfills the decoupled cells, to size the growth's share (expected: the stock
  batch toward 42 gets and 55 µs, the continued rows toward 0, redo down), and a setting-up-tables
  note that an inline table's first writes grow its rows unless backfilled or loaded with PCTFREE.
  Also: the ofa-oracle instance had died this morning (PMON, ORA-471 after a clock jump) and was
  restarted; the scratch tables survived.
- 2026-10-31 (the user's word, "yes, 1 and 2", via 6e; "yes" to me for 366 first with the CASE copy
  beside it): items (a) and (b) above designed on main as 31ec14c, design 4.2 "The key-only read on an
  inline table at READ COMMITTED as the CASE copy", one paragraph for the key read and the anchor-less
  aggregate, the 19c clause included; handed to c7 for its own branch (SplitRead's copy choice, the
  key-only test beside `keyConjuncts`, the gated form's selection; suites on 23 and 19c; rebase onto
  the 19c branches or they onto this). Item (c), the inline backfill A/B, and the harness's plans
  capture are 6e's to put to the user after 1 and 2 land. Todo 366's option 1 runs on the container
  meanwhile (`ora/ser366.jsh`).
- 2026-11-01 (review of c7's db5a464 on `inline-rc-case-copy`, from `git show`): mergeable. `SqlAst.keyOnly`
  is the design's test and conservative where it cannot tell (a subquery in WHERE or ON, a NATURAL or
  USING join, an unqualified column of a join without every table's columns known, a reference in a
  subquery); `Router.caseCopies` takes the copy on Oracle at READ COMMITTED for an inline reference in
  a top-level inner place, and the gate only where every ScalarDB table of the statement is a copy;
  `SplitRead.caseCopy` spells the text as 4.2 has it, the anchors the before image's identity for a
  row in flight through the same CASE. Two things the build taught the design, now in the paragraph:
  the nullable side of a LEFT JOIN keeps its pieces (one copy turned the join inner,
  `ReadIntegrationTest`'s `imp LEFT JOIN made` lost its unmatched rows), and a predicate naming no
  column of the table is key-only. Tests: the new OLTP test (the lookup's text and before image, the
  key-prefix range without the in-flight insert, the SUM in one round trip without a gate, the
  non-key predicate's arms, the join's mixed copies, the item read at the vendor's 3 gets with 50
  items in flight), form 6 and 7's expectations, ReadIntegrationTest's Oracle inline split text, the
  AST unit cases; green on Free 23 (CatalogIntegrationTest's 15 failures are c7's reset script's,
  unchanged). One nit, not blocking: the copy carries no `NO_INDEX_SS` hint; on a table still on the
  plain in-flight index the committed copy's reason for it (the sixth run's skip scan) applies to the
  copy too, so the hint belongs there where `inflightExpressions` is false. 19c not run: plain SQL,
  the run follows with 33's workflow once both land. Lands on the user's word in c7's session.
- 2026-11-01 (c7's 9aabeb0 on top of db5a464, from `git show`): the nit folded in, the copy carrying
  `/*+ NO_INDEX_SS(t "T_inflight") */` where `inflightExpressions` is false, as the committed copy
  does, nothing on the function-based form; the new test's tail rebuilds ccit's index plain and reads
  item 520 through the copy with the hint on a fresh catalog (the in-flight transactions are rolled
  back by then, so it reads the committed price: a smoke test of the text, not of a row in flight,
  fine). The verdict carries to 9aabeb0; the design's rule sentence now says "no hint but the
  committed copy's on the plain form". Lands on the user's word in c7's session.
- 2026-11-01 (the CASE copy on main): c7 landed 5e1da62 + 13d3307 by fast-forward on the user's "land
  it" in c7's session, rebased onto f4f69ec; `git range-diff efc47ae..9aabeb0 f4f69ec..13d3307` shows
  both patches identical to the reviewed db5a464 + 9aabeb0, and the code diff between 9aabeb0 and
  13d3307 outside docs and todos is empty. Not pushed. 6e told: a run from this main shows the
  copy's effect (expected: RC inline toward 0.8, the item read at the vendor's 3 gets), and item 3
  (the inline backfill A/B) and the harness plans capture can go to the user. The 19c run of the copy
  follows with 33's workflow.
- 2026-11-01 (6e, the twelfth Oracle run `20261101-1528-ora` from `4804b71`, inline TPC-C, fast,
  RC and SNAPSHOT, each backfilled and not): the copy as predicted. At RC inline the item read went
  4.0 → 3.0 gets (the vendor's 3.0), 10.2 → 8.5 µs; the customer, oorder and warehouse key reads lost
  a get and 2–4 µs each; the server's active time a commit fell 1.73 → 1.60 ms; (c)/(a) 0.75 → 0.79.
  Delivery's SUM is the copy on the vendor's plan (PK range scan), 14.0 gets against the vendor's
  4.2. The 10 extra gets are its 10 rows' forwarding pointers: Delivery only delivers loaded orders
  in a 60 s run, and an unbackfilled inline row grows and migrates at its first write (184,000 to
  201,000 `table fetch continued row` a run against the vendor's 1,400 to 2,300). Item 3 answered:
  backfilled, the rows migrate during the setup instead (543,000 to 588,000 a run). The order_line
  UPDATE then falls 62 → 33 gets, but the SUM stays at 14 and the stock batch rises 52 → 54.
  Throughput −2 points at RC, +3 at SNAPSHOT, within the spread, so the default (off) stands.
  Proposed to the user: a harness cell `inline-moved` (backfill, then `ALTER TABLE ... MOVE` and the
  indexes rebuilt) for the steady state of rows inserted through the driver, and a docs note for
  inline tables imported with data (backfill and `MOVE` once before going live, or a larger
  `PCTFREE`). The new plans capture works: 23 plans in each RC inline run's server.txt.

### 2026-11-01 (night): the decoupled layout's next items, measured by statement on the container

The user's priority via 6e: the decoupled layout first. From 6e's V$SQL figures of the eleventh run's
RC decoupled TPC-C (20261031-1349-ora, 0.61; CPU a call, driver against vendor): the 10-row stock
write block 374 µs / 131 gets against 42 / 40 (inside it the data UPDATE `qa1` 141 / 80, the
metadata CAS `qa2` 149 / 50, the block's own 84); order_line's insert block 151 / 45 against 27 (the
metadata INSERT `k2` 69 / 38) and Delivery's update block 155 / 79 against 30 (the metadata stamp
`g3` 64 / 51); the one-row blocks 64–107 against 8–19; the keyed reads stock 17.5 / 9 against 7.9 / 5
and item 15.3 / 7 against 4.6 / 3. A NewOrder's server CPU adds up to ~1,130 µs against ~270. At
SNAPSHOT decoupled (0.68) the reads are near the vendor's (stock's locking read 10.0 / 5 against
8.0 / 5, item 5.4 / 3 against 4.7 / 3) and the gap is the write blocks (stock 259 / 92, the `k3` MERGE
stamp 121 / 51; order_line 105 and 131; one-row 59–99).

Mirrored by hand on the container (scratchpad `ora/dx366*.jsh`, `ora/fbi366*.jsh`; ST 3.2M rows with
`ST_tx_metadata`, a function-based in-flight index added; forms alternated per round, warm cache,
medians; CPU µs / gets a call):

- Keyed reads at READ COMMITTED: vendor 4.7 / 4; the committed copy alone (the LEFT JOIN to the
  metadata row for the anchors) 6.1 / 8; the driver's form, committed copy UNION ALL in-flight copy,
  8.3 / 10; the CASE copy over the join 6.6 / 8; an anchor-less read with the gate column 6.5 / 6. So
  the anchor join is +1.4 µs and +4 gets (structural at this level: the write's CAS needs the version
  read, as ScalarDB's own READ_COMMITTED prepare does), and the in-flight branch +2.2 µs and +2 gets,
  which the CASE copy removes (−20% of the read's CPU).
- One-row write block as deployed: 40.2 / 20.8, its data UPDATE with the `EXISTS` gate and RETURNING
  11.2 / 10, its metadata CAS 18.6 / 10.9, the block's own ~10; without the gate 36.0 / 17.1 (the DML
  9.6 / 6.0); without the anchor conjuncts 34.0 / 21.0. The gate is 4 gets and ~1.6 µs of the DML,
  ~10% of the block; the stamp is the largest piece and structural. In the 10-row block the gate is 41
  gets of the data DML's 83 (its CPU 62 → 35). Dropping RETURNING for the bound keys measured worse
  through a PL/SQL loop and is not an item.
- The in-flight index's maintenance on a stamp (the index evaluated for the old and new row, no
  entry changing): alternated per round, the setup's 3-expression index against none, one row 14.6
  against 13.5, a 10-row FORALL 84.6 against 60.8 (its UPDATE 56.8 against 49.7): ~1–2.4 µs a row.
  Not an item; the index's probe and first-write gains stand. (An earlier, non-alternated pass had
  suggested 15 µs a row; that was drift between campaigns, hence the alternated rerun.)
- Migrated rows: 20% of the container's `ST_tx_metadata` rows chain (the table's history); its gets
  a stamp (10–14) are not the AWS table's (5), whose backfill writes the 36-char tx_id.

Proposals, the user's call, in the order recommended:

1. **The metadata table's primary key in the data key's order** ((C), 2026-10-28, with figures now):
   order_line's metadata PK is (OL_D_ID, OL_NUMBER, OL_O_ID, OL_W_ID), so a NewOrder's 10 metadata
   inserts land in 10 leaves (`k2` 38 gets) and a Delivery's 10 stamps probe 10 leaves (`g3` 51 gets,
   ten times a Delivery); in the data order (W, D, O, NUMBER) they share one or two leaves, ~10 gets
   each and less redo. Both levels, both of TPC-C's slowest decoupled transactions. Two ways, not
   exclusive: the ScalarDB fix, `JdbcAdmin.getImportTableMetadata` taking the key columns by KEY_SEQ
   (the user can add it to the ScalarDB feedback), and the setup rebuilding the metadata PK's index in
   the data order where it differs (Oracle enforces a PK with an index whose columns are the key's in
   any order, `USING INDEX`), the setup's check reading the order; measured on the container first
   (an ORDER_LINE-shaped pair), then the AWS cell.
2. **The CASE copy for decoupled keyed reads at READ COMMITTED**: 4.2's inline CASE copy over the
   LEFT JOIN to the metadata row, `CASE WHEN COALESCE(m.tx_state, 3) IN (1, 2) THEN m.before_c ELSE
   t.c END` per column and the before anchors the same way, the in-flight branch and its index probe
   gone; exact as the inline argument, plus: a ScalarDB delete in flight keeps the data row until its
   commit (the prepare is a Put at state 2 with the before image), so the join from the data table
   finds every in-flight row that has a before image. −2 gets and ~−20% CPU a keyed read, 20 reads a
   NewOrder. READ COMMITTED only.
3. **The RC decoupled blocks' data DML without the metadata `EXISTS` gate**: the metadata CAS carries
   the same conditions and decides; on `stamped < written` the block rolls back to a SAVEPOINT taken
   at its start and reports the counts, and the Java side runs the entries through the single form as
   it does today for a count of 0. Semantics as today (the conflict and in-flight cases end in the
   same wait or abort, through the CAS instead of the gate); −4 gets a written row (−40 of the stock
   block's 131 on AWS), ~−10% of a block's CPU. READ COMMITTED only.
4. Not proposed: the anchor join at READ COMMITTED (ScalarDB parity), the in-flight index's upkeep
   (measured small), the PL/SQL block's own cost (the round trip it saves is worth more), RETURNING
   for bound keys.

Sent to the user and to 6e (todo 329's record).

### 2026-11-02: item 1 measured on the container (facts for the user's pick; nothing designed)

6e's relay of the previous night ("yes to all three") was withdrawn the same night: the user's "Can
you give me the three?" asked for the list, not for the build. Nothing of items 1–3 is designed or
sent to c7; the measurement below was run before the correction and stands as input to the pick.

An ORDER_LINE-shaped metadata table of 300K committed rows (W 1..10, D 1..10, O 1..300, N 1..10;
scratchpad `ora/key366.jsh` and `ora/key366b.jsh`), three copies: A as ScalarDB's import and today's
setup leave it, the primary key (D, N, O, W) by column name plus the key index `T_key` (W, D, O, N);
C the import alone, no key index; B after the proposed conversion, a unique index (W, D, O, N) built
ONLINE, the import's primary key dropped with its index and added again USING INDEX that one, no
second key index. All three carry the function-based in-flight index.

- The conversion on 300K rows: `CREATE UNIQUE INDEX ... ONLINE` 0.4–0.7 s, `ALTER TABLE ... DROP
  PRIMARY KEY DROP INDEX` 35–68 ms, `ALTER TABLE ... ADD PRIMARY KEY (W, D, O, N) USING INDEX ...`
  90–97 ms; uniqueness never lapses, the unique index holds it between the two. Oracle also accepts
  the constraint written in the catalog's order over an index in the other (133 ms), but
  `getPrimaryKeys` then reports the constraint's order, so a setup would write the constraint in the
  data order and its check would read it there by `KEY_SEQ`.
- Two campaigns (6 rounds x 150 calls with the three copies always in the same order, then 9 x 150
  with the forms rotated one a round), CPU µs / gets / redo bytes a call of ten rows:
  - k2, the FORALL INSERT of an order's ten metadata rows: A 39.6–71.4 / 38.7–38.9 / 7.0 KB; C
    22.1–22.6 / 33.5–33.6 / 4.2–4.4 KB; B 19.6–20.8 / 7.7–9.0 / 2.9–3.9 KB. The second index costs
    more CPU than the scattered order does; the data order pins one leaf for the ten rows.
  - g3, the FORALL stamp UPDATE by full key: A 66–71 / 52; C 54–77 / 52; B 64–69 / 42. Ten gets, no
    CPU to speak of: the probes are unique scans either way.
  - the (W, D, O) prefix count: A 5.4–6.6 / 3.0 (`T_key`), C 33–35 / 23 (the skip scan, the (C)
    finding), B 5.5–5.6 / 3.0 (the primary key). B keeps what `T_key` bought.
  So item 1's gain is the insert: against today's layout CPU −45% or more, gets 39 → 8, redo −45 to
  −60% per ten rows; AWS's k2 at 38.4 gets a call (the eleventh run) matches A's 38.7. The stamp's
  "51 → ~10" in the proposal was wrong: g3 keeps ~42 gets, its probes being unique scans.
- A fact that changes item 1's shape: `IGNORE_ROW_ON_DUPKEY_INDEX(x (cols))` resolves the index by
  the columns in the index's own order; `(D, N, O, W)` over an index (W, D, O, N) is ORA-38913 "Index
  specified in the index hint is invalid". The driver spells the catalog's order at its three insert
  sites (`Writes` 2931, 5987, 10638), so the conversion alone would break the decoupled inserts: item
  1 needs the hint naming the index, `IGNORE_ROW_ON_DUPKEY_INDEX(x "name")`, honored in the stored
  spelling (a lower-case misspelling is ORA-38913 too), the name learned by the catalog with the
  setup's objects (the metadata table's primary key index from `ALL_CONSTRAINTS`, or the setup's
  unique `T_key` when listed), the columns in the catalog's order where none is known; a catalog
  loaded before a conversion run beside the application spells the old form until it refreshes.
- MySQL and MariaDB key the metadata table by name too (section 3); their primary key is the
  clustered index, so the same rule there is `ALTER TABLE ... DROP PRIMARY KEY, ADD PRIMARY KEY (the
  data order), ALGORITHM=INPLACE, LOCK=NONE`, a table rebuild, not measured (no container up).
- With ScalarDB's own fix (the import by `KEY_SEQ`), a new import's catalog order equals its primary
  key's and the driver's hint as spelled today is right; tables imported before it keep the by-name
  key and need the conversion.

## Design of items 2 and 3 (2026-11-02, for c7)

The user's pick, typed in 6e's session and relayed 2026-11-02: "For 1, it should be fixed in
ScalarDB. I will ask the team, so can you give me the summary of the issue? For 2 and 3, they look
good if they only improve performance." Item 1 is not built here; 6e writes the ScalarDB issue from
the facts logged above (the import by `KEY_SEQ`; the measured insert cost of the by-name key; the
hint's column order). Items 2 and 3 are designed below under the user's condition: the same rows,
outcomes, waits, aborts and errors as today, performance alone; anything that would change one stops
and goes back to the user through 6e before it lands. Each is one branch of c7's, reviewed here from
`git show`, landing on the user's own word in c7's session; 6e takes the AWS READ COMMITTED and
SNAPSHOT decoupled TPC-C cells when the second is on main. Item 2 first: the larger gain.

### Item 2: the CASE copy for decoupled keyed reads at READ COMMITTED (branch `decoupled-rc-case-copy`)

Design 4.2: the new paragraph "The key-only read on a decoupled table at READ COMMITTED as the CASE
copy over the metadata join", and the todo 337 sentence above it amended (on Oracle the join is
cheaper by the in-flight arm's probe; on PostgreSQL it measured the same).

1. `Router.caseCopies`: admit a reference of a decoupled table under the inline tests (Oracle, READ
   COMMITTED, `Place.INNER`, `SqlAst.keyOnly`) and one more, the reference in `own`, a read that
   records anchors; an anchor-less decoupled read keeps 4.2's gated statement as written, whose gate
   column costs less than the join (6.5 µs / 6 gets against 6.6 / 8). `own` is built before
   `caseCopies` is called (`Router` ~1934).
2. `SplitRead.caseCopy`, a decoupled table's form: `FROM <data> t LEFT JOIN <meta> m ON <keyJoin(m)>`;
   the in-flight condition `COALESCE(m.tx_state, 3) IN (1, 2)`; a non-key column `CASE WHEN <in
   flight> THEN <t.beforeImage(engine, "m", c)> ELSE t.c END AS c`, a key column `t.k`; the versions
   `CASE ... THEN m.before_tx_id ELSE m.tx_id END AS <OWN>0`, the same for `tx_version` as `<OWN>1`,
   `m.tx_state AS <OWN>2` with `v.state`; `WHERE COALESCE(m.tx_state, 3) NOT IN (1, 2) OR
   m.before_tx_id IS NOT NULL OR m.before_tx_version IS NOT NULL`; no hint (the inline copy's
   `NO_INDEX_SS` names an inline table's plain index; the decoupled committed copy carries none).
   `emit` already reduces a `caseCopies` reference to one COMMITTED piece; `keyRange` (PostgreSQL)
   untouched; `gated` drops its gate only where every ScalarDB table of the statement is a copy, as
   today.
3. Tests. Keep: the READ COMMITTED decoupled read cases of `WriteIntegrationTest`,
   `MoreWritesIntegrationTest` and the imported-table suites: a row in flight read as its before
   image by key with (`before_tx_id`, `before_tx_version`) as its anchor, an in-flight insert
   invisible, a ScalarDB delete in flight read as the row before it, an imported row without a
   metadata row read with NULL anchors and stamped by its later write (todo 360). Add: the copy's
   text for a decoupled key lookup and for a key-prefix range (Delivery's `(W, D, O)`), the
   anchor-less decoupled key read still the gated statement as written, the two arms kept for a
   non-key predicate and for the nullable side of an outer join, and `OracleOltpIntegrationTest`'s
   decoupled item read at 8 gets (the two arms 10). 19c runs the copy as 23 does (plain SQL); 33's
   workflow after.
4. Expected: each decoupled keyed read at READ COMMITTED −2 gets and about −20% CPU (container 8.3 /
   10 → 6.6 / 8; AWS stock 17.5 / 9 and item 15.3 / 7), about twenty reads a NewOrder; the READ
   COMMITTED decoupled TPC-C cell from 0.61 toward 0.64–0.66.

### Item 3: the READ COMMITTED decoupled blocks' gate as the in-flight probe (branch `decoupled-rc-inflight-gate`)

Design 4.3's decoupled bullet of "The keyed write at READ COMMITTED on Oracle" and 4.4's "The READ
COMMITTED decoupled entries" amended. Measured first (`ora/dx366d.jsh`, the stock-shaped pair, 8
rounds alternated, CPU µs / gets a call): the one-row block as deployed 38.0 / 15.70 (its DML 15.8 /
10.14, its CAS 9.2 / 5.56), with the in-flight probe as the gate 39.4 / 13.97 (DML 11.8 / 7.99),
without any gate 41.0 / 12.02 (DML 13.1 / 6.00), the vendor 10.7 / 6.0; the ten-row block as
deployed 340.8 / 188.6 (DML 68.9 / 88.2, CAS 215.3 / 100.9), the probe gate 327.7 / 169.0 (DML 40.8
/ 54.2), no gate 266.6 / 142.1 (DML 38.0 / 41.9), the vendor's array UPDATE 28.1 / 42.1. The form
without a gate, the proposal as first written, is not designed: it writes and locks a row in flight
or at another version before the CAS tells, which section 6 forbids (the READ COMMITTED wait holds
no lock), opens a deadlock against a ScalarDB record commit or rollback that reaches the metadata
row first, and turns the abort of a prepare that lands between the DML's read and the CAS into a
wait. The probe gate keeps every outcome and every lock as today, at about half that gain: a third
of the block's gets saved on the container becomes a tenth, the CPU a few percent.

1. `Writes.rcFirstText`, the decoupled text (~3240–3262): the DML's gate `AND EXISTS (SELECT 1 FROM
   T_meta m WHERE <key> AND COALESCE(m.tx_state, 3) = 3 <anchor>)` becomes `AND NOT EXISTS (SELECT
   /*+ INDEX(m <T_inflight>) */ 1 FROM T_meta m WHERE <t.inflight(engine, "m")> AND
   <inflightKeyJoin>)`, the spelling of `SplitRead.committed`'s `NOT_EXISTS` check with the hint of
   4.2's gate column (on the plain-form index a range scan, as the gate column's is); the anchored
   and plain texts share it, the anchor staying in `metaCas` alone. `SAVEPOINT scalardb_native_r;`
   after `BEGIN`. After the CAS: `IF l_written = 1 AND l_stamped = 0 THEN SELECT COUNT(*) INTO l_f
   FROM T_meta m WHERE <in flight> AND <key = l_k...>; IF l_f = 0 THEN ROLLBACK TO scalardb_native_r;
   l_written := 0; END IF; END IF;`. OUT `written, stamped` as today; `applyRcFirst` unchanged: 0 →
   null → the block of steps decides, a moved version or a missing metadata row told as today; 1 / 0
   → the abort as today, the row having gone in flight after the DML's read.
2. `Writes.Bulk.buildRc` and the bulk DML's builder (~2544–2570, `onDataTable(... AND EXISTS ...)`):
   the same gate, without anchors in either text; `SAVEPOINT scalardb_native_q;` after `BEGIN`; after
   the CAS `FORALL`: copy `SQL%BULK_ROWCOUNT(j)` for `j IN 1 .. l_r` into a collection before any
   other SQL, then `IF l_s < l_r THEN` for each unstamped `j` a `SELECT COUNT(*) INTO l_x FROM T_meta
   m WHERE <in flight> AND <key = l_w*(j)>` summed into `l_f`; `IF l_f = 0 THEN ROLLBACK TO
   scalardb_native_q; l_r := 0; l_s := 0; FOR i IN 1 .. l_n LOOP l_o(i) := 0; END LOOP; END IF; END
   IF;`. OUT as today; `applyOracleBulkRc` unchanged: `l_s != l_r` only when a row went in flight,
   the abort as today; all zeros the entries' single forms as a count of 0 is today (the block of
   entries, then the keyed block), which write the undone entries once each with the same stamp and
   tell the others' outcomes as today. A created table's DELETE CAS (`DELETE FROM T_meta WHERE cas`)
   is handled the same.
3. Tests. Keep: `MoreWritesIntegrationTest`'s READ COMMITTED decoupled cases (`committedWhileItWaits`
   by key, `inFlightWait`, `batches`, `timeoutMidWrite`), `WriteIntegrationTest`'s decoupled writes,
   an imported table's key without a metadata row written and given its row (todo 360), two entries
   on one key, a version moved by another connection between the read and the write aborting with
   `40001` and today's message, the nested texts' parse counts. Add: the one-row and ten-row texts
   with the probe gate and the savepoint; a moved version through the one-row block (written, undone,
   the steps' abort) and through the ten-row block beside nine others (all undone, each then written
   once by the entries' forms: versions +1, not +2); a key without a metadata row through each block
   on an imported table (undone, then stamped by the steps); a key in flight through each (the gate
   closed, nothing written, the wait or the abort as today). Measured through the driver on the
   container before and after (`ClientCostIntegrationTest` or the dx scripts). 19c: `SAVEPOINT` and
   `ROLLBACK TO` inside PL/SQL as on 23.
4. Expected: −3 gets a written row (the stock block's DML from 80 toward 50 of the block's 131 gets
   on AWS), the block's CPU −4 to −10%; some 40–60 µs of a NewOrder. Small; the user may drop it.
