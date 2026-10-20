---
status: pending
priority: p3
issue_id: "274"
tags: [code-review, sqlserver, serializable, simplification]
dependencies: []
---

# Simplify 8c3

## Problem Statement

1. Router.byName + the SQL Server check loop (1205-1235) duplicate `checks()` (1125, TreeSet order):
   give `checks()` a per-table hint; delete byName (~20 LOC).
2. Writes 345-357 (two ternaries on one flag) and 1250-1254 (three-way ternary) repeat Router's
   share/REPEATABLEREAD choices (lockingRead ~775, ~789): one per-engine helper.
3. `holdingEveryTable`'s insert-sort-apply tail copies `lockingEverySelect`'s (1158-1163); its alias
   walk re-implements `hintedTable`'s (extends 261.1).
4. Router.ran's SQL Server branch (1855-1891) is the MySQL branch with another regex and re-apply:
   one branch (extends 267.2).
5. SnapshotIntegrationTest.sqlServerPlainTables mostly copies plainTables (~35 LOC).

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 5acbf90 (8c3), verified as stated above.
- 2026-10-20: partly fixed. 1 (byName folded into checks), 2 (Writes.shareLock), 3 (shared insert tail) done. Open: 4 (Router.ran branches), 5 (test duplication).
