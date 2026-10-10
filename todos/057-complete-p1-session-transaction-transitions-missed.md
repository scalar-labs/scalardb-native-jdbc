---
status: complete
priority: p1
issue_id: "057"
tags: [code-review, security, correctness]
dependencies: []
---

# Session transaction transitions missed

## Problem Statement

Three transitions that undo or apply a session change go unseen: `setAutoCommit(true)` mid-transaction (implicit commit), `rollback(Savepoint)` (treated as the end of the transaction), and a statement that fails after changing the session.

## Findings

- `Proxies.java:152-181`: no `setAutoCommit` case. Scenario: `setAutoCommit(false); SET LOCAL search_path = other;` a statement (re-read gives `[other]`), then `setAutoCommit(true)`. PostgreSQL reverts the path; the driver keeps `[other]` with `stale = false`, so an unqualified ScalarDB table can resolve as plain. The integration test rolls back before `setAutoCommit(true)`, so it misses this.
- `Proxies.java:165-171`: `case "rollback"` matches `rollback(Savepoint)` too and clears `changedInTransaction`; a `SET LOCAL` before the savepoint is then forgotten at the real commit. `releaseSavepoint` is not considered.
- `Proxies.java:251-254`: `ran` runs only after `forward` returns, so a `BatchUpdateException` with entries applied, or a DDL/SET that fails after partial effect, leaves the session trusted. And if `ran` itself throws after a successful vendor call, the caller sees a failure for a statement that ran.
- Reported by correctness, architecture and security reviews.

## Proposed Solutions

### Option 1: Intercept and mark before forwarding

**Approach:** Mark the session stale (and record the in-transaction change) before forwarding a session-changing statement; it is lazy, so it costs nothing. Intercept `setAutoCommit`: when switching false to true, call `transactionEnded()`. For `rollback(Savepoint)` set stale only, keep `changedInTransaction`.

**Pros:** Covers all three; no extra round trips.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. `setAutoCommit` intercepted (false to true ends the transaction for the session); `rollback(Savepoint)` marks stale but keeps the in-transaction record; `ran` runs in a `finally` and never throws (`Session.changed` treats an unreadable autocommit as a transaction). Tests: both finding057 cases.

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [x] `setAutoCommit(false); SET LOCAL ...; stmt; setAutoCommit(true)` then an unqualified name resolves to the reverted path (integration test).
- [x] `rollback(sp)` then `commit()` re-reads.
- [x] A failing session statement still marks stale.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.
- Reproduced on PostgreSQL 16: the driver let the statement read the raw ScalarDB table; fixed, and the reproduction now ends in a refusal.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
