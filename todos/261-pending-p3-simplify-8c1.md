---
status: pending
priority: p3
issue_id: "261"
tags: [code-review, sqlserver, simplification]
dependencies: []
---

# Simplify 8c1

## Problem Statement

From the simplicity pass (no behaviour change except the fixture):

1. `TableHints.tokensBetween`/`firstBetween` (147-165) re-lex the whole statement twice per
   clause from `SqlAst.hintedTable` (654-655); walk the parser's tokens as `startOfFirstAfter`
   (1234) does and delete both.
2. The hint whitelist is checked in Router (485-494) before every `classify`, so
   StatementShape 169-175 can't fail on the driver's route; keep one (keep 176-178).
3. `SqlAst.LOCKING_HINTS` (1391) public but used only at 1383; StatementShape 650-657 spells the
   same set again.
4. One-caller overload `onDataTable` (Writes 627-630); javadoc names the parameter `from`.
5. Writes 1695 `SQLSERVER ? " WHERE 1 = 0" : " WHERE FALSE"` → `" WHERE 1 = 0"` (as SplitRead 317).
6. Writes 1609 `boolean pg = !n.lookups()` is true on SQL Server too: rename.
7. `insertStamp` (2310-2319) relies on its caller's `WHERE` over alias `d`; build the clause in
   `stampFromData` (1313).
8. Redundant hand-written T-SQL ternaries in tests (WriteIntegrationTest 529-532, 937-940), since
   `Rows.rows` already translates.
9. `Rows.tsql` (82) silently drops hints when TABLE doesn't match; throw instead.
10. LOCK_MARK refusal duplicated (Router 755-758 and 797): hoist.
11. Comment wrapped mid-sentence at Writes 1801-1803.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of cc3a681 (8c1), verified as stated above.
