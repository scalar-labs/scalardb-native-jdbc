---
status: pending
priority: p3
issue_id: "067"
tags: [code-review, testing]
dependencies: []
---

# Several RoutingIntegrationTest cases do not prove what their names claim

## Problem Statement

Several RoutingIntegrationTest cases do not prove what their names claim.

## Findings

- `readUncommitted_isRefused_andTheDefaultIsReadCommitted`: PostgreSQL defaults to READ COMMITTED, so it passes without the connect-time setting; needs MySQL/MariaDB or an `ALTER ROLE ... SET default_transaction_isolation`.
- `aPoolOverTheDriver_behavesTheSame`: the `SET search_path` has no effect (all names qualified) and `rs.next()` is not asserted; should query unqualified `s` on reused connections.
- `plainTables_workAsWithoutTheDriver` deletes and counts shared `m1.p`; flaky under JUnit parallelism.
- JOIN/subquery/SET-subquery refusals test SqlAst walking, already covered in SqlAstTest; keep one case per proxy entry point.
- `PG.open()` called twice in setup.

## Proposed Solutions

### Option 1: Tighten

**Approach:** Run the isolation test on MySQL; make the pool test resolve an unqualified name; use per-test tables.

**Pros:** Tests prove their claims.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [ ] Each test fails if the behaviour it names is removed.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
