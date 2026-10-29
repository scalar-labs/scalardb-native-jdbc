---
status: pending
priority: p2
issue_id: "360"
tags: [oracle, decoupled, imported, read-committed, performance, backfill]
dependencies: []
---

# Oracle: a key without a metadata row written in the blocks, not by the steps

## Problem Statement

On an imported decoupled table, a key the backfill has not reached has no metadata row. On Oracle
at READ COMMITTED, the keyed block and the block over the predicate's keys hand such a key to the
steps one by one. A batch's entry left at 0 by the merged form runs its single form, the statement
first and then the block.

Measured with `UnstampedWriteBenchmark` (79feec7), container, 2026-10-29, the median write and
commit with a metadata row against without one:

| write | with | without | round trips |
|---|---|---|---|
| UPDATE by key | 1,734 µs | 4,014 µs | 2 → 7 |
| UPDATE of a 10-key range | 1,528 | 12,565 | 2 → 26 |
| batch of 10 UPDATEs by key | 1,423 | 25,046 | 2 → 52 |
| DELETE by key | 1,050 | 2,814 | 2 → 7 |

SNAPSHOT and PostgreSQL are unaffected. The cost is paid once per row, and it matters most if the
backfill becomes optional.

## Proposed Solution

Design: docs/design.md 4.3, "A key without a metadata row in the blocks". The blocks write such a
key with the steps' own statements, the DML and then the version-1 metadata row of 4.4, in the
same call. A batch's entries counted 0 go straight to the keyed block as one block of entries. The
statement first and every other path keep their texts.

Expected: 3 round trips for each of the four writes.

## Acceptance Criteria

- [x] The keyed block's branch for `rows 1, meta 0`, UPDATE and DELETE.
- [x] The predicate block writes keys without a metadata row, with step 3 in two parts.
- [x] A batch's 0-count entries at READ COMMITTED go to the block of entries. The block of entries
      no longer stops at a key without a metadata row.
- [x] The tests listed in the design paragraph, at READ COMMITTED and SNAPSHOT.
- [x] `UnstampedWriteBenchmark` rerun on Oracle, with its figures in the design paragraph.

## Work Log

- 2026-10-29: designed (option 1 of the discussion; the statement first accepting a missing
  metadata row was not taken).
- 2026-10-29: built on branch oracle-unstamped-blocks (c7): the keyed block's branch with the
  stamp bound twice, the predicate block's second FORALL, a batch's 0-count entries as blocks of
  entries; `OracleOltpIntegrationTest.todo360_aKeyWithoutAMetadataRowIsWrittenInTheBlocks`; the
  full Oracle suite passes but for `ScalarDbNullMetadataIntegrationTest`, which fails the same way
  on main (a ScalarDB-only test building an int key for a BIGINT column). `UnstampedWriteBenchmark`
  at READ COMMITTED: 3 round trips for each write, from 7, 26, 52 and 7; figures in design 4.3.
