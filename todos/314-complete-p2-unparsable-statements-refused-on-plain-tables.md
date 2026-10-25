---
status: complete
priority: p2
issue_id: "314"
tags: [parser, refusal, postgresql]
dependencies: []
---

# Statements the parser cannot read are refused even when they touch no ScalarDB table

## Problem Statement

The driver refuses every statement its parser (JSqlParser) cannot read, `0AN01 the statement cannot
be parsed`, also where the statement names no ScalarDB table at all. In T4 that is about 300 of
PostgreSQL's regression statements, most on plain tables, that run on the database. An application
must switch to the passthrough setting for them, which applies in autocommit only.

## Findings

T4, PostgreSQL REL_16_15, statements refused by the parser (each layout):

- Transaction and session control in SQL: `RELEASE SAVEPOINT x`, `SAVEPOINT settings` (a keyword
  as name), `ROLLBACK TO x`, `COMMIT AND CHAIN`, `END`, `SET TRANSACTION READ ONLY`, `RESET SESSION
  AUTHORIZATION`. JDBC's own calls cover most, not `RELEASE SAVEPOINT` written in SQL.
- Cursors: `DECLARE ... CURSOR`, `FETCH`, `MOVE`, `CLOSE`.
- Queries: `ORDER BY x USING <`, `JOIN ... USING (i) AS x`, nested `GROUPING SETS`, `GROUP BY
  DISTINCT/ALL rollup(...)`, `person* p`, `CYCLE ... SET` in a recursive CTE, a window frame
  `unbounded(1) preceding`, `select union select`, `aggfns(... order by c using ~<~)`.
- DML: `insert into t (f4[1].if2[1]) ...`, `UPDATE ... SET (a,b) = ROW(v.*)`, `DELETE ... USING t1
  JOIN t2`, `WITH foo AS (MERGE ...)`, `MERGE ... INSERT DEFAULT VALUES`.
- DDL: `CREATE RULE ... DO ALSO INSERT ...`, `DO $$ ... $$`, and other `CREATE`/`DROP` forms.

## Proposed Solutions

### Option 1: a lexical check before refusing
When a statement cannot be parsed, scan its tokens (identifiers outside strings and comments); if
none matches the name of a ScalarDB table in the catalog (or a schema holding one), run it as
written. Conservative: any identifier that could name a ScalarDB table keeps the refusal.
**Effort:** Medium.

### Option 2: report JSqlParser gaps upstream
Some are plain grammar gaps (`RELEASE SAVEPOINT`, `ORDER BY ... USING`). **Effort:** Small each,
slow to land.

## Recommended Action

Option 1, approved 2026-10-25, with the conditions of design 4.7 ("Unparsed vendor syntax"): no
protected name in the text, comments and literals included (on Oracle also the synonyms a parsed
statement may not use); a query, DML or DDL by its first word, not stored code; one statement; no
Unicode escape, invisible character, executable MySQL comment or SQL-as-text function; refused at
SERIALIZABLE on MySQL, MariaDB and SQL Server, where every read must be locked.

## Acceptance Criteria

- [x] T4's parser refusals on statements without ScalarDB tables run, with nothing else changed:
  refused 745 to 592 in both layouts, passed 4223 to 4376 (d) and 4243 to 4396 (i), the failed
  statements the same (21 and 1, before the change too). Those still refused are transaction
  control (`SAVEPOINT settings`, `RELEASE`, `COMMIT AND CHAIN`, `SET TRANSACTION`), stored code
  (`CREATE FUNCTION`, `CREATE RULE`, `DO`), `PREPARE`/`EXECUTE`, several statements or a `;` in a
  literal, and statements that name a ScalarDB table.

## Work Log

### 2026-10-21 - Found by T4

**By:** Claude Code
- `RegressIntegrationTest`'s report lists the refusals by reason.

### 2026-10-25 - Done

**By:** Claude Code
- `SqlWords.whyNotAsWritten`, `Outcome.isUnreadable`, `Router.unparsed`,
  `Catalog.guardedSynonyms`; `UnparsedIntegrationTest` on every engine.
- Found on the way: passthrough's name check misreads `--` after a dollar quote on PostgreSQL and
  without a space on MySQL (todo 330).
