---
status: complete
priority: p3
issue_id: "185"
tags: [code-review, docs, isolation]
dependencies: []
---

# Precision fixes to the SSI argument and the sections around it

## Problem Statement

- Design 5, read-only step: "every writer of x is ordered before Sr(x) or after Sv(x)" holds for writers
  that change `tx_id`; a record commit or roll-forward of a row read in state 1 lands in between with
  `tx_id` unchanged (harmless). Take W' as the prepare. Say "directly" can only be rw (a ww or wr edge
  W -> W' would make W visible to Sv(y)).
- The window bullet covers two-transaction cycles; the read-only structure applies to a read-write S
  too: point to it rather than leaving it under "What is assumed".
- "What is assumed": Sp is one storage transaction only with a single JDBC storage; multi-storage gives
  several prepares, like two-phase commit.
- Section 7: the isolation table states "serializable if ..." without the two-phase commit assumption
  or the "argument, not a proof" caveat.
- Section 10 item 15: add DISTINCT and grouped reads to the per-table check shapes.
- References: the coordinator write is omitted for read-only transactions by default
  (`write_omission_on_read_only`); `isValidationRequired` is also false when every get is in the write
  set and there are no scans; `JdbcDatabase.get`/`scan` have no conflict mapping (todo 182).

## Proposed Solutions

### Option 1
Edit the sections as listed.

## Acceptance Criteria

- [ ] Each item above reflected in design.md.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.

### 2026-10-17 - Fixed

**By:** Claude Code
- Design 5: writers that change tx_id, prepares as the writers, `directly` is rw and why, the
  read-only structure for a read-write S, one prepare per storage; section 7 table carries the
  assumptions; item 15 adds grouped and DISTINCT reads; references: coordinator write omission,
  isValidationRequired's exception, JdbcDatabase get/scan without conflict mapping.
