---
status: complete
priority: p3
issue_id: "044"
tags: [code-review, catalog]
dependencies: []
---

# The resolution cache key collides for an unqualified name and a schema literally named `null`

## Problem Statement

`Catalog.resolve` builds its cache key by string concatenation, and a `null` schema is rendered as the text `null`. An unqualified lookup of `t` and a qualified lookup of `"null".t` therefore share a key, and whichever runs first answers the other from the cache without asking the engine. It needs a schema named `null`, so it is rare, but the key is wrong by construction.

## Findings

- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:216`: `String.join("\u0000", sessionSchemas) + "\u0001" + schema + "\u0001" + table` with `schema == null` prints `null`.
- Verified by security-sentinel with a scripted connection: after `lookup([app], null, "t")` answered `app.t CLASSIC`, `lookup([app], "`null`", "t")` is answered from the cache with 0 engine queries as `app.t CLASSIC`, where the engine would have resolved schema `null`. In the reverse order the real table comes back PLAIN.
- feature-dev:code-reviewer found the same by reading.

## Proposed Solutions

### Option 1: A distinct marker for the null case

**Approach:** `(schema == null ? "" : "\u0002" + schema)` in the key, so a null schema and any real schema name cannot collide.

**Pros:**
- One line.

**Cons:**
- Still a hand-built string key.

**Effort:** 10 minutes

**Risk:** Low

---

### Option 2: A list-of-parts key

**Approach:** Key the map by `List.of(sessionSchemas, Optional.ofNullable(schema), table)` (or a small record-like class) so equality is structural.

**Pros:**
- No separator encoding at all; readable in a debugger.

**Cons:**
- Slightly more allocation per lookup (todo 047 already wants the hit path cheaper).

**Effort:** 30 minutes

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/Catalog.java:216` - key construction

**Related components:**
- Todo 047 (what the resolution cache stores), todo 035 (the lock around it).

**Database changes:** None.

## Resources

- Found by: feature-dev:code-reviewer (P3), security-sentinel (P3, verified with a scripted connection).

## Acceptance Criteria

- [ ] `lookup(session, null, "t")` and `lookup(session, "\"null\"", "t")` each run their own engine resolution.
- [ ] A `CatalogTest` case with a fake resolver asserts the two keys differ.

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- security-sentinel reproduced the collision with a scripted JDBC connection counting engine queries.

**Learnings:**
- A `null` inside a concatenated key is a value like any other; encode it or use structural keys.

### 2026-10-09 - Fixed with 035

**By:** Claude Code

**Actions:**
- The resolution key now encodes a null schema as "" and a present one as "\u0002" + schema, so an unqualified name and a schema named null no longer share an entry.

## Notes

- Fix together with 047 if the cache is reshaped.
