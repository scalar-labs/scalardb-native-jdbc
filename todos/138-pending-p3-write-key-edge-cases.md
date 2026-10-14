---
status: pending
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

## Acceptance Criteria

- [ ] Both probes write the row.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).
