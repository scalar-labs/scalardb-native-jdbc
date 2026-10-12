---
status: complete
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

Option 1, plus (decided by the user, 2026-10-12) a SQL Server connection stays in the database it was
opened in: the catalog and the check are for that database alone.

## Technical Details

See Findings.

## Acceptance Criteria

- [x] Connecting to a SQL Server database without READ_COMMITTED_SNAPSHOT is refused with 08004.
- [x] The test container enables it, and the full suite passes.

## Work Log

### 2026-10-12 - Fixed

**By:** Claude Code
- `Session.requireReadCommittedSnapshot` runs at connect on SQL Server (`Proxies.connection`): one query
  on `sys.databases` for `DB_ID()`; off is refused with 08004 naming the `ALTER DATABASE` that fixes it.
- Database switches: `USE` naming another database is refused in `Router` (`SqlAst.useTarget`),
  `setCatalog` in the connection proxy, both 0AN01; the same database is allowed. A switch under the
  passthrough setting, which the driver does not read, is caught when the session is next re-read:
  every statement is refused with 0AN01 until the connection is back in its database.
- The test container enables READ_COMMITTED_SNAPSHOT on `appdb`. `SqlServerDatabaseIntegrationTest`:
  a database without it is refused; USE/setCatalog to another database refused, to the same allowed;
  a passthrough switch stops the next statement. Full suite passes on all five engines.
- Design section 1 states the database rule.

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
