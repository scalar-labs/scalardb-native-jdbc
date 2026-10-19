---
status: pending
priority: p3
issue_id: "271"
tags: [code-review, sqlserver, serializable, robustness]
dependencies: []
---

# The coarse check's INDEX hint fails under forced parameterization

## Problem Statement

With `PARAMETERIZATION FORCED` on the database, `SELECT TOP 1 1 FROM m WITH (HOLDLOCK, INDEX(inflight))
WHERE tx_state IN (1, 2)` fails with Msg 8622 (no plan because of the hints): the literal becomes a
parameter and the filtered index no longer matches (measured on m8-mssql). Every coarse-path read at
SERIALIZABLE then fails (closed).

## Proposed Solutions

1. Add `OPTION (RECOMPILE)` to the check (literals kept), or check the database's parameterization
   setting and refuse at connect; probe which works (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 5acbf90 (8c3), verified as stated above.
