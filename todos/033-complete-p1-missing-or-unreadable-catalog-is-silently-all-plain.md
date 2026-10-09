---
status: complete
priority: p1
issue_id: "033"
tags: [code-review, correctness, catalog, engine, security]
dependencies: []
---

# A missing or unreadable ScalarDB catalog is silently "no ScalarDB tables here", so a mistyped schema or a missing grant forwards every statement unrewritten

## Problem Statement

When the `metadata` table cannot be read with the engine's "no such table" error, `Catalog.load` records `present=false` and every lookup answers PLAIN for the TTL, reloading identically. That is also exactly what a mistyped `scalardbnative.metadataSchema` produces on every engine, and on Oracle what a missing grant produces: ORA-00942 means "no privilege" as much as "no such table", and the application user normally has no grant on `"scalardb"."metadata"`. In the default configuration on Oracle, every ScalarDB table the application can write is forwarded verbatim, with no signal to the caller, the operator or a log. At commit 06c137f the same situation threw on every lookup; this commit made it silent, and the integration test encodes the silence as intended.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:311-316`: a "missing" `metadata` table sets `present=false`; `:175-177`: `lookup` returns `plain(...)` for every name while the snapshot says absent; `:285-290`: a missing `virtual_tables` is swallowed as "a ScalarDB too old for virtual tables", also without a log.
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:386-400`: `isMissingTable` matches PostgreSQL 42P01, MySQL/MariaDB 1049 and 1146, SQL Server 208 and Oracle 942, which is precisely the set a wrong schema name produces (`ScalarDB` on PostgreSQL, `SCALARDB` on Oracle).
- Verified live by the lead on Oracle Free 23: `appuser` with `SELECT, INSERT, UPDATE, DELETE` on `APP.ORDERS` (imported with decoupling) and no grant on `scalardb.*` gets `ORA-00942: table or view "scalardb"."metadata" does not exist (code 942)` on the catalog, `isMissingTable` is true, and `Catalog.lookup([APPUSER], "app", "orders")` through an `appuser` connection returns `PLAIN`; the user `scalardb` is visible to that account in `all_users`. security-sentinel reproduced the same (`OracleLiveProbe`): the same lookup through a privileged connection returns the ScalarDB layout.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java:299-303` (`aDatabaseWithoutScalarDb_hasOnlyPlainTables`) asserts the silent behaviour; the harness runs Oracle as `SYSTEM`, so the privilege case is never exercised.
- On the other engines a privilege error has a different code (PostgreSQL 42501, MySQL 1142/1044, SQL Server 229) and is thrown, which is the right fail-closed behaviour; only Oracle conflates the two.

## Proposed Solutions

### Option 1: Fail closed by default, and tell Oracle's two cases apart

**Approach:** A database with no ScalarDB catalog does not need this driver. Load the snapshot eagerly from the driver's `connect` and, when the catalog is absent, throw `SQLNonTransientConnectionException` with SQLSTATE 08004 naming `<metadataSchema>.metadata` and the `scalardbnative.metadataSchema` property, unless a property (`scalardbnative.catalog=absent`) says the database has no ScalarDB. On Oracle, on a 942, run `SELECT 1 FROM all_users WHERE username = ?` (visible to every user): if the schema user exists, the table is not visible for lack of a grant, so throw with a message naming the grant to give. Log one WARN per catalog for the `virtual_tables`-missing branch. Flip the integration test to the chosen behaviour and add the restricted-privilege Oracle case.

**Pros:**
- Closes the fail-open path on every engine, with an actionable message at connect rather than on the first statement.
- The Oracle check is one cheap query on the catalog connection, only on the failure path.

**Cons:**
- A deployment that really has no ScalarDB catalog must set the property.
- Depends on the driver's `connect` holding a catalog (todo 037).

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Keep the lenient behaviour but make it visible

**Approach:** Expose `Catalog.present()` so the driver can report it, emit one WARN per catalog on the `com.scalar.db.nativejdbc` logger naming the schema and the engine's error, and add the Oracle `all_users` check so a missing grant throws.

**Pros:**
- No new property.

**Cons:**
- A typo still disables the driver's purpose; the WARN is visible only to whoever reads logs.

**Effort:** Small

**Risk:** Medium

---

### Option 3: Oracle-only fix

**Approach:** Only the `all_users` check on 942; leave absence silent elsewhere.

**Pros:**
- Smallest change.

**Cons:**
- The mistyped-schema case stays silent on all five engines.

**Effort:** Small

**Risk:** High

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:175-177, 285-290, 311-316` - the absent branch
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:386-400` - `isMissingTable`, the Oracle `all_users` check
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java` - eager load at connect, 08004
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java:299-303` - flip the assertion, add the Oracle application-user case
- `docs/design.md` - the property and the connect-time refusal

**Related components:**
- Todo 037 (catalog at connect), todo 052 (a non-DBA application user in the Oracle harness), todo 016 (error contract), todo 026 (logging).

**Database changes:** None.

## Resources

- `docs/design.md` section 1 (URL and properties), section 3 (connect-time verification).
- Found by: feature-dev:code-reviewer (P1), agent-native-reviewer (P2), architecture-strategist (P3), security-sentinel (P2, live), code-simplicity-reviewer (note).
- Verification probe: `scratchpad/review2/verify/P1Verify.java` (Oracle), security-sentinel's `OracleLiveProbe`.

## Acceptance Criteria

- [ ] On Oracle, an application user with rights on a ScalarDB table but none on `scalardb.metadata` gets an error naming the missing grant, never a PLAIN classification.
- [ ] A mistyped `scalardbnative.metadataSchema` fails at connect with SQLSTATE 08004 naming the schema and the property.
- [ ] A database declared catalog-less by the property connects and treats every table as plain.
- [ ] The `virtual_tables`-missing branch logs one WARN per catalog.
- [ ] The integration matrix has an Oracle application-user case and the flipped no-catalog case.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer identified the ORA-00942 conflation by reading `isMissingTable`; agent-native-reviewer and architecture-strategist flagged the silent all-PLAIN for a mistyped schema on every engine; code-simplicity-reviewer noted that ScalarDB creates its catalog lazily, so absence is a real state that needs the per-engine codes.
- The lead reproduced the Oracle privilege case live on Oracle Free 23, and security-sentinel reproduced it independently.

**Learnings:**
- Oracle hides inaccessible objects behind the same error as nonexistent ones; `all_users` is the cheap, universally visible way to tell the cases apart.
- "No ScalarDB here" must be an explicit statement by the operator, not an inference from an error code.

### 2026-10-09 - Fix applied (error unless allowed, as decided)

**By:** Claude Code

**Actions:**
- Catalog.load distinguishes the cases when the metadata table is not visible: Engine.schemaExists (all_users on Oracle, pg_namespace, information_schema.schemata, sys.schemas) says whether the schema exists; if it does, the lookup fails with SQLSTATE 42501 and a message naming the SELECT grants on metadata and virtual_tables (or that ScalarDB has not created them yet); if it does not, the lookup fails with SQLSTATE 08004 naming the schema and the property, unless the catalog was constructed with allowAbsent=true, in which case every table is plain and one WARN is logged. A missing virtual_tables with a present metadata table (a ScalarDB too old for virtual tables) is logged at WARN. SLF4J logger com.scalar.db.nativejdbc.catalog.Catalog.
- Tests: aDatabaseWithoutScalarDb_isAnErrorUnlessAllowed on every configuration (08004, then PLAIN with allowAbsent); aUserWhoCannotSeeTheCatalog_getsAnError_notPlainTables on Oracle with a user granted the application table but nothing on scalardb.* (42501, "lacks SELECT").
- Still to wire: the driver property for allowAbsent (todo 017's list) and the design's statement of the contract (027).

## Notes

- Reverses the lenient choice made in the catalog rework (todo 009's work log); the integration test for that choice must change with it.
