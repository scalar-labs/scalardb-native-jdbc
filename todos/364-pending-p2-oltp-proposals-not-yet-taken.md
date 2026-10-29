---
status: pending
priority: p2
issue_id: "364"
tags: [performance, oracle, postgresql, design]
dependencies: []
---

# OLTP proposals made and not yet taken (from todo 334)

## Problem Statement

Todo 334 closed on 2026-10-29 with every Oracle form it designed built. These proposals from its log
and the reviews around it were made but not taken; each waits on the user's word. Details are in
todo 334's log and the design sections named.

## The proposals

1. **Oracle's one-second buffer busy waits.** Read from the next full run's diagnostics: each
   repetition's stall count (55048da) and the V$SESSION poller's `waits.txt` (7ce0408). Then decide
   whether anything on the driver's side is involved (the vendor's runs have them too).
2. **D1's gate, one per statement.** The SNAPSHOT inline state column's gate costs two INLIST probes a
   UNION ALL branch (16 of 27.7 gets in the ninth run); one gate per statement, or `BETWEEN 1 AND 2`.
3. **READ COMMITTED decoupled INSERT in bulk, and the merged statement-first block** on Oracle
   (designed and measured, todo 334's log).
4. **Oracle's inline `NO_INDEX_SS`** (design 4.2, a build item).
5. **PostgreSQL's metadata-side key range** (design 4.2, a build item).
6. **The key order of ScalarDB's import.** ScalarDB orders a composite key alphabetically, so the
   metadata table's key and the in-flight index are out of the data key's order; the options are in
   todo 334's log and design section 3.

## Acceptance Criteria

- [ ] Each proposal taken or dropped with the user, the taken ones as todos of their own.

## Work Log

- 2026-10-29: collected from todo 334 when it closed (the user's go-ahead).
