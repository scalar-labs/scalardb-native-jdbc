---
status: pending
priority: p3
issue_id: "020"
tags: [code-review, performance, sql]
dependencies: ["008"]
---

# Reflective scan rescans per call and allocates reflection state per node; small per-call allocations on the hot path

## Problem Statement

`parameterCount()` and `nodeTypes()` walk the whole AST every time they are called, and the walk itself re-fetches and re-opens every JSQLParser field on every visit, so each statement pays reflection setup for every node on every scan. The cost is small next to parsing today (a few microseconds), but it runs on every execute once placeholders are re-bound, and a handful of helper accessors allocate on each call as well. This is an efficiency cleanup, not a correctness issue, and it belongs with the statement cache of todo 008.

## Findings

All line numbers refer to commit 06c137f; paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

- `sql/SqlAst.java:370-378` (`parameterCount`) and `:439-445` (`nodeTypes`) run a full reflective scan on every call; only `unknownNodes` is memoised per instance. Measured by the performance reviewer: a full scan costs 5-22 µs and 22-78 KB per statement depending on size.
- `sql/SqlAst.java:1055-1071`: `Class.getDeclaredFields()` returns fresh `Field` copies on each call and `setAccessible` is applied per copy, so every node pays reflection setup each scan. The `TreeSet<String>` at `:408` sorts class names on every scan.
- `catalog/TableInfo.java:130-134, 151-160`: `keyColumns()` and `nonKeyColumns()` build a new list per call, and `nonKeyColumns` is O(columns × keys).
- `sql/SqlAst.java:918-921`: `ColumnRef.qualifier()` allocates a `TableRef` per column per check.
- `sql/StatementShape.java:539-541`: `resolve()` lower-cases per comparison.
- Whole-pipeline measurement for context (performance reviewer, JDK 25, warmed): parse 99-165 µs, scan 5-6 µs, full shape + two rewrites + parameter count 320 µs. Parsing dominates; the scan is about 5 % of it.

## Proposed Solutions

### Option 1: Memoise and cache reflection state (recommended, together with todo 008)

**Approach:** Count parameters in the same pass as `unknownNodes` and memoise the result; keep a static `ClassValue<Field[]>` of already-accessible, non-static, reference-typed fields per class and a `ClassValue<Boolean>` for the known-type check; build the sorted type set once; precompute `TableInfo`'s key and non-key lists in the constructor; let `ColumnRef.qualifier()` return a cached value.

**Pros:**
- Expected 2-3× on the scan and about 10× less allocation, in a few lines
- No behaviour change

**Cons:**
- Only matters once statements are executed repeatedly; invisible without the statement cache

**Effort:** 1-2 hours

**Risk:** Low

---

### Option 2: Replace the reflective scan with visitors

**Approach:** Use JSQLParser visitors for parameter counting, WHERE columns and function detection (see todo 003's visitor option); keep the reflective walk only as a test oracle.

**Pros:**
- Removes the reflection cost entirely from production paths
- Also addresses the module-path failure of todo 003

**Cons:**
- Larger change; visitors do not fail closed on new fields of existing node types

**Effort:** 4-6 hours

**Risk:** Medium

---

### Option 3: Do nothing until measured in the proxy

**Approach:** Wait for the connection proxy and the statement cache, then measure whether the scan is visible at all.

**Pros:**
- Avoids optimising code that the cache may make irrelevant

**Cons:**
- `TableInfo` per-call lists and the `Field` copies are cheap to fix now and will be touched anyway

**Effort:** none now

**Risk:** Low

## Recommended Action

To be filled during triage.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:370-378, 408, 439-445, 918-921, 1055-1071`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:539-541`
- `driver/src/main/java/com/scalar/db/nativejdbc/catalog/TableInfo.java:130-134, 151-160`

**Related components:**
- Todo 008 (statement cache): makes the per-execute scan disappear for repeated SQL text
- Todo 003 (scan fails open): the visitor option there overlaps Option 2 here

**Database changes:** none

## Resources

- `docs/design.md` section 1 (placeholders re-bound per execute) and section 4.4
- Performance reviewer's scratch benchmarks (not in the repo): `scratchpad/bench/com/scalar/db/nativejdbc/sql/Bench.java`, `Bench2.java`
- Review agents: performance-oracle (P2-3, P3-2)

## Acceptance Criteria

- [ ] `parameterCount()` and `nodeTypes()` return memoised values after the first call
- [ ] Reflection `Field[]` per class is computed once per JVM (`ClassValue` or equivalent)
- [ ] `TableInfo.keyColumns()` / `nonKeyColumns()` return precomputed lists
- [ ] A microbenchmark or timing test shows the full scan at or below half its current time
- [ ] `./gradlew check` passes

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Consolidated findings from performance-oracle (P2-3, P3-2), which measured the scan and the pipeline against the built classes

**Learnings:**
- Parsing, not scanning, dominates per-statement cost; this item is a cleanup to do alongside the statement cache rather than a standalone priority

---

### 2026-10-09 - Partly resolved by 003

**By:** Claude Code

**Actions:**
- The reflective scan, its Field copies and the per-call rescans are gone; parameterCount() is computed once at parse. Remaining from this item: TableInfo.keyColumns()/nonKeyColumns() allocation per call, ColumnRef.qualifier() allocation, and resolve() lower-casing per comparison.

## Notes

- If todo 003 is resolved by moving to visitors, Option 2 here comes for free and Option 1 shrinks to the `TableInfo` and `ColumnRef` allocations.
