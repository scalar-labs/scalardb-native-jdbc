---
status: complete
priority: p1
issue_id: "132"
tags: [code-review, correctness, writes, tombstone]
dependencies: []
---

# A DELETE of a row the driver stamped from NULL metadata leaves no tombstone

## Problem Statement

On an imported table, a row without metadata read by a ScalarDB transaction Z (anchor `tx_id IS NULL`),
then updated by the driver (which stamps metadata) and deleted by the driver (which, seeing a real
`tx_id`, deletes the metadata row), lets Z's update commit: its "insert metadata if none exists" succeeds
and the row comes back. Design 4.5 promises the tombstone stops exactly this.

## Findings

- Probed on keys with no metadata row and with all-NULL metadata, with the UPDATE and DELETE in one
  driver transaction and in two: Z commits, the row is back with Z's value.
- `Writes.cas` DELETE branch; the anchor knows only the current metadata.
- The two-transaction form is the same existence ABA ScalarDB has on its own (update, delete, stale
  NULL-anchored prepare); the driver still promises the tombstone.

## Proposed Solutions

### Option 1
On imported (LEFT OUTER) tables a DELETE always leaves a stamped tombstone (UPDATE the metadata row to a
fresh tx_id, state 3) instead of deleting it; design 4.5 updated: tombstones then remain for every
driver-deleted key of an imported table, taken over by a later INSERT.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] The probe's interleavings end with Z's commit failing and the row deleted, both variants.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).

### 2026-10-14 - Fixed

**By:** Claude Code
- An imported table's DELETE stamps the metadata row (version + 1) instead of deleting it; a row without one gets the stamp from its data row before the DELETE. Design 4.3 step 3 and 4.5 updated. Test "132" (update then delete, the stale ScalarDB update fails); the older test now expects the tombstone on the imported table.
