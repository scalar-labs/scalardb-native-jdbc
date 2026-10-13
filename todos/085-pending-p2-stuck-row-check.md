---
status: pending
priority: p2
issue_id: "085"
tags: [operations, diagnostics, liveness]
dependencies: ["026", "084"]
---

# Report rows that ScalarDB left prepared past the transaction lifetime

## Problem Statement

The driver cannot settle a row ScalarDB left prepared, and ScalarDB has no job that does (todo 084).
Such rows show stale values to the driver's reads and block its writes, with no sign of why. Todo 105
showed they happen in normal operation on Oracle, not only after a crash. An operator needs to see them.

## Proposed Solution

The stuck-row check of design 4.4: per decoupled table, read-only and in autocommit,

```sql
SELECT COUNT(*), MIN(tx_prepared_at) FROM T_meta
WHERE tx_state IN (1, 2) AND tx_prepared_at < :now_ms - 15000
```

served by the in-flight index. Report per table the count and the oldest age. It never reads the
coordinator and never writes.

Where it lives: a `check` subcommand of the `tools` setup command (CLI contract in todo 026); possibly
also a driver API or log line, if a monitoring hook is wanted.

## Acceptance Criteria

- [ ] The check reports per table the count and the oldest age of rows prepared past the lifetime, and
      exits 1 when there are any (todo 026's exit codes).
- [ ] An integration test leaves rows prepared (state 1, `tx_prepared_at` in the past) and sees them
      reported, and a committed table reports none.
- [ ] The documentation says what to do with a report: ScalarDB recovery (todo 084), or a ScalarDB read
      of the keys.

## Work Log

### 2026-10-12 - Recorded

**By:** Claude Code
- Replaces the driver-side sweeper, which the design dropped so the driver does not depend on the
  coordinator.
