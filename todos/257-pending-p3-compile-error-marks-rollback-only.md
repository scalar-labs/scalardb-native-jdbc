---
status: pending
priority: p3
issue_id: "257"
tags: [code-review, sqlserver, correctness]
dependencies: []
---

# A failing first statement that opened no transaction marks the transaction rollback-only

## Problem Statement

The SQL Server branch of `endsTransaction` (Writes.java:693-698, from Proxies.java:842-847) reads
`@@TRANCOUNT` = 0 as "the transaction ended". A compile error (207) as the first statement with
autocommit off opens no implicit transaction, so the count is 0 and the next statement fails with
40001 "the transaction was aborted by an earlier statement". 2627/8134 as first statement leave it
at 1; 245 really ends it (handled right).

## Proposed Solutions

1. Only count it as ended if a transaction was open before the statement (track whether the
   session has run anything in this transaction) (small).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
