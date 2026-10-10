---
status: complete
priority: p1
issue_id: "056"
tags: [code-review, security, architecture]
dependencies: []
---

# Prepared route not rechecked at execute

## Problem Statement

A `PreparedStatement` keeps the route chosen at prepare time; a later change of session schemas, catalog resolutions, passthrough flag or autocommit mode is never checked at execute.

## Findings

- `Proxies.java:129-136, 248-249`: execute reuses `prepared` without re-routing.
- Scenario: under `search_path = other` (no `t` there, or a plain `t`), `prepareStatement("SELECT * FROM t")`; then `setSchema("public")` where `public.t` is a ScalarDB table; `ps.executeQuery()` sends the text, PostgreSQL re-plans against the new path, and the ScalarDB table is read raw.
- Same for a DROP that unhides a table, or a table registered with ScalarDB after prepare (pooled statement caches keep prepared statements for hours).
- Passthrough: a statement prepared with passthrough on in autocommit still runs verbatim after passthrough is turned off or `setAutoCommit(false)`.
- The milestone 6 cache key in the plan omits the session schemas, the same defect at cache scope (architecture review).

## Proposed Solutions

### Option 1: Generation counters on Route

**Approach:** `Session` bumps a generation on every change; `Catalog` bumps one on `invalidateResolutions` and reload. `Route` records both plus passthrough and autocommit. On execute, if anything moved, route the original SQL again and refuse (0A000) if the answer differs.

**Pros:** Exact, cheap (two int compares per execute); same key serves milestone 6.

**Cons:** Refusing on change may surprise apps that change schema between prepare and execute; acceptable and documented.

**Effort:** Medium

**Risk:** Low

### Option 2: Re-route on every execute

**Approach:** Parse again each execute.

**Pros:** Simplest.

**Cons:** Parse cost per execute until milestone 6.

**Effort:** Small

**Risk:** Low

## Recommended Action

Done 2026-10-10. `Session` and `Catalog` carry generations; `Route` records them, the passthrough flag and its source text; `Router.current` routes a prepared statement again when anything moved, refusing if the answer differs (and if the rewritten text would differ from the prepared one). Tests: both finding056 cases. The milestone 6 cache key should include the session generation (todo 064).

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [x] Prepare under one path, change the path to make the name a ScalarDB table, execute: refused (integration test).
- [x] A passthrough-prepared statement executed after passthrough is off or outside autocommit is refused.
- [x] Plan milestone 6 cache key includes the session generation.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.
- Reproduced on PostgreSQL 16: the driver let the statement read the raw ScalarDB table; fixed, and the reproduction now ends in a refusal.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
