---
status: complete
priority: p3
issue_id: "126"
tags: [code-review, reliability, app-server]
dependencies: ["008"]
---

# The shared parser threads inherit the creating thread's context classloader

## Problem Statement

`SqlAst.PARSER` (a7ea1c1) creates threads with `new Thread(r, name)`. They inherit the context
classloader and inheritable thread-locals of whichever thread first parsed. With the driver in an app
server's shared lib, a thread created on a webapp request pins that webapp's classloader after undeploy
(Metaspace leak), and Tomcat warns that the thread was not stopped. Nothing shuts the pool down on
deregister.

## Findings

- Interrupts and timeouts are handled by JSqlParser; pool threads never parse recursively (no
  deadlock); each parse has its own parser instance. Only the classloader pinning is a problem.

## Proposed Solutions

### Option 1
- `new Thread(null, r, name, 0, false)` and `setContextClassLoader(SqlAst.class.getClassLoader())`;
  daemon. Optionally shut the pool down on deregister.
- **Effort:** Small. **Risk:** Low.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] Parser threads carry the driver's classloader and no inherited thread-locals.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of a7ea1c1 (reading the code).

### 2026-10-14 - Fixed

**By:** Claude Code
- Parser threads are created with no inherited thread-locals and the driver's class loader as their
  context class loader.
- No test: the cached pool reuses an idle thread, so a test cannot choose which thread creates one.
  Shutting the pool down on deregister is left out; its threads are daemons that end when idle
  (60 s).
