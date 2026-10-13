---
status: complete
priority: p3
issue_id: "115"
tags: [code-review, correctness, api]
dependencies: []
---

# ScalarDbTables.kind throws NullPointerException on a null schema

## Problem Statement

`conn.unwrap(ScalarDbTables.class).kind(null, "t")` throws a raw NPE from `engine.quote(null)`
(`Proxies.java:153`, `Router.java:547`). A null schema, meaning "the session's schemas", is a
likely call on the driver's public interface. Added in 7c000b5 (the shell session's work); found by the security
reviewer.

## Findings

- `jdbc/Router.java` `kind`.

## Proposed Solutions

### Option 1
Treat a null schema as unqualified (`lookup` takes null), or throw an `SQLException`. **Effort:**
Small.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] `kind(null, "t")` classifies the table through the session's schemas.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".

### 2026-10-14 - Fixed

**By:** Claude Code
- A null schema is an unqualified name, found through the session's schemas, as in a statement;
  `ScalarDbTables.kind` documents it. Test in `RoutingIntegrationTest.scalarDbTables_classifiesByStoredName`.
