---
status: pending
priority: p1
issue_id: "088"
tags: [code-review, correctness, sqlserver, split-read]
dependencies: []
---

# SQL Server's READ_COMMITTED_SNAPSHOT is not checked at connect

## Problem Statement

The design (sections around lines 110, 280, 366 and the 08004 row at 402) says the driver checks
`sys.databases.is_read_committed_snapshot_on` at connect and refuses without it, because the read rules
need a statement-level snapshot at READ COMMITTED. Nothing in `driver/src/main` implements it. Under
locking READ COMMITTED a copy that scans `T` and a later copy that probes `T_meta` can see different
states: a prepare landing between them returns a row twice (committed and before image), a record commit
drops it. The rc_T view had the same exposure with two arms; the split has up to 16 copies, and SQL
Server's LEFT_JOIN copies widen the window.

## Findings

- `grep -rn read_committed_snapshot driver/src/main` finds nothing; Proxies.java:163 refuses only
  READ_UNCOMMITTED.
- Todo 052 assumes the check exists. The SQL Server test container (`Engines.SQLSERVER`) creates `appdb`
  without enabling it, so the integration runs exercise the locking mode.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Confirmed: the SQL Server test database has `is_read_committed_snapshot_on = 0` and the driver
connects anyway. The race itself (a row twice or missing) was not reproduced; it needs a ScalarDB
prepare or commit between two copies of one statement.

## Proposed Solutions

### Option 1: implement the check as designed
At connect on SQL Server, read `is_read_committed_snapshot_on` for the current database and refuse with
08004 when it is off; enable it in the test container. **Effort:** Small. **Risk:** Low; existing users
without it are refused, as the design states.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] Connecting to a SQL Server database without READ_COMMITTED_SNAPSHOT is refused with 08004.
- [ ] The test container enables it, and the full suite passes.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
