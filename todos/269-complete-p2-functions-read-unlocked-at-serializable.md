---
status: complete
priority: p2
issue_id: "269"
tags: [code-review, sqlserver, serializable, isolation]
dependencies: []
---

# Table-valued and scalar functions read unlocked at SERIALIZABLE on SQL Server

## Problem Statement

`holdingEveryTable` (SqlAst) walks `tableRefs` only. An inline TVF `pp.tvf(@x)` over `pp.q` and a
scalar UDF summing `pp.q`: `SELECT v FROM pp.tvf(1)`, `CROSS APPLY pp.tvf(p.id)`, `SELECT pp.fsum()`,
`UPDATE pr.t SET v = pp.fsum() WHERE id = 1` all run, and a concurrent `UPDATE pp.q ... WHERE id = 1`
succeeds at once (a direct pp.q read blocks it, 1222). SQL Server rejects hints on a TVF (Msg 1018),
so these cannot be held.

Related, wider than 8c3: a function body that reads a ScalarDB table is not seen by the driver at any
level; check whether that is documented/refused.

## Proposed Solutions

1. At SERIALIZABLE on SQL Server refuse a statement calling a user function (schema-qualified or not
   built in) or a table function (small).
2. Document the limitation instead.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 5acbf90 (8c3), verified as stated above.
- 2026-10-19: fixed. SqlAst.callsUserFunction (schema-qualified call outside sys, or a table function other than OPENJSON/STRING_SPLIT/GENERATE_SERIES) makes holdingEveryTable refuse, so such a statement is refused at SERIALIZABLE on SQL Server. Function bodies reading ScalarDB tables at any level stay as design 4.1 says (a function the user wrote that runs SQL is not supported). Tested in TableHintsTest and SnapshotIntegrationTest.sqlServerPlainTables.
