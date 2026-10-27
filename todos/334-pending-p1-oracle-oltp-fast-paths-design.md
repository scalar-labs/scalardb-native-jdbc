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

### 2026-10-26: from the implementing session: forms 1 and 2 built, two choices for the review

Form 1 (reads without anchors at SNAPSHOT, the clear-table memory, the hinted probes) and form 2
(the keyed block) are built and measured (todo 335's log: a keyed write 4 round trips to 2 in a
transaction, the second the cursor's fetch; the inline layout's clear-table statement 1). Two
choices in form 2's block differ from the paragraph's text and are for the review pass: the
decoupled CAS matches the metadata row by its own key read in step 1 rather than by `l_w(1)` from
the DML's RETURNING, since on Oracle a CHAR(n) data key against the VARCHAR2 metadata key is a
non-padded comparison and the design's form would stamp nothing for CHAR keys (the steps match by
`row.metaKey` too); and the block's read goes through a derived table of the key, as the steps'
keys read does, since an unqualified key column in `P` is ambiguous across `T` and `T_meta`.

### 2026-10-26: from the implementing session: all six forms built, the review items

Forms 3 to 6 are built and measured (todo 335's log). The items that differ from the paragraphs,
for the review pass: (1) `FORALL i IN VALUES OF l_do` for the CAS over the keys written, since a
FORALL index may only subscript a collection directly (PLS-00430 for `l_k(l_do(j))`); (2) a
collection's `.COUNT` through a NUMBER local before the cursor's SELECT; (3) the predicate block's
row is decided with `after` before `found`, as PostgreSQL's fused statement checks it: a row in
flight whose before image matches nothing is `found` 0 with `after` 1 and must abort
(`SnapshotIntegrationTest`, todo 166), so the table's first two rows swap; (4) the decoupled CAS
matches the metadata row by its own key read in step 1; (5) the block of entries binds a stamp per
entry rather than once, each entry's `tx_id` its own as on PostgreSQL; (6) the READ COMMITTED
locking read's `FOR UPDATE` stays on the derived table, so the metadata row joined in is locked
beside the data row, where the paragraph named `OF t.k`; (7) not built, as low-value or dependent:
the predicate block's entries, the inline SNAPSHOT statement through `executeBatch`, the merged
`FORALL` block (the block of entries already makes a batch one execution and one fetch), and the
one-round-trip autocommit of a raising block (the open case of the row read back stands).

### 2026-10-26: decision requested now (the user): the row read back, cursor or OUT binds

The user asks the design to decide this case now, before the AWS run, so that the run measures the
form the driver will ship. The question, with what is known:

- **As built:** every block that returns a row opens a `SYS_REFCURSOR` over `dual` on its locals,
  read through `readKey`; ojdbc fetches that cursor in a second round trip (fact 8, measured by
  `v$mystat` and by the client's chunks, `DBMS_SQL.RETURN_RESULT` the same). So in a transaction a
  keyed write, a write beyond the key, a decoupled INSERT and a block of batch entries cost 2 round
  trips where PostgreSQL's forms cost 1; in autocommit 3.
- **The alternative:** the counts as NUMBER OUT binds and the key through an OUT bind registered
  with the JDBC type `Writes.columns` reads for the column from `ALL_TAB_COLUMNS` (2 NUMBER, 12
  VARCHAR2, 1 CHAR, 101 BINARY_DOUBLE and BINARY_FLOAT through `TO_BINARY_DOUBLE`, 93 DATE and
  TIMESTAMP, -3 RAW), read as `readKey` reads that type. Fact 3b measured it equal under
  `Anchors.key` to what the reads record for all nine types, the padded CHAR included; a type outside
  that table (TIMESTAMP WITH TIME ZONE, INTERVAL, NCHAR, one the setup did not record) would keep the
  cursor. One round trip in a transaction, two in autocommit.
- **The risk the design named:** an OUT bind typed otherwise than the reads record is an anchor
  not found, which `Anchors.unchanged` takes as unchanged, a silent lost update. The table above is
  the evidence against it for the types ScalarDB imports; the cursor stays for any other.
- **Where the change lands:** the row read back is one method per plan (`KeyedPlan.applyBlock`,
  `applyPredicateBlock`, `applyFast`'s Oracle branch, `InsertPlan.applyOracleBlock`,
  `Writes.applyOracleEntries`), and the block texts' last line; the implementing session estimates
  an hour and the suites.

Asked: cursor as designed, or OUT binds with the cursor kept for a type outside the table. Answer
here, and the implementing session builds it.

### 2026-10-26: decided (Fable): OUT binds, the cursor kept as the check and the fallback

Decision: the blocks return their row through OUT binds, the counts as `NUMBER`, the key through
binds registered with the JDBC type the reads report for the column's declared type (2 `NUMBER`,
12 `VARCHAR2`, 1 `CHAR`, 101 `BINARY_DOUBLE` and `BINARY_FLOAT` through `TO_BINARY_DOUBLE`, 93
`DATE` and `TIMESTAMP`, -3 `RAW`), read by a sibling of `readKey` that switches on the type as
`readKey` does; a key column of a declared type outside the table keeps the cursor. The objection
the design raised against OUT binds is answered by a check, not by the table: the first block of
a table in a process runs the OUT text with the cursor opened after the binds on the same locals,
the driver compares the key through both under `Anchors.key`, and marks the table verified (OUT
binds alone from then on) or cursor-only for the rest of the process (one WARN line with both
values and their classes); a row without a key (`rows` 0) decides nothing and the next block
checks. One round trip per block in a transaction, as PostgreSQL's forms; the check costs one
more, once per table per process. The design text (4.3 "The keyed write on Oracle") carries the
rule; the other blocks refer to it.

Why a check and not the table alone. Fact 3b registered each OUT bind with the type the table's
own result set reported and found the key equal under `Anchors.key` for the nine types: strong
evidence for those types under the container's ojdbc with its default properties. The driver
will map the declared type (`ALL_TAB_COLUMNS`, todo 277) to that reported type by a table of its
own, and what no table can see is the connection's properties
(`oracle.jdbc.mapDateToTimestamp=false` makes the reads report 91 and read a `java.sql.Date`,
where a bind registered 93 reads a `LocalDateTime`), an ojdbc version reporting a type otherwise,
or a declared variant the nine did not cover. Each is an anchor not found, which
`Anchors.unchanged` takes as unchanged: the silent lost update the design named. The check
compares the two paths on the real column, through the real connection, at the first write; the
two keys come from the same local of the same block execution, so a difference can only be the
client's typing, and a mismatch costs the table its one round trip, loudly, never its exactness.

Weighed and set aside: ojdbc's `RETURNING ... INTO` return parameter (`registerReturnParameter`),
typed by the driver all the same and not available to a block; one `VARCHAR2` OUT bind with a
canonical text of the key, re-typed by the driver, the typing twice and lossy for
`BINARY_DOUBLE` and timestamps; the key from the driver's own bind values or the literal, which
have the application's Java type, not the reads'; a describe of the key columns at plan build
(`SELECT <key as the reads read it> FROM T WHERE 1 = 0`) to take the registration type from the
engine, which settles the table's mapping but not the `CallableStatement`-against-`ResultSet`
reading and costs a round trip per table anyway, so the check at first use is both cheaper and
stronger; the cursor as designed, two round trips per block, which the AWS run would measure for
a form that would not ship.

Consequences in the text: each block's last line is the assignments `? := l_rows; ... ? := l_k;`
in place of `OPEN ? FOR SELECT ... FROM dual` (4.3's keyed write in both layouts, the write
beyond the key, the clear-table block, the INSERT; 4.4's block of entries per entry); the
predicate block's `l_k.COUNT` is a PL/SQL expression again, so the implementing session's text
note resolves itself; the write beyond the key returns counts only and needs no check; the block
of entries returns `n, done` and the write's row per entry as OUT binds, its checking run the
cursor over the `UNION ALL` as built, fetched whole. For the implementing session: the row read
back is one method per plan and the block texts' last line; build the checking variant as the
OUT text plus the cursor, so the two texts never differ in anything but that line; make
confirmation test 3b an assertion for the nine types, registering by the declared type as the
driver will; add a unit test of the check's three outcomes (equal, different, no key); log the
round trips of the four blocks again, and the first write's extra one.

### 2026-10-26: review (Fable): the result handling of the six forms against the design's tables

Reviewed at b0b46ef the methods that read a block's row and decide: `KeyedPlan.applyBlock` and
`decide`, `applyPredicateBlock`, `applyFast`'s Oracle branch, `InsertPlan.applyOracleBlock` and
`entry`, `LockPlan.applyGated` and `applyFastRc` on Oracle, `Writes.applyOracleEntries` with its
caller in `Proxies`, `applyVendorBatch`, `OutBindCheck`, `oracleKeyJdbcType`, `readOutKey`, and
the block texts' row items and stop conditions (`keyedBody`, `oraclePredicateBlock`, the INSERT
body). Every row of the keyed write's, the write beyond the key's, the INSERT's, the locking
read's and the batches' tables has its branch, in the design's order, with the differences below.

One finding to fix before merge:

1. `KeyedPlan.applyBlock`, CHECK variant: the row is built from the OUT binds and `decide` is
   told the cursor's key too, but its outcome is not used; when the two keys differ,
   `decide(run, row)` checks the anchor with the OUT key, an anchor not found, "unchanged", on the
   one write the check exists to protect. `InsertPlan.applyOracleBlock` and `applyOracleEntries`
   take the cursor's key or row in that case; the keyed block must too: rebuild the `KeyedRow`
   with the cursor's key when the outcome is `CURSOR`, and a cursor without a row where the OUT
   binds show one is the block returning no row. A unit test: a mismatching pair on a table's
   first keyed write, the anchor recorded under the cursor's key, the write aborting for a moved
   version.

Accepted, the design text amended to the built form:

- the decoupled keyed block reads through a derived table of the key and its CAS matches the
  metadata row by its own key read in step 1 (`l_m`), without the DML's `RETURNING`: a CHAR(n)
  data key against the varchar metadata key compares unpadded in a WHERE clause; the same in the
  write beyond the key (`l_m(i)`), whose CAS is `FORALL i IN VALUES OF l_do` (PLS-00430);
- the write beyond the key decides `after` before `found`, as PostgreSQL's fused statement does;
  the table's rows swapped;
- the READ COMMITTED decoupled locking read's `FOR UPDATE` on the derived table, the metadata row
  locked beside the data row, harmless since a prepare waits on either lock;
- the block of entries binds a stamp per entry, each entry's `tx_id` its own;
- the replay one by one after a failure of a block of entries is `undoable`'s, SQLState 22 and
  23; Oracle's conflicts, 72000 and 61000, end the batch at the block with the counts before it,
  8177 mapped `40001` and rollback-only by `asRetryable` and `endsTransaction`. The transaction's
  outcome is the same; the table says so now rather than asking for the two states;
- deferred, recorded in 4.4: the block of entries over the write beyond the key, the gated inline
  statement through `executeBatch` at SNAPSHOT, the merged `FORALL` form, the one-round-trip
  autocommit of a raising block.

Noted, no change asked: `OutBindCheck`'s state is per qualified table name per process, so two
data sources to one table name in a process share a verdict (a different ojdbc configuration per
source would be unusual); after a block of entries stops, the stopping entry is sent again as the
first of a new block before its single form runs, one block execution more per stop, not a
semantic difference; the predicate block's step 1 would raise ORA-01422 on a predicate matching
several rows of one key, which the full key excludes.

Asked of the implementing session with the fix: a line per table row in todo 335's log naming the
test that exercises it, or "none", so that the rows without one (the decoupled `written` 1
`stamped` 0, the predicate block's abort codes 3 and 4, the INSERT's `meta` 0 `stamped` 0 look the
likely ones) are known before the AWS run rather than found by it.

### 2026-10-26: the fix verified (Fable); the mismatch warning carries classes, not values

02727ae read from the committed revision: `KeyedPlan.applyBlock`'s CHECK variant takes the cursor's
whole row where the check says `CURSOR`, and a cursor without a row where the OUT binds show one
is the block returning none; the INSERT block and the block of entries do the same; the unit test
drives `KeyedPlan.apply` through fakes with a String OUT key against a NUMBER cursor key, and
fails without the fix. The review's item 1 is closed. The review itself had read `Writes.java`
from the working tree, which held the implementing session's fix in progress, so it took the
INSERT block and the entries for already taking the cursor's value; in a shared checkout a review
reads `git show <rev>:<path>`, not the tree.

Decided, on the implementing session's question: the WARN line of `OutBindCheck.decide` names
the table and the two keys' classes per column, never their values; the values go to DEBUG. A
key may be a person's identifier, and a driver's default-level log reaches aggregators the
operator did not choose for row data; the classes tell the typing mismatches the check exists for
(a `String` against a `BigDecimal`, a `java.sql.Date` against a `LocalDateTime`), and the
value-level ones, a padded `CHAR` or a scale, are for the driver's developers, who reproduce them
on the column type with the confirmation test. The design text's "both values with their classes"
becomes "their classes, the values at DEBUG".

The row-to-test map in todo 335's log lists the rows without a test; the ones to add first, since
a mistake there is silent: the decoupled `written` 1 `stamped` 0 (a metadata row moved between
the read and the CAS), and the INSERT's `meta` 0 `stamped` 0 on an imported table. The predicate
block's codes 3 and 4 are asserted as `40001` by `form3`'s moved key, which is the outcome that
matters; the rest are error paths the engine's own mapping covers.

### 2026-10-26: review closed (Fable) at 9f69337

Read from the committed revision: the WARN line names the table and the two keys' classes per
column (`OutBindCheck.classes`), the values and classes on a DEBUG line (`describe`), the unit
test asserting the WARN text carries no value; the two silent rows have their tests
(`form2RowMovedBetweenTheReadAndTheCas`, both layouts, through a lock wait on a row another
connection's keyed write holds, which lands on `stamped` 0 and the inline `written` 0 rather than
on the anchor; `form4InsertOverAMetadataRowInFlight` on the imported table). Nothing is open on
the design side for the six forms. What follows is the measurement: the Oracle BenchBase build and
the AWS run after the PostgreSQL matrix, against the figures of todo 335's log.

### 2026-10-27: the first Oracle BenchBase run read (the design)

Run 20261027-0133 (`docs/benchmarks/benchbase-oracle-aws.md`, 9850da9 and 9349ad8, by the AWS
session; tree 71e53d5): TPC-C 0.41–0.69 of the vendor fast, 0.58–0.74 slow; YCSB decoupled READ
COMMITTED 0.20 fast. The user finds it unacceptable. Three things decided or measured here, two
candidates sized, and one anomaly in the readout.

**1. The decoupled READ COMMITTED lock holds the metadata row, which closes a cycle with the write
blocks: fixed in the design by `FOR UPDATE OF`.** YCSB's read-modify-write cell logs about 25
ORA-00060 a run and 813 `enq: TX - row lock contention` waits worth 316 of 480 terminal-seconds;
inside the write block the `UPDATE T_meta` waits 84 s over 53,909 calls, which only a session
holding the metadata row without the data row explains, and the lock read is the one statement
that takes a metadata row. Checked on the container (`scratchpad/ora/lockscope.sh`): the form as
built, `... FROM (T t LEFT JOIN T_meta m ...) x WHERE x.k = :1 FOR UPDATE`, leaves a second
connection's `FOR UPDATE NOWAIT` of the key's metadata row and data row both ORA-00054; with `FOR
UPDATE OF x.k` (or `OF k`) the metadata row is free and the data row locked, the plan unchanged
(FOR UPDATE over NESTED LOOPS OUTER from `T`). Design 4.3 "The locking read on Oracle" now has the
decoupled form with `OF` a key column of the derived table under the exposed name, the reason, and
the lock's scope as a third deciding test. The builder: in `Router`'s Oracle `fastRc` branch on a
decoupled table, the user's `FOR UPDATE[ NOWAIT]` clause becomes `FOR UPDATE OF <x>.<k0>[ NOWAIT]`;
`OracleFormsIntegrationTest` gets the scope test beside test 6, and the restart test runs again on
the new text. The YCSB decoupled READ COMMITTED cell on AWS is the proof. TPC-C at that level shows
no deadlock because its contended keys (district, warehouse) are locked by NewOrder alone and
written by Payment's block, whose data-row wait precedes any metadata lock; YCSB's update and
read-modify-write on one hot key meet in both orders.

**2. Every statement the driver prepares on Oracle is parsed per call; measured, the cache is
worth taking.** `server.txt` has parse calls equal to calls on both sides (BenchBase prepares per
transaction; ojdbc's implicit statement cache is off by default; the driver prepares its own
texts, the blocks among them, per call on the vendor connection). On PostgreSQL pgjdbc's
server-side prepared statements gave the driver's texts this for free. Measured on the container
(`scratchpad/ora/cache2.jsh`, 10,000 calls a cell after 300 warm, `V$MYSTAT` deltas of the session,
a clear-table block of the warehouse's shape against the plain UPDATE):

| per call | parsed per call | implicit cache |
|---|---|---|
| block: server CPU, DB time | 37, 95 µs | 21, 75 µs |
| block: wall clock at the client | 344 µs | 254 µs |
| plain UPDATE: server CPU, DB time | 10, 40 µs | 8, 38 µs |
| plain UPDATE: wall clock | 236 µs | 200 µs |
| parses per call (block, with its two nested statements) | 3.09 | 0 |

So a parse call costs the server 16 µs of CPU on a block (2 on a plain statement) and the client
90 (36), and the nested statements' parse calls go with it. Decision: the driver turns on ojdbc's
implicit statement cache for the connection it opens on Oracle unless the user set the property
(`oracle.jdbc.implicitStatementCacheSize`, a size that holds the driver's texts, 128: the TPC-C
cell ran 80 texts, one block text per batch row count among them; the measurement used 64 over
two texts), noted in
design 4.3's keyed-write paragraph. The application's own statements are cached by it too, which
is a gain to users and a question of fairness in the benchmark: the vendor's run should get the
same property for the comparison, or the harness its own statement cache. At 21–36 driver calls a
commit the server saving is of the order of 0.2 ms a commit of the 2.6 ms gap at SNAPSHOT
decoupled, the latency saving larger; the AWS cell decides.

**3. The next design item, the batch entries at SNAPSHOT on a clear table.** In the SNAPSHOT
decoupled TPC-C cell NewOrder's stock batch runs as keyed-block entries (`l_rows_1`, ... one text
per row count): per stock row a versions read (`SELECT 1, CASE WHEN M.k IS NULL ...`, 283,777
calls, 14 µs each), the `UPDATE STOCK` and the `UPDATE STOCK_tx_metadata` (24 µs), and per order
line the `INSERT ORDER_LINE` and its metadata insert (24 µs): about 50 nested statements a
NewOrder, 1,262 µs of block time a commit of which 722 is the nested statements and 540 the
blocks' own. On a table the transaction found clear at SNAPSHOT the lock recorded no version
(4.2 on Oracle), so the entries need no read and no CAS: the clear-table form's two writes per
entry, which as `FORALL` over the entries' arrays are two bulk statements a batch in one block, one
text for every row count, the metadata rows stamped by one `MERGE` or `INSERT`. The same for the
decoupled INSERT entries. Not designed here yet: the outcome rules per entry under a bulk statement
(`SQL%BULK_ROWCOUNT`) and the duplicate among inserts (`SAVE EXCEPTIONS`). This is the largest
statement-CPU item for TPC-C decoupled after the cache; the inline layout's SNAPSHOT vendor batch,
designed in 4.4 and not built, is its counterpart there.

**4. Noted, not acted on.** (a) At READ COMMITTED the keyed block whose data-row UPDATE waited on
another connection's write finds the version moved and returns `stamped` 0, which the driver
aborts as `40001` (`form2RowMovedBetweenTheReadAndTheCas`); the vendor applies the update after
the wait, and PostgreSQL's form of todo 336 does too. BenchBase shows it as "rejected (server
retry)": 550 in YCSB decoupled READ COMMITTED and 614 inline against the vendor's 0, 2 in TPC-C.
A behavioural difference at this level, small in cost; the fix is the block re-reading the version
under the lock after the data write and comparing a recorded anchor afterwards. (b) SNAPSHOT inline
YCSB fast: 585 row-lock waits (45 s) and 17 s of buffer busy waits, no deadlock; unexplained. (c)
BenchBase's `isRetryable` knows MySQL's and PostgreSQL's codes only, so ORA-08177 (both sides,
25,930 and 6,784 unexpected in TPC-C fast) and ORA-00060 are dropped transactions, not retries.

**5. The readout.** In the driver's `tpcc-c-1/server.txt` of the SNAPSHOT decoupled cell the
vendor-shaped texts (a leading space, lower-case table names: ` SELECT S_QUANTITY ... FOR UPDATE`
with 614,539 calls, the vendor's own count to the call) appear beside the driver's rewritten ones
(`/* scalardb_native_lock */ SELECT S_QUANTITY ...`, 261,668 plain and 29,165 gated), 643,273
calls and 10.2 of 147 s of CPU in all: the delta seems to carry the previous run's cursors. "The
stock SELECT ... FOR UPDATE runs 10.4 times a commit against 5.0" reads as that artifact; the
driver's locks are one gated and nine plain a NewOrder. Sent to the AWS session.

The AWS session (2026-10-27): the per-statement deltas of run 20261027-0133 carried earlier runs'
cursors (confirmed; the harness flushes the shared pool before the start reading, 7dbe91e; the
host figures were unaffected). The cache cell needs no build: `BB_JVM_ARGS` passes
`-Doracle.jdbc.implicitStatementCacheSize=128` to both runs, and ojdbc11 23.26.3.0.0 honours the
system property (checked locally: `getImplicitCachingEnabled()` true, size 128), the vendor's parse
calls in `V$SQL` the check. The `FOR UPDATE OF` fix's proof cell waits on a build and the user's
approval; the batch item is sized again from a flushed run first.

### 2026-10-27: d56e87d reviewed (the design), the FOR UPDATE OF fix

Read from the commit. `Router.lockingRead`'s Oracle `fastRc` branch, on a decoupled table only,
inserts ` OF <exposed alias>.<quoted first key column>` after the last `UPDATE` token of the
emitted text and keeps `NOWAIT` or `WAIT n` after it; the select list and the inline form are
untouched; no new binds. That is the design's clause. The tests decide what the design asked:
`scopeOfTheDecoupledLock` in the vendor's spelling of the derived-table form (the plain clause
locks both rows, `OF t.k` with or without `NOWAIT` leaves the metadata row free and the data row
locked), test 6 on the new text in both layouts (the newest row after the restart for a prepare
and for a write), and `form5LocksTheDataRowAlone` through `Router.route` and the driver for both
layouts with and without `NOWAIT`. Matches; nothing to send back.

One note for later, not a send-back: a clause the user wrote with `OF <column>` is left as
written. On one table any `OF` column locks the same rows as the key column, so the key column
could replace it, which would also spare the case of a user's `OF` column the derived table does
not select (ORA-00904 on the decoupled form, as before this fix). The proof stays the YCSB
decoupled READ COMMITTED cell on AWS.

### 2026-10-27: the single-row write as a block, a procedure, a minimal block, and the vendor's statement (the design)

At the user's request through the AWS session, on the container (`scratchpad/ora/forms.jsh`;
YCSB's shape, a key and ten 100-character fields, 1,000 rows loaded with metadata, the in-flight
index `(tx_state, K)`; 10,000 calls a cell after 300 warm, a commit every 100; server CPU and DB
time from `V$MYSTAT` deltas, parses likewise, wall at the client; ojdbc's implicit cache off and
on at 128). The forms: (d) the vendor's statement; (a) the driver's clear-table block as emitted
(the gated UPDATE `RETURNING ... BULK COLLECT`, `FORALL` over the keys stamping `T_meta`, two OUT
binds); (c) the minimal block, the same two statements without the collection, the stamp's values
bound in the second statement; (pa) and (pc) the same two bodies as stored procedures called as
`BEGIN p(...); END;`; the insert likewise; the key read plain and with the states column (`CASE
WHEN NOT EXISTS (in-flight probe) THEN 0 WHEN (K) IN (...) THEN 1 END`).

| per call, µs | parsed per call: wall, CPU, DB time | cached: wall, CPU, DB time |
|---|---|---|
| update (d) plain | 242, 14, 39 | 199, 9, 23 |
| update (a) the driver's block | 396, 43, 110 | 279, 36, 94 |
| update (c) minimal block | 469, 37, 105 | 316, 29, 95 |
| update (pa) procedure, (a)'s body | 360, 51, 97 | 302, 37, 93 |
| update (pc) procedure, (c)'s body | 378, 35, 92 | 362, 33, 91 |
| insert (d) plain | 253, 18, 60 | 260, 10, 52 |
| insert (a) the driver's block | 511, 43, 127 | 377, 32, 99 |
| insert (c) minimal block | 423, 43, 109 | 376, 35, 122 |
| insert (pa) procedure, (a)'s body | 415, 42, 119 | 378, 44, 115 |
| read (d) plain | 241, 10, 25 | 197, 3, 22 |
| read (s) with the states column | 279, 13, 44 | 208, 5, 36 |

Parses per call: 3 for a block (its two nested statements with it), 1 for a procedure call or a
plain statement, 0 cached. `V$SQL` per execution, cached: the block 30 µs of CPU and 82 elapsed,
its nested gated `UPDATE U` 8–10 and 20–31, its `UPDATE U_meta` 11–12 and 28–30; the plain UPDATE
7 and 21. (The insert cells ran in sequence on a growing table, so their DB time drifts upward
with the arm; read them as CPU.)

Reading. (1) A stored procedure gains nothing over the anonymous block once the text is cached
(37 against 36 µs of CPU, 93 against 94 of DB time); uncached it spares two parse calls, 97
against 110 of DB time, which the cache spares anyway. Not worth an object created at import.
(2) The minimal block spares about 7 µs of CPU and nothing of DB time: the collection plumbing
and the `%TYPE` locals cost next to nothing at run time. (3) The cache's gain is mostly the
client's: 117 µs of wall a block call (396 → 279), 7 of server CPU, 16 of DB time; the decision
above stands. (4) What a block costs beyond its two statements, cached: about 16 µs of CPU and
30 of DB time, PL/SQL's call, two context switches and the OUT binds; the second write itself 11
of CPU and 28–30 elapsed, the layout's own; the gate 1–2 of CPU. Against the vendor's plain
UPDATE at 9 and 23, the decoupled write stands at 36 and 94: about +70 µs of server time a call,
of which a third is the block and a third the metadata row's write. (5) The read's states column
costs 2 µs of CPU, 14 of DB time and 11 of wall over the plain read.

What it means for the user's 10–20% on key reads and writes (YCSB fast: the vendor's update 380
µs, the driver +237; insert 417, +200): the server's share of the +237 is about 70; the rest is
the client's, of which the cache takes about 100 (the AWS cache cell will say); what remains at
the client is the driver's own work per call, to be measured against raw ojdbc. The server's +70
has no PL/SQL form cheaper than the block; a form without a block, one SQL statement, does not
exist on Oracle for two tables (a trigger stamping the metadata row from a session context would
fire for ScalarDB's own writers too and is not proposed). The key read is within the goal already
(+11 µs of wall on 197).

### 2026-10-27: the driver's own cost at the client against raw ojdbc (the design)

At the user's request, on the container through a measurement test in a scratch worktree
(`scratchpad/wt2/.../ClientCostIntegrationTest.java`, run with gradle against `ofa-oracle`): a
decoupled table of YCSB's shape created by ScalarDB with 1,000 rows and the driver's setup; the
same read, update and insert through the driver (catalog TTL 600 s) and through raw ojdbc on the
data table, one operation per transaction with its commit, SNAPSHOT (the vendor at SERIALIZABLE)
and READ COMMITTED, ojdbc's implicit cache off and on at 128, 5,000 calls a cell after 300 warm,
the whole matrix twice. Per call: wall at the client, the thread's CPU and allocation, GC time,
the session's CPU, DB time, user calls, executes, parses and round trips (`V$SESSTAT` by SID).
Round trips and user calls are equal on both sides in every cell; GC is 0–2 µs a call; the wall
clock varies by about ±100 µs between rounds on the laptop (its Docker round trip is about 200
µs), so the CPU counters are the figures to read.

The driver less the vendor, cached, round 0 / round 1:

| per call | client CPU | server CPU | DB time | allocation |
|---|---|---|---|---|
| read, SNAPSHOT | +20 / +5 µs | −2 / +22 | −20 / +8 | +1.2 KB |
| read, READ COMMITTED | +7 / +10 | +8 / 0 | +10 / +20 | +1.7 KB |
| update, SNAPSHOT | +38 / +45 | +24 / +40 | +96 / +162 | +4.6 KB |
| update, READ COMMITTED | +63 / +35 | +22 / −30 | +124 / −38 | +9.2 KB |
| insert, SNAPSHOT | +47 / +22 | +10 / +16 | −12 / +144 | +5.7 KB |
| insert, READ COMMITTED | +35 / +36 | +30 / −6 | +54 / +64 | +5.6 KB |

Uncached, the driver's client CPU exceeds the vendor's by 50–70 µs on a read and 105–166 on a
write, with 56–86 KB allocated a call against the vendor's 15–18: ojdbc's preparation of the
rewritten text each call, which the cache removes, and with it the driver's own per-call
preparation, since cached the driver allocates 3–11 KB a call, so its routing is served from a
cache by text.

Reading. (1) With the cache the driver's own work at the client is about 5–20 µs on a key read
and 35–50 µs on a single-row write, of which the block call's own handling in ojdbc (OUT binds,
a callable statement) is about 10 (the forms measurement: the block's wall less its DB time
against the plain statement's). The rest, 25–40 µs a write, is the driver's: bind translation to
the block's 16 binds, the row read back, the anchors and the clear-table memory. (2) The server's
share is the forms measurement's: +70 µs of DB time a write, of which a third the block, a third
the metadata row. (3) GC and allocation are not a factor once cached.

Against the user's 10–20% on YCSB's update (the vendor 380 µs at the fast round trip, the driver
+237 before the cache): the cache should take the client's uncached excess, about 100; what stays
is about 70 at the server and 35–50 at the client, 105–120 µs or 28–32%, if the AWS client
behaves as the laptop's. To reach 20% both must shrink: the client's by a profile of the driver's
write path (JFR on this test), the server's only by a form without a block, which Oracle does
not offer for two tables. The key read is within the goal at both levels.

### 2026-10-27: the second Oracle run read (the design): the inline whole-row lock at SNAPSHOT, the nested parses

Run 20261027-0858 ("Second run" in `docs/benchmarks/benchbase-oracle-aws.md`, 6f8936d, by the AWS
session): 1219aac, the statement cache at 128 on both sides, the shared pool flushed before each
reading, fast network. TPC-C (c)/(a): READ COMMITTED inline 0.47 → 0.66, SNAPSHOT decoupled 0.47
→ 0.64, SNAPSHOT inline 0.69 → 0.76; YCSB SNAPSHOT decoupled 0.86 → 1.04, READ COMMITTED inline
0.62 → 0.90, SNAPSHOT inline 0.59 → 0.61. YCSB p50 at SNAPSHOT decoupled, the vendor → the
driver: read 199 → 237, update 341 → 477 (+136; the client-cost entry above predicted +105–120),
insert 354 → 449, read-modify-write 464 → 601. Packets a commit equal the vendor's. The decoupled
READ COMMITTED cell failed before its first run (the harness's logon ran before the tablespace
existed; fixed in the harness, 0298611), so the `FOR UPDATE OF` fix is still unproven.

**The SNAPSHOT inline cell's row-lock waits are the whole-row lock taking the steps.** YCSB's
read-modify-write locks with `SELECT * ... FOR UPDATE`. `Router.lockingRead` builds the plain and
the gated one-statement forms at SNAPSHOT only when the columns read are known (`!n.inline ||
ast.columnsReadFrom(readRef) != null`), so an inline table read whole takes the committed copy's
form, which for a locking read is the steps: the predicate's in-flight check by either image
(155,266 calls in the cell), the lock over the committed copy with its version lookups (79,405
calls, 855 µs elapsed each against 40 of CPU), the keys and versions under the lock (75,859), the
keyed block with the anchors (75,862), the check again; five round trips and a savepoint, the row
held across them. Hot keys then queue: 1,440 row-lock waits for 98 s a run, the holder's own 36 s
of buffer busy waits, read-modify-write p50 1,381 µs against 468. At READ COMMITTED on the same
layout the one-statement form expands `*` to the columns (the cell's lock text shows them) and
read-modify-write is 575 against 490; at SNAPSHOT on the decoupled layout the row-lock waits total
0.3 s. Decided, design 4.3 "The locking read on Oracle", the SNAPSHOT paragraph: an inline table
read whole takes the statement as written with `*` expanded to the user's columns from the
catalog, the metadata columns left out, on either engine (the condition is engine-independent, so
PostgreSQL's inline SNAPSHOT whole-row lock takes the steps today as well). The builder: in the
plain and gated texts (`Writes.onDataTable` and `Writes.gatedLock` under `plainSql`/`gatedSql`),
replace the `*` of the locked inline table by its user columns; tests: the routed text of `SELECT
* FROM <inline> WHERE k = ? FOR UPDATE` at SNAPSHOT on Oracle and PostgreSQL names the columns and
no metadata column, one statement, the row returned as the vendor's; the YCSB SNAPSHOT inline
cell is the proof.

**The batch blocks' nested statements parse on every call because the session's cursor cache
overflows.** `session_cached_cursors` is 50 by default (the container: 50, `open_cursors` 300).
Each entry's body in a block of entries is its own static SQL, so a 16-entry stock block holds 48
nested cursors and the fifteen row-count texts hundreds, far past 50: the PL/SQL cursor cache
evicts them between calls and each nested execution soft-parses (about 3–5 µs of CPU each from
the forms measurement, where a block's two nested parses cost 7; about 50 a NewOrder, so of the
order of 0.1 ms a commit). The single-row blocks' nested MERGEs parse on part of their calls for
the same reason. The batch item above, one text with bulk statements, has three nested cursors
and ends it; raising `session_cached_cursors` is the DBA's stopgap, for both sides alike in a
benchmark, and not a change the driver makes. The remaining server CPU at SNAPSHOT decoupled,
3.00 against 1.28 ms a commit, is mostly the nested statements' execution and the metadata rows'
writes, which the batch item also addresses.
