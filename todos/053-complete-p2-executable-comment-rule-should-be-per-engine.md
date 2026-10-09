---
status: complete
priority: p2
issue_id: "053"
tags: [code-review, sql, usability, engine]
dependencies: []
---

# The executable-comment rule refuses ordinary comments and Oracle hints on engines that read them as comments

## Problem Statement

The fix for 034 refuses, at parse time and on every engine, any statement containing a MySQL executable comment (`/*!`, `/*M!`), a block comment nested in another, or a `--` not followed by whitespace. Only some engines read those forms as code: MySQL and MariaDB run `/*! ... */` and read `--1` as `- -1`; MySQL, MariaDB and Oracle end a block comment at the first `*/`, where PostgreSQL and SQL Server nest. On the other engines the same text is an ordinary comment, so the rule refuses valid statements, plain tables included, since the refusal happens before routing. The visible cases are Oracle's `--+` optimizer hints, which design section 4.7 says are kept, and comments written without a space such as `--TODO` on PostgreSQL.

## Findings

- `SqlAst.hasCommentAnEngineMayExecute` (driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java) takes no engine, while `SqlAst.parse` now has one.
- Found while reviewing design section 4.7 on 2026-10-09: the section describes `--+` hints as parsed and kept; the code refuses them.

## Proposed Solutions

### Option 1: per-engine rule

**Approach:** pass the engine: `/*!` and `/*M!` refused on MySQL and MariaDB only; nested block comments refused on MySQL, MariaDB and Oracle; `--` without whitespace refused on MySQL and MariaDB only.

**Pros:** exact; restores Oracle `--+` hints and ordinary comments elsewhere.

**Cons:** three engine facts to keep right; each needs a test on its container.

**Effort:** 1-2 hours. **Risk:** Low.

### Option 2: keep the uniform rule, exempt `--+`

**Approach:** keep refusing on every engine but accept a line comment starting with `--+`.

**Pros:** one line.

**Cons:** still refuses `--TODO` on PostgreSQL and nested comments on SQL Server; on MySQL `--+1` is code.

**Effort:** minutes. **Risk:** Medium.

## Recommended Action

**To be filled during triage.**

## Technical Details

- driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java (`hasCommentAnEngineMayExecute`)
- driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java (`parse_refusesCommentFormsAnEngineMayExecute`, `parse_keepsOrdinaryCommentsAndHints`)
- docs/design.md 4.1 and 4.7

## Resources

- todo 034 (the rule), design sections 4.1 and 4.7

## Acceptance Criteria

- [ ] `--+ INDEX(t)` and `--TODO` parse on PostgreSQL, SQL Server and Oracle; refused on MySQL and MariaDB.
- [ ] A nested block comment parses on PostgreSQL and SQL Server; refused on MySQL, MariaDB and Oracle.
- [ ] `/*!` and `/*M!` refused on MySQL and MariaDB, ordinary comments elsewhere.
- [ ] Each fact checked on its container; design 4.1 and 4.7 updated.

## Work Log

### 2026-10-09 - Found while reviewing design section 4.7

**By:** Claude Code

**Actions:**
- Compared section 4.7 (Oracle `--+` hints kept) with the 034 rule (any `--` without whitespace refused on every engine); recorded the conflict in 4.7 and here.

### 2026-10-09 - Fix applied (plan milestone 0)

**By:** Claude Code

**Actions:**
- The rule is per engine: `/*!` refused on MySQL and MariaDB, `/*M!` on MariaDB only (the engine test showed MySQL treats `/*M!` as an ordinary comment, which the first version had wrong); a nested block comment refused on MySQL, MariaDB and Oracle; `--` not followed by whitespace refused on MySQL and MariaDB. Oracle's `--+` hints and PostgreSQL's `--TODO` parse again.
- Tests: unit tests per engine; CommentFormsIntegrationTest runs each of four forms on every engine configuration and asserts the parser refuses it exactly when the engine returns 2 instead of 1 (the engine executed part of the comment).

## Notes

- The uniform rule was the implementer's choice for 034, not a decision by the maintainer.
