---
status: complete
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
- The cause: the emitter re-spaces the tokens it re-emits (`a+b*2` as `a + b * 2`), and a table
  renamed inside an item (a subquery's) changes its text. MySQL keeps the typed text as the label
  over views, derived tables and UNION ALL alike (probed): only the driver's text changes it.
- MySQL truncates a long label, MariaDB drops comments from it and joins adjacent literals:
  emulating the rules is fragile, keeping the text is not.
- The ten type differences found with these are todo 321.

## Proposed Solutions

### Option 1: alias unaliased expressions with their original text
On MySQL and MariaDB, give each unaliased top-level select item that is not a bare column the
alias the database would label it with: the item's original text. On SQL Server, keep a lifted
grouping column's own name. **Effort:** Small to Medium. **Risk:** MySQL's label rules (leading
spaces, length limit 256, a literal's own label) need matching exactly.

### Option 2: document it
Alias computed columns read by label. **Effort:** Small.

## Acceptance Criteria

- [x] sqllogictest's label failures are gone on MySQL, MariaDB, SQL Server and Oracle, but for
  `SELECT *` of a self-join on Oracle (todo 322).

## Work Log

### 2026-10-22 - Found by T4 sqllogictest

**By:** Claude Code

### 2026-10-23 - Fixed (Option 1, keeping the text rather than emulating the rules)

**By:** Claude Code

- MySQL, MariaDB and Oracle: an unaliased top-level item with no renamed table inside is emitted
  as its original text, so the database labels it itself (`SqlAst.keepLabels`, on the read-view
  path and on each copy of a split read); one with a renamed table inside, and any such item of a
  lifted (grouped, DISTINCT) read, is aliased with the label of its original text
  (`expressionLabel`). An item with a `?` is left alone (todo 109).
- `+ col` and `(col)` are labelled as the column on every engine but PostgreSQL, `+ 39` and `(39)`
  as the number on MySQL and MariaDB (`unwrapped`).
- The harness compared labels naming the plain schema (`slt0dp`) with the driver's (`slt0d`):
  it now maps the one to the other.
- sqllogictest after it, per layout: MySQL 4 and MariaDB 6 failures left, the types of todo 321;
  SQL Server 0; Oracle 26, `SELECT *` of a self-join labelled `QCSJ_C...` (todo 322, P3).

## Resources

- docs/plans/testing.md, milestone T4.
