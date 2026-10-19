---
status: pending
priority: p3
issue_id: "265"
tags: [code-review, sqlserver, docs]
dependencies: []
---

# The SQL Server branch of inflightCount is unreachable; plan and commit claim it

## Problem Statement

`Router.inflightCount` (1513-1520) gained T-SQL, but its only caller (l.1020) is inside
`if (engine == MYSQL || engine == MARIADB)` (l.999). proxy-step.md (l.505-506) and the 8c2 commit
message say the counts are T-SQL now. Only `checks()` (l.1123) is live.

## Proposed Solutions

1. Drop the SQL Server ternaries and fix the plan's wording; add them in 8c3 if the coarse path uses
   them, with a shared limit helper next to `Writes.anyRow`.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 51e1c51 (8c2), verified as stated above.
