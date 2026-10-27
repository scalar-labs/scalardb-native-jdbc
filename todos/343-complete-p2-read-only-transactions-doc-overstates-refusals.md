---
status: pending
priority: p2
issue_id: "343"
tags: [code-review, docs, transactions]
dependencies: []
---

# transactions.md: read-only refusals stated as general, true only at SERIALIZABLE on MySQL, MariaDB, SQL Server

## Problem Statement

- "It runs reads only. A write of any table, SQL Server's SELECT ... INTO, any other kind of statement, and
  passthrough are refused with 25006" holds only when `Router.readOnlySerializable()` (read-only, SERIALIZABLE,
  locking engine). Elsewhere the driver refuses writes of ScalarDB tables only (Writes.java ~6025).
- "setReadOnly fails with 25001 once the transaction has run a statement": only when the value changes
  (Proxies.java ~415).
- On MySQL a locking read of a ScalarDB table in a read-only transaction is refused with 25006 (Writes.java ~6015);
  not documented in errors.md or transactions.md.
Accuracy review items 2, 8, 9; confirmed in the code.

## Proposed Solutions

1. Rewrite "Read-only transactions" per level and engine, and the 25006/25001 rows of errors.md to match.

## Recommended Action

## Acceptance Criteria

- [ ] Each stated refusal matches the code path that raises it.

## Work Log

- 2026-10-27: found in the review of the user-facing docs (ce:review: an accuracy reviewer against the code and the
  design, and a first-time-user reviewer).
