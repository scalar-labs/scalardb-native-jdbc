---
status: pending
priority: p1
issue_id: "371"
tags: [design, oracle, snapshot, read-committed, performance]
dependencies: ["369"]
---

# Oracle's gated form has no row to read its gates from on an empty result

## Problem Statement

The Oracle gated form (todo 334, D2; todo 369's whole-table gates past the split) appends its
gate columns to the select list, so a result of no rows carries none and falls to the fallback:
at SNAPSHOT in autocommit the own transaction's checks and, since todo 367, the plain statement
again; at READ COMMITTED the split, or `rc_T` past the split. An empty key lookup in autocommit
pays two or three round trips for one. The second of the user's three items of 2026-11-04, to
build after todo 370.

## Findings

- Session 28's Oracle TPC-H step at SNAPSHOT (2026-11-04, Oracle 23 free, SF 0.1, `tpchCheck`
  3): Q18, which returns no row at that scale, 2.01x decoupled and 2.10x inline in autocommit
  (66-69 against 139 ms) against 1.03-1.09x in a transaction; the rest 0.96-1.37x.
- Container measurement (2026-11-04, Oracle 23, `OLI_V` 1.2M rows, PK `(OL_W_ID, OL_D_ID,
  OL_O_ID, OL_NUMBER)`, scratchpad `ora/lateral.jsh` and `lateral.out`): the one-row wrapper
  `SELECT s.*, g.* FROM (SELECT <gate> FROM dual) g LEFT JOIN LATERAL (SELECT i.*, 1 AS P FROM
  (<statement>) i) s ON 1 = 1`, and the same as `OUTER APPLY`, returned 30,605 rows and 50 rows
  with `FETCH FIRST` in the plain statement's order (`ORDER BY OL_O_ID DESC, OL_NUMBER`) for two
  (w, d) pairs, and one row of NULLs for two empty pairs; the plan is NESTED LOOPS OUTER from FAST
  DUAL to the lateral view (`VW_LAT_...`) with SORT ORDER BY STOPKEY inside it, the gate a scalar
  subquery evaluated once (FILTER over FAST DUAL and an INDEX RANGE SCAN of the in-flight index);
  the cost the plain statement's, 8.62 against 8.70 ms a call (medians of 5 rounds of 50).

## Design (4.2 "Oracle's gated form on a row of its own", 2026-11-04)

The Oracle gated form moves to the one-row wrapper of todo 369 in Oracle's spelling, for every
use of it (SNAPSHOT's whole-table gates, READ COMMITTED's range gates within the split, 369's and
370's whole-table gates): the gates from `dual` on the outer side of a lateral join (12c and
later; `OUTER APPLY` plans the same), the statement as written in an inline view inside the
lateral view with the marker column, each gate the existing `(SELECT COUNT(*) FROM dual WHERE
EXISTS (SELECT /*+ INDEX */ 1 FROM T_meta m WHERE m.tx_state IN (1, 2) [AND <range>]))`, the range
conjuncts and their placeholders as `SqlAst.gated` extracts them, now textually before the
statement's placeholders, the parameter sources recorded accordingly.

Why the order holds: the lateral view is the inner row source of a nested loop whose outer side
is one row, and its ORDER BY (and FETCH FIRST) sit inside the view, so its rows come out in its
own order; measured above on 30,605 rows. The build's test asserts it on a ranged read with ORDER
BY and FETCH FIRST against the vendor's rows.

What the wrapper removes: the `MAX()` over a grouped select list; the bare-`*` restriction
(Oracle takes `*` beside no other item, but inside the inline view it stands alone); the
position-past-the-select-list guard for the gated form (the inner's ORDER BY positions are its
own, Oracle refusing an invalid one itself); the no-row fallback: a NULL marker is the empty
result, read as on PostgreSQL. What stays: a `*` over an inline table keeps the split (its
metadata columns would show); the gates' meaning, the marking of a clear table at SNAPSHOT, the
fallback on a gate of 1; `Proxies.gated` unchanged, `Gated.marker` true.

Expected: Q18 in autocommit at SNAPSHOT from 2.0x to about 1x; an empty key lookup in autocommit
one round trip; at READ COMMITTED an empty gated read one statement.

Build: `Router.gated`'s Oracle branch builds the wrapper from the renamed text as `lateralGated`
does, with the ranged gates' conjuncts and placeholders from `SqlAst.gated`'s extraction
(`keyConjuncts`); the Oracle tests of todo 334/D2 and 369 updated to the wrapper's text shape.
Tests to add (`OracleOltpIntegrationTest`): an empty result in autocommit at SNAPSHOT is one
statement (one text for the tag in `v$sql`, no second), rows empty, on both layouts; a ranged
read with ORDER BY and FETCH FIRST in the vendor's order; a grouped read without `MAX()`; a bare
`SELECT *` over a decoupled table taking the gated form. 19c in CI (LATERAL since 12c).

## Amendment (2026-11-04, after 28's build of the wrapper alone)

28's build 9ee0024 on `oracle-gated-lateral` (on 370's pre-rebase commits; suites green, every
execution the vendor's rows) regressed two TPC-H queries on Oracle at SNAPSHOT (SF 0.1,
`tpchCheck` 3, against the pre-371 step): Q18 in a transaction 1.09x to 1.85x decoupled and 1.03x
to 1.76x inline, in autocommit 2.01x to 1.83x and 2.10x to 1.78x (117.8/125.5 ms against the
vendor's 64.4/70.4); Q13 1.02-1.10x to 1.38-1.45x (55.5 against 76.7 ms); the other 19 unchanged
or better (Q2 autocommit 1.20x to 1.01x, Q21 1.14-1.17x to 1.00-1.06x). Not mergeable as built.

Cause, from the container (Oracle 23, `OLI_V` 1.2M rows; scratchpad `ora/lat2.jsh` to
`lat6.jsh`): under the lateral view Oracle plans every GROUP BY and DISTINCT as SORT GROUP BY and
SORT UNIQUE in place of HASH GROUP BY and HASH UNIQUE, the join shapes unchanged; a Q18-shaped
statement 284 against 199 ms (1.42x), a Q13-shaped one 72 against 59 (1.26x); every wrapped
spelling alike (LATERAL, OUTER APPLY, a non-lateral LEFT OUTER JOIN, which Oracle turns into a
lateral view anyway, with or without LEADING/USE_NL); no hint on the outer query restores the hash
aggregation (ALL_ROWS, OPT_PARAM('optimizer_mode' 'all_rows'), NO_DECORRELATE, NO_MERGE,
USE_HASH_AGGREGATION at the top), only USE_HASH_AGGREGATION written inside the user's own query
blocks does (198 and 57.7 ms, the plain's), which the driver will not do. Parallel query is not
available on Oracle Free (FORCE PARALLEL QUERY gives no PX step), so nothing parallel was measured.

The amended design (4.2's paragraph rewritten): two spellings by shape.

- A statement without GROUP BY and without a DISTINCT select list, every OLTP shape among them,
  takes the lateral wrapper as built: the plain's cost (a 10-row key read 0.54 against 0.59 ms; a
  30,605-row ordered read 43.5 against 41.5), the order structural (nested loop from one row).
- A statement that groups or selects DISTINCT takes the materialized form:

  ```sql
  WITH r AS (SELECT /*+ MATERIALIZE */ i.*, ROWNUM AS scalardb_native_n FROM (<statement>) i)
  SELECT r.*, 1 AS scalardb_native_present, <gates> FROM r
  UNION ALL
  SELECT NULL, ..., NULL, NULL, <gates> FROM dual WHERE NOT EXISTS (SELECT 1 FROM r)
  ORDER BY <the position of scalardb_native_n>
  ```

  The statement is evaluated once into a temporary table (TEMP TABLE TRANSFORMATION, LOAD AS
  SELECT; in memory on 23ai, the temporary tablespace on 19c) with its plan as it is, the hash
  aggregation kept; the sentinel row comes from `dual` where the table is empty; the order is
  structural: ROWNUM is assigned as the ordered inline view produces its rows (the top-N idiom),
  and the outer ORDER BY restores it whatever order the temporary table is read in, parallel
  execution included; without an ORDER BY in the statement, neither the column nor the outer
  ORDER BY. Measured: the Q18 shape 199.9 against 198.5 ms, the Q13 shape 58.4 against 57.3, the
  30,605-row ordered read 51.9 against 41.5 (the load and the result's sort), a 10-row key read
  0.98 against 0.59 (the temporary table's fixed cost, about 0.4 ms, which is why the OLTP shapes
  keep the lateral wrapper), an empty result one row with the gates. `ORDER BY` names the position
  (`ORDER BY "scalardb_native_n"` on the compound query is ORA-00904).

Both spellings read alike in `Proxies.gated`: the gates and the marker hidden (the order column
too on the materialized form), a NULL marker the empty result, a gate of 1 the fallback; the range
conjuncts' placeholders before the statement's in the lateral form and after it in the
materialized one, the sources recorded. A statement with its own WITH clause goes inside the
inline view (a subquery factoring clause in an inline view is accepted since 12c); to be tested.

Build, on 9ee0024: the shape test (GROUP BY anywhere in the statement's own query, or a DISTINCT
select list, the parser knows) choosing the spelling; the materialized text; the hidden-column
count per form; tests to add: a grouped read with ORDER BY on the materialized form in the
vendor's order, an empty grouped read in autocommit at SNAPSHOT as one text; the TPC-H check's
Q13 and Q18 back at their pre-371 figures, the other queries as in 9ee0024's run; TPC-C's gated
statements (StockLevel, Delivery) on the lateral form, their latency unchanged.

## Second amendment (2026-11-04, after 28's build of the materialized form)

28's d2524d1 (+ e9718f2, a test fix: 370's Oracle test had asserted no UNION ALL, which the
sentinel branch adds), the materialized form as designed, does not restore Q13 and Q18 either:
pre-371 / 9ee0024 / d2524d1, Q18 in a transaction decoupled 1.09 / 1.85 / 1.88x, inline 1.03 /
1.76 / 1.89x, in autocommit 2.01 / 1.83 / 1.84x and 2.10 / 1.78 / 1.93x; Q13 1.10 / 1.43 / 1.43x
and 1.03 / 1.38 / 1.42x; the other queries within 0.9-1.3x; every execution the vendor's rows;
suites green. 28's cursor plans (scratchpad `s371b/`): inside the CTE's load the unnested
subquery's aggregation (`VW_NSO_1`) is SORT GROUP BY where the vendor's is HASH GROUP BY, the
rest of the plan alike.

Reproduced on the container with TPC-H-shaped tables made from `OLI_V` (`TH_LINEITEM` 1.2M rows,
`TH_ORDERS`, `TH_CUSTOMER`; scratchpad `ora/q18.jsh`, `q18b.jsh`, `q18c.jsh`, `dup.jsh`),
BenchBase's Q18 and Q13 texts with binds, actual cursor plans by `DBMS_XPLAN.DISPLAY_CURSOR`:

- Q13: plain 20.2 ms (HASH GROUP BY, SORT GROUP BY NOSORT, MERGE JOIN OUTER, HASH GROUP BY); the
  lateral wrapper 39.7 (SORT GROUP BY twice); the materialized `WITH` 39.6 (the inner GROUP BY
  SORT), with or without ROWNUM, with or without the sentinel; a plain derived table with the
  gates in its own select list, `SELECT i.*, 1 AS P, <gates> FROM (<statement>) i`, 19.8, the
  plan the plain's.
- Q18: plain 76.5 (`VW_NSO_1` HASH GROUP BY); the derived wrapper 76.8, the same plan; the lateral
  81.1 with `VW_NSO_1` SORT GROUP BY (the data here make the sort cheap; 28's real data do not).
- No hint on the outer query changes it (FIRST_ROWS(100) and FIRST_ROWS(1) tried too; FIRST_ROWS(1)
  moved Q13 to a hash join but is no lever for BI plans). Oracle Free has no parallel query.
- Oracle refuses `i.*` over an inline view with two columns of one name, ORA-00918, under both
  wrappers (`SELECT 1 AS a, 2 AS a`, `c_custkey, c_custkey`, `SUM(x), SUM(x)`); a WITH clause
  inside the inline view works.

So the materialized form is dropped and the design (4.2's paragraph rewritten again) is:

- No GROUP BY and no DISTINCT select list: the lateral wrapper, as built in 9ee0024.
- GROUP BY or DISTINCT: the derived wrapper `SELECT i.*, 1 AS scalardb_native_present, <gates>
  FROM (<statement>) i`, the gates in the wrapper's select list (no `MAX()`), the plan the
  plain's; no row means no gates, so the fallback: at SNAPSHOT the own transaction's checks and
  the plain statement as before (an empty grouped result in autocommit at twice the cost, Q18 at
  SF 0.1 only); at READ COMMITTED the lateral spelling of the same statement as the second
  statement, exact in its own snapshot, its gates deciding, in place of `rc_T` (past the split) and
  of the split (within it).
- Both wrappers only where the statement's output names are unique (the parser: aliases, column
  names of plain references, the text of unaliased expressions); otherwise today's select-list
  form stays, as for a `*` whose tables' columns are not all known.
- `Gated` carries the form (lateral, derived, select-list) and the hidden count; the placeholders'
  sources per form; `Proxies.gated` reads the marker on the lateral form only and, on the derived
  form at READ COMMITTED with no row, runs the lateral text before the fallback.

Build, on e9718f2: drop the materialized text; add the derived form and the shape and name
tests; the READ COMMITTED no-row second statement; the tests listed in the design. Acceptance as
amended below.

## Review record (2026-11-04, `oracle-gated-lateral` at f2238ab on 4e916f6, built by session 28)

Verdict: mergeable. Review range 4e916f6..f2238ab (0682c91 the lateral form, d4e6f0c the
materialized form, d37229f its test fix, 766a762 the forms by shape superseding d4e6f0c, f2238ab
the changelog), read as the combined diff from `git diff 4e916f6 f2238ab` and per commit.

- `SqlAst`: `GatedForm` SELECT_LIST (todo 334's code restored: the gates appended under `MAX()`
  where grouped, a bare `*` null), LATERAL (`SELECT s.*, g.* FROM (SELECT <gates> FROM dual) g
  LEFT JOIN LATERAL (SELECT i.*, 1 AS "scalardb_native_present" FROM (<statement>) i) s ON 1 =
  1`), DERIVED (`SELECT i.*, 1 AS "scalardb_native_present", <gates> FROM (<statement>) i`); one
  `gateList(e, gates, ranged, grouped, commaFirst)`; `groupsOrDistinct()` over every query block
  (`selects`), so Q18's subquery counts; `outputNamesUnique()` over the top select list: alias,
  else column name, else the expression's text without whitespace, lower-cased and unquoted, false
  for any `*` or a non-plain select. In both wrappers the gates stand in the outer select list
  before the statement's text, so their placeholders come first; the design's sentence that put
  the derived form's after corrected in this commit.
- `Router.gated`: `form = !unique ? SELECT_LIST : grouped ? DERIVED : LATERAL`; the position-past-
  the-select-list guard kept for SELECT_LIST only; `ranged = !snapshot && !pastSplit` as before;
  `Gated(sql, sources, checks, marks, marker, hidden, retry)`: LATERAL marker true and hidden
  gates + 1, DERIVED marker false, hidden gates + 1 and, at READ COMMITTED, `retry` the LATERAL
  text of the same statement (marker true, hidden gates + 1, no retry of its own), SELECT_LIST
  hidden gates; PostgreSQL's `lateralGated` passes gates + 1 and no retry. Right.
- `Proxies.gated`: a dispatcher and `gated(proxy, one, g, ...)`; in the no-row branch when not
  marking: the result closed, then `g.retry` run as a gated read if present, else the fallback.
  The marker is read on the lateral form only (`g.marker`), the hidden count from `g.hidden`.
  Right.
- `OracleOltpIntegrationTest.gatedReadsRideARowOfTheirOwn` (`acl{i,d}`, TTL 59372): at SNAPSHOT in
  autocommit an empty decoupled key read is one text (it takes the state-column split, never a
  gate; one text asserted) and an empty five-way join past the split one `LEFT JOIN LATERAL`
  text; at READ COMMITTED an empty grouped five-way join past the split is two texts, one starting
  `SELECT i.*, 1 AS` and one `LEFT JOIN LATERAL`, no `RC_`; a ranged `ORDER BY x DESC FETCH FIRST
  3` with binds returns 8/80, 7/70, 6/60 on the lateral form; a grouped `ORDER BY 2 DESC` and a
  DISTINCT `ORDER BY 1 DESC` on the derived form in the vendor's order, no `MAX(`, no `LATERAL`;
  a statement with its own WITH inside the wrapper; `t1.k, t2.k` on the select-list form (g0, no
  `LATERAL`, no `SELECT i.*`). `executedTexts` and form 7's pattern search `sql_fulltext` by
  `DBMS_LOB.INSTR`; 370's Oracle test reads the forms' texts (its "no UNION ALL" assertion
  dropped); 9ee0024's bare-`*` gated case dropped, the design keeping `*` on the select list.
- Suites as reported: unit 417, PostgreSQL 195, Oracle 195 green. Changelog
  `improvement-20261104-180000`: accurate (the forms by shape, the empty result one statement
  where nothing groups, the second statement at READ COMMITTED where it does).
- Measured by 28 (local Oracle 23 free, SF 0.1, `tpchCheck` 3; pre-371 / 9ee0024 / 766a762): Q13
  in a transaction decoupled 1.10 / 1.43 / 1.02x, inline 1.02 / 1.43 / 1.09x, in autocommit 1.03 /
  1.38 / 0.93x and 1.02 / 1.45 / 1.01x; Q18 in a transaction 1.09 / 1.85 / 1.05x and 1.03 / 1.76 /
  1.03x, in autocommit 2.01 / 1.83 / 2.09x and 2.10 / 1.78 / 1.97x (the documented empty case);
  the others within 0.9-1.4x; every execution the vendor's rows. TPC-C's gated statements: form
  7's Delivery SUM on the lateral form, its test green. 19c at CI.
- History: the materialized step (d4e6f0c) and its test fix (d37229f) never land as a state of
  their own; recommended to squash them into 766a762 before landing (three commits to one), the
  lateral commit and the changelog kept; the squashed head's diff against f2238ab must be empty.
  The user's call with the landing.

Squashed (2026-11-04): the branch is 0682c91, d8db452 (d4e6f0c + d37229f + 766a762 under 766a762's
message) and 55e79b9 (the changelog); my `git diff f2238ab 55e79b9` is empty, the tree the
reviewed one. Mergeable as a whole; landing on the user's word after a rebase onto main's head,
the landed head to be range-diffed against 4e916f6..55e79b9.

### 2026-11-04: on main (ab328f5 + 0b269cf + d44d1bf)

Landed by 28 on the user's "land it" typed in 28's session, rebased onto a9bd7b2; my range-diff of
4e916f6..55e79b9 against a9bd7b2..d44d1bf shows all three commits identical. Nothing pushed; 19c
at CI after the user's push, the last tick.

## Acceptance Criteria

- [x] Oracle's gated reads run on the lateral wrapper (no GROUP BY or DISTINCT) or the derived
      wrapper (grouped), an empty ungrouped result one statement, an empty grouped result at READ
      COMMITTED never `rc_T`, with the tests above (f2238ab, reviewed 2026-11-04)
- [ ] Full check passes on all engines, 19c included
- [x] Q13 and Q18 in a transaction at their pre-371 figures (1.02-1.09x, 1.03-1.05x), Q18 in
      autocommit at about 2x as its empty case documents, Delivery's gated SUM on the lateral form
      (2026-11-04)

## Work Log

- 2026-11-04: the user's item 2 (via 28); measured on the container (above); designed into 4.2.
  28 builds after todo 370, on the user's word.
