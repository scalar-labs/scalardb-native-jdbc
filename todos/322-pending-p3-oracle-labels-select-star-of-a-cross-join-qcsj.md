---
status: pending
priority: p3
issue_id: "322"
tags: [read, result-metadata, oracle]
dependencies: []
---

# On Oracle, `SELECT *` of a join of one table with itself labels columns QCSJ_C...

## Problem Statement

On Oracle, `SELECT * FROM tab0 cor0 CROSS JOIN tab0 cor1` through the driver labels the
columns `QCSJ_C000000000700000`, `QCSJ_C000000000700002`, ..., where the plain tables give
`COL0`, `COL1`, ... The values and the types match. An application reading such a column by
label fails, though a join's duplicate names are ambiguous to read by label anyway.

## Findings

- T4 sqllogictest, 2026-10-23: 26 queries per layout, in random/* (`CROSS JOIN` with `*`).
- `QCSJ_C` is the name Oracle gives a column when it turns an ANSI join into its own form and two
  of the join's inputs have a column of the same name; likely it shows here because the driver
  reads the tables through views or inline views. Not traced.

## Proposed Solutions

### Option 1: expand `*` of such a join into its columns, each aliased with its name
**Effort:** Medium. **Risk:** the expansion needs the read view's column order (todo 316).

### Option 2: leave it
Duplicate labels are read by position. **Effort:** none.

## Acceptance Criteria

- [ ] The 26 queries label their columns as the plain tables do, or the case is documented.

## Work Log

### 2026-10-23 - Found by T4 sqllogictest, after todo 320

**By:** Claude Code

### 2026-10-28 - Every query of the 15 files

**By:** Claude Code

At e8f3436, all 122,012 queries per layout on Oracle Free: 190 failures per layout, every one
this label (`SELECT *` of a `CROSS JOIN`, 156 in random/select, 29 in random/aggregates, 5 in
index/random); no other failure, and no difference in values.

## Resources

- docs/plans/testing.md, milestone T4; todo 320.
