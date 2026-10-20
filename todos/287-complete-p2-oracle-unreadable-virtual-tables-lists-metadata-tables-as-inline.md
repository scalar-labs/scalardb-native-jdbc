---
status: complete
priority: p2
issue_id: "287"
tags: [code-review, catalog, oracle, inline]
dependencies: []
---

# Oracle: an unreadable virtual_tables makes decoupled metadata tables inline

## Problem Statement

On Oracle a user who can read `scalardb.metadata` but not `scalardb.virtual_tables` gets ORA-00942, which
`Engine.isMissingTable` takes for a missing table: the load warns and goes on with no virtual tables
(Catalog.java, `load`). Each decoupled table's metadata table then holds every transaction column and is no
virtual table's source, so `inlineTables` lists it and `classify` makes it INLINE; `Setup.run` (through
`scalarDbTables()`) would create a view and an index on it and backfill it. Older and wider in the same
scenario: the decoupled data tables read as PLAIN, uncommitted rows included. Code trace (review of 10a).

## Proposed Solutions

1. An inline table has a data column `c` for every `before_c`; a metadata table has `before_c` without `c`.
   Classify and list as inline only then (small).
2. Treat a privilege failure on virtual_tables as fatal when metadata is readable (small; separate).

## Recommended Action

## Acceptance Criteria

- [x] Fixed and tested

## Work Log

- 2026-10-20: found in the review of 10a (26d93a2).
- 2026-10-20: fixed. an inline table needs a data column for each before_c (or before_c is a data column with its own before image), which a metadata table never has; unit test without virtual tables. The privilege failure itself still reads as a missing table, as before.
