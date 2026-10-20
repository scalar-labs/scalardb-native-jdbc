---
status: complete
priority: p3
issue_id: "256"
tags: [code-review, sqlserver, correctness]
dependencies: []
---

# getGeneratedKeys after a batch of over 2100/width keys fails after the rows are written

## Problem Statement

`Writes.returned` (Writes.java:1683) binds every key in one statement, not chunked by
`chunk(n)`; its comment gives PostgreSQL's 65,535 limit, SQL Server's is 2,100. 2,101 `addBatch`
rows of `INSERT INTO ns.idt (x) VALUES (?)` with RETURN_GENERATED_KEYS in autocommit:
`executeBatch` commits, then `getGeneratedKeys` fails with 8003.

## Proposed Solutions

1. Chunk the read with `chunk(n)` (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
- 2026-10-20: fixed. refused before the commit when the keys to read back exceed the engine's parameter limit (2,100 on SQL Server, 65,535 elsewhere), so autocommit writes nothing (Writes.returned). Chunked reading into rows held in memory left for when a batch needs more.
