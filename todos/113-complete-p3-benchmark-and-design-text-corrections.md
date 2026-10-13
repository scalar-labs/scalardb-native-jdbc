---
status: complete
priority: p3
issue_id: "113"
tags: [code-review, documentation]
dependencies: []
---

# Benchmark and design text overstate some driver results

## Problem Statement

The "Through the driver" section and design 4.2 contain claims the data does not support.

## Findings

- **"Selective shapes ... within about 20%".** Not true for the IN subquery: the driver against the
  hand-written form is 1.6 vs 0.4 on Oracle, 3.2 vs 2.2 on MariaDB and 2.7 vs 2.2 on SQL Server.
  The open list names only PostgreSQL.
- **The routing-cost table is not "nothing cached".**
  - The driver itself contacts the server at prepare: PostgreSQL's `EXPLAIN` and the MySQL/MariaDB
    `COUNT`. Both were cached for most of the 1,300 repetitions.
  - A first-seen statement pays a round trip more than shown.
- **MariaDB pagination.** "Close to the plain query" holds at 100 rows in flight only; it is 5.9x at
  10k.
- **Design 4.2.** It says the per-row form "measured flat", which holds only for key-reached
  shapes.

## Proposed Solutions

### Option 1
Correct the text. **Effort:** Small.

## Recommended Action

## Technical Details

See Findings. Paths are under `driver/src/main/java/com/scalar/db/nativejdbc/`.

## Acceptance Criteria

- [ ] Each claim matches the tables.

## Work Log

### 2026-10-13 - Found in review

**By:** Claude Code
- Review of 6388f1b..d719647 (today's 3b work), 2026-10-13: correctness (rewrites; re-prepare and
  routing), security, performance and simplicity reviewers. Claims marked "verified" were checked
  against the code before filing.

## Resources

- Commits a772e20, 48f523a, 785e126, cdbc969, 2892bad, 59c9309, d719647; docs/design.md 4.2;
  docs/benchmarks/read-overhead.md, "Through the driver".

### 2026-10-14 - Fixed

**By:** Claude Code
- docs/benchmarks/read-overhead.md "Through the driver": the 20% claim excludes the IN subquery with
  its numbers; the open list names the IN subquery on every engine; pagination on MariaDB holds at
  100 in flight only (5.9x at 10k); the routing-cost table says which server calls were cached and
  that a first-seen statement pays a round trip more.
- docs/design.md 4.2: "measured flat" is for tables reached through their key.
