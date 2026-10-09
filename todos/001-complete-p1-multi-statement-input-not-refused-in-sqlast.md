---
status: complete
priority: p1
issue_id: "001"
tags: [code-review, security, correctness, sql]
dependencies: []
---

# Multi-statement input is not refused in `SqlAst.parse`; the second statement is dropped or forwarded unrewritten

## Problem Statement

`SqlAst.parse` accepts text that contains more than one statement and silently models only the first one. The only guard against this, `hasSecondStatement`, lives in `StatementShape.of(String)` and is not reached from `SqlAst.parse` or from `StatementShape.of(SqlAst)`. Every route that consumes `SqlAst` directly (the planned `rc_T` read route, the pass-through route of design.md 4.1) therefore classifies `a; b` by `a` alone.

This matters because the pass-through route forwards a statement verbatim when it touches no ScalarDB table. `select 1 from plain; delete from scalardb_table` is classified plain-only, pgjdbc and mssql-jdbc execute both statements, and the DELETE reaches a ScalarDB table without the rewrite that the protocol depends on. On the rewrite route the second statement is dropped without any error.

## Findings

- `SqlAst.parse` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:236-278`) calls `CCJSqlParserUtil.parse`, which returns only the first statement of `a; b`.
- `StatementShape.of(SqlAst)` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:112`) has no multi-statement guard.
- The only guard, `hasSecondStatement` (`StatementShape.java:143-156`), runs from `StatementShape.of(String)` only (`StatementShape.java:99-104`).
- **Verified** against the built classes: `insert into t (id) values (1); delete from u` gives `tableRefs=[t]`, `unknownNodes=[]`, `StatementShape.of(SqlAst)` = INSERT, and `joinTokens` emits `insert into t(id) values(1);` with the second statement silently dropped. `select * from t; drop table u -- x` behaves the same way.
- `hasSecondStatement` also calls `parseStatements` whenever a `;` appears anywhere in the text, including inside a literal or comment (about +300 µs and one more platform thread per call), and reports any `parseStatements` failure as "second statement".
- The class's own contract ("touch nothing unless everything is understood") is violated by design on the `SqlAst` route.

## Proposed Solutions

### Option 1: Parse once with `parseStatements` in `SqlAst.parse` and refuse when more than one statement is present

**Approach:** Replace `CCJSqlParserUtil.parse` with `CCJSqlParserUtil.parseStatements(sql)` (available in JSQLParser 5.4). Return `Optional.empty()` unless `getStatements().size() == 1`; model the single statement. Delete `hasSecondStatement` and its second parse from `StatementShape`.

**Pros:**
- One place guards every consumer of `SqlAst`.
- One parse instead of two; removes 14 lines; `;` inside a literal or comment no longer triggers a second parse.
- `parseStatements` costs about the same as `parse` (~97 µs vs ~99 µs measured).

**Cons:**
- `parseStatements` tolerates a trailing `;`; confirm that a lone trailing `;` still yields one statement and is accepted.

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 2: Keep `parse`, then check the token chain after the statement's last token

**Approach:** After `CCJSqlParserUtil.parse`, walk from the root's `jjtGetLastToken()` and refuse unless the next token is EOF or a lone `;` followed by EOF. Delete `hasSecondStatement`.

**Pros:**
- No second parse at all.
- Keeps the existing single-statement parse path.

**Cons:**
- Depends on JJTree token-chain details (comments as special tokens, the trailing `;` token) and needs careful tests.
- Slightly more code than Option 1.

**Effort:** 2-3 hours

**Risk:** Medium

---

### Option 3: Add the existing `hasSecondStatement` check to `SqlAst.parse` as well

**Approach:** Call the current guard from `SqlAst.parse` and from `StatementShape.of(SqlAst)`.

**Pros:**
- Smallest diff.

**Cons:**
- Keeps the extra parse and the `;`-in-literal false trigger; duplicates the guard in two classes.

**Effort:** 30 minutes

**Risk:** Low, but leaves the performance and duplication problems in place.

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:236-278` - `parse`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:99-104, 112, 143-156` - `of(String)`, `of(SqlAst)`, `hasSecondStatement`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java` - new tests
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java` - move or delete the second-statement tests

**Related components:**
- The pass-through route and the `rc_T` read route of design.md 4.1 and 4.2, which will consume `SqlAst` directly.
- Todo 008 (statement cache and parse count) benefits from the single parse.

**Database changes:** None.

## Resources

- `docs/design.md` section 4.1 (routing; "a statement with no ScalarDB table is forwarded verbatim") and section 1 ("Placeholders").
- Found by: feature-dev:code-reviewer (P1-1), security-sentinel (P1-2), performance-oracle (P1-1 item 2, P3-1), code-simplicity-reviewer (P1-1).

## Acceptance Criteria

- [ ] `SqlAst.parse("insert into t (id) values (1); delete from u")` returns empty.
- [ ] `SqlAst.parse("select * from t; drop table u -- x")` returns empty.
- [ ] `SqlAst.parse("select * from t;")` (lone trailing semicolon) returns a statement.
- [ ] `SqlAst.parse("select * from t where a = 'x; y'")` returns a statement and performs only one parse.
- [ ] `StatementShape.of(SqlAst)` cannot be reached with a multi-statement AST.
- [ ] `hasSecondStatement` is deleted or no longer the only guard; the String overload has no guard the `SqlAst` overload lacks.
- [ ] Tests for multi-statement input live in `SqlAstTest`.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found independently by feature-dev:code-reviewer, security-sentinel and performance-oracle; the code-simplicity-reviewer proposed the same fix (parse once with `parseStatements`).
- Verified by running the built classes: the second statement is dropped by `joinTokens` and never seen by `StatementShape.of(SqlAst)`.
- Measured the cost of the current double parse (performance-oracle).

**Learnings:**
- The guard belongs in the one function every consumer routes through, `SqlAst.parse`.
- `parseStatements` is not more expensive than `parse` in JSQLParser 5.4.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- Fixed in SqlAst.parse: CCJSqlParserUtil.parseStatements, empty unless exactly one statement; hasSecondStatement and its second parse removed from StatementShape. Tests: parse_returnsEmptyUnlessTheTextIsExactlyOneStatement (two statements, ';;', trailing ';', trailing comment, ';' in a literal).
- Verified: unit tests 101/101, full `./gradlew check` green (SpotBugs, Error Prone, javadoc, PostgreSQL integration test), and the live reproduction probe no longer reproduces.

**Learnings:**
- The probe that found this is a cheap regression guard; its cases are now in the unit tests.

## Notes

- Related: todo 008 (one parse per statement, statement cache) and todo 016 (surfacing the refusal reason).
