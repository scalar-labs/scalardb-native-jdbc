---
status: complete
priority: p1
issue_id: "004"
tags: [code-review, correctness, catalog, sql, architecture]
dependencies: []
---

# Identifier case folding and current-schema resolution are engine-blind, so a ScalarDB table can be classified PLAIN and accessed unrewritten

## Problem Statement

The driver lower-cases every unquoted identifier and compares it exactly against the catalog. That is PostgreSQL's rule only. On Oracle, MySQL, MariaDB and SQL Server the engine resolves a differently-cased or differently-quoted name to the same ScalarDB table, but the driver's catalog lookup misses, and a miss means PLAIN: the statement is forwarded verbatim and its writes bypass the protocol. The same thing happens on PostgreSQL when the table is reachable through a later `search_path` entry than the one `getSchema()` reports. Nothing in the design states the case rule or the current-schema rule.

## Findings

- `fold` / `resolve` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:717-746`): every unquoted identifier is lower-cased; quoted names are kept.
- `Catalog.lookup` (`driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:91-122`) compares with exact `=` on `full_table_name`.
- `TableInfo.java:151-160` and `StatementShape.java:329, 424` use exact `List.contains` on resolved column names.
- `Catalog.currentSchema` (`Catalog.java:197-207`) resolves unqualified names through `getSchema()` else `getCatalog()`; on PostgreSQL that is the first `search_path` entry only.
- Consequences, by code reading (not run against the other engines):
  - Oracle folds unquoted names to upper case, so ScalarDB table `ORDERS` addressed as `orders` resolves on the engine but misses the catalog.
  - MySQL on Linux preserves table-name case, so `Orders` is looked up as `orders`.
  - SQL Server with a case-insensitive collation and MySQL with `lower_case_table_names=1` resolve `"Orders"` to the same table as `orders`; the driver does not.
  - PostgreSQL with `search_path = app, public` and the table in `public`: `getSchema()` returns `app`, the lookup of `app.orders` misses.
  - On case-insensitive column engines a differently-cased non-key column escapes the `before_` substitution, so `P_before == P` and the second UNION arm misses committed rows under READ COMMITTED.
- `docs/design.md`'s per-engine table names the Oracle fold, but "The catalog" says nothing about case or about how unqualified names resolve.

## Proposed Solutions

### Option 1: A per-engine identifier rule injected into `sql` and `catalog`, and a catalog that compares the way the engine does

**Approach:** Add a small JDBC-free `Identifiers` value in `sql` (resolve, quote, sameName) chosen per sub-protocol and injected into `SqlAst`/`StatementShape` and `Catalog`. `Catalog` compares `LOWER(full_table_name) = ?` (or `UPPER`) where the engine is case-insensitive and refuses on more than one row; column names from the catalog are matched with the same rule. Document "unqualified names resolve in the connection's current schema only; otherwise qualify" and the case rule in design.md ("The catalog").

**Pros:**
- Correct on all five engines; puts the rule where the design already says engine variance lives.
- Keeps `sql` free of `java.sql`.

**Cons:**
- Touches three packages and the design.
- The MySQL `lower_case_table_names` setting is a server property; the engine strategy must read it (or treat MySQL as case-insensitive and refuse ambiguous rows).

**Effort:** 1-2 days

**Risk:** Medium

---

### Option 2: Never let a catalog miss on an ambiguous name mean PLAIN

**Approach:** For unqualified names, query the catalog by table name across all namespaces (case-insensitively); if any ScalarDB table of that name exists anywhere, refuse rather than default to PLAIN. Treat a miss as proof of a plain table only when the name is exact and unambiguous.

**Pros:**
- Closes the fail-open path even before the engine rules are complete.
- Small catalog change.

**Cons:**
- Refuses some legitimate plain-table statements (same table name in another namespace) until the user qualifies the name.
- Does not fix the column-name comparison for `before_` substitution.

**Effort:** Half a day

**Risk:** Low

---

### Option 3: Both: Option 2 now as the safety net, Option 1 as the real fix

**Approach:** Land the refuse-on-ambiguity rule first, then the per-engine identifier rule.

**Pros:**
- Immediate fail-closed behaviour, correct behaviour afterwards.

**Cons:**
- Two steps.

**Effort:** Half a day, then 1-2 days

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:717-746` - `fold`, `resolve`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:91-122, 197-207` - lookup comparison, current schema
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/TableInfo.java:151-160` - column membership
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:329, 424` - key and column matching
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcUrl.java` - sub-protocol is the engine selector
- `docs/design.md` - "The catalog", per-engine table

**Related components:**
- Todo 005 (where the current schema is resolved) and todo 024 (engine package home).
- The `before_` substitution of design.md 4.2.

**Database changes:** None.

## Resources

- `docs/design.md` section 1 (per-engine table: "unquoted names fold to upper case" on Oracle; "Where engine variance lives"), "The catalog".
- Found by: architecture-strategist (P1-1, P2-7), feature-dev:code-reviewer (P2-9, 14), security-sentinel (P2-2).

## Acceptance Criteria

- [ ] On Oracle, `select * from orders` and `select * from ORDERS` both resolve to ScalarDB table `ORDERS`.
- [ ] On SQL Server (CI collation) and MySQL with `lower_case_table_names=1`, `"Orders"` and `orders` resolve to the same catalog entry.
- [ ] On MySQL (Linux, case-sensitive), `Orders` is looked up as `Orders`.
- [ ] On PostgreSQL, an unqualified name reachable only through a later `search_path` entry is either resolved or refused, never PLAIN.
- [ ] Column-name matching for keys and `before_` substitution uses the engine's rule; a differently-cased non-key column is still substituted on case-insensitive engines.
- [ ] A catalog lookup that is ambiguous (several candidates) refuses with an actionable error.
- [ ] design.md states the case rule and the current-schema rule.
- [ ] Integration tests cover case-sensitive and case-insensitive names on at least PostgreSQL and one case-insensitive engine.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- architecture-strategist traced the PostgreSQL-only fold through `sql`, `catalog` and `StatementShape`.
- feature-dev:code-reviewer and security-sentinel independently derived the Oracle, MySQL, SQL Server and `search_path` scenarios.
- Confirmed by code reading; not yet reproduced on the non-PostgreSQL engines (only PostgreSQL is in the integration suite).

**Learnings:**
- A catalog miss is the fail-open path of the whole driver; identifier resolution must be at least as permissive as the engine's.
- The design's per-engine table already has the Oracle fold; the catalog section needs the same rule.

### 2026-10-09 - Live verification (PostgreSQL)

**By:** Claude Code

**Actions:**
- PostgreSQL 16 container; `ns.orders` created as a plain table and imported by ScalarDB 3.19.1 with transaction metadata decoupling; an empty schema `app`; the user connection runs `SET search_path TO app, ns`.
- The engine resolves `SELECT count(*) FROM orders` to `ns.orders`; `current_schema()` and pgjdbc `getSchema()` both return `app`.
- `Catalog.lookup(conn, null, "orders")` returns `PLAIN`; `Catalog.lookup(conn, "ns", "orders")` returns `DECOUPLED_IMPORTED`. So the same statement the engine runs against the ScalarDB table is classified as plain by the driver and would be forwarded unrewritten.
- The Oracle upper-case fold and the MySQL/SQL Server case rules were not executed (no container in the classpath); they follow from the engines' documented folding and the exact-match comparison in `Catalog.read`.

**Learnings:**
- The search_path case needs no unusual setup: it is the default for any application that uses a per-tenant or per-app schema ahead of the one holding the imported tables.

### 2026-10-09 - Fix applied (catalog rework)

**By:** Claude Code

**Actions:**
- New package com.scalar.db.nativejdbc.engine: Engine enumerates the five engines with the rules measured by the probe: folding of unquoted identifiers (lower on PostgreSQL, upper on Oracle, kept elsewhere), quoting, case-insensitive column comparison on MySQL, MariaDB and SQL Server, sessionSchemas(Connection) (current_schemas / database() / schema_name() / SYS_CONTEXT, never Connector/J's stale getCatalog), and resolve(): the engine's own catalog decides which stored table or view a name denotes (pg_class with the search path in order, information_schema.tables, sys.objects with the dbo fallback, all_objects then all_synonyms), so shadowing by a plain table and Oracle synonyms behave as the engine does.
- Catalog.lookup takes the raw names as written (quotes included) plus the session schemas, resolves through the engine, and classifies the stored spelling, which is also the spelling ScalarDB keeps. StatementShape takes an Engine and resolves and compares column names with it; NativeJdbcUrl derives its sub-protocols from Engine.
- Verified: 178 unit tests; the catalog integration test parameterised over PostgreSQL 16, MySQL 8.4, MariaDB 11.4, SQL Server 2022 and Oracle Free 23 with tables created and imported by ScalarDB 3.19.1 (45 cases); full ./gradlew check.
- Oracle: the imported APP.ORDERS is found by orders, Orders, ORDERS and "ORDERS" and not by "orders"; MySQL and MariaDB find Orders and not orders; SQL Server finds every spelling; PostgreSQL finds orders, Orders and "orders" and not "Orders"; a plain public.orders ahead of app in search_path shadows the ScalarDB table; a public synonym on Oracle leads to the table it stands for.

**Learnings:**
- Resolving the name through the engine removes the need for any Java-side table-name comparison rule: ScalarDB stores exactly what the engine stores.

### 2026-10-09 - Reopened in part by the second review

**By:** Claude Code

**Actions:**
- Two residuals found by the review of 75106c2: the catalog classifies by the exact spelling ScalarDB recorded, which differs from the engine's stored spelling on SQL Server and on MySQL/MariaDB with lower_case_table_names=1 (todo 032, verified live); and SqlAst still folds CTE names and column qualifiers with the PostgreSQL rule (todo 038). This item stays complete for what it covered; the two residuals have their own todos.

## Notes

- Related: todo 005 (catalog connection and current schema), todo 018 (unquoting escapes), todo 024 (engine package).
