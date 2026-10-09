---
status: complete
priority: p1
issue_id: "032"
tags: [code-review, correctness, catalog, engine]
dependencies: []
---

# The name ScalarDB recorded can differ in case from the engine's stored spelling, so a ScalarDB table is classified PLAIN on SQL Server and on MySQL/MariaDB with lower_case_table_names=1

## Problem Statement

`Catalog` resolves a user's name through the engine and gets the spelling the engine stores. It then looks that spelling up, exactly, in the rows ScalarDB wrote into `virtual_tables` and `metadata`. Those rows carry the spelling the operator gave `importTable` or `createTable`, which the engine accepted because it compares table names without regard to case or normalises them on storage. Where the two spellings differ, the lookup misses every map and the table is PLAIN: the statement is forwarded verbatim and its writes bypass the protocol. This is the fail-open class todo 004 was opened for; 004 is reopened by this item.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:363-367`: `Snapshot` keys `byName`, `byLeft`, `byRight` and `metadata` by the exact `full_table_name` ScalarDB recorded.
- `Catalog.java:371-372, 393, 412, 419`: `classify` builds `full = q.schema() + "." + q.table()` from the engine's stored spelling and does exact `HashMap.get`/`containsKey`.
- Verified live by the lead on SQL Server 2022 (collation `SQL_Latin1_General_CP1_CI_AS`): `CREATE TABLE app.Orders` stores `Orders`; ScalarDB accepted `importTable("app", "orders")` and recorded `app.orders` and `app.orders_tx_metadata`; `Catalog.lookup(List.of(), "app", "Orders")` resolved the stored `app.Orders`, missed every map, and returned `PLAIN`.
- Verified live by security-sentinel on `mysql:8.4 --lower-case-table-names=1` (the Windows default, common on RDS/Aurora and in portable Docker images): `CREATE TABLE app.Orders` is stored as `orders`, `information_schema` returns `app.orders`, ScalarDB's metadata rows say `app.Orders` with the ten transaction columns; `Catalog.lookup([app], null, "Orders")`, `"orders"` and `` "`Orders`" `` all return `app.orders [PLAIN] keys=[]`.
- The integration matrix runs case-sensitive defaults (`lower_case_table_names=0`, `CatalogIntegrationTest.java:140-146` asserts MySQL keeps case) and imports with `e.stored(...)`, so the recorded spelling always equals the stored one and neither case is exercised.
- The `Engine` enum's static `columnsCaseInsensitive` has the same shape problem on a SQL Server database with a case-sensitive collation; rare, and a documented limit is enough for now.

## Proposed Solutions

### Option 1: A per-database table key read once per load, applied to the snapshot maps and the classify lookup

**Approach:** In `Catalog.load`, on the catalog connection, read the setting that decides how the engine compares table names: `SELECT @@lower_case_table_names` on MySQL/MariaDB (non-zero means lower-case), `DATABASEPROPERTYEX(DB_NAME(), 'Collation')` on SQL Server (contains `_CI` means lower-case), exact on PostgreSQL and Oracle. Store a `tableKey` function in `Snapshot`, key the four maps by `tableKey(full_table_name)` and look up `tableKey(full)` in `classify`. Keep the setting in the `Snapshot`, not in the `Engine` enum: it is a property of the database, not of the engine.

**Pros:**
- Exactly the engine's own comparison rule, measured per database rather than assumed.
- One extra `SELECT` per load; no change to the hot path.
- The derived table names (`orders_tx_metadata`, `orders_scalardb`) keep ScalarDB's spelling, which is what the engine stores for the objects ScalarDB created.

**Cons:**
- Touches `load`, `Snapshot` and the unit test fixture.
- A SQL Server database with a case-sensitive collation behaves exactly, which is right, but the enum's column rule still says case-insensitive.

**Effort:** Half a day plus the two integration cases

**Risk:** Low

---

### Option 2: Compare case-insensitively everywhere and refuse on ambiguity

**Approach:** Key the snapshot maps by the lower-cased name on every engine; when two recorded names collide under lower-casing, refuse the lookup with SQLSTATE 42000.

**Pros:**
- No per-database query.

**Cons:**
- Wrong on PostgreSQL and Oracle, where `Orders` and `orders` are two tables; a refusal on a legitimate pair of tables is a regression.

**Effort:** Small

**Risk:** Medium

---

### Option 3: Normalise at import time instead

**Approach:** Document that ScalarDB must be given the stored spelling at import, and have the setup tool check it.

**Pros:**
- No driver change.

**Cons:**
- ScalarDB accepts the other spelling today and existing deployments already have it recorded; the driver would stay fail-open for them.

**Effort:** Small

**Risk:** High

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:260-330` - `load`, the extra setting query
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:347-442` - `Snapshot` constructor and `classify`
- `driver/src/test/java/com/scalar/db/nativejdbc/catalog/CatalogTest.java` - snapshot fixture with a table key
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/testing/Engines.java` - a MySQL container with `--lower-case-table-names=1`
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java` - the two new cases

**Related components:**
- Todo 004 (reopened), todo 045 (keep per-database settings out of the enum), todo 035 (the load path).

**Database changes:** None.

## Resources

- `docs/design.md` section 1, "The catalog" and the per-engine table.
- Found by: feature-dev:code-reviewer (P1), architecture-strategist (P1), security-sentinel (P2, live).
- Verification probe: `scratchpad/review2/verify/P1Verify.java` (SQL Server), security-sentinel's `MySqlLiveProbe`.

## Acceptance Criteria

- [ ] On SQL Server, a table stored as `Orders` and imported as `app.orders` is classified `DECOUPLED_IMPORTED` for `Orders`, `orders` and `[ORDERS]`.
- [ ] On MySQL with `lower_case_table_names=1`, a table created as `app.Orders` and recorded as `app.Orders` is classified `DECOUPLED_IMPORTED` for `Orders`, `orders` and `` `Orders` ``.
- [ ] On PostgreSQL and Oracle, `Orders` and `orders` remain two different lookups.
- [ ] `CatalogTest` covers a snapshot with a lower-casing table key.
- [ ] The integration matrix includes a `lower_case_table_names=1` MySQL container and a SQL Server import with a different case than stored.
- [ ] `./gradlew check` green on all five engines.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer and architecture-strategist derived the mismatch from `Snapshot.classify`'s exact lookup by code reading.
- security-sentinel reproduced it live on `mysql:8.4 --lower-case-table-names=1`.
- The lead reproduced the SQL Server case live: ScalarDB accepted `importTable("app", "orders")` on a table stored as `Orders`, recorded `app.orders`, and the lookup of `app.Orders` returned `PLAIN`.

**Learnings:**
- "The stored spelling is also the spelling ScalarDB keeps" holds only where the engine compares table names exactly; ScalarDB validates the import with the engine's own comparison, so it records whatever spelling it was given.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- Engine.tableNamesCaseInsensitive(Connection) reads the per-database setting once per load (@@lower_case_table_names on MySQL and MariaDB, the database collation on SQL Server; never on PostgreSQL and Oracle); Catalog.Snapshot keys its four maps and the classify lookup by the lower-cased full name where it is true. TableInfo keeps the engine's spelling for the user's table and ScalarDB's recorded spelling for the derived tables.
- Tests: CatalogTest.whereTheDatabaseIgnoresCase_theRecordedSpellingIsMatchedThatWay (no database); the integration fixture imports a table as MIXED_case where the engine ignores case and aNameRecordedInAnotherCaseThanStored_isStillTheScalarDbTable asserts it on every configuration; a new MYSQL_LOWER_CASE configuration (mysql:8.4 with --lower-case-table-names=1) joins the matrix with its own expectations in the folding and resolution tests.
- Verified: integration matrix green on PostgreSQL, MySQL, MySQL lower-case, MariaDB, SQL Server and Oracle.

## Notes

- The fix for columns (todo 004's `columnsCaseInsensitive`) stays in the enum; the table rule moves to the per-database snapshot because it depends on a server setting.
