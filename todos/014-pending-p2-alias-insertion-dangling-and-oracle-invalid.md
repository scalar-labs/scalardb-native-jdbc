---
status: pending
priority: p2
issue_id: "014"
tags: [code-review, correctness, sql]
dependencies: []
---

# Alias insertion emits dangling SQL when no alias is supplied, uses `AS` that Oracle rejects, and keys aliases by bare name

## Problem Statement

When `joinTokens` replaces a table that has no alias but is used as a column qualifier, it needs to alias the replacement so the qualifiers stay valid. Today that alias comes from `Naming.aliasFor`, and when it returns null the table is replaced while the qualifier is kept, producing SQL that no engine will run. No null is returned and the re-parse does not catch it, and the unit test asserts that dangling output as correct. When an alias is supplied, it is emitted as `rc_t AS o`, which Oracle rejects (ORA-00933) although Oracle is a supported engine. The alias bookkeeping is also keyed by the bare folded name, so two same-named tables in different schemas are confused.

## Findings

Lines refer to commit 06c137f.

- `sql/SqlAst.java:538-556` (alias planning) and `:605` (emission as `e.getValue() + " AS " + alias`).
- Verified by the lead against the built classes: `select "order".id from s."order" where "order".id = 1` with `replacementFor = "rc_order"` and `aliasFor = null` produces `select "order".id from rc_order where "order".id = 1` (dangling qualifier). With `aliasFor = "o"` it produces `select o.id from rc_order AS o where o.id = 1`.
- `SqlAstTest.joinTokens_replacesATable_andAddsAnAlias` (`SqlAstTest.java:114-116`) asserts the dangling output as the expected result, so the test passes for the wrong reason.
- `noAlias` and `aliasByName` (`:537`, `:550`) are keyed by the bare folded name. With `from s1.t, s2.t` where only `s1.t` is replaced, a column `s2.t.x` is wrongly re-qualified.
- The simplicity reviewer (item 13) observes that `aliasFor` is never a real choice: when a replaced, unaliased table is used as a column qualifier, the only alias that keeps every existing qualifier valid is the table's original name.

## Proposed Solutions

### Option 1: Alias the replacement with the table's original name; drop `aliasFor`; emit a bare alias

**Approach:** In `planRewrite`, when a replaced table has no alias and is used as a column qualifier, set the alias to the table's original (quoted as written) name: `plan.aliases.put(r, r.name())`. Remove `Naming.aliasFor`. Emit `rc_order "order"` (space-separated, no `AS`), which is valid on all five engines. Key `noAlias`/`aliasByName` on `(schema, name)`. Change the test to expect `select "order".id from rc_order "order" where "order".id = 1`.

**Pros:**
- Every existing qualifier stays valid without rewriting columns; smallest output change.
- Removes an interface method that had no meaningful alternative value.
- Fixes the Oracle `AS` problem in the same line.

**Cons:**
- An alias equal to a table name can collide if the same name is also a real table elsewhere in the FROM list (already refused by the single-table shape; for the `rc_T` route, refuse when the original name is also another table's name or alias).

**Effort:** Small (1-2 hours)

**Risk:** Low

---

### Option 2: Keep `aliasFor`, fail closed when it returns null

**Approach:** Return null from `joinTokens` when `usedAsColumnQualifier(r)` and `aliasFor(r)` is null; change the test to expect null; emit the alias without `AS`; key the maps on `(schema, name)`.

**Pros:**
- Keeps the caller in control of alias names.

**Cons:**
- Every caller must supply an alias that avoids collisions, duplicating the logic of Option 1 at each call site.

**Effort:** Small

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:110-112` - `Naming.aliasFor`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:530-556` - alias planning (`noAlias`, `aliasByName`)
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:605` - `" AS "` emission
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java:114-116` - the test asserting the wrong output

**Related components:**
- Todo 012 (fragment emitter) builds on the same planning code
- Todo 004 (engine-specific identifier quoting of the emitted alias)

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` section 1, per-engine table ("Identifier quoting"); 4.2 (`rc_T` route)
- Found by: feature-dev:code-reviewer (P2-5, P2-6), code-simplicity-reviewer (item 13)

## Acceptance Criteria

- [ ] `select "order".id from s."order" where "order".id = 1` with the table replaced emits a statement that parses and whose qualifiers resolve (`rc_order "order"`)
- [ ] No emitted alias uses `AS`
- [ ] `from s1.t, s2.t` with only `s1.t` replaced leaves `s2.t.x` unchanged (test)
- [ ] `t.*` and schema-qualified column plus replaced table plus rename are covered by tests
- [ ] `SqlAstTest.joinTokens_replacesATable_andAddsAnAlias` asserts valid SQL
- [ ] Unit tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by feature-dev:code-reviewer (P2-5, P2-6) during the `/ce:review` run on commit 06c137f; the "alias with the original name" remedy proposed by code-simplicity-reviewer (item 13)
- Reproduced by the lead with a probe against the built classes (both the dangling and the `AS` outputs)

**Learnings:**
- A test that asserts the current output verbatim can lock in a bug; assert properties (re-parse succeeds, qualifiers resolve) as well as text

---

## Notes

- Oracle accepts `AS` for column aliases but not for table aliases; keep that distinction if column aliasing is ever emitted.
