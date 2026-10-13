---
status: pending
priority: p2
issue_id: "108"
tags: [code-review, correctness]
dependencies: []
---

# Per-copy row limit: large literals overflow or throw

## Problem Statement

`SqlAst.copyLimit` adds the offset and the count as `long`s taken from `LongValue.getValue()`, which
is `Long.parseLong` in JSqlParser 5.4 (verified).

## Findings

- **Throws.** MySQL's documented "to the end" form, `LIMIT 5, 18446744073709551615`, throws
  `NumberFormatException` out of routing. That is unchecked, not a `SQLException`.
- **Overflows.** `OFFSET 1 ROWS FETCH NEXT 9223372036854775807 ROWS ONLY` on Oracle gives
  `ROWNUM <= -9223372036854775808`: an empty result with no error. On the others it gives a
  negative `LIMIT`, a syntax error.
- **Minor.** `limitsEachCopy()` can say true while `emitSplit` drops the limit (an ORDER BY element
  that cannot be named). MySQL then uses the per-row check with no per-copy limit.
- `sql/SqlAst.java:2248-2251`.

## Proposed Solutions

### Option 1
- Parse with `getBigIntegerValue()` and add with `Math.addExact`.
- On overflow, or above a cap, return null: no per-copy limit.
- Make `limitsEachCopy` use the same decision as `emitSplit`.
- **Effort:** Small.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] `LIMIT 5, 18446744073709551615` and `FETCH NEXT 9223372036854775807 ROWS` return the plain
      query's rows on every engine.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".
