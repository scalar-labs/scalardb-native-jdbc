---
status: complete
priority: p1
issue_id: "002"
tags: [code-review, security, correctness, sql, performance]
dependencies: []
---

# A `--` comment containing `*/` swallows the rest of the rewritten statement; the same code is quadratic in statement length

## Problem Statement

When `SqlAst.joinTokens` re-joins the original tokens, it decides whether the previous line comment is still "open" by looking at the text of the output buffer. A `--` comment whose text contains `*/` is treated as closed, so no newline is emitted after it and every following token lands inside the comment. The driver still believes it executed the statement it classified: `StatementShape` is built from the original AST and reports a key DELETE with `whereColumns=[id]`, while the SQL sent to the engine has no WHERE clause at all.

Comment text is the one part of a statement that application frameworks let request data flow into (sqlcommenter-style tagging), so this is reachable without any application bug. The same function also scans and copies the whole output buffer for every token, which makes the rewrite quadratic in statement length.

## Findings

- `appendToken` and `endsWithOpenLineComment` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:634-661`): a line comment counts as closed when its text contains `*/`.
- **Verified** against the built classes: `delete from t -- cleanup */\nwhere id = 1` joins to `delete from t -- cleanup */ where id = 1`. The re-parse at `SqlAst.java:507` succeeds (the whole tail is a trailing comment), and `StatementShape` still reports DELETE with `whereColumns=[id]`, so the driver would run an unconditional DELETE believing it is a key write. With `?` placeholders the vendor driver fails on the parameter count; with literals the predicate loss is silent.
- **Verified** performance: `out.lastIndexOf("\n")` scans the whole buffer and `out.substring(nl + 1)` copies it on every appended token. Multi-row INSERT with 5 columns: 100 rows parse 1.2 ms / joinTokens 2.0 ms; 400 rows 4.6 ms / 16.6 ms and 15 MB; 1600 rows (19 KB) 18 ms / 196 ms and 241 MB allocated.
- `specialTokensOf` (`SqlAst.java:664-673`) inserts with `add(0, s)`, which is O(k²) in chained comments.

## Proposed Solutions

### Option 1: Decide by token kind, not by text

**Approach:** In the special-token loop (`SqlAst.java:481-484`), remember a boolean "the last emitted token was a line comment with no trailing newline" (by token kind, or `image.startsWith("--")`). In `appendToken`, if the flag is set, emit `\n` before the token and clear the flag. Delete `endsWithOpenLineComment` and the substring. Build `specialTokensOf` forward and `Collections.reverse` once.

**Pros:**
- O(1) per token, no buffer scans or copies.
- Removes the text heuristic entirely; a comment can no longer close itself.
- Smaller code than today.

**Cons:**
- None of substance; a newline is emitted after every line comment, which is always valid SQL.

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 2: Always terminate a line comment when it is emitted

**Approach:** When emitting a special token whose image starts with `--`, append `\n` to the image itself if it lacks one, and drop the open-comment check.

**Pros:**
- Even simpler than Option 1.

**Cons:**
- Changes the comment token's text (an extra newline at EOF for a trailing comment); harmless but visible in rewritten SQL and tests.

**Effort:** 1 hour

**Risk:** Low

---

### Option 3: Re-verify the rewritten statement against the shape

**Approach:** After the re-parse, compare the rewritten statement's structure (statement type, WHERE present) with the original.

**Pros:**
- Would also catch other future token-join mistakes.

**Cons:**
- Treats the symptom; keeps the quadratic scan; adds a second structural comparison that must be maintained.

**Effort:** 3-4 hours

**Risk:** Medium

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:481-484` - special-token loop in `joinTokens`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:634-661` - `appendToken`, `endsWithOpenLineComment`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:664-673` - `specialTokensOf`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java` - corpus additions

**Related components:**
- Every rewrite emitted through `joinTokens` (design.md 4.2 and 4.3).
- Todo 008 (per-statement cost) for the measured numbers.

**Database changes:** None.

## Resources

- `docs/design.md` section 4.2 (reads are emitted by re-joining the original tokens so comments and hints survive).
- Found by: security-sentinel (P1-1), performance-oracle (P1-3), feature-dev:code-reviewer (16).

## Acceptance Criteria

- [ ] `delete from t -- cleanup */\nwhere id = 1` joins to text whose WHERE clause is outside the comment, and the rewritten statement re-parses to a DELETE with a WHERE.
- [ ] `select * from t -- x */\nwhere id = ?` keeps its placeholder outside the comment.
- [ ] `-- x */` is in the SqlAstTest round-trip corpus; a comment inside a replaced table range is covered.
- [ ] `endsWithOpenLineComment` and the per-token substring are gone.
- [ ] A 1600-row multi-row INSERT joins in time proportional to its length (test guard: joinTokens below a few milliseconds, no 100 MB allocation).
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- security-sentinel compiled the sql package against jsqlparser-5.4 and reproduced the swallowed WHERE clause.
- performance-oracle measured the quadratic joinTokens cost on multi-row INSERTs.
- feature-dev:code-reviewer flagged the per-token substring from a static read.
- The lead re-ran the probe against the built classes; both behaviours reproduced.

**Learnings:**
- The lexer already knows what a line comment is; the rewrite must not re-derive it from text.
- The same one-line flag fixes the correctness bug and the quadratic cost.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- Fixed in SqlAst.joinTokens/appendToken: a line comment is recognised by token kind (LINE_COMMENT, HASH_LINE_COMMENT) and always followed by a newline; endsWithOpenLineComment and its per-token substring removed (no longer quadratic); specialTokensOf builds forward and reverses. Tests: two corpus entries and joinTokens_closesALineCommentByItsKind_whateverItContains.
- Verified: unit tests 101/101, full `./gradlew check` green (SpotBugs, Error Prone, javadoc, PostgreSQL integration test), and the live reproduction probe no longer reproduces.

**Learnings:**
- The probe that found this is a cheap regression guard; its cases are now in the unit tests.

## Notes

- Keep the existing behaviour for `--` inside a string literal (the literal is one token; a newline is only inserted after a real comment token).
