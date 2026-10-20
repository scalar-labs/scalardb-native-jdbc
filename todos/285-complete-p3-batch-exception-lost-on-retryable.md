---
status: complete
priority: p3
issue_id: "285"
tags: [code-review, oracle, correctness]
dependencies: []
---

# asRetryable turns a vendor BatchUpdateException into a plain SQLTransactionRollbackException

## Problem Statement

Proxies.java:877 maps the error of a plain-table batch through `Writes.asRetryable`; an ORA-08177
(or SQL Server 3960) in a batch comes back as SQLTransactionRollbackException 40001 without its
update counts, where a ScalarDB-table batch gives BatchUpdateException 40001.

## Proposed Solutions

1. Keep a BatchUpdateException: rebuild it with SQLState 40001 and its counts (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-20: found in the review of d18b58c (8d2), verified as stated above.
- 2026-10-20: fixed. asRetryable keeps a BatchUpdateException with its counts, SQLState 40001.
