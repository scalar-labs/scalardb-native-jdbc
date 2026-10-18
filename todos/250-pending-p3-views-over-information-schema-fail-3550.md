---
status: pending
priority: p3
issue_id: "250"
tags: [code-review, mysql, serializable, docs]
dependencies: []
---

# Views over information_schema (sys schema, user views) fail with 3550 at SERIALIZABLE on MySQL

## Problem Statement

`readsInformationSchema` recognises only tables qualified `information_schema`. A view that reads
information_schema, the user's own or one of MySQL's `sys` views, gets ` LOCK IN SHARE MODE` and
MySQL refuses it with error 3550. The README says the driver "does not lock information_schema",
which reads as if such reads work. It fails closed (an error, nothing read unlocked), so this is
usability and docs.

## Findings

- MySQL 8.4: `CREATE VIEW vi AS SELECT table_name FROM information_schema.tables; SELECT * FROM vi
  LIMIT 1 LOCK IN SHARE MODE` gives `ERROR 3550 (HY000) Information schema queries do not support
  the 'LOCK IN SHARE MODE' clause`.
- MySQL accepts the clause on a derived table or CTE over information_schema, on a block with an
  information_schema subquery, and on `performance_schema`, `mysql` and `sys.version` (probed);
  MariaDB accepts all of them.

## Proposed Solutions

### Option 1: document it
Add to the README sentence: views that read information_schema, `sys` ones included, fail with
MySQL's 3550 at SERIALIZABLE; read the base tables, or at another level.

### Option 2: retry without the clause on 3550
Only for blocks whose tables are all views; needs care to not unlock user tables. Probably not
worth it.

## Acceptance Criteria

- [ ] README states the limit (or the driver handles views)

## Work Log

- 2026-10-18: filed by the focused review of 45afd86 and e24a850 (probe on MySQL 8.4).
