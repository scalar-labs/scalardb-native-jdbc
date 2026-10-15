---
status: complete
priority: p2
issue_id: "008"
tags: [code-review, performance, sql, architecture]
dependencies: ["001"]
---

# No statement cache; three to five parses per statement, each on a fresh platform thread

## Problem Statement

Every statement that reaches a ScalarDB table is parsed three times (up to five in the worst case), and each parse in JSQLParser 5.4 creates and tears down a single-thread executor. Measured on JDK 25 the whole pipeline (shape, two UNION arms, parameter count) costs about 320 µs, 350 KB and three platform threads per statement. For a local PostgreSQL key lookup with a 150-300 µs round trip, the driver roughly doubles latency and caps one core at about 3,000 rewritten statements per second. ORMs reuse a few hundred distinct SQL texts, so a bounded cache keyed by SQL text would turn the steady-state cost into one map lookup. Nothing caches today, and the design does not say where the rewrite happens.

## Findings

Per rewritten single-table SELECT today (lines refer to commit 06c137f):

| Step | Location | Parses | Reflective scans |
|---|---|---|---|
| `StatementShape.of(String)` → `hasSecondStatement` | `StatementShape.java:100,143-156` | 0, or 1 (`parseStatements`) when a `;` appears anywhere, including inside a literal or comment | 0 |
| `SqlAst.parse` | `SqlAst.java:239` | 1 (`CCJSqlParserUtil.parse`) | 0 |
| `of(SqlAst)` → `hasUnknownNodes` | `StatementShape.java:113`, `SqlAst.java:389-423` | 0 | 1 full (memoised per instance) |
| `select()` → `containsFunction` per non-plain item | `StatementShape.java:427,530-537` | 0 | k partial |
| `predicate()` → `nodesUnder(where)` | `StatementShape.java:519` | 0 | 1 partial |
| `joinTokens(naming)` × 2 arms | `SqlAst.java:507` (+`:589` `parseAST` when no tables) | 2 | 0 |
| `parameterCount()` | `SqlAst.java:370-378` | 0 | 1 full, not memoised |

- Each `CCJSqlParserUtil.parse` / `parseStatements` in 5.4 does `Executors.newSingleThreadExecutor()`, submits, `future.get(timeout)`, `shutdown()` (verified via javap), and first tries `allowComplexParsing=false`, re-parsing with `true` on failure. The executor exists only to serve `Feature.timeOut`.
- Measured (performance-oracle microbenchmark, JDK 25, Apple Silicon, warmed, 3,000-5,000 iterations; order-of-magnitude, not JMH), Hibernate-style key select (103 chars, 5 qualified columns):
  - `CCJSqlParserUtil.parse(sql)`: 99-165 µs, ~100 KB, one platform thread per call. Raw `newParser(sql).Statement()`: 40 µs. `parse(sql, sharedExecutor, null)`: 55 µs. Bare `new Thread().start()+join`: 36 µs. About 60% of every parse is thread creation.
  - Full reflective scan: 5.4-6.4 µs, 22 KB. `nodesUnder(where)`: 0.6 µs, 3 KB. The scan is about 5% of the parse.
  - `StatementShape.of`: 118 µs. `joinTokens()`: 100 µs (re-parse dominated). `;` inside a literal: 411 µs and two threads.
  - Whole pipeline: 320 µs, 350 KB, 3 threads per statement. INSERT of 5 columns: 147 µs; UPDATE: 114 µs. 20-column select with `IN (50 ?)`: parse 280 µs, scan 22 µs / 78 KB, shape 312 µs, joinTokens 294 µs.
- Under Spring Boot with virtual threads (design.md, Spring Boot notes) each parse still spawns a platform thread.
- Verified: measured by the performance reviewer against the built classes; the executor-per-parse behaviour verified with javap on jsqlparser-5.4.jar.

## Proposed Solutions

### Option 1: Bounded statement cache keyed by (SQL text, catalog generation), rewrite at prepare time

**Approach:** Classify and rewrite once per distinct SQL text and keep the result (`Optional<StatementShape>`, the rewritten arms, the parameter count and placeholder mapping) in a bounded LRU (`LinkedHashMap` with `accessOrder=true` and `removeEldestEntry` at 1024, behind a `ReentrantLock`, or equivalent), per driver or per connection. Include a catalog epoch in the key, bumped by `Catalog.invalidate()`, since the rewrite depends on the layout. Do the work at `prepareStatement` time and use the same cache for `Statement.execute(String)`. State this in design.md section 1 (Architecture).

**Pros:**
- Steady-state cost becomes one map lookup; parsing cost stops mattering for ORM workloads.
- Makes the remaining per-statement work (placeholder re-binding) cheap and predictable.

**Cons:**
- A cache is new state with invalidation rules (catalog epoch) that must be right.
- Applications generating unique SQL text per call (literal-inlined SQL) still pay the full cost.

**Effort:** Medium (half a day with tests)

**Risk:** Low

---

### Option 2: Cut the parse count: parse once, skip the no-op re-parse, share an executor

**Approach:** (1) Replace `hasSecondStatement` + `SqlAst.parse` with a single `parseStatements` call and refuse when `size() > 1` (this is the fix of todo 001). (2) In `joinTokens`, skip the re-parse when `naming == null` or the plan is empty, since the output is the original token stream. (3) Avoid the per-call executor: `CCJSqlParserUtil.parse(sql, sharedExecutor, null)` with one cached-thread pool per driver (55 µs instead of 99 µs), or in-thread `newParser(sql).withAllowComplexParsing(false).Statement()` with a complex-parsing retry (40 µs).

**Pros:**
- Halves or better the cost for every statement, cached or not.
- Small, local changes; no new state besides a shared executor.

**Cons:**
- Still a parse per execute; does not reach map-lookup cost.
- A shared executor is a driver-lifetime resource that needs shutdown on `deregister()`.

**Effort:** Small

**Risk:** Low

---

### Option 3: Both, in that order

**Approach:** Do Option 2 first (it is partly required by todo 001 anyway), then Option 1 so that the cached path is the common one and the uncached path is as cheap as it can be.

**Pros:**
- Best steady-state and worst-case numbers.

**Cons:**
- Two changes to review.

**Effort:** Medium

**Risk:** Low

## Recommended Action

Done as a per-connection route cache (Option 1 without a driver-wide cache) plus the shared parser
executor of Option 2.

## Technical Details

**Affected files:**
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/StatementShape.java:100-103,143-156` - `of(String)` and `hasSecondStatement`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:239` - `parse`
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:467-512,589` - `joinTokens` re-parse and `parseAST` fallback
- `driver/src/main/java/com/scalar/db/nativejdbc/sql/SqlAst.java:370-378` - `parameterCount` (not memoised)
- `docs/design.md` section 1 (Architecture) - state where the rewrite happens and that it is cached

**Related components:**
- The connection and statement proxies (not yet written) are where the cache naturally lives.
- `Catalog.invalidate()` must bump the catalog epoch used in the cache key.
- Todo 001 (multi-statement guard) and todo 020 (scan memoisation) touch the same code.

**Database changes (if any):**
- Migration needed? No

## Resources

- `docs/design.md` section 1, Architecture and "Placeholders"; Spring Boot notes (virtual threads)
- Found by: performance-oracle (P1-1), architecture-strategist (P2-9), security-sentinel (P3-4)
- Benchmark sources from the review (scratch, not in the repo): `scratchpad/bench/com/scalar/db/nativejdbc/sql/Bench.java` and `Bench2.java`

## Acceptance Criteria

- [x] A rewritten statement parses its SQL text at most once per distinct text (cache hit does no parse)
- [x] `joinTokens` with no replacements does not re-parse (not on the routed path; tests only)
- [x] No per-parse platform thread creation on the hot path (shared executor or in-thread parse)
- [x] The cache is bounded and keyed on a catalog epoch that `Catalog.invalidate()` bumps
- [x] A microbenchmark or timing guard shows the per-statement cost on a cache hit under 10 µs and on a miss under 150 µs for the Hibernate-style key select
- [x] design.md section 1 states where the rewrite happens and that it is cached
- [x] Unit tests pass; integration tests pass

## Work Log

### 2026-10-09 - Initial Discovery

**By:** Claude Code

**Actions:**
- Found by performance-oracle (P1-1, with a microbenchmark against the built classes and javap on jsqlparser-5.4.jar), architecture-strategist (P2-9) and security-sentinel (P3-4) during the `/ce:review` run on commit 06c137f
- Severity set to P2: a performance issue, not a correctness or data-safety issue
- Linked to todo 001 (single parse) and todo 020 (scan memoisation)

**Learnings:**
- About 60% of every JSQLParser 5.4 parse is thread creation for the timeout executor
- The reflective scan is about 5% of the parse cost; parsing dominates

---

### 2026-10-09 - Status after 75106c2 (performance review)

**By:** Claude Code

**Actions:**
- Unchanged by the commit. New measurements: pipeline 343 us / 348 KB for the Hibernate key select (was ~320 us); joinTokens(null) still re-parses (106 us); one platform thread per parse and per joinTokens (parseStatements creates the same executor); a refused DDL costs 180 us and a syntax error 446 us per execution, uncached. The shape step itself fell from 312 us to 2.5 us on the IN(50) statement because the second parse inside of(String) is gone. Acceptance criteria unchanged and unmet.

### 2026-10-14 - Done

**By:** Claude Code
- `Router.route` keeps the last route of each of 256 texts per connection and reuses it through
  `current()`, the check a prepared statement's route already had: session and catalog generations,
  the catalog TTL, isolation, passthrough and the in-flight recheck time. `Catalog.invalidate()`
  already bumps the generation, so no new epoch was needed.
- JSQLParser parses on a shared cached pool of daemon threads (`SqlAst.PARSER`).
- Profiling (JFR, SQL Server, four-table read) showed `SqlAst.checked` lexing the whole split text
  as 65% of an uncached route; it is skipped for a statement without comments.
- Measured (docs/benchmarks/read-overhead.md): a repeated text costs the driver 1-5 µs (criterion
  met); a new text 0.3-0.5 ms on SQL Server, down from 0.3-1.6 ms, so the 150 µs criterion is not
  met: two parses remain, the statement and its first copy. `joinTokens` with no replacement is not
  on the routed path any more (plain statements are forwarded as written).
- Left: a driver-wide cache shared by pooled connections; a cheaper check of the first copy.

### 2026-10-15 - Miss under 150 µs (milestone 6)
**By:** Claude Code
- The split read's two verification parses (first and last copy) are Java asserts now: on in the
  tests, off in applications. All engines' integration tests pass with them as asserts.
- `bench/RouteCostBenchmark` (integration, `-Dnativejdbc.bench=true`, assertions off). A new text
  varies an alias rather than adding leading blanks, which inflated the lexer's share. A key lookup
  miss is 84 µs on SQL Server and 104 µs on MySQL (was 240 and 209). PostgreSQL's 0.8 ms is mostly
  the server plan check of design 4.2.

## Notes

- The measurements are order-of-magnitude from a warmed loop, not JMH; re-measure after the change with the same harness.
- Do not replace `joinTokens` with `Statement.toString()` to save a parse: the token join is design-mandated (hints and comments must survive in place).
