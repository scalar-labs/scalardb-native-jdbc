---
status: pending
priority: p2
issue_id: "332"
tags: [code-review, passthrough, safety, coordinator]
dependencies: ["314", "323"]
---

# A configured coordinator namespace, or its table named alone, is not protected

## Problem Statement

Passthrough and statements the parser cannot read (todo 314) are refused when their words name
ScalarDB's coordinator namespace. The driver protects only ScalarDB's default name, `coordinator`
(`Router.COORDINATOR_NAMESPACE`). ScalarDB lets a deployment configure another one. The coordinator
table's own name, `state`, is not protected either, so where the session's default schema is the
coordinator namespace, `FROM state` names it unqualified.

## Findings

From the review of todo 314 (read in the code, not run against a database):

- `Router.java:381`, `:581`: the one name added is `coordinator`.
- With a configured namespace `mycoord`, an unparsed `SELECT ... FROM mycoord.state ...` (any form the
  parser cannot read) is sent as written.

## Proposed Solutions

### Option 1: read the configured namespace
Take the namespace from the ScalarDB properties the driver is given, and protect it with `state`
qualified by it. **Effort:** Small.

### Option 2: also protect the word `state` alone
It errs the safe way, but refuses any unparsed statement with a column or literal `state`.
**Effort:** Small.

## Recommended Action

## Acceptance Criteria

- [ ] A configured coordinator namespace is refused in passthrough and unparsed statements.
- [ ] Design 4.1 (passthrough row) and 4.7 name the rule.

## Work Log

### 2026-10-25 - Found in the review of todo 314

**By:** Claude Code
- Read in `Router.protectedNames`.
