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
