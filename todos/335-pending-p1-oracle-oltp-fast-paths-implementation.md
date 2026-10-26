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
