---
status: complete
priority: p1
issue_id: "034"
tags: [code-review, security, correctness, sql]
dependencies: []
---

# Comment forms that JSQLParser treats as comments but the engines execute carry text past the classifier and through the rewrite

## Problem Statement

`SqlAst` decides what a statement does from the parser's tree and copies every comment token verbatim into the emitted statement. Three comment forms are comments to JSQLParser 5.4 but executable text to an engine: MySQL/MariaDB executable comments `/*! ... */` and `/*M! ... */`; a `--` not followed by whitespace, which MySQL/MariaDB do not treat as a comment; and nested block comments, which JSQLParser and PostgreSQL nest but MySQL, MariaDB and Oracle end at the first `*/`. Through them, a statement classified as a key write on a plain table is, on the engine, a multi-table write on a ScalarDB table, or a key delete of row 1 is a delete of row 2. Both the pass-through route and the rewrite route carry the comment through unchanged.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:417-422, 574-577`: special tokens are appended verbatim; only the token kind is inspected, never the image. `:172-249`: `parse` checks nothing about comments.
- Verified by security-sentinel on the 75106c2 build (`LexProbe`, `TokenProbe`) and live on `mysql:8.4`:
  - `update plain /*! , scalar_t */ set y = 1 where id = 1`: driver says UPDATE of `plain`, `whereColumns=[id]`, key equality true, `tables=[plain]`; MySQL executes `update plain, scalar_t set y = 1 where id = 1`, a multi-table update that writes `scalar_t.y`. The rewrite route emits `update "V_plain" /*! , scalar_t */ ...` with the comment intact.
  - `delete /*! scalar_t */ from plain /*! join scalar_t using (id) */ where id = 1`: a key DELETE of `plain` for the driver, a multi-table delete of `scalar_t` for MySQL.
  - `delete from scalar_t where id = 1 --1`: JSQLParser lexes `--1` as `LINE_COMMENT`; shape DELETE, `where=[id]`, `isKeyEquality=true`; MySQL evaluates `id = 1 - -1` and deletes id=2. The per-key protocol would prepare key 1 while the engine deletes row 2.
  - `select * from plain where x = 1 --(select max(id) from scalar_t)`: `tables=[plain]` for the driver, a raw read of `scalar_t` for MySQL. With `allowMultiQueries=true`, `... where x = 1 --1; delete from scalar_t` hides a second statement from todo 001's refusal.
  - `select * from plain /* /* */ where x in (select id from scalar_t) -- */`: `select * from plain` for the driver, a subquery over `scalar_t` for MySQL; `update plain /* /* */ , scalar_t -- */ set y = 1` is a multi-table write on MySQL; on SQL Server `update s set y = 1 /* /* */ from scalar_t s -- */` resolves `s` to nothing (PLAIN) and writes `scalar_t`.
  - Engine side: `SELECT 1 /*! + 1 */` = 2, `SELECT 1 --1` = 2, `SELECT 1 /* /* */ + 1 -- */` = 2 on MySQL 8.4.
- `#` is consistent: PostgreSQL treats it as XOR and JSQLParser lexes it as an operator in every position tried, never as `HASH_LINE_COMMENT`, so it fails closed and is not part of this item.
- No privileges and no unusual configuration are needed; the text is plain SQL.

## Proposed Solutions

### Option 1: Refuse the three forms at parse, by token image, engine-agnostically

**Approach:** In `SqlAst.parse`, after `rootOf`, walk the token chain once and return empty when any special token image starts with `/*!` or `/*M!`, contains `/*` after its opener, or is a line comment whose third character exists and is not whitespace. About ten lines, one place, which covers the pass-through route as well. sqlcommenter `/*k='v'*/`, Oracle `/*+ ... */` and ordinary `-- text` are unaffected. Add the six statements above to `SqlAstTest`.

**Pros:**
- Closes all three forms on every engine with one rule; no per-engine lexer knowledge needed in the driver.
- A refused statement is a visible error, not a silent bypass.

**Cons:**
- Refuses `/*! ... */` on engines where it is an ordinary comment (PostgreSQL), and `--1` everywhere; both are rare in application SQL.

**Effort:** Small

**Risk:** Low

---

### Option 2: Refuse per engine

**Approach:** Apply each rule only on the engines where the form is executable (`/*!` and `--x` on MySQL/MariaDB, nested comments on MySQL/MariaDB/Oracle, and SQL Server for the nested case), which means `SqlAst.parse` takes the engine (todo 038 wants that anyway).

**Pros:**
- No false refusals on PostgreSQL.

**Cons:**
- More rules to keep right; a wrong engine mapping reopens the hole.

**Effort:** Small, after 038

**Risk:** Medium

---

### Option 3: Strip comments before parsing

**Approach:** Remove every comment from the text with a driver-side lexer before JSQLParser sees it.

**Pros:**
- No comment ever reaches the engine.

**Cons:**
- Breaks the design's promise that hints and comments survive the rewrite (section 4, token join); needs a second lexer that agrees with every engine, which is the problem itself.

**Effort:** Medium

**Risk:** High

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:172-249` - `parse`, the token-chain check
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java` - the six refusal cases

**Related components:**
- Todo 001 (multi-statement refusal, which the `--1;` form bypasses), todo 002 (line-comment handling in the token join), the uncommitted design section 4.7 draft on hints.

**Database changes:** None.

## Resources

- `docs/design.md` section 4 ("Emit by joining the original tokens"), section 4.1 ("never guess").
- Found by: security-sentinel (P1, verified on the build and live on MySQL 8.4).
- Probes: `scratchpad/review2/sec/probes/LexProbe.java`, `TokenProbe.java`, `MySqlLiveProbe.java`.

## Acceptance Criteria

- [ ] `SqlAst.parse` returns empty for a statement containing `/*! ... */`, `/*M! ... */`, a `--` followed by a non-whitespace character, or a `/*` inside a block comment.
- [ ] `/*k='v'*/`, `/*+ hint */` and `-- text` still parse and round-trip through `joinTokens`.
- [ ] The six statements from the findings are in `SqlAstTest` and refused.
- [ ] The pass-through route cannot forward any of the three forms (covered by the parse-time refusal).

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- security-sentinel compiled the sources at 75106c2, lexed the three forms with JSQLParser 5.4 (`LexProbe`, `TokenProbe`) and confirmed the engine side on a throw-away `mysql:8.4` container, then showed the rewrite route emits the comment intact.

**Learnings:**
- Comments are the one part of a statement where the driver's lexer and the engine's can disagree; the fix must be by token image, since the token kind is what the parser got wrong.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- SqlAst.parse refuses, on every engine, a statement whose comment tokens include a MySQL executable comment (/*! or /*M!), a block comment holding a second opener, or a line comment whose third character is not whitespace (hasCommentAnEngineMayExecute, decided by token kind and image, ten lines). Ordinary comments, sqlcommenter tags and /*+ hints are unaffected.
- Tests: SqlAstTest.parse_refusesCommentFormsAnEngineMayExecute (the six statements from the review, including the ones reproduced live on MySQL) and parse_keepsOrdinaryCommentsAndHints.

## Notes

- Oracle and SQL Server share only the nested-comment form; the `/*!` and `--x` forms are MySQL/MariaDB specific, but a single engine-agnostic refusal is the smaller and safer rule.
