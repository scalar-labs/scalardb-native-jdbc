---
status: pending
priority: p1
issue_id: "335"
tags: [performance, oracle, oltp, isolation, implementation]
dependencies: ["334"]
---

# Oracle: implement the OLTP fast paths of todo 334, confirmation tests first

## Problem Statement

Todo 334 designed Oracle's six OLTP fast paths as paragraphs of `docs/design.md`, each with its
argument, its SQL or PL/SQL text, the row the driver reads back and a table of what the driver
does for every outcome, so that implementing is translation. This todo is the implementing
session's brief (Opus, per the division of work in todo 334). The design was written without
running Oracle: it names the engine behaviours each form rests on, and those are confirmed on the
container before any builder is written, since a wrong one is a silent lost update, not a failing
test.

The design paragraphs, in the order to build them:

1. 4.2 **"On Oracle both hold"**: reads without anchors at SNAPSHOT, the clear-table memory.
2. 4.3 **"The keyed write on Oracle, one PL/SQL block"**, and its clear-table statement.
3. 4.3 **"The write beyond the key on Oracle"**, and the decoupled clear-table block.
4. 4.3 **"An INSERT on Oracle, one block"** and 4.4 **"Batches on Oracle"**.
5. 4.3 **"The locking read on Oracle"**.
6. 4.2 **"The read side on Oracle"**.

Todo 334's log has, per form, the alternatives weighed and the facts to confirm; read it with the
paragraph. Section 5's Oracle paragraphs say what is built today.

## Rules

- **Send any case the design leaves open back to the design** (a Fable session, todo 334's log)
  rather than deciding it: the open cases are the ones where a mistake is a silent lost update.
  Text builders, bind plumbing, tests and measurements are yours; outcome rules are the design's.
- **A confirmation test that fails is sent back the same way**, with the test and the error. The
  design says for each what a failure means; do not work around it in code.
- **Do not touch `bench-env/` or `BenchBaseBenchmark`** while the PostgreSQL AWS matrix runs
  (todo 329). The Oracle BenchBase build comes after it.
- Several sessions share this checkout: `git branch --show-current` and `git diff <file>` before
  every commit, stage only your own hunks.
- Oracle's container (`gvenzl/oracle-free:23-slim-faststart`, `testing.Engines`) runs under
  emulation on the laptop and is slow; its times mean nothing absolute. Run the suite with
  `./gradlew :driver:integrationTestOracle`, one test class with
  `./gradlew :driver:integrationTest --tests '*X*' -Dnativejdbc.engines=oracle`.
- Before the forms merge, the semantics-bearing parts (result handling, outcome rules; not the
  text builders) get one review pass on Fable against the design text. Keep each form's outcome
  handling in one place per plan, as `applyFused`, `applyFast` and `slim` are on PostgreSQL, so
  that the review can read it beside the design's table.

## Task 1: the confirmation tests

A new integration test class on Oracle, `OracleFormsIntegrationTest` or so, one method per fact,
written against the vendor connection and the driver as each needs, run before anything is built.
Each says which form it decides and what a failure means.

1. **Statement-level atomicity of an anonymous block.** On the vendor connection, autocommit off,
   a block that updates a row and then raises (`RAISE_APPLICATION_ERROR(-20001, 'x')`, and a second
   case with an Oracle error, a NOT NULL violation): the exception reaches the client, the row is
   unchanged when read in the same transaction, and the transaction is still open and usable.
   Decides: forms 2, 3, 4 and 5 run without a savepoint around a block, and a batch's failed block
   is replayed one by one. A failure means every block needs the write's savepoint back.
2. **ORA-08177 out of a block at SERIALIZABLE.** Connection A at SERIALIZABLE reads a row (its
   snapshot starts); connection B updates the row and commits; A runs a block whose UPDATE meets the
   row: the exception's `getErrorCode()` is 8177 and `getSQLState()` is `72000`, which
   `Writes.oracleSerialization` matches, and a row the block updated before that statement is back
   as it was. Decides: forms 1 and 2's error mapping as today. Also: the same with a
   `SELECT ... FOR UPDATE` in place of the UPDATE, which section 5 states and form 5 rests on.
3. **A `SYS_REFCURSOR` over `dual` on a block's locals.** A `CallableStatement` on an anonymous
   block that assigns locals and ends with `OPEN ? FOR SELECT l_a, l_b, ... FROM dual`, the cursor
   registered as `OracleTypes.CURSOR` and read as a `ResultSet` after `execute()`, one row. For each
   key type the driver supports on Oracle (NUMBER for INT and BIGINT, VARCHAR2, CHAR with its
   padding, BINARY_FLOAT read through `TO_BINARY_DOUBLE`, BINARY_DOUBLE, DATE, TIMESTAMP, RAW): a
   local declared `T.k%TYPE`, filled by `SELECT ... INTO` from a row, read back through
   `Writes.readKey` equals, under `Anchors.key`, what `readKey` gives for the same column of a
   `SELECT` on the table, the CHAR key of `reviewOf8d1` included. Decides: form 2's row read back
   and its anchor check. A type that differs is sent back, with the two values.
4. **Binds in a block.** `?` placeholders in an anonymous block bound by position, the same user
   value bound twice (the WHERE in a read and in a DML), the stamp's three values, and the cursor
   as the last bind, on a `CallableStatement`; and `addBatch` refused on such a statement, so that
   form 4's block of entries is the only way to batch it.
5. **`FORALL` and `RETURNING`.** `FORALL` over an empty collection runs nothing and raises
   nothing; `RETURNING t.k BULK COLLECT INTO` across a `FORALL`'s UPDATE and INSERT executions
   collects one element per iteration in order; `SQL%BULK_ROWCOUNT(i)` after a `FORALL` whose
   statement carries scalar `?` binds; `MERGE` inside `FORALL` with `l_w(i)` in its `USING`
   subquery; `INSERT ... RETURNING k INTO` of an `IDENTITY` key; `IGNORE_ROW_ON_DUPKEY_INDEX`
   inside a `FORALL` INSERT. Decides: forms 3 and 4's texts as written. A failure is a text to
   rewrite, sent back with the error.
6. **The restart of a waiting `SELECT ... FOR UPDATE` at READ COMMITTED**, form 5's test, both
   layouts. Connection A, as a ScalarDB prepare would, updates a row of `T` and sets its metadata
   row to state 1 in one transaction and holds it; on connection B, at READ COMMITTED, the
   one-statement locking read of the design (inline: the row's `tx_id`, `tx_version`, `tx_state`
   appended; decoupled: `FROM T t LEFT JOIN T_meta m ON m.k = t.k ... FOR UPDATE OF t.k` with the
   metadata row's columns) waits on the row; A commits; B's statement returns the row with state 1.
   Second case: A commits a plain change of the row and its metadata as a driver write does; B's
   row carries A's `tx_id` and version. Decides: the inline one-statement form (a failure there is
   sent back: the design's premise would be wrong), and the decoupled layout's one statement
   against its two round trips (a failure there is expected as possible, and selects the two
   statements, which the design gives).
7. **ojdbc's batching.** A prepared batch of the inline INSERT with a duplicate as the third of
   five entries: what `BatchUpdateException.getUpdateCounts()` reports and which rows remain in the
   transaction (the design expects the entries before the failing one kept and counted). And a
   `CallableStatement` with the cursor OUT bind refuses `addBatch` (with test 4). Decides: form 4's
   vendor-batched INSERT and its duplicate classification.
8. **Not blocking, a measurement:** with autocommit on, whether ojdbc sends the commit with the
   execute of a block (one round trip against two), by counting round trips as the benchmark
   harness does; the design lists the one-round-trip autocommit as a follow-up.

Record each result in this todo's log with the Oracle version it ran on (Oracle Free 23 here; RDS
19 differs on the read side, not on these).

## Task 2: the forms, in order

Each form: the builders as the design's text, in `Writes` or `Router` beside the PostgreSQL ones,
gated on `Engine.ORACLE`; the outcome handling as the design's table, in one method per plan; unit
tests of the texts in `WritesTest` as the PostgreSQL forms have; the Oracle suite green, the tests
the design names per form among them; then the measurement. After each form, a log entry here
with what it gave, round trips counted.

- Form 1: `Router.versionless` and the clear-table memory (`passed`) on Oracle at SNAPSHOT in a
  transaction; the state column, the coarse check and the gate hinted on the in-flight index
  (form 6's second point applies from the start).
- Form 2: `KeyedPlan` gains the block for Oracle, the row through `OracleTypes.CURSOR`, the inline
  clear-table statement; `timeoutMidWrite`'s expectation moves to the one-statement one.
- Form 3: the predicate block and the decoupled clear-table block; a table with recorded anchors
  keeps the steps.
- Form 4: the INSERT block; ojdbc's batching for the plain statement forms; the block of entries;
  the merged `FORALL` block last, measured against the block of entries.
- Form 5: the locking read at SNAPSHOT with the gate; at READ COMMITTED the inline one statement,
  and for the decoupled layout what test 6 selected.
- Form 6: the READ COMMITTED anchors as the copies' own columns on Oracle (`Router.versions`'s own
  branch, `SplitRead.OWN`), and the hints wherever a probe reads the in-flight index.

## Measurement

Locally on the container: round trips per statement kind before and after (the harness's packet
counts), and each form beside the vendor's own statement and beside the steps on the same
container; absolute times there mean nothing. Then on AWS once BenchBase has an Oracle build in
`matrix.sh`, after the PostgreSQL matrix: TPC-C and YCSB, (c) against (a), both layouts, both
levels, the time added per transaction beside the ratio (todo 329's rule). The target is the
vendor on plain tables on Oracle, not PostgreSQL's numbers.

## Files

- `docs/design.md`: the six paragraphs above; section 5 (Oracle as built); 4.4 "Batches".
- `todos/334-*.md`: the design's log, the alternatives and the facts.
- `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Writes.java` (`KeyedPlan`, `InsertPlan`,
  `LockPlan`, `applyTogether`, `applyMerged`, `readKey`, `oracleSerialization`, `Names`),
  `Router.java` (`versionless`, `versions`, `lockingRead`, `passed`), `SplitRead.java` (the
  in-flight copies and their hint), `engine/Engine.java` (Oracle's dialect).
- `driver/src/testFixtures/java/com/scalar/db/nativejdbc/testing/Engines.java`,
  `driver/src/integrationTest/java/com/scalar/db/nativejdbc/jdbc/MoreWritesIntegrationTest.java`
  (`committedWhileItWaits`, `inFlightWait`, `lockingReads`, `batches`, `timeoutMidWrite`,
  `reviewOf8d1`), `InlineIntegrationTest`, `WriteIntegrationTest`, `SnapshotIntegrationTest`.

## Work Log

(the implementing session's, from the confirmation tests on)

### 2026-10-26: task 1, the eight confirmation tests, all passing on Oracle Free 23

`OracleFormsIntegrationTest` (eight methods, vendor connections, no ScalarDB), run against
`gvenzl/oracle-free:23-slim-faststart` on the laptop through ojdbc11 23.26.3.0.0, with
`-Dnativejdbc.oracle.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1` on a container started by hand
(`docker run -d --name ofa-oracle -p 1521:1521 -e ORACLE_PASSWORD=test ...`, the SYSDBA setting of
`Engines.ORACLE` applied with sqlplus), so that a run costs 8 s rather than a container start. The
results, as the tests print them under `[335]`:

1. **Statement-level atomicity of a block.** A block that updates a row and then raises, by
   `RAISE_APPLICATION_ERROR(-20001)` (SQLState 72000) and by ORA-01407 (a NOT NULL violation, SQLState
   72000): the exception reaches the client, the row is unchanged when read in the same transaction,
   and the transaction is open and usable (a later UPDATE and commit go through). Confirmed: forms 2
   to 5 need no savepoint around a block; a batch's failed block is replayed one by one.
2. **ORA-08177 out of a block at SERIALIZABLE.** Both a block whose UPDATE meets a row changed and
   committed after the snapshot and one whose `SELECT ... INTO ... FOR UPDATE` does fail with error
   code 8177 and SQLState 72000, which `Writes.asRetryable` (through `oracleSerialization`) maps to
   `40001`; the row the block updated before the failing statement is back as it was (read in the
   same transaction, where the block's own write would show). A later UPDATE of an unchanged row in
   that transaction fails with ORA-08177 too, as section 5 states. A plain `SELECT ... FOR UPDATE` of
   such a row fails with 8177 as well, form 5's conflict check at SNAPSHOT. Confirmed.
3. **A `SYS_REFCURSOR` over `dual` on the block's locals.** For each key type (NUMBER(10),
   NUMBER(19) with a value beyond double precision, VARCHAR2, CHAR(5) stored padded, BINARY_FLOAT
   through `TO_BINARY_DOUBLE`, BINARY_DOUBLE, DATE with a time part, TIMESTAMP(6), RAW), a local
   `T.k%TYPE` filled by `SELECT ... INTO`, returned through `OPEN ? FOR SELECT ... l_k FROM dual`,
   registered as `OracleTypes.CURSOR` (-10, checked against the class) and read after `execute()`
   through `Writes.readKey`, equals under `Anchors.key` what `readKey` gives for the table's column:
   the same Java class each time (BigDecimal, String padded, Double, LocalDateTime, byte[]) and the
   same JDBC type reported (NUMBER as 2, CHAR as 1, BINARY_DOUBLE as 101, DATE and TIMESTAMP as 93,
   RAW as -3). A row not found leaves the local NULL, which `readKey` reads as no key. Confirmed.
   **3b, recorded for the design, not asserted:** the same key through a scalar OUT bind registered
   with the JDBC type the table's result set reported, read as `readKey` reads that type
   (`getObject(i, LocalDateTime.class)` for 93, `getObject(i)` otherwise), equals under `Anchors.key`
   for all nine types as well.
4. **Binds in a block.** Seven `?` placeholders bound by position on a `CallableStatement`: the
   key in the read's WHERE, the stamp's three values, the user's SET placeholder, the key again in
   the DML's WHERE, and the cursor last; the row and the cursor row come back as expected.
   `addBatch()` on that statement is refused at once: ORA-17090 "Operation not allowed: Stored
   procedure with out or inout parameters cannot be batched". Confirmed: form 4's block of entries
   is the only way to batch a block.
5. **`FORALL` and `RETURNING`.** (a) `FORALL` over an empty collection runs and raises nothing, and
   `SQL%ROWCOUNT` after it is 0 (not NULL). (b) `RETURNING k BULK COLLECT INTO` across a `FORALL`
   UPDATE over keys (3, 1, 99, 2), 99 matching no row, collects (3, 1, 2): one element per row
   written, in iteration order, none for a skipped key; `SQL%ROWCOUNT` 3; `SQL%BULK_ROWCOUNT(i)` is
   (1, 1, 0, 1) with two scalar `?` binds in the statement. A `FORALL` INSERT `RETURNING k BULK
   COLLECT INTO` returns the IDENTITY keys in entry order. (d) `MERGE` inside `FORALL` with `l_w(i)`
   in its `USING` subquery and the stamp from locals: one row updated, one inserted, `SQL%ROWCOUNT` 2.
   (e) `INSERT ... RETURNING k INTO l_k` of an IDENTITY key, then the metadata insert under
   `IGNORE_ROW_ON_DUPKEY_INDEX(x (k))` with `l_k`: `SQL%ROWCOUNT` 1; the hinted insert of a key that
   has a row gives `SQL%ROWCOUNT` 0 and leaves the row alone (form 4's `l_meta`). (f) The hint inside
   a `FORALL` INSERT over (1, 7, 2) with 1 and 2 present: `SQL%ROWCOUNT` 1, `SQL%BULK_ROWCOUNT` (0, 1,
   0). Confirmed, with one text correction: a collection's `.COUNT` is a PL/SQL expression and is
   refused inside the cursor's SQL (`OPEN ? FOR SELECT l_k.COUNT ... FROM dual` fails with
   ORA-06550/ORA-00904 "L_K"."COUNT": invalid identifier); it goes through a NUMBER local assigned
   before the OPEN. The design's predicate block has it in its SELECT; sent back as a text note.
6. **The restart of a waiting `SELECT ... FOR UPDATE` at READ COMMITTED, both layouts.** A holds a
   prepare-shaped change (the data row changed, the metadata row to state 1, version 2) and B's
   one-statement lock waits; A commits; B's row is `1/p/P1/2/1`, the newest, on the inline layout
   and on the decoupled one through `T LEFT JOIN T_meta ... FOR UPDATE OF t.k`: the restart runs the
   join again at the new point in time. The second case, A committing a plain write (state 3,
   version 5): B's row carries A's `tx_id` and version on both layouts. Extra: a `FOR UPDATE` whose
   WHERE has the gate's `NOT EXISTS (SELECT /*+ INDEX(m ...) */ 1 FROM T_meta m WHERE m.tx_state IN
   (1, 2))` locks no metadata row (another session updates it without waiting). Confirmed: the
   inline one-statement form, and the decoupled layout's one statement, which test 6 selects over
   its two round trips.
7. **ojdbc's batching.** A prepared batch of five inline INSERTs with a duplicate as the third:
   `executeBatch` throws `BatchUpdateException` ORA-00001, SQLState 23000, `getUpdateCounts()` and
   `getLargeUpdateCounts()` both `[1, 1]`; the rows in the transaction after it are the two before
   the duplicate (and the pre-existing one), the fourth and fifth not written; the transaction is
   usable after it. Confirmed as the design expects: the entries before the failing one kept and
   counted.
8. **Round trips, a measurement** (`SQL*Net roundtrips to/from client` from `v$mystat`, and the
   client's chunks through a local counting proxy as `DelayProxy.requests` counts them; the two agreed
   on every line):

   | statement | autocommit off | autocommit on |
   |---|---|---|
   | a plain prepared UPDATE | 1 | 1 |
   | the block with a ref cursor OUT: execute | 1 | 1 |
   | ... the fetch of its one row | +1 | +1 |
   | the block with three scalar OUT binds, values included | 1 | 1 |
   | the block returning its row by `DBMS_SQL.RETURN_RESULT`, row included | 2 | 2 |
   | the commit after a block | 1 | — |

   So ojdbc sends the commit with the execute when autocommit is on (a block or an UPDATE is one
   round trip either way), and **a ref cursor's row costs a second round trip**, which scalar OUT
   binds do not; `RETURN_RESULT` costs the same as the cursor. The keyed block as designed is
   therefore two round trips in a transaction (three with the commit), one round trip fewer than
   the design counted. Sent back to the design with 3b's table as an open case: the row read back
   through OUT binds (the counts as NUMBER, the key with the JDBC type of its column, which the
   setup's column query knows, the cursor kept for a type outside the table) against the cursor as
   designed.

Nothing in `bench-env/` or `BenchBaseBenchmark` touched; the counting proxy is a private class of
the test. Next: form 1 (`Router.versionless` and the clear-table memory on Oracle at SNAPSHOT), then
form 2 with the row read back as the design answers.

### 2026-10-26: form 1 built, reads without anchors at SNAPSHOT and the clear-table memory on Oracle

By translation of 4.2 "On Oracle both hold": `Router.versionless()` holds on Oracle at SNAPSHOT as
on PostgreSQL, so a read in a transaction records no anchor and appends the state column instead;
the state column's two probes, the coarse check (`Writes.inFlightCheck`, now built from `Names`,
whose text is the `passed` key) and the writes' gate (`clearGate`) carry `/*+ INDEX(m <T_inflight>)
*/` on Oracle, the alias written as the SQL writes it (the hint report resolves
`INDEX("scalardb_native_meta" ...)` for the quoted alias and leaves `INDEX(scalardb_native_meta
...)` unresolved, checked on the container with `DBMS_XPLAN ... +HINT_REPORT`); the pending
per-table checks go as one statement, `SELECT CASE WHEN EXISTS (...) THEN 1 ELSE 0 END, ... FROM
dual`; and `withPlain` gives an Oracle read its statement as written over the data tables once
every table is found clear (`Proxies` switches to it as on PostgreSQL, nothing engine-specific
there). The locking read keeps its versions on Oracle until form 5 (`lockingRead`'s `versioned`),
since its steps read them. Nothing else gated on PostgreSQL moved. `SnapshotIntegrationTest`'s
"reads record no versions" branch runs on Oracle too (a DELETE and an UPDATE by predicate of a row
ScalarDB changed after the snapshot, and one a prepare rolled back, all ORA-08177 as `40001`; a row
locked then written). The in-flight copies of the split read are not hinted (form 6's and the
micro-benchmark session's shape); the probes the SNAPSHOT forms rest on are.

Measured through the driver (`OracleOltpIntegrationTest.form1...`, `SQL*Net roundtrips` of the
driver's session), a 20-row ScalarDB table, both layouts, in a transaction at SNAPSHOT:

| read | round trips |
|---|---|
| `SELECT COUNT(*) ... WHERE id <= 20`, the table not yet found clear (the check, then the read) | 2 |
| the same again, the table found clear (the statement as written, no check) | 1 |
| `SELECT x ... WHERE id = 1`, the state column in the statement | 1 |
| the aggregate after that key read (its state 0 marked the table clear) | 1 |
| the aggregate in autocommit, each time (todo 283's own transaction: autocommit off, the level, the check, the read, the commit) | 5 |

The autocommit figure is not form 1's (nothing is remembered there by design) but is worth the
design's eye: a checked read in autocommit on Oracle costs five round trips. Unit tests unchanged
but for PostgreSQL's gate text, kept as it was; the Oracle suite run in full after the change (its
result below).

The full Oracle suite after form 1: 53 tests, all passing (10 min 40 s on the container).

### 2026-10-26: form 2 built, the keyed write on Oracle as one PL/SQL block

By translation of 4.3 "The keyed write on Oracle, one PL/SQL block": `Writes.oracleKeyedBlock`
builds the block for an UPDATE or DELETE by the full key without a locking clause, in both
layouts, created or imported, at both levels; `KeyedPlan.block` holds it and
`KeyedPlan.applyBlock` decides from its cursor row in the design's order (no row: 0; a row without
metadata: the steps, under a savepoint of their own; in flight: the bounded wait and the block
again at READ COMMITTED with a wait set, else an abort; a state that is no image: 0; the anchor
moved: abort; written and stamped: 1; else abort, "a key matched n rows" for several). The row
comes back through a `SYS_REFCURSOR` registered as `OracleTypes.CURSOR` on a `CallableStatement`
(`Run.call`, `Run.cursor`), the key through `readKey` and `Names.keyExpr` (a FLOAT key through
`TO_BINARY_DOUBLE`, as the reads record it). A `Step` now carries where the stamp's three values
sit among its binds (`stampAfter`; the block binds them between its read and its DML), and a plan
says whether it runs under a savepoint (`Plan.savepointed`): the block plan does not, the engine
undoing a failed block whole (fact 1). At SNAPSHOT in the inline layout the vendor's statement with
the stamp and the hinted gate (`fastKeyed`, `applyFast`) runs first, as on PostgreSQL; the
decoupled clear-table block comes with form 3. The builders are in `Writes` beside the PostgreSQL
ones; unit tests of the texts in `WritesTest.onOracle_aKeyedWriteIsOneBlock` (both layouts,
UPDATE and DELETE, created and imported, the gated statement at SNAPSHOT, a write beyond the key
left to the steps).

Two things for the review of the semantics-bearing parts, both deliberate:

1. **The decoupled CAS matches the metadata row by the metadata row's own key, read in step 1
   (`l_m0`), not by the key the DML returns.** The design text has `WHERE k = l_w(1)` from
   `RETURNING ... BULK COLLECT`, "as the data row spells it, which the other forms do too". On
   Oracle a CHAR(n) data key compared with the metadata table's VARCHAR2 key is a non-padded
   comparison ('a    ' against 'a'), so the design's form would stamp nothing for a CHAR key and
   abort every write of it; the steps match by `row.metaKey`, the key as the metadata row holds it,
   and the block does the same. The RETURNING is then not needed in the keyed block and is left
   out (`SQL%ROWCOUNT` gives the count); it stays in the clear-table block, which reads nothing
   first. The CHAR key case is in `form2AnchorsOfEveryKeyType`.
2. **The decoupled read is the data table joined to its metadata table through a derived table of
   the key**, `FROM (SELECT t.k AS k FROM T t WHERE P) d LEFT JOIN T_meta m ON m.k = d.k`, as the
   steps' keys read is, rather than the design's `FROM T t LEFT JOIN T_meta m ON ... WHERE P`: an
   unqualified key column in `P` would be ambiguous between the two tables (ORA-00918). Same rows.

What it gave (`OracleOltpIntegrationTest.form2KeyedWriteAsOneBlock`, `SQL*Net roundtrips` of the
driver's session, each layout):

| keyed write, in a transaction | before (the steps) | after |
|---|---|---|
| UPDATE or DELETE by the key at READ COMMITTED (decoupled and inline) | 4: the savepoint, the keys read, the DML, the CAS | 2: the block, and the fetch of its cursor row |
| a write of a missing key | 2 (savepoint, keys read) | 2 |
| at SNAPSHOT, decoupled | 4 | 2 |
| at SNAPSHOT, inline, a table clear or not yet known (the vendor's statement with the stamp) | 4 | 1 |
| in autocommit, at READ COMMITTED | 5 | 3: the block, the fetch, the commit |

The "before" column is measured on the same run as a write by the key written as a predicate,
`WHERE id IN (8)`, which still takes the steps until form 3. The second round trip is the ref
cursor's fetch (fact 8), the open case sent to the design: with OUT binds the block would be one
round trip in a transaction and two in autocommit. Outcomes checked on both layouts: a missing key
0; the anchor of a row read then changed by another connection, `40001`; a row in flight, `40001`
at once, and with `scalardbnative.inflightWaitMs` a wait that ends with the write once the row
settles; at SNAPSHOT a row changed after the snapshot, `40001` with error code 8177 and the
transaction rollback-only; ScalarDB reads what the driver wrote. And for each key type ScalarDB
imports on Oracle (NUMBER(10), NUMBER(15), VARCHAR2, CHAR(5), BINARY_FLOAT, BINARY_DOUBLE, DATE,
TIMESTAMP at millisecond precision; not RAW, which ScalarDB takes no key of on Oracle, nor
NUMBER(19), which it does not import), the anchor a read records is the one the block reads back:
a change in between aborts, the unchanged row is written. `timeoutMidWrite`'s statement is a
predicate write (`id IN (80, 81)`), so its expectation moves with form 3, not here. The full
Oracle suite after form 2: run next, its result below.

The full Oracle suite after form 2: 55 tests (the 53 and the two form tests), all passing, 11 min
42 s on a fresh container.

### 2026-10-26: form 3 built, the write beyond the key on Oracle as one block, and the decoupled clear-table block

By translation of 4.3 "The write beyond the key on Oracle": `Writes.oraclePredicateBlock` builds
the block for an UPDATE or DELETE whose predicate is not the full key, both layouts, both levels;
`KeyedPlan.predicateBlock` holds it and `KeyedPlan.applyPredicateBlock` decides from its row of
counts in the design's order (found 0: count 0; after 1: abort; held: the wait and the block again
at READ COMMITTED with a wait set, else abort; missing: the steps; the abort codes 1 to 5 with the
steps' messages; stamped short of written: abort; else the count). It runs only when the
transaction recorded no anchor of the table, as the design says; with anchors the steps run, under
their savepoint. The decoupled clear-table block (`oracleClearTableBlock`, the DML with the gate
returning its keys, the stamp as a `FORALL`, a created table's DELETE removing the metadata rows,
an imported table's `MERGE` per key) is `fastKeyed`'s Oracle text for the decoupled layout and runs
through `applyFast` by the cursor's two counts, at SNAPSHOT, for the keyed and the predicate
forms alike; the inline layout's clear-table statement is form 2's. Unit tests of the texts in
`WritesTest.onOracle_aWriteBeyondTheKeyIsOneBlock`. `timeoutMidWrite`'s expectation moves to the
one-statement one on Oracle.

Three text-level choices, beside form 2's two, for the review:

1. **`FORALL i IN VALUES OF l_do`** for the CAS over the keys written, in place of the paragraph's
   `FORALL j IN 1 .. l_do.COUNT ... l_k(l_do(j))`: a FORALL index may only subscript a collection
   directly (PLS-00430 otherwise), and `VALUES OF` iterates the positions `l_do` holds, so the
   statement subscripts `l_m0(i)`, `l_id(i)`, `l_version(i)` with the index itself.
2. **The collection's `.COUNT` goes through a NUMBER local** (`l_found`), as noted in task 1.
3. **The decoupled CAS matches by the metadata row's own key** (`l_m0`, read in step 1 beside the
   data key), as in form 2 and for the same reason.

What it gave (`OracleOltpIntegrationTest.form3WriteBeyondTheKeyAsOneBlock`, each layout):

| write beyond the key, in a transaction | before (the steps) | after |
|---|---|---|
| UPDATE over two keys at READ COMMITTED (`id IN (9, 10)`) | 6: the savepoint, the keys read, and a DML and a CAS per key | 2: the block, and the fetch of its cursor row |
| one matching nothing | 2 | 2 |
| at SNAPSHOT, decoupled, the table not yet found clear, then found clear | 7 (the after-image check too), then 7 | 2, then 2 |
| at SNAPSHOT, inline, likewise | 7, then 7 | 1, then 1 (the vendor's statement with the stamp) |
| at SNAPSHOT, an imported table, three keys one of which has no metadata row | 7 | 2, the `MERGE` writing the missing row at version 1 and stamping the two others at version 2 |

Outcomes checked on both layouts: a key the predicate matched that another connection moves under
the block's wait so that it stops matching is skipped, count 1, the other key written and stamped
(the vendor's own skip); one moved that still matches aborts with `40001` (the decoupled CAS short
of the DML, the inline UPDATE's version -1); a predicate matching nothing counts 0; ScalarDB reads
what the block wrote and sees a block's DELETE gone. The full Oracle suite after form 3: run next.

### 2026-10-26: form 4, first part: the INSERT on Oracle as one statement

By translation of 4.3 "An INSERT on Oracle, one block": `Writes.oracleInsertBlock` builds the
decoupled table's block for an INSERT of one row by `VALUES` without a RETURNING (the user's
INSERT returning its key into locals, an IDENTITY key included; the metadata row under
`IGNORE_ROW_ON_DUPKEY_INDEX`; the row that hint skipped taken over by the stamp under the
deemed-committed condition; `meta, stamped, k...` back through the cursor), and
`InsertPlan.applyOracleBlock` decides from its row as the design's table says (`meta` 1 or
`stamped` 1: written; neither: an abort, the metadata row in flight; ORA-00001 out of the block,
which leaves nothing of it, classified as the steps classify it, no savepoint). The inline table's
INSERT runs as the one statement it is with its stamp columns (`inlineInsert` without PostgreSQL's
RETURNING and merged text, `InsertPlan.applyOracleInline`), prepared with `RETURN_GENERATED_KEYS`
so that its ROWID comes back with the execute, and its key is read by that ROWID only when the
application asked for the generated keys (`Context.wantsKeys`, which `Proxies` sets from the
statement's request), as `returned` then reads them back by the keys recorded. Several rows and an
`INSERT ... SELECT` keep the steps. Unit tests of the texts in
`WritesTest.onOracle_anInsertIsOneStatement`.

What it gave (`OracleOltpIntegrationTest.form4InsertAsOneStatement`, the execute alone on a
connection with a catalog TTL, since an INSERT's plan reads the table's columns and triggers once
at prepare and the fixture's TTL of 0 would repeat that at every execute):

| INSERT of one row, in a transaction | before (the steps) | after |
|---|---|---|
| decoupled | 4: the savepoint, the INSERT, the key read back, the metadata row | 2: the block, and the fetch of its cursor row |
| inline | 3: the savepoint, the INSERT, the stamp of the row | 1 |
| either, with the generated keys asked for | 5 or 4 | 3: the write, the key by ROWID where the statement did not return it, and the read-back of the rows asked for, as today |

Checked on both layouts: the application's duplicate is reported as such (SQLState 23000), the
transaction open and usable; a key a ScalarDB prepare holds aborts with `40001`; ScalarDB reads
the rows written; on an imported table a DELETE's tombstone is taken over inside the block, the
metadata row at the next version; an IDENTITY key comes back through the block and is read back
when asked for.

The full Oracle suite after forms 3 and 4's INSERT: 55 passing, 2 failing, both
`SnapshotIntegrationTest`'s "an update in flight that only its after image matches" (todo 166): the
predicate block's row of counts was decided in the table's order, `found` 0 before `after` 1, and a
row in flight whose before image matches nothing is `found` 0 with `after` 1, so the write counted
0 where it must abort. `applyPredicateBlock` now reads `after` first, as PostgreSQL's fused
statement does; the paragraph's table may be reordered, a note for the review. Green after that.

### 2026-10-26: form 4, second part: batches on Oracle

By translation of 4.4 "Batches on Oracle". **The block of entries** (`Writes.Entries`,
`Run.callEntries`, `Writes.applyOracleEntries`): the keyed block and the INSERT block are built
from a body whose locals carry a suffix (`keyedBody`, `insertBody`), and a batch's consecutive
parameter sets of one such write, up to 16, go as one block: each entry's body under `IF l_stop =
0`, `l_done_n` set as it is reached, the entry's own binds and its own stamp (the design binds one
stamp per block; one per entry keeps each entry's `tx_id` its own, as the batch forms on PostgreSQL
do, at three binds more per entry), and a stop after an entry whose outcome is not final, which
for the keyed write is anything but no row, a state that is no image, or written and stamped, and
for the INSERT anything but written or stamped; the cursor returns `n, done` and the write's row
per entry, `ORDER BY 1`, fetched with its end in one round trip (`setFetchSize(entries + 1)`,
without which ojdbc's prefetch of ten costs a second fetch for ten entries). The driver records the
entries' counts in order until the first that is not final (`KeyedPlan.entry`, `InsertPlan.entry`,
through the single forms' `decide`), and `Proxies.runBatch` then runs that entry by its single
form (the wait and the block again, or the steps, or the abort) and the entries after it as a new
block; a failure the block raises of the undoable kinds (SQLState 22, 23, 40, 42) leaves nothing of
it and the entries run one by one, as PostgreSQL's replay. An entry's abort, an anchor moved among
them, fails the batch with the counts before it. **The vendor's batching** (`Plan.vendorBatch`,
`Writes.applyVendorBatch`): a batch's inline INSERTs go as one `executeBatch` of the statement with
the stamp columns, each entry's binds and stamp, unless the application asked for the generated
keys; a `BatchUpdateException` records the counts ojdbc reports for the entries before the failing
one and is classified for that entry as the single form classifies a duplicate. The predicate
block's entries, the inline statement with the stamp at SNAPSHOT through `executeBatch`, and the
merged `FORALL` block are not built: the first two are rare in a batch, the third is measured
against the block of entries once it exists, and the block of entries already makes the batch one
round trip of execution plus the fetch. Unit tests of the texts in
`WritesTest.onOracle_aBatchsEntriesGoAsOneBlock`.

What it gave (`OracleOltpIntegrationTest.form4BatchesAsOneBlock`):

| a prepared batch in a transaction at READ COMMITTED | before | after |
|---|---|---|
| ten keyed UPDATEs, decoupled or inline | 20: each the block and its fetch (40 by the steps) | 2 |
| ten INSERTs, decoupled | 20 | 2 |
| ten INSERTs, inline | 10 | 1 |

Checked on both layouts: an entry in flight stops the block, the entry's own wait settles it and
the rest go on, every count 1; at SNAPSHOT such an entry fails the batch with `40001` and the
counts before it; a duplicate as the third of five INSERTs fails the batch with the counts `[1, 1]`
and the application's duplicate, the vendor's own counts on the inline table and the replay's on
the decoupled one; `MoreWritesIntegrationTest.batches` holds (20 entries, entries that see the ones
before, a tombstone taken over in a batch, a duplicate as the 19th entry reported with 18 counts).

### 2026-10-26: form 5 built, the locking read on Oracle

By translation of 4.3 "The locking read on Oracle". At SNAPSHOT the statement as written on the
data table with the write's gate in its WHERE clause (`Writes.gatedLock`, the predicate
parenthesized and the gate added, or a WHERE clause of the gate alone before the locking clause of
a statement without one, left to the steps when an ORDER BY would stand in the way) for a table
not yet found clear, and as written alone for one found clear; `LockPlan.applyGated` hands the
rows over and marks the table clear when rows came back, sends the coarse check after an empty
result on a table not yet clear, marks it clear when none is found, and otherwise runs the
predicate's in-flight check, an abort for a match by either image, else the committed copy's lock
as the steps take it, without versions. The lock's own text reads no versions at SNAPSHOT on
Oracle now (`lockingRead`'s `versioned`); `SKIP LOCKED` and an inline table read whole keep the
steps. At READ COMMITTED the one statement of PostgreSQL's inline form on both layouts
(`fastRc`, `applyFastRc`): the decoupled table's copy carries its metadata row's columns through
the left join (`SplitRead`'s `Versions` with `Check.NONE`), which the restart after a lock wait
re-reads at the new point in time (fact 6). One thing for the review: the statement's `FOR UPDATE`
stays as written on the derived table, so Oracle locks the metadata row joined in beside the data
row, where the design named `OF t.k` to lock the data rows alone; a prepare of that key blocks on
the data row anyway, and the lock goes with the transaction. No savepoint around either form.

What it gave (`OracleOltpIntegrationTest.form5LockingReadAsOneStatement`, each layout):

| `SELECT ... WHERE id = ? FOR UPDATE`, in a transaction | before (the steps) | after |
|---|---|---|
| at READ COMMITTED | 5: the in-flight check, the savepoint, the lock with its version lookups, the check again, the versions under the lock | 1 |
| at SNAPSHOT, the table not yet found clear, rows returned | 5 | 1, and the table found clear by it (an aggregate after it 1) |
| at SNAPSHOT, the table found clear, no row matched | 5 | 1 |
| at SNAPSHOT, the table not yet clear, no row matched | 5 | 2: the gated statement, the coarse check |

Checked on both layouts: a row in flight aborts with `40001`, or fails with the driver's ORA-00054
under NOWAIT; a lock that waited on another connection's keyed write returns the row as that write
left it and the write of it after goes through (the restart's anchor); at SNAPSHOT a row in flight
the predicate matches aborts and one it does not match leaves the lock to the committed copy;
`MoreWritesIntegrationTest.lockingReads`, `largeLockingRead`, `inFlightWait`, `timeoutMidWrite`
and `committedWhileItWaits` hold.

### 2026-10-26: form 6 built, the read side on Oracle

By translation of 4.2 "The read side on Oracle": at READ COMMITTED a read's anchors are the copies'
own columns on Oracle as on PostgreSQL (`Router`'s `own` set, `SplitRead`'s `Versions`): the inline
copy's `tx_id` and `tx_version` with its state condition on the row, the decoupled copy's from a
left join to its metadata row with `COALESCE(m.tx_state, 3) NOT IN (1, 2)` in place of the `NOT
EXISTS` per row, the in-flight copy's its before image's. The probes' hints are form 1's. The
copies' shapes are otherwise untouched, the micro-benchmark session's.

What it gave (`OracleOltpIntegrationTest.form6ReadSideAnchorsAsOwnColumns`): a key read at READ
COMMITTED in a transaction is one round trip on both layouts, its text without the two lookups per
row; the write of a row read then holds to the version read (a change in between aborts with
`40001`, an unchanged row is written).

Every form of the brief is built. The full Oracle suite after all six: run next, its result below.
Then the design's review pass of the result handling and the outcome rules (`applyBlock`/`decide`,
`applyPredicateBlock`, `applyFast`'s Oracle branch, `applyOracleBlock`, `applyGated`, `entry`,
`applyOracleEntries`, `applyVendorBatch`) against the paragraphs' tables, and the Oracle BenchBase
build in `matrix.sh` once the PostgreSQL matrix is done (not touched here).

The full Oracle suite after all six forms: 57 passing, 3 failing, all three expectations of the
read side's text pinned to the lookups per row (`ReadIntegrationTest`'s key read with `COALESCE`
taken as the per-row form, `WriteIntegrationTest.anchorsFromJoins` expecting `scalardb_native_meta`
rather than the copies' own columns on Oracle); both now take Oracle as PostgreSQL, the own-column
form being form 6's. Run again on a fresh container, and the PostgreSQL suite after it, since the
shared code changed (`Step`'s stamp position, `Plan.savepointed`, `Context.wantsKeys`,
`lockingRead`'s order of computation).

The full Oracle suite after that: 59 passing, 1 failing, `UnparsedIntegrationTest` on Oracle,
which is a test-order interaction and not the forms': it reads a plain table named `p` as written,
and `WriteIntegrationTest` imports a ScalarDB table named `p` in another schema, which the unparsed
name check then refuses by design (4.1, a name in any schema); the class order shifted with the new
test class, and the test fails alone on a database where `aj.p` exists and passed in every earlier
run where it came first. The plain table is renamed so that no ScalarDB table shares its name.
The PostgreSQL suite after all six forms: 77 tests, all passing (6 min 12 s under Testcontainers),
so the shared code that moved (`Step`'s stamp position, `Plan.savepointed`, `Context.wantsKeys`,
`lockingRead`'s order of computation, `inFlightCheck` from `Names`) changed nothing there.

### 2026-10-26: the row through OUT binds, as the design decided (todo 334, commit 2833a5d)

The blocks now return their row through OUT binds, the cursor kept as the first-use check and as
the fallback, by translation of the decision's consequences. **The texts.** `Writes.OracleBody`
holds a block's declarations, body, items and stop, and `block(Variant)` closes it in one of three
ways: the items as assignments to OUT binds (`? := l_rows; ? := 1; ? := l_id; ... ? := l_k0; END;`,
`Variant.OUT`), the assignments and then `OPEN ? FOR SELECT <the same locals> FROM dual`
(`CHECK`), or the cursor alone (`CURSOR`, the texts as built before); `Block` (the keyed write,
the INSERT) and `Entries` (the block of entries, per entry `n, done` and the row) give each
variant's `Step`, with the OUT binds' types and whether a cursor follows, so the three texts
differ in nothing but the last line (`WritesTest.onOracle_theRowComesBackThroughOutBinds` pins
it). The counts are `NUMBER` binds; the key's bind is registered by `Writes.oracleKeyJdbcType`
from the column's declared type, which `plan` now reads for every write plan on Oracle
(`Writes.columns`, `ALL_TAB_COLUMNS`, before the INSERT's alone): 2 `NUMBER`, 12 `VARCHAR2`, 1
`CHAR`, 101 `BINARY_DOUBLE` and `BINARY_FLOAT`, 93 `DATE` and `TIMESTAMP`, -3 `RAW`, and null for
any other declared type, which keeps the cursor for that table (`Block.keyTypes` null); the row is
read by `Writes.readOutKey`, `readKey`'s sibling on a `CallableStatement`, switching on the type
as `readKey` does. The predicate block returns its eight counts as OUT binds and nothing else; the
clear-table block `l_written` and `l_stamped`.

**The check** (`Writes.OutBindCheck`, per table name per process): a table's blocks run the
`CHECK` variant until the table is decided; the first block that returns a key gives it through
both paths, `decide` compares them under `Anchors.key`, and equal marks the table verified (the
`OUT` variant from then on), different marks it cursor-only for the rest of the process with one
WARN line carrying both values and their classes, and no key (rows 0) decides nothing, so the
next block checks. The row's read-back stays one method per plan: `KeyedPlan.applyBlock` (the
check and `decide`), `KeyedPlan.applyPredicateBlock` (the counts, no key, no check),
`KeyedPlan.applyFast`'s Oracle branch (the clear-table block's two counts),
`InsertPlan.applyOracleBlock`, and `Writes.applyOracleEntries` for a batch (the row per entry at
its OUT position; the checking variant's cursor over the `UNION ALL` fetched whole and each entry
reached compared, the batch's own first-use check). An INSERT whose generated keys were asked for
takes its key from the OUT binds (`Keys.addFromOut`), so the decoupled INSERT no longer reads the
key by ROWID; `addBatch` on a callable statement with OUT binds is refused by ojdbc (ORA-17090),
which the block of entries never needed.

**Tests.** Confirmation test 3b is an assertion for the nine key types: the OUT bind registered
by `oracleKeyJdbcType` of the declared type and read by `readOutKey` equals the table's key under
`Anchors.key`, each of the nine (passes). Unit tests: `onOracle_theRowComesBackThroughOutBinds`
(the texts per variant of the keyed block, its OUT types `2, 2, 12, 2, 2, 2, 2, 2`, the INSERT
block's, the entries'), `onOracle_theFirstUseCheck_decidesFromTheTwoKeys` (equal, different, no
key, and the first decisive pair counting, a later one not), the earlier text tests on the cursor
variant as before. The form tests prepare each measured statement before measuring it, since a
text's plan reads the table's columns once on Oracle and the fixture's catalog TTL of 0 repeated
that at every execute (their connections carry `catalogTtlMs=60000`), and form 2 measures the
table's first block of the process after `OutBindCheck.reset()`.

What it gave, the four blocks again and the first write's extra round trip, in a transaction:

| block | with the cursor | with OUT binds |
|---|---|---|
| the keyed UPDATE or DELETE, either layout, at READ COMMITTED | 2 | 1; the table's first block in the process (the check, OUT binds and the cursor both) 2 |
| a write of a missing key | 2 | 1 |
| the keyed write at SNAPSHOT: decoupled (the clear-table block); inline (the vendor's statement) | 2; 1 | 1; 1 |
| the keyed write in autocommit (the block, the commit) | 3 | 2 |
| the write beyond the key over two keys; one matching nothing | 2; 2 | 1; 1 |
| the write beyond the key at SNAPSHOT, decoupled, on the table not yet found clear, then a DELETE on it found clear | 2, 2 | 1, 1 |
| the clear-table block on the imported table, three keys one without a metadata row | 2 | 1 |
| the INSERT of one row, decoupled (the block); inline (the statement) | 2; 1 | 1; 1 |
| the INSERT with its generated keys asked for, decoupled; inline | 3; 3 | 2 (the block with the key, the read-back); 3 (the statement, the key by ROWID, the read-back) |
| a batch of ten keyed UPDATEs; of ten INSERTs, decoupled | 2; 2 | 1; 1 |
| the FOR UPDATE by the key at READ COMMITTED, and the write of the locked row after it | 1, 2 | 1, 1 (the write of another row first, so that the table's check is done; before the change the figure was the check's 2, form 5 being the first form to run) |

Unit tests: 392, all passing. The full Oracle suite on a fresh container after the change: 60
tests, all passing (11 min 55 s). `OracleOltpIntegrationTest` again after form 5's test change:
all eight passing, the figures of the table. The PostgreSQL suite after it, since `plan` and `Step`
changed for every engine: 77 tests, all passing (6 min 8 s under Testcontainers). Pending as
before: the design's review pass of the result handling (`applyBlock`/`decide` now with the check,
`applyPredicateBlock`, `applyFast`'s Oracle branch, `applyOracleBlock`, `applyGated`, `entry`,
`applyOracleEntries`, `applyVendorBatch`) against the paragraphs' tables, and the Oracle
BenchBase build in `matrix.sh` once the PostgreSQL matrix is done.

### 2026-10-26: the block that finds a mismatch takes its row from the cursor

A review of commit b0b46ef found the check fail-open for the one block that detects the mismatch:
`applyBlock`, `applyOracleBlock` and `applyOracleEntries` recorded the outcome in `OutBindCheck`
and went on to decide that block from the row of the OUT binds, the mistyped key included, so the
anchor of that one write would have been the key the design's rule names as the silent lost
update, and only the blocks after it would have had the cursor's. Now each of the three takes the
cursor's row where `decide` says `CURSOR`: `applyBlock` the whole `KeyedRow` of the cursor,
`applyOracleBlock` the cursor's key for the generated keys, the block of entries each reached
entry from the cursor's row (`plan.entry(run, rs, 3)`) rather than the binds; a `CURSOR` outcome
without a cursor row, which `FROM dual` cannot produce, fails the block as the cursor variant
fails a block without a row (`XX000`). Equal keys and a row without a key decide from the OUT
binds as before. The same review flagged the WARN line carrying the two key values: the design
asked for the values with their classes, the line fires once per table per process and only on a
mismatch, and a value shows what a class alone does not (a precision lost, a time zone shifted),
so the line is kept as designed; a key that is sensitive would reach the log through it, which
the design may weigh.

The INSERT block's check and the block of entries' check had not run against the database (the
form 4 tests warmed the table with a keyed write first), so each form 4 test now resets the check
and measures the table's first INSERT and first batch in the process besides: the decoupled
INSERT's first block 2 round trips (the inline INSERT is the vendor's statement, 1), the first
batch of ten keyed UPDATEs 2 on either layout, the rows as written through ScalarDB after. Unit
tests 392 passing; `OracleOltpIntegrationTest` and `OracleFormsIntegrationTest` passing on the
container; the full Oracle suite on a fresh container after the fix: 60 tests, all passing
(11 min 21 s). The PostgreSQL suite is not run again, the change being inside the Oracle-only
branches. Unit tests after the mismatch test below: 393, all passing, and the mismatch test fails
without the fix (the write counting 1 where it must abort).

### 2026-10-26: the design's table rows and the tests that exercise them (asked with the review, todo 334)

The review of commit b0b46ef (todo 334's log, f4671bd) asked for its one finding, the checking
variant of `KeyedPlan.applyBlock` deciding from the OUT binds' row, which the entry above fixes
(the same fix in `applyOracleBlock` and `applyOracleEntries`, whose OUT-bind rows the review read
as already taken from the cursor), with a unit test:
`WritesTest.onOracle_theBlockThatFindsAMismatch_decidesFromTheCursorsRow` drives
`KeyedPlan.apply` through fakes of the vendor connection, the callable statement and the cursor
(`java.lang.reflect.Proxy`, no JDBC driver) with the OUT binds giving the key as a String and the
cursor as the NUMBER the reads report: the anchor recorded under the cursor's key at version 5, the
block finding the row written at 7, the write aborts with the moved version and the table is marked
cursor-only; the keys equal, the write goes through against the version read and the table is
verified; no row, count 0 and nothing decided. And the review asked which test exercises each row
of the design's four tables (4.3), or none, before the AWS run:

**The keyed write on Oracle.**
- `rows` 0: `OracleOltpIntegrationTest.form2KeyedWriteAsOneBlock`, a write of a missing key, count 0, both layouts.
- `rows` 1, `meta` 0: none. No test writes a row of an imported table by its key before its backfill; `oo.imp`'s row without a metadata row is written by the predicate form (form 3), and form 4's keyed DELETE of the tombstone has its metadata row.
- `tx_state` 1 or 2: form 2 (a row in flight: the abort at once, and with `inflightWaitMs` the wait and the write once the row settles); `MoreWritesIntegrationTest.inFlightWait` (rolled back or committed while the write waits, the wait running out).
- `tx_state` neither 1, 2, 3 nor NULL: `MoreWritesIntegrationTest.mergedBatches` (a metadata row set to state 4, the keyed write counts 0, the row untouched).
- the version read is not the one the transaction read: form 2 (a row read, changed by another connection, then written: `40001`); `form6ReadSideAnchorsAsOwnColumns` (the anchor from the read's own columns); the unit test above (the first-use check's mismatch, the anchor under the cursor's key).
- `written` 1, `stamped` 1: form 2 (the UPDATE and the DELETE count 1, ScalarDB reads the row at the driver's version); `form2AnchorsOfEveryKeyType`.
- `written` 1, `stamped` 0 (decoupled): `form2RowMovedBetweenTheReadAndTheCas` (the entry below: the DML waits on another connection's keyed write, the CAS then finds the metadata row moved).
- `written` 0 after a committed row was read: `form2RowMovedBetweenTheReadAndTheCas` on the inline table (the DML's own CAS finds the version moved after the wait); at SNAPSHOT the ORA-08177 that comes first, form 2.
- `written` above 1: none; impossible with the full key.
- ORA-08177, ORA-08176: form 2 (a row changed after the snapshot, error code 8177 as `40001`, the transaction rollback-only); `SnapshotIntegrationTest.snapshotLevels` (the engine's own conflict mapped, rollback-only after it).
- ORA-00060, any other error: `InterleavingIntegrationTest` (ORA-00060 out of the block accepted as the deadlock's own, error code 60 with SQLState 61000, and retried); none for another error.
- a duplicate in a batch, two entries on one key: `MoreWritesIntegrationTest.batches` (entries that see the ones before, two entries on one key, run one by one); form 4's duplicate is the INSERT's.

**The write beyond the key on Oracle.**
- `after` 1: `SnapshotIntegrationTest.snapshotLevels` (an update in flight that only its after image matches: a write by the predicate aborts, UPDATE and DELETE; todo 166, the case that put `after` before `found`).
- `found` 0: form 3 (one matching nothing, count 0).
- `held` above 0: `MoreWritesIntegrationTest.timeoutMidWrite` (the wait on a key in flight under a predicate write, `id IN (80, 81)`, ended by the query timeout); none for the wait that ends with the write (form 3's two waits are on another connection's vendor lock, not on a ScalarDB state).
- `missing` above 0: none at READ COMMITTED (the steps); form 3's imported table covers the clear-table block at SNAPSHOT over such a key, the MERGE, not this row.
- `abort` 1 to 5, and `stamped` below `written`: form 3 (a key moved under the block's wait that still matches: `40001`); the test asserts the state alone, not which row the layout gives (an inline row moved under the lock, its version -1, or an `abort` code), and none names codes 3 and 4.
- `stamped` equal to `written`: form 3 (two keys written, count 2; a key moved so that it stops matching skipped, count 1 for the other).
- ORA-08177, ORA-08176, ORA-00060, any other error: none out of the predicate block itself (`snapshotLevels`'s 8177 is a keyed write's).
- a duplicate in a batch: not this form.

**An INSERT on Oracle.**
- `meta` 1: form 4 (an INSERT of one row, count 1, ScalarDB reads it; the IDENTITY key returned and read back).
- `meta` 0, `stamped` 1: form 4 (a tombstone taken over: the imported table's DELETE keeps its metadata row, the INSERT stamps it at the next version).
- `meta` 0, `stamped` 0: form 4 on a created table (a key a ScalarDB prepare holds, `40001`); `form4InsertOverAMetadataRowInFlight` on an imported table (a metadata row in flight for a key without a data row).
- ORA-00001: form 4 (the application's duplicate, `23000`, the transaction open; a key a ScalarDB prepare holds); `form4BatchesAsOneBlock` (a duplicate in the middle of an INSERT batch); `MoreWritesIntegrationTest.batches` (the 19th entry).
- any other error: none.

**The locking read on Oracle.**
- SNAPSHOT, rows returned: form 5 (gated, the row returned marks the table clear; the aggregate after it one round trip).
- SNAPSHOT, no row, table found clear: form 5 (a lock matching nothing on the table found clear, one round trip).
- SNAPSHOT, no row, table not yet clear: form 5 (the coarse check, two round trips; a row in flight the predicate matches aborts, one it does not match leaves the lock to the committed copy, the write of it after).
- SNAPSHOT, ORA-08177: `MoreWritesIntegrationTest.lockingReads` (a row locked since the snapshot, error code 8177 accepted as the serialization failure); `SnapshotIntegrationTest.snapshotLevels` (a locking read of a row changed after the snapshot).
- READ COMMITTED, every row's state committed: form 5 (the lock, the write of the locked row after it holding its version; the restart: the lock waits on another connection's write and returns the row as that write left it).
- READ COMMITTED, a row's state 1 or 2: form 5 (`40001`; the driver's ORA-00054 under NOWAIT, error code 54); `MoreWritesIntegrationTest.lockingReads`.
- READ COMMITTED, decoupled, two statements: does not arise; the lock is one statement on both layouts on Oracle (form 5), the row stays for PostgreSQL's fallback.
- ORA-00054 from Oracle under NOWAIT: `MoreWritesIntegrationTest.lockingReads` (another connection's `FOR UPDATE NOWAIT` on a row the driver's transaction locks).
- any other error: none.

**The block of entries (4.4).** `form4BatchesAsOneBlock`: an entry in flight stops the block, its own wait settles it and the rest go on, every count 1; at SNAPSHOT such an entry fails the batch with `40001` and the counts before it; a duplicate in the middle of an INSERT batch, the counts before it; the first batch of a table in the process checked. `MoreWritesIntegrationTest.batches`: 20 entries, entries that see the ones before, a tombstone taken over in a batch, a duplicate as the 19th entry with 18 counts. Oracle's conflicts out of a block of entries (SQLState 72000 and 61000, not replayed one by one): none.

### 2026-10-26: the mismatch WARN carries the keys' classes, and the two silent rows tested

As the design decided (todo 334's log 69fe378, design 4.3 at 41cfa1d): `OutBindCheck`'s WARN line
names the table and the two keys' classes per column (`Writes.OutBindCheck.classes`, "null" for
a null column, "no key" for none), never the values; the values with their classes go to a DEBUG
line (`describe`). A key may be a person's identifier, and a driver's default-level log reaches
aggregators the operator did not choose for row data; the classes name the typing mismatch the
check exists for (a String against a BigDecimal, a `java.sql.Date` against a `LocalDateTime`),
and a value-level one is for the driver's developers, who reproduce it on the column type with
confirmation test 3b. `WritesTest.onOracle_theFirstUseCheck_decidesFromTheTwoKeys` asserts both
texts.

The two rows of the map whose mistake would be silent now have tests, as the design asked, the
rest being error paths the engine's mapping covers:

- **The keyed write, `written` 1 and `stamped` 0 (decoupled), and `written` 0 after a committed
  row was read (inline)**: `OracleOltpIntegrationTest.form2RowMovedBetweenTheReadAndTheCas`.
  Another driver connection's keyed write holds the row under the vendor's lock, uncommitted; the
  block of the write under test reads the row committed before and its DML waits on the lock;
  the other commits. On the decoupled table the DML goes through (`written` 1) and the CAS finds
  the metadata row at another version and another `tx_id` (`stamped` 0); on the inline table the
  DML's own CAS finds the version moved (`written` 0). Both abort with `40001`, "a row the
  statement writes changed after it was read"; after the rollback the row is as the other
  connection left it, through the driver and through ScalarDB, and the write then goes through.
  Both layouts pass.
- **The INSERT, `meta` 0 and `stamped` 0 on an imported table**:
  `OracleOltpIntegrationTest.form4InsertOverAMetadataRowInFlight`. The vendor connection writes a
  metadata row in flight (`tx_state` 1) for a key of `oo.imp` that has no data row, the window
  between ScalarDB's metadata upsert and its data insert; the driver's INSERT of that key aborts
  with `40001`, "a key the statement inserts is being written by ScalarDB", and the rollback
  leaves no data row; the metadata row deleted, the INSERT goes through at version 1, state 3.
  Passes.

The map's rows updated accordingly: the keyed `written` 1 `stamped` 0 and `written` 0 rows, and
the INSERT's `meta` 0 `stamped` 0 on an imported table, now name these tests. The ten form tests
pass on the container; unit tests 393 passing; the full Oracle suite is not run again for a log
line and two new tests.

### 2026-10-26: SpotBugs on `Writes.java` after b0b46ef

A clean build at cb59f57 failed `:driver:spotbugsMain` in `Writes.java` (reported by the bench
session): `Run.prepare(String, boolean, String[])`'s statement handed to `track`, which can throw
after the statement was made (OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE; `track` closes it, which
the analysis does not follow), and the suppression of that obligation on
`LockPlan.applyFastRcDecoupled`, where the analysis had placed it before, reported useless. The
overload now closes the statement itself when `track` throws, and the suppression is gone;
`spotbugsMain` and `spotbugsTest` report no warning. `spotbugsIntegrationTest` reported the two
Oracle test classes' class-level suppressions of OBL_UNSATISFIED_OBLIGATION and
ODR_OPEN_DATABASE_RESOURCE as useless, now removed, and the form tests' setup executing the
cleanup's statements built from the catalog's names (SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE),
now suppressed with the class's SQL_INJECTION_JDBC; its one remaining finding,
`InFlightCheckBenchmark.inFlightCheck` writing the static `metaTables` from an instance method, is
the benchmark session's, reported to it.

### 2026-10-27: the decoupled lock locks the data row alone (Opus)

As decided in design 4.3 "The locking read on Oracle" (ed5a8ba) and todo 334's log, "the first
Oracle BenchBase run read", point 1: at READ COMMITTED on a decoupled table, form 5's clause is
now `FOR UPDATE OF t."ID"`, where `t` is the derived table's alias and `"ID"` its first key
column. `NOWAIT` (and `WAIT n`) stay after it, and a clause the user wrote with OF is left alone.
`Router.lockingRead` inserts the column after the last UPDATE token of the emitted text. The
select list is unchanged, and the inline form keeps its clause. Tests:
- OracleFormsIntegrationTest 6b `scopeOfTheDecoupledLock` (vendor): with the plain clause, both
  rows are locked (ORA-00054 on each). With `OF t.k`, `NOWAIT` or not, the metadata row is free
  and the data row locked.
- Test 6 now uses the driver's derived-table spelling: the newest row after the restart, in both
  layouts and both cases.
- OracleOltpIntegrationTest `form5LocksTheDataRowAlone`: the routed text for both layouts, with
  and without `NOWAIT`. Through the driver, a second connection's `FOR UPDATE NOWAIT` returns the
  metadata row and fails on the data row with ORA-00054.
- Form 5 and the rest of the Oracle OLTP class pass.

On ofa-oracle: the Oracle integration suite (141 tests, 65 run, none failing), plus the unit
tests (397) and SpotBugs. The proof is the YCSB decoupled READ COMMITTED cell on AWS.

### 2026-10-27: the inline whole-row lock at SNAPSHOT as one statement (Opus, the AWS session)

As decided in design 4.3 "The locking read on Oracle" and todo 334's log (e2ee905): at SNAPSHOT
on an inline table, a locking read that reads the row whole (`*` or `q.*`) now takes the plain and
gated one-statement forms. Its stars are spelled out as the user's columns (`Names.star`), with
no metadata column. Before, it fell to the steps over the committed copy: five round trips and a
savepoint with the row held. That queued YCSB's read-modify-write in the second Oracle run (1,381
against 468 µs).
- `SqlAst.stars()` places each star item and its qualifier as written.
- `Router.spellableStars` takes them for a one-table statement once the spelled text reads known
  columns. A NATURAL join or a whole-row reference keeps the steps.
- `Router.spelled` replaces them in the plain text and in `Writes.gatedLock`'s. Both edit only
  after the select list.
- The condition is engine-independent, so PostgreSQL's inline whole-row lock at SNAPSHOT and
  SERIALIZABLE takes its plain form too.
Tests:
- `OracleOltpIntegrationTest.form5LocksAnInlineRowReadWholeInOneStatement`: for `*`, `ti.*` and
  `a.*`, the routed plain and gated texts name the columns and no metadata column. Through the
  driver, the lock is one round trip on a table not yet found clear and one after. The row equals
  the vendor's `SELECT id, x, v`, three columns, and a second connection's `NOWAIT` gets ORA-00054.
- `InlineIntegrationTest`: `*`, `imp.*` and `i.*` FOR UPDATE at SNAPSHOT give the user's two
  columns and the row (PostgreSQL passed. On Oracle the class's fixture fails on the shared
  container's leftover INL users, ORA-01920, before it gets there).
- `SqlAstTest.stars_placeEachStarItemAndItsQualifierAsWritten`.
Results:
- On ofa-oracle: `OracleOltpIntegrationTest` 12 of 12 passed.
- On bb-pgss: `InlineIntegrationTest` 3 of 3 passed.
- Unit tests 397, none failing; spotless clean.
The proof is the YCSB SNAPSHOT inline cell on AWS, and PostgreSQL's should move too.
