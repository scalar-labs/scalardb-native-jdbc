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
