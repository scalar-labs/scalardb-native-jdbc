---
status: pending
priority: p3
issue_id: "317"
tags: [parser, refusal]
dependencies: []
---

# Statements with chained unary signs (`- - 27`, `+ + col0`) are refused as unparsable

## Problem Statement

The SQL parser rejects a sign applied to a signed operand: `SELECT - - 27 FROM t`,
`SELECT col0 * - + col2 FROM t`, `CAST ( + - 4 AS INTEGER )`, `MAX ( + + 15 )`. Every engine
accepts them; the driver refuses them with 0AN01 "the statement cannot be parsed: unexpected "-"",
on ScalarDB tables and plain ones alike (todo 314 covers the plain ones in general). Generated SQL
and expression builders write such signs.

## Findings

- T4 sqllogictest on PostgreSQL, 100 queries per file: about 150 of the 185 refusals, most in the
  `random/*` and `index/random` files; at 1000 queries per file, 1457 of the 1700.
- JSqlParser 5.4 rejects only a unary sign right after another: `- - 27`, `+ + 27`, `- + 26`,
  `- - - 3`, `+ + CAST(1 AS INT)` fail; `-(-27)`, `- (- 27)`, `1 - - 2` (binary, then one unary
  sign) parse.
- Folding the signs is not exact: `- - '1'` converts the string to a number on MySQL and
  `+ 'a'` fails on PostgreSQL. Parenthesizing, `- (- x)`, is exact but needs the operand's end
  (a literal, a name, a group, a call, `CAST(...)`, `CASE ... END`): a small parser of its own
  on the path every statement takes.
- Hand-written and ORM-generated SQL rarely chains signs; sqllogictest generates them. The
  refusal is an error, never a wrong result.

## Proposed Solutions

### Option 1: parenthesize the signs before parsing, on a parse failure only
`- - x` becomes `- (- x)`, outside literals, quoted names and comments. **Effort:** Medium; risk
on the main path for SQL applications rarely write.

### Option 2: report and fix it in JSqlParser's grammar
**Effort:** Unknown; upstream.

## Recommended Action

P3 (2026-10-22): leave refused; report it to JSqlParser (option 2) when the user decides to.

## Acceptance Criteria

- [ ] The statements above parse; sqllogictest's refusals for them are gone, on every engine.

## Work Log

### 2026-10-22 - Found by T4 sqllogictest

**By:** Claude Code

### 2026-10-28 - Every query of the 15 files on Oracle

**By:** Claude Code

At e8f3436, all 122,012 queries per layout: 9,546 refused, the same in both layouts. 9,526 are
chained signs. The other 20 are a simple `CASE` whose operand is signed, `CASE - 37 WHEN ...`,
`CASE + cor0.col2 WHEN ...` (random/groupby, past its first 1,000 queries), which JSqlParser
rejects at `WHEN`; 11 of them have no chained sign. Option 1 would need to cover that form too.

## Resources

- docs/plans/testing.md, milestone T4.
