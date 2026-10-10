---
status: pending
priority: p2
issue_id: "064"
tags: [code-review, architecture, plan]
dependencies: []
---

# Plan gaps for milestones 3 4 6

## Problem Statement

The proxy forwards PreparedStatement parameters straight to one vendor statement, which milestone 4 (several vendor statements per execute) cannot use; the plan has no per-transaction object and its milestone 6 cache key omits the session.

## Findings

- `Proxies.java:127-136`, `StatementHandler`: `setXxx` goes to the vendor statement. Milestone 3 fan-out (one ordinal to several positions) also has to buffer streams and readers.
- Milestone 4 runs a state read, the user DML, a CAS and a key loop per execute, so parameters must be recorded and replayed; `Route.sql` cannot express a plan.
- Anchors (M3), rollback-only and driver-opened autocommit transactions (M4) need a per-transaction object; `Session.changedInTransaction` is already one.
- Plan milestone 6 cache key: add the session generation (see 056).
- Plan package table puts the router in `route`; the code has it in `jdbc`.

## Proposed Solutions

### Option 1: Update the plan now, build in M3

**Approach:** Plan: M3 records parameter calls per ordinal and gives `Route` an executable plan; a small `Transaction` object in `jdbc` cleared by commit/rollback/setAutoCommit; M6 key includes the session generation; package table matches the code.

**Pros:** No speculative code now.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [ ] `docs/plans/proxy-step.md` updated for M3, M4 and M6.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
