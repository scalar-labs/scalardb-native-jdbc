---
status: complete
priority: p2
issue_id: "318"
tags: [read-views, rewrite, refusal]
dependencies: []
---

# A table named in a query and in its subquery is refused on the read views

## Problem Statement

`SELECT CASE WHEN c > (SELECT avg(c) FROM t1) THEN a ELSE b END, (SELECT count(*) FROM t1 AS x
WHERE x.b < t1.b) FROM t1` was refused with 0AN01 "the statement cannot be rewritten onto the read
views". Both unaliased `t1` were to get the alias `t1` on `rc_t1`, which the rewrite took for two
tables sharing one alias.

## Findings

- T4 sqllogictest on PostgreSQL, 1000 queries per file: 243 of the 1700 refusals, all in
  select1-3. Since todo 312 these statements are read through the read views, not split.
- Two tables of one name share an alias only in one FROM (`FROM s1.t, s2.t`). In nested queries
  the inner alias hides the outer one, as the inner name did.

## Proposed Solutions

### Option 1: compare the aliases given per query (chosen)
The walk records, for each table node, the query whose FROM holds it; a conflict is two of one
name in one query. **Effort:** Small.

### Option 2: resolve unqualified columns from the catalog and split again
**Effort:** Medium; not needed once these read through the views.

## Acceptance Criteria

- [x] The nested form rewrites; `FROM s1.t, s2.t` with both renamed is still refused
  (SqlAstTest).
- [x] sqllogictest's select1-3 refusals for it are gone.

## Work Log

### 2026-10-22 - Found by T4 sqllogictest and fixed

**By:** Claude Code

- `SqlAst.Walker` records each table's query; `planRewrite` keeps the aliases given per query.

## Resources

- docs/plans/testing.md, milestone T4; todo 312.
