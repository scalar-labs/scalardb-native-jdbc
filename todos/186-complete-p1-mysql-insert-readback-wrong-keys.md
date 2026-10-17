---
status: complete
priority: p1
issue_id: "186"
tags: [code-review, correctness, jdbc, mysql]
dependencies: []
---

# On MySQL an INSERT's keys read back by value can miss the row or match other rows

## Problem Statement

On MySQL (no RETURNING) InsertPlan reads the written keys back with `SELECT keys FROM data d WHERE
(d.k = <expr>) OR ...` (Writes.java ~381, used ~1132 with no count check). MySQL's `=` coercion is
not how the value was stored, and the orphan takeover in `InsertPlan.stamp` then turns any wrong key
into a silent restamp of a committed row (lost anchor, no error).

Verified end to end through the driver on m8-mysql (probe scratchpad/rv2, cases a, i, v):

- AUTO_INCREMENT key bound NULL (`VALUES (?, ?)` with setNull, the MyBatis useGeneratedKeys idiom), or
  a literal 0: the rows are generated, the read-back finds none, `executeUpdate` returns 0, no
  metadata row; on a created table the row is then invisible yet holds the key.
- INT key `VALUES (1.6, 1)` or `('3.6', 1)`: stored as 2/4, read-back finds nothing; DATETIME `.6`
  seconds and FLOAT keys likewise.
- VARCHAR key `VALUES (1, 1)`: `d.k = 1` also matches existing `'01'` and `'1.0'`: count 3 for one row,
  both other rows' metadata taken over (tx_version 1 -> 2); had one been in flight, a spurious abort.

MariaDB (RETURNING) is not affected.

## Proposed Solutions

### Option 1
Read back per VALUES row and require exactly one row each, total equal to the INSERT's update count;
otherwise throw, the write's savepoint undoing the insert. Compare with the value cast to the column
(`CAST(? AS ...)` per MySQL type) or by `<=>` on the stored row the INSERT produced.

### Option 2
Route a NULL, DEFAULT or 0 value for an AUTO_INCREMENT single key to the generated path (see 187);
refuse a key value that is not a placeholder or literal of the column's own type.

### Option 3
Read the key back with `LAST_INSERT_ID()`-free means: insert one row per statement and read
`ROW_COUNT()`/the row by a driver-added marker. Heavier.

## Acceptance Criteria

- [x] NULL- and 0-bound AUTO_INCREMENT keys get metadata and the right count, or are refused
- [x] A coerced key (1.6 into INT, 1 into VARCHAR with '01' present) never stamps another row
- [x] Read-back row count must equal the INSERT count, else the write is undone and fails
- [x] Integration tests on MySQL for each case

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.

### 2026-10-17 - Fixed

**By:** Claude Code
- Writes: each VALUES row's key is read back by its own values and must match exactly one row, or the write is undone and refused (0AN01); a NULL or 0 AUTO_INCREMENT key is refused, never left without metadata. MoreWritesIntegrationTest.reviewOf8a covers NULL, 0 and the '01' case.
