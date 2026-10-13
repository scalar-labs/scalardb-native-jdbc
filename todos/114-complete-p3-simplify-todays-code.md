---
status: complete
priority: p3
issue_id: "114"
tags: [code-review, quality]
dependencies: ["008"]
---

# Simplify today's code

## Problem Statement

The simplicity review found about 115 lines that can go, without behaviour changes.

## Findings

- **Router:**
  - the 15-argument `Route` copied twice to flip one field (add `with(...)`);
  - two `route()` overloads;
  - `forcedPerRow` used as a hidden parameter (pass it instead);
  - a third routing pass even when only the recheck expired (then `countsOnly` is already known).
- **Proxies:**
  - `vendor` and `target` hold the same statement;
  - replay state recorded for every statement.
- **SqlAst:**
  - `lift` / `liftGrouped` share their opening;
  - `joinTokensNumbered` repeats `joinTokens`;
  - GROUP BY key sorting (use a `LinkedHashMap`);
  - the function-name normalisation is written three times;
  - the exposed-name expression is written three times;
  - a redundant guard on `copyLimit`;
  - a double exit in `reachedThrough`;
  - `Grouping` copies lists it could share.
- **Benchmark:**
  - the static `driver` connection and `on()`;
  - the median computed three times;
  - the statistics switch duplicated with `load()`;
  - two catalog loops in `importForDriver`.

## Proposed Solutions

### Option 1
Fold into the statement-cache work (todo 008), which touches the same code. **Effort:** Small.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [x] Same tests pass; the listed duplication is gone (one item kept, see the work log).

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".

### 2026-10-14 - Done

**By:** Claude Code
- Router: `Route.with(...)` replaces the three hand copies; one `route(...)` helper; the per-row
  choices to force are a parameter of `decide`, not a field; `current` skips the second routing pass
  when only the in-flight recheck was due, since only the counts can then have moved.
- Proxies: the statement handler reads its vendor statement from `target` (`vendor()`), no second
  field; settings and parameters are recorded only for a route that depends on the in-flight
  counts (`recheckAt != 0`), the only kind prepared again.
- SqlAst: `opening`/`bodyEnd` shared by `lift` and `liftGrouped`; `joinTokens` and
  `joinTokensNumbered` share `joined`; GROUP BY keys come in order from a `LinkedHashMap`; `bareName`
  for the three function-name normalisations; `exposedName()` for the exposed name; the redundant
  guard before `copyLimit` removed; `Grouping` takes the check's lists. The `reachedThrough` item
  went with todo 112.
- Benchmark: `Result.of` computes the median and p95 once; `statistics(...)` builds the per-table
  statement, used by `importForDriver` and `load` on SQL Server; the metadata copy and statistics
  share one catalog loop.
- Kept: the benchmark's static `driver` connection and `on()`. Passing the connection instead adds a
  parameter to four methods, which is not simpler.
