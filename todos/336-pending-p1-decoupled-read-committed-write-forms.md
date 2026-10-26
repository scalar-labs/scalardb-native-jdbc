---
status: pending
priority: p1
issue_id: "336"
tags: [performance, postgresql, read-committed, decoupled, implementation]
dependencies: ["329"]
---

# PostgreSQL, decoupled layout, READ COMMITTED: the lock without its version lookups; the keyed write and the write beyond the key as the clear-table statement with the CAS

## Problem Statement

Decoupled READ COMMITTED on AWS (tenth run, `816f506`): TPC-C 0.66 of the vendor at the ~70 µs
round trip (+1.19 ms a transaction) and 0.83 at ~330 µs (+1.48 ms), against decoupled SNAPSHOT's
0.78 (+0.61 ms) and 0.93 (+0.60 ms). Round trips are the vendor's (20.0 packets a commit against
19.7); what is left is server time per statement, 970 against 613 µs a commit, in three places:

1. **The locking read's first statement** carries the anchored read's two version lookups a row
   (correlated subqueries into `T_meta`), which the second statement of the same round trip reads
   again: the stock lock 24.7 µs a call on AWS against SNAPSHOT's plain lock at 10 (the laptop 34.2
   against 15.4), ten a NewOrder.
2. **The keyed write** in its slim keys-CTE form (`Writes.slimKeyed`, and `mergedKeyed` for a
   batch's entries): 42.6 to 54.9 µs a call on AWS against the clear-table statement's 29.4 to 43.4
   that SNAPSHOT sends on a table it found clear; the merged batch of five 201 against 120.
3. **The write beyond the key** in its fused form (`Writes.fusedKeyed`): Delivery's order lines
   239 µs a call against the clear-table statement's 143.

The design is written, with its argument, text, row returned and outcome table per form, in
`docs/design.md` 4.3:

- the locking read's paragraph at READ COMMITTED on a decoupled table, amended ("the lock as
  written with its rows' keys appended and nothing else");
- **"The keyed write at READ COMMITTED on a decoupled table (todo 336)"**;
- **"The write beyond the key at READ COMMITTED on a decoupled table (todo 336)"**.

This todo is the implementing session's brief (Opus, by the division of work of todos 334 and
335): confirmation tests first, then the forms in order, then the measurement. The outcome rules
are the design's; text builders, bind plumbing, tests and measurement are yours; a case the design
leaves open goes back to it (the Fable session, this todo's log), not into code.

## Why these forms, measured

Server execution time from `pg_stat_statements` (`min_exec_time`, `mean_exec_time`), pgbench in
prepared mode, one client, the laptop's `bb-pgss` container over the TPC-C decoupled tables, µs,
least / average. The keyed write on `district`, the write beyond the key on an order's ten
`order_line` rows:

| text | loaded laptop (load 13-26) | quiet laptop (load 2) |
|---|---|---|
| slim keys-CTE statement, as today | 27 / 33 | 12 / 14 |
| the same, the CTE `NOT MATERIALIZED` | 20 / 30 | |
| the same, gate folded into the DML, stamp joined, final by joins | 26 / 31 | |
| the clear-table statement (SNAPSHOT's, the floor) | 9 / 11 | 9 / 11 |
| the clear-table statement with the CAS by a subquery on `T_meta` | | 10 / 12 |
| **the design's: the key's row in a CTE read once, the CAS `EXISTS` on it, the row returned** | | 11 / 13 |
| the same with a lateral probe in the final SELECT instead of the CTE | | 11 / 13 |
| fused statement beyond the key, as today | 54-56 / 112-127 | |
| the same, final by joins; the same, gate folded | 53 / 119; 56 / 138 | |
| the clear-table statement beyond the key (the floor) | 28 / 70 | |
| **the design's: the clear-table statement with the CAS by a subquery** | 34 / 85 | |

So reshaping the keys-CTE statements gains nothing; what costs is the shape itself under
contention (the slim statement is 3 µs over the floor alone and 2x it under the benchmark's four
terminals), and the clear-table statement with the CAS folded into its stamp is within 1 to 2 µs
of the floor. The forms rest on one PostgreSQL property, confirmed for the locking read of todo
329 and to be confirmed again here for the stamp (test 1): an UPDATE's WHERE, and `ON CONFLICT DO
UPDATE`'s, is re-evaluated on the row as it is after a wait on it, while a subquery in it keeps the
statement's snapshot.

## Tasks

### 1. Confirmation tests, before any builder

Each on PostgreSQL (the Testcontainers suite or an external database), each saying which form it
decides. A failure goes back to the design with the test and the error.

1. **The CAS sees the newest row and the snapshot's.** Two connections at READ COMMITTED on a
   decoupled imported table with a committed row. B: `BEGIN`, `UPDATE T SET x = x WHERE k = ...`,
   `UPDATE T_meta SET tx_version = tx_version + 1 WHERE k = ...`, and hold both locks. A, on
   another thread: the design's keyed statement for that key; its snapshot, taken at its start,
   reads B's old version into `scalardb_native_keys`, and its DML waits on the data row. B commits.
   A's DML re-checks and writes the row, its stamp finds the metadata row at B's version, and A
   returns `written` 1, `stamped` 0. Then the same without B: `written` 1, `stamped` 1,
   `tx_version` raised by one, `tx_id` the driver's. Decides forms 2 and 3 (the CAS).
   Do it for the imported table's upsert stamp and for a created table's `UPDATE T_meta ... FROM`
   stamp; a DELETE on a created table removes the row (`scalardb_native_removed`) under the same
   CAS.
2. **A key without a metadata row is stamped by the upsert's insert at version 1** on an imported
   table, and the row returned has NULL `tx_id`, `tx_version`, `tx_state`; the anchors of a read that
   saw no row compare equal to it (`Anchors.unchanged`). Decides form 2's last column.
3. **An aggregate read records no anchor.** `SELECT SUM(x) FROM T WHERE ...` at READ COMMITTED in
   a transaction: `Anchors.recorded(T)` stays false. Decides whether form 3 applies to Delivery; if
   it records anchors, form 3 still applies to a transaction that read none of the table, and the
   design's expectation for Delivery is sent back.
4. **A row the transaction wrote before.** A read, a keyed write, a second keyed write of the same
   key in one transaction: both succeed (`Anchors.unchanged` holds for the transaction's own
   `tx_id`), the version raised twice, as today. `WriteIntegrationTest.readsItsOwnWrites_withinTheTransaction`
   covers the batch form; add the single-statement case if it lacks one.
5. **The before-image rule beyond the key.** A ScalarDB transaction holds a row prepared whose
   before image matches a predicate on a non-key column and whose after image does not: the
   design's statement beyond the key returns the `EXISTS` column true and the driver aborts
   `40001`, writing nothing that stays. `MoreWritesIntegrationTest` has the fixtures (rows prepared
   through ScalarDB, `committedWhileItWaits`, `inFlightWait`).

### 2. Form 1: the lock's first statement with its keys only

`Router.lockingRead`, the `fastRc && !n.inline` branch: `lockSql` is built from the anchored lock
text, which `versions(...)` gave the key columns and two version lookups; give it the key columns
alone (a keys-only variant of `versions`, or the plain lock text with `n.readColumns` appended).
`Writes.LockPlan.applyFastRcDecoupled` reads the keys at `from = count - width - 1` on the
assumption of two lookups after them: adjust. Tests: the decoupled READ COMMITTED locking-read
tests of `WriteIntegrationTest` pass unchanged; a text assertion that the first statement names
`T_meta` nowhere (`WritesTest` or `RouterTest`, where the lock plan's `sql()` is visible).
Expected: the stock lock from 24.7 to about 10 µs a call on AWS.

### 3. Form 2: the keyed write, one key and a batch's entries

- `Writes.clearKeyedRc` (name as you like) beside `slimKeyed`, built from `fastKeyed`'s
  clear-table text (the DML as written on the data table returning its keys; the stamp from them:
  `n.stamp` for a created table, the upsert for an imported one, `scalardb_native_removed` for a
  created table's DELETE) with the CTE and the `EXISTS` of the design's text, and the final SELECT
  of the design. Chosen on PostgreSQL, decoupled layouts, `!snapshot`, a full-key predicate
  (`fullKey`), `!locks`, no RETURNING, and only when `run.context.inflightWait().isZero()` (the
  plan may carry both and `KeyedPlan.apply` pick, as `LockPlan.apply` does for `fastRc`); the slim
  statement stays for a configured wait.
- Its decoder follows the design's outcome table; `slim()` is the model (`readKey`,
  `Anchors.unchanged`, `anchors().wrote`). Unlike `slim()`, it never falls back to the fused
  statement: `written` 0 is the vendor's 0, and a stamp missed is an abort whose message the
  snapshot's `tx_state` picks.
- The batch's entries: `clearMergedRc` from `fastMerged` (the entries of `scalardb_native_e`, the
  DML joined to them, the upsert stamp from `scalardb_native_written`), with the key's metadata
  read per entry by a lateral subquery into a keys CTE, the CAS `EXISTS` on it by entry, and per
  entry `written`, `stamped`, `tx_id`, `tx_version`, `tx_state`; the decoder from `mergedFast` with
  `fused()`'s per-row anchor check. The `Merge` plumbing (`together`, `TOGETHER_MAX`) is unchanged.
- `WritesTest`: the texts, for a created and an imported table, UPDATE and DELETE, one key and a
  merged batch. Integration: `WriteIntegrationTest`, `MoreWritesIntegrationTest` (the race tests,
  `aPredicateDelete_racingAnUpdate_abortsOrSkipsAsTheRowNowMatches` among them),
  `SnapshotIntegrationTest`, on PostgreSQL in both layouts; the other engines' suites must be
  untouched by construction (PostgreSQL-only branch).

### 4. Form 3: the write beyond the key

`Writes.clearPredicateRc` beside `fusedKeyed`, chosen as form 2 is but for any predicate the
fused statement accepts, and only when `!run.context.anchors().recorded(n.table)` at execution
(as `KeyedPlan.fastMerge(Context)` checks); otherwise the fused statement as today. The text: the
DML as written returning its keys, the stamp from them with the CAS by a subquery on `T_meta` by
the stamped row's key (no CTE), and the before-image column; the decoder per the design's
paragraph. Tests as form 2's, plus confirmation test 5 kept as a regression test.

### 5. Measure, then hand to the AWS session

Locally first, as todo 329's entries did: the previous commit exported with `git archive` into a
scratch tree and run from there, the change from the working tree, minutes apart, TPC-C decoupled
READ COMMITTED 60 s, 4 terminals:

```
./gradlew :driver:integrationTestPostgresql --rerun --tests '*BenchBaseBenchmark*' \
  -Dnativejdbc.postgresql.url=jdbc:postgresql://localhost:55433/test \
  -Dnativejdbc.postgresql.username=test -Dnativejdbc.postgresql.password=test \
  -Dnativejdbc.benchbase=<a BenchBase postgres build> -Dnativejdbc.benchbase.workloads=tpcc \
  -Dnativejdbc.benchbase.runs=a,c -Dnativejdbc.benchbase.reps=1 -Dnativejdbc.benchbase.time=60 \
  -Dnativejdbc.benchbase.scale.tpcc=4 -Dnativejdbc.benchbase.layout=decoupled \
  -Dnativejdbc.benchbase.isolation=READ_COMMITTED -Dnativejdbc.benchbase.serverStats=true \
  -Pnativejdbc.parallelEngines=1
```

Read `driver/build/bench/benchbase-postgresql/tpcc-c-1/server.txt` (pg_stat_statements per
statement): the stock lock's first statement, the district and customer keyed writes, Delivery's
order_line write, the merged stock batch, each against the before tree's. Expected on the laptop:
the lock near SNAPSHOT's 15 µs, the keyed writes near the clear-table statement's 27 to 50, the
order_line write near 120 to 140. Record in todo 329's log and here; then ask the AWS session
(scalardb-native-jdbc-6e) for decoupled READ COMMITTED at both round trips from the commit.

## Rules

- **Send any case the design leaves open back to the design** rather than deciding it; a
  confirmation test that fails likewise, with the test and the error.
- **Semantics the forms must keep**: ScalarDB's prepare rule, a write CASes on the version the
  transaction read, or the statement's snapshot read, never the newest (design 4.3 step 3, section
  9); nothing written while a key is in flight when a wait is configured; the lock order data row
  then metadata row; the before-image rule of 4.2 at this level.
- **Do not touch `bench-env/` or `BenchBaseBenchmark`** while the AWS runs are in flight; the
  harness's flags above are enough.
- **The checkout is shared**: check the branch (`main`) and stage only your own hunks; other
  sessions' uncommitted edits may sit in `Writes.java`. Commits end with
  `Co-Authored-By: Claude <model> <noreply@anthropic.com>` for the model used.
- **Gates**: Spotless, SpotBugs (main and integration tests), NullAway, the unit tests, the
  PostgreSQL integration suite in both layouts; run the suites of the other engines once, since
  `Writes.java` is shared.

## Log

### 2026-10-26: designed (Fable), handed over

The three forms designed and measured as above; the design paragraphs in `docs/design.md` 4.3;
the pgbench variants in the designing session's scratchpad (not in the repository). Handed to an
Opus session.

### 2026-10-26: task 1, the five confirmation tests, all passing on PostgreSQL (Opus)

`DecoupledReadCommittedIntegrationTest` on PostgreSQL under Testcontainers, a decoupled imported
table `rcd.imp` and a decoupled created table `rcd.made`, each test the design's statement written
out by hand on vendor connections (or the driver itself for 2's anchor, 3 and 4), printed under
`[336]`:

1. **The CAS sees the newest row and the snapshot's.** B (`BEGIN`, the data row updated, the
   metadata row given another `tx_id` and `tx_version + 1`, both locks held); A's statement on
   another thread, its DML waiting on the data row (checked: not done after 700 ms); B commits. A
   returns `written` 1, `stamped` 0, and the snapshot's `tx_id`, `tx_version`, `tx_state` as before
   B (`1/0/seed/5/3`). Alone: `1/1/seed/5/3`, the metadata row then `driver-tx`, version 6 (5 + 1),
   state 3. For the imported table's upsert stamp, the created table's `UPDATE T_meta ... FROM`
   stamp, and the created table's DELETE, whose metadata row is removed alone and kept when raced.
   Decides forms 2 and 3: the `EXISTS` of the stamp's WHERE reads the snapshot, the stamped row is
   the newest after the wait.
2. **A key without a metadata row** on the imported table (a row the application inserted after
   the setup): `1/1/null/null/null`, the metadata row inserted at version 1, state 3, the
   statement's `tx_id`. A driver read of such a key records an anchor that `Anchors.unchanged`
   finds equal to NULLs and not to a version. Decides form 2's last column.
3. **An aggregate read records no anchor**: `SELECT SUM(v) ... WHERE id BETWEEN 5 AND 9` at READ
   COMMITTED in a transaction leaves `Anchors.recorded` false, both tables. Form 3 applies to
   Delivery as the design expects.
4. **A row the transaction wrote before**: a read, two keyed writes of the key in one transaction,
   both count 1, the version raised twice (5 to 7), the second value read back; both tables. A
   regression test for form 2 (the anchor of a row the transaction stamped holds).
5. **The before-image rule beyond the key**: a ScalarDB transaction holds row 12 prepared, its
   before image `x = 'pick'`, its after image `'moved'`; the design's statement beyond the key for
   `UPDATE ... SET v = 9 WHERE x = 'pick'` returns `0/0/t` (nothing written, nothing stamped, the
   before-image column true) on both tables; the driver's abort is form 3's to add, the test kept
   as its regression test.

The anchors are read in the tests from the driver connection's handler by reflection (the field
`anchors` of `Proxies`' connection handler), the one way a test sees `Anchors.recorded`.

### 2026-10-26: form 1 built keys-only; one case sent back (Opus)

`Router.lockingRead`'s decoupled READ COMMITTED branch now emits the lock with a keys-only
`versions` (the key columns, the two lookups left out) and `LockPlan.applyFastRcDecoupled` reads
the key as the last columns and hides only them. What the first statement still is, from
`DecoupledReadCommittedIntegrationTest.form1_theLocksFirstStatementHasItsKeysOnly`:

```sql
SELECT x, made."id" AS "scalardb_native_x0" FROM (SELECT t."id", t."x" FROM "rcd"."made_data" t
  WHERE NOT EXISTS (SELECT 1 FROM "rcd"."made_tx_metadata" q WHERE q."id" = t."id"
                    AND q."tx_state" IN (1, 2))) made WHERE id = ? FOR UPDATE
```

The committed copy's own filter, one probe of `T_meta` per row, stays. **Sent back to the
design**: the brief's text test asks that the first statement name `T_meta` nowhere, which needs
the lock over the data table as written (SNAPSHOT's `plainSql`) with the keys appended. That
changes one outcome: a row a ScalarDB prepare is inserting (in flight, no before image) that the
predicate matches is today excluded by the filter, so the lock skips it and the read returns
without it; over the data table the lock takes it, the second statement shows it in flight among
the locked keys, and the read aborts `40001` (or fails as NOWAIT does). Rows in flight with a
before image abort either way. Which does the design want: the filter kept (built, no outcome
changed), or the plain lock with the prepared insert aborting? Forms 2 and 3 go on meanwhile.

### 2026-10-26: form 1 as decided; forms 2 and 3 built; two cases sent back (Opus)

**Form 1, the design's answer recorded**: the plain lock over the data table, keys appended, no
`T_meta` in the first statement; a prepared insert the predicate matches is locked, shown in flight
by the second statement, and the read aborts `40001` or fails as NOWAIT does (the design's
decision of 2026-10-26, design 4.3's decoupled READ COMMITTED locking-read sentences; the inline
layout's one-statement lock already aborts on any row in flight). Built by emitting the committed
copy raw (`SplitRead.emit`'s `raw`, rows in flight kept) with the keys-only `versions`; the text
test asserts the metadata table named nowhere in the first statement.

**Forms 2 and 3 built** (`Writes.clearKeyedRc`, `clearMergedRc`, `clearPredicateRc`, the CAS
`rcCas`; `KeyedPlan.applyClearRc`, `applyPredicateRc`, `mergedRc`; chosen in `KeyedPlan.apply`
and `fastMerge(Context)` when `inflightWait` is zero, form 3 also only without recorded anchors);
the final row of form 2 carries the key written besides the design's columns, for the anchor
check. Unit tests in `WritesTest` (the texts, created and imported, UPDATE and DELETE, one key,
merged, beyond the key): 397 passing. The PostgreSQL suite: 134 tests, 2 failing, both cases the
design's tables do not cover, **sent back**:

1. **Form 2, a metadata row in a state neither 1, 2, 3 nor NULL** (`MoreWritesIntegrationTest`'s
   `mergedBatches`, `tx_state` set to 4 on a key, the keyed write expected to count 0 and write
   nothing, "no committed image, as the fused statement counts it"). Form 2 has no gate: its DML
   writes the data row, the stamp's `COALESCE(tx_state, 3) = 3` refuses, and the decoder aborts
   "changed after it was read". Count 0 would need the data write undone, which the statement
   cannot do without a gate or a savepoint. Abort (a changed outcome for that test), or a gate on
   the snapshot's state for that case alone?
2. **Form 3, finding 134: a prepare and rollback during the statement** (`WriteIntegrationTest`'s
   `reviewFindings`, line ~639: `UPDATE rf.imp SET x = 'drv' WHERE id = 60 AND rf.gate(x)`,
   `gate` sleeping 3 s; a ScalarDB prepare writes `x = 'held'` at 1 s, its rollback restores `'a'`
   at 4 s). Under form 3 the DML's re-check after the row moved runs on the prepare's after image
   (`gate('held')`, false), so the row whose committed image matched is skipped: count 0, nothing
   written, no abort. The before-image column reads the statement's snapshot, taken before the
   prepare, so it shows nothing in flight. The fused statement passes (count 1) by its keys read
   and step 2's zero-row rule (a key read whose DML skipped it is re-checked and aborts if still
   matching). Form 3 has no such rule; the design's text gives the DML as written and the
   before-image check at the snapshot only. What should form 3 do with a key the DML skipped after
   a wait: is a zero-row rule wanted (which needs the keys read, the fused statement's shape), or
   should form 3 not apply where the predicate is volatile, or something else?

Both forms stay built; form 3's question decides whether it ships at all.

### 2026-10-26: the two cases answered by the design, built; forms 1 to 3 committed (Opus)

The design's answers (docs/design.md 4.3, committed on main by the design session):

1. **Form 2 gets a gate on the snapshot's state** over the CTE it already has: the DML's WHERE
   extended by `AND NOT EXISTS (SELECT 1 FROM scalardb_native_keys WHERE
   COALESCE(scalardb_native_state, 3) <> 3)`, the merged form the same per entry (over the entry's
   keys row). Written 0 with `tx_state` 1 or 2: abort "being written by a ScalarDB transaction";
   written 0 otherwise, a state neither 1, 2, 3 nor NULL among it: 0 rows, nothing written; a key
   without a metadata row passes the gate and is stamped by the insert. Built in `clearKeyedRc`,
   `clearMergedRc` and `applyClearRc`; `MoreWritesIntegrationTest.mergedBatches` passes unchanged.
2. **Form 3 ships scoped to predicates on key columns only**, pure, as the READ COMMITTED lock is
   scoped, still only without recorded anchors of the table; predicates on other columns keep the
   fused statement and its zero-row rule (finding 134). Built (`Writes.keyColumnsOnly` on
   `whereColumns`, and `wherePure`); `WriteIntegrationTest.reviewFindings` passes unchanged. The
   divergence the design accepted: a row in the abnormal state matched by a key-only predicate
   aborts under form 3 where the fused statement skips it.

Tests: `WritesTest` (the gated texts, the merged gate per entry, form 3 on `id > ?` and absent for
`x = ?` and for an impure predicate), 397 unit tests passing; `DecoupledReadCommittedIntegrationTest`
adds the driver-level forms: form 2 (a missing key 0; a write 1 read by ScalarDB; an anchor
moved, an abort; a row in flight, "being written"; a row moved between the snapshot and the lock
by another connection's keyed write, "changed after it was read"; a batch of three, merged, all
1; with a wait configured, the slim statement waits on a row in flight and writes once it rolls
back) and form 3 (a key range written; a key range over a row in flight, an abort; a predicate on
another column, the fused statement, an abort as before), both tables. The PostgreSQL suite: 136
tests, 85 run, all passing (6 min 17 s). SpotBugs: main and unit tests clean; the integration
tests' remaining findings are `BenchBaseBenchmark`'s and `InFlightCheckBenchmark`'s, not this
todo's. Next: the other engines' suites once, and the measurement.
