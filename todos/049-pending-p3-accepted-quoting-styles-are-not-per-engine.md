---
status: pending
priority: p3
issue_id: "049"
tags: [code-review, correctness, engine]
dependencies: []
---

# `Engine.resolveIdentifier` accepts all three quoting styles on every engine

## Problem Statement

`resolveIdentifier` unquotes `"x"`, `` `x` `` and `[x]` regardless of the engine. On MySQL and MariaDB without `ANSI_QUOTES`, `"active"` is a string literal, but JSQLParser parses it as a column and the driver then lists `active` as a WHERE column; on PostgreSQL, Oracle and MySQL `[x]` is not an identifier at all, so a rewrite could turn an engine-invalid statement into a valid one. Harmless today, because `isKeyEquality` fails closed on a column right-hand side, but the `before_` substitution of the read path will walk `whereColumns`.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:152-172`: the three delimiter pairs are accepted unconditionally; the constructor already carries `quoteOpen`/`quoteClose` per engine but only for `quote()`.
- `WHERE status = "active"` on MySQL: JSQLParser yields a `Column` named `"active"`; `resolveIdentifier` returns `active`; `StatementShape.whereColumns` contains it (architecture-strategist, by reading).
- The column-side check in `isKeyEquality` rejects it as a key term (the right-hand side is a `Column`), so no wrong key write results today.

## Proposed Solutions

### Option 1: Per-engine accepted delimiters

**Approach:** Make the accepted delimiter set a constructor constant per engine (PostgreSQL and Oracle `"`, MySQL and MariaDB `` ` `` plus `"` only when the proxy knows `ANSI_QUOTES` is on, SQL Server `[ ]` and `"`). An unaccepted delimiter means "not an identifier": the term is not a column, and the shape fails closed.

**Pros:**
- Matches each engine's lexer; removes the string-as-column case on MySQL.

**Cons:**
- MySQL `ANSI_QUOTES` is a session setting the proxy must read.

**Effort:** 2-3 hours

**Risk:** Low

---

### Option 2: Refuse the foreign styles outright

**Approach:** Keep the current unquoting but have `StatementShape` refuse any statement containing a quoted identifier in a style the engine does not use.

**Pros:**
- Fail-closed with no session dependency.

**Cons:**
- Refuses `"x"` on MySQL even with `ANSI_QUOTES` on.

**Effort:** 1-2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:152-172`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java` - where a non-identifier term should fail closed
- `driver/src/test/java/com/scalar/db/nativejdbc/engine/EngineTest.java:28-37` - currently asserts all three styles on every engine

**Related components:**
- Todo 043 (folding rule), todo 018 (unquoting escapes).

**Database changes:** None.

## Resources

- `docs/design.md` section 1, per-engine table ("Identifier quoting").
- Found by: architecture-strategist (P3).

## Acceptance Criteria

- [ ] `Engine.MYSQL.resolveIdentifier("\"active\"")` is not treated as an identifier unless `ANSI_QUOTES` is known to be on.
- [ ] `Engine.POSTGRESQL.resolveIdentifier("[x]")` is not treated as an identifier.
- [ ] `EngineTest` asserts the accepted styles per engine.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- architecture-strategist read the unconditional unquoting and traced the MySQL string-literal case to `whereColumns`.

**Learnings:**
- Quoting is an engine rule in both directions: how to emit and what to accept.

## Notes

- Low priority until the `before_` substitution exists; then it becomes a correctness item.
