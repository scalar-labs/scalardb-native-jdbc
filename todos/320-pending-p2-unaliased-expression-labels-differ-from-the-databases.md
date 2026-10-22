---
status: pending
priority: p2
issue_id: "320"
tags: [read, result-metadata, mysql, mariadb, sqlserver, oracle]
dependencies: []
---

# An unaliased expression's column label differs from the database's

## Problem Statement

MySQL and MariaDB label an unaliased select-list expression with its text as written. The driver
runs a rewritten text (re-spaced, read views named), so the label changes:
`SELECT a+b*2 FROM t` is labelled `a + b * 2`, `count( * )` becomes `count(*)`, and a subquery's
label names `rc_t1`. An application reading such a column by label
(`rs.getLong("a+b*2")`) fails. On SQL Server, `SELECT + cor0.col2 ... GROUP BY cor0.col2` is
labelled `g1` (the split read's lifted grouping alias), not `col2`. The values and the types match.

## Findings

- T4 sqllogictest, 1000 queries per file, 2026-10-22: MySQL 3029 and MariaDB 3029 of their
  failures, mostly select1-3; SQL Server 45, all `+ col` grouped (random/groupby); Oracle 322
  (it labels with the text upper-cased, spaces removed, so only the read views' names and
  rewritten tokens show). PostgreSQL labels by function name or `?column?`: none.
- Also found: on MySQL `n DIV col` through the driver is BIGINT where the plain table gives INT (4
  queries); on MariaDB `MAX(constant)` types differ (6 queries). Types via the read views' UNION
  ALL; P3, kept here until split out.

## Proposed Solutions

### Option 1: alias unaliased expressions with their original text
On MySQL and MariaDB, give each unaliased top-level select item that is not a bare column the
alias the database would label it with: the item's original text. On SQL Server, keep a lifted
grouping column's own name. **Effort:** Small to Medium. **Risk:** MySQL's label rules (leading
spaces, length limit 256, a literal's own label) need matching exactly.

### Option 2: document it
Alias computed columns read by label. **Effort:** Small.

## Acceptance Criteria

- [ ] sqllogictest's label failures are gone on MySQL, MariaDB, SQL Server and Oracle.

## Work Log

### 2026-10-22 - Found by T4 sqllogictest

**By:** Claude Code

## Resources

- docs/plans/testing.md, milestone T4.
