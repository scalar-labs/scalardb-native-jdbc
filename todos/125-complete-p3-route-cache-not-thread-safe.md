---
status: complete
priority: p3
issue_id: "125"
tags: [code-review, concurrency, routing]
dependencies: ["008"]
---

# The per-connection route cache breaks under concurrent use of one connection

## Problem Statement

`Router.routes` is an access-ordered `LinkedHashMap` (`Router.java:199`); `get()` reorders it and
nothing synchronizes it. JDBC drivers such as pgjdbc allow one connection to be used from several
threads. Concurrent use corrupts the map: it grows past its bound, and iteration or a later `get` can
loop or throw. Before a7ea1c1 only PostgreSQL's `parallelPlain` map had this risk.

## Findings

- Repro: 8 threads calling `route()` on one Router (passthrough, 400 texts): `size()` 467 (bound 256);
  iterating the keys gave 0 entries.

## Proposed Solutions

### Option 1
- `synchronized` on `route` / `current` (or a lock around the map).
- **Effort:** Small. **Risk:** Low; contention only for shared connections.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] A unit test with concurrent `route()` calls keeps the bound and a working map.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of a7ea1c1; reproduced with a probe.

### 2026-10-14 - Fixed

**By:** Claude Code
- Every Router and Session entry point holds a per-object `ReentrantLock` (not `synchronized`,
  which pins a virtual thread's carrier through round trips; design section on Java targets).
- RouterTest `routesFromSeveralThreads_keepTheCacheWorking`: 8 threads, then the cache's bound and
  iteration checked.
