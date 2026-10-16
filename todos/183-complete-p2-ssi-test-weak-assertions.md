---
status: complete
priority: p2
issue_id: "183"
tags: [code-review, tests, isolation]
dependencies: []
---

# SerializableIntegrationTest can pass at SSI without SSI doing the work

## Problem Statement

- Fekete's read-only case passes at SSI on the absence of "anomaly" alone; the outcome there is fixed
  (the deposit commits first): the read-only sees 20/0 and the driver aborts 40001. Neither is asserted,
  nor that one side commits. It runs only through Read.ROWS at one timing, though design 5 reads as if
  all paths and timings cover it.
- In the CHECKED mode the per-table check scans the whole table; a leftover in-flight row from any
  group (failed record commits, more likely under SSI) aborts `d.read`, and the SSI branch passes.
  Nothing asserts `d.read` did not abort outside IN_FLIGHT.
- Nothing asserts the read modes take the paths claimed (P3).

## Proposed Solutions

### Option 1
At SSI assert `read-only sees 20/0` and `driver aborts` in the read-only case; assert no `d.read aborts`
except in IN_FLIGHT; reword design 5's test sentence; optionally assert the route through nativeSQL.

## Acceptance Criteria

- [ ] The assertions above in place; the test still passes at both storage levels.

## Recommended Action

## Work Log

### 2026-10-17 - Found

**By:** Claude Code
- From the review of milestone 7 and its fixes, d4949c0..322bd32.

### 2026-10-17 - Fixed

**By:** Claude Code
- SerializableIntegrationTest: each read mode's path asserted through nativeSQL; no `d.read aborts`
  outside IN_FLIGHT; the read-only case runs through all three read paths and asserts, at SSI, `read-only
  sees 20/0` and `driver aborts`. Design 5's test sentence matches.
