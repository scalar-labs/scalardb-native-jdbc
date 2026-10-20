---
status: pending
priority: p3
issue_id: "267"
tags: [code-review, sqlserver, simplification]
dependencies: []
---

# Simplify 8c2, and docs

## Problem Statement

1. The 3-argument `withMeta` (Writes 2242-2245) only forwards; let the 2-argument call the 4-argument.
2. Vendor→driver level mapping in three places: `Router.driverLevel`, the SERIALIZABLE check in
   Proxies getTransactionIsolation (272-280, comment l.271 omits SNAPSHOT), `Router.ran`'s expected
   logic (1785-1795): one Router method from a vendor level and the current isolation.
3. Proxies:257 `vendorLevel != level && level == RR` is `vendorLevel == SQLSERVER_SNAPSHOT`.
4. Writes:1997 comment says SNAPSHOT, but the metadata-row hint applies to every SQL Server locking
   read, READ COMMITTED included (NOWAIT under READPAST too): reword, and note the change in design.
5. design.md 1473-1475 still says the translation of REPEATABLE READ comes with milestone 8; lines
   1472 and README 116 run past 100 columns.

The BatchUpdateException concern was checked: mssql-jdbc throws a plain SQLServerException for a
3960 in executeBatch, so nothing is lost.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 51e1c51 (8c2), verified as stated above.
- 2026-10-20: partly fixed. 1 (withMeta), 4 (comment) done; 3 and 5 were done in 8c3. Open: 2, the vendor-to-driver level mapping in one Router method.
