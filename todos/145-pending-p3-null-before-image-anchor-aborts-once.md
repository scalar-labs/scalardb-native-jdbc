---
status: pending
priority: p3
issue_id: "145"
tags: [code-review, anchors, imported-tables]
dependencies: []
---

# A row without metadata read in flight anchors (NULL, 0) and aborts once after ScalarDB's rollback

## Problem Statement

ScalarDB's prepare of a row without metadata writes `before_tx_id` NULL and `before_tx_version` 0; a
read through the in-flight copy anchors (NULL, 0). After the rollback the metadata is all NULL, S1 reads
(NULL, NULL), and the write aborts although the row did not change. A retry succeeds; only until the
backfill has run.

## Findings

- Probe (lost-update reviewer), Anchors.Version.is; Writes.cas already ignores the version when tx_id
  is NULL.

## Proposed Solutions

### Option 1
When tx_id is NULL on both sides, treat the versions as equal: every write that changes data sets a
non-NULL tx_id.

## Recommended Action

## Acceptance Criteria

- [ ] The probe's sequence writes the row.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of ef68593 and b312ced (milestone 4b).
