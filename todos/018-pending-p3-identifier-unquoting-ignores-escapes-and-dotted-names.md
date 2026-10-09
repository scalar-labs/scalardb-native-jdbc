---
status: pending
priority: p3
issue_id: "018"
tags: [code-review, correctness, sql]
dependencies: []
---

# Identifier unquoting ignores doubled delimiters, mismatched pairs, `..` and dotted quoted names

## Problem Statement

`SqlAst.unquote` and `SqlAst.isQuoted` strip the outer delimiters of a quoted identifier but do not undo the doubling an engine uses to escape a delimiter inside the name, and they accept an opening delimiter paired with the wrong closing one. A ScalarDB table whose name contains a quote character therefore never matches the catalog and is treated as plain, which forwards its statements unrewritten. Two related token-range edges, the SQL Server `..` token and a quoted name containing a dot, produce either a truncated replacement range or a lookup of the wrong table. Names like these are rare in practice, which keeps this at P3, but the failure mode is the silent one the design forbids.

## Findings

All line numbers refer to commit 06c137f; paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

- `sql/SqlAst.java:699-709` (`unquote` / `isQuoted`): doubled delimiters are not unescaped. **Verified** by the security reviewer's probe: `"a""b"` resolves to `a""b` where the engine sees `a"b`; `` `a``b` `` resolves to `` a``b ``; `[a]]b]` likewise.
- Mismatched pairs are accepted: `"x]` is unquoted to `x` while `isQuoted` says false for the same text, so the two helpers disagree. **Verified** by the same probe.
- Consequence: a ScalarDB table with a quote in its name never matches `full_table_name`, the catalog answers PLAIN, and the statement is forwarded verbatim (writes bypass the protocol).
- `nameEndOf` (`:678`) does not handle the `..` token (SQL Server `db..t`), so the replacement range stops early and the mangled output is caught only if the re-parse happens to fail.
- JSQLParser splits a quoted `"a.b"` into schema `a` and table `b`. **Verified** by the security reviewer's probe: `select * from "a.b"` is reported as `tableRefs=["a"."b"]` and rewritten to `V_b`, so the user's table `a.b` is looked up as `a`.`b`. The catalog cache key has the same ambiguity (todo 009).
- The simplicity reviewer notes that JSQLParser 5.4 ships `MultiPartName.unquote(String)` and `MultiPartName.isQuoted(String)`, plus `Table.getUnquotedName()` and `Column.getUnquotedColumnName()`; if those handle doubled delimiters, the driver's helpers can be deleted (todo 029).

## Proposed Solutions

### Option 1: Replace the helpers with JSQLParser's own (recommended if they pass the probe)

**Approach:** Check `MultiPartName.unquote` / `isQuoted` against the four cases (`"a""b"`, `` `a``b` ``, `[a]]b]`, `"x]`), then delete `SqlAst.unquote` / `isQuoted` and call the library. Refuse identifiers that still contain `.` or a delimiter after unquoting until the engine strategy can quote them on emit.

**Pros:**
- Removes about 35 lines
- One definition of quoting shared with the parser that produced the tokens

**Cons:**
- Depends on the library's behaviour for the bracket style; must be probed first

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 2: Fix the helpers in place

**Approach:** Require a matching pair per style (`"…"`, `` `…` ``, `[…]`), unescape the doubled delimiter of that style, and make `isQuoted` and `unquote` share one implementation. Handle `..` in `nameEndOf`. Refuse identifiers containing `.` or quote characters.

**Pros:**
- No dependency on library behaviour
- Straightforward unit tests

**Cons:**
- Keeps code the library already provides

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 3: Refuse any quoted identifier containing a delimiter or a dot

**Approach:** Treat such names as "not understood" (parse returns empty), which is fail-closed, and leave full support for later.

**Pros:**
- Smallest change, closes the silent-PLAIN path immediately

**Cons:**
- Tables with such names cannot be used through the driver at all

**Effort:** 30 minutes

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:678, 699-735`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java`

**Related components:**
- Todo 004 (engine-specific identifier rules): the per-engine `Identifiers` type is where quoting on emit belongs
- Todo 009 (catalog cache key ambiguity for dotted names)
- Todo 029 (delete helpers JSQLParser already provides)

**Database changes:** none

## Resources

- `docs/design.md` section 1, per-engine table, "Identifier quoting" row
- JSQLParser 5.4 `net.sf.jsqlparser.schema.MultiPartName`
- Review agents: security-sentinel (P3-2, P3-1 part), feature-dev:code-reviewer (16)

## Acceptance Criteria

- [ ] `"a""b"`, `` `a``b` `` and `[a]]b]` resolve to `a"b`, `` a`b `` and `a]b`
- [ ] `"x]` is not treated as quoted by either helper, or is refused
- [ ] `isQuoted` and `unquote` agree on every input in the test
- [ ] `db..t` is rewritten with the full three-part range, asserted by a `joinTokens` test
- [ ] A quoted name containing a dot is either looked up as one identifier or refused; it is never split
- [ ] `./gradlew check` passes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated findings from security-sentinel (P3-2, P3-1 part) and feature-dev:code-reviewer (16)
- The escape and mismatched-pair cases were verified by the security reviewer's probe against the built classes; the dotted-name split was verified the same way

**Learnings:**
- JSQLParser 5.4 already exposes unquoting helpers that may make the driver's own redundant

---

## Notes

- Probe `MultiPartName.unquote` with the bracket style before choosing Option 1; SQL Server brackets are the style most likely to differ.
