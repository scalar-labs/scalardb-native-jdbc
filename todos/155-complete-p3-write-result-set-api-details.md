---
status: complete
priority: p3
issue_id: "155"
tags: [code-review, jdbc, api]
dependencies: []
---

# Result-set API details of 4c: closeOnCompletion, getGeneratedKeys, FOR UPDATE OF a table name, messages

## Problem Statement

Small departures from the JDBC contract or from pgjdbc found in the 4c review.

## Findings

- `closeOnCompletion()` never closes the statement after a write's or locking read's result set is
  closed: the rows come from a driver statement (write reviewer, verified, `stmtClosed=false`).
- `getGeneratedKeys()` after `execute("INSERT ... RETURNING x")` without asking for keys returns the
  RETURNING rows, the same object as `getResultSet()`; pgjdbc returns an empty result set.
  `getMoreResults` closes that shared object (write reviewer, verified).
- `SELECT x FROM wb.imp WHERE id = 1 FOR UPDATE OF imp` (unaliased, schema-qualified table named in
  OF) is refused with 0AN01 "cannot be rewritten"; the aliased form works (locking reviewer; cause
  likely the OF target being replaced by the derived table, not confirmed).
- Keys asked of a locking read are refused with a message about UPDATE or DELETE.
- A precheck 55P03 leaves the transaction usable, unlike native (benign).

## Proposed Solutions

### Option 1
Close the statement proxy when its write result closes and `closeOnCompletion` is set; return
`writeResult` from `getGeneratedKeys` only when keys were asked; keep the OF target's name when the
table is replaced; word the refusal for any non-INSERT.

## Recommended Action


## Acceptance Criteria

- [x] Each item behaves as pgjdbc does, or is refused with a fitting message.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of d7be4f0 and cdfc58a (milestone 4c).

### 2026-10-15 - Fixed

**By:** Claude Code
- Closing a driver's rows closes the statement under closeOnCompletion; getGeneratedKeys returns rows only when keys were asked; FOR UPDATE OF a table renamed with its own name as alias is accepted; the keys refusal says INSERT only. Tests for the first three.
