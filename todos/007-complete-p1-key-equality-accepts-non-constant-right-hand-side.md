---
status: complete
priority: p1
issue_id: "007"
tags: [code-review, correctness, sql]
dependencies: []
---

# `isKeyEquality` accepts any non-Column right-hand side, so `id = id + 0` counts as a point write

## Problem Statement

`StatementShape.isKeyEquality` decides whether a WHERE clause addresses exactly one row by key. The only check on the other side of each `=` is that it is not a `Column`, so any expression counts as a constant: a self-referencing arithmetic term, a subquery, a function call, or `NULL`. `where id = id + 0` matches every row in the table but would be treated as a single-key point read or write with a single-key anchor, which breaks the per-key protocol of design.md 4.3 and the anchored read of 4.2.

## Findings

- `isKeyEquality` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:329`): `other instanceof Column` is the only exclusion.
- **Verified** by the lead against the built classes, all with key `[id]`:
  - `update t set x = ? where id = id + 0` → `isKeyEquality = true`, `whereColumns=[id]` (matches every row).
  - `update t set x = ? where id = (select 5)` → true (subquery; a dependent value the driver cannot anchor).
  - `update t set x = ? where id = null` → true (matches nothing; not an equality).
  - `update t set x = ? where id = abs(1)` → true (function; deterministic here, but `random()` would pass too).
- The design's deterministic-predicate rule (4.4) has no hook in the shape reader either (see todo 019).

## Proposed Solutions

### Option 1: Require a constant-only right-hand side

**Approach:** Walk the other side with `nodesUnder` (or a visitor) and return false if it contains any `Column`, any `Select`/subquery, or any `Function` (optionally allowing a small deterministic allowlist such as `abs`, `upper`); treat `NullValue` as not an equality. Keep placeholders, literals and arithmetic over literals as constants.

**Pros:**
- Closes every verified case with one predicate.
- Fail-closed for future expression types.

**Cons:**
- `id = ? + 1` style arithmetic needs an explicit decision (accept as constant if it contains no Column/Function/Select).

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 2: Accept only `JdbcParameter` and literal values

**Approach:** The right-hand side must be a `JdbcParameter`, `JdbcNamedParameter`, or a literal (`LongValue`, `StringValue`, `DoubleValue`, `DateValue`, …).

**Pros:**
- Simplest possible rule; exactly what ORMs emit.

**Cons:**
- Refuses `id = 1 + 1` and casts such as `id = CAST(? AS bigint)`, which some ORMs and dialects produce; those fall to the range path and lose the anchor.

**Effort:** 1 hour

**Risk:** Low (over-refusal only)

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:329` - `isKeyEquality`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java` - cases below

**Related components:**
- design.md 4.2 (anchored reads), 4.3 (key writes), 4.4 (deterministic predicates).
- Todo 003 (if `nodesUnder` changes), todo 019 (function reporting in WHERE).

**Database changes:** None.

## Resources

- `docs/design.md` 4.2, 4.3, 4.4.
- Found by: feature-dev:code-reviewer (P1-3).

## Acceptance Criteria

- [ ] `id = id + 1` returns false.
- [ ] `id = (select 5)` returns false.
- [ ] `id = null` returns false.
- [ ] `id = abs(1)` returns false (or true only under an explicit deterministic allowlist that is tested).
- [ ] `id = ?`, `id = 1`, `1 = id` still return true; `id = ? and k = ?` for key `[id, k]` still returns true.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer identified the `instanceof Column` check from a static read.
- The lead verified all four forms against the built classes.

**Learnings:**
- "Not a column" is not "a constant"; the check must say what it accepts, not what it rejects.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- Fixed in StatementShape.isKeyEquality: the other side of '=' must be a placeholder or literal, possibly signed or cast (isConstant); NULL, columns, functions, subqueries and arithmetic fail closed. ColDataType added to KNOWN_NODE_TYPES so that CAST, already listed there, is actually accepted. Tests: keyEquality_acceptsPlaceholdersAndLiterals_signedOrCast and keyEquality_refusesAnythingButAValue.
- Verified: unit tests 101/101, full `./gradlew check` green (SpotBugs, Error Prone, javadoc, PostgreSQL integration test), and the live reproduction probe no longer reproduces.

**Learnings:**
- The probe that found this is a cheap regression guard; its cases are now in the unit tests.

## Notes

- Also decide here whether `id = ? + 0` (arithmetic over a placeholder) is a key equality; it is harmless for the anchor but unusual in ORM output.
