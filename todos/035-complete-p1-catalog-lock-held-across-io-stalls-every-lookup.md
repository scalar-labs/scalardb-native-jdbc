---
status: complete
priority: p1
issue_id: "035"
tags: [code-review, performance, reliability, architecture, catalog]
dependencies: []
---

# The catalog holds one lock across its I/O, so a TTL reload or a resolution miss stalls every lookup in the JVM

## Problem Statement

`Catalog` is one instance per database, shared by every connection and thread of the JVM. Its `snapshot()` runs the whole-catalog load inside `synchronized (lock)`, `resolve()` runs the engine's round trip inside the same lock, `lookup` takes it twice per call, and every TTL reload clears the resolution cache. A reload of a 5,000-table catalog takes 150 ms, and during it every thread that needs a lookup, cache hits included, waits. After the reload every name in use misses again, each miss serialising the JVM for about half a millisecond. The design forbids `synchronized` on hot paths because of virtual-thread pinning and asks for `ReentrantLock`.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:200-212` (`snapshot()`): `load()` runs under `synchronized (lock)`; `:214-228` (`resolve()`): `engine.resolve` runs under the same lock; `:170-183` (`lookup`): two lock acquisitions per call; `:209`: `resolutions.clear()` on every TTL reload.
- `docs/design.md:862-866`: no `synchronized` on hot paths; `ReentrantLock` where a lock is needed at all.
- Measured by performance-oracle against PostgreSQL 16 (local container, pgjdbc 42.7.13): a load is two whole-table scans, 44 ms median at 500 tables (20k `metadata` rows), 152 ms median / 171 ms p90 at 5,000 tables (200k rows), 9 MB allocated per load (the in-memory CPU part is 6 ms; the rest is row transfer). A miss is 0.37-0.55 ms median per distinct name.
- Stall experiment (TTL 500 ms, a 200 ms reload, 8 threads doing hits for 2 s): every hitter waits the full reload, max hit latency 209 ms, 32 lookups over 10 ms = 8 threads x 4 reloads, none served stale.
- At 10k statements/s with 5,000 tables about 1,500 statements queue behind a 150 ms I/O once a minute, plus the post-reload miss storm (200 names: about 90 ms of serialised lookups per minute); the stall grows linearly with the catalog.
- The lock itself is not the bottleneck: hits run at 1.72M lookups/s single-threaded (0.58 us) and 2.97M/s aggregate at 16 threads, so at 10k/s the monitor is under 1% busy. The problem is I/O under it.
- Worst-case holders: SQL Server resolves run the same query twice and Oracle up to three (todo 046); a hung query or `open()` with no timeout (todo 037) blocks every lookup indefinitely.

## Proposed Solutions

### Option 1: Never hold the map lock across I/O: stale-while-revalidate and resolve outside the lock

**Approach:** Make `snapshot` a `volatile` field and store `expiresAt` in `Snapshot`. On expiry, `tryLock()` a separate `ReentrantLock loadLock`: the thread that gets it reloads and swaps the reference; every other thread keeps serving the stale snapshot (only a `null` snapshot waits). Resolve misses outside the map lock: `get` under the lock, run the engine query serialised on the connection's own monitor, `put` under the lock; a duplicate concurrent resolve of the same name is harmless. Do not clear `resolutions` on a TTL reload, only on `invalidate()`; give each entry its own `resolvedAt` and re-resolve lazily when it is older than the TTL.

```java
private volatile @Nullable Snapshot snapshot;
private final ReentrantLock loadLock = new ReentrantLock();

private Snapshot snapshot() throws SQLException {
  Snapshot s = snapshot;
  Instant now = clock.instant();
  if (s != null && s.expiresAt.isAfter(now)) return s;
  if (s != null && !loadLock.tryLock()) return s; // stale, someone else is reloading
  if (s == null) loadLock.lock();                 // nothing to serve yet: wait for the first load
  try {
    Snapshot again = snapshot;
    if (again != null && again.expiresAt.isAfter(now)) return again;
    Snapshot loaded = withConnection(c -> load(c, now));
    snapshot = loaded;
    return loaded;
  } finally { loadLock.unlock(); }
}
```

**Pros:**
- Hits never wait for I/O; a reload costs one thread, once per TTL.
- Follows the design's `ReentrantLock` rule and keeps the LRU bound.

**Cons:**
- Readers see a stale snapshot for the duration of a reload (bounded by the reload time, which is the current behaviour's stall).
- Slightly more state (`expiresAt`, per-entry `resolvedAt`).

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Load per table on a miss instead of the whole catalog

**Approach:** Replace the whole-catalog load with a per-name query (`WHERE full_table_name IN (?, ?||'_data', ?||'_tx_metadata')` plus the three `virtual_tables` lookups) so a reload is O(1) per name and the 9 MB snapshot disappears; cache the `TableInfo` per name with its own TTL.

**Pros:**
- Removes the O(catalog) load entirely; scales to any deployment size.

**Cons:**
- Three to four queries per miss, back to the pattern 009 replaced; the classification is no longer a pure function of loaded rows (todo 024's testability).
- Larger change; only needed if 5,000-table deployments are real.

**Effort:** 1-2 days

**Risk:** Medium

---

### Option 3: Keep the lock, shorten what it covers

**Approach:** Keep a single `ReentrantLock` but open the connection and run the queries before taking it, then install the result under the lock.

**Pros:**
- Smallest diff.

**Cons:**
- Concurrent misses for the same name run duplicate queries (harmless) and reloads still race; does not solve the reload stampede without Option 1's `tryLock`.

**Effort:** Small

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:99-110, 170-183, 200-228, 235-252` - fields, `lookup`, `snapshot`, `resolve`, `withConnection`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:347-367` - `Snapshot` gets `expiresAt`
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java` - the TTL case with the fake clock still passes; add a concurrent-hit case if cheap

**Related components:**
- Todo 037 (connection lifetime, timeouts, retry), todo 046 (resolve round trips), todo 047 (cache `TableInfo` on the hit path), todo 008 (statement cache, the other hot-path item).

**Database changes:** None.

## Resources

- `docs/design.md` section 4.6 (`synchronized` rule), section 1 "The catalog".
- Found by: performance-oracle (P1, measured), architecture-strategist (P2), agent-native-reviewer (P3), feature-dev:code-reviewer (P2), security-sentinel (P3).
- Benchmarks: `scratchpad/review2/perf/src/.../catalog/CatalogBench.java`, `CatalogPgBench.java`; results in `catalog-bench.txt`, `catalog-pg-bench.txt`.

## Acceptance Criteria

- [ ] No network I/O runs while a lock that `lookup` hits take is held.
- [ ] During a TTL reload, concurrent hits are served from the stale snapshot; max hit latency during a reload is in the microseconds, not the reload time.
- [ ] A TTL reload does not clear the resolution cache; entries expire individually.
- [ ] No `synchronized` block remains on the lookup path.
- [ ] The TTL and `invalidate` integration cases still pass on all five engines; `CatalogTest` unchanged or extended.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- performance-oracle measured load, miss and hit costs against a real PostgreSQL 16 with 500 and 5,000 ScalarDB tables and ran the 8-thread stall experiment.
- architecture-strategist and agent-native-reviewer flagged the design's `synchronized` rule; feature-dev:code-reviewer and security-sentinel flagged the global mutex over network I/O and the missing timeouts.

**Learnings:**
- The hit path is two orders of magnitude faster than needed; only the I/O under the lock matters, so the fix is structural (where the lock is), not an optimisation.
- Clearing the resolution cache on reload turns one reload into N serialised misses.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- No lock is held across a round trip any more. The snapshot is a volatile reference carrying its expiry; a stale snapshot is served while the one thread that wins tryLock on a separate ReentrantLock reloads and swaps it, and only a null snapshot makes callers wait. Resolutions live in the LRU under a short synchronized block for get and put only; a stale resolution is served while the catalog connection is busy (tryLock on the connection's own ReentrantLock) and only a missing one waits; entries expire individually, so a reload no longer triggers a miss storm. The catalog connection is guarded by its own lock, reentrant so callers may hold it.
- Test: aReloadNeverBlocksCacheHits (PostgreSQL) wraps the catalog connection in a proxy that delays every query by 200 ms, expires the TTL, and asserts that eight hits during the reload each finish in under 100 ms; before the fix they took 375 ms (lead's probe) and, with per-entry expiry alone, 570 ms.
- Also done here from 037: a connection that fails with a class-08 SQLSTATE after it had been used is reopened once and the query retried; setAutoCommit(true) after open; Catalog is AutoCloseable and the tests close theirs. Not done: timeouts on the catalog connection, eager load at connect, the driver's per-URL map.

## Notes

- The lead rated this P1 rather than P2: a global mutex across every connection of a JDBC driver around network I/O, in breach of a stated design rule, is a critical architectural issue even though no data is at risk.
