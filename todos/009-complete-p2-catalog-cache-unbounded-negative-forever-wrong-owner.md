---
status: complete
priority: p2
issue_id: "009"
tags: [code-review, performance, reliability, catalog]
dependencies: []
---

# Catalog cache is unbounded, caches misses forever, uses an ambiguous key and the wrong owner

## Problem Statement

`Catalog` caches every lookup result in a `ConcurrentHashMap` for the lifetime of the driver, including negative results (a name that is not a ScalarDB table is stored as `PLAIN`). The design (docs/design.md 4.4, "Catalog cache") calls for refresh on a miss, on "not found" errors, and a TTL; none of that exists. The consequences are a fail-open path (a table imported into ScalarDB after its first lookup is forwarded unrewritten in every running JVM until someone calls `invalidate()`), unbounded memory growth from arbitrary names, a cache key that collides for dotted names, a miss that costs two to three sequential round trips, and a "per driver instance" owner although one `java.sql.Driver` serves many databases.

## Findings

Lines refer to commit 06c137f.

- `catalog/Catalog.java:65` holds the cache (`ConcurrentHashMap`); `:94` builds the key as `ns + "." + table`; `:95-101` and `:193-194` store misses as `PLAIN` with no expiry.
- Fails open: a plain table later created or imported through ScalarDB stays `PLAIN` until `invalidate()`, which nothing in the JDBC surface can call (see todo 026). A write to it is forwarded verbatim with no error.
- Unbounded: every distinct name, including nonexistent ones, typos, Hibernate `HT_*` temporary tables and `information_schema` probes, is cached forever. About 250 B per entry (key ~70 B, CHM node 32 B, `TableInfo` with three empty lists); one million names is about 250 MB.
- Ambiguous key: `"x.a"."b"` and `"x"."a.b"` collide. JSQLParser splits a quoted `"a.b"` into schema `a` and table `b` (verified by the security reviewer's probe), so the user's `"a.b"` is looked up as `a.b` and would be rewritten to `V_b`.
- Miss cost: query 1 (`full_table_name = ?`), query 2 (`left_source_table_full_table_name = ?`), then `columns()`; plain and classic tables pay all three plus `getMetaData().getIdentifierQuoteString()` (`Catalog.java:116-194`). The simplicity reviewer confirms steps 1 and 2 are the same query twice (`Catalog.java:115-177`).
- Wrong owner: the design says "read once per driver instance", but `ns.table` collides across URLs when one driver serves several databases. `NativeJdbcDriver.java:58-69` holds no catalog yet, so the owner is still to be decided.
- Concurrency: CHM get/put is safe; concurrent misses each run the full read (benign duplication, N×3 queries at pool warm-up); `invalidate()` racing a miss can re-insert the just-read entry after `clear()` (acceptable under TTL semantics, but it is not a barrier).
- Verified: by code reading of `Catalog.java` by four reviewers and the probe of JSQLParser's dotted-name split.

## Proposed Solutions

### Option 1: Bounded LRU with expiry on negative entries, structured key, one virtual_tables query

**Approach:** Replace the map with a bounded LRU (for example 4,096 entries). Give `PLAIN` entries an expiry (for example 60 s) and keep ScalarDB entries until `invalidate()`. Key by `(schema, table)` instead of a joined string, and refuse identifiers containing `.` or quote characters until the engine strategy escapes them. Collapse steps 1 and 2 into one `SELECT ... FROM virtual_tables WHERE full_table_name = ? OR left_source_table_full_table_name = ? OR right_source_table_full_table_name = ?` and branch on which column matched (this also fixes todo 010). Hold one `Catalog` per `(vendorUrl, metadataSchema)` in the driver. Fix the design wording from "per driver instance" to "per database".

**Pros:**
- Closes the fail-open path within the TTL window.
- Bounded memory; two round trips per miss instead of three.
- Small, local changes to one class.

**Cons:**
- A TTL is a compromise: a table imported inside the window is still forwarded unrewritten until it expires.
- Expiry adds a timestamp per entry and a clock dependency to tests.

**Effort:** Small to Medium (2-4 hours)

**Risk:** Low

---

### Option 2: Load the catalog whole on first use and refresh on TTL

**Approach:** Read both `virtual_tables` (one row per decoupled table) and `metadata` (`ORDER BY full_table_name, ordinal_position`) once per database with two queries, build the full map, and refresh the whole thing on a TTL and on `invalidate()`. Misses become free (a name absent from the loaded map is `PLAIN` with no round trip); the per-name cache disappears.

**Pros:**
- No per-name negative entries at all; no unbounded growth from arbitrary names.
- Two queries per refresh regardless of how many tables the application touches.
- Simplest possible lookup path.

**Cons:**
- Pays for every ScalarDB table in the database even if the application uses one.
- A refresh is a full reload; large catalogs make refresh cost visible.
- Needs a per-database owner (same as Option 1).

**Effort:** Medium (half a day)

**Risk:** Low

---

### Option 3: Option 1 now, Option 2 if catalogs stay small in practice

**Approach:** Ship Option 1 to close the fail-open path and the growth, then measure catalog sizes in the preview deployments; switch to whole-catalog loading if they stay in the hundreds of tables.

**Pros:**
- Immediate fix, with a clear upgrade path.

**Cons:**
- Two implementations over time.

**Effort:** Medium overall

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:65` - cache map
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:91-102` - lookup and key
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:115-194` - the three-step read
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java:58-69` - where a per-database catalog map would live
- `docs/design.md` 4.4 "Catalog cache" - fix "per driver instance"

**Related components:**
- Todo 005 (catalog reads on a separate connection) changes how the read is issued
- Todo 010 (`_tx_metadata` refusal) shares the single-query change
- Todo 026 (catalog refresh reachable through the JDBC surface)
- Todo 018 (dotted and quoted identifiers)

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` 4.4 "Catalog cache"; section 1 "The catalog"
- Found by: feature-dev:code-reviewer (P2-7, 14), architecture-strategist (P2-6), security-sentinel (P3-1), performance-oracle (P2-1, P2-2), code-simplicity-reviewer (item 9)

## Acceptance Criteria

- [ ] The cache is bounded (size) and negative entries expire
- [ ] A table imported through ScalarDB after a `PLAIN` lookup is recognised after the TTL without `invalidate()` (integration test)
- [ ] The key is `(schema, table)`; `"x.a"."b"` and `"x"."a.b"` do not collide (unit test on the key, or refusal of dotted identifiers)
- [ ] A miss issues at most two round trips (one `virtual_tables` query, one `metadata` query)
- [ ] One `Catalog` per `(vendorUrl, metadataSchema)`; two URLs with the same `ns.table` do not share entries
- [ ] design.md 4.4 wording matches the implementation (owner, TTL, refresh rules)
- [ ] Unit tests pass; integration tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by feature-dev:code-reviewer, architecture-strategist, security-sentinel, performance-oracle and code-simplicity-reviewer during the `/ce:review` run on commit 06c137f; five reports merged into one todo
- Verified the dotted-name split in JSQLParser 5.4 (security reviewer's probe)
- Severity set to P2: reliability and performance, with one fail-open path bounded by a TTL once implemented

**Learnings:**
- `Catalog.read` steps 1 and 2 are the same query with a different column; one `OR` query serves both and the `_tx_metadata` refusal

---

### 2026-10-09 - Fix applied (catalog rework)

**By:** Claude Code

**Actions:**
- The whole ScalarDB catalog is loaded once per Catalog (per database and credentials) and trusted for a TTL (60 s by default, injectable Clock), then reloaded; invalidate() drops it. Negative answers are no longer stored per name: a plain table is simply absent from the loaded snapshot. Name resolutions are cached in an LRU bounded at 4096, keyed by session schemas plus the resolved name, and cleared with the snapshot. A miss costs one engine query instead of two or three catalog queries, and the three-query miss path is gone.
- The design's "read once per driver instance" and "refresh on miss / TTL" wording and the per-database owner still need to be written into design.md (todo 027).
- Verified: 178 unit tests; the catalog integration test parameterised over PostgreSQL 16, MySQL 8.4, MariaDB 11.4, SQL Server 2022 and Oracle Free 23 with tables created and imported by ScalarDB 3.19.1 (45 cases); full ./gradlew check. The TTL and invalidate paths are exercised with a controllable clock on every engine.

## Notes

- The design already specifies refresh-on-miss, refresh-on-not-found and a TTL; this todo implements what the design says rather than adding behaviour.
- Decide the TTL as a driver property (see todo 017 for the property list).
