---
status: complete
priority: p2
issue_id: "059"
tags: [code-review, security, correctness]
dependencies: []
---

# Metadata result sets unwrapped

## Problem Statement

Result sets returned by `DatabaseMetaData` methods are the vendor's objects, so `getStatement()` and `getStatement().getConnection()` hand out vendor objects that run SQL without the router.

## Findings

- `Proxies.java:149-151, 263-290`: `ChildHandler` wraps nothing it returns.
- pgjdbc 42.7.11 (security review, bytecode): metadata queries run on a `PgConnection.createStatement()` statement, which `PgResultSet.getStatement()` returns. `getMetaData().getTables(...).getStatement().executeQuery("UPDATE st ...")` reaches the vendor directly.
- Contradicts the class Javadoc ("never holds a vendor object it did not ask for").

## Proposed Solutions

### Option 1: Wrap ResultSets in ChildHandler

**Approach:** Any `ResultSet` a forwarded call returns is wrapped with `getStatement()` returning null (allowed for metadata result sets).

**Pros:** Small, closes the escape.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. `ChildHandler` wraps any result set it returns, with a null statement; integration test asserts `getMetaData().getTables(...).getStatement()` is null.

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [x] `getMetaData().getTables(...).getStatement()` is null; no vendor object reachable (integration test).

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
