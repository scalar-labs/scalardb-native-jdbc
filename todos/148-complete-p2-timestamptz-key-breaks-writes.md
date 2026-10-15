---
status: complete
priority: p2
issue_id: "148"
tags: [code-review, correctness, jdbc, writes]
dependencies: []
---

# Every write to a table keyed by TIMESTAMPTZ fails reading its key back

## Problem Statement

pgjdbc reports a `timestamptz` column as `Types.TIMESTAMP`, so `Writes.readKey` asks for
`getObject(c, LocalDateTime.class)`, which pgjdbc rejects for TIMESTAMPTZ. Since d7be4f0 every INSERT
reads its key back through `readKey`, so any INSERT into such a table fails; the UPDATE/DELETE key
read has failed the same way since eb42eb4 (todo 138's LocalDateTime case).

## Findings

- Probe (write reviewer): imported `pr.tz (k TIMESTAMPTZ PRIMARY KEY, x TEXT)`;
  `INSERT INTO pr.tz (k, x) VALUES (now(), 'a')` -> `Cannot convert the column of type TIMESTAMPTZ to
  requested type java.time.LocalDateTime`; `UPDATE pr.tz SET x='u' WHERE x='n'` fails the same way.
- Writes.java `readKey` (Types.TIMESTAMP case); callers InsertPlan.apply, settledKeys, LockPlan.apply,
  skipOrAbort, AnchoredHandler.

## Proposed Solutions

### Option 1
Choose by `getColumnTypeName(c)`: `timestamptz` -> OffsetDateTime, `timestamp` -> LocalDateTime, `time`
-> LocalTime, `timetz` -> OffsetTime; the type code otherwise.

## Recommended Action


## Acceptance Criteria

- [x] INSERT, UPDATE, DELETE, an anchored read and a locking read of a TIMESTAMPTZ-keyed table succeed (integration test).

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of d7be4f0 and f56c8d1 (milestone 4c).

### 2026-10-15 - Fixed

**By:** Claude Code
- readKey picks the type by the column's type name: timestamptz as OffsetDateTime, timetz as OffsetTime. Test: a TIMESTAMPTZ-keyed imported table, INSERT (now() and a literal), UPDATE, a locking read, an anchored read and a DELETE.
