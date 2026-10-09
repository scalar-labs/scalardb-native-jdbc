---
status: pending
priority: p3
issue_id: "051"
tags: [code-review, usability, catalog, build]
dependencies: []
---

# Refusal messages do not name the table to use, the engine selector fails on a typo, and the catalog TTL has no operator-facing name

## Problem Statement

Three small things an automated caller or operator meets first. The three refusals say "name the ScalarDB table instead" without naming it, though the snapshot holds the name one field away. `-Dnativejdbc.engines=postgres` fails every parameterised integration method with "No enum constant" instead of listing the accepted values. The 60 s catalog TTL, a correctness knob (a table imported after the load is plain until it expires), is a constructor argument that no property names and no document mentions. Separately, this commit added about fifteen more silent refusal causes to `StatementShape`, and removed the helper todo 016 planned to use for reporting them.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:375-377, 396-398, 413-417`: the messages carry the stored name, the action and SQLSTATE 42000, but not the table to use; `v.left` (virtual table of an imported table), `v.name` (data table of a created one) and `byRight.get(full)` (`v.name` or `v.left` for the metadata table) hold it. `CatalogTest.theThreeNamesAStatementMustNotUse_areRefused` (`driver/src/test/java/com/scalar/db/nativejdbc/catalog/CatalogTest.java:112-127`) asserts the SQLSTATE on the first case only.
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/testing/Engines.java:117-128`: `selected()` calls `valueOf` at `:125`; `README.md:56-59` lists the five names but not that the property goes on `./gradlew integrationTest` (or `check`).
- `Catalog.java:61` (`DEFAULT_TTL`) and `:133-147` (five-argument constructor): the TTL is injectable from Java only; `docs/design.md:279-283` lists `inflightWaitMs`, `passthrough` and `metadataSchema` as the driver properties.
- `StatementShape.of(String, Engine)` (`driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:116-118`) still flattens every cause into `Optional.empty()`; this commit added table hints and `TABLESAMPLE`/`PIVOT` (`:363-370`), `SKIP LOCKED`/`NOWAIT`/`WAIT` (`:444-446`), `INSERT IGNORE`, `ON DUPLICATE KEY`, `ON CONFLICT`, `OUTPUT`, Oracle hints (`:487-492`, `:530-532`, `:558-560`) and "predicate not inspectable" (`:572-575`). `SqlAst.unknownNodes()`, which todo 016 cited as the ready-made reason text, no longer exists.

## Proposed Solutions

### Option 1: Three small edits plus a note on 016

**Approach:** Append "use `<schema>.<table>`" to each refusal and assert the suggested name and SQLSTATE in `CatalogTest` for all four cases. Catch `IllegalArgumentException` in `Engines.selected()` and rethrow naming the five values and the property; add `./gradlew integrationTest -Dnativejdbc.engines=postgresql` to the README. Name the TTL in the design's property list as `scalardbnative.catalogTtlMs` (default 60000, 0 = never trust), which also gives todo 026 its refresh knob. Record in 016 that the new direction is a `Builder.refuse(String why)` that records the clause, with `of` returning the reason alongside the empty result.

**Pros:**
- Each change is a few lines and independent.

**Cons:**
- The TTL property itself is implemented only when the driver holds catalogs (todo 037).

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 2: Fold the message and TTL items into 016 and 037

**Approach:** Treat the refusal text as part of the error-contract work of 016 and the TTL property as part of the driver's catalog ownership in 037; fix only the selector now.

**Pros:**
- No separate pass.

**Cons:**
- The messages stay unhelpful until 016 lands.

**Effort:** 15 minutes now

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:375-377, 396-398, 413-417`
- `driver/src/test/java/com/scalar/db/nativejdbc/catalog/CatalogTest.java:112-127`
- `driver/src/integrationTest/java/com/scalar/db/nativejdbc/testing/Engines.java:117-128`
- `README.md:56-59`
- `docs/design.md:279-283` - property list

**Related components:**
- Todo 016 (refusal error contract), 026 (catalog refresh from the JDBC surface), 037 (driver-owned catalogs and their properties).

**Database changes:** None.

## Resources

- Found by: agent-native-reviewer (P3 x3, and the note on 016).

## Acceptance Criteria

- [ ] Each of the four refusals names the table to use; `CatalogTest` asserts message and SQLSTATE for all four.
- [ ] `-Dnativejdbc.engines=postgres` fails with a message listing `postgresql, mysql, mariadb, sqlserver, oracle` and the property name.
- [ ] README shows the full command with the property.
- [ ] `docs/design.md` lists the catalog TTL property with its default and the meaning of 0.
- [ ] Todo 016's work log records the new refusal causes and the `Builder.refuse` direction.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- agent-native-reviewer walked the automated-caller surface of the commit: refusal messages, the selector, the TTL, and the growth of silent refusal causes.

**Learnings:**
- A refusal that knows the right answer should say it.

### 2026-10-09 - Status

**By:** Claude Code

**Actions:**
- Done: refusal messages name the table to use (033 fix); the catalog TTL is named in the design as scalardbnative.catalogTtlMs (027).
- Still open: Engines.selected() fails on a typo with "No enum constant"; the refusal reasons are todo 016 (plan milestone 0).

## Notes

- The selector fix is test-only and can go in with any commit.
