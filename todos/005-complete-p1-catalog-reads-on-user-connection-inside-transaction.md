---
status: complete
priority: p1
issue_id: "005"
tags: [code-review, correctness, catalog, performance, architecture]
dependencies: []
---

# Catalog reads run on the user's connection inside the user's transaction, with a current-schema round trip before every cache check

## Problem Statement

`Catalog.lookup` takes the caller's `Connection` and runs its SELECTs on it, inside whatever transaction the caller has open. It also calls `currentSchema(connection)` before consulting its cache. This breaks the design's coarse SERIALIZABLE path (whose lock must be the transaction's first statement), aborts the user's transaction on PostgreSQL when the catalog schema is missing, pulls driver bookkeeping into the user's lock and snapshot scope, and on three engines costs a database round trip per statement even on a cache hit.

## Findings

- `Catalog.lookup` (`driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:91-99`) prepares its SELECTs on the caller's connection (`:109-195`, `:230`), and calls `currentSchema(connection)` at `:93` before the cache is consulted.
- **(a) Coarse path.** `docs/design.md` 4.2 requires `LOCK TABLE` (PostgreSQL, Oracle) or the locking read (MySQL, MariaDB, SQL Server) to be the transaction's first statement, because the first query fixes the snapshot. A cache miss during that first statement issues a catalog SELECT first, so the snapshot predates the lock and the hole the design closes reopens silently. By code reading.
- **(b) Aborted transactions.** On PostgreSQL any catalog SQL error aborts the user's transaction; a database with no `scalardb` schema fails every lookup this way, and failures are not cached. By code reading.
- **(c) Lock and snapshot scope.** Driver bookkeeping enters the user's SSI read set and SQL Server SNAPSHOT scope; at REPEATABLE READ on SQL Server, shared locks on `virtual_tables`/`metadata` rows are held until the user's transaction ends.
- **(d) Round trip per statement.** **Verified** via `javap`: pgjdbc 42.7.14 `getSchema()` executes `select current_schema()`, mssql-jdbc `SELECT SCHEMA_NAME()`; ojdbc queries `SYS_CONTEXT` unless cached; MySQL/MariaDB return the cached catalog. ORMs emit unqualified names, so on PostgreSQL, SQL Server and Oracle every statement pays about one key lookup's latency (100-500 µs local, 1 ms or more cross-AZ) even on a cache hit.

## Proposed Solutions

### Option 1: The catalog owns its reads on a short-lived autocommit connection; the proxy caches the current schema

**Approach:** `Catalog`'s constructor takes a connection supplier (vendor URL and properties from the driver) and reads in autocommit on a short-lived connection; `lookup(namespace, table)` loses the `Connection` parameter. The connection proxy resolves the current schema once per connection (refreshed on `setSchema`/`setCatalog` and on pass-through `SET search_path`/`USE`, all of which it sees) and passes it in; the cache is checked before the schema is resolved; `getSchema()` stays as the miss-time fallback.

**Pros:**
- Fixes (a), (b), (c) and (d) together.
- Matches the design ("read once per driver instance") and todo 009's per-database catalog owner.

**Cons:**
- One extra connection per catalog miss (rare, bounded by the cache).
- Requires the connection proxy, which does not exist yet; the signature change lands now, the schema cache with the proxy.

**Effort:** 1 day

**Risk:** Low

---

### Option 2: Load the catalog at connect time in autocommit

**Approach:** On `connect`, before handing the connection to the user, read `virtual_tables` and `metadata` once (two queries) and keep them per database with TTL refresh. Misses become free; the user's transaction never sees a catalog query.

**Pros:**
- No per-statement catalog traffic at all; simplest runtime path.

**Cons:**
- Connect cost grows with the catalog; refresh needs its own connection anyway (see todo 009).
- Tables created after connect are seen only after the TTL.

**Effort:** 1 day

**Risk:** Low

---

### Option 3: Savepoint around the lookup on the user's connection

**Approach:** Wrap each catalog query in a savepoint and treat "relation does not exist" as "no ScalarDB here".

**Pros:**
- Fixes (b) only, with a small change.

**Cons:**
- Leaves (a), (c) and (d) in place; savepoints are themselves statements that fix the snapshot.

**Effort:** 2 hours

**Risk:** Medium (does not address the coarse-path hole)

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:91-99, 109-195, 197-207, 230` - `lookup`, `read`, `currentSchema`, `columns`
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java:58-69` - where the catalog and the connection supplier will live
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java` - lookups no longer take a connection
- `docs/design.md` - "The catalog", 4.2 coarse path, 4.4 "Catalog cache"

**Related components:**
- Todo 004 (current-schema rule), todo 009 (cache owner, TTL, round trips per miss), the future connection proxy.

**Database changes:** None.

## Resources

- `docs/design.md` 4.2 (coarse SERIALIZABLE path: the lock "as the first statement"), 4.4 ("Catalog cache"), 1 ("The catalog").
- Found by: architecture-strategist (P1-2), feature-dev:code-reviewer (P2-8), performance-oracle (P1-4, P2-1 note).

## Acceptance Criteria

- [ ] `Catalog.lookup` no longer takes the user's `Connection`; catalog SQL never runs inside a user transaction.
- [ ] A missing `scalardb` schema does not abort the user's transaction; the lookup result (PLAIN or a refusal) is cached per todo 009.
- [ ] On a cache hit no database round trip happens (verified with a counting datasource or pgjdbc logging).
- [ ] The current schema is resolved at most once per connection and refreshed on `setSchema`/`setCatalog`.
- [ ] An integration test shows the coarse path's first statement is the lock, with a cold catalog cache.
- [ ] design.md records that catalog reads use a separate autocommit connection.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- architecture-strategist traced the coarse-path ordering requirement to the catalog read.
- feature-dev:code-reviewer found the PostgreSQL aborted-transaction case.
- performance-oracle verified via `javap` that `getSchema()` is a query on pgjdbc and mssql-jdbc and measured the per-statement cost.

**Learnings:**
- Driver bookkeeping must never share the user's transaction, both for correctness (snapshot timing) and for cost.
- The cache must be consulted before anything touches the database.

### 2026-10-09 - Live verification (PostgreSQL)

**By:** Claude Code

**Actions:**
- (a) In a user transaction that has already done an INSERT, a lookup through a `Catalog` whose metadata schema does not exist fails with `relation "nosuch.virtual_tables" does not exist`, and the user's next statement fails with `current transaction is aborted, commands ignored until end of transaction block`.
- (b) At REPEATABLE READ: connection A does a cache-miss lookup first, connection B then inserts and commits a row, A runs `LOCK TABLE ns.orders IN SHARE MODE` and reads: it does not see B's row (count=0). Connection D with a warm cache runs `LOCK TABLE` as its first statement and reads: it sees the row (count=1). The catalog read fixed A's snapshot before the lock, which is the hole design.md 4.2 closes by ordering the lock first.
- (c) With `log_statement=all`, ten cache-hit lookups of an unqualified name issued ten `current_schema()` queries and zero catalog queries: the round trip happens before the cache is consulted.

**Learnings:**
- All three effects reproduce on PostgreSQL with pgjdbc 42.7.14; the snapshot effect is the one that silently weakens SERIALIZABLE.

### 2026-10-09 - Fix applied (catalog rework)

**By:** Claude Code

**Actions:**
- The Catalog no longer takes the user's connection. It opens its own connection through a ConnectionSupplier (the driver will pass the vendor URL and properties), loads ScalarDB's two catalog tables whole in two queries, and runs the engine's name-resolution query there too; the session state the resolution depends on (search path, current database or schema) is captured by the caller with Engine.sessionSchemas on the user's connection outside a transaction and passed in. Nothing runs inside the user's transaction, so neither the snapshot-before-LOCK-TABLE hole nor the aborted-transaction effect can occur, and the current_schema() round trip per statement is gone.
- Still to wire when the connection proxy exists: call sessionSchemas at connect and after SET search_path / USE / ALTER SESSION SET CURRENT_SCHEMA / setSchema / setCatalog.
- Verified: 178 unit tests; the catalog integration test parameterised over PostgreSQL 16, MySQL 8.4, MariaDB 11.4, SQL Server 2022 and Oracle Free 23 with tables created and imported by ScalarDB 3.19.1 (45 cases); full ./gradlew check.

## Notes

- Related: todo 004, todo 009, todo 027 (doc update).
