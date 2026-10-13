---
status: pending
priority: p2
issue_id: "110"
tags: [code-review, correctness, postgresql]
dependencies: []
---

# Narrow projection: PostgreSQL whole-row references and escaped quotes

## Problem Statement

`columnsReadFrom` (48f523a) decides which columns a copy reads. Two inputs make it read too few.

## Findings

- **Whole-row reference, P2, wrong result.**
  - `SELECT t FROM t WHERE id = ?`, `ORDER BY t` or `t IS NOT NULL`. On PostgreSQL `t` is the whole
    row.
  - It matches no column, so the copy keeps only the first key and the row value becomes `(1)`
    instead of `(1,a,b)`. `SqlAst.java:1381-1389`.
- **Doubled quotes, P3.**
  - `unquoted` strips the outer quotes but does not unescape `""` or doubled backticks, so a column
    named `a"b` is dropped from the copy.
  - If another joined table has it, an ambiguous name, which is an error, resolves to that table.
  - Otherwise the statement fails.

## Proposed Solutions

### Option 1
- Read every column when an unqualified name equals the table's alias or name.
- Unescape doubled quote characters.
- **Effort:** Small.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] `SELECT t FROM t` through the driver equals the plain query on PostgreSQL.
- [ ] A column named `a"b` reads correctly.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

### 2026-10-13 - Reproduced

**By:** Claude Code
- `ReviewFindingsIntegrationTest` (uncommitted until the fixes) asserts the correct behaviour, on
  all six configurations.
- **Confirmed:** `SELECT m FROM made m WHERE m.id = 1` returned `(1)` instead of `(1,a)` on
  PostgreSQL.
- **Not tested:** doubled quotes.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".
