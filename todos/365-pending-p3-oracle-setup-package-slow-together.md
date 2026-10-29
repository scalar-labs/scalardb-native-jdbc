---
status: pending
priority: p3
issue_id: "365"
tags: [test, oracle, setup, slow-test]
dependencies: []
---

# Oracle: one setup test case takes 35 minutes when the setup package runs together

## Problem Statement

On Oracle at 536fe1e, `:driver:integrationTest --tests '*setup*'` on a fresh container passes, but
one `SetupIntegrationTest` case took 2,113 s (the report doesn't name the method), another 177 s,
and `ObjectsCheckIntegrationTest` 180 s. Alone, each of the class's methods passes in 4.5 to 16.2 s
on a fresh container (`withoutTheBackfill_...` 10.7 s, `setup_makesTablesUsable` 16.2 s,
`anImportedKeyOutOfNameOrder` 4.5 s). So the time comes from the classes sharing one database.

Suspect, not confirmed: one of the setup's statements waits for another class's open transaction,
for example Oracle's online index build, which waits for transactions open on the table. If so, it
also matters to operators: the setup on a live database would wait for the applications' open
transactions.

## Proposed Solution

Rerun the setup package on a fresh Oracle container while sampling `V$SESSION`'s waits (event,
blocking session, SQL) every few seconds, about 40 minutes. Then fix the test that leaves a
transaction open, and, if it is the online index build, say in setting-up-tables.md that the setup
waits for open transactions on the table.

## Acceptance Criteria

- [ ] The waiting statement and what it waits on named.
- [ ] The setup package on Oracle in minutes, not half an hour.

## Work Log

- 2026-10-29: found by c7 rerunning the Oracle setup and tools suites against 536fe1e (both pass);
  not chased then, by 6e's call.
