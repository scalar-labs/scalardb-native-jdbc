---
status: pending
priority: p2
issue_id: "039"
tags: [code-review, architecture, design, engine]
dependencies: []
---

# The session-schema capture contract names triggers the parser cannot see, misses others, and is specified only in a Javadoc

## Problem Statement

`Catalog.lookup` correctly takes the session's schemas as an input and holds no session state, so the whole burden of keeping that list fresh falls on a connection layer that does not exist yet. The `Engine.sessionSchemas` Javadoc says when to re-read it, but the parser cannot detect three of the five triggers it names, the list is incomplete, and re-reading inside a transaction costs the coarse SERIALIZABLE path its first-statement slot. A stale list means a ScalarDB table resolved as plain, or the reverse, silently.

## Findings

- The contract: `Engine.java:197-210` says read on the user's connection, outside any transaction, at connect and after `SET search_path`, `USE`, `ALTER SESSION SET CURRENT_SCHEMA`, `setSchema`, `setCatalog`. `docs/design.md` at 75106c2 contains no occurrence of any of these.
- Undetectable triggers, probed on the built classes by agent-native-reviewer: `SqlAst.parse("SET search_path TO app, public")` is empty (does not parse), `SET SCHEMA 'app'` is empty, `USE app` is `Kind.OTHER`, `ALTER SESSION SET CURRENT_SCHEMA = app` is `Kind.DDL` (classified by package name, `SqlAst.java:306-312`). The PostgreSQL trigger, which a schema-per-tenant application sends on every request, is indistinguishable from unparseable text.
- Incomplete list: PostgreSQL `SET ROLE` and `SET SESSION AUTHORIZATION` (change `$user` in the path), `SET LOCAL ...` (reverts at commit), `RESET ALL` / `DISCARD ALL`; SQL Server `EXECUTE AS`, and `USE database`, which `sessionSchemas` cannot represent (it returns `schema_name()` only, `Engine.java:221-223`, while the catalog connection is pinned to the URL's database, so after `USE otherdb` names resolve in the wrong database); every passthrough statement; DDL mid-session (allowed in passthrough, design 4.1), which changes which plain table shadows which ScalarDB table while the cached resolution keeps the old answer for up to the TTL.
- "Outside any transaction" is not in the proxy's gift: after `BEGIN; SET search_path ...` the re-read is a `SELECT` that takes PostgreSQL's snapshot and steals the first-statement slot the coarse SERIALIZABLE path needs (design 4.2).
- Shadowing the other way: `current_schemas(false)` omits `pg_temp`, which PostgreSQL searches first, and MySQL `TEMPORARY` tables are absent from `information_schema`; a temp table named like a ScalarDB table makes the driver apply the protocol to the permanent table (a correctness problem, not a bypass).
- An empty session list now yields `PLAIN` with `namespace == ""` (`Catalog.java:193-198`; `Engine.resolve` returns null on an empty list at `:273-275`, `:320-322`, `:345-347`) where 06c137f threw `3F000` "qualify the table name"; the engine errors next, so the caller is not stuck, but `""` is a sentinel a rewrite could stringify.
- Cost: `sessionSchemas` is one round trip, measured 382-420 us on PostgreSQL (performance-oracle), so it must stay per connection and never per statement; the joined key string is rebuilt per lookup (`Catalog.java:216`).

## Proposed Solutions

### Option 1: Mark stale on anything not classified, re-read lazily

**Approach:** Add `Kind.SESSION` (`SetStatement`, `UseStatement`, `AlterSession`, `ResetStatement`) so the parseable triggers are named. The proxy marks the schemas stale after `setSchema`/`setCatalog`, after any statement whose kind is not SELECT or single-table DML (empty parse, OTHER, SESSION, DDL, passthrough), and at `commit`/`rollback` when a `SET` ran inside the transaction, and re-reads lazily before the next lookup. `catalog.invalidate()` after `Kind.DDL` / `TRUNCATE`. On SQL Server return `db_name()` alongside the schema and refuse when it differs from the catalog connection's database. Keep `3F000` for the unqualified-and-empty case (or make `TableInfo.namespace()` nullable). Document temporary tables named like ScalarDB tables as unsupported. Cache the joined key string per connection. Write the rule and the first-statement cost into design.md section 1 and the Javadoc.

**Pros:**
- Zero cost for a DML-only workload; one round trip after anything else; the cheapest rule that is also correct for every trigger, including the ones the parser cannot read.
- Lazy re-read sidesteps "outside any transaction": the proxy can read before `BEGIN` is sent (pgjdbc sends `BEGIN` with the first statement) or accept the one-slot cost and say so in the design.

**Cons:**
- Needs the proxy to exist; until then the contract is prose.
- `SET LOCAL` inside a transaction reverts at commit, so the re-read at commit is required, not optional.

**Effort:** 1 day once the proxy exists; the `Kind.SESSION` and SQL Server `db_name()` parts are an hour now

**Risk:** Medium

---

### Option 2: Refuse session-changing statements in non-passthrough mode

**Approach:** The proxy refuses `SET search_path`, `USE`, `ALTER SESSION SET CURRENT_SCHEMA`, `SET ROLE`, `EXECUTE AS` and `setSchema`/`setCatalog` unless passthrough is on, and re-reads the schemas when passthrough is turned off.

**Pros:**
- Simple and fail-closed; the list is never stale because it cannot change.

**Cons:**
- Breaks schema-per-tenant applications and pools that reset the schema on return (HikariCP goes through `setSchema`/`setCatalog`).
- Still needs detection of the statements it refuses, which is the same parser gap.

**Effort:** Half a day

**Risk:** Medium

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:197-248` - `sessionSchemas` contract and the SQL Server `db_name()` gap
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:273-275, 320-322, 345-347` - empty-list handling
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:85, 290-312` - `Kind` (add SESSION; `AlterSession` is currently DDL)
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:193-198, 216` - `plain()` with `""`, the per-lookup key string
- `docs/design.md` section 1 (Architecture, "The catalog") and 4.2 (first-statement rule) - the rule is undocumented

**Related components:**
- The connection proxy (next implementation step) owns the per-connection state.
- Todo 035 (lock), todo 040 (synonyms, which are also session-independent on Oracle but per-user on SQL Server).

**Database changes:** None.

## Resources

- `docs/design.md` section 1 ("The catalog"), 4.1 (passthrough), 4.2 (coarse SERIALIZABLE path, first statement).
- Found by: agent-native-reviewer (P2, with the parser probe), architecture-strategist (P2), security-sentinel (P3: SQL Server `USE`, `pg_temp`, TEMPORARY tables), performance-oracle (watch item: per-connection cost).

## Acceptance Criteria

- [ ] `SqlAst.kind()` returns `SESSION` for `SET`, `USE`, `ALTER SESSION` and `RESET` statements, and `ALTER SESSION` is no longer `DDL`.
- [ ] The proxy (when it exists) re-reads the session schemas after `setSchema`/`setCatalog`, after any statement not classified as SELECT or single-table DML, after passthrough, and at commit/rollback when a `SET` ran inside the transaction; a test changes `search_path` through an unparseable statement and the next lookup sees the new path.
- [ ] `catalog.invalidate()` runs after DDL and TRUNCATE.
- [ ] On SQL Server, `USE otherdb` followed by a lookup is refused or resolved in the new database, never in the old one.
- [ ] An unqualified name in a session with no schema fails with `3F000` or a nullable namespace, never `""`.
- [ ] design.md section 1 states the capture rule, its triggers, the first-statement cost inside a transaction, and that temporary tables named like ScalarDB tables are unsupported.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- agent-native-reviewer probed the built classes: `SET search_path` and `SET SCHEMA` do not parse, `USE` is OTHER, `ALTER SESSION` is DDL.
- architecture-strategist listed the missing triggers (`SET ROLE`, `SET LOCAL`, `RESET`, `EXECUTE AS`, passthrough, DDL) and the first-statement conflict with design 4.2.
- security-sentinel added SQL Server `USE database`, `pg_temp` and MySQL TEMPORARY tables by code reading; performance-oracle measured `sessionSchemas` at ~400 us on PostgreSQL.

**Learnings:**
- Because the parser cannot see the most common trigger, the only safe rule is "anything the driver did not classify marks the schemas stale".

## Notes

- The lazy re-read before the next lookup is both the cheapest and the simplest form; the design should state the one-slot cost it has inside a transaction rather than promise "outside any transaction".
