---
status: complete
priority: p1
issue_id: "086"
tags: [code-review, correctness, split-read]
dependencies: []
---

# A trailing line comment comments out every split copy after the first

## Problem Statement

When a split statement has nothing lifted on top (no ORDER BY, row limit or DISTINCT), each copy is
emitted up to EOF, including EOF's special tokens, so a trailing `-- note` (or `# note` on MySQL) ends
every copy, and `String.join(" UNION ALL ", texts)` puts `UNION ALL` and all later copies on the comment
line. The engine runs only the all-committed copy: every committed row with an update or delete in
flight disappears from the result, silently. With placeholders the vendor statement has fewer `?` than
`parameterSources`, and binding fails with a vendor index error.

## Findings

- `SqlAst.java:1415-1425` (the non-lifted branch of `emitSplit`), `Emitter.tokens` 1052-1058, `text()`
  1087-1089. `parses()` checks only copy 0.
- Probe (confirmed): `SELECT x FROM a WHERE id = ? -- note` emits `... WHERE id = ? -- note UNION ALL
  SELECT ... WHERE id = ? -- note`, sources [1, 1].
- Found independently by three reviewers. `SqlAstTest:161` has a trailing-comment statement, but no
  split test does.

## Verification (2026-10-12)

Run through the driver against the five Testcontainers engines with `made` row 2 held prepared by a
2PC transaction (scratch probe `Verify.java`, not in the repository).

Confirmed on all five engines.
- `SELECT x FROM rd.made -- note` returned 2 rows (a, c) instead of a, b, c on PostgreSQL, MySQL,
  MariaDB, SQL Server and Oracle: the committed row with an update in flight is missing.
- With `WHERE id = ? -- note` and 2: index-out-of-range errors on PostgreSQL (22023), MySQL (S1009),
  SQL Server (S1093) and Oracle (ORA-17003); **MariaDB returned [] silently**.

## Proposed Solutions

### Option 1: newline joins plus a full check
End each copy with a newline when it ends in a line comment (or join with `"\nUNION ALL\n"`); parse
the whole emitted text, and assert that its `?` token count equals `sources.size()`, returning null
(fallback) otherwise. **Effort:** Small. **Risk:** Low.

### Option 2: drop EOF special tokens from copies
Emit trailing comments once, after the last copy. **Effort:** Small. **Risk:** Low.

## Recommended Action

## Technical Details

See Findings.

## Acceptance Criteria

- [x] SplitTest cases with a trailing `--` comment and a trailing `#` comment (MySQL), with and without
      placeholders, emit every copy.
- [x] The emitted text is parsed whole and its placeholder count checked before use.
- [x] An integration case with a trailing comment returns before images of in-flight rows.

## Work Log

### 2026-10-12 - Fixed

**By:** Claude Code
- `Emitter.text()` keeps the newline that closes a trailing line comment, so `UNION ALL` starts a new
  line; a comment before the first select item, emitted in every copy, is closed the same way.
- `checked()` lexes the whole emitted text and falls back unless its `?` count equals the parameter
  mapping, so a swallowed copy can no longer reach the engine.
- Found while fixing: on MySQL and MariaDB the parser reads `#` as an operator, so `WHERE id = 1 # c\n
  AND y = 2` parsed, and both the split and the rc_T rewrite re-emitted it on one line, commenting out
  `AND y = 2` (more rows; todo 034's "fails closed" held only where `#` does not parse). Now a statement
  with a `#` token is not rewritten on those engines (`hasMisreadHashComment`): refused with 0AN01 when
  it needs a rewrite, passed through unchanged when it does not.
- Tests: SplitTest (`--` with a placeholder, `#`, a comment before the first item), ReadIntegrationTest
  (trailing comment with and without a placeholder, all engines). Probe: all five engines now return
  a, b, c and b.

### 2026-10-12 - Found in review

**By:** Claude Code
- Review of 0238f99..56c44ba (milestone 3b), 2026-10-12: correctness, binding, security, performance and simplicity reviewers.

## Resources

- Commits 7503c10, ca8046d, 56c44ba; docs/design.md, docs/plans/proxy-step.md milestone 3b,
  docs/benchmarks/read-overhead.md.
