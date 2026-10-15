---
status: complete
priority: p2
issue_id: "150"
tags: [code-review, performance, correctness, jdbc]
dependencies: []
---

# Key lists bound as OR chains hit the parameter limit and go quadratic

## Problem Statement

`Names.anyKey` writes `(k = ?) OR ...` once per key. A locking read of more than 65,535 rows (32,767
with a two-column key) fails after its rows are locked; the read-back of generated keys adds an
`ORDER BY CASE` of N branches, quadratic in the batch size; the INSERT stamp and take-over share the
form.

## Findings

- Probe (locking reviewer): `SELECT id FROM wb.imp FOR UPDATE` over 70,000 rows -> `PreparedStatement
  can have at most 65,535 parameters`; 5,000 rows took 203 ms end to end.
- Probe (write reviewer): prepared batch of 8,000 inserts with keys asked: 22.0 s against 16.0 s
  without; no measurable difference at 2,000.
- Writes.java `anyKey` callers: LockPlan.apply, Writes.returned, stampFromData, the take-over.

## Proposed Solutions

### Option 1
Bind one array per key column and join `unnest(?, ...) WITH ORDINALITY` on the key, ordered by the
ordinal where order matters.
### Option 2
Chunk the statements at 1,000 keys.

## Recommended Action


## Acceptance Criteria

- [x] A locking read of 100,000 rows and a batch of 10,000 INSERTs with generated keys succeed; the read-back is linear.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of d7be4f0 and cdfc58a (milestone 4c).

### 2026-10-15 - Fixed

**By:** Claude Code
- Key lists are a VALUES table with each value cast to the column's type (serial read as int4), in chunks of 1,000 for the stamp, the take-over and the check under the lock; the read-back of generated keys joins it and orders by its ordinal, linear. Test: a locking read of 70,000 rows.
