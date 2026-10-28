---
status: pending
priority: p2
issue_id: "359"
tags: [batch, sqlserver, update-counts, flaky-test]
dependencies: []
---

# SQL Server: a batch INSERT with a duplicate late in it reported 15 counts, not 18, once

## Problem Statement

`MoreWritesIntegrationTest.batches` (via `batchesKeysWaitsAndLocks`), SQL Server, on main at
43e0573 with todo 358's test change in the working tree (2026-10-29, Oracle, MariaDB and SQL Server
three containers at a time): in a transaction, a prepared INSERT batch of 20 entries whose 19th
(index 18, id 78 as 45) duplicates a key. The `BatchUpdateException` carried 15 update counts, all 1,
where the test expects the 18 entries before the duplicate. The scenario's two reruns on SQL Server
alone passed, and so did the full SQL Server task at 5de26b9.

## Findings

- 15 = 20 - 5: as if the batch ran in blocks and the failing block's entries before the duplicate
  were not counted, instead of the entries being taken one by one to the failing one. Not checked.
- JDBC lets a driver stop at the failing entry or go on, but the driver's contract here
  (`Proxies`, the batch's Javadoc near line 1491) is the counts of the entries before it; a count
  that varies by run would tell an application wrongly how many of its entries were applied.
- The batch paths changed recently: d10c269 (todo 334 A, the INSERT's bulk blocks) and 9cac28d
  (inline batches through the vendor's batching). Which of them SQL Server takes for this table is
  not checked.

## Proposed Solutions

1. Find the path that produced 15: loop the scenario on SQL Server under load with the batch's
   path logged, then fix the count where the failing block is not taken one by one.

## Recommended Action

Reproduce first, as above.

## Acceptance Criteria

- [ ] The path found, and the counts of a failing batch fixed to the entries before the failing one
- [ ] `batches` stable on SQL Server under the full suite

## Work Log

### 2026-10-29

- Seen once while checking todo 358 (the test at MoreWritesIntegrationTest.java:855); two reruns
  passed.
