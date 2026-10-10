---
status: pending
priority: p3
issue_id: "066"
tags: [code-review, quality, simplicity]
dependencies: []
---

# Proxies and router simplifications

## Problem Statement

Proxies dispatches through a `boolean[] handled` out-parameter repeated in three handlers, duplicates the prepareStatement/prepareCall blocks, and reaches into its owner's fields; several small cleanups apply.

## Findings

- `Proxies.java:76-101, 118-122, 232-236, 277-281`: `common()` with `boolean[]`; an abstract `Handler` base with `invoke` handling equals/hashCode/toString/unwrap/isWrapperFor and delegating `handle(...)` removes it.
- `Proxies.java:127-148`: `prepareStatement`/`prepareCall` identical except the class literal; use `method.getReturnType()`.
- `Proxies.java:245, 253`: `owner.router`, `owner.vendor`; give `ConnectionHandler` `route(sql)`/`ran(route)` (where 056's check belongs).
- `Proxies.java:192-200`: `setClientInfo(Properties)` copy loop; clone and remove.
- `Kind.SESSION` is unused by `Router` (everything not SELECT/DML counts); use it once 063 narrows the rule.
- `Errors` is one method; keep only if milestone 2 adds factories.
- `Proxies.PASSTHROUGH` belongs with the other `scalardbnative.*` keys in `DriverProperties`.
- Error table in design section 1 limits 0A000 to ScalarDB statements and READ UNCOMMITTED; the code also uses it for parse refusals, SET autocommit, procedure calls and passthrough outside autocommit. Widen the row; consider 25000 for passthrough outside autocommit. DDL on a ScalarDB table says "not supported yet" where design 4.1 makes it passthrough-only for good.

## Proposed Solutions

### Option 1: Apply with 055-057

**Approach:** The handler base class lands naturally with the batch and transition fixes.

**Pros:** Less code.

**Cons:** None.

**Effort:** Small

**Risk:** Low

## Recommended Action

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [ ] No `boolean[]` dispatch.
- [ ] Design error table matches the code.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
