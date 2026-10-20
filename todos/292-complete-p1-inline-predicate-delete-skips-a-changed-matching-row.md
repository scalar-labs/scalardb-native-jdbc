---
status: complete
priority: p1
issue_id: "292"
tags: [code-review, inline, writes, correctness]
dependencies: []
---

# An inline DELETE with a predicate skips a row a writer changed that still matches

## Problem Statement

On inline, a DELETE is the anchored delete alone and never re-applies the user's predicate. A row
another transaction (the driver or ScalarDB) changed between the key read and the delete makes the
anchored delete match 0 rows; skipOrAbort then skipped it with a predicate, though it still matched:
the DELETE committed having deleted nothing, where the decoupled DML re-applies the predicate and
aborts with 40001. Verified on PostgreSQL and MySQL (review of 10b).

## Proposed Solutions

1. On a miss, read the predicate over the key as the row is now; abort if it matches, skip if not.

## Recommended Action

## Acceptance Criteria

- [x] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10b (ae77ce3).
- 2026-10-20: Fixed: KeyedPlan.matches, `SELECT 1 FROM T alias WHERE (pred) AND key`, run when the anchored delete misses a row still there and not in flight. InlineIntegrationTest races a predicate DELETE against an UPDATE that keeps the row matching (40001) and one that does not (skipped); fails without the fix (mutation checked).
