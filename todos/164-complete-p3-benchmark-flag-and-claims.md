---
status: complete
priority: p3
issue_id: "164"
tags: [code-review, performance, docs, testing]
dependencies: []
---

# RouteCostBenchmark: the bench flag is too broad and todo 008 overstates the result

## Findings

- driver/build.gradle.kts:86 uses `isPresent`: `-Dnativejdbc.bench=false` also disables assertions,
  and the flag without `--tests` runs the whole integration suite with the checks off.
- Dropping `-ea` disables assertions in every jar (mssql-jdbc 13.6 has 68 classes with asserts), so if
  the "was" figures were taken with `-ea` the before/after deltas are not purely the two parses; how
  "was" was measured is not recorded.
- Todo 008 marks "miss under 150 µs" done; it holds on MySQL and SQL Server only (PostgreSQL 782 µs;
  MariaDB and Oracle not run).
- proxy-step.md:418 attributes most of PostgreSQL's 0.7-0.8 ms to the EXPLAIN plan check; the benchmark
  does not measure that split.
- read-overhead.md's new paragraph drops the note that cold name resolution and catalog reads are not
  in the numbers.

## Proposed Solutions

### Option 1
`providers.systemProperty("nativejdbc.bench").map { it == "true" }`; an A/B with
`-da:com.scalar.db.nativejdbc...` only; record how "was" was taken; qualify the 008 criterion per
engine; measure or soften the EXPLAIN claim; restore the cold-path note.

## Acceptance Criteria

- [x] The docs and todo 008 state per-engine results and what the numbers include.

## Recommended Action

Option 1.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.

### 2026-10-16 - Fixed

**By:** Claude Code
- build.gradle.kts: assertions are off only for `-Dnativejdbc.bench=true`.
- A/B on the same code (MySQL / SQL Server / PostgreSQL key lookup, new text, µs): all off
  161 / 94 / 603, vendors on and driver off 153 / 93 / 591, all on 264 / 198 / 728. The vendors'
  assertions cost nothing measurable, so the before/after figures differ by the two parses.
- MySQL measured 104 and 161 µs in two runs: todo 008's criterion now says it holds on SQL Server,
  MySQL is at the limit, PostgreSQL is not under it. The PostgreSQL plan-check claim is now an
  estimate from the other engines; read-overhead.md has the cold-path note back and the A/B.
