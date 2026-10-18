---
status: pending
priority: p1
issue_id: "262"
tags: [code-review, sqlserver, interleaving, isolation]
dependencies: []
---

# Interleaving test: an uncounted delta on SQL Server at READ COMMITTED

## Problem Statement

A run of InterleavingIntegrationTest on SQL Server (2026-10-19, while fixing 251-255, none of which
touches its path: INT keys, no hints, no time keys) failed once:
`ScalarDB READ_COMMITTED, il.made_read_committed, seed 3, row 200: the deltas of the committed
transactions` expected 0 but was 4. The rerun passed. Either a change landed that no transaction
counted as committed, or a counted one is missing: an isolation break or test accounting (cf. 217,
ScalarDB's best-effort rollback).

Steps: s2:0 s3:0 s3:1 d0:0 s3:2 s3:3 committed d0:1 s2:1 s3:0 s3:1 d1:0 d0:2 aborted s3:2 d1:1 d1:2
d1:3 aborted d0:0 d1:0 d1:1 s2:2 aborted s2:0 d0:1 s3:3 rolled back d0:2 d1:2 d1:3 d1:4 committed
d1:0 s2:1 d0:3 aborted d1:1 d0:0 d1:2 d1:3 s2:2 aborted d1:4 committed s2:0 d1:0 s3:0 s3:1 d0:1
d0:2 aborted d0:0 d0:1 s2:1 d0:2 d1:1 d0:3 s2:2 blocked s3:2 blocked d1:2 d1:3 blocked d0:4
committed d0:0 d1:3 aborted s3:2 aborted d0:1 s2:2 aborted d0:2 d0:3 d1:0 s2:0 d1:1 d1:2 d1:3 s2:1
blocked d0:4 committed s3:0 blocked s2:1 d1:4 committed s2:2 aborted s3:0 s2:0 s3:1 s3:2 s2:1 s3:3
committed s3:0 s2:2 aborted s3:1 s3:2 s3:3 rolled back

## Proposed Solutions

1. Make the run reproducible enough to see (loop seed 3 on SQL Server, log each actor's per-row
   deltas and outcome), then decide isolation bug vs accounting.

## Acceptance Criteria

- [ ] Cause found; fixed in the driver or the test
- [ ] Seed 3 on SQL Server passes repeatedly

## Work Log

- 2026-10-19: seen once; rerun passed.
