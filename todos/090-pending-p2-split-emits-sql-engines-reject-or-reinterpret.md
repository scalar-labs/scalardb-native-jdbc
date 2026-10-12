---
status: pending
priority: p2
issue_id: "090"
tags: [code-review, correctness, split-read]
dependencies: []
---

# The split emits SQL that some engines reject or read differently

## Problem Statement

Several shapes pass `splittableRefs` and `lift` but produce split SQL the engine rejects, where the rc_T
route would have run, or that an engine resolves differently from the original. The user gets a vendor
error that quotes the rewritten 2^n-copy text.

## Findings

- Sequences: Oracle `s.NEXTVAL` (parsed as a Column) and SQL Server `NEXT VALUE FOR s`
  (`NextValExpression`) split; both engines reject them in a UNION (ORA-02287). RowWise checks only
  `Function` (SqlAst.java:1259ff).
- Duplicate labels with ORDER BY (confirmed by probe): `SELECT x, x FROM a ORDER BY x` becomes `SELECT
  v.c1 AS x, v.c2 AS x ... ORDER BY x`, ambiguous on PostgreSQL (and likely MySQL). Lift 1612-1630.
- MySQL `HIGH_PRIORITY`, `SQL_CACHE`/`SQL_NO_CACHE`, `SQL_BUFFER_RESULT` are repeated per copy; MySQL
  rejects them inside a UNION (per docs, not run).
- MySQL/MariaDB: a reserved word allowed after a qualifier (`t.key`) becomes an unquoted label `AS key`, a
  syntax error (lift 1602-1609; reviewer's confidence about 75%).
- ORDER BY expressions that use a select alias (`SELECT x AS y FROM a ORDER BY y + 0 LIMIT 1`): the hidden
  `sN` column evaluates the expression in the copy against the base column. MySQL (and possibly Oracle)
  resolve the alias in the original, so order and the limited rows differ, or the statement fails
  (lift 1614-1640; emitted SQL confirmed, engine behaviour not run).
- `parses()` validates only copy 0 (see also 086); an unchecked exception from `SplitRead.emit` passes
  the proxy as a non-SQLException (no reachable trigger found).

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Partly confirmed.
- Duplicate labels with ORDER BY: errors on PostgreSQL (42702), MySQL and MariaDB (ambiguous), Oracle
  (ORA-00960), while the original runs on PostgreSQL, MySQL and MariaDB. SQL Server rejects the original
  too, so no regression there.
- `HIGH_PRIORITY`: rejected on MySQL and MariaDB. `SQL_NO_CACHE`: rejected on MariaDB, accepted on MySQL.
- Reserved-word label (`SELECT t.rank FROM rd.made2 t ORDER BY t.id LIMIT 5`): syntax error on MySQL,
  works on MariaDB; the original works on both.
- Sequences: ORA-02287 on Oracle; SQL Server rejects NEXT VALUE FOR in a UNION ALL.
- **Not reproduced:** the alias inside an ORDER BY expression. `SELECT id AS x FROM rd.made WHERE id <> 2
  ORDER BY x * -1, id LIMIT 1` returned the same row as the original on MySQL and MariaDB. Dropped from
  this todo.

## Proposed Solutions

### Option 1: refuse each shape, and validate the whole text
In RowWise refuse `NextValExpression` and Oracle `NEXTVAL`/`CURRVAL` columns; in Lift refuse duplicate
labels referenced by ORDER BY and hidden expressions that mention a label; in `splittableRefs` refuse the
MySQL modifiers; backtick-quote Column labels on MySQL/MariaDB; parse the full text; turn unchecked
exceptions from `SplitRead.emit` into a fallback. **Effort:** Medium. **Risk:** Low (fewer splits).

### Option 2: retry unsplit on a vendor syntax error at prepare
Catch a syntax error on a split route at prepare and fall back to the rc_T text. **Effort:** Medium.
**Risk:** Medium (masks bugs).

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [ ] SplitTest covers each shape above and gets either a fallback or SQL the engine accepts.
- [ ] Integration cases for sequences (Oracle, SQL Server) and duplicate labels pass.

## Work Log

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
