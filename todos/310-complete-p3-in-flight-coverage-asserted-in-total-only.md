---
status: complete
priority: p3
issue_id: "310"
tags: [code-review, testing, in-flight]
dependencies: []
---

# Rows in flight asserted in total only: one layout or table can run with none, and no abort is required

## Problem Statement

T3's in-flight phase asserts the sum of held keys over both layouts and three tables is positive.
On Oracle a prepare can fail (ORA-08177) and `InFlight` drops that transaction; if all three of a
layout fail, or a table gets no key at 30%, that layout or table runs as the settled phase while the
report says "rows in flight". Nothing asserts that any statement reached a held key (the plan's
"8 to 13" aborts are observed, not asserted). T2 checks rows in flight on the inline schema only
(`tx_state IN (1, 2)`), never on the decoupled one.

## Findings

- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/WriteCorpusIntegrationTest.java:152-154`: `heldKeys.values()...sum()` positive.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/InFlight.java:153-157`: a failed prepare is rolled back and its keys left out.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/coverage/CorpusIntegrationTest.java:141-160`: the count reads `set + "i"` only.

## Proposed Solutions

### Option 1
Assert per `<layout>.<table>` that keys are held (T3) and rows are in flight in both layouts (T2,
through the decoupled metadata table); assert `aborted > 0` per layout in T3. **Effort:** Small.

## Acceptance Criteria

- [ ] Each layout and table has rows in flight in both T2 and T3, asserted.
- [ ] T3's in-flight phase fails if no statement reached a held key.

## Work Log

### 2026-10-21 - Found by the review of T3

**By:** Claude Code

### 2026-10-21 - Fixed

Fixed with the other T3 review todos (305-311); five engines pass, both phases.
