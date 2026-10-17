---
status: pending
priority: p1
issue_id: "217"
tags: [correctness, data, mysql, flaky]
dependencies: []
---

# An interleaving schedule on MySQL (lower_case_table_names=1) at READ COMMITTED ended with an extra delta

## Problem Statement

The all-engine run of 8b1 (2026-10-17) failed once in InterleavingIntegrationTest, MYSQL_LOWER_CASE:
`ScalarDB READ_COMMITTED, il.made_read_committed, seed 16, row 202: the deltas of the committed
transactions` expected -1 but was -2: a change landed that no transaction counted as committed made
(an abort or rollback that did not undo, or a commit reported as failed). The rerun of the class on
mysql_lower_case and mysql passed. Transfer workload, a created table, updates of existing rows
only, so none of 8b1's changes (keyIn point lookups, INSERT checks, snapshot comparison) are on its
path. No earlier log in the scratchpad shows it.

Steps: s2:0 d0:0 s3:0 s3:1 d0:1 d0:2 d1:0 d0:3 d1:1 d0:4 committed d1:2 s2:1 d0:0 s2:2 blocked d0:1
d0:2 blocked s3:2 blocked d1:3 aborted d0:2 aborted d1:0 s2:2 d0:0 s3:2 aborted s3:0 d0:1 d0:2 d0:3
blocked d1:1 s3:1 s3:2 aborted d1:2 d1:3 aborted s3:0 d1:0 s3:1 s3:2 aborted d1:1 d1:2 s3:0 s2:3
committed s3:1 s3:2 aborted s3:0 d0:3 aborted s3:1 s3:2 d1:3 aborted s2:0 s2:1 s2:2 aborted d1:0
s2:0 s3:3 rolled back d1:1 d1:2 d1:3 d1:4 committed s2:1 d0:0 s2:2 aborted d1:0 d0:1 d0:2 s2:0 d1:1
s2:1 s2:2 s2:3 committed s2:0 s2:1 s2:2 blocked d0:3 blocked d1:2 aborted d0:3 aborted s2:2 s2:3
committed d0:0 d0:1 d0:2 blocked d0:2 aborted

## Proposed Solutions

### Option 1
Reproduce by looping the schedule (seed 16, transfer, made_read_committed) on MySQL; log each
actor's statements and errors; check whether a driver 40001 that is a MySQL lock wait timeout (1205,
which since todo 204 leaves the transaction open) or a ScalarDB rollback after prepare is involved.

## Acceptance Criteria

- [ ] Reproduced and fixed, or shown to be the test's accounting

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- In the all-engine run of milestone 8b1; not reproduced on rerun.
