---
status: pending
priority: p2
issue_id: "135"
tags: [code-review, correctness, writes, postgresql]
dependencies: []
---

# UPDATE and DELETE always abort on an imported table keyed by CHAR(n)

## Problem Statement

The data table's key is `character(n)`, ScalarDB's metadata table holds it as `character varying`
unpadded. S1 returns the padded value; bound back with `setObject` it matches the data table (bpchar
comparison) but never the metadata row, so every CAS updates 0 rows and the write aborts.

## Findings

- Probe confirmed on `kp.ch (k CHAR(4) PK, x TEXT)`.
- `Writes.bindKey` in the CAS and `skipOrAbort`.

## Proposed Solutions

### Option 1
Return the metadata table's own key (`m.k`) from S1 and bind that in metadata statements, the data
table's in the DML; or cast the bound value to the metadata column's type there.

## Recommended Action

## Acceptance Criteria

- [ ] UPDATE and DELETE work on a CHAR(n)-keyed imported table.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).
