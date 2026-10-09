---
status: complete
priority: p3
issue_id: "047"
tags: [code-review, performance, catalog]
dependencies: []
---

# A catalog hit rebuilds the `TableInfo` from the metadata rows on every lookup

## Problem Statement

The resolution cache stores the engine's `QualifiedName`, not the answer. Every hit therefore runs `Snapshot.classify`, which rebuilds the column lists and a fresh `TableInfo` from the metadata rows, plus two `Instant`s for the TTL check, a joined key string and two `resolveIdentifier` calls. It is about 1% of a core at 10k statements/s, so not a bottleneck, but the cache stores the wrong thing and the garbage is avoidable.

## Findings

- Measured by performance-oracle on the built classes: a hit costs 0.52-0.59 µs / 1.9 KB in memory (0.8-1.1 µs against PostgreSQL, the two monitor enters) versus 0.14 µs / 496 B when the name is plain.
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:370-442` (`classify`) calls `columns()` (`:444-471`) on every hit: a `LinkedHashSet`, three `ArrayList`s, a `Columns`, then three `List.copyOf` in the `TableInfo` constructor.
- `Catalog.java:203-204`: `clock.instant()` and `loadedAt.plus(ttl)` allocate two `Instant`s per lookup; `:216` builds the key with `String.join` and two concatenations; `:172-173` calls `resolveIdentifier` twice (48 B).
- About 19 MB/s of garbage at 10k lookups/s.

## Proposed Solutions

### Option 1: Cache the answer, not the name

**Approach:** Store the `TableInfo` (or the refusal `SQLException`) in `resolutions` instead of the `QualifiedName`, keyed with the snapshot generation so a reload invalidates it; precompute `expiresAt` in `Snapshot`; take the lock once per lookup. A hit becomes key build plus one map get (the measured plain path, about 0.15 µs / 500 B).

**Pros:**
- Removes the rebuild and one of the two lock acquisitions.

**Cons:**
- Must be designed together with the lock change of todo 035 and the key fix of 044.

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Memoise `classify` per snapshot

**Approach:** Keep the resolution cache as is and add a per-snapshot `ConcurrentHashMap<String, TableInfo>` in `Snapshot` keyed by the full stored name; refusals stay exceptions.

**Pros:**
- Local change inside `Snapshot`.

**Cons:**
- Still two lookups and the `Instant` allocations per hit.

**Effort:** 1-2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:170-228, 370-471`

**Related components:**
- Todo 035 (lock across I/O), todo 044 (key collision).

**Database changes:** None.

## Resources

- Found by: performance-oracle (P3, measured; sources under the review scratchpad `review2/perf`).

## Acceptance Criteria

- [ ] A hit allocates no `TableInfo` and no column lists.
- [ ] A hit takes the lock at most once.
- [ ] The TTL and invalidate integration cases still pass on all five engines.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- performance-oracle measured the hit and plain paths and traced the allocation to `classify`/`columns()`.

**Learnings:**
- Cache the result the caller needs, not an intermediate.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- Each cached resolution keeps the classification it produced, tagged with the snapshot it came from: a TableInfo, or a refusal rethrown as a fresh SQLException with the same message and SQLSTATE. A hit against the same snapshot returns the same TableInfo instance; a reload reclassifies on the next lookup. Test: aHit_returnsTheClassificationAlreadyMade on every configuration.

## Notes

- Fold into the 035 lock rework rather than doing it alone.
