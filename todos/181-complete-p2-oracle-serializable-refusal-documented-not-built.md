---
status: complete
priority: p2
issue_id: "181"
tags: [code-review, docs, jdbc, oracle]
dependencies: []
---

# The README and design say Oracle refuses SERIALIZABLE, which is not built yet

## Problem Statement

README (Isolation levels, from 7c7a4a3) and design.md section 5 and the errors table say
`setTransactionIsolation(TRANSACTION_SERIALIZABLE)` throws 0AN01 on Oracle and
`supportsTransactionIsolationLevel` reports it unsupported; design 5 also says REPEATABLE READ is
translated to SNAPSHOT on SQL Server and to SERIALIZABLE on Oracle. None of it is in the code
(Proxies refuses only READ_UNCOMMITTED; 64a3ceb changed docs only; the plan puts it in milestone 8).
Today the level is accepted and each ScalarDB-table statement is refused with 0AN01. The README's
"any other combination ... is refused" is per statement, not at setTransactionIsolation.

## Proposed Solutions

### Option 1
Reword the README and design to the current behaviour, marking the refusal and the translation as
milestone 8.

### Option 2
Build the refusal now (setTransactionIsolation and the metadata proxy), leave the translation to 8.

## Acceptance Criteria

- [ ] README and design describe what the code does today, or the code does what they say.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.

### 2026-10-17 - Fixed

**By:** Claude Code
- README says what the code does today: READ_UNCOMMITTED refused; SNAPSHOT and SERIALIZABLE accepted
  off PostgreSQL with every ScalarDB-table statement refused; Oracle's refusal still to come. Design 5
  opens with what is built; the errors table and section 10 item 15 mark Oracle's refusal as milestone 8
  (Option 1).
