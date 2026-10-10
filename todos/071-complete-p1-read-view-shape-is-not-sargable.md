---
status: complete
priority: p1
issue_id: "071"
tags: [code-review, performance, design, decision]
dependencies: []
---

# Read view shape is not sargable

## Problem Statement

The `rc_T` view's CASE columns make any predicate or join on a non-key column a full scan and hash join of both tables; milestone 3 routes joins and subqueries through `rc_T`, so typical ORM queries would be slow. Needs a design decision before milestone 3.

## Findings

- Measured on PostgreSQL 18 (5M rows, 500 in flight, performance review): `WHERE x = 42` through `rc_T` 2262 ms (parallel hash left join, temp spill); `count(*)` 1739 ms; key lookups and key ranges fine (0.09 ms, 1.1 ms per 1001 rows).
- The two-arm form of design 4.2 as the view body: `x = 42` 0.40 ms, `count(*)` 348 ms, an FK join 309 ms instead of 2196 ms. PostgreSQL, SQL Server and Oracle push filters and join predicates into UNION ALL branches.
- MySQL materialises a UNION view: constant filters reach it only from 8.0.29 (derived-condition pushdown), join predicates never. MariaDB similar.

## Proposed Solutions

### Option 1: UNION ALL view on PostgreSQL, SQL Server, Oracle; inline arms on MySQL/MariaDB

**Approach:** `rc_T` = committed rows with no in-flight metadata (anti-join on the in-flight index) UNION ALL the before images of in-flight rows. On MySQL/MariaDB, milestone 3 emits the arms inline instead of naming the view.

**Pros:** Index use on every predicate.

**Cons:** The view's role differs by engine; M3 needs the inline form for two engines.

**Effort:** Medium

**Risk:** Medium

### Option 2: Keep the LEFT JOIN view, accept the cost

**Approach:** Document that non-key predicates through `rc_T` scan.

**Pros:** No change.

**Cons:** Slow joins for every ORM.

**Effort:** Small

**Risk:** High

## Recommended Action

Option 1 (user, 2026-10-10).

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] Design section 3 and 4.2 updated with the chosen shape per engine.
- [ ] A benchmark of a non-key predicate and an FK join through the read surface on each engine.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Measured: PostgreSQL 16, 2M rows: non-key filter 515 ms through the LEFT JOIN view against 1.3 ms through the UNION ALL form (same rows), plan = parallel hash left join over two seq scans; key lookup 0.3-0.4 ms either way. MySQL 8.4, 500k rows: non-key filter 403 ms against 1.2 ms (derived-condition pushdown works for constants), but a join on the non-key column 416 ms (LEFT JOIN view) and 645 ms (UNION ALL view): joins need the arms inline on MySQL.

- Resolved (2026-10-10): `rc_T` is now the two arms of 4.2 as a UNION ALL on every engine; milestone 3 writes the arms inline on MySQL and MariaDB for joins and subqueries (plan updated). Test: on PostgreSQL a non-key filter through `rc_T` uses the table's index (`todo071_...`); the per-engine join benchmark is left to milestone 3, where the inline form exists.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
