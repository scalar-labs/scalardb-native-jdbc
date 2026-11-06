---
status: pending
priority: p3
issue_id: "375"
tags: [design, postgresql, oracle, gated-read, plans]
dependencies: []
---

# The gated statement's gates come in identity-hash order, so one statement has several texts

## Problem Statement

`Router.gated` and `lateralGated` build the gates by iterating `scalarDb.entrySet()`, an
`IdentityHashMap` keyed by `TableRef`, whose order follows the references' identity hashes and so
differs between JVMs and between parses of the same statement. One statement then has several
gated texts (the gates in different orders), each a cursor of its own on Oracle and a row of its
own in `pg_stat_statements`, parsed and planned separately.

## Findings (2026-11-06, from 6e's Oracle 19c READ COMMITTED SF 3 decoupled cell, 20261105-1825-ora19)

TPC-H Q12 showed 1.23-1.27x in the check (2,113 against 1,713 ms, one run a mode) while
BenchBase's p50 was 1.00x and its p95 1.26x. The driver run's `server.txt` has Q12 as two texts of
two calls each: the gates `LINEITEM, ORDERS` at 303,126 gets a call and 1,672 ms (the vendor's
303,227 and 1,663: 1.00x), and the gates `ORDERS, LINEITEM` at 347,860 gets and 1,859 ms. Q8 is
two texts as well (`NATION` first in both visible heads, 368,567 and 368,579 gets, both the
vendor's 368,539). So Q12's quarter is two cursors for one statement, each peeking its own first
binds (the check's seed parameters, BenchBase's random ones) and planning once, one of them 15%
more gets; the vendor's single text took one plan for all four. The inline cell, with its own
first binds, had Q12 at 1.00x. Not the derived wrapper's plan (the gets of the fast text are the
vendor's), not the gates (two index probes).

## Design

The gates, their checks and the hidden columns follow the statement's own reference order
(`ast.tableRefs()`, or the split's places in order), the same for every parse of a text; `scalarDb`
iterated in that order wherever its order reaches a statement text (the gates, `checks`, the
coarse checks' columns in `Router.check`, the in-flight checks of `withPlain`). One text a
statement, one cursor, one `pg_stat_statements` row. Bind peeking's plan variance stays Oracle's,
as it is for the vendor's own text; this item only stops the driver from multiplying it.

Tests: a unit test parsing a two-table gated read twice (two `SqlAst` instances) and asserting the
same gated text; the Oracle and PostgreSQL gated tests' texts unchanged in meaning.

## Acceptance Criteria

- [ ] The gated text's gate order is the statement's reference order, with the unit test
- [ ] Full check passes on all engines

## Work Log

- 2026-11-06: filed from the Q12 reading of 6e's run (the archive at
  `bench-env/results/20261105-1825-ora19/b/`); a small build for 28 when the user wants it.
