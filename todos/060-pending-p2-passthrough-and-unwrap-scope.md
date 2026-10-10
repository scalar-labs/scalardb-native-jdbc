---
status: pending
priority: p2
issue_id: "060"
tags: [code-review, security, design]
dependencies: []
---

# Passthrough and unwrap scope

## Problem Statement

The passthrough flag outlives the code that set it, and `unwrap` hands out an unrestricted vendor connection that the design does not mention.

## Findings

- Pools: the flag lives on the physical connection; HikariCP resets autocommit, isolation, read-only, catalog, schema and network timeout on return but not client info, so the next borrower inherits passthrough (`Router.java:39`, `Proxies.java:184-204`).
- `setClientInfo(Properties)`: JDBC says keys missing from the set are cleared; a set without the key leaves passthrough on.
- Passthrough statements clear the shared resolution cache (see 062).
- `unwrap(PGConnection.class)` and the like return a full vendor `Connection`: unrouted SQL in any transaction mode, stronger than passthrough. Design section 1 "Architecture" is silent.

## Proposed Solutions

### Option 1: Narrow and document

**Approach:** Clear passthrough on `setAutoCommit(false)` and on `setClientInfo(Properties)` without the key; document that a pool must clear it (or the app must reset it before close); document `unwrap` as a deliberate escape hatch with the passthrough warning.

**Pros:** Keeps the escape hatches, makes their scope explicit.

**Cons:** Pool leakage still relies on the app.

**Effort:** Small

**Risk:** Low

### Option 2: Clear passthrough when the pool resets the connection

**Approach:** Hook `setSchema`/`setCatalog`/`setAutoCommit(true)`/`clearWarnings`.

**Pros:** Automatic.

**Cons:** Hikari calls these only when values changed: unreliable.

**Effort:** Small

**Risk:** Medium

### Option 3: Restrict unwrap to the proxy's own interfaces

**Approach:** Refuse vendor types.

**Pros:** Closes the hatch.

**Cons:** Breaks apps that need COPY or vendor APIs.

**Effort:** Small

**Risk:** Medium

## Recommended Action

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [ ] Design section 1 states the scope of passthrough and of `unwrap`.
- [ ] `setClientInfo(Properties)` without the key turns passthrough off.
- [ ] Pool test: a connection returned with passthrough on is not handed out with it on (or the doc says so).

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
