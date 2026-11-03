---
status: pending
priority: p2
issue_id: "368"
tags: [design, postgresql, inline, sql-ast, performance]
dependencies: []
---

# The whole-row rule takes an enclosing block's column for the inline table's row, sending TPC-H Q9 through `rc_T`

## Problem Statement

`SqlAst.columnsReadFrom` returns null, every column, for an unqualified column reference whose name
equals the table's exposed name, since PostgreSQL reads `SELECT t FROM t` as the whole row (todo
110). It looks at every column reference of the statement, whatever query block it is written in.
TPC-H Q9 reads `select nation, o_year, ... from (select n_name as nation, ... from part, supplier,
lineitem, partsupp, orders, nation where ...) as profit group by nation, o_year order by nation,
o_year`: the outer block's `nation` is the derived table's column, and the table `nation`, unaliased
in the inner block, cannot be seen from there; the rule reads it as nation's whole row all the
same. On an inline table a whole-row read has no plain statement (`Router.withPlain` declines, as
the metadata columns would show) and no gate, so in a transaction whose checks passed the whole
six-table statement runs through `rc_T`, a UNION ALL view into which no engine pushes a join
predicate (section 3). Decoupled tables are not gated by the rule, so only inline is hit; Q7 and
Q8 alias nation as `n1`/`n2` and escape.

## Findings (session 28, 2026-11-03)

Local bb-pgss (PostgreSQL 16), BenchBase TPC-H at scale factor 0.1, inline layout, main at
8c25ffc. BenchBase's runs: 20 of 21 queries within 0.79-1.28x of the vendor; Q9 1,035 s through
the driver against the vendor's 59 ms (17,543x). The row check in a REPEATABLE READ transaction:
20 queries the vendor's rows at 0.65-1.5x cold (Q19 4.3x, Q22 2.6x, Q1 and Q2 about 2x); Q9 timed
out at 30 s. Proven on the container: the same Q9 with the alias renamed to `nat` runs as written
over the data tables in 64 ms (`pg_stat_statements`). Autocommit on inline is todo 367's cliff
again (Q5, Q7, Q8 and Q9 time out, Q2 178x, Q11 196x, Q22 326x, Q21 out of `/dev/shm`), recorded
there.

Confirmed from the code (2026-11-03): `SqlAst.java` line 3378, `q == null && name.equals(self)`
over `columnRefs`, which holds every block's columns; `Walker.scopes` records a query block for each
table node (`open`, the stack of PlainSelects being visited) and nothing for a column node.

## Design (4.2's projection rule, 2026-11-03)

An unqualified name equal to the table's exposed name reads every column only where PostgreSQL could
resolve it to the table: written in the table's own query block, or in a subquery of it (a
correlated reference sees the enclosing blocks' tables). One written in an enclosing block, or in
a block beside the table's, names something of its own block, a derived table's column as Q9's
`nation`, and is an ordinary column name to the rule.

Build:

- `Walker.visit(Column)` records the column's block as `visit(Table)` does, `open.peek()`, and the
  walker records each block's parent at the push (`open.peek()` before it), so the AST can tell
  whether one block is under another.
- `ColumnRef` carries its block; a column the walk skipped (`SqlAst.java` ~614, added from the
  clause's values) has none and keeps today's reading, every column, the conservative side.
- `columnsReadFrom`: the whole-row case applies when the column's block is the table's block or
  under it; the table at the top (`r.scope == null`) has every block under it.

Unchanged: a `*` or `t.*` in the select list, NATURAL and USING joins, the qualified reference
`t.x` from any block (naming too many is harmless, as the Javadoc has it), the doubled-quote rule.

Expected: Q9 as written in a transaction whose checks passed, 28's 64 ms; and a narrower copy in
the split read for the same shape with fewer tables.

What must keep holding: `SplitTest` ("110: a whole-row reference reads every column"),
`ReviewFindingsIntegrationTest` ("110 whole-row reference", `SELECT m FROM {T} m WHERE m.id = 1`).
To add, in `SplitTest`: Q9's shape, `SELECT nation, SUM(x) FROM (SELECT n_name AS nation, x FROM
nation WHERE ...) p GROUP BY nation`, whose columns read from `nation` are `n_name` and `x`; a
correlated whole-row reference, `SELECT 1 FROM t WHERE EXISTS (SELECT 1 FROM u WHERE u.r = t)`,
still every column; and a table beside a derived table that exposes a column of its name, `SELECT t
FROM (SELECT 1 AS t FROM u) d, t`, where PostgreSQL takes the column and the rule, which cannot see
a derived table's columns, keeps reading every column, the harmless side (the table's own block).
In the integration tests: Q9's shape on an inline table returns the vendor's rows in a transaction
and in autocommit.

## Acceptance Criteria

- [ ] `columnsReadFrom` scopes the whole-row rule as designed, with the unit tests above
- [ ] Q9's shape on an inline table runs as written once the checks passed (the text the vendor
      received), returning the vendor's rows
- [ ] Full check passes on all engines

## Review record (2026-11-03, b8c2b5e on `whole-row-rule-scope`, built by session 28 on d7df0e7)

Verdict: mergeable once one line is changed, `Walker.enclosing(Column)` returning null for a column
with no recorded query (today it returns an empty set). Read from `git show`.

- `SqlAst`: the walker records `columnBlocks` (column to `open.peek()` at `visit(Column)`) and
  `parents` (query to `open.peek()` at the push in `visit(PlainSelect)`), the same stack the
  tables' scopes use, so the two agree; `enclosing(c)` walks the parents into an identity set;
  `ColumnRef` carries the set, null for a `t.*` and for a column the walk skipped (the clause's
  values, `SqlAst.java` ~614); `sees(r)` is `enclosing == null || r.scope == null ||
  enclosing.contains(r.scope)`; the whole-row condition gains `&& c.sees(r)`. The ancestry computed
  per column at parse time, the parent map staying in the walker, in place of the design's field
  on the AST: fine, smaller.
- Checked against the parser (JSQLParser 5.4's `TablesNamesFinder`, fetched from its tag):
  `visit(PlainSelect)` visits the WITH items, select items, FROM, joins, WHERE, GROUP BY, HAVING,
  window definitions, ORDER BY, LIMIT, OFFSET and FETCH inside the method, so every column of a
  top-level PlainSelect is recorded under its push; columns visited outside any push belong to a
  `SetOperationList` or `ParenthesedSelect` ORDER BY, statements for which `columnsReadFrom`
  returns null before the rule. So the empty-set case is unreachable today, which is why the
  change asked is one line and not a block: an empty set says "sees no query" and would turn the
  whole-row reading off, todo 110's wrong rows, for any column a later parser or walker change
  visits outside a push, where null keeps the conservative reading the design asks for a column
  the walk did not record.
- Observation, no change: a CTE body is visited under the main query's push, so its parent is the
  main query and a CTE body's unqualified column named like a main-query table still reads that
  table whole, though a CTE body cannot see the main query's FROM. Over-reading, harmless, as it
  was before the change.
- `SplitTest`: Q9's shape reads nation's named columns (`amount, n_name, n_nationkey, nation,
  s_acctbal, s_nationkey`; `nation` among them is the outer column's name, naming too many); the
  correlated `exists (select 1 from u where u.r = t)` stays null; the derived table's own `t`
  (`select t from (select x as t from t) s`) reads `t, x`; the todo 110 cases hold. Right.
- `SnapshotIntegrationTest.enclosingColumnNamedLikeTheTable` (PostgreSQL, both layouts): Q9's
  shape over `t` runs as written in a transaction, the text from its own `current_query()`
  containing the data table and neither `rc_` nor `UNION`, and returns the rows in autocommit
  (rows only, the autocommit path being todo 367's). Without the change the inline run's text is
  `"sni"."rc_t"`. Right.
- Suites as reported: unit 417, PostgreSQL and Oracle integration green; the same two Oracle
  tests failed once with ORA-12516 and passed rerun alone.
- Changelog `bugfix-20261103-110000`: accurate, not engine-specific, as the rule is not.

Follow-up (2026-11-03): the line in as asked, 4e551da on the branch: `Walker.enclosing(Column)` is
`@Nullable` and returns null where `columnBlocks` has no entry for the column, the set as before
otherwise, its Javadoc saying null keeps the whole-row reading. Unit suite and spotbugs clean,
`SnapshotIntegrationTest` and `ReviewFindingsIntegrationTest` green on PostgreSQL per 28; no unit
case, none reaching it. The branch at 4e551da (b8c2b5e + 4e551da) is mergeable as a whole;
landing on the user's word, the landed head to be range-diffed against it.

## Work Log

- 2026-11-03: filed from session 28's TPC-H inline run on PostgreSQL; the cause confirmed from
  `SqlAst`; the design written into 4.2's projection sentence. Awaiting the user's word on who
  builds it.
