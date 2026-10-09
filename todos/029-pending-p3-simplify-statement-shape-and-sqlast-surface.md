---
status: pending
priority: p3
issue_id: "029"
tags: [code-review, simplification, sql]
dependencies: ["012", "018"]
---

# Dead accessors, a mirrored Builder and flags the rewrite cannot use in the sql package

## Problem Statement

About a fifth of the `sql` package has no caller in main and no consumer in design sections 4.1-4.3:
a `Builder` that mirrors fourteen fields, boolean flags and names-only lists where the two-arm builder
will need clause nodes, accessors nobody reads, a `Kind` enum nobody switches on, and hand-written
quoting helpers that JSQLParser 5.4 already provides. Less surface means less to keep correct when the
rewrite lands.

## Findings

- `StatementShape.Builder` is 45 lines of field mirroring: 14 final fields plus copy constructor
  (`driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:59-91`) and 14 identical Builder
  fields (`:347-367`).
- Flags that duplicate what the AST already says and that the 4.2 builder cannot use as booleans:
  `ordered()` `:219`, `limited()` `:228`, `insertRowCount()` `:276`, `returning()` `:294`, `selectColumns()`
  `:200` (goes empty on any alias). Keep `grouped()`, `selectAll()`, `lockMode()`, `setColumns()`,
  `insertColumns()`, `where()`, `whereColumns()`, `isKeyEquality()`: each is named by 4.2/4.3. See 012.
- `SqlAst` accessors with no caller in main: `source()` `:315`, the `cteNames` field and accessor `:212/:361`
  (`cteRefs()` is what `StatementShape` uses), `TableRef.quoted()` `:790`, `resolvedSchema()` `:819`,
  `isColumnQualifier()` and the `qualifier` flag `:751-756/:857`, `fullyQualifiedName()` `:829` (only
  `toString` uses it). Verified by the simplicity reviewer's grep (main=0 callers).
- `SqlAst.Kind` TRUNCATE/DDL/OTHER (`:73-84, :286-308`; DDL detected by class-name prefix): `kind()` has no
  caller; 4.1 routes SELECT, single-table DML and "anything else: refuse", so `statement instanceof Select` at
  the call site suffices.
- `SqlAst.unquote` / `isQuoted` (`:699-735`): JSQLParser 5.4 has `MultiPartName.unquote(String)` /
  `isQuoted(String)` and `Table.getUnquotedName()` / `Column.getUnquotedColumnName()`. Confirm `[x]` handling,
  then delete (-35 lines); if the library handles doubled quotes this also resolves the escape bugs in 018.
- `astRoot()` fallback (`:588-592`) re-parses the SQL for a statement with no tables, in which nothing can be
  replaced; return null or the source instead.
- Small cleanups: `Catalog.java:229` uses a fully qualified `java.util.LinkedHashSet` inline;
  `BEFORE_PREFIX` is used once (`:59, :258`); `NativeJdbcUrl.toString()` (`:95-98`) is unused;
  `Objects.requireNonNull` in `accepts` (`:41`) is redundant under `@NullMarked` with NullAway;
  the comment at `NativeJdbcDriver.java:66-68` is mis-wrapped.
- Estimated: `SqlAst` 1074 to ~750 lines, `StatementShape` 543 to ~440.
- Do not replace `joinTokens` with `Statement.toString()`: the token-join machinery (`SqlAst.java:453-691`) is
  design-mandated so that hints and comments survive verbatim, and the deparser mangles `DELETE/*+ */`.

## Proposed Solutions

### Option 1: Delete now, before the proxies land

**Approach:** Drop `Builder` (make `select/insert/update/delete/predicate` private instance methods that set
private fields; `of()` constructs, calls one, returns empty on false); delete the five flags, the unused
accessors, `Kind`, the quoting helpers (after a `[x]` check against `MultiPartName`), and the `astRoot()`
re-parse; apply the small cleanups.

**Pros:**
- Roughly 450 fewer lines before any consumer exists.
- The proxies are written against the surface that will stay.

**Cons:**
- Some of the flags may be re-added in another form by 012; coordinate.

**Effort:** Half a day

**Risk:** Low

---

### Option 2: Delete only what has zero callers, decide the rest with 012

**Approach:** Remove the unused accessors, `Kind`, `toString`, the redundant null check and the `astRoot()`
re-parse now; leave `Builder`, the flags and the quoting helpers until the fragment emitter (012) and the
unquoting fix (018) show what the rewrite reads.

**Pros:**
- No chance of deleting something 012 reintroduces.

**Cons:**
- `Builder` mirroring stays a while longer.

**Effort:** 2 hours

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:59-91, 200-294, 347-367`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:73-84, 212, 286-308, 315, 361, 588-592,
  699-735, 751-756, 790, 819, 829, 857`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:59, 229, 258`
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcUrl.java:41, 95-98`
- `driver/src/main/java/com/scalar/db/nativejdbc/NativeJdbcDriver.java:66-68`
- `driver/src/test/java/com/scalar/db/nativejdbc/sql/SqlAstTest.java`, `StatementShapeTest.java` (tests of
  the removed members)

**Related components:**
- 012 (fragment emitter) decides what replaces the flags; 018 (unquoting) overlaps the quoting helpers.

**Database changes:** No

## Resources

- `docs/design.md` sections 4.1, 4.2, 4.3 (what the rewrite reads from the shape).
- JSQLParser 5.4 `net.sf.jsqlparser.schema.MultiPartName`.
- Found by: code-simplicity-reviewer (P2-5, P2-6, P2-7, P3-10, P3-11, P3-12, P3-15, P3-16).

## Acceptance Criteria

- [ ] No public member of `sql` lacks a caller in main or a named consumer in design 4.1-4.3.
- [ ] `StatementShape` has no `Builder`.
- [ ] `[x]`-quoted identifiers round-trip through whatever unquoting is kept.
- [ ] `joinTokens` still re-joins original tokens (hints and comments preserved; existing corpus test green).
- [ ] `./gradlew check` passes.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated from the code-simplicity-reviewer report (P2-5, P2-6, P2-7, P3-10, P3-11, P3-12, P3-15,
  P3-16) of the seven-agent review of main @ 06c137f.

**Learnings:**
- The simplicity reviewer confirmed the token-join rewrite is required by the design, so the cuts are all
  around it, not in it.

### 2026-10-09 - New weight of the same kind in 75106c2

**By:** Claude Code

**Actions:**
- Listed in todo 050: the second unquoting rule in SqlAst (038), resolvedName x2 now callerless, treeValues, Engine.of unused while NativeJdbcUrl rebuilds the set, Engine.sameColumn, Catalog.engine(), QualifiedName.toString, Query/withConnection, Engines.url()/useSchema(), an inline new java.util.HashSet<>() in NativeJdbcUrl.

### 2026-10-09 - Status

**By:** Claude Code

**Actions:**
- Removed since the todo was written: hasSecondStatement (001), the reflective scan and the allowlist (003, 011), TableRef.foldedName/resolvedName/quoted/resolvedSchema, ColumnRef.resolvedName and SqlAst.unquote/fold/isQuoted/resolve (038).
- Still present: SqlAst.source(), cteNames(), TableRef.isColumnQualifier() and fullyQualifiedName(), SqlAst.Kind, StatementShape's mirrored Builder and the flags selectColumns/ordered/limited/insertRowCount/returning. The plan's milestone 3 (rewrite fragments) is where these are settled.

## Notes

- Pair with 012 so the fragment emitter and the deletions land as one change to the `sql` surface.
