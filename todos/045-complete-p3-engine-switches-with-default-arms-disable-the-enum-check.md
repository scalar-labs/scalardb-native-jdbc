---
status: complete
priority: p3
issue_id: "045"
tags: [code-review, quality, engine]
dependencies: []
---

# Every `switch (this)` in `Engine` has a `default` arm, which disables the enum-completeness check the build escalates on purpose

## Problem Statement

The build turns Error Prone's `MissingCasesInEnumSwitch` into an error so that adding an enum constant fails compilation wherever a switch forgets it. Every per-engine switch in `Engine` ends with `default:` for Oracle, so the check never fires: a sixth engine would silently inherit Oracle's session query, its `all_objects` resolution and its ORA-942 missing-table code. The same file is where the design's per-engine table will grow by about twelve more rules, so the growth pattern matters now.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:170` (`switch (fold)`, `default` for `NONE`), `:224` (`sessionSchemas`), `:302` (`resolve`), `:397` (`isMissingTable`): each has a `default` arm standing in for `ORACLE` or `NONE`.
- `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:112-115` escalates `MissingCasesInEnumSwitch` to an error; it has nothing to report when `default` is present.
- architecture-strategist: the per-engine table in `docs/design.md` (section 1) will add locking syntax, row limit, boolean test, insert-collision handling, retryable and duplicate-key codes, isolation mapping and the RCSI check; switch-per-method does not scale to that.
- Per-database server settings (`lower_case_table_names`, collation, RCSI) do not belong in the enum at all; todo 032 reads them at catalog load.

## Proposed Solutions

### Option 1: Name every constant and end with an assertion

**Approach:** Replace each `default:` by the explicit `ORACLE:` (and `NONE:`) case and finish the method with `throw new AssertionError(this)`, so the escalated check fires on a new constant.

**Pros:**
- Four small edits; the existing build check does the rest.

**Cons:**
- Keeps the switch-per-method shape.

**Effort:** 30 minutes

**Risk:** Low

---

### Option 2: Identity plus data, structural variants in per-constant bodies

**Approach:** Keep `Engine` as the identity and carry the per-engine data (quote chars, fold, SQL templates, error-code sets) as constructor constants; put the structural variants (session query, resolution strategy, later the SERIALIZABLE lock strategy and collision handling) in per-constant bodies. Split into one class per engine only if the file outgrows `SqlAst`.

**Pros:**
- Adding an engine means adding one constant with every rule in one place.
- No switch to forget.

**Cons:**
- A larger refactor than Option 1; best done when the next per-engine rules land.

**Effort:** Half a day

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:170, 224, 302, 397`
- `build-logic/src/main/kotlin/scalardb-native-jdbc.java-conventions.gradle.kts:112-115` - the escalated check

**Related components:**
- Todo 032 (per-database settings read at load, not in the enum).

**Database changes:** None.

## Resources

- `docs/design.md` section 1, per-engine table and "Where engine variance lives".
- Found by: code-simplicity-reviewer (P2), architecture-strategist (P3).

## Acceptance Criteria

- [ ] No `default` arm in any `switch (this)` or `switch (fold)` in `Engine`.
- [ ] Adding a constant to `Engine` without handling it fails compilation.
- [ ] `EngineTest` still passes on all five constants.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- code-simplicity-reviewer listed the four switches and the build setting they defeat.
- architecture-strategist judged the enum the right type and the switch-per-method the wrong growth pattern.

**Learnings:**
- A `default` arm on an enum switch is a silent opt-out of the compile-time check the project pays for.

### 2026-10-09 - Fix applied

**By:** Claude Code

**Actions:**
- No switch in Engine has a default arm any more: each names every engine and ends with throw new AssertionError(this), with the per-engine query texts moved into small exhaustive helpers (sessionSchemasQuery, tableNameSettingQuery, schemaExistsQuery), so MissingCasesInEnumSwitch fails the build when an engine is added. The broader "identity plus data" restructuring stays a design note for when the per-engine table grows.

## Notes

- Option 1 now, Option 2 when the locking and collision rules are implemented.
