---
status: complete
priority: p2
issue_id: "040"
tags: [code-review, correctness, engine]
dependencies: []
---

# A synonym the engine resolves to a ScalarDB table resolves to nothing in the driver, so the statement is forwarded as plain

## Problem Statement

`Engine.resolve` follows Oracle synonyms one level and only for unqualified names, and never looks at SQL Server synonyms at all. When the engine resolves a synonym to a ScalarDB table and the driver gets `null`, the lookup falls to `PLAIN` and the statement is forwarded verbatim: a write through a synonym bypasses the protocol. The design's rule is "never guess"; an object that exists on the engine but that the driver cannot resolve should be refused, not passed through.

## Findings

- SQL Server: `Engine.java:283-301` filters `o.type IN ('U', 'V')`, so a synonym (`sys.objects.type = 'SN'`) over a ScalarDB table returns `null`. By code reading.
- Oracle: a qualified name never consults `all_synonyms` (`Engine.java:342-344`), and the unqualified path follows exactly one level (`:353-362`); a synonym whose target is another synonym, or anything but a table or view, returns `null`.
- Verified live by security-sentinel on Oracle Free 23, as an application user with `s1 -> orders` and `s2 -> s1`:
  - `lookup([APPUSER], null, "s1")` -> `CLASSIC` (works, one level).
  - `lookup([APPUSER], null, "s2")` -> `PLAIN` although `SELECT count(*) FROM s2` succeeds on the engine.
  - `lookup([SYSTEM], "appuser", "s1")` -> `PLAIN` (qualified name, synonyms not consulted).
- The committed design says Oracle synonyms resolve, and `CatalogIntegrationTest.java:274-278` asserts the public-synonym case; the uncommitted section 4.7 draft in the working tree says synonyms over a ScalarDB table pass through as plain. The two must be reconciled whichever lands.
- `Engine`'s Javadoc claims synonym resolution without stating the one-level, unqualified-only limit.

## Proposed Solutions

### Option 1: Follow synonyms on both engines, bounded

**Approach:** SQL Server: include `'SN'` in the type filter and, for a synonym, read `sys.synonyms.base_object_name`, parse it into schema and object, and look that up (one more query). Oracle: consult `all_synonyms` for the qualified path too, and loop over the chain with a small bound (for example four hops), resolving the final target in `all_objects`; if the bound is hit or the target is not a table or view, throw rather than return `null`.

**Pros:**
- Matches what the engine does, so an application that already reaches its tables through synonyms works unchanged.
- Keeps the existing integration assertion for the Oracle public synonym.

**Cons:**
- `base_object_name` is a three-part name in SQL Server's own quoting and may point at another database or server, which must be refused.
- Up to four extra round trips per miss on Oracle (cached per TTL, but under the lock of todo 035).

**Effort:** Half a day plus an SQL Server and an Oracle integration case each

**Risk:** Medium

---

### Option 2: Refuse synonyms with SQLSTATE 42000

**Approach:** Wherever the resolved object is a synonym (SQL Server type `'SN'`; Oracle `all_objects.object_type = 'SYNONYM'`, consulted on both paths), throw `SQLException` "synonyms are not supported; name the table itself" with SQLSTATE `42000`, like the other three refusals. Drop the one-level follow for consistency, and update the integration test and the design accordingly.

**Pros:**
- A few lines; fail-closed on every engine; no chain or cross-database questions.
- Consistent with the uncommitted 4.7 draft's position, so reconciling the design is one sentence.

**Cons:**
- Applications that use synonyms must rewrite their SQL to name the table.
- Reverses the behaviour the integration test currently asserts.

**Effort:** 1-2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:283-301` - SQL Server resolution (type filter, `sys.synonyms`)
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:336-363` - Oracle resolution (qualified path, one-level follow)
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:16-30, 250-262` - Javadoc claims
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/catalog/CatalogIntegrationTest.java:274-278` - the public-synonym assertion
- `docs/design.md` - "The catalog" and the per-engine table; the uncommitted 4.7 draft

**Related components:**
- Todo 035 (extra round trips run under the lock), todo 039 (session state), todo 046 (one-query resolution forms, which would absorb the synonym join on Oracle).

**Database changes:** None.

## Resources

- `docs/design.md` section 1 ("The catalog", "never guess"), section 7.
- Found by: security-sentinel (P2, verified live on Oracle), architecture-strategist (P2), feature-dev:code-reviewer (P3).

## Acceptance Criteria

- [ ] On SQL Server, `CREATE SYNONYM ord FOR app.Orders` followed by a lookup of `ord` yields the ScalarDB layout (Option 1) or a `42000` refusal (Option 2), never `PLAIN`.
- [ ] On Oracle, a private synonym addressed as `owner.syn`, a public synonym, and a two-hop chain each yield the ScalarDB layout (Option 1) or `42000` (Option 2), never `PLAIN`.
- [ ] A synonym to a cross-database or non-table target is refused.
- [ ] The `Engine` Javadoc states exactly what is followed; the integration test and design.md agree with the code.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- security-sentinel created `s1 -> orders` and `s2 -> s1` on Oracle Free 23 as an application user and ran the three lookups above; `s2` and the qualified `appuser.s1` come back `PLAIN` while the engine reads them.
- architecture-strategist read the SQL Server type filter and the design's "never guess" rule; feature-dev:code-reviewer noted the Javadoc overstates the support.

**Learnings:**
- `null` from `Engine.resolve` means two different things, "no such object" and "an object I cannot resolve"; only the first may become `PLAIN`.

### 2026-10-09 - Decision and fix: refuse synonyms

**By:** Claude Code

**Actions:**
- The maintainer chose to refuse rather than follow synonyms. Engine.resolve reports a synonym as one instead of following it (Engine.QualifiedName.isSynonym): on SQL Server sys.objects type SN is included; on Oracle the own object may be a TABLE, VIEW or SYNONYM, then a public synonym, in one query, qualified or not. Catalog.Snapshot.classify refuses a synonym with SQLSTATE 42000 "is a synonym; name the table it stands for instead", whatever it points at, so chains and synonyms to plain tables are refused too.
- Tests: CatalogTest.aSynonym_isRefused_whateverItStandsFor; on Oracle a public synonym, a private one unqualified and a private one qualified are refused; on SQL Server a synonym qualified and unqualified is refused.
- Design: the committed design says Oracle synonyms resolve and the uncommitted section 4.7 draft says synonyms pass through as plain; both must now say "refused" (todo 027).

## Notes

- Whichever option is chosen, make `resolve` distinguish "not found" from "found but unresolvable" so the catalog can refuse the latter.
