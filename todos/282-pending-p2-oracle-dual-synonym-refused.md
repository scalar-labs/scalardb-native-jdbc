---
status: pending
priority: p2
issue_id: "282"
tags: [oracle, usability, catalog]
dependencies: []
---

# SELECT ... FROM dual is refused on Oracle: DUAL is a public synonym

## Problem Statement

The catalog refuses every synonym (Catalog.java:810-813, "PUBLIC.DUAL is a synonym; name the table it
stands for instead"), since a synonym could stand for a ScalarDB table the driver would then not
rewrite. DUAL is a public synonym for SYS.DUAL, so `SELECT 1 FROM dual`, `SELECT seq.NEXTVAL FROM
dual`, `SELECT SYSDATE FROM dual` and the validation query of connection pools and of Hibernate's
Oracle dialect all fail through the driver; only `FROM sys.dual` works. Predates 8d (reads on Oracle).

## Findings

- Seen while debugging 8d2: a test query `SELECT ... FROM dual` through the driver failed with 42000.

## Proposed Solutions

1. Resolve a synonym to its target (all_synonyms.table_owner, table_name) and refuse it only when
   the target is a ScalarDB table, its data, metadata or view table (medium; the general rule).
2. Treat PUBLIC.DUAL as SYS.DUAL, a plain table (small; the common case).

## Acceptance Criteria

- [ ] `SELECT 1 FROM dual` runs through the driver on Oracle; a synonym of a ScalarDB table is still
      refused
- [ ] Full check passes

## Work Log

- 2026-10-19: found while building 8d2.
