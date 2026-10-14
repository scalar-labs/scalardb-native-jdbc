---
status: pending
priority: p3
issue_id: "119"
tags: [performance, split-read]
dependencies: ["095"]
---

# Measure 32 copies to set the split's limit

## Problem Statement

`SplitRead.MAX_COPIES` is 16: four inner tables, or a nullable table with two inner ones. A
statement that needs more copies is read whole through `rc_T`, which measured 24-1800x for a
fifth table. Whether 32 copies, or a partial split (some tables split, the rest through `rc_T`),
beats that has not been measured (todo 095's last criterion).

## Proposed Solutions

1. Add a 5-table shape to the benchmark: 32 copies, against `rc_T`, against 4 split and 1 on
   `rc_T`; set the limit, and choose a partial split, from the numbers.

## Acceptance Criteria

- [ ] The 5-table measurements recorded in `docs/benchmarks/read-overhead.md`.
- [ ] `MAX_COPIES` and the fallback set from them.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- Split out of todo 095 when milestone 3c was done.
