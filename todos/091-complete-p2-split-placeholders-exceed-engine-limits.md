---
status: complete
priority: p2
issue_id: "091"
tags: [code-review, robustness, split-read]
dependencies: []
---

# Repeating placeholders per copy can exceed the engine's parameter limit

## Problem Statement

Every `?` is repeated in each of the 2^n copies. SQL Server allows 2100 parameters per statement: `WHERE
id IN (?, ...)` with 1051 or more values over one split table, or 131 over four, fails, while the same
statement runs through rc_T. pgjdbc's limit is 32767 and MySQL's 65535.

## Findings

- `SplitRead.emit` checks only `MAX_TABLES` (SplitRead.java:61-63); `Router.read`.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Confirmed on SQL Server: 1100 placeholders over one table fail ("maximum of 2100 parameters").
PostgreSQL ran 17000 placeholders (34000 emitted) without error, so its limit is higher than assumed
(the protocol allows 65535); only SQL Server is affected at realistic sizes.

## Proposed Solutions

### Option 1: fall back above a per-engine limit
Return null from the split when `sources.size()` exceeds the engine's limit (SQL Server 2100, PostgreSQL
32767, MySQL/MariaDB 65535, Oracle per its driver), so the statement reads through rc_T.
**Effort:** Small. **Risk:** Low.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [x] A SQL Server statement with 1100 placeholders over one ScalarDB table runs (through rc_T), by
      the scratch probe; no integration test.
- [x] SplitTest asserts the fallback at the limit.

## Work Log

### 2026-10-12 - Fixed

**By:** Claude Code
- `checked()` falls back when the emitted placeholders exceed 2100 on SQL Server (65535 elsewhere).
- SplitTest at the limit; probe: 1100 placeholders on SQL Server now return the rows through rc_T.

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
