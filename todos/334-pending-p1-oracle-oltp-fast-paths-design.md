---
status: pending
priority: p1
issue_id: "334"
tags: [performance, oracle, design, oltp, isolation]
dependencies: ["328", "329"]
---

# Oracle: the design of the OLTP fast paths PostgreSQL has (todos 328, 329)

## Problem Statement

Every fast path of todos 328 and 329 is gated on PostgreSQL (21 places in `Router` and `Writes`).
On Oracle the driver runs the original step-by-step protocol of design 4.3 and 4.2: a keyed write is
the keys read, the DML, the CAS, a savepoint and its release, three to five round trips; a write
beyond the key repeats that per key; a batch runs entry by entry; a locking read is the in-flight
check, a savepoint, the lock, the versions read again and the release; every read takes two anchor
lookups per row at both levels. A BenchBase run on Oracle today would land about where PostgreSQL
stood before todo 328 (TPC-C 0.69 of the vendor at a 105 µs round trip, the sixth AWS run), likely
lower, since round trips are what multiply. The user wants the Oracle work designed first, on this
model, and implemented and measured in another session; the AWS matrix of todo 329 runs
PostgreSQL only meanwhile, and a further session is improving the driver's Oracle queries at the
micro-benchmark level (`docs/benchmarks/form-matrix-aws.md`), which this design must take into
account.

## Findings

What is known about Oracle in this repo, to build on rather than rediscover:

- **Levels.** The driver's SNAPSHOT runs at Oracle's SERIALIZABLE, which is snapshot isolation; the
  driver's SERIALIZABLE is refused on Oracle; READ COMMITTED is Oracle's own (design section 5,
  README "Isolation levels"). In autocommit the driver uses READ COMMITTED on Oracle because ojdbc
  at SERIALIZABLE commits after a write only, and a read in autocommit then fails with ORA-01453
  (design 4.x, "READ COMMITTED instead").
- **Writes on Oracle, as built** (milestone 8d1, design "Writes on Oracle at READ COMMITTED"): the
  driver's own SQL in Oracle's dialect, steps one by one; ORA-08177 ("can't serialize access") is
  mapped as `40001` and leaves the transaction rollback-only, since Oracle then fails its later writes.
- **The plan of todo 328 for one round trip per write:** Oracle has no data-modifying CTEs; the
  design names an anonymous PL/SQL block per write, results through OUT binds (design 4.3, "The other
  engines"; todo 328's list). Not built.
- **The clear-table argument of todo 329** (design 4.2 "A table found clear") rests on one snapshot
  per transaction and on a write to a row changed after the snapshot failing. Oracle's SERIALIZABLE
  gives both: one snapshot, and ORA-08177 on such a write. The argument should port; what differs
  is how Oracle reports the failure (statement-level error, transaction not necessarily aborted) and
  the autocommit exception above.
- **Reads without anchors at SNAPSHOT** (`Router.versionless()`, PostgreSQL only) rest on the same
  argument and should port with it.
- **The one-statement locking read at READ COMMITTED** (todo 329, `LockPlan.applyFastRc`) relies on
  PostgreSQL re-reading the newest row after a lock wait, which gives the "read now" anchor. Oracle
  restarts the statement in that case ("write consistency"), which should give the same anchor, but
  it has to be checked against Oracle's documentation and with a test, not assumed.
- **Inline-layout forms** (the stamp in the SET list, `fastKeyed`, `fastMerged`) are plain SQL and
  port syntactically, but `IS NOT DISTINCT FROM`, `RETURNING` into a result set and a VALUES list
  joined into an UPDATE do not exist on Oracle: `RETURNING ... INTO` needs PL/SQL or a collection,
  a merged batch needs `FORALL` or a block, and the anchored comparison needs `DECODE` or `LNNVL`
  forms. The decoupled layout's second row per write stays.
- **Measured on Oracle so far**, micro-benchmarks only (`docs/benchmarks/form-matrix-aws.md`,
  `read-overhead.md`, `inflight-check.md`): the keyset-paged read under the driver's fixed
  statistics (todo 094) at about a thousand times the vendor's, 37x on the keyset page; aggregates
  0.8x to 11x. These are the read side; no write form has been measured on Oracle.
- **Environment.** Locally, Testcontainers runs `gvenzl/oracle-free:23-slim`, under emulation on the
  arm64 laptop (slow; the Oracle suite ran here on 2026-10-26, 27 s for one test). On AWS the EC2
  tier's Oracle is Oracle Free (2 CPUs, 2 GB, 12 GB of data) unless an Enterprise image is set in
  `bench.tfvars`; the RDS tier had Oracle SE2 19. BenchBase has no Oracle build in `matrix.sh` yet
  (postgres profile only, dist path hard-coded).
- **The number to beat is not PostgreSQL's.** The target is (c) against (a), the vendor on plain
  tables, on Oracle; read the time added per transaction beside the ratio (todo 329's log).

## Proposed Solutions

Design each form before its SQL, in this order, each as a paragraph in `docs/design.md` beside the
PostgreSQL one, with the argument for its exactness and what Oracle does differently:

1. **Reads at SNAPSHOT without anchors, and the clear-table memory.** Port the argument; decide how
   ORA-08177 surfaces (per statement) and how the transaction is marked rollback-only; the
   autocommit case keeps READ COMMITTED.
2. **The keyed write in one round trip.** A PL/SQL block that reads the key's row (the view at READ
   COMMITTED, the table at SNAPSHOT), applies the DML with the stamp, CASes, and returns the keys'
   versions, written and stamped flags through OUT binds; or, in the inline layout at SNAPSHOT on a
   clear table, the vendor's UPDATE with the stamp alone, as on PostgreSQL. Decide what the client
   reads back and how a zero-row result is told apart.
3. **The write beyond the key**, the same block over the predicate's keys.
4. **Batches**: `FORALL` over the entries' keys inside one block, or the merged form of 4.4 where
   Oracle's SQL allows it; what a repeated key or a missing row does (undo to the savepoint, one by
   one, as on PostgreSQL).
5. **The locking read**: at SNAPSHOT the in-flight check and the lock in one round trip (a block, or
   two statements in one call), and at READ COMMITTED the one-statement form if Oracle's statement
   restart gives the newest version's anchor; verify first.
6. **The read side's known Oracle costs** from the micro-benchmarks, with the session that is
   working on them, so the two efforts do not cross.

Each form: the argument, the SQL, what the driver reads back, the tests that must keep holding
(`MoreWritesIntegrationTest.committedWhileItWaits`, the in-flight tests, `InlineIntegrationTest`,
`WriteIntegrationTest.readsItsOwnWrites_withinTheTransaction`, run on Oracle), and a measurement plan
(pgbench's Oracle equivalent is not available; time the forms through the driver on the local
container first, then on AWS once BenchBase has an Oracle build).

## Recommended Action

Write the design (sections 4.2 to 4.4 of `docs/design.md`, Oracle paragraphs) and this todo's log
first, one form at a time, starting with the SNAPSHOT read argument and the keyed write block, since
they carry most of a TPC-C transaction. Hand the implementation and measurement to another session
with the design text as its brief. Do not touch `bench-env/` or `BenchBaseBenchmark` while the
PostgreSQL matrix runs (todo 329, 2026-10-26); add the Oracle BenchBase build after.

The division of work (decided 2026-10-26): the design session, on Fable, writes for each form the
SQL or PL/SQL text, the columns the driver reads back, and a table of what the driver does for each
outcome (a key written and stamped, written and not, not written, in flight, missing, a duplicate),
so that implementing is translation; the implementing session, on Opus, writes the text builders
and their unit tests, runs the Oracle suite, measures, and sends any case the design leaves open
back to the design rather than deciding it, since the open cases are the ones where a mistake is a
silent lost update. Before the forms merge, the semantics-bearing parts of the implementation (the
result handling and the outcome rules, not the text builders) get one review pass on Fable against
the design text.

## Files

- `docs/design.md`: section 5 (engine mapping), 4.2 (reads, "A table found clear"), 4.3 (writes,
  "The vendor's statement with the stamp", "The other engines"), 4.4 (batches), section 6 (in-flight
  rows), the Oracle write paragraph ("Writes on Oracle at READ COMMITTED").
- `todos/328-*.md` (one round trip per write, the PL/SQL plan), `todos/329-*.md` (the log of what each
  PostgreSQL form gave and the measurements of 2026-10-26).
- `docs/benchmarks/oltp-overhead-status.md` (where PostgreSQL stands), `form-matrix-aws.md` (Oracle's
  read-side findings).
- `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Writes.java` (`fastKeyed`, `fusedKeyedInline`,
  `slimKeyedInline`, `LockPlan`), `Router.java` (`versionless`, `lockingRead`, `read`),
  `SplitRead.java`; `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java` for Oracle's
  dialect.
- `driver/src/testFixtures/java/com/scalar/db/nativejdbc/testing/Engines.java` (the Oracle
  container), `./gradlew :driver:integrationTestOracle`.

## Work Log

### 2026-10-26: forms 1 and 2 designed (Fable), design 4.2 and 4.3, Oracle paragraphs

**Form 1, reads without anchors at SNAPSHOT and the clear-table memory.** The PostgreSQL argument
rests on one snapshot per transaction and on a write to a row changed after it failing before the
CAS. Oracle's SERIALIZABLE gives both: the snapshot is fixed at the transaction's first statement,
a read included, and an UPDATE, a DELETE or a FOR UPDATE of a row changed and committed since
raises ORA-08177 at the statement that meets the row. Checked case by case which statement meets
the change: a ScalarDB prepare, commit of a delete, or another driver write changes the data row,
so the DML fails; a record commit, a rollback or a lazy recovery changes only the metadata row of a
row the snapshot shows in flight, so the gate stops before any write; the backfill of section 1
changes a metadata row alone, so the CAS fails, with ORA-08177 rather than zero rows. Two
differences from PostgreSQL, both conservative: Oracle raises ORA-08177 also when a block's ITL
cannot tell whether the row changed, and for a row a ScalarDB prepare rolled back after the
snapshot (changed twice, back at the version read), which PostgreSQL may write (todo 134). The
state column, the coarse check and the four ways of finding a table clear port as they are; the
probes read the in-flight index under the same hint the split read uses, since the fixed
statistics of todo 094 would send the optimizer elsewhere. Autocommit stays at READ COMMITTED on
Oracle (section 5), where nothing is remembered: unchanged.

**Form 2, the keyed write as one PL/SQL block.** The block is the three steps of 4.3 with the two
decisions between them moved to the server; the exactness argument is that each decision depends
on the earlier statements' results alone, except the comparison with an anchor the transaction
read earlier, which can come after the write because its failure is an abort (as on PostgreSQL's
fused statement). Decisions taken, and the alternatives weighed:

- **The block returns before writing and never raises on its own.** The alternative, raising an
  application error on every aborting outcome so that the block's statement-level rollback
  undoes the DML and autocommit could run in one round trip (ojdbc sends the commit with the
  execute), was set aside for the first version: the driver's abort handling stays the
  PostgreSQL one (mark rollback-only, the DML undone with the rollback), one code path, and the
  wait at READ COMMITTED needs a returned outcome anyway. Listed as a measured follow-up.
- **The anchor is compared by the driver after the block, from the version the block read**, not
  passed in. Passing it in needs the key from the statement's bound values, whose Java types need
  not equal the key as read (an Integer bound, a BigDecimal read), a silent miss; comparing after
  reuses `Anchors.unchanged` as the PostgreSQL forms do.
- **The row comes back as a `SYS_REFCURSOR` over `dual`**, not as scalar OUT binds. OUT binds
  for the key columns would need a JDBC type per column that agrees with what `readKey` recorded
  from a result set, the one place a mistake is a lost update (an anchor not found means
  `unchanged` is true); a cursor row goes through `readKey` itself. `DBMS_SQL.RETURN_RESULT`
  (12.1) would give the same row without a CallableStatement; the ref cursor is the older,
  certain mechanism, and the implementing session may switch if it measures the same.
- **The block's read is `T LEFT JOIN T_meta` by the key at both levels** (step 1's data-anchored
  surface), the row itself in the inline layout; a data row without a metadata row (an imported
  table before its backfill) returns to the steps, as PostgreSQL's gate does, rather than
  inserting the version-1 row inside the block with a DUP_VAL_ON_INDEX handler.
- **No savepoint around the block.** An unhandled exception fails the block as the client's one
  statement and undoes its work (statement-level atomicity, Oracle Concepts "Transactions";
  Kyte, Expert Oracle Database Architecture, ch. 8, the anonymous-block demonstration); the
  implementing session confirms it with a test on the container before dropping `Writes.apply`'s
  savepoint for this plan on Oracle.
- **The decoupled DML keeps no gate of its own** (`EXISTS` over the committed metadata row inside
  the UPDATE), which would rely on Oracle's statement restart re-evaluating a subquery after a
  lock wait: the thing form 5 must verify. Both are exact; the gate would only leave less locked
  on an abort.
- **The clear-table statement with the stamp is given for the inline layout only**; the
  decoupled one is the block without its read and comes with form 3, which needs the `FORALL`
  stamp over returned keys anyway.
- **The one-statement autocommit, and the cost of PL/SQL's context switches**, are measured, not
  designed: the local container's times are not absolute (emulation); round trips and the block
  beside the vendor's UPDATE on the same container, then AWS.

Oracle facts the two forms rest on, to be confirmed by the implementing session's tests rather
than assumed: (1) a block's statement-level atomicity; (2) ORA-08177 out of a PL/SQL block reaches
ojdbc with error code 8177 and SQLState 72000, which `oracleSerialization` matches; (3) a ref
cursor opened over `dual` on the block's variables is fetchable after the block ends; (4) `?`
placeholders bound twice in one block (the WHERE in the read and in the DML) and the cursor as the
last OUT bind on a `CallableStatement`. Facts relied on and not in question: the snapshot at the
first statement, ORA-08177 on a changed row at SERIALIZABLE, `RETURNING ... BULK COLLECT INTO` on a
multi-row DML, `SQL%ROWCOUNT`, `DECODE` as the NULL-safe comparison (already in the driver).

Left for the next design turns, in the todo's order: form 3 (the write beyond the key: the same
block with the keys read into collections, the `FORALL` CAS and `SQL%BULK_ROWCOUNT`, the rows back
through the cursor; the anchor check needs per-key rows, which a cursor over a local collection
would give and which needs `TABLE()` of a local type, 12.1, to be checked, or the keys' versions
re-read from the metadata table by the stamp's `tx_id`), form 4 (batches), form 5 (the locking
read, with the statement-restart verification), form 6 (the read side with the micro-benchmark
session). Nothing in `bench-env/` or `BenchBaseBenchmark` was touched.

### 2026-10-26: form 3 designed (Fable), design 4.3, "The write beyond the key on Oracle"

The predicate write is the keyed block with step 1 over the predicate's keys, step 2 as a `FORALL`
over them and step 3 as a `FORALL` over the keys written, with step 2's zero-row rule made inside
the block. The decision that shaped it: **the block returns one row of counts, no per-key rows.**
Oracle's `TABLE` operator takes a collection type declared in a package specification; one
declared in an anonymous block fails with ORA-22905 (12.1, oracle-base's article on the TABLE
operator with locally defined types, checked today), so a cursor over the block's own collections
is not available, and per-key rows would need SQL-level types the setup creates per table and the
catalog checks. Every per-key decision but one is a function of what the block itself read, so it
moves into the block: in flight, missing metadata, an after image matched, the zero-row rule
(`skipOrAbort`'s four cases, as codes 1 to 4, and a key matching several rows as 5), the stamp
count. The one that cannot is the comparison with an anchor the transaction recorded before the
statement, so **a write with recorded anchors on its table keeps the steps**, which at READ
COMMITTED is a predicate write after a read of the same table (TPC-C has none: Delivery's
`order_line` write follows an aggregate, which records no anchor) and at SNAPSHOT a `SKIP LOCKED`
read's table. The follow-up, if a workload shows the case: the keys read in one round trip, then
sent with their anchors into a block of a per-count text, two round trips instead of 1 + 2n.

Other decisions, each mirroring the PostgreSQL form the implementer translates from:

- The inline UPDATE folds its CAS into the version it writes, -1 where the row moved
  (`fusedKeyedInline`'s `scalardb_native_ok`), so a moved row that still matches is written and
  reported an abort, and one that stopped matching is the skip of the zero-row rule; the inline
  DELETE keeps its CAS in the WHERE and re-reads the predicate on a skipped row (`matches`, todo
  292). Weighed and rejected for the UPDATE: the re-read with the predicate, which decides on a
  later version than the one the DML met.
- The keys read is the keys step's text in the decoupled layout (`rc_T` with the metadata rows
  joined) and the two arms of `fusedKeyedInline` in the inline one, where `rc_T`'s CASE columns
  would hide a non-key predicate from the indexes.
- The stamp's three values are bound first into locals, so one text serves the `FORALL`
  statements and the imported table's `MERGE`, whose two branches both need them.
- The decoupled clear-table block (the DML with the gate returning its keys, the stamp as a
  `FORALL`, two counts back) is given here, where it is needed for a write beyond the key; the
  inline layout's clear-table form is the keyed form's statement, whose predicate is any.
- "The same version" is one rule in forms 2 and 3: `tx_id` equal with two NULLs equal (`DECODE`
  in SQL, an explicit `IS NULL` pair in a PL/SQL expression, which has no `DECODE`) and
  `COALESCE(tx_version, 0)` equal, which agrees with `Anchors.Version.is` and with `cas()` on every
  row ScalarDB writes (a row with a `tx_id` has a version of 1 or more). `SQL%BULK_ROWCOUNT` is
  copied into a local array right after its `FORALL`, before the zero-row rule's own statements.

Facts the implementing session confirms by test: `FORALL` over an empty collection (the gate
skips it anyway); `RETURNING ... BULK COLLECT INTO` across a `FORALL`'s executions (the inline
UPDATE's versions); `SQL%BULK_ROWCOUNT` after a `FORALL` whose statement carries the user's
placeholders; and a `MERGE` inside `FORALL` with `l_w(i)` in its `USING` clause.

### 2026-10-26: form 4 designed (Fable), design 4.3 "An INSERT on Oracle" and 4.4 "Batches on Oracle"

The vendor's own batching sets the target, so that was checked first: Oracle's JDBC Developer's
Guide (23ai, performance extensions, update batching) says standard batching gains only for
`PreparedStatement`, "does not implement true batching for generic statements and callable
statements", and takes no callable statement with an OUT parameter. Hence the division: entries
that are not consecutive parameter sets of one prepared write run one per round trip, each its
single form, at parity with the vendor; parameter sets of one prepared write go in one round
trip by one of two means.

- **The vendor's batching for writes whose form is one plain statement**: the inline INSERT with
  its stamp columns, and the inline statement with the stamp at SNAPSHOT (clear or gated). Nothing
  to build but adding the entries' binds to the rewritten statement; the counts are the vendor's.
  The gate is one condition for all entries in the snapshot, so counts all 0 ask the coarse check
  and any count above 0 marks the table clear.
- **The block of entries for writes whose form is a block** (keyed, beyond the key, decoupled
  INSERT): the write's block body repeated per entry, up to 16, sequential, stopping at the first
  entry whose outcome is not final by its own rules, one cursor row per entry with a `done`
  column. No savepoint, unlike PostgreSQL's text of entries: each entry's outcome is final once
  made, so a stop needs no undo, and a failure undoes the whole block by itself (statement-level
  atomicity), after which the entries are replayed one by one, as PostgreSQL replays. Weighed and
  rejected: undoing to a savepoint on a stop, which PostgreSQL needs because its statements cannot
  stop mid-text and a failed statement aborts its transaction; neither holds on Oracle.
- **The merged `FORALL` block**, one statement execution per step for all entries (the form of the
  write beyond the key with the keys given), built after the block of entries and measured
  against it. Its risk is the typing of the entries' values as collection elements, which is the
  one place a mistake is silent (a value converted or compared otherwise than the scalar bind):
  decided as "the type ojdbc binds the scalar as", by the setter, never the column's type (a CHAR
  column would pad a `setString` and match a row the statement alone misses), with the table in
  the design; anything outside the table keeps the block of entries. Two entries on one key are
  told before any write by comparing the keys as read, and go to the block of entries, whose order
  they need.
- **The decoupled INSERT block** was needed first (the batch of inserts is built on it) and is in
  4.3: the two inserts with `RETURNING ... INTO` carrying the key, the metadata insert under
  `IGNORE_ROW_ON_DUPKEY_INDEX` as today, and the tombstone takeover inside the block, which was a
  second round trip; ORA-00001 propagates and is classified as today. The inline INSERT needs no
  block: it is the one statement with the stamp columns already.

Facts the implementing session confirms by test: `RETURNING k INTO` of an `IDENTITY` key inside
the block; `FORALL ... INSERT ... RETURNING BULK COLLECT INTO` keeping the entries' order;
`IGNORE_ROW_ON_DUPKEY_INDEX` inside a `FORALL`; ojdbc's `BatchUpdateException` counts on a
duplicate in the middle of a prepared batch (`executeBatch` of the inline INSERT); and that a
`CallableStatement` with the cursor OUT bind is refused by `addBatch`, so that the block of
entries is the only way.

Left: form 5 (the locking read: the in-flight check and the lock in one round trip at SNAPSHOT,
and at READ COMMITTED the one-statement form if Oracle's statement restart gives the newest
version's anchor, to verify first), form 6 (the read side with the micro-benchmark session).

### 2026-10-26: form 5 designed (Fable), design 4.3 "The locking read on Oracle"

Designed against the PostgreSQL text as the other session left it (`816f506`, the decoupled
READ COMMITTED locking read as two statements in one round trip). Oracle has no multi-statement
text, so "two statements in one round trip" becomes one statement or two round trips; the forms
make the common case one statement at both levels.

- **SNAPSHOT:** the PostgreSQL argument holds as it stands (one snapshot, no versions, ORA-08177
  from the lock itself for a row changed since). The coarse check and the lock, which PostgreSQL
  sends together, become the write's gate inside the lock statement, `AND NOT EXISTS (in
  flight)`, one round trip when rows come back (which proves the table clear), and the coarse
  check in a second round trip only when nothing came back on a table not yet clear, exactly as
  `applyFast` tells a write's zero rows apart. Weighed and rejected: a PL/SQL block holding the
  check and the lock as a ref cursor, since a ref cursor's result set is forward-only and the
  application's statement may be scrollable; and the coarse check always first, a round trip on
  every first touch of a table in a transaction.
- **READ COMMITTED, inline:** the PostgreSQL one-statement form as it is, the three metadata
  columns appended. It rests on Oracle's statement restart, which Oracle's Concepts manual states
  for a waiting UPDATE (the statement proceeds against the newly committed row, its predicate
  re-checked) and Kyte's AskTom series states for `SELECT FOR UPDATE` (the statement restarts when
  a row's current version differs from the one it read); the test in the design confirms it on
  the container before the form is built, as the brief asked.
- **READ COMMITTED, decoupled:** the join form, `T LEFT JOIN T_meta ... FOR UPDATE OF t.k`, one
  statement, which PostgreSQL cannot have (its re-read does not reach a joined row) and Oracle
  can if its restart runs the join again at the new point in time. Pachot (dbi services) shows a
  write-consistency bug, open since 2011 and present in 21c, where a nested subquery in an UPDATE's
  WHERE escapes the re-evaluation, so the join form is conditional on the restart test passing on
  the decoupled layout, and the two statements as two round trips stand otherwise, exact by the
  PostgreSQL form's own argument (no uncommitted prepare holds a locked data row once the lock is
  granted). A verifiable condition, not an open case: the test fails loudly, by returning a row
  in flight, if the form is unsound.
- The rest as on PostgreSQL: a wait configured, `SKIP LOCKED`, a predicate beyond the key
  columns, or an impure one, keep the steps and the savepoint; `NOWAIT` fails as the driver's
  ORA-00054 on a row in flight and as Oracle's own on a locked row.

Sources checked today: Oracle Database Concepts 23ai, "Data Concurrency and Consistency"
(conflicting writes at read committed; ORA-08177 at serializable for UPDATE and DELETE, the
FOR UPDATE case the driver's section 5 states as built); Franck Pachot, "Oracle write consistency
bug and multi-thread de-queuing" (dbi services); Kyte's "Something Different, part I" as search
results summarize it (the page itself timed out twice).

Left: form 6 (the read side's Oracle costs from the micro-benchmarks, with the session working on
them).

### 2026-10-26: form 6 designed (Fable), design 4.2 "The read side on Oracle"; all six forms designed

The read side's OLTP work on Oracle is small beside the writes, since form 1 already gave the
SNAPSHOT reads without anchors and the clear-table memory; what is left and decided here:

- **READ COMMITTED anchors as the copies' own columns**, the PostgreSQL change of todo 329 ported
  as it is: the inline copy's own `tx_id` and `tx_version` with its state condition on the row, the
  decoupled copy's from a left join to its metadata row, the in-flight copy's before image's. The
  argument is statement-level read consistency, which Oracle has at READ COMMITTED (section 1).
  On Oracle this also replaces the decoupled copy's `NOT EXISTS` per row by the joined row's
  state, one metadata access per data row instead of three.
- **The probes carry the in-flight index hint**, so that no OLTP form depends on the metadata
  table's statistics: todo 094's fixed, locked statistics are uncommitted and its review listed
  reasons against committing them as they are, and the gathered statistics gave the 1654x plans
  of `read-overhead.md`. The state column's two subqueries, the coarse check and the writes' and
  locking read's gate all read `T_meta WHERE tx_state IN (1, 2)`; hinted, the probe is a range scan
  stopped at its first row. This is the one thing the micro-benchmark session must keep.
- **The boundary with the micro-benchmark session**: paging (todo 296), aggregates, the deep
  OFFSET (todo 117), the in-flight pairs and todo 094 stay there; the forms of todo 334 change no
  copy's shape beyond the two above.
- **What to measure** on the OLTP read shapes, listed in the design with the matrix's figures.

With this, every form of the brief has its paragraph in `docs/design.md` (4.2: "On Oracle both
hold", "The read side on Oracle"; 4.3: "The keyed write on Oracle", "The write beyond the key on
Oracle", "An INSERT on Oracle", "The locking read on Oracle"; 4.4: "Batches on Oracle"), each with
its argument, its text, the row the driver reads back and its outcome table, and this log has the
alternatives and the facts the implementing session confirms by test. Next, as the division of
work says: the implementing session builds the forms in the brief's order, runs the Oracle suite,
measures locally then on AWS once BenchBase has an Oracle build (after the PostgreSQL matrix), and
sends any case the design leaves open back here; the semantics-bearing parts get one review pass
on Fable against the design text before the forms merge.

### 2026-10-26: from the implementing session (todo 335, task 1): every fact confirmed, two items back

The eight confirmation tests pass on Oracle Free 23 (`OracleFormsIntegrationTest`, results in todo
335's log): statement-level atomicity of a block; ORA-08177 out of a block (UPDATE and `FOR UPDATE`)
as 8177/72000 with the block's earlier write undone; the ref cursor over `dual` giving `readKey` the
same value and class as the table's column for all nine key types; positional binds and ORA-17090
on `addBatch`; `FORALL` over an empty collection (`SQL%ROWCOUNT` 0), `RETURNING ... BULK COLLECT`
collecting one element per row written in order, `SQL%BULK_ROWCOUNT` with scalar binds, `MERGE` in
`FORALL`, `RETURNING INTO` of an IDENTITY key, the hint in a `FORALL` INSERT; the restart of a
waiting `FOR UPDATE` re-reading the newest row on the inline layout **and** the joined metadata row
on the decoupled one (so form 5's decoupled one statement is selected); ojdbc's `BatchUpdateException`
with `[1, 1]` and the two rows before the duplicate kept. Two items for the design:

1. **A text note, forms 3 and 4.** A collection's `.COUNT` is a PL/SQL expression and cannot appear
   in the cursor's SQL: `OPEN ? FOR SELECT l_k.COUNT, l_held, ... FROM dual` fails with ORA-06550,
   ORA-00904 `"L_K"."COUNT": invalid identifier`. The predicate block's last line (4.3, "The write
   beyond the key on Oracle") needs a NUMBER local assigned before the OPEN; the builder will do
   that, the paragraph may say so.
2. **An open case, form 2 (and the INSERT block, and every block that returns a row): the row read
   back.** Measured by `v$mystat`'s `SQL*Net roundtrips to/from client` and by the client's chunks
   (both agree): a block's execute is one round trip, autocommit on or off (ojdbc sends the commit
   with the execute), but **the fetch of a ref cursor's row is a second round trip**, and
   `DBMS_SQL.RETURN_RESULT` costs the same two; three scalar OUT binds come back with the execute in
   one. So the keyed block as designed is two round trips in a transaction where the design counted
   one. The alternative the design set aside for its typing risk was measured too (test 3b): the key
   through an OUT bind registered with the JDBC type the table's result set reports for the column
   (2 for NUMBER, 12 VARCHAR2, 1 CHAR, 101 BINARY_DOUBLE and BINARY_FLOAT through
   `TO_BINARY_DOUBLE`, 93 DATE and TIMESTAMP, -3 RAW), read as `readKey` reads that type, equals
   under `Anchors.key` what `readKey` gives from the table for all nine types, the padded CHAR
   included. The decision asked of the design: keep the cursor (two round trips, no type table), or
   return the counts as NUMBER OUT binds and the key through an OUT bind typed from the column's
   declared type (which `Writes` already reads from `ALL_TAB_COLUMNS` for the INSERT's CAST, todo
   277), with the cursor kept for a type outside that table (TIMESTAMP WITH TIME ZONE, INTERVAL,
   NCHAR, a type the setup did not record). The implementing session builds form 1 meanwhile and
   form 2's result handling behind one method that reads the row, so either answer is a local change.
