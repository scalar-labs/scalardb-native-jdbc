---
status: complete
priority: p2
issue_id: "189"
tags: [code-review, correctness, jdbc, mysql, mariadb]
dependencies: []
---

# MySQL/MariaDB locking reads find and move the clause by text: whitespace, literals and extras break it

## Problem Statement

Router.lockingRead (~711-773) locates the user's clause with `afterLast("FOR")`, strips it by
`emitted.lastIndexOf(own)` and replaces every `LOCK_MARK`. Verified through the driver
(scratchpad/rv1lock):

- Whitespace in the clause is refused: `FOR  UPDATE` (two spaces), `FOR UPDATE\n  NOWAIT` on MySQL;
  the emitted text is re-spaced so `lastIndexOf` fails.
- MariaDB: `LOCK IN SHARE MODE NOWAIT` / `SKIP LOCKED` are always refused, because the padded
  `FOR SHARE         ` in `own` never matches; MariaDB rejects `FOR SHARE` natively, so no shared
  NOWAIT/SKIP LOCKED read works on MariaDB at all.
- `.replace(LOCK_MARK, inner)` also rewrites the mark inside a user literal:
  `WHERE x <> '/* scalardb_native_lock */' FOR UPDATE` returned 5 rows instead of 6.
- Extras are dropped: `FOR UPDATE OF <name>` loses OF (an invalid OF now passes); `FOR SHARE` is
  accepted on MariaDB and `LOCK IN SHARE MODE NOWAIT` on MySQL, which those engines reject.
- `ast.afterLast("LOCK") - 4` is dead (no LOCK token survives the pre-parse rewrite).

## Proposed Solutions

### Option 1
Have SqlAst give the locking clause's token span, emit the split read up to it (or with it cut), and
let SplitRead return the fragment's insertion point instead of a text mark.

### Option 2
Compare with whitespace normalised, replace only the last mark the emitter inserted, refuse a source
that contains the mark. Smaller, still text-based.

## Acceptance Criteria

- [x] Extra whitespace in the clause works on both engines
- [x] MariaDB LOCK IN SHARE MODE NOWAIT and SKIP LOCKED work end to end (test)
- [x] A literal containing the mark is unchanged
- [x] The dead LOCK branch is gone

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 8a, 758043b.

### 2026-10-17 - Fixed

**By:** Claude Code
- Router.lockingRead: the statement is parsed again without its clause (SqlAst.without), the inner clause built from the shape; no text search; a source holding the mark is refused; the dead LOCK branch is gone. Tested: whitespace in the clause, MariaDB LOCK IN SHARE MODE NOWAIT and SKIP LOCKED, the mark in a literal. A trailing ; stays refused by the split read on every engine, as before 8a.
