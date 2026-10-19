---
status: pending
priority: p1
issue_id: "275"
tags: [code-review, oracle, correctness]
dependencies: []
---

# A COMPOUND trigger passes the trigger refusal; the INSERT then stamps an older row

## Problem Statement

`Writes.oracleTrigger` (Writes.java:226) matches `trigger_type LIKE 'BEFORE%'` only. Oracle reports a
COMPOUND trigger as `COMPOUND`; its BEFORE EACH ROW section can change `:NEW.k` (todo 201's hole on
MySQL).

## Findings

Through the driver: imported `rv.ct (k NUMBER(10) PK, x)` with row (5, 0) stamped; trigger `FOR INSERT
... COMPOUND TRIGGER BEFORE EACH ROW IS BEGIN :NEW.k := :NEW.k + 100; ...`. `INSERT INTO rv.ct (k, x)
VALUES (5, 1)` returns 1 and stores 105; the read-back by value finds row 5 and stamps it (new tx_id,
version 2); row 105 gets no metadata; getGeneratedKeys reports 5.

## Proposed Solutions

1. Refuse any enabled row-level INSERT trigger: `trigger_type IN ('BEFORE EACH ROW', 'COMPOUND')` or
   simply any INSERT trigger on the data table (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 2ce2755 (8d1), verified as stated above.
