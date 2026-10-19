---
status: pending
priority: p3
issue_id: "281"
tags: [code-review, oracle, simplification]
dependencies: []
---

# Simplify 8d1

## Problem Statement

1. Per-engine ternaries with silent defaults in Writes (columns 246, anyRow 761, release 776,
   distinctFrom 2368, key 2195, insertStamp 2420, alias 678, FROM dual 2233, notAvailable): the dialect
   pieces as exhaustive Engine methods (Engine.isDuplicateKey's style); catalog queries stay.
2. The identity fact fetched twice: `autoIncrement(run)` queries again what `columns()` knew; compute
   at plan time, drop the lazy field.
3. Writes 177-183 tests the engine twice: one `otherKeys(vendor, n)` switch.
4. Run.prepare's three-way `String[] generated` (null / empty / names) (optional).
5. StatementShape 666-678: five booleans for two rules; an early Oracle branch.
6. Engines.writes() always true: delete it and its assumeTrue callers; ReadIntegrationTest:381.
7. Rows.oracle's FOR lookahead and javadoc sentence are dead (Oracle refuses a limit with FOR UPDATE).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 2ce2755 (8d1), verified as stated above.
