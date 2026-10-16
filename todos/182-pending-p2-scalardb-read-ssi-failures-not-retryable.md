---
status: pending
priority: p2
issue_id: "182"
tags: [code-review, docs, isolation, scalardb]
dependencies: []
---

# An SSI failure on a ScalarDB read reaches ScalarDB applications as a non-retryable error

## Problem Statement

With ScalarDB's storage at SERIALIZABLE, PostgreSQL can abort a ScalarDB read or validation read with
40001. ScalarDB 3.19.1 `JdbcDatabase.get`/`scan` wrap every SQLException in a plain ExecutionException
(only `mutate` checks `isConflict`), so the application sees CrudException, ValidationException or
CommitException, none of them conflict exceptions. Design 5 ("ScalarDB's validation fails") and 6
("maps them to a retryable conflict") imply retryability; true for writes only. Verified in the v3.19.1
source.

## Proposed Solutions

### Option 1
State it in design 5, 6 and 7 and the README: ScalarDB applications retry those exceptions when the
cause's SQLState is 40001; raise the mapping with ScalarDB.

## Acceptance Criteria

- [ ] Design and README say which ScalarDB exceptions an SSI failure surfaces as, and how to retry.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.
