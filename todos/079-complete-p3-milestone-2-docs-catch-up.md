---
status: complete
priority: p3
issue_id: "079"
tags: [code-review, docs]
dependencies: []
---

# Milestone 2 docs catch up

## Problem Statement

The plan and design still describe the milestone 2 that was planned, not the one built.

## Findings

- Plan milestone 2 bullets still say `testing.Setup`, promotion to a `tools` module and `08004`; the package table lacks `setup`; milestone 9 still moves Setup to `tools`.
- Design: the `08004` row's "or run the setup" was removed, check the remaining text; the Oracle example `RC_ORDERS` holds only for an upper-case stored name (a table created through ScalarDB with a quoted lower-case name gives `RC_made`).
- The other drivers see a newly set-up table only after the catalog TTL; say so in the setup's report and the design.

## Proposed Solutions

### Option 1: Edit

**Approach:** Update the bullets, package table, milestone 9 and the design examples.

**Pros:** Docs match the code.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. Plan milestone 2 bullets, test line, package table and milestone 9 updated; design section 3 describes order, check, naming examples, teardown, TTL visibility.

## Technical Details

- Commit under review: 5f95267 (milestone 2), diff e297037..5f95267.
- Main files: `setup/Setup.java`, `engine/Engine.java`, `catalog/Catalog.java`, `catalog/TableInfo.java`, `SetupIntegrationTest`.

## Acceptance Criteria

- [x] Plan and design describe the code as built.

## Work Log

### 2026-10-10 - Code review of 5f95267

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and checked against the code at 5f95267.

## Resources

- `docs/design.md` sections 1, 3 and 4.2; `docs/plans/proxy-step.md` milestones 2, 3 and 9.
