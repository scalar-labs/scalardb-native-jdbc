---
status: pending
priority: p2
issue_id: "063"
tags: [code-review, performance]
dependencies: []
---

# Session re read too often

## Problem Statement

Every SET (including `SET LOCAL statement_timeout`, `SET application_name`, `ALTER SESSION SET NLS_...`) and every OTHER statement marks the session stale, inside a transaction twice; a re-read is two queries on MySQL, MariaDB and SQL Server; and `setSchema` with an unchanged value re-reads anyway.

## Findings

- `Router.java:99`, `Session.java:41-59`, `Engine.java:280,348`, `Proxies.java:158-164`.
- About 2 extra round trips per transaction on PostgreSQL/Oracle and 4 on MySQL/MariaDB/SQL Server for a request that sets a timeout; schema-per-tenant with Hibernate calls `setSchema` on every acquire and release.

## Proposed Solutions

### Option 1: Mark stale only for variables Session reads

**Approach:** PostgreSQL `search_path` (and `set_config`, SET ROLE, SESSION AUTHORIZATION, RESET); MySQL/MariaDB `sql_mode`, USE; SQL Server `QUOTED_IDENTIFIER`, `ANSI_QUOTES`, EXECUTE AS; Oracle `CURRENT_SCHEMA`. Combine the two reads into one statement. Skip `changed` when `setSchema`/`setCatalog` repeat the current value.

**Pros:** Removes most re-reads.

**Cons:** An allow-list must stay complete per engine; unparseable statements still mark stale.

**Effort:** Medium

**Risk:** Medium

## Recommended Action

## Technical Details

- Commit under review: df9580b (milestone 1), diff f48bace..df9580b.
- Main files: `driver/src/main/java/com/scalar/db/nativejdbc/jdbc/Proxies.java`, `Router.java`, `Session.java`.

## Acceptance Criteria

- [ ] A `SET statement_timeout` does not cause a re-read (counting test).
- [ ] One query per re-read on every engine.

## Work Log

### 2026-10-10 - Code review of df9580b

**By:** Claude Code (/ce:review: security, correctness, architecture, performance, simplicity)

- Found and verified against the code at df9580b.

## Resources

- `docs/design.md` sections 1 and 4.1; `docs/plans/proxy-step.md` milestone 1.
