---
status: complete
priority: p2
issue_id: "101"
tags: [code-review, sqlserver, parsing]
dependencies: []
---

# SQL Server statements with [bracketed] identifiers are refused as unparseable

## Problem Statement

SQL Server's standard identifier quoting is `[name]`, and tools and ORMs emit it. The driver's main parse
does not enable JSqlParser's square-bracket mode, so every statement with a bracketed identifier is
refused with SQLSTATE 0AN01 ("the statement cannot be parsed"), whatever table it touches. It fails
closed (no wrong results), but SQL Server applications that bracket names cannot use the driver.

## Findings

- `SqlAst.java:245` (`CCJSqlParserUtil.parseStatements(sql)`) and `:628` (`parseAST`) parse without
  `withSquareBracketQuotation`; only the validation parse at `:809` enables it for SQL Server.
- Verified 2026-10-12 on SQL Server through the driver: `SELECT [x] FROM rd.[made] ORDER BY [id]` gives
  `0AN01 the statement cannot be parsed: unexpected "." at line 1, column 19`; the same statement with
  double quotes returns the rows. `Engine.quote` for SQL Server itself produces `[made]`.
- Found while verifying the milestone 3b review; it predates the split read (the parse is from
  milestone 1). Not covered by todo 018, which is about unquoting.

## Proposed Solutions

### Option 1: enable square brackets in every SQL Server parse

Pass `p -> p.withSquareBracketQuotation(engine == Engine.SQLSERVER)` to the main parses as the
validation parse already does, and check that identifier folding and unquoting (todo 018's code) handle
`[...]` and `]]` escapes. **Effort:** Small. **Risk:** Low; check that `[` in other positions (none in
T-SQL outside identifiers and LIKE patterns, which are literals) still lexes.

## Recommended Action

## Technical Details

- Affected: `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java` (parse entry points and
  identifier unquoting); SQL Server only.

## Acceptance Criteria

- [x] `SELECT [x] FROM rd.[made]` reads through the driver on SQL Server, split and unsplit.
- [x] A unit test covers bracketed names. A name containing `]` (written `]]`) is refused at parse:
      JSqlParser 5.4 does not lex the escape (`unexpected "]"`), so it fails closed; rare enough to
      leave.

## Work Log

### 2026-10-12 - Fixed

**By:** Claude Code
- `analyze` parses with `withSquareBracketQuotation` on SQL Server; name folding already handled
  brackets. The `parseAST` fallback (statements with no table or column node) was left unchanged at
  first, which broke `USE [appdb]`; fixed with todo 088 by a parser subclass (`TreeParser`) that sets
  the option and reaches the syntax tree.
- SplitTest; probe: `SELECT [x] FROM rd.[made] ORDER BY [id]` returns the rows on SQL Server.

### 2026-10-12 - Found while verifying the review

**By:** Claude Code
- Reproduced through the driver against the SQL Server test container.

## Resources

- Verification run of todos 086-100 (see their "Verification" sections).
