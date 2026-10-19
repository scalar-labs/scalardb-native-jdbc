---
status: pending
priority: p3
issue_id: "279"
tags: [code-review, oracle, docs]
dependencies: []
---

# Docs and comments stale after 8d1

## Problem Statement

- design.md 4.7 (l.1423-1427) says Oracle NOWAIT, WAIT n, SKIP LOCKED are carried to a separate
  base-table FOR UPDATE and OF col refused; the code keeps the clause in place, accepts NOWAIT, SKIP
  LOCKED and OF col, refuses WAIT.
- proxy-step.md 8d1 "As planned": FOR SHARE in tests is inline per test, not through the fixture.
- Writes comments: keyViolated javadoc (1614) names pgjdbc and MySQL only; InsertPlan fields (1409,
  1412) say MySQL/AUTO_INCREMENT; columns() javadoc (240) — the tag now marks identity columns too
  (rename it, e.g. " generated"); "IGNORE also turns data errors into warnings" (2417) above the
  Oracle hint branch.

## Recommended Action

## Acceptance Criteria

- [ ] Fixed, with a test that fails without the fix
- [ ] Full check passes on all engines

## Work Log

- 2026-10-19: found in the review of 2ce2755 (8d1), verified as stated above.
