---
status: complete
priority: p1
issue_id: "006"
tags: [code-review, correctness, sql]
dependencies: []
---

# `INSERT IGNORE`, `UPDATE IGNORE`, `SKIP LOCKED` and `NOWAIT` are invisible to the shape reader and the allowlist

## Problem Statement

`StatementShape` does not check the boolean flags and lock-wait options on JSQLParser's statement nodes, and the reflective scan skips primitive fields, so the node-type allowlist cannot see them either. An `INSERT IGNORE` is classified as a plain INSERT and a `SELECT ... FOR UPDATE SKIP LOCKED` as a plain locking read. The design forbids `INSERT IGNORE` outright: a collision looks like success, so the orphan-takeover CAS of 4.3 restamps a live row's metadata and causes spurious ScalarDB validation failures. A `SKIP LOCKED` queue read would be rewritten into a blocking `FOR UPDATE`.

## Findings

- `StatementShape.insert` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:441-474`) and `select` (`:397-437`) read `getForMode()` but not `isModifierIgnore`, `isSkipLocked`, `getWait`, `isNoWait`.
- The reflective scan skips primitives (`driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:1060`), so a boolean modifier never appears in `unknownNodes`.
- **Verified** by the lead against the built classes:
  - `insert ignore into t (id, x) values (?, ?)` gives shape INSERT, `unknownNodes=[]`.
  - `update ignore t set x = ? where id = ?` gives shape UPDATE.
  - `select * from t where id = ? for update skip locked` and `... for update nowait` give shape SELECT with `lockMode=UPDATE`.
  - `insert ... on duplicate key update x = x + 1` and `insert ... on conflict (id) do nothing` ARE refused today, because `InsertDuplicateAction`/`InsertConflictAction` are outside the allowlist. The earlier report that they pass was wrong. Those refusals should still become explicit so they do not depend on the allowlist (todo 011 may remove or narrow it).

## Proposed Solutions

### Option 1: Check every modifier explicitly in the shape reader

**Approach:** In `insert`, refuse on `isModifierIgnore()`, `isUseDuplicate()`/`getDuplicateUpdateSets()`, `getConflictTarget()`/`getConflictAction()`, `getOutputClause()`/`getReturningClause()` (or expose `returning` as today). In `update`/`delete`, refuse ignore modifiers. In `select`, refuse `isSkipLocked()`, `getWait()`, `isNoWait()` on a ScalarDB table (or expose them so the rewrite can decide). Add all forms to the refusal corpus in `StatementShapeTest`.

**Pros:**
- Explicit, readable, independent of the allowlist.
- A dozen lines.

**Cons:**
- Must be kept in step with JSQLParser's node API across upgrades (the corpus test catches regressions).

**Effort:** 2-3 hours

**Risk:** Low

---

### Option 2: Include primitive fields in the reflective scan and extend the allowlist

**Approach:** Have `scan` report non-default boolean fields on known types as `flag:` entries, refused unless allowlisted.

**Pros:**
- Catches flags the shape reader never thought of.

**Cons:**
- Couples safety to the reflective scan that todo 003 and todo 011 want to shrink; a non-default boolean is not always a modifier.

**Effort:** Half a day

**Risk:** Medium

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:397-437, 441-474` - `select`, `insert`; `update`/`delete` for ignore modifiers
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/StatementShapeTest.java` - refusal corpus

**Related components:**
- design.md 4.3 (INSERT IGNORE forbidden; orphan take-over on INSERT), 4.2 (locking clause rule).
- Todo 011 (allowlist scope) and todo 019 (other shape points).

**Database changes:** None.

## Resources

- `docs/design.md` 4.3 ("INSERT IGNORE" is refused), 4.2 (locking reads), 4.6 (`@Lock` mapping).
- JSQLParser 5.4: `Insert.isModifierIgnore/isUseDuplicate/getConflictAction/getOutputClause/getReturningClause`, `PlainSelect.isSkipLocked/getWait/isNoWait`.
- Found by: feature-dev:code-reviewer (P1-2, corrected by the lead's probe), architecture-strategist (P2-4).

## Acceptance Criteria

- [ ] `insert ignore ...` and `update ignore ...` are refused.
- [ ] `select ... for update skip locked` and `... for update nowait` are refused or exposed as distinct lock modes, never reported as a plain `FOR UPDATE`.
- [ ] `insert ... on duplicate key update`, `insert ... on conflict ...`, `RETURNING`/`OUTPUT` are refused explicitly, independent of `KNOWN_NODE_TYPES`.
- [ ] Each form above is in the `StatementShapeTest` refusal corpus.
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- feature-dev:code-reviewer reported `ON DUPLICATE KEY UPDATE`/`INSERT IGNORE` accepted from a static read; architecture-strategist's jshell probe showed `INSERT IGNORE` and `SKIP LOCKED` accepted and `ON DUPLICATE KEY` refused.
- The lead re-ran all forms against the built classes and settled the disagreement: boolean modifiers pass, node-typed clauses are refused by the allowlist.

**Learnings:**
- The allowlist only sees node types; anything JSQLParser stores as a boolean is invisible to it.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- Fixed in StatementShape: explicit refusals of isModifierIgnore (INSERT, UPDATE, DELETE), getDuplicateUpdateSets, getConflictTarget/getConflictAction, getOutputClause, and isSkipLocked/isNoWait/getWait on SELECT. Tests: eight new refusal cases in of_isEmptyForShapesTheDriverDoesNotHandle.
- Verified: unit tests 101/101, full `./gradlew check` green (SpotBugs, Error Prone, javadoc, PostgreSQL integration test), and the live reproduction probe no longer reproduces.

**Learnings:**
- The probe that found this is a cheap regression guard; its cases are now in the unit tests.

## Notes

- If todo 011 narrows or removes the allowlist, the explicit refusals here become the only guard for `ON DUPLICATE KEY`/`ON CONFLICT`.
