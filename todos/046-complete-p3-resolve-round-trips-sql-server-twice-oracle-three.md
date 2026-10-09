---
status: complete
priority: p3
issue_id: "046"
tags: [code-review, performance, engine]
dependencies: []
---

# `Engine.resolve` runs the SQL Server query twice and up to three Oracle queries on a miss

## Problem Statement

Resolving an unqualified name on SQL Server appends `dbo` to the session schemas, but the common session is already `["dbo"]`, so an unknown name runs the identical `sys.objects` query twice. On Oracle an unqualified name costs up to three queries: own schema, then the synonym, then the synonym's target. Each result is cached per TTL, so the cost is per distinct name per minute, but these are the longest holders of the catalog lock (todo 035).

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:292-300`: `candidates = new ArrayList<>(sessionSchemas); candidates.add("dbo")` → `["dbo", "dbo"]` when the session is `dbo`, which `CatalogIntegrationTest.java:254` asserts is the administrator's default. Two round trips, about 0.8 ms (performance-oracle).
- `Engine.java:336-363`: Oracle resolution runs `all_objects`, then `all_synonyms`, then `all_objects` again for the target: about 1.2 ms.
- Both run under `Catalog.lock` while it is held across I/O (todo 035).

## Proposed Solutions

### Option 1: One query per engine

**Approach:** SQL Server:
```sql
SELECT TOP 1 s.name, o.name FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
WHERE o.name = ? AND o.type IN ('U','V') AND s.name IN (?, 'dbo')
ORDER BY CASE WHEN s.name = ? THEN 0 ELSE 1 END
```
Oracle:
```sql
SELECT owner, object_name FROM (
  SELECT owner, object_name, 0 pri FROM all_objects
   WHERE owner = ? AND object_name = ? AND object_type IN ('TABLE','VIEW')
  UNION ALL
  SELECT o.owner, o.object_name, CASE s.owner WHEN 'PUBLIC' THEN 2 ELSE 1 END
    FROM all_synonyms s JOIN all_objects o ON o.owner = s.table_owner AND o.object_name = s.table_name
   WHERE s.synonym_name = ? AND s.owner IN (?, 'PUBLIC') AND o.object_type IN ('TABLE','VIEW'))
ORDER BY pri FETCH FIRST 1 ROW ONLY
```

**Pros:**
- One round trip per miss on both engines.

**Cons:**
- The Oracle form must be kept in step with whatever todo 040 decides about synonyms.

**Effort:** 1-2 hours plus the integration run

**Risk:** Low

---

### Option 2: Dedupe the candidate list only

**Approach:** `if (!candidates.contains("dbo")) candidates.add("dbo")` on SQL Server; leave Oracle as is.

**Pros:**
- Two lines.

**Cons:**
- Oracle stays at three round trips.

**Effort:** 10 minutes

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:283-305` - SQL Server branch
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:336-363` - Oracle branch

**Related components:**
- Todo 035 (lock across I/O), todo 040 (synonym policy).

**Database changes:** None.

## Resources

- Found by: performance-oracle (P3, measured against the containers).

## Acceptance Criteria

- [ ] An unqualified miss on SQL Server with session `["dbo"]` issues one query.
- [ ] An unqualified miss on Oracle issues one query.
- [ ] `CatalogIntegrationTest` resolution cases still pass on both engines.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- performance-oracle measured the duplicated SQL Server query and the Oracle triple and supplied the single-query forms above.

**Learnings:**
- The fallback schema must be deduplicated against the session list before looping.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- SQL Server resolves in one query (TOP 1 over the default schema and dbo, ordered by preference); Oracle in one query (the current schema's own object, then a private synonym, then a public one, through a UNION ALL ordered by priority with FETCH FIRST 1 ROW ONLY). Verified by the integration matrix on both engines, including the dbo shadowing case and the public synonym case.

## Notes

- Do after 040, so the Oracle query encodes the chosen synonym policy once.
