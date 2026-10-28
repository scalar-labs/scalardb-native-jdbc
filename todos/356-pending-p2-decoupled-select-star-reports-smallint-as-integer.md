---
status: pending
priority: p2
issue_id: "356"
tags: [read, result-metadata, postgresql, sqlserver, regression]
dependencies: []
---

# A split read reports a `SMALLINT` column as INTEGER on PostgreSQL and SQL Server

## Problem Statement

T4's PostgreSQL regression tests at e8f3436, layout d: after every file, the comparison of the
imported tables fails on `int2_tbl` (`f1 int2 PRIMARY KEY`): through the driver,
`SELECT * FROM <ns>.int2_tbl` reports `f1` as `Types.INTEGER` (4), the plain table as
`Types.SMALLINT` (5). The values match. 20 of layout d's 21 failures (the 21st is todo 313); the
run of 2026-10-21 had 1 failure in each layout.

## Findings

- Cause: the split read's second arm, the before images (`SplitRead.inFlight`), reads the metadata
  table alone, and so the key from it, where ScalarDB keeps a `SMALLINT` key as `INT`; a non-key
  column's before image (`before_v`) is `INT` too, in both layouts. `SMALLINT UNION ALL INTEGER`
  is `INTEGER` on PostgreSQL and `INT` on SQL Server (`TINYINT` too). Todo 303 cast the before
  images back to the column's type on MySQL and MariaDB only; the key from the metadata table
  was never cast. The read view `rc_T` takes the key from `T` and was right.
- PostgreSQL's other widenings keep the type: a `UNION` takes the first arm's type when the other
  converts to it implicitly, as `text` does to `char(n)` and `varchar(n)` (checked on 16).
- The bisect (cd2223d good, e8f3436 bad) named 202e467, todo 316 (`SELECT *` in the table's
  order). The arm reading the key from the metadata table alone came earlier, with eef0e86; why
  the suite showed it only from 202e467 is not traced.
- MySQL and MariaDB widen `SMALLINT`, `TINYINT` and `MEDIUMINT` the same way (checked on MySQL
  8.4: a `UNION ALL` of each with `INT` is `int`), but their `CAST` has no `SMALLINT` target; left
  as is, documented in sql-support.md's Column types.
- An application calling `getShort` read the value either way; one switching on
  `ResultSetMetaData.getColumnType`, or an ORM mapping by it, saw another type.

## Proposed Solutions

### Option 1: cast as todo 303 does, the key too
The catalog lists each `SMALLINT` and SQL Server `TINYINT` column of a data table
(`Engine.addBeforeTypes`), and the split read casts the before image and, on PostgreSQL and SQL
Server only, the metadata table's key to it (`TableInfo.metadataKey`). MySQL and MariaDB keep the
key uncast: todo 303 lists their `CHAR`, `VARCHAR`, `DATETIME` and `TIMESTAMP` columns, and a cast
`VARCHAR` key would cost as below on a common kind of key. A predicate on the cast key cannot use the metadata table's key
index: a read by key reads every row of the table in flight through the in-flight index. Measured
on PostgreSQL 16, 65,536 rows (a `SMALLINT` key's most), the before-image arm of `k = 5000`: 0.010
against 0.012 ms with 10 rows in flight, 0.010 against 0.21 ms with 1,000, 0.017 against 4.6 ms (a
full scan) with 30,000. The arm for the rows not in flight keeps the data table's key index.
**Effort:** Small.

### Option 2: report the source column's type in the result's metadata, as todo 304 does
Keeps the key index, but only for plain column references: `MAX(k)` or `COALESCE(k, 0)` still
report `INTEGER`, `k + k` no longer overflows where the table raises "smallint out of range",
`pg_typeof(k)` returns `integer`, and `CREATE TABLE ... AS` would make an `integer` column.
**Effort:** Medium. Not chosen (2026-10-28, the user): the cast, and the docs advise `INTEGER`.

## Recommended Action

Option 1, on branch `smallint-types`.

## Acceptance Criteria

- [x] The regress suite's layout d has no `int2_tbl differs` failure (seed 1, settled and in flight: 4,354, 634, 1, as layout i).
- [x] `ReadIntegrationTest.narrowIntegers_keepTheirType_whileRowsAreInFlight`, PostgreSQL and SQL
  Server, both layouts: the plain table's types and the before image's values (fails without the
  fix in all four).

## Work Log

### 2026-10-28 - Found by T4 PostgreSQL regression tests, bisected, fixed

**By:** Claude Code

Overnight run at e8f3436, seeds 1-3, settled and in flight: the same 21 failures each time.
Reproduced on a throwaway PostgreSQL 16 with statement logging: the split read's second arm is
`SELECT m."f1" FROM "rd"."int2_tbl_tx_metadata" m ...`, `f1 integer` there.

## Resources

- docs/plans/testing.md, milestone T4; todos 303, 316.
