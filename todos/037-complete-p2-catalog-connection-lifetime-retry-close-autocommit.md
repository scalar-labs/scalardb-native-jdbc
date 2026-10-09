---
status: complete
priority: p2
issue_id: "037"
tags: [code-review, reliability, catalog]
dependencies: []
---

# The catalog's own connection has no retry, no close, no enforced autocommit and no timeouts, and its failures reach the user raw

## Problem Statement

`Catalog` opens one vendor connection on first use and keeps it for its lifetime. When that connection goes stale, which is the normal case after an idle reap, the first user statement that needs a catalog round trip fails with the catalog's exception and the next one recovers. Nothing ever closes the connection, nothing puts it in autocommit although the supplier's contract says so, and nothing bounds how long one of its queries may hang. The failure surfaces on the first statement rather than at connect, with the vendor's own message about a table the statement never named.

## Findings

- `Catalog.java:99-101`: `connection` is a field that lives as long as the `Catalog`; `:235-252` (`withConnection`) closes it and rethrows the vendor `SQLException` on any failure, with no retry. A connection killed by MySQL `wait_timeout`, a firewall idle timer, a load balancer or a failover therefore fails exactly one user statement per drop. With `DEFAULT_TTL` of 60 s, the catalog connection sits idle for up to 60 s between uses in steady state (every lookup is a cache hit), so idle drops are the expected case, not the exception.
- No `close()`: `Catalog` is not `AutoCloseable` (class ends at `:486`). The integration test creates one `Catalog` per test method (`CatalogIntegrationTest.java:104-107`) and never closes any, about 12 open sessions per engine for that class alone; the driver's future per-URL map would leak one per catalog across `deregisterDriver` and redeploys.
- `ConnectionSupplier.open()` is documented "in autocommit mode" (`:81-91`) but nothing sets or checks it. A `DataSource`-backed supplier with `autoCommit=false` on MySQL/MariaDB at their default REPEATABLE READ freezes the first read's snapshot, so every TTL reload and `invalidate()` returns the same catalog forever. (Connector/J forces `autocommit=1` even under a server-side `autocommit=0`, verified live by security-sentinel; pgjdbc has no server-side equivalent, so the risk is a supplier choice, not a server one.)
- No query timeout and no network timeout on the catalog connection, so a hung `open()` or query blocks every lookup in the JVM behind the lock of todo 035.
- Attribution: the exception arrives on the first real statement in the first transaction, not at connect, so pool warm-up and health checks pass; it carries the vendor's SQLSTATE for a connection the application never opened and a message about `scalardb.metadata`. On MySQL/MariaDB (1142/1044) and Oracle (ORA-01031) a privilege failure reports `42000`, the same state as the three refusals in `Snapshot.classify` (`:375-377`, `:396-398`, `:413-417`), all plain `SQLException`, so an automated caller cannot tell "grant SELECT on the catalog" from "name the table itself".
- Verified by code reading (five reviewers); the autocommit behaviour of Connector/J verified live.

## Proposed Solutions

### Option 1: Harden `withConnection` in place

**Approach:** On `open()`, call `setAutoCommit(true)` and `setReadOnly(true)` and set a network timeout and a per-statement query timeout. When a reused connection fails with a connection-class SQLSTATE (`08xxx`), drop it, open a fresh one and retry the query once before propagating. Rethrow any other failure wrapped with a prefix ("ScalarDB catalog on the driver's own connection: ...") and the vendor exception as cause, keeping its SQLSTATE. Make `Catalog` implement `AutoCloseable`, closing the connection; close catalogs in the tests' `@AfterAll`.

**Pros:**
- About thirty lines, all inside the one method that owns the connection.
- Removes the one-statement-per-drop failure and the leak without touching the driver.

**Cons:**
- Does not move the failure to connect time; the first statement still pays for a dead catalog.
- A retry inside the lock of todo 035 lengthens the stall; do it together with 035.

**Effort:** 2-3 hours

**Risk:** Low

---

### Option 2: Option 1 plus an eager load at connect and a per-URL map in the driver

**Approach:** The driver holds a `ConcurrentHashMap<Key, Catalog>` keyed by (engine, vendor URL, user, metadata schema), never by password; the supplier captures the connect `Properties` with the `scalardbnative.*` keys stripped (todo 017). At `connect`, the driver loads the snapshot once (cached by the TTL anyway) and throws `SQLNonTransientConnectionException` with SQLSTATE `08004` naming `<metadataSchema>.virtual_tables` / `.metadata` and the `scalardbnative.metadataSchema` property when it cannot. Catalogs are evicted and closed on `deregisterDriver`.

**Pros:**
- Failures are reported where a pool reports them, at connect, with a state the caller can act on.
- Settles the catalog's owner, which todo 009 left to the driver, and the credential question: `all_objects` / `sys.objects` visibility is per user, so a catalog cannot be shared across users.

**Cons:**
- Touches `NativeJdbcDriver`, which has no proxy yet; the map outlives any single connection, so its lifecycle needs a test.
- One extra connection per (URL, user) for the life of the driver.

**Effort:** Half a day on top of Option 1

**Risk:** Medium

---

### Option 3: Open a connection per use instead of caching one

**Approach:** Drop the cached connection; `withConnection` opens, uses and closes a connection for each load and each resolution miss.

**Pros:**
- No stale connection, no leak, no `close()` needed, no autocommit question.

**Cons:**
- A connect per miss costs milliseconds; a warm-up with a few hundred distinct names opens a few hundred connections, and pools count none of them.
- Still needs the timeouts and the attribution.

**Effort:** 1 hour

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:81-91` - `ConnectionSupplier` contract
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:99-101, 235-252` - the cached connection and `withConnection`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:375-377, 396-398, 413-417` - refusal SQLSTATEs that a privilege failure collides with
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java` - the future per-URL map and the eager load (Option 2)
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java:104-107` - one unclosed `Catalog` per test method

**Related components:**
- Todo 035 (lock held across I/O: the retry must happen outside it).
- Todo 033 (what a missing or unreadable catalog means).
- Todo 016 (error contract) and 017 (driver properties stripped before the vendor sees them).

**Database changes:** None.

## Resources

- `docs/design.md` section 1 ("The catalog"), section 7 (error contract: `SQLTransactionRollbackException` / `40001` for aborts).
- Found by: feature-dev:code-reviewer (P2), agent-native-reviewer (two P2s), architecture-strategist (P2), performance-oracle (P2), security-sentinel (P3).

## Acceptance Criteria

- [ ] Killing the catalog's session on the server between two lookups (for example `KILL` on MySQL, `pg_terminate_backend` on PostgreSQL) does not fail the next user lookup.
- [ ] The catalog connection is in autocommit and read-only after `open()`, whatever the supplier returned.
- [ ] A query that hangs on the catalog connection fails within the configured timeout instead of blocking every lookup.
- [ ] `Catalog` is `AutoCloseable`; closing it closes its connection; the integration tests close every catalog they create and leave no extra session on the engine.
- [ ] A catalog failure is reported with a message that names the catalog tables and the metadata-schema property and carries the vendor exception as cause; a privilege failure is distinguishable from a refusal by SQLSTATE or exception type on every engine.
- [ ] If Option 2 is chosen: a missing or unreadable catalog fails `connect` with `08004`; the driver holds one catalog per (engine, URL, user, metadata schema) and closes them on `deregisterDriver`.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer traced `withConnection`'s drop-and-rethrow and the missing `close()`.
- agent-native-reviewer reported the late, unattributed failure and the SQLSTATE collision with the refusals on MySQL/MariaDB and Oracle; architecture-strategist and performance-oracle added the idle-timeout scenario, the autocommit gap and the per-user key for the driver's map.
- security-sentinel verified live that Connector/J forces autocommit even under a server-side `autocommit=0`, so the autocommit risk is on the supplier side.

**Learnings:**
- With a 60 s TTL and an all-hit steady state, the catalog connection is idle for about a minute at a time, which is longer than many idle reapers allow; a retry is not optional.

### 2026-10-09 - Partly resolved with 035

**By:** Claude Code

**Actions:**
- Done in the 035 fix: retry once on a fresh connection after a class-08 failure of a reused one; setAutoCommit(true) after open; Catalog implements AutoCloseable and the integration tests close their catalogs. Still open: query and network timeouts on the catalog connection, eager load at connect with an 08004 there, attributed rethrow of catalog failures, and the driver's per-(engine, URL, user, schema) catalog map with eviction on deregister.

### 2026-10-09 - Fix applied (plan milestone 0)

**By:** Claude Code

**Actions:**
- The driver keeps one Catalog per (vendor URL, user, metadata schema, TTL, allowNoCatalog), never keyed by password, whose supplier opens connections with the vendor driver and the stripped properties. `connect` verifies the catalog first (`Catalog.verify()`), so a database without it fails with 08004 and an unreadable one with 42501 before any statement. The catalog connection gets a 30 s network timeout (daemon executor); retry once, autocommit and close() came with 035.
- Tests: DriverIntegrationTest (connect checks the catalog; nosuch is 08004 unless allowNoCatalog, also via the URL; connections to one database as one user share one catalog).

## Notes

- Do the retry together with todo 035 so that it runs outside the lock.
- The per-user key matters on Oracle and SQL Server: `all_objects` and `sys.objects` show only what the connected user can see.
