---
status: complete
priority: p3
issue_id: "144"
tags: [code-review, jdbc, anchors]
dependencies: []
---

# Smaller details of anchored reads

## Problem Statement

Rare or latent problems of the hidden columns and their SQL, found in the review of 4b.

## Findings

- `getString("scalardb_native_x0")` (and x1, x2) return the hidden key, tx_id and version; only
  `findColumn` is guarded (probe).
- `getGeneratedKeys()` after an anchored read is wrapped as anchored and reports -3 columns (probe): any
  result set a statement returns is wrapped with the last execute's anchoring.
- `SELECT x FROM t AS scalardb_native_meta` makes the version subquery bind to its own alias: 21000, or a
  wrong version with one metadata row (probe); the comment calling it an alias the statement cannot use
  is wrong.
- `SELECT x FROM t ORDER BY 2` succeeds sorted by the hidden key, where PostgreSQL raises 42P10 (probe).
- `Router.keepText` keeps `now.anchored` with `kept`'s text (latent: count-only routes are MySQL only).
- An updatable result set (`CONCUR_UPDATABLE`) reports updatable and fails in `updateRow` with a syntax
  error (probe; probably older than 4b): refuse updatable result sets on ScalarDB tables clearly.

## Proposed Solutions

### Option 1
Guard labels through the vendor's findColumn; wrap as anchored only results of execute*/getResultSet;
refuse anchoring when the exposed name is the lookup's alias (or choose another alias); refuse an ORDER BY
position past the select list in `lift`; carry `kept.anchored`; refuse CONCUR_UPDATABLE on reads of
ScalarDB tables.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] Each item fixed, with a probe or unit test where cheap.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of ef68593 and b312ced (milestone 4b).

### 2026-10-15 - Fixed

**By:** Claude Code
- Hidden columns guarded by label too; only execute*/getResultSet results are anchored; the lookups' alias refused as the table's; an ORDER BY position past the select list leaves the split to the database; keepText carries the kept route's anchoring; an updatable result set over a rewritten ScalarDB read is refused (0AN01). Test "144".
