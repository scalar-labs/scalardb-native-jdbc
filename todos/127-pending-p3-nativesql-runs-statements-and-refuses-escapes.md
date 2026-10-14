---
status: pending
priority: p3
issue_id: "127"
tags: [code-review, jdbc-api, routing]
dependencies: []
---

# nativeSQL runs statements on the connection and throws where it used to translate

## Problem Statement

Since 9a00150 `Connection.nativeSQL` calls `router.route()`, the same path as running a statement. For a
ScalarDB read it runs the in-flight COUNT and `getPrimaryKeys` (MySQL/MariaDB) or `EXPLAIN
(GENERIC_PLAN)` under a savepoint (PostgreSQL) on the application's connection, and updates the count
hysteresis and the route cache. It also throws 0AN01 for text the vendor would translate.

## Findings

- `Proxies.java:183-185`; Router `:549`, `:589`, `:500-525`.
- Inside an aborted PostgreSQL transaction `setSavepoint` fails and `parallelPlain` caches `false` for
  that text for good, so later long reads of it run with 0 workers.
- `nativeSQL("{call p(?)}")`, `"{? = call f(?)}"`, `"RELEASE SAVEPOINT s"` throw "cannot be parsed";
  in passthrough mode with autocommit off it throws too.

## Proposed Solutions

### Option 1
- For refused, passthrough or plain statements, return the vendor's `nativeSQL(sql)`.
- For split reads, route without probes: use a cached route if present, else decide with counting and
  EXPLAIN off.
- **Effort:** Small. **Risk:** Low.

## Recommended Action

## Acceptance Criteria

- [ ] `nativeSQL` runs no statement on the connection and translates JDBC escapes as the vendor does.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of 9a00150; verified by a probe.
