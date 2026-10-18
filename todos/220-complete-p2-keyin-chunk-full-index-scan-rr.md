---
status: complete
priority: p2
issue_id: "220"
tags: [code-review, concurrency, mysql, mariadb]
dependencies: []
---

# keyIn's OR of point lookups becomes a full index scan on large chunks and locks the whole table at RR

## Problem Statement

Writes.java:1852 (keyIn), CHUNK = 1000 (:817); callers stampFromData INSERT IGNORE ... SELECT
(:1120, no index hint), orphan takeover (:1421), lockedVersions (:1691/:1697, FORCE INDEX (PRIMARY)
still scans the whole index). With a chunk that is a large share of the table (1000 keys / ~3000
rows) MySQL picks type=index; at RR every scanned row and gap is next-key locked up to the supremum.
Verified (scratchpad rv8b1, probes I2/K): a 1000-row INSERT leaves 2006 S locks on ix incl.
supremum and a far-key INSERT by another transaction fails 1205; a 1000-row locking read leaves
2006 X locks on PRIMARY, same 1205; 100 keys take record locks only. MariaDB: the 1000-row INSERT
path also blocks a far-key INSERT (1205). Contradicts design §5 "lock only the rows".

## Proposed Solutions

### Option 1
Make the key list the driving table: `(SELECT ? AS k0 UNION ALL ...) u STRAIGHT_JOIN data d FORCE
INDEX (PRIMARY) ON d.k = u.k0` (or JOIN_ORDER hint), also in the stamp's INSERT ... SELECT.
### Option 2
Smaller CHUNK off PostgreSQL (lowers odds only; the plan still depends on table size).

## Acceptance Criteria

- [x] 1000-key writes and locking reads at RR take record locks only (data_locks probe)
- [x] Far-key INSERT does not wait

## Recommended Action

## Work Log

### 2026-10-18 - Found

**By:** Claude Code
- From the review of 8b1 (9d4a74a, 056849c). Probes in the session scratchpad (rv8b1).

### 2026-10-18 - Fixed

**By:** Claude Code
- Fixed: off PostgreSQL the key lookups are `<keyValues> u STRAIGHT_JOIN data d ON d.k = u.k0` (Names.dataOf/keysOf): the relock and snapshot reads, the stamp's INSERT ... SELECT and the orphan takeover's multi-table UPDATE. Probed eq_ref for INT, BIGINT UNSIGNED, DECIMAL, DATETIME, DATE, FLOAT, DOUBLE, CHAR bin, latin1 VARCHAR, VARBINARY, TIME on both engines; a BIT key is not found this way (not supported as a key in practice). Probe: 1000 keys of 3000 rows, record locks only, far INSERT does not wait. Test in SnapshotIntegrationTest. A MariaDB range locking read still blocks such an INSERT: todo 228.
