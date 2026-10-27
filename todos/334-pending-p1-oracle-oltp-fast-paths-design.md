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

### 2026-10-27: two more single-row write forms, INSERT ALL and the raising block (the design)

At the user's request through the AWS session, on the container as the forms measurement above
(`scratchpad/ora/forms2.jsh`; 10,000 calls a cell, two rounds, cache off and on at 128; wall,
server CPU, DB time, parses from `V$MYSTAT`): (e) the insert as one `INSERT ALL` statement into
the data and the metadata table, a plain `executeUpdate`; (f) the update block without OUT
binds, the gated UPDATE then a plain UPDATE of the metadata row, `RAISE_APPLICATION_ERROR` with
a number per zero-row outcome, sent as a plain PreparedStatement. Beside (d) the vendor's
statement and (a) the driver's block.

| per call, cached, round 0 / round 1 | wall µs | server CPU | DB time |
|---|---|---|---|
| update (d) plain | 226 / 254 | 8 / 11 | 32 / 39 |
| update (a) the driver's block | 431 / 489 | 48 / 48 | 117 / 94 |
| update (f) raising block | 466 / 463 | 44 / 42 | 93 / 110 |
| insert (d) plain | 463 / 418 | 23 / 14 | 74 / 71 |
| insert (a) the driver's block | 538 / 542 | 52 / 49 | 152 / 159 |
| insert (e) INSERT ALL | 480 / 540 | 36 / 43 | 130 / 131 |

Uncached: (e) parses once a call against the block's three, 41–45 of CPU against 64–113; (f)
51–62 against 62–82. The cases of (e): a metadata row without a data row (a tombstone) fails
`INSERT ALL` with `ORA-00001: unique constraint (SYSTEM.U2_META_PK) violated` and leaves no data
row (checked: 0 after the failure); a duplicate data row fails with the data table's constraint
named. (f)'s absent key raises ORA-20001 as written.

Reading and decisions. (e) is taken, written into design 4.3 as "The decoupled INSERT as one
statement": the block's own cost goes (server CPU 36–43 against 49–52, DB time 130 against
152–159), what stays over the plain insert is the metadata row's own insert; the tombstone and
the in-flight tombstone fall to ORA-00001 on the metadata constraint, which the driver tells
from the data row's by the constraint name and sends to the block, a second round trip only
then; generated keys keep the block. On AWS the insert's +95 µs should come down by the block's
share, about 25–35, near the goal's edge. (f) is not taken: it measures within 5 µs of the block
(the OUT binds and the callable statement are about 10 µs at the client, the block itself stays)
and buys that with an error path per outcome; the design's answer to the question stands, that
distinct numbers would carry what the counts carry, but there is nothing to buy with them. The
update has no single-statement form on Oracle, no statement writing two tables, so the decoupled
update keeps the block and its +136 µs on AWS stands at about +70 at the server and 35–50 at
the client; the inline layout, at +8%, is the layout that meets the goal for updates.

### 2026-10-27: the batch item designed; the commit-time stamp evaluated and measured; 1070d98 reviewed (the design)

**The batch item, for the Opus session c7.** Design 4.4, the new paragraph "The entries merged
on a clear table at SNAPSHOT": at SNAPSHOT on a decoupled table of which the transaction
recorded no anchor, a batch of one keyed write's or one INSERT's parameter sets runs as one bulk
block per write shape, the same text for every batch size up to 16 (slots bound NULL beyond
`l_n`), the gate once per batch, the user's DML as `FORALL` with `RETURNING <key> BULK COLLECT`,
the counts per entry from `SQL%BULK_ROWCOUNT`, the stamps as one `FORALL` over the keys returned;
the INSERT as `FORALL`, its metadata insert as `FORALL` under the hint, the takeover as `FORALL i
IN INDICES OF` the zeros; the outcomes and the duplicate's replay as the paragraph has them. The
builder's order, as for todo 335: the confirmation tests first on the container, each saying
which part it decides: (1) `SQL%BULK_ROWCOUNT(i)` after a `FORALL` UPDATE with `RETURNING ...
BULK COLLECT INTO` gives each entry's count and the collection the keys in iteration order; (2)
a duplicate in a `FORALL` INSERT raises ORA-00001 out of the block and leaves nothing, the call's
atomicity; (3) `IGNORE_ROW_ON_DUPKEY_INDEX` holds per iteration under `FORALL` and the skipped
row counts 0; (4) `FORALL i IN INDICES OF` a sparse index-by table runs the listed entries only;
(5) a slot beyond `l_n` bound NULL is never touched; (6) the parse count of the nested statements
stays 0 across calls of a 16-entry batch, the cursor cache no longer overflowing. Then the two
forms (`Writes`, beside `oracleKeyedEntries` and `oracleInsertEntries`, chosen where the single
form is the clear-table block), unit tests of the texts as `WritesTest.onOracle_*`, the
outcome rows in `OracleOltpIntegrationTest` (a key without a row among the entries, the gate
closed, a tombstone among the inserts, a duplicate), a local NewOrder-shaped before-and-after,
and the review here before the AWS run.

Measured on the container (`scratchpad/ora/forms3.jsh`, two rounds in opposite orders, fresh
insert tables per round; the cache on at 128; per transaction):

| NewOrder-shaped, 10 stock updates and 10 order-line inserts | wall µs | server CPU | DB time | parses | round trips |
|---|---|---|---|---|---|
| (V) the vendor, two array executions | 2,284 / 1,548 | 285 / 205 | 955 / 610 | 0 | 3 |
| (E) today, two blocks of entries | 3,644 / 4,305 | 1,190 / 1,210 | 2,405 / 2,510 | 40 | 3 |
| (B) two bulk blocks, 16 slots | 2,980 / 3,388 | 455 / 515 | 1,125 / 1,380 | 0 | 3 |
| (B2) the same with index-by table binds | 2,152 / 3,178 | 500 / 645 | 1,205 / 1,630 | 0 | 3 |
| (C) the vendor's array executions of the gated writes, the stamps at commit | 2,006 / 4,799 | 430 / 695 | 1,045 / 2,080 | 0 | 3 |

The wall clock drifts between rounds on the laptop; the CPU and DB time read: the bulk block
takes the server CPU from 1,190–1,210 to 455–515 against the vendor's 205–285, the DB time from
2,405–2,510 to 1,125–1,380 against 610–955, and the nested parses from 40 a transaction to none.
Index-by table binds in place of the slots are no better at the server and `setPlsqlIndexTable`
is deprecated in ojdbc11 23.26: not taken.

**The commit-time stamp, evaluated at the user's request.** The form: every write of the
transaction a plain gated DML of the data row (the keyed write and the INSERT; the write beyond
the key and a generated key keep their blocks), the keys written recorded by the driver, and at
commit one block that stamps them all, a `FORALL` per table under the deemed-committed condition
and the recorded anchor's version where the key was read, then `COMMIT`, in the round trip the
commit costs. Safety, point by point. The data row is locked from the plain write to the commit,
as the block's write locks it today, and every writer of the driver takes the data row before
the metadata row, the lock read now too, so no cycle appears that is not there today. A conflict
that the block raises at the write surfaces at commit instead: at SNAPSHOT the stamp's UPDATE
fails with ORA-08177 for a metadata row changed since the snapshot, at READ COMMITTED the anchor's
version condition finds the row moved, both `40001` and the transaction rolled back, later than
today but the same outcome, and a commit that may fail with `40001` is what the application
handles already. The in-flight check stays per write as the gate in each statement's WHERE, one
probe each; a closed gate takes the write to the coarse check and the slow path as today. A
savepoint the application sets and rolls back to undoes data writes whose keys the driver has
recorded, so the driver must drop the keys recorded after it, through the JDBC savepoint API and
the SQL `SAVEPOINT`/`ROLLBACK TO` it parses; a key missed there would be stamped at commit with
no data change, a version raised for nothing, which concurrent readers would take as a move:
the one new hazard, and a bookkeeping one. ScalarDB Core readers see the data uncommitted until
the commit and the metadata row at its old version, consistent with each other as they are today
before the commit, and both at the new version after; a Core writer's prepare of a key the
transaction wrote blocks on the data row as it does today. READ COMMITTED against SNAPSHOT:
the stamp's condition differs (the anchor's version against the deemed-committed condition
alone), the rest is the same; a key written twice in the transaction is stamped once, one version
per transaction as Core gives it, where today's blocks stamp at each write. In autocommit nothing
changes: the write and its stamp-and-commit are two round trips as the block and its commit are.

Measured (the same harness; per transaction, two or three rounds):

| | wall µs | server CPU | DB time | round trips |
|---|---|---|---|---|
| Payment-shaped, 3 keyed updates and 1 insert: (V) the vendor | 1,920 / 1,964 | 123 / 148 | 530 / 578 | 5 |
| (E) today, four blocks | 2,516 / 2,093 | 265 / 263 | 805 / 673 | 5 |
| (C) plain gated writes, one stamp block at commit | 2,209 / 2,019 | 233 / 218 | 753 / 665 | 5 |
| single update: (V) the vendor | 1,108 / 922 | 120 / 103 | 433 / 397 | 2 |
| (E) today, the block and the commit | 1,444 / 1,301 | 148 / 147 | 582 / 512 | 2 |
| (C) the plain write, the stamp-and-commit block | 1,316 / 1,091 | 160 / 158 | 568 / 507 | 2 |

(A first Payment round ran while another session's suite ran on the container and is left out.)
So the commit-time stamp saves about a block's own cost per write beyond the first: Payment 30–45
µs of server CPU and 40–50 of DB time a transaction; NewOrder the same server cost as the bulk
blocks, its data writes as the vendor's own array executions; a single write nothing, the block
moved rather than removed. Against the batch item it is not an addition but another shape of the
same protocol: with it the bulk blocks are not needed, the batched data writes going as the
vendor's batches and every stamp to the commit. Verdict: not now. It reshapes the write protocol
(stamps deferred, conflicts at commit, savepoint bookkeeping) for about 0.04 ms a Payment of a
gap of 1.7 ms a commit, where the batch item takes most of NewOrder's with one new block form
and no change of protocol. Revisit after the batch item is in and the flushed per-statement
figures show what the single-write blocks still cost.

**1070d98 reviewed: the inline whole-row lock at SNAPSHOT.** Read from the commit (the AWS
session built it at the user's request). `SqlAst.stars()` places each `*` and `q.*` item;
`Router.spellableStars` takes them for a one-table statement reading an inline table whole,
re-parses the spelled text and keeps the steps when the columns read are still unknown (a
NATURAL join, a whole-row reference); `spelled` replaces the items, last to first, in the
`onDataTable` text and in the gated lock's, both edited only after the select list, so the
offsets hold; the plain form's condition is `stars != null`, an empty list for a column list
known, so a decoupled table and a known list route as before, and PostgreSQL's inline whole-row
lock takes the one statement too. The routed texts name the user's columns and no metadata
column, under the lock's marker, the gate kept. Matches the decision. The tests decide it on
Oracle (`form5LocksAnInlineRowReadWholeInOneStatement`: `*`, `ti.*`, `a.*`; one round trip gated
and one plain; the row the vendor's; a second connection's NOWAIT ORA-00054) and on PostgreSQL
(`InlineIntegrationTest`'s three star forms at SNAPSHOT). One ask before the proof run: the
Oracle `InlineIntegrationTest`, which failed on the container's leftover users and not on the
change, on a fresh container.

The ask on 1070d98 is met (the AWS session, 2026-10-27): the Oracle `InlineIntegrationTest`
passed on a fresh container, 3 of 3, the star forms at SNAPSHOT among them. The review of 1070d98
is closed; its proof is the SNAPSHOT inline YCSB cell of the next AWS run.

### 2026-10-27: 438d627 reviewed (the design), INSERT ALL on the branch oracle-insert-all

Read from the commit (rebased onto 3c8ef6f, not on main). `Writes.oracleInsertAll` emits the
decided text, the data INSERT as written with `ALL` after its first keyword and the metadata
`INTO` with the key's values again and the stamp, `SELECT 1 FROM dual`; its binds the
statement's, the key's again, the stamp's; built for one VALUES row whose key the statement gives
and is not generated, with nothing after the row and every placeholder inside it; run unless
the application asked for generated keys; batches keep the block of entries. ORA-00001 is read
by its constraint's table from `all_constraints`, once per name and cached: the metadata
table's sends the row to the block, which takes it over or aborts on a row in flight; any other
is the duplicate as before. The tests decide it (form 4: one round trip for the first insert, the
tombstone taken over at the next version; the duplicate, the in-flight key, the in-flight
tombstone, generated keys). Matches the decision.

One ask before it lands, not a hold on the design: the key's values are repeated by their source
text, so a value that is an expression is evaluated again in the second `INTO`. Oracle evaluates
a sequence's `NEXTVAL` once per subquery row across the `INTO` clauses and `SYSDATE` once per
statement, but a user's function may answer differently twice, and the metadata row would then
carry a key the data row does not, silently. Take the single statement for a key given as
placeholders and literals and leave any other expression to the block, which carries the key
through `RETURNING ... INTO`. A note, no change asked: `ALL` placed right after `INSERT` lands
before a hint comment the user wrote there, which Oracle then reads as a comment; the hint is
lost, nothing else.

The AWS session's finding is logged with the test: `SQL*Net roundtrips` in `V$MYSTAT` does not
count a failed call, so the tombstone path reads one round trip there though it is two on the
wire.

The ask on 438d627 already held: the key's values reach the statement through
`StatementShape.insertKeyValues`, which gives them only when each is a placeholder or a literal
(signed or cast), and on Oracle an INSERT whose key is any other expression is refused before any
form is chosen. The builder added the assertion (`VALUES (ABS(?), 'a')` refused) as c94dd1a on
the branch. The review of INSERT ALL is closed; it waits on the user's decision to land it.

### 2026-10-27: bedbdb5 reviewed (the design): INSERT ALL's metadata key as the data row stores it

The builder found, after the review above, that the metadata row's key was the value the
statement gave while the data row holds the value its column makes of it: on the imported CHAR(5)
key `'b'` the metadata row held `'b'` against the data row's `'b    '`, and CHAR against the
metadata table's VARCHAR2 compares without padding, a data row without its metadata row,
silently. The block never had it, its `RETURNING ... INTO` carrying the stored value. The fix
casts each key value in the metadata `VALUES` to the data column's declared type (`CAST(? AS
char(5))`, `CAST(? AS number(10))`, the spelling the duplicate checks use), and the test
`form4InsertAllKeysAsTheDataRowHoldsThem` decides it on a CHAR(5) key, a NUMBER(10) key of 7.6 and
a TIMESTAMP(6) key of milliseconds, none left without its metadata row. Matches; the design's
paragraph now says the cast and why. One ask: where the column's type is not known to the driver
(`type(...)` empty) the fix falls back to the uncast value, the case just found; take the block
there instead, which is right whatever the type. Noted, ScalarDB's, not ours: the metadata key of
a TIMESTAMP(6) column is TIMESTAMP(3), so a key of microseconds finds no metadata row by either
form.

The ask on bedbdb5 is done (0072b54 on the branch): `oracleInsertAll` returns null, the block,
when any key column's type is unknown, asserted with a column map lacking the key. The INSERT
ALL review is closed again; the branch waits on the user's decision to land it.

### 2026-10-27: the batch item's build reviewed (the design): oracle-bulk-batches at 9bbacd2

Read from the commits (afbd1e6 the six confirmation tests, a02b072 the stamp factored out of the
clear-table block with its text unchanged, ace0d6e the forms, 9bbacd2 the measurement; on
oracle-insert-all, merging cleanly with main). The texts are the design's: one block per shape
and set of slot types, cached, 16 slots; the probe once (`SELECT COUNT(*) INTO l_g ... AND
ROWNUM = 1`), `FORALL i IN 1 .. l_n` over the DML without the per-row gate with `RETURNING ...
BULK COLLECT`, the `SQL%BULK_ROWCOUNT` loop, the stamp as one `FORALL` over the keys returned
through the clear-table stamp the single form uses; the INSERT as `FORALL`, the metadata insert
as `FORALL` under the hint with the keys cast to their columns' types, the takeover as `FORALL i
IN INDICES OF` the zeros; OUT `l_g`, the keys returned, the rows stamped, the 16 counts. Chosen
in `applyOracleEntries` before the block of entries, at SNAPSHOT, on a decoupled table without a
recorded anchor, the keyed plan where the fast form exists and the INSERT where INSERT ALL does,
not when generated keys are wanted; one stamp for the block, the lead run's; the anchors told of
it. The six confirmation tests decide the facts the paragraph rests on (per-entry counts with the
keys in order, the duplicate's atomicity, the hint per iteration, `INDICES OF`, NULL slots
untouched, no parse calls over 20 calls of a 16-slot block), and the form tests the outcomes (a
missing key among the entries, the gate closed, a tombstone among the inserts, a duplicate as a
`BatchUpdateException` with the counts before it and `23000`). Matches.

Two deviations, both accepted and written into the paragraph: `setDouble` and `setFloat` keep the
block of entries, since the driver cannot tell whether the connection sets
`SetFloatAndDoubleUseBinary`, as does a placeholder whose entries bind different types; and
`l_g` 1 sends the entries to the block of entries at once without the coarse check, which at
SNAPSHOT reads the same snapshot as the gate and would find the row, a round trip saved.

Measured through the driver (todo 335's log, a NewOrder-shaped transaction, 1,000 after 50 warm,
before/after/after/before): server CPU 760/890 → 350/260 µs, DB time 1,780/1,590 → 930/830,
parse calls 50 → 0, wall 2,752/2,608 → 1,767/1,582, three round trips either way. In line with
the harness above. Landing the two branches waits on the user; the proof is the TPC-C SNAPSHOT
decoupled cell of the next AWS run.

### 2026-10-27: both Oracle branches on main (the design's note)

At the user's request the Opus session rebased the two branches onto main at 481f143 and
fast-forwarded main to d13f78f; nothing is pushed. The hashes the reviews above name, as
rebased: INSERT ALL 438d627 → 71277c0, c94dd1a → 1168704, bedbdb5 → ba51b0b, 0072b54 →
9bea11a; the bulk block afbd1e6 → 2660f5a, a02b072 → 9f98181, ace0d6e → 15d29a5, 9bbacd2 →
d13f78f. Unit tests, SpotBugs, the Oracle OLTP and forms classes pass on the rebased code. With
the inline whole-row lock (1070d98) and the `FOR UPDATE OF` fix (d56e87d) already on main, the
Oracle proof run can go from main: the decoupled READ COMMITTED cell for the lock's scope, the
inline SNAPSHOT cell for the whole-row lock, and the decoupled cells at SNAPSHOT for INSERT ALL
and the bulk block.

### 2026-10-27: the bulk block and BenchBase's order_line batch (the design's decision)

Run 20261027-1344 (6e, main 2162759): at SNAPSHOT decoupled the stock batch went bulk (43k calls,
432k rows, 4 parses) and the order_line batch did not, BenchBase's NewOrder binding OL_AMOUNT
with `setDouble`, which 4.4 kept on the block of entries; TPC-C 0.63 against 0.64 before. c7
proposed reading `SetFloatAndDoubleUseBinary` from the properties, the URL and the system
property. Measured on the container (`ora/dbl*.jsh` in the design session's scratchpad): ojdbc
honours the setting from the properties, the URL, the system property and the `ojdbc.properties`
file under TNS_ADMIN (by the URL parameter or `oracle.net.tns_admin`), and reports the resolved
value through `OracleConnection.getProperties()` under the key `SetFloatAndDoubleUseBinary` alone,
the `oracle.jdbc.`-prefixed key absent: `false` by default, `true` for `true` or `TRUE`, `false`
for `yes`. `setDouble`, `setFloat` and `setObject` of a Double or a Float all follow it, NUMBER
(Typ=2) off, BINARY_DOUBLE (101) and BINARY_FLOAT (100) on, `setObject(d, Types.NUMERIC)` too.
Through a block whose element is typed as the bind, ten doubles (0.1, 1.005, 2.675, 0.1+0.2, 1e-7,
1234.5678, -0.005, pi, 9999.994, 1/3) stored into NUMBER(6,2), NUMBER, BINARY_DOUBLE and
BINARY_FLOAT columns are byte-identical by DUMP to the statement alone, under both settings. Typed
BINARY_DOUBLE against a NUMBER bind, 7 of 10 rows differ (2.675 → 2.67 for the statement's 2.68 in
NUMBER(6,2), 0.1's binary expansion for 0.1 in NUMBER): the exclusion was right while the setting
was unknown. Typed NUMBER against a BINARY_DOUBLE bind the rows match, the comparison semantics
would not. Decided: the element typed by ojdbc's resolved properties, read once at connect, not
from the sources, which would miss the file and ojdbc's own parsing; a connection whose
properties lack the key keeps the block of entries for such binds. Design 4.4 amended. Handed to
c7 to build, with the confirmation test under both settings (the DUMP comparison, and the key's
presence in `getProperties()`, so that an ojdbc change fails the test rather than the fast path
silently). Proof: a SNAPSHOT decoupled TPC-C rerun, the order_line batch counted as bulk calls.

### 2026-10-27: the double's bulk element reviewed (the design's note)

c7 built it as 5d10929 on the branch oracle-bulk-double, on top of 4935bf4, not on main. Reviewed
from `git show`: matches. `Proxies.binaryDoubles` reads the short key from ojdbc's resolved map by
reflection once per Oracle connection, null with a DEBUG line when absent or unreadable;
`Boolean.parseBoolean` agrees with ojdbc's own normalisation; `plsqlType` types `setDouble`,
`setFloat`, a two-argument `setObject` of a Double or Float and a `setNull` of DOUBLE, FLOAT or
REAL as the paragraph says, null where the connection does not say; `nullTypeOf` pads the
`BINARY_*` slots as DOUBLE and REAL; `Bulk.types()` still refuses a placeholder whose entries
differ. The test's property reaches ojdbc (`Engines.openDriver` merges it,
`DriverProperties.vendorProperties` passes every non-prefixed key through). One ask: the form-4
test asserts the stored values only under the default; the binary iteration needs its mirror
(2.675 → 2.67 in the NUMBER(6,2), 0.1's expansion in the NUMBER), or a dropped property would pass
both iterations as NUMBER untested. The seed row the test needs (ORA-08177 on the first INSERT
into a segment-less table at SERIALIZABLE) is Oracle's own, a documentation point for the
transactions guide. Landing waits on the user; the proof is 6e's SNAPSHOT decoupled TPC-C rerun.

The ask met as 4834c21 (on 5d10929, the branch oracle-bulk-double): under the binary iteration
the test asserts 2.675 stored as 2.67 in the NUMBER(6,2) and 0.1 in the NUMBER as one row that is
not .1; only the test and todo 335's log changed, the main code as reviewed. Both iterations run
as the bulk block, 4 statements each. Review closed; landing on main waits on the user.

### 2026-10-27: the third run read (the design's note on the scan, the update and the client)

Run 20261027-1344 complete (135faaf, "Third run"): the deadlock fix and the inline whole-row lock
proven (SNAPSHOT inline read-modify-write 522 against the vendor's 500); INSERT ALL +14/+17%; the
bulk block takes the stock batch, the order_line batch after 4834c21. Three figures read here,
with the container (`ora/stamp.jsh`, `proxy.jsh`, `scan*.jsh`, `realscan*.jsh`, the scratch
worktree's `ClientCostIntegrationTest` with a 500-row scan added; all in the design session's
scratchpad):

- **The decoupled update, +34–46%** (SNAPSHOT decoupled YCSB: vendor 357 → 521 µs p50). Server:
  the clear-table block 147 µs elapsed / 90 CPU a call against the vendor's UPDATE at 34 / 21;
  inside it the data UPDATE with RETURNING 26 µs, the stamp as MERGE 56 elapsed / 33 CPU, the gate
  about 5, the block's own the rest. The MERGE is not the lever: on the container the stamp as
  `UPDATE ... IF SQL%ROWCOUNT = 0 THEN INSERT` costs the same as the MERGE (block 39–48 against
  40–43 µs CPU; alone 14–16 against 21–22). What stands out at AWS is the MERGE's 44,232 parse
  calls on 99,150 executions and 588 ms of `cursor: pin S wait on X`, where the container parses
  once per 10,000: a cursor re-parsed or invalidated, not explained from here (asked of the next
  run: V$SQL per child for that SQL_ID, loads, invalidations, bind sensitivity). The floor of a
  decoupled single-row update on Oracle is two DMLs in one block: no form change proposed; the
  remaining lever is the client, +50 µs a transaction of JVM CPU (6e's recording), of which the
  callable statement's own cost is ojdbc's.
- **The scan at decoupled READ COMMITTED, 2,306 → 4,010 µs p50.** Server CPU 1.49 against 0.42 ms a
  call for the same 500 rows (3.5x). The committed copy joins the metadata row per row for the
  anchors (4.2, the RC form). On the container, with the driver's real text (a UNION ALL of the
  committed copy's `LEFT JOIN T_meta ... WHERE COALESCE(m.tx_state, 3) NOT IN (1, 2)` and the
  before-image arm) under statistics of a 1M-row table as the harness leaves them (data table
  gathered whole, the metadata table's rows counted at setup after the backfill, its key column
  without statistics), the optimizer plans a HASH JOIN OUTER over two range scans: 82 logical
  reads against the vendor's 76 (+8%), CPU within drift. Forced or chosen nested loops (a unique
  probe of the metadata row per row) cost 592 logical reads and 2.5x the CPU: that is what the
  setup's statistics locked on an *empty* metadata table give once the table is filled (num_rows
  0, locked, measured: NESTED LOOPS OUTER, 592 reads), the staleness of todo 094, and what the AWS
  figure looks like. The imported YCSB table is backfilled before its statistics are taken, so the
  AWS plan is not explained by that; asked of the next run: V$SQL_PLAN of the top statements and
  the two tables' `all_tab_statistics` and key-column statistics at run start. A once-gated form
  without anchors measures the same 80 reads as the hash join, so dropping the scan's anchors buys
  nothing once the plan is right; a lazy anchor by SCN is out, a plain user reads no SCN
  (`DBMS_FLASHBACK`, `V$DATABASE` not granted; `TIMESTAMP_TO_SCN` approximate). The key read is
  nested loops at 6 reads whatever the statistics. Client: the driver adds 360–840 µs of CPU to a
  500-row scan at READ COMMITTED (the three hidden columns read and an anchor recorded per row,
  +420 KB allocated) and 150–350 at SNAPSHOT (+200 KB); the SNAPSHOT decoupled scan's +368 µs at
  AWS is that client cost, its server form being the once-probed CASE (80 reads).
- **The reflection under the proxies, 12% of the driver run's samples.** A `java.lang.reflect.Proxy`
  call over ojdbc's ResultSet costs 21 ns against 18 direct (JDK 25, `Method.invoke` as the handler
  does it); the name switch 10 more; a bound MethodHandle 127. The samples are the volume: a
  500-row scan makes about 6,000 proxied calls, 25–60 ns each through `AnchoredHandler.handle`,
  the 150–350 µs above; a key read's twenty calls are under a microsecond. Plain delegating classes
  would recover most of a scan's client cost and nothing on a single-key operation, so they do not
  serve the 10–20% goal on key reads and writes; a fast path in the handler for `getXxx(int)`
  within the visible columns is the cheap part of the same gain. Not proposed now.

### 2026-10-27: the double's bulk element on main (the design's note)

With the user's approval c7 rebased the branch oracle-bulk-double onto main at 804b9e8 and
fast-forwarded main to bdbdd21: 5d10929 → 65d83c3, 4834c21 → bdbdd21, both identical to the
commits reviewed above (`git range-diff`). Unit tests, SpotBugs and the two Oracle classes pass on
the rebased tree; nothing is pushed. Main now holds every Oracle item of todo 334 and the harness
captures of 804b9e8; the proof is the SNAPSHOT decoupled TPC-C rerun, the order_line batch as
bulk block calls.

### 2026-10-27: the fourth run read; the update by part, and what is left of it (the design's note)

Run 20261027-1617 (bdbdd21, decoupled at both levels; the benchmark session's write-up in
docs/benchmarks/benchbase-oracle-aws.md, 0774142), read with the harness's new sections and
measured on the container where the run left a question. The user's order is YCSB's single-key
operations first, the update above all: the latest p50 gaps against the vendor are inline SNAPSHOT
+16/+7/+12/+4 (read/update/insert/RMW), inline READ COMMITTED +15/+30/+15/+23, decoupled READ
COMMITTED +13/+38/+16/+30, decoupled SNAPSHOT +19/+46/+18/+33. Reads and inserts are within the
goal; the update and the RMW, which is the update again, are not.

**What the READ COMMITTED update sends, and why inline READ COMMITTED is +30% where inline SNAPSHOT
is +7%.** At SNAPSHOT on an inline table the update is the vendor's statement with the stamp in its
SET list and the gate in its WHERE (`UPDATE usertable SET tx_id = :1, ..., FIELD1 = ... WHERE
YCSB_KEY = ... AND NOT EXISTS (in-flight probe)`): one execution, 28 µs elapsed and 14 of CPU at the
server against the vendor's 35/22 (third run, bb-inline-d0), the gap the client's. At READ
COMMITTED the update is the keyed block (`Writes.keyedBody`): step 1 `SELECT 1, tx_id, tx_version,
tx_state, key INTO ...` on the predicate, then under `rows = 1 AND COALESCE(state, 3) = 3` the UPDATE
with the stamp and the CAS on the version just read (`COALESCE(tx_state, 3) = 3 AND DECODE(tx_id,
l_id, 1, 0) = 1 AND COALESCE(tx_version, 0) = COALESCE(l_version, 0)`), and the row back through OUT
binds (rows, written, id, version, state, the key), the client comparing the version with its
anchor. One round trip but two executions and a PL/SQL call: 127 µs elapsed, 69 of CPU, of which the
read 15 and the UPDATE 52 (bb-inline-rc-d0), against the vendor's 38; and at the client 95 µs of CPU
against the vendor's 34 (the container, clientcost3), the block's binds and OUT binds, where the
SNAPSHOT form costs 58. The decoupled READ COMMITTED block is the same shape with the metadata read
and stamped: 140 µs = the read-back 16 + `UPDATE T_meta` 34 + `UPDATE T` 28 + about 62 of the call.

**The decoupled SNAPSHOT update by part (fourth run, bb-d0 YCSB).** The block 130 µs elapsed, 62 of
CPU, 7.8 gets: the nested `UPDATE USERTABLE` 23 (11 CPU, 3.3 gets), the nested `MERGE INTO
USERTABLE_tx_metadata` 50 (23 CPU, 4.5 gets, a parse call on 44% of executions), the PL/SQL call and
the two cursor executions the remaining 57; the vendor's UPDATE 31. The RMW's update is a second
block shape, ungated after the locking read, 171 µs with its nested UPDATE at 54 (9 gets, 826 parse
calls), which I have not explained. At the client +17 µs of CPU (58 against 41).

**The MERGE's parse calls are not the cursor cache's size.** On the container (forms4, forms5: the
instance's `session_cached_cursors` at 50 and at 500, set `DEFERRED` before the session opens) the
block of entries parses 40 nested statements a call at either setting, and the bulk block 0; the
parse calls are session-cursor-cache hits. The rule, measured (cc, cc2): one anonymous block whose
nested text occurs once keeps that cursor open across calls, 0 parse calls; the same text in k
places of one block parses k times a call; two or three blocks sharing one nested text parse once a
call, each evicting the other's; a hint comment per occurrence (`/*+ o1 */`, since PL/SQL strips
plain comments) makes the texts distinct and the parse calls 0. The cost is small: at k = 20, 293–313
µs of CPU a call with the parses against 260–320 without, a few µs an execution. So the decoupled
update's MERGE parses on 44% of executions because the two block shapes (gated, and ungated after the
locking read) share its text; the district stamp in TPC-C on 50% for the same reason; and the block
of entries at READ COMMITTED parses every nested statement of every entry (2,363,298 parse calls in
the RC TPC-C run's 5.1 M calls, 449,183 on 449,310 executions of each nested statement). The design's
sentence on `session_cached_cursors` is corrected by this: the storm is the texts' repetition, not
the cache's size, and `ALTER SESSION SET session_cached_cursors` would not remove it.

**The stamp as a row trigger, measured (forms6), the one packaging that makes the decoupled update one
DML.** A trigger `AFTER UPDATE ON T FOR EACH ROW` stamping `T_meta` from `SYS_CONTEXT('USERENV',
'CLIENT_IDENTIFIER')` and MODULE/ACTION, which ojdbc's `setClientInfo("OCSID.CLIENTID")` sends with
the next call: round trips 2 a transaction as the vendor's (the identifier rides along), executions
2. Per transaction, three rounds: the vendor 52–65 µs of server CPU, DB time 198–265; today's block
93–105, 297–330; the trigger 87–123, 270–337 with the identifier set each transaction, 88–107,
248–320 set once. The server cost is the block's: a nested DML in a PL/SQL context costs the same
whether the block or the trigger holds it. What the trigger saves is the client's CallableStatement
and OUT binds. Not proposed: a trigger per decoupled table, a stamp carried in session attributes
of 64 bytes, and firing rules for sessions that are not the driver's, for a client-side gain the
fast path below gets without any of it.

**Proposals for the update.**
1. *The CAS-first fast path at READ COMMITTED, the update as one statement.* The vendor's UPDATE
   with the stamp (inline) or the stamp's UPDATE of `T_meta` then the DML (decoupled, in the block
   without the read-back), its WHERE carrying the deemed-committed condition and, when the
   transaction recorded an anchor for the key, `tx_id = :anchor_id AND tx_version = :anchor_version`;
   a count of 1 settles the row (the anchor held, the row was committed, the first use records
   `wrote(tx_id)`); a count of 0 goes to today's block, which classifies it (no row, in flight and the
   wait, the anchor moved and the abort), the rare path paying the round trip. Expected: inline READ
   COMMITTED's update from 127 to about 30 µs at the server and from 95 to about 58 µs at the client,
   the +30% to about SNAPSHOT inline's +7–10%; decoupled READ COMMITTED's block from 140 to about 120
   and the client's 95 to 58, the +38% to about +25%; the RMW the same way. This also makes READ
   COMMITTED run about as fast as SNAPSHOT on the update, todo 338's condition for it to stay the
   default.
2. *Distinct nested texts.* A hint comment per block shape in the stamp (`MERGE /*+
   scalardb_native_gated */`, ...) and per entry in the block of entries: the MERGE's 44% and the
   district's 50% parse calls gone, about 15 µs an update at SNAPSHOT decoupled; the READ COMMITTED
   batches' 2.36 M parse calls gone even before item 4 below. One line in the builders.
3. *The decoupled update's floor.* Two DMLs in one call: the data UPDATE 23, the stamp 34–50, the
   call about 45 lean (fewer OUT binds, no read-back) against the vendor's 31, about +70 µs at the
   server and +17 at the client, +25% at the fast network's 345 µs base. Not 20%: the layout's second
   row costs a second DML, and no Oracle statement writes two tables. Smaller levers, unmeasured: the
   metadata table of an imported table as an index-organized table (the stamp's 4.5 gets to about 2,
   the driver creates that table); the RMW block's nested UPDATE at 54 µs and 9 gets against the
   plain shape's 23 and 3.3, worth a plan. The inline layout is the one that meets +20% on the update.

**TPC-C, from the same run (for later, the user's order being YCSB first).**
- *READ COMMITTED's NewOrder batches are the block of entries, at 1,159 and 984 µs a call.* The stock
  updates: 11 texts (one per batch size), 44,853 calls, 897 µs of CPU and 161 gets a call; the
  order_line inserts: 11 texts, 44,855 calls, 559 µs of CPU, 137 gets; together 2.1 ms of server
  elapsed a NewOrder against SNAPSHOT's two bulk blocks at 414 + 371 and the vendor's two array
  executions at 102 + 74. The merged form of 4.4 ("the entries merged, one statement for all") is
  the item, with the CAS-first shape of proposal 1 per entry: FORALL over the slots with the anchor
  per entry in the WHERE, `SQL%BULK_ROWCOUNT(i)` per entry, the entries counting 0 to the block of
  entries. Local figures (forms5): the block of entries 767–813 µs of CPU and 1,760–1,787 of DB time
  a NewOrder pair, the bulk block 267–333 and 807–960. Expected at AWS: about 1.2 ms a NewOrder, the
  READ COMMITTED cell from 0.51 toward SNAPSHOT's 0.62.
- *SNAPSHOT's TPC-C is Oracle's SERIALIZABLE on two hot blocks.* Scale 4 with 4 terminals puts the
  four warehouse rows in one block and the forty district rows in one; Payment's plain reads after
  its own updates wait on `buffer busy waits` (55 s a run for the driver, 49 for the vendor, 3.5 ms a
  wait; the district read 838 µs elapsed for 13 of CPU, the vendor's 402; the warehouse read 265
  against 260). Reproduced on the container (bbw, bbw2): four sessions on their own rows of the two
  blocks, Payment's four statements, at READ COMMITTED the reads 23–25 µs at the server; at
  SERIALIZABLE the warehouse read 828 µs of which 771 concurrency wait, the district read 49, and
  with twenty reads before them (an older snapshot) 940 and 2,360; the waits in V$WAITSTAT's data
  block class, V$SESSION naming no blocking session, p3 65537 and 1 on the warehouse block; `CR blocks
  created` 2.7 a transaction. The same runs raise ORA-08177 on 18–25% of transactions with no two
  sessions on one row (INITRANS 16 changes nothing), and the AWS run's parse-call counts place its
  failures at the district row: the vendor's `SELECT ... FOR UPDATE` 6,292 and `UPDATE district`
  5,116 parse calls, its 15,831 "unexpected" transactions 8.2% of the run, the driver's 5,477 4.5%.
  Both sides pay; the driver's Payment reaches the district read later, so its district wait is twice
  the vendor's. Not a driver item: the benchmark's shape at this scale under Oracle's SERIALIZABLE
  (one row a block for warehouse and district, PCTFREE 99, would isolate the driver's own cost).
- *The YCSB scan at scale 1 is bind peeking on 1,000-row statistics.* The driver's READ COMMITTED
  scan is a hash join of two full scans (1,660 gets, 3.65 ms); the vendor's own scan is a full scan
  in one cell (1,560 gets, 4.9 ms) and an index range scan in the other (104 gets, 788 µs), by the
  binds peeked at the first parse; the table grows from 1,000 to about 19,000 rows in the run. The
  next run's 1 M rows settle it; with correct statistics the driver's join is +8% reads (the third
  run's note).

For the next run (YCSB A/C/E/F at 1 M rows): keep the cursor and plan sections; add V$WAITSTAT's
deltas and V$SYSSTAT's `CR blocks created` and `data blocks consistent reads - undo records applied`
per cell, cheap and the serializable artifact's signature; and the client's CPU per operation if the
plan-file harness can run a JFR cell, the READ COMMITTED update's 95 µs being the one client figure
that matters now.

### 2026-10-27: the CAS-first keyed write handed to the implementation session (the design's note)

The user chose proposal 1 with proposal 2 folded in, the floor (proposal 3) left as the record.
The form is in design 4.3 at b50c8db ("The keyed write at READ COMMITTED on Oracle, the statement
first and the block on a miss"): the statement with the state condition and the recorded anchor in
its WHERE, today's block on a miss; the anchor looked up by the key as bound, the block kept where
the table has versions recorded and the key is not found (the CHAR-key hazard); the decoupled form
the block without step 1, two OUT binds; a hint per block shape in every nested text. Sent to c7 to
build with the tests and the measurement the design lists; review from `git show` when it comes.

### 2026-10-27: ca325c8 reviewed, the statement first built as designed (the design's note)

c7's ca325c8 on `oracle-rc-cas-first` (rebased on cc17393), read from `git show`: mergeable as it
stands. Checked against the design: the inline texts (the stamp first in the SET list, `(predicate)
AND COALESCE(tx_state, 3) = 3` and the anchor by `DECODE`/`COALESCE`, the DELETE without a stamp);
the decoupled block (the DML under `EXISTS` of the committed metadata row and the anchor in `l_ai`,
`l_av`, `RETURNING` the key into `l_k<i>` of the data column's type, the CAS by it under the same
conditions, `written` and `stamped` out; a created table's DELETE deletes the metadata row, an
imported one stamps it, as `keyedStampAfter` has the block do); the bind order on both (inline: the
stamp, the user's, the anchor; decoupled: the anchor, the user's, the stamp, the two outs);
`applyRcFirst`'s rule (not recorded: plain; recorded and found: anchored; recorded and not found,
or a bind the proxy does not read back: the block); the outcomes (0: the block; 1 and 0: abort;
more than 1: abort); `anchors.wrote` on the hit. `hinted` runs once per text (`Block.steps`, the
`Entries` and `Bulk` caches); the fast path is reached with the default in-flight wait of 0, since
`clearRc` and `predicateRc` are PostgreSQL forms (`rc`); `keySources` accepts what `fullKey` does.

c7's five points. (1) The key conversion holds: `readKey` records a NUMBER key by `getObject`, and
ojdbc 23 returns NUMBER, NUMBER(10) and NUMBER(19) integers at scale 0 (5, 500, 1000000,
12300000000 and -700 checked on the container), which `BigDecimal.valueOf(long)` equals. (2) The
CHAR literal to the block, the padded bind to the statement, as the design says. (3) Confirmed: a
write without a read has no anchor, and the vendor's statement at READ COMMITTED writes over the
other's committed row once the lock is given; the block's 40001 there was its own step 1 taken as
an anchor the transaction never read; the design's abort is "between the read and the write"; one
sentence added to the design. (4) The name per occurrence with the block's hash is right, a shape
alone leaving the clear-table stamp and the per-entry texts shared. A name merged into an existing
hint comment is safe: Oracle 19c's documentation ("the database does consider other correctly
specified hints in the same comment") and the 23ai container (`FULL(t)` applied with the name
before it and after it; `INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(..) scalardb_native_k2_dead */`
still ignores the duplicate); the hint report lists the name as `E - Syntax error`, cosmetic, noted
in the design. (5) The CAS by the data row's key: the `EXISTS` compares `m.k = t.k` and the CAS
`k = l_k0` of the data column's type under the same comparison semantics (nonpadded where the
metadata key is VARCHAR2 and the data key CHAR), so a match of the one is a match of the other; an
imported CHAR key cannot abort falsely, it takes the block where the metadata spells the key
otherwise.

Notes, no change asked: a second write of a key read and written goes through the block (the
anchor is the first read's, `putIfAbsent`; the row carries the own stamp), 2 round trips, rare in
YCSB and TPC-C; `Context.value` returns null for the three-argument `setObject` and the temporal
setters, the block then; `written > 1` is unreachable under the full key. The container figures:
the inline update at the vendor's cost (executes 1, server CPU +7 µs over the vendor); the
decoupled form's one execution less did not show on the laptop. Next: c7 merges to main; 6e's
YCSB single-key plan on the user's approval measures both layouts, a JFR cell with it.

### 2026-10-27: the scan at READ COMMITTED on a decoupled table, where its +47% goes and what can go (the design's note)

Run 20261027-1948's first cell (RC decoupled, YCSB at 1M rows, 60 s; main at 5cfffc3), 6e's
reading: YCSB E's scan 2,031 → 2,979 µs p50 (+47%), its insert 443 → 564; A's update 327 → 418
(+28%, from +34–38% before the statement first); F's RMW 420 → 551 (+31%); C's read 93 → 110
(+18%). The scan's V$SQL: the vendor's range read 748 µs elapsed / 306 CPU / 104 gets a call for
about 500 rows; the driver's 1,307 / 621 / 119: the server adds about +560 elapsed and +315 CPU a
scan with nearly the same gets, the plan the right one (a HASH JOIN OUTER of the two range scans,
UNION ALL the in-flight arm's INLIST ITERATOR), and the rest of the +950 µs, about +390, is the
client, the JFR of the run to confirm. The form is 4.2's READ COMMITTED read of a decoupled table
on Oracle: the committed copy `T LEFT JOIN T_meta m ON m.k = t.k WHERE COALESCE(m.tx_state, 3)
NOT IN (1, 2)` carrying `m.tx_id, m.tx_version` per row for the anchors, UNION ALL the in-flight
arm; the user's predicate pushed into both.

**The server's part, taken apart on the container** (`ora/scan1.jsh`; 200,000 rows of ten 100-char
fields and a metadata row each, 500-row ranges, fetch size 100, 200 executions a form after a
warm-up, V$SQL per execution; the first run on a quiet laptop, the later ones under a load average
of 25 at 2–3x the figures and noisy, used for the ordering only; `scan3.jsh`, `scan4.jsh`):

| form, per 500-row scan | elapsed µs | CPU µs | gets |
|---|---|---|---|
| the vendor's `SELECT *` range | 1,002 | 416 | 83.6 |
| the driver's form, hash join and the arm | 1,513 | 734 | 93.6 |
| the join alone, no arm | 1,306 | 657 | 89.6 |
| no join, the arm kept (no anchors, no exclusion) | 1,085 | 487 | 87.6 |
| the join as MERGE (a SORT JOIN on the metadata side) | 2,023 | 1,064 | 93.6 |
| the join as NESTED LOOPS (a unique probe a row) | 1,585 | 736 | 607.9 |
| the join over a covering index `T_meta (k, tx_state, tx_id, tx_version)` | 1,362 | 663 | 92.7 |

So the driver's +511 elapsed / +318 CPU on the container is AWS's +560 / +315, and of it the join
is about +300 / +240 (the metadata range scan, its table access, the hash join over 500 rows), the
arm and the union about +200 / +80. The optimizer's hash join is the right method: merge sorts,
nested loops read 6x the blocks. The covering index saves −150 / −70 a scan and the optimizer takes
it unhinted, but the metadata stamp then maintains it on every write: the stamp's UPDATE 98
elapsed / 27 CPU / 10 gets against 36 / 10 / 5 without (+17 CPU a write, the data UPDATE not
counted); a choice per table for a scan-heavy one, the user's index, not a driver change. Without
anchors the committed copy still has to leave rows in flight out, as one anti-join against the
in-flight index's range (`t.k NOT IN (SELECT q.k FROM T_meta q WHERE q.tx_state IN (1, 2))`, a
HASH JOIN RIGHT ANTI whose build side is empty), which the loaded runs did not resolve against the
join; the floor without anchors lies between the no-join row above (+71 CPU) and that.

**The client's part** (the third run's note above, `ClientCostIntegrationTest` with a 500-row scan;
the run's JFR to confirm): the driver adds 360–840 µs of CPU to a 500-row scan at READ COMMITTED,
the three hidden columns read and an anchor recorded per row (+420 KB allocated), and 150–350 at
SNAPSHOT, the proxied calls (about 6,000 a scan at 25–60 ns). The anchors' own share is the
difference, 200–500 µs: per row `readKey` (which calls `getMetaData().getColumnTypeName()` per key
column per row), `getString` of `tx_id`, `getObject` of `tx_version` as a BigDecimal, the key as a
List, a `Version`, and a synchronized `HashMap` insert (`Anchors.read`, first read wins).

**The three directions.** (a) A table known clear is not a READ COMMITTED fact across statements:
each statement's snapshot is its own, and a ScalarDB prepare between two statements puts a row in
flight that the first statement's probe did not see. Within a statement it holds and is in use:
the arm is that probe. But the join is not there for the state; it carries the anchors, and
without them the exclusion is the anti-join above. (b) The version can only be read with the row
(nothing else holds what the transaction read; an SCN is out, a plain user reads none, the third
run's note). What can be lazy is the map: record per row the raw key and version objects in a flat
list per table, and build the key-to-version map at the first call that needs it
(`recorded`, `version`, `unchanged`, at a write to the table). A scan never followed by a write,
YCSB E's and most read-only scans, then pays the three columns' fetch and a list add, about 0.2–0.3
µs a row against about 0.8, the per-row metadata lookups of `readKey` hoisted to one per result set
with it: about −250 µs a 500-row scan, most of the client's +390. (c) The key appended per table is
needed (the user's columns need not carry it); `tx_id` and `tx_version` are two getters and about
40 bytes a row; one column for both (`tx_id || '|' || tx_version`) saves a getter a row, about
0.1 µs; small. The server's analogue is the covering index above.

**What the anchors of a scan are for, verified in ScalarDB's source today** (`CrudHandler`:
`processScanResult` puts every scanned record into the read set through
`putIntoReadSetInSnapshot` in read-write mode, READ_COMMITTED included, the first read of a key
winning; `PrepareMutationComposer` conditions the prepare on the read set's `tx_id`;
`readIfImplicitPreReadEnabled` reads only a record not in the read set): a record scanned and then
written is prepared against the scan's version at READ_COMMITTED too. The driver's anchors do the
same. Dropping them for scans would let a write after a scan overwrite a row changed in between,
where ScalarDB aborts: a semantic change, the user's to make, not proposed.

**Proposed:** (b), the lazy map with the hoisted key getters, on all engines (the anchors are
engine-neutral); the texts unchanged. Expected: YCSB E's scan from +47% to about +30%, the server's
+560 µs staying as the layout's cost at READ COMMITTED (the inline layout's scan has none of it,
its row carrying its own columns; the run's inline cell shows). Tests to keep: every anchored
read-then-write test (the anchor still the first read's); to add: a scan then a write of a scanned
key, the version moved by another connection in between, `40001`; a scan on a read-only
connection records nothing. Measure: `ClientCostIntegrationTest`'s 500-row scan at READ
COMMITTED, the client CPU and the allocation, then the next YCSB E cell. After it, the proxied
calls' 150–350 µs a scan are the remaining client item at both levels (the fast path for
`getXxx(int)` within the visible columns, the third run's note); the covering index goes into the
Oracle notes as a per-table choice.

### 2026-10-27: YCSB E's inserts, +27% decoupled and +32% inline at READ COMMITTED (the design's note, interim)

6e's figures from run 20261027-1948, rep 1: the insert's p50, vendor → driver, decoupled 443 → 564
(+121 µs), inline 429 → 565 (+136); V$SQL a call: the vendor's INSERT 92 elapsed / 45 CPU / 7.2
gets (the decoupled cell) and 90 / 45 / 7.3 (the inline cell); the driver's INSERT ALL 158 / 72 /
13.8, its inline INSERT with the tx columns 121 / 56 / 10.6. Each insert is a transaction of its
own, the insert and its commit, among 95% scans of about 500 rows.

What the data settle:
- The statement's part. Decoupled, +66 µs a call: the second table's row, its primary-key entry
  and its in-flight index entry (13.8 gets against 7.2), the +15% the mixed workload gave INSERT
  ALL (+14–17%). Inline, +31 µs: five more columns in the row and the in-flight index's entry
  (10.6 against 7.3). Index maintenance at 1M rows (hypothesis 3) is inside those figures, on both
  sides alike: nothing beyond the second table and the second index.
- The commit (hypothesis 1), refuted. The driver's commit is the vendor's `commit()` and
  client-side bookkeeping (`Proxies`, the "commit" case: `transactionEnded` on the session, the
  router and the anchors), no statement, no round trip. On the container the insert-and-commit
  transaction through the driver costs the vendor's calls: 2 round trips, 1 execute, 2–3 user
  calls (`clientcost3.txt`, both levels, with and without the cache), its wall within the drift.
  `log file sync` is one wait per commit on both sides: the fourth run's YCSB A at READ COMMITTED
  decoupled has the vendor at 294,722 waits of 105 µs and the driver at 128,509 of 97 µs; the E
  cell's driver figure, 106 µs a wait, is that, and the vendor's is below the capture's top waits
  by its total (6,051 × 105 µs, 0.6 s, under the scans' SQL*Net waits), not absent.
- The client's per-insert cost: the proxy's bind recording, the plan's lookup, the stamp (an id
  sliced from a per-thread DRBG's kilobyte, `freshTxId`), the vendor statement from ojdbc's cache:
  +10–45 µs of client CPU an insert on the container, not a hundred.

What is open: +55 (decoupled) and +105 (inline) µs an insert outside the INSERT, both layouts
landing at the same p50 (564, 565) while their INSERTs differ by 37 µs, which points at something
common to the driver's runs rather than the statement. Hypothesis 2, queueing behind the scans on
the client, has against it the client's size: m6i.4xlarge, 16 vCPUs for 4 terminals; the driver's
scans add about 390 µs of client CPU each, 1,476 a second, 0.6 CPU-seconds a second, no saturation,
unless the JVM's GC under the scans' allocation (+420 KB a scan) pauses the terminals. The fetched
files settle it: (1) every statement with calls near the insert count in the driver's cells, where
a per-insert extra would show; (2) the cells' `user calls`, `execute count`, `SQL*Net roundtrips
to/from client` and `user commits`, the calls a transaction; (3) the inserts' latency percentiles,
a shift at every percentile being a per-insert cost and a spread interference; (4) the JFR:
`jdk.GCPhasePause` count and total, `jdk.CPULoad`, and the execution samples under BenchBase's
`InsertRecord`, the driver's frames beneath. Read after the fetch; the answer completed then.

### 2026-10-27: YCSB E's inserts answered from the fetched files; the inline INSERT's RETURNING ROWID; the scan's client cost re-sized by the JFR (the design's note)

Run 20261027-1948 read from `bench-env/results/20261027-1948/a/y/<cell>/benchbase-oracle/ycsb-e-{a,c}-{1,2}/`
(a = the vendor, c = the driver): `execute/*.results.InsertRecord.csv` (the percentiles, the median
over the 5-second buckets after the first), `server.txt` (V$SQL a call, the wait events; the
capture has no V$SYSSTAT, so `user calls` and `SQL*Net roundtrips` are not in it and the waits
stand in), `execute.jfr` (`jdk.CPUTimeSample`, 1 ms of the thread's CPU a sample, every sample's
stack classified by its procedure and by where it lies; `ora/jfrcat.py`, `ora/jfrstack.py`).

**The insert, taken apart.** READ COMMITTED, the median of the two reps, µs an insert:

| per insert | vendor | driver | difference |
|---|---|---|---|
| decoupled: p50 | 443 | 557 | +114 |
| its INSERT, server elapsed / CPU / gets | 90 / 42 / 7.2 | 157 / 74 / 13.8 | +67 elapsed |
| `log file sync` a commit | 103 | 109 | +6 |
| the client's CPU (JFR) | 26 | 50 | +25 |
| inline: p50 | 428 | 554 | +127 |
| its INSERT, server elapsed / CPU / gets | 90 / 45 / 7.3 | 123 / 59 / 10.6 | +34 elapsed |
| `log file sync` a commit | 94 | 108 | +14 |
| the client's CPU (JFR) | 17 | 63 | +46 |

The parts sum: decoupled 98 of the +114, inline 94 of the +127; the rest is within the figures'
own noise (the vendor's insert CPU differs by 8 µs between its two cells for the same statement,
`log file sync` by 5). The items of the interim note: (1) no other per-insert statement (6e).
(2) The round trips are the vendor's: `SQL*Net message to client` waits a measured request, both
sides, A 2.02 (the update and its commit), C 1.00–1.01, E 6.4–6.5 (the scan's fetches); nothing
extra an insert. (3) The percentiles shift as one: decoupled +101 at p25, +114 p50, +128 p75, +143
p95 (inline +108 / +127 / +152 / +170), the minimum by +40 and +66: a per-insert cost, the
fastest inserts paying the statement's floor, not interference between the terminals. (4) GC pauses equal
(6e); the JFR's CPU an insert above; its wall samplers (`jdk.ExecutionSample`,
`jdk.NativeMethodSample`) under-sample the inserts 1.4–3x against the latencies on both sides and
are not used. So hypothesis 2 (queueing) is out with 1 (the commit); the answer is the statement
(+67 / +34 server elapsed, the layout's second row and index entries, 13.8 / 10.6 gets against
7.2) plus the client's +25 / +46 of CPU, of which the decoupled part is the driver's own path
(`insertBody` 3, `freshTxId` 2, `runWrite` 2, the `Run`, the context, the plan: 15 µs), the proxy
4, the forwarded setters 4; the inline part has a finding in it:

**The inline INSERT asks ojdbc for the ROWID on every insert.** `applyOracleInline` prepares the
statement with `new String[0]`, i.e. `RETURN_GENERATED_KEYS`, whether or not the application
wants keys (only the read-back is gated by `wantsKeys()`); ojdbc then appends ` RETURNING ROWID
INTO :15` (V$SQL's text of the inline INSERT ends so) and, at every prepare, tokenizes the whole
text to classify it (`AutoKeyInfo.create` → `parseSql` → `OracleSql.computeBasicInfo` →
`SQLTokenizer`: 51 of the insert's 294 CPU samples, 11 µs an insert, where the decoupled INSERT
ALL's prepare has 2 samples) and at every execute registers the return parameter
(`initializeAutoKeyInfo`, 2 µs). On the container (`ora/ret.jsh`: the inline shape, 200k rows,
4,000 inserts a form interleaved, a commit each, laptop load 3): the server's INSERT 127 µs elapsed
plain against 174 with `RETURNING ROWID INTO` (+47; the CPU column too coarse there), the client
+21 µs of CPU an insert. On AWS the RETURNING's part sits inside the inline INSERT's +34 of server
elapsed and the client's +13. The fix is one expression in `applyOracleInline`:
`run.prepare(step, null, false, run.context.wantsKeys() ? new String[0] : null)`; the duplicate
path and the ROWID read-back unchanged (the read-back only runs under `wantsKeys()`, where the
prepare still asks). Test: an inline INSERT without `getGeneratedKeys` reaches the vendor without
a RETURNING clause (the captured prepare, or V$SQL's text in the Oracle integration test), and one
with `RETURN_GENERATED_KEYS` still reads its key back (the existing test). Expected on the inline
insert: about −13 client and up to −30 server, p50 from +127 to about +90 (+21%); the decoupled
insert stays at +114 (+26%), the layout's price: the second table's row and its two index entries.

**The scan's client cost, re-sized** (6e's question on the lazy map's −250 estimate). The JFR's
CPU an E transaction (rep 1, the samples under `ScanRecord`): vendor 697 µs, driver 1,078, +381
(inline: 717 → 1,009, +292; SNAPSHOT decoupled 727 → 845, +118). Where the +381 lies:

| per 500-row scan, RC decoupled | µs | what |
|---|---|---|
| the proxy's dispatch | 125 | `Method.invoke` and its access checks (44), the handler's switch and name compares (30), the invoke (17), the rest the arguments: 20 ns a call over 5,500 calls (500 `next`, 5,000 `getString`); SNAPSHOT's +118 is this alone (111) |
| the anchors | 124 | the version getters 50 (`getString` of `tx_id` 30, `getObject` of `tx_version` 20); `readKey` 42 (the key's `getObject` as a BigDecimal 21, the per-row `getColumnTypeName` and the loop 21); the map 32 (`putIfAbsent` 11, `resize` 7 as the map grows through 500 rows, the key `List` and its hash 14) |
| ojdbc's own work on the three hidden columns | ~140 | `next()` 239 against 142 (14 columns unpacked a row instead of 11, `tx_id` 36 characters), `getString` +20, the socket +23 |

So the anchor bookkeeping is 124 of the +381, the map 32 of that, and the lazy map as proposed
(the raw key and version objects in a flat list, the map at the first write, `readKey`'s metadata
hoisted) saves the map and the per-row lookups and loop: about −50 µs a scan, not −250; `getLong`
for `tx_version` and an integral key instead of the BigDecimal decode takes another ~25. The
getters stay: the row must be read before `next()` moves on. The estimate's error: the
`ClientCostIntegrationTest` figures counted the whole client difference at RC as the anchors' and
the per-row cost at 0.8 µs; the JFR puts the anchors at 0.25 µs a row, the rest in the dispatch
and in ojdbc. Corrected expectation for (b): YCSB E's scan from +47% to about +44–45%.

The client's levers, ranked by the JFR: (1) the proxied-call fast path (the parked item: the
result set's `next` and `getXxx(int)` called on the target directly from the handler instead of
through `Method.invoke`, the anchors' hook kept on `next`): −80–100 µs a scan at both levels, every
engine, and a share of every proxied read; (2) the lazy map with `getLong`: −50–75 at RC;
(3) nothing else without dropping a hidden column (`tx_id`'s 36 characters are the one that costs
on the wire and in `next()`, ~20–30). The server's +560 at RC decoupled (the join +400 over
SNAPSHOT's probe form, which costs +140) stays the largest piece; the covering index's −150 is the
user's per-table choice (the scan note above). Recommended order: the inline RETURNING fix now
(one line, its own cell to confirm), the fast path next (todo 334's parked item, the measure
`ClientCostIntegrationTest`'s scan and read), (b) after it if the user still wants the RC scan's
remaining 50–75.

### 2026-10-27: the proxied-call fast path for the result set, designed (the design's decision; item 2 of the user's order, for c7)

The user approved f767e74's three items in order (6e): (1) the inline INSERT's RETURNING fix,
built by c7 as 990c49d on `oracle-insert-returning` and reviewed mergeable; (2) this; (3) the lazy
anchor map with `getLong`, designed after this one lands, on the same path.

**What costs.** A call on a driver result set runs `$Proxy.getString(int)` (the stub boxes the
arguments into an `Object[]`) → `Handler.invoke` (a switch over the five Object and Wrapper
methods) → `AnchoredHandler.handle` (a second switch, then `startsWith("get")`, the bound check
`i > visible()`) → `forward` → `Method.invoke` (its access checks and method-handle dispatch, the
call site megamorphic) → the vendor's getter. The JFR of the fifth run (the note above): about 20
ns a call, 5,500 calls a 500-row scan (500 `next`, 5,000 `getString`), 125 µs of the scan's +381
of client CPU, the same at SNAPSHOT (111 of its +118): `Method.invoke`'s checks 8 ns, the two
switches and name compares 8, the stub, the arguments and the result checks the rest. The third
run's microbenchmark (21 against 18 ns) saw a monomorphic site the JIT inlines; the handler's is
not. A getter by label costs a vendor `findColumn` for the bound check and then the vendor's own
lookup inside `getString(label)`: two lookups.

**The design: direct calls for the hot methods, the generic path for the rest.** The proxies stay
(one proxy class an interface, `Handler.invoke` as it is); the three result-set handlers,
`AnchoredHandler`, `ChildHandler` and `BooleanColumns`, call the vendor object directly for:

- `next()`: `rs.next()`, then the row hook — the anchor and state code now inline after `forward`,
  moved into `rowReached(ResultSet)` and called by the fast case and by the generic path for
  `previous`, `first`, `last`, `absolute`, `relative`, which stay generic;
- `wasNull()`: direct;
- the getters by index with one `int` argument: `getString`, `getObject`, `getInt`, `getLong`,
  `getDouble`, `getBoolean`, `getBigDecimal`, `getTimestamp`, `getDate`, `getTime`, `getBytes`,
  `getShort`, `getFloat`, `getByte` — one static `Proxies.getter(ResultSet rs, String name, int i)`,
  a switch returning a sentinel `NONE` for any other name, so the three handlers share one list;
  the handler's bound check first (`i > visible()`, the same `22023`), `getObject`'s result checked
  for a `ResultSet` and wrapped as today (a refcursor on PostgreSQL);
- the same getters by label: the index from the vendor's `findColumn(label)` (the handler calls it
  for the bound check already), then the direct getter by index: one lookup, not two;
- around every direct call the same `try { } catch (SQLException e) { throw failed.apply(e); }`
  `forward` has (SQL Server's 3960 and deadlocks while rows stream, todo 263); a direct call throws
  the vendor's exception itself, no `InvocationTargetException` to unwrap.

`BooleanColumns` keeps its `getObject` of a widened column first (todo 304), then the fast getters
on its target, the anchored proxy, whose own fast path runs next. Every other method, the updaters,
`getMetaData`, `close`, `findColumn`, the two-argument getters, the Object methods: unchanged.
Expected: about 8–10 ns a call instead of 20, −55–70 µs of a 500-row scan's client CPU at both
levels on every engine, a few hundred ns off a key read, one vendor lookup less per getter by
label; not the whole 125: the stub's arguments and the two switches remain.

**Declined: a delegating `ResultSet` class.** 190 hand-written methods would take the stub and the
switches too (about 3 ns a call left), 45 µs more a 500-row scan, 1.5% of its p50, and every hook
(the six cursor moves, the bound check in the sixty getters and updaters, the metadata, the close,
the exception mapping) placed by hand in each; the generic path keeps them in one place. The
measure decides whether to revisit.

**Tests** (every engine of `Engines`, both layouts where the suite has them; the fast path runs on
all engines, so the whole suite is the regression net, and these are the ones to read first):
- kept: `ReadIntegrationTest.reads_seeTheCommittedImage_whileScalarDbHoldsRowsPrepared` (the
  values through the getters, the hidden columns in the SQL only);
  `WriteIntegrationTest.anchorReviewFindings` (a hidden column by label refused, `22023`; the
  scrollable result set); `SnapshotIntegrationTest.scenarios` (a row in flight aborts the read as
  `next` reaches it; the scrollable read in autocommit; SQL Server's 3960 while rows stream);
  `DecoupledReadCommittedIntegrationTest` form2/form3 and `OracleOltpIntegrationTest`'s anchored
  read-then-write tests (the anchor the first read's, through `next`); the widened-BOOLEAN reads
  of todo 304 where they are tested (`BooleanColumns`), the coverage corpus if nowhere else.
- to add, in `ReadIntegrationTest` on every engine: every fast getter by index and by label on a
  ScalarDB table's row of each column type the engine has (string, integer, long, double, decimal,
  boolean, timestamp, date, bytes), equal to the vendor's own values on the plain table; `wasNull`
  after a NULL column; the hidden columns refused by index past the visible count and by their
  label (`scalardb_native_x0`) with `22023`, by every fast getter, not only `getString`; a vendor
  error from a getter mapped through `failed` (SQL Server's 3960 is the live case; on the others a
  closed result set's getter still raises the vendor's exception); a scrollable read at READ
  COMMITTED, a row reached by `absolute` and then written while another connection moved its
  version in between, `40001` (the generic cursor moves still anchor); on PostgreSQL `getObject`
  of a refcursor column returns driver rows (wrapped) — if the suite reaches refcursors anywhere,
  else noted as not covered.
- the measure: the JFR of the next AWS run (6e's plan, Oracle and PostgreSQL): `Method.invoke`'s
  frames gone from the scan's samples, the dispatch's share of the 500-row scan from 125 µs toward
  60; locally, `ora/jfrcat.py` on a BenchBase YCSB E run against a container, or a JFR of
  `ReadIntegrationTest`'s scan read in a loop.

**The design's own text.** At the merge, the architecture bullet of design section 1 gains:
"the result set's `next`, `wasNull` and the common getters are called on the vendor object
directly, everything else through reflection (todo 334)"; nothing else in the design changes.

### 2026-10-28: 6865e55 reviewed, the fast path built as designed (the design's note)

c7's 6865e55 on `result-set-fast-path` (based on f26cdde; 25c52e4 after it, test fixes only: SQL
Server's `WITH (UPDLOCK)` in `InlineIntegrationTest`'s inline whole-row lock loop, which sent `FOR
UPDATE` there, and the SpotBugs suppressions of todo 335's two findings), read from `git show`:
mergeable as it is, the user deciding the landing.

- `Proxies.getter(rs, name, i)`: the fourteen getters, `NONE` otherwise, shared by the three
  handlers. `AnchoredHandler`: `next()` and `wasNull()` direct under `failed`, `next` then
  `rowReached(rs)`, the row hook factored and still reached by `previous`, `first`, `last`,
  `absolute`, `relative` through the generic path (its guard, `anchors != null || states`, moved
  inside the hook, equivalent); a one-argument getter by index or label resolved once with the
  vendor's `findColumn`, the bound check `22023` before the call, the call under `failed`, a
  `ResultSet` result wrapped. `ChildHandler`: the same through `direct`, on result sets only (it
  wraps metadata too), under `failed`. `BooleanColumns`: `next`, `wasNull` and the getters by index
  direct on its target (the anchored proxy, whose own fast path then runs), its widened `getObject`
  first, labels forwarded.
- A nit, not blocking: a getter that is not one of the fourteen (`getClob(int)`, `getNString`,
  `getArray`, ...) runs the bound check twice, and by label resolves the label twice, in the fast
  block and then in the generic one; the methods are rare and the second lookup is the vendor's
  hash. Fold the generic block's resolution into the fast block's when the path is next touched
  (item 3 touches `rowReached`, not this).
- The test, `ReadIntegrationTest.fastGetters_returnTheVendorsValues`, on every engine and both
  layouts: every fast getter by index and by label against the vendor's values on the data table
  for each ScalarDB type, `wasNull` after each, at READ COMMITTED, SNAPSHOT and autocommit; the
  hidden columns refused through every fast getter by index and by label; a getter on closed rows
  the vendor's exception; a scrollable READ COMMITTED read whose row `absolute` reached anchored
  (`40001` after another connection moved its version). A fast `getString` broken to a constant
  fails it. No test reaches a PostgreSQL refcursor; noted in the javadoc.
- Noted apart: on MySQL and MariaDB a widened BOOLEAN read's `getObject(label)` of a hidden column
  is refused `42703` (through the anchored rows' `findColumn`) where every other getter says
  `22023`; on main before this change; the test accepts it; a one-line choice to make separately.
- The full suite green on PostgreSQL, MySQL, MySQL lower case, MariaDB, Oracle and SQL Server
  (after 25c52e4); unit tests 402 of 402; spotbugsIntegrationTest down to the two findings of
  other work (6e's report).
- The design's sentence (section 1's architecture bullet) added with this note.

### 2026-10-28: the lazy anchor map, the hoisted key reader and the version by getLong, designed (the design's decision; item 3 of the user's order, for c7 after item 2 lands)

**What costs** (the fifth run's JFR, per 500-row scan at READ COMMITTED decoupled, the note of
f767e74): the anchors are 124 µs of the client's +381: the two version getters 50 (`getString` of
`tx_id` 30, `getObject` of `tx_version` 20, a BigDecimal on Oracle); `readKey` 42 (the key's
`getObject` 21, a BigDecimal; `getMetaData`, `getColumnTypeName` and `getColumnType` per key
column per row and the loop's compares 21); `Anchors.read` 32 (`key()`'s `ArrayList`, the hash of
it, `putIfAbsent`, the map's resizes through 500 rows). After item 2 the same per-row code runs
from `rowReached`.

**The design.** Three changes on the same per-row path, engine-neutral, the texts untouched:

1. *The key reader's metadata hoisted.* `Writes.readKey(rs, from, width)` asks the metadata per
   row for each key column (`getColumnTypeName`, `getColumnType`, and `onSqlServer` for a `time`)
   to choose among `getObject`, `getObject(c, OffsetDateTime.class)`, `OffsetTime`, `LocalDateTime`,
   `LocalTime` and `getString` (todos 148 and 254). A `Writes.KeyReader` (per column one of those
   six kinds) is computed once per result set from the metadata, at the first row, and `readKey(rs,
   from, KeyReader)` reads the columns with no metadata call; `AnchoredHandler` keeps one per
   table of `anchored.tables`. The old `readKey(rs, from, width)` stays for the write paths'
   read-backs (one row or a few) as `readKey(rs, from, KeyReader.of(rs, from, width))`.

2. *The version by `getLong`.* The row hook reads `tx_version` with `rs.getLong(c)` and
   `rs.wasNull()` (both direct on the vendor's rows) instead of `getObject`, so no BigDecimal on
   Oracle; `Anchors.Version` gains the constructor `(String txId, long version, boolean isNull)`
   or keeps its `Object` one fed a `Long`: the stored `Long version` and `is()` unchanged. The
   key's getter stays `getObject`: `Anchors.key` matches what the read returned with what the
   write binds, normalized per engine (`RcFirst.key`: an integral bind to a BigDecimal of scale 0
   on Oracle, as ojdbc's `getObject` of a NUMBER), and moving the read to `Long` would move the
   write's normalization with it for about 20 µs a scan; declined.

3. *The map built when first asked.* `Anchors` keeps per table a flat list of what was read, in
   order — an entry of the key array as `readKey` returned it (the driver's own array, never the
   application's) and its `Version` — appended by `read(at, table, key, v)` under the same epoch
   check: no `key()` wrapping, no hash, no map insert per row. The key-to-version map of a table is
   built from its list at the first call that needs it, `version(table, key)` or `unchanged(...)`,
   by `putIfAbsent` in list order, so the first read of a key still wins; a build folds in only the
   entries added since the last one (a scan after a write to the same table). `recorded(table)`
   answers from the list (non-empty), no build. `transactionEnded` clears the lists, the maps and
   the stamps. A scan never followed by a write to its table, YCSB E's and most read-only scans,
   never builds; a write after a scan pays the build once, what the scan paid per row before. The
   single-row `anchor(...)` of the write paths' read-backs (`Proxies`, `records`) goes through the
   same `read`.

Per row after the three: the three getters (`getString`, `getObject` of the key, `getLong` and
`wasNull`), an `Object[]`, an entry and a list add; about 0.35 µs against 0.6 today on Oracle.
Expected: about −50 µs of a 500-row scan's client CPU on Oracle (−20 the metadata, −15 the
BigDecimal of the version, −15 the map), −35 on PostgreSQL, whose `getObject` of an `int8` is a
`Long` already; the allocation per scan down by about a third (the `ArrayList` key, the map node
and its resizes gone per row). With item 2, the scan's client cost at READ COMMITTED from +381 to
about +260 µs; the rest is the three hidden columns in ojdbc's `next()` and the proxy's stub.

**Tests** (every engine, both layouts where the suite has them; item 2's full-suite run is the
baseline):
- kept: every anchored read-then-write test, the anchor the first read's — `DecoupledReadCommitted-
  IntegrationTest` test1–test5 (test2 the row without metadata, its version NULL, through
  `getLong` and `wasNull`), form2 and form3, `WriteIntegrationTest.anchorReviewFindings`,
  `OracleOltpIntegrationTest`'s READ COMMITTED tests, `ReadIntegrationTest.fastGetters_...`'s
  scrollable `absolute` case; todo 141's read across a transaction's end recording nothing (the
  epoch); todo 142's own stamp unchanged; the temporal-key tests of todos 148 and 254
  (`timestamptz` and `timetz` keys on PostgreSQL, `time` keys on SQL Server), which exercise the
  reader's kinds; a `byte[]` key read and then written (content equality after the build), where
  the suite has one.
- to add: `AnchorsTest` (unit, `driver/src/test`): a key read twice with different versions gives
  the first; `recorded` true after reads and `version` null for a key not read, without and with a
  build; a read after `transactionEnded` (the epoch moved) records nothing; a build then more reads
  then another lookup finds both the old and the new entries; `byte[]` keys match by content;
  `unchanged` true for a version carrying an own stamp with no build. Integration, on every engine:
  a scan of a few hundred rows of a ScalarDB table then an UPDATE of one of its rows after another
  connection moved that row's version (`40001`), and of a row the scan did not reach (no anchor:
  the write goes through) — the build at the write finds the right entry; the same with two scans
  and a write between them (the fold of the entries added after the first build).
- the measure: the next AWS run's JFR, `Anchors.*` and `readKey` from 6.7% of the driver's samples
  toward 3%, `getMetaData` and `getColumnTypeName` gone from the scan's stacks; locally
  `ora/jfrcat.py` on a container YCSB E run.

**The design's own text.** At the merge, design 4.2's anchors paragraph gains one sentence: the
versions a read records are kept per table as read and indexed by key at the first write to the
table (todo 334); nothing else changes.

### 2026-10-28: e8f3436 reviewed, the lazy anchor map built as designed and on main (the design's note)

c7's e8f3436 on `lazy-anchor-map` (based on c9c3120), read from `git show`: mergeable as it is, and
on main by a fast-forward with this note, the user having said to land it once found so.

- `Writes.KeyReader`: per key column one of six kinds (`getObject`, `OffsetDateTime`, `OffsetTime`,
  `LocalDateTime`, `LocalTime`, `getString` for SQL Server's `time`), chosen by `of(rs, from,
  width)` from the metadata with the same tests in the same order as the old per-row code (todos
  148 and 254), `onSqlServer` included; `readKey(rs, from, KeyReader)` reads with no metadata call
  and returns null on a NULL first column as before; `readKey(rs, from, width)` stays for the write
  paths' read-backs, its callers untouched.
- `AnchoredHandler`: `readers`, one `KeyReader` per anchored table, made at the first row the hook
  reaches; `tx_version` by `getLong` then `wasNull`, the `Version` given a `Long` or null through
  its `Object` constructor, `is()` unchanged; the key's getter `getObject`, as decided.
- `Anchors`: per table a `Read` of parallel key and version lists and `byKey`; `read` appends the
  driver's own key array under the epoch check, no `key()`, no hash; `index()` folds the lists into
  `byKey` by `putIfAbsent` in order and clears them, so the first read of a key wins at the first
  build and at every fold; `version` and `unchanged` build, `recorded` answers lists-or-map
  non-empty with no build; the own-stamp check before the lookup and `transactionEnded` (`read`
  cleared, the stamps, the epoch moved) as before.
- c7's one addition, accepted: `Read.add` indexes once more than `UNINDEXED` (4096) entries wait,
  so a transaction scanning the same rows again and again with no write holds an entry per key, not
  per read, the bound the old map gave; a 500-row scan never reaches it, a scan past 4096 rows pays
  one fold per 4096 rows, about what it paid per row before.
- Tests: `AnchorsTest`, eight unit cases (the six of the design, plus 3×`UNINDEXED` reads of one
  key keeping the first, and a NULL-metadata row matching one without); `ReadIntegrationTest.
  scannedRows_areAnchored_atTheFirstWrite` on every engine and both layouts: 300 rows, a scan of
  250 at READ COMMITTED, then a row the scan did not reach written through, a scanned row nobody
  moved written through, a scanned row another connection moved since `40001`; after a rollback
  two scans with a write between, a row of the second scan moved since `40001` (the fold). A fold
  broken to drop the reads after the first build fails it (c7's check).
- The full suite green on PostgreSQL, MySQL, MySQL lower case, MariaDB, SQL Server and Oracle
  before the rebase; after the rebase onto c9c3120 the Read, Write, DecoupledReadCommitted,
  Snapshot and Inline suites on the engines retested, OracleOltp 20 (2 skipped), OracleForms 15;
  `check` down to the two SpotBugs findings of other work (c7's report).
- The design's sentence (4.2's anchors paragraph) added with this note. All three items of the
  user's order are on main: 0cf9ac0, 5be09a9 + bf83b4e, e8f3436. Next: the AWS run, the YCSB
  single-key plan on Oracle and the same on PostgreSQL, 6e putting it to the user; its JFR the
  measure (`Method.invoke` gone from the scan's stacks, `Anchors.*` and `readKey` toward 3% of the
  driver's samples), its E cells the inline insert's p50 toward +21%.

### 2026-10-28: the overnight run's first cell, TPC-C at READ COMMITTED on a decoupled table, 0.13 of the vendor: Oracle samples the in-flight index at every hard parse because the setup leaves the metadata table's key without column statistics (the design's note, for the morning)

**The cell** (6e's report from 20261028-0143-ora, `tpcc-c-1`, read while it ran): the driver 198/s against
the vendor's 1,579, 0.13; PostgreSQL's same cell 0.71. V$SQL holds `SELECT /* DS_SVC */ ...
OPT_ESTIMATE(@"innerQuery", TABLE, "STOCK_tx_metadata", ROWS=1) ... INDEX("STOCK_tx_metadata"
"STOCK_INFLIGHT") COUNT(*) ...` under several sql_ids, 371 executions, about 317 ms and 12,853 gets
each, 120 s of the run's 395 s of V$SQL elapsed; the vendor's run has none. `STOCK_tx_metadata`:
3,200,000 rows, 31,517 blocks, statistics gathered after the load and locked (the harness: BenchBase
load, the import and the setup, `GATHER_SCHEMA_STATS`, a flush, the execute). `optimizer_dynamic_sampling`
2, `optimizer_adaptive_statistics` FALSE. Waits: `cursor: pin S wait on X` 235 waits, 46.6 s. The RC
NewOrder blocks of entries 33–55 ms a call, 22–32 ms CPU, 990–1,380 gets.

**The cause, reproduced on the container** (`ora/ds.sql`, `ora/ds2.sql` and their outputs in the
scratchpad): `DS_T` and `DS_T_META` with the setup's index `(tx_state, K)` and `ORACLE_FIXED_STATISTICS`
as the setup runs it, 1,000,000 rows, the driver's statement shapes hard-parsed under tags.
- The setup gathers the metadata table's statistics with `method_opt => 'FOR COLUMNS tx_state SIZE 1'`:
  the table's and the indexes' statistics, and tx_state's column statistics, which the fixed
  histogram then replaces; the key columns, `tx_id`, `tx_version` and the rest get none, whether the
  gather runs on the empty table or after the load. A hard parse of any statement with a predicate on
  the metadata table's key (the CAS, `UPDATE T_meta ... WHERE K = :1 AND COALESCE(tx_state, 3) = 3`;
  step 1's `LEFT JOIN T_meta m ON m.K = d.K WHERE d.K = :1`) then runs dynamic statistics for the
  key predicate, and for the in-flight index as an access path it counts the predicate through the
  whole index: `INDEX("M" "DS_T_INFLIGHT") COUNT(*) AS C1, ..., SUM(CASE WHEN ("M"."K" = :B1) THEN 1
  ELSE 0 END)` over `DS_T_META`, 80–98 ms and 2,377 gets at 1M rows (the CAS's hard parse 94 ms and
  2,470 gets with the statistics gathered after the load, 398 ms and 17,409 with them gathered empty);
  at 3.2M rows with a two-column key, the 317 ms and 12,853 gets seen. The primary key's own sample is
  a range scan, 3 gets; the level-2 block samples 3 gets each.
- `ALTER SESSION SET optimizer_dynamic_sampling = 0`: no sampling, the parses 1–30 ms.
- `FOR ALL COLUMNS SIZE 1` or `SIZE REPEAT`, then the fixed histogram, locked: no sampling, 1–6 ms;
  the gate `NOT EXISTS (SELECT /*+ INDEX(m ...) */ 1 FROM T_meta m WHERE m.tx_state IN (1, 2))` an
  INDEX RANGE SCAN of the in-flight index, 1 row, cost 3, under the 10,000-row histogram.
- The gather of tx_state alone over a table whose columns have statistics keeps theirs: an inline
  table, the application's own with its own statistics, is not exposed unless it never had any.
- Why a storm in TPC-C and not in YCSB: TPC-C at READ COMMITTED on decoupled tables has hundreds of
  distinct texts (per table, per form, per batch size of the block of entries, each block's nested
  statements cursors of their own), all hard-parsed inside the measured run after the harness's
  flush, 1–2 samplings each; YCSB has a handful, parsed in the warm-up (the 21 SQL plan directives
  on `USERTABLE_tx_metadata` show the same sampling ran there). The 235 `cursor: pin S wait on X`
  waits are the other workers waiting on those parses. Deployed, the cost recurs at every re-parse
  (an aged or invalidated cursor, a statistics gather, DDL): about 0.3 s a text at this size, more
  as the metadata table grows.

**The fix, proposed** (the user's call; one line in `Setup.ORACLE_FIXED_STATISTICS`): `method_opt =>
'FOR ALL COLUMNS SIZE REPEAT'` in place of `'FOR COLUMNS ' || c || ' SIZE 1'`, the
`SET_COLUMN_STATS` of the fixed histogram on tx_state after it as now. `SIZE REPEAT` keeps an
application's histograms on an inline table and gathers the basic statistics every column lacks; on
the driver's own metadata table it is `SIZE 1`. Its test, in `OracleOltpIntegrationTest`: after the
setup of a decoupled table every column of `T_tx_metadata` has a row in `DBA_TAB_COL_STATISTICS`,
and a hard parse of a keyed write (a fresh comment in the text) adds no `SELECT /* DS_SVC */` text
naming the metadata table to V$SQL; the same for the inline table's tx_state. The fix reaches a
database only through a setup run (`force` past the lock on a table set up before it), so the
overnight run's decoupled cells keep the sampling; the next run after it measures the difference,
and cell 5 (the same cell at +250 µs) confirms the cause from the live dictionary: `DBA_TAB_COL_STATISTICS`
of `STOCK_tx_metadata` with only `TX_STATE`, the sampling texts' `SUM(CASE WHEN (key = :B1) ...)`
through `STOCK_INFLIGHT`.

**What it does not explain**: the block of entries at 33–55 ms and 990–1,380 gets a call. The
sampling is per hard parse, one or two a text, under 2 ms a call averaged over its 500 calls; the
rest is the block's own run, over 100 gets an entry, 10x what its statements should cost by the
primary keys, which the nested statements' plans in the cell's `server.txt` will show when its
files are fetched. That is the merged form's item (design 4.4) and a plan question beside it, TPC-C's
turn after the single-key work. With the sampling gone the cell would be about 0.19, not 0.7.
