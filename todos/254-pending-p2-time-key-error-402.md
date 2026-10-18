---
status: pending
priority: p2
issue_id: "254"
tags: [code-review, sqlserver, correctness]
dependencies: []
---

# Every write to a table with a TIME key fails with error 402

## Problem Statement

mssql-jdbc's default `sendTimeAsDatetime=true` sends a `LocalTime` as `datetime`; the driver
never sets it. A ScalarDB table with a TIME partition key (`time(6)`): INSERT, UPDATE and DELETE
through the driver all fail with S0001/402 "The data types time and datetime are incompatible in
the equal to operator". Nothing is changed, but such tables can't be written.

## Proposed Solutions

1. Set `sendTimeAsDatetime=false` on the driver's connections (check user-visible effect).
2. Same fix as 253 (cast or explicit type).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
