---
status: complete
priority: p1
issue_id: "055"
tags: [code-review, security, correctness]
dependencies: []
---

# Batched statements never mark the session stale

## Problem Statement

`Statement.addBatch(sql)` routes each statement but skips `ran`, and `executeBatch` has no SQL and no prepared route, so a batched `SET` or DDL never marks the session stale or clears resolutions.

## Findings

- `Proxies.java:244-254`: `ran` is skipped for `addBatch`; `executeBatch`/`executeLargeBatch` take no String and `prepared` is null on a plain Statement, so `route` stays null.
- Scenario (security review): `addBatch("SET search_path = other"); executeBatch();` then `executeQuery("SELECT * FROM t")`. The session still holds `[public]`; if `public.t` does not exist, `t` resolves as plain and the vendor reads `other.t`, a ScalarDB table, unrewritten.
- Same with `addBatch("DROP TABLE a.t")` where `b.t` is a ScalarDB table further down the path: the cached plain resolution survives.
- Reported independently by all five reviewers.

## Proposed Solutions

### Option 1: Accumulate routes per statement

**Approach:** Keep a `List<Route>` (or OR'ed flags) in `StatementHandler`; on `executeBatch*` apply them in a `finally`; clear on `clearBatch`.

**Pros:** Exact; small.

**Cons:** None.

**Effort:** Small

**Risk:** Low

### Option 2: Refuse session/DDL kinds in addBatch

**Approach:** Only SELECT/DML may be batched.

**Pros:** Smallest.

**Cons:** Rejects legal JDBC usage.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. Statement wrapper keeps the routes of `addBatch(String)` and applies them when the batch runs (in a `finally`); `clearBatch` drops them. Test: finding055 in RoutingIntegrationTest.

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [x] A batched `SET search_path` followed by an unqualified name resolves the new way (integration test).
- [x] A batched DDL clears resolutions.
- [x] `clearBatch` drops pending routes.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.
- Reproduced on PostgreSQL 16: the driver let the statement read the raw ScalarDB table; fixed, and the reproduction now ends in a refusal.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
