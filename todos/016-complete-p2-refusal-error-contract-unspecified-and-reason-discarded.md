---
status: complete
priority: p2
issue_id: "016"
tags: [code-review, usability, design, sql, driver]
dependencies: []
---

# Refusals have no specified SQLSTATE or exception type, and the reason is discarded before anything can report it

## Problem Statement

The design specifies driver-decided aborts precisely (`SQLTransactionRollbackException`, SQLSTATE `40001`), which is what an automated retry loop needs. Every other refusal is specified only as "a clear error": unsupported statement shapes, a table without its committed view or in-flight index at connect, `READ_UNCOMMITTED`, SQL Server without `READ_COMMITTED_SNAPSHOT`, an UPDATE naming a metadata column, a locking clause on an `rc_T` shape. A caller (application, framework or agent) must decide between three actions, retry the transaction, rewrite the statement, or stop and run operator setup, and only the first is distinguishable today. On top of that, the classifier throws away the reason for a refusal: `SqlAst.parse` and `StatementShape.of` return `Optional.empty()` for eight distinct causes, so the connection layer that will sit on top can only say "refused".

## Findings

Lines refer to commit 06c137f.

- `docs/design.md` section 7 specifies `SQLTransactionRollbackException` + `40001` for aborts; refusals at 4.1 (unsupported shapes, "never guess"), 3 (table without `rc_T`/index at connect), 1 (`READ_UNCOMMITTED`; SQL Server without RCSI; UPDATE naming a metadata column), 4.2 (locking clause on an `rc_T` shape) say only "a clear error".
- `sql/SqlAst.java:236-248`: `parse` returns `Optional.empty()` for both a parser exception and a walker failure, dropping the JSQLParser message.
- `sql/StatementShape.java:99-134`: `of` collapses eight causes into the same `Optional.empty()`: second statement, unknown nodes, several tables, CTE, three-part name, unresolvable qualifier, unsupported statement type, builder refusal. `hasSecondStatement` (`:138-156`) also swallows the parse error.
- `SqlAst.unknownNodes()` (`:389`) already computes the `table:` / `type:` findings that would make the message.
- The existing catalog refusals already use standard, actionable SQLSTATEs: `42000` (`Catalog.java:127-129`, `:159-161`) and `3F000` (`:203-204`); `connect` uses `08001`. These are the model to extend.
- Verified: by code reading; the `Optional.empty()` returns are unconditional.

## Proposed Solutions

### Option 1: An error table in the design mirrored by a constants class, plus a refusal reason carried out of the classifier

**Approach:** Add a short table to design.md (next to the abort contract in section 7) and a `SqlStates`/`Errors` constants class:
- `0A000` + `SQLFeatureNotSupportedException` for refused statement shapes and `READ_UNCOMMITTED` (never retry; rewrite the statement).
- `08004` + `SQLNonTransientConnectionException` for connect-time refusals (RCSI off, naming the `ALTER DATABASE` to run).
- One fixed state for "setup command not run on table T" (for example `42S02`, the ODBC base-table-or-view-not-found state MySQL and SQL Server already use), with the message naming the command.
- Keep `42000` and `3F000` as they are; keep `40001` for aborts.
Return the refusal reason with the empty result: a small result type (`Refusal` with a code and message) or a checked exception carrying the parser message and `unknownNodes()`, and put it in the `0A000` message.

**Pros:**
- A caller can key on SQLSTATE class: `40` retry, `0A` rewrite, `08`/`42S02` run setup.
- The reason is computed already; this only stops discarding it.

**Cons:**
- Changes the `Optional<StatementShape>` signature; every caller adapts (there are few today).

**Effort:** Medium (half a day including the design table)

**Risk:** Low

---

### Option 2: Design table only, reasons later

**Approach:** Write the SQLSTATE table into design.md now and implement the states in the connection layer when it is written; keep the classifier's `Optional.empty()` and attach a generic "statement shape not supported" message.

**Pros:**
- No classifier change now.

**Cons:**
- The refused statement cannot say why (unknown node type, two tables, CTE), so a user or agent rewriting it is guessing; the reason has to be threaded through later anyway.

**Effort:** Small

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `docs/design.md` section 7 (abort contract) and the refusal sites in 1, 3, 4.1, 4.2
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:236-248` - `parse`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:99-156` - `of` and `hasSecondStatement`
- A new constants class for SQLSTATEs under `com.scalar.db.nativejdbc`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:127-129,159-161,203-204` - existing states to reference from the constants class

**Related components:**
- Todo 017 (`getPropertyInfo` unchecked exception; "No suitable driver" message)
- Todo 015 (no URL in messages)
- Todo 011 (which node types are refused; their names belong in the message)
- Spring's `SQLExceptionTranslator` mapping noted in design.md 4.6

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` section 7 (`SQLTransactionRollbackException`, `40001`), 4.6 (Spring translation)
- Found by: agent-native-reviewer (P2-1, P3-2)

## Acceptance Criteria

- [ ] design.md contains a table of every refusal with its SQLSTATE, exception type and the action the caller should take (retry / rewrite / run setup)
- [ ] A constants class defines those states and every throw site uses it
- [ ] `StatementShape.of` and `SqlAst.parse` report the refusal reason (cause and, for unknown nodes, the node names) to the caller
- [ ] A refused statement surfaces as `SQLFeatureNotSupportedException` with SQLSTATE `0A000` and a message naming the cause
- [ ] Tests assert SQLSTATE and exception type, not only message text, for each refusal class
- [ ] Unit tests pass; integration tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by agent-native-reviewer (P2-1, P3-2) during the `/ce:review` run on commit 06c137f
- Severity set to P2: usability of the driver for frameworks and automated callers; no data risk

**Learnings:**
- The catalog already models the right pattern (standard SQLSTATE plus an actionable message); the rest of the driver should follow it

---

### 2026-10-09 - Status after 75106c2 (agent-native review)

**By:** Claude Code

**Actions:**
- The commit added about fifteen new silent refusal causes to StatementShape (table hints, TABLESAMPLE, PIVOT, SKIP LOCKED, NOWAIT, WAIT, INSERT IGNORE, ON DUPLICATE KEY, ON CONFLICT, OUTPUT, hints, "predicate not inspectable") and removed SqlAst.unknownNodes(), which this todo's Option 1 relied on; hasSecondStatement moved into SqlAst.parse, which still swallows the parser message. New direction: a Builder.refuse(String why) that records the clause and returns false, and of() returning the reason with the empty result; ModulePathCheck and StatementShapeTest should assert the reason.

### 2026-10-09 - Fix applied (plan milestone 0)

**By:** Claude Code

**Actions:**
- `sql.Outcome<T>` carries a value or the reason a statement was refused. `SqlAst.analyze(sql, engine, doubleQuotes)` and `StatementShape.classify(ast)` return it, and `parse`/`of` are wrappers over them; every refusal point of the parser and the shape builder states the clause that caused it (multi-statement text, a parse error with the parser's first line, an executable comment form, a database-qualified or dotted quoted name, a double-quoted name where the session reads a string, a table in an uninterpreted clause, a join, WITH, each modifier and clause of 036, SET (a, b), a foreign qualifier, FOR UPDATE OF another table).
- The error contract is design section 1, "Errors": 40001 SQLTransactionRollbackException, 0A000 SQLFeatureNotSupportedException, 42000 and 42501 SQLSyntaxErrorException, 08004 SQLNonTransientConnectionException, 08001; the catalog now raises the subclasses. The router raises 0A000 with the Outcome's reason from milestone 1.
- Tests: SqlAstTest.refusals_sayWhy, StatementShapeTest.refusals_sayWhy (ten shapes).

## Notes

- `0A000` ("feature not supported") is what pgjdbc and ojdbc use for unsupported JDBC features, so frameworks already translate it as non-transient.
