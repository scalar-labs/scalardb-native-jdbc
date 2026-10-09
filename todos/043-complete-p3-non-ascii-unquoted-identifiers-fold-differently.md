---
status: complete
priority: p3
issue_id: "043"
tags: [code-review, correctness, engine]
dependencies: []
---

# Non-ASCII unquoted identifiers fold differently in the driver than on PostgreSQL and Oracle

## Problem Statement

`Engine.resolveIdentifier` folds an unquoted identifier with Java's `toLowerCase` / `toUpperCase(Locale.ROOT)`, which folds all of Unicode. PostgreSQL folds only ASCII `A-Z` in multibyte encodings, and Oracle's upper-case rule is NLS-based (`ß` stays `ß`, Java gives `SS`). A ScalarDB table whose name contains a non-ASCII letter is therefore resolved to a spelling the engine never stores, the engine's catalog query misses, and the table is classified PLAIN: a statement on it is forwarded verbatim. Rare in practice, real where it occurs.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:165-172`: `switch (fold)` applies `toLowerCase(Locale.ROOT)` for `LOWER` and `toUpperCase(Locale.ROOT)` for `UPPER` to the whole string.
- Verified live by security-sentinel on `postgres:16`: `CREATE TABLE Ärger` stores `relname` `Ärger`; `select * from ärger` fails with "relation does not exist". The driver folds `Ärger` to `ärger`, so `pg_class` misses and a ScalarDB table `Ärger` addressed unquoted is PLAIN.
- Oracle part by documentation (unverified): the upper-case fold is NLS-dependent; Java's `ß` → `SS` changes the length and never matches `all_objects`.
- `Locale.ROOT` itself is correct for the Turkish-i problem; the gap is Unicode versus ASCII-only folding.
- MySQL, MariaDB and SQL Server keep the case as written (`Fold.NONE`), so they are unaffected.

## Proposed Solutions

### Option 1: Fold ASCII letters only for PostgreSQL, same rule plus a documented caveat for Oracle

**Approach:** Replace the `toLowerCase`/`toUpperCase` calls with a loop that folds `A-Z`/`a-z` only (or `Character.isLetter` restricted to ASCII), which is exactly PostgreSQL's rule. Apply the same ASCII rule on Oracle and state in the javadoc and design that non-ASCII unquoted names on Oracle are not supported unquoted.

**Pros:**
- Exact for PostgreSQL; a few lines.
- No extra round trip.

**Cons:**
- Oracle non-ASCII names remain a documented gap.

**Effort:** 1 hour

**Risk:** Low

---

### Option 2: Let Oracle fold on the server

**Approach:** On Oracle, pass the unquoted name unfolded and bind `UPPER(?)` in the `all_objects` and `all_synonyms` queries of `Engine.resolve`, so the server's NLS does the folding; keep the ASCII rule of Option 1 for PostgreSQL.

**Pros:**
- Exactly the engine's rule on both engines.

**Cons:**
- The resolved spelling for Oracle then comes only from the query result, so `resolveIdentifier` alone is no longer enough for comparisons elsewhere (StatementShape column names).

**Effort:** Half a day

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:165-172` - the fold
- `driver/src/main/java/com/scalar/db/nativejdbc/engine/Engine.java:336-363` - Oracle resolution queries (Option 2)
- `driver/src/test/java/com/scalar/db/nativejdbc/engine/EngineTest.java` - folding cases
- `docs/design.md` - per-engine identifier rule

**Related components:**
- Todo 032 (table key per database), todo 049 (quoting styles per engine).

**Database changes:** None.

## Resources

- `docs/design.md` section 1, per-engine table ("unquoted names fold to upper case" on Oracle).
- PostgreSQL docs: identifier folding is ASCII-only in multibyte encodings.
- Found by: security-sentinel (P3, verified on PostgreSQL).

## Acceptance Criteria

- [ ] `Engine.POSTGRESQL.resolveIdentifier("Ärger")` returns `Ärger` and `resolveIdentifier("Orders")` returns `orders`.
- [ ] An integration case on PostgreSQL imports a table named `Ärger` and the unquoted lookup finds it.
- [ ] Oracle behaviour for non-ASCII unquoted names is either exact (Option 2) or documented as unsupported.
- [ ] `EngineTest` covers a non-ASCII name per engine.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- security-sentinel probed the fold against `postgres:16` and showed the engine keeps `Ärger` while the driver produces `ärger`.
- Oracle part taken from documentation; not run.

**Learnings:**
- "Fold like the engine" means the engine's character class, not Java's.

### 2026-10-09 - Decision and fix: the server folds on Oracle

**By:** Claude Code

**Actions:**
- PostgreSQL: Engine.resolveIdentifier lower-cases A-Z only, PostgreSQL's own rule, so Ärger stays Ärger. Oracle: an unquoted name with characters beyond ASCII is folded by Oracle itself (Engine.needsServerFold, Engine.serverFold with SELECT UPPER(?) FROM dual on the catalog connection), cached per name in the catalog; Java's own upper-casing for Oracle is now code point by code point, so it never expands ß into SS. Column names in StatementShape still fold in Java with that simple mapping (no server round trip on the statement path); recorded as a limit for non-ASCII column names on Oracle.
- Tests: EngineTest.nonAsciiNames_foldAsTheEnginesDo; on Oracle a table created unquoted as größe is imported under the name Oracle stored and found by the unquoted lookup.

## Notes

- Decision pending between Option 1 and Option 2; Option 1 is enough for the preview if the Oracle caveat is written down.
