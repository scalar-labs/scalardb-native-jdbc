---
status: complete
priority: p2
issue_id: "178"
tags: [code-review, correctness, jdbc, isolation]
dependencies: []
---

# At SNAPSHOT and SERIALIZABLE a genuine duplicate can become an endless 40001

## Problem Statement

The fix for todo 171 (InsertPlan's `committed` step, Writes.java ~957-966) turns a 23505 into 40001
when no key of the statement is in flight and none is committed in the snapshot. Two genuine
duplicates satisfy that and are retried forever by an application that retries 40001:

- One multi-row INSERT that repeats a key: `INSERT INTO sn.t (id, x, v) VALUES (90, 'p', 0), (90, 'q', 0)`.
  The savepoint rollback discards the first row; the key is nowhere; 40001.
- A unique constraint other than the key, e.g. a unique index on the data table: the new key is
  nowhere; 40001.

Both verified on PostgreSQL at both levels (probe, 2026-10-17). At READ COMMITTED both give 23505.
Also, `InsertPlan.sql()` leaves out the `committed` step, which `KeyedPlan.sql()` lists for its own.

## Proposed Solutions

### Option 1
Run the in-flight and committed reasoning only when the violated constraint is the data table's
primary key (`PSQLException.getServerErrorMessage().getConstraint()`), and rethrow 23505 when the
statement's bound key tuples repeat. Tightens READ COMMITTED too, where any 23505 is read as a key one.

### Option 2
Re-read the key under READ COMMITTED semantics is not possible in the transaction; Option 1.

## Acceptance Criteria

- [ ] A multi-row INSERT repeating a key gives 23505 at both snapshot levels.
- [ ] A non-key unique violation gives 23505 at every level.
- [ ] A key ScalarDB prepared or committed after the snapshot still gives 40001.
- [ ] `InsertPlan.sql()` lists the `committed` step.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.

### 2026-10-17 - Fixed

**By:** Claude Code
- Writes: only a duplicate of the data table's primary key (constraint name read from pgjdbc's
  server message by reflection, matched in pg_constraint) is looked into; at the snapshot levels a
  multi-row INSERT that repeats a key (VALUES ... GROUP BY ... HAVING COUNT(*) > 1, under a savepoint,
  true when it cannot tell) keeps 23505. `InsertPlan.sql()` lists every step. Design 4.3 says so.
