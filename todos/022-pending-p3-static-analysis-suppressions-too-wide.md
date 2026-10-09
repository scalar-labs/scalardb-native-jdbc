---
status: pending
priority: p3
issue_id: "022"
tags: [code-review, quality]
dependencies: []
---

# Static-analysis suppressions are class-wide and one justification is inaccurate

## Problem Statement

Two SpotBugs suppressions are broader than the code they cover. The FindSecBugs SQL-injection suppression on `Catalog` applies to the whole class, so any future string concatenation into SQL there will be silently excused, and the `EI_EXPOSE_REP` suppression on `SqlAst` claims that every collection handed out is an immutable copy when two accessors hand out the mutable JSQLParser AST. Neither is a bug today; both weaken the checks the build relies on and will matter as soon as the rewrite starts emitting SQL at many sites.

## Findings

All line numbers refer to commit 06c137f; paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

- `catalog/Catalog.java:36-40`: `@SuppressFBWarnings("SQL_INJECTION_JDBC")` is class-wide. The only concatenated text today is `metadataSchema`, validated by a full-match `IDENTIFIER` regex and wrapped in the engine's quote string (the security reviewer checked this and found it safe on all five engines), but the suppression will hide any future concatenation anywhere in the class. The rewrite will trip the same detector at every emit site.
- `config/spotbugs/exclude.xml` today covers `*Test` classes only, so there is no package-level exclusion for the code that builds SQL by design.
- `sql/SqlAst.java:66-69`: `@SuppressFBWarnings("EI_EXPOSE_REP")` with the justification "every collection handed out is an immutable copy". `SqlAst.statement()` (`:324`) and `StatementShape.where()` (`StatementShape.java:247`) hand out the mutable AST; a caller mutating it desynchronises the token chain from `source`, since `joinTokens` re-joins the original tokens. By code reading.

## Proposed Solutions

### Option 1: Narrow the suppressions and fix the justification (recommended)

**Approach:** Move the `SQL_INJECTION_JDBC` suppression from the class to the two methods that build SQL (`read` and `columns`), each with a justification naming the validated input; change the `EI_EXPOSE_REP` justification to state the real contract ("the AST is handed out for read-only traversal; mutating it desynchronises the token chain") and say so in the javadoc of `statement()` and `where()`.

**Pros:**
- Keeps the detector active for future code in the same class
- Documents the actual contract callers must honour

**Cons:**
- Each new emit site in the rewrite needs its own suppression

**Effort:** 30 minutes

**Risk:** Low

---

### Option 2: Package-level exclusion for the SQL-building packages

**Approach:** Exclude `SQL_INJECTION_JDBC` by package in `config/spotbugs/exclude.xml` for the packages that build SQL by design (catalog, the future rewrite package), keep it active elsewhere, and remove the class-level annotation.

**Pros:**
- One place to maintain as emit sites multiply
- No per-method annotations

**Cons:**
- Coarser than Option 1; the detector is off for whole packages

**Effort:** 30 minutes

**Risk:** Low

---

### Option 3: Leave as is and revisit when the rewrite lands

**Approach:** Keep the suppressions; address them with the first emit site.

**Pros:**
- No change now

**Cons:**
- The inaccurate justification stays; the class-wide suppression grows unnoticed

**Effort:** none

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:36-40, 109-113, 226-234`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:66-69, 324`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:247`
- `config/spotbugs/exclude.xml`

**Related components:**
- Todo 024 (API surface): deciding whether `sql` hands out JSQLParser nodes at all changes the `EI_EXPOSE_REP` story
- Todo 021 (SpotBugs on test sources)

**Database changes:** none

## Resources

- FindSecBugs `SQL_INJECTION_JDBC` detector documentation
- SpotBugs `EI_EXPOSE_REP` detector documentation
- Review agents: security-sentinel (P3-3), architecture-strategist (P3-6)

## Acceptance Criteria

- [ ] No class-wide `SQL_INJECTION_JDBC` suppression remains; each remaining suppression names the validated input
- [ ] The `EI_EXPOSE_REP` justification matches what `statement()` and `where()` actually return
- [ ] Javadoc on `statement()` and `where()` states the read-only contract
- [ ] `./gradlew check` (SpotBugs included) passes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated findings from security-sentinel (P3-3) and architecture-strategist (P3-6)
- The catalog SQL itself was reviewed and found safe by the security reviewer; this item is about the width of the suppression, not an injection

**Learnings:**
- A class-wide suppression added for one validated concatenation becomes a blind spot once the class grows

---

### 2026-10-09 - Status

**By:** Claude Code

**Actions:**
- Done with the catalog rework: the class-wide SQL_INJECTION_JDBC suppression on Catalog is gone; the suppressions are method-scoped (Catalog.load, Engine.resolvePostgresql/resolveOracle/one) with accurate justifications.
- Still open: SqlAst's EI_EXPOSE_REP justification ("every collection handed out is an immutable copy") is still inaccurate, since statement() hands out the mutable AST.

## Notes

- If todo 024 removes JSQLParser types from the public API, the `EI_EXPOSE_REP` suppression may become unnecessary.
