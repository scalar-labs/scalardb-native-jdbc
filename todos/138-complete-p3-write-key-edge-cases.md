---
status: complete
priority: p3
issue_id: "138"
tags: [code-review, writes, postgresql]
dependencies: []
---

# Two key edge cases: NULL tx_state with a tx_id, and TIMESTAMP keys in a DST gap

## Problem Statement

Two rare rows the driver cannot write.

## Findings

- A metadata row with a `tx_id` and NULL `tx_state` (foreign writes only) always aborts: S1 treats NULL
  as committed but the CAS tests `tx_state = 3` (`Writes.cas`); section 1 says every state-3 test is
  `COALESCE(tx_state, 3) = 3`. Probe confirmed.
- A `TIMESTAMP` key in the JVM zone's spring-forward gap is read back by `getObject` as a lenient
  `Timestamp` an hour later and matches nothing (probe confirmed, America/New_York).

## Proposed Solutions

### Option 1
Use `COALESCE(tx_state, 3) = 3` in both CAS forms; read keys as `LocalDateTime`/`OffsetDateTime` for
timestamp types (or through `getObject(i, Class)` per column type).

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] The NULL-state row is written (WriteIntegrationTest "138").
- [x] A TIMESTAMP key in a DST gap is written (WriteIntegrationTest "138", the JVM zone set to
      America/New_York around the write).

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).

### 2026-10-14 - Fixed

**By:** Claude Code
- Both CAS forms use COALESCE(tx_state, 3) = 3; TIMESTAMP, TIME and TIMESTAMP WITH TIME ZONE keys are read as LocalDateTime, LocalTime, OffsetDateTime. Test "138" for the NULL state; the DST case is not tested (needs the JVM zone).

### 2026-10-14 - DST case tested

**By:** Claude Code
- The test switches the JVM's default zone around one write of a key at 2020-03-08 02:30.
