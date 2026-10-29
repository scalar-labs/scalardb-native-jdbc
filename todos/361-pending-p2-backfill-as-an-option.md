---
status: pending
priority: p2
issue_id: "361"
tags: [setup, tools, backfill, imported, decoupled, inline, oracle, mysql]
dependencies: ["360"]
---

# The backfill as an option of the setup, with a `backfill` command and a `check` for ScalarDB's stuck rows

## Problem Statement

The setup backfills every imported table, decoupled and inline. The driver's correctness doesn't
depend on it (design section 7, item 8). With todo 360, its speed doesn't either. What remains is
ScalarDB's own defect on imported decoupled tables: a rollback over a row with no metadata row
leaves an all-NULL metadata row that ScalarDB can't prepare again (section 1; left to ScalarDB,
2026-10-29).

The backfill itself costs: one transaction over every row and its locks. On an inline table it
rewrites the application's rows, likely the cause of the migrated rows behind the ninth Oracle run's
buffer busy waits.

## Proposed Solution

Design: docs/design.md section 3, "The backfill as an option".

- `setup`: backfill by default only for imported decoupled tables; `--backfill` / `--no-backfill`
  override the default.
- A new `backfill` command, which also repairs rows ScalarDB's defect left.
- `check` counts metadata rows with `tx_state` NULL on imported decoupled tables, and exits 1 when
  it finds any.
- On Oracle, a metadata table not backfilled gets statistics as if it were (from the data table's),
  then locked. To be measured on the container first, with MySQL's histogram.
- The decoupled default flips to off once ScalarDB ships the fix; the docs name the version.

## Acceptance Criteria

- [ ] The container measurement of plans with and without the backfill (Oracle, MySQL), before
      the statistics part is built.
- [ ] `setup` defaults and flags, its output line for a table not backfilled.
- [ ] The `backfill` command.
- [ ] `check`'s count and exit code.
- [ ] Oracle's statistics as if backfilled (MySQL's if the measurement says so).
- [ ] The tests listed in the design paragraph.
- [ ] getting-started.md: the setup's flags, and when to run the backfill.

## Work Log

- 2026-10-29: designed, at the user's request, in parallel with todo 360.
