---
status: pending
priority: p2
issue_id: "062"
tags: [code-review, performance]
dependencies: []
---

# Resolution invalidation too broad and no single flight

## Problem Statement

Passthrough statements, TRUNCATE and every DDL clear the catalog's shared resolutions, and a miss after the clear has no single flight, so all connections queue on the one catalog connection.

## Findings

- `Router.java:70`: passthrough returns `Route(sql, true, true)`; the procedure-call refusal tells users to use passthrough, so a procedure per request clears the cache per request.
- `Router.java:100`: TRUNCATE never changes names; `Kind.DDL` includes CREATE INDEX, COMMENT, ADD COLUMN.
- `Catalog.java:417-431`: after `connectionLock.lock()` the cache is not checked again; C connections x T hot tables serialized round trips (about 0.5 s at 50 x 10 x 1 ms).
- DDL inside a transaction invalidates before its commit, so another connection can re-cache the pre-DDL answer until the TTL (architecture review).

## Proposed Solutions

### Option 1: Narrow and single-flight

**Approach:** Passthrough: `changesNames = false` (the TTL covers it; document). Set `changesNames` only for CREATE/DROP/ALTER-RENAME of table, view, synonym or schema. Re-check the cache after taking the lock, using a generation that `invalidateResolutions` bumps. When DDL runs outside autocommit, invalidate again at commit.

**Pros:** T round trips instead of C x T.

**Cons:** Passthrough DDL relies on the TTL; documented.

**Effort:** Small

**Risk:** Low

## Recommended Action

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [ ] Concurrent misses after an invalidation issue one query per name (unit test with a counting fake).
- [ ] TRUNCATE and passthrough do not invalidate.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
