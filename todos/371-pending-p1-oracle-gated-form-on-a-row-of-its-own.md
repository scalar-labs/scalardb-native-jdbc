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

## Acceptance Criteria

- [ ] Oracle's gated reads run on the wrapper, an empty result one statement, with the tests above
- [ ] Full check passes on all engines, 19c included
- [ ] Q18 in autocommit at SNAPSHOT at about 1x in 28's Oracle step from the build

## Work Log

- 2026-11-04: the user's item 2 (via 28); measured on the container (above); designed into 4.2.
  28 builds after todo 370, on the user's word.
