---
status: complete
priority: p1
issue_id: "070"
tags: [code-review, security, setup, correctness]
dependencies: []
---

# Set up check can pass wrongly

## Problem Statement

The check that marks a table set up can pass when it is not: it lower-cases names on case-sensitive engines, it counts a table whose backfill timed out, and it accepts any object with the right name.

## Findings

- `Engine.collect`, `Snapshot.marked`, `Setup.table` lower-case every key. On PostgreSQL, Oracle (quoted), MySQL with `lower_case_table_names=0` and case-sensitive SQL Server collations, a ScalarDB table `ns."Orders"` is marked set up by `ns.orders`'s `rc_orders`/`orders_inflight`, and `Setup` then skips creating its index (security F1, correctness, architecture).
- The view and index are created before the backfill; a backfill that times out (55000) still leaves both objects, so the next catalog load marks the imported table set up and the router accepts it, while the design says the backfill completes before the driver is enabled (security F2, architecture).
- Any view named `rc_T` and any index named `T_inflight` pass, whatever their definition; `CREATE OR REPLACE` silently replaces a user's view of that name. A failed `CREATE INDEX CONCURRENTLY` (if adopted, todo 074) leaves an invalid index `pg_indexes` still lists.

## Proposed Solutions

### Option 1: Exact names, view last, shape check

**Approach:** Compare exactly unless the snapshot is case-insensitive, with one key function shared by Engine, Catalog and Setup. Create the index, then backfill, then the view, so the view marks completion. Compare the view's columns with `dataColumns` at load (one more column in the listing) and refuse to replace a view whose columns differ. On PostgreSQL require `indisvalid`.

**Pros:** Closes all three; the view doubles as the completion marker.

**Cons:** A shape check per engine.

**Effort:** Medium

**Risk:** Low

### Option 2: Tag objects with a comment

**Approach:** PostgreSQL/Oracle COMMENT, SQL Server extended property, MySQL view comment is not available.

**Pros:** Explicit ownership.

**Cons:** Not uniform across engines.

**Effort:** Medium

**Risk:** Medium

## Recommended Action

Done 2026-10-10. `engine.SetupObjects` is the one check, used by the catalog and the setup: exact names unless the database ignores table-name case; index usable (PostgreSQL indisvalid, Oracle STATUS, SQL Server is_disabled); view columns equal to the table's. The setup creates index, then backfill, then view, and refuses to replace a foreign view. The three SetupReviewIntegrationTest cases pass.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] `ns."Orders"` next to a set-up `ns.orders` is not set up (integration, PostgreSQL).
- [x] A timed-out backfill leaves the table not set up.
- [x] A hand-made `rc_T` with other columns does not pass.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.
- Verification (2026-10-10): Reproduced on PostgreSQL 16: (a) after the failed backfill of 069 the table is still marked set up; (b) r70."Orders" is marked set up by r70.orders's rc_orders and orders_inflight; (c) a hand-made rc_t with other columns and any index named t_inflight count as the setup (SetupReviewIntegrationTest, three failing tests kept as regression tests).

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
