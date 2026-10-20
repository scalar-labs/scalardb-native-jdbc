---
status: pending
priority: p3
issue_id: "280"
tags: [code-review, oracle, tests]
dependencies: []
---

# 8d1 test gaps and widened acceptances

## Problem Statement

1. InterleavingIntegrationTest:765 widened ScalarDbActor.aborts from CrudException to any
   TransactionException with 1205/60/8177, on every engine: limit to Oracle's codes, or fix the comment.
2. MoreWrites 1092-1100: the Oracle catch accepts any ExecutionException without checking 8177, and the
   'read aborts on a row a queued prepare wrote first' assertion never runs on Oracle.
3. No test would fail if these broke: FOR UPDATE OF bare col / alias.col / refusal of other.col on
   Oracle (none in StatementShapeTest); qualifiedStar over a join, USING/NATURAL, unaliased derived
   table; the IGNORE_ROW_ON_DUPKEY_INDEX skip path (deterministic race); keyViolated's Oracle branch
   with a non-PK unique violation and a message without the constraint.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 2ce2755 (8d1), verified as stated above.
- 2026-10-20: partly fixed. 1: no change, each code is paired with its engine's SQLState, so MySQL's 1205 (HY000) is not accepted. 2: the Oracle catch requires ORA-08177. 3: Oracle FOR UPDATE OF in StatementShapeTest. Open from 3: qualifiedStar over joins, the IGNORE_ROW_ON_DUPKEY_INDEX skip path, keyViolated's Oracle branch.
