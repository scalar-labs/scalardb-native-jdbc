---
status: complete
priority: p2
issue_id: "248"
tags: [code-review, mariadb, serializable, rollback-only]
dependencies: []
---

# MariaDB error 1020 (innodb_snapshot_isolation) rolls the transaction back but is not marked rollback-only

## Problem Statement

With `innodb_snapshot_isolation=ON` (a session or global setting on MariaDB 11.4, the default in
later MariaDB releases per its docs), a locking read at REPEATABLE READ of a row changed since the
transaction's read view fails with `ER_CHECKREAD` 1020, SQLState HY000, and InnoDB rolls back the
whole transaction. `Writes.endsTransaction` (~616) treats only class 40 (and 1205 with
`innodb_rollback_on_timeout`) as ending the transaction, so `Proxies` (~840) does not set
`rollbackOnly`: the session goes on in a new transaction while the driver keeps the checks passed
and the anchors read in the old one (the hazard of todo 230). At SERIALIZABLE every SELECT is now a
locking read, so 1020 is the ordinary conflict outcome on such a server, and the driver's own
`FOR SHARE` re-reads at SNAPSHOT hit it too.

## Findings

- MariaDB 11.4 (m8-mariadb), mariadb client with `--force`: `SET SESSION
  innodb_snapshot_isolation=ON; SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ; BEGIN;
  INSERT INTO v VALUES (9); SELECT b FROM t WHERE a=2;` then another session updates `t` row 1 and
  commits; `SELECT b FROM t WHERE a=1 LOCK IN SHARE MODE` fails with `ERROR 1020 (HY000) Record has
  changed since last read`; afterwards `@@in_transaction` = 0 and the own insert is gone. With the
  setting OFF the read returns the new value and the transaction stays (in_transaction 1, insert
  visible).
- JDBC probe (scratchpad `SiProbe`, mariadb-java-client 3.5.10): the exception is plain
  `java.sql.SQLException`, SQLState HY000, code 1020; the next statement runs in a new transaction
  without the insert.
- `@@global.innodb_snapshot_isolation` is 0 on the container, so the integration tests never meet it.

## Proposed Solutions

### Option 1: count 1020 on MariaDB as ending the transaction
In `endsTransaction`, return true for error code 1020 when the product is MariaDB; consider mapping
it to 40001 for the application as a serialization failure. One place, every caller benefits.

### Option 2: refuse or turn off innodb_snapshot_isolation
Read it at connect (as design 4.x reads other settings) and `SET SESSION
innodb_snapshot_isolation=OFF`, or refuse the connection. Avoids the error but overrides the user's
server setting.

## Acceptance Criteria

- [ ] A 1020 on a forwarded read at SNAPSHOT/SERIALIZABLE on MariaDB marks the transaction
      rollback-only (or surfaces as 40001)
- [ ] An integration test runs with `innodb_snapshot_isolation=ON`

## Work Log

- 2026-10-18: filed by the focused review of 45afd86 and e24a850 (probes on MariaDB 11.4).
- 2026-10-18: fixed (Option 1): endsTransaction counts 1020 on MariaDB. At SERIALIZABLE every read locks, so no read view forms and 1020 does not arise there; SNAPSHOT is where it does. SnapshotIntegrationTest runs a SNAPSHOT read with innodb_snapshot_isolation=ON; it fails without the fix.
