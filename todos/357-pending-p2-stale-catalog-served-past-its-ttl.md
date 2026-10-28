---
status: pending
priority: p2
issue_id: "357"
tags: [catalog, isolation, mariadb, flaky-test]
dependencies: []
---

# A ScalarDB table read as plain: a stale catalog is served for as long as a reload is busy

## Problem Statement

The integration suite at 1e9f967 (2026-10-28, all six engines, two containers at a time, load
average about 16): `ReadOnlyReaderIntegrationTest.readOnlyReader_isSerializable`, MariaDB, inline
layout, failed. Every case where ScalarDB is S2 and the reader reads while y is in flight (groups
3, 4, 8, 9, 12, 13, 16, 17, 20, 21, 24, 25) committed instead of aborting with 40001, and read the
stored values as they are: S1's uncommitted y = 10 (`{1=0, 2=10}`, `{1=1, 2=10}`) and a raw sum
(`{0=10}`, `{0=11}`). The driver-writer half of the same test passed. The test alone passed twice,
and the whole MariaDB task passed when run again. The other five engines passed.

## Findings

- What the reader saw is what a plain table returns: no in-flight check, the after image of a
  prepared row. So for a stretch of several seconds the driver most likely classified `roi.t` as
  plain.
- The tests' connections (`Engines.openDriver`) share one `Catalog` per JVM
  (`NativeJdbcDriver.catalog`), with `catalog_ttl_millis` 0. The test creates `roi.t` through
  ScalarDB's admin right before it reads, so any catalog loaded earlier lists no `roi.t`.
- With TTL 0 every lookup reloads, except that `Catalog.snapshot()` serves the previous snapshot
  when `loadLock` is held by another thread, and `resolve()` serves a stale resolution when
  `connectionLock` is held, with no bound on the age of what is served. The design accepts this
  (design.md, "Catalog cache").
- Not explained: the test is single-threaded and JUnit runs sequentially here, so the lock holder
  would be a thread left over from an earlier class (`MoreWritesIntegrationTest`'s pools are
  candidates), in a catalog query that is slow or hung; the catalog connection's network timeout
  is 30 s. Unconfirmed. The failing run's XML report was overwritten by the rerun.
- Beyond the test: a stale snapshot is served past the TTL for as long as a reload is busy, up to
  the 30 s network timeout per attempt. The setup command waits out only `--catalog-ttl` before it
  says ScalarDB transactions may write a new table, so a driver whose reload hangs in that window
  could still read and write the table as plain.

## Proposed Solutions

1. Test only: refresh the driver's catalog after the test creates its table. Hides the failure;
   leaves the driver's gap.
2. Bound the staleness: serve a stale snapshot or resolution only up to its expiry plus a margin,
   and past that wait for the reload. Changes the design's "a lookup never waits on a round trip".
3. Both.

## Recommended Action

Confirm the cause first: log in a scratch build every stale serve with its age and the lock
holder's stack, and loop the MariaDB suite under load. Then decide between 2 and 3.

## Acceptance Criteria

- [ ] The cause confirmed or replaced by the one found
- [ ] The driver's staleness bound decided, and the design's catalog paragraph and the setup's
      wait stated against it
- [ ] `ReadOnlyReaderIntegrationTest` stable on MariaDB under the full suite

## Work Log

### 2026-10-28

- Found in the integration run on merged main 1e9f967; reruns passed (alone twice, the MariaDB task
  once). Traced through `Catalog`, `NativeJdbcDriver.catalog` and the test; reproduction started.

## Resources

- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java` (`snapshot`, `resolve`,
  `NETWORK_TIMEOUT`)
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/jdbc/ReadOnlyReaderIntegrationTest.java`
- `docs/design.md`, "Catalog cache"; `docs/setting-up-tables.md`, the setup's TTL wait
