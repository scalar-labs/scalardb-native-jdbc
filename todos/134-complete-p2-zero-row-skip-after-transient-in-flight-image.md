---
status: complete
priority: p2
issue_id: "134"
tags: [code-review, correctness, writes, interleaving]
dependencies: []
---

# A row whose committed value matches is skipped when a ScalarDB prepare and rollback straddle the DML

## Problem Statement

S1 reads a committed row that matches P; a ScalarDB transaction prepares the row (after image does not
match P); the driver's per-key DML sees the after image and writes 0 rows; the ScalarDB transaction rolls
back; `skipOrAbort` sees the row present in state 3 and skips. The statement returns 0 for a row whose
committed value always matched; a native UPDATE would have waited and updated it.

## Findings

- Probed with a sleeping predicate function widening the windows (UPDATE and DELETE alike).
- `Writes.skipOrAbort` reads only state and existence.

## Proposed Solutions

### Option 1
Pass the key's anchor to `skipOrAbort` and read `tx_id, tx_version` too: a state 3 row still at the
anchored version cannot have stopped matching, so abort with 40001 (or retry the DML once); skip only
when the version moved.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] The probe's interleaving aborts with 40001 (or writes the row), never returns 0.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).

### 2026-10-14 - Fixed

**By:** Claude Code
- skipOrAbort reads the key's metadata again from the data row; at the anchored version (same tx_id and version) a zero-row DML aborts with 40001, only a moved version skips. Test "134" (prepare and rollback around the per-key UPDATE, widened with a sleeping predicate).
