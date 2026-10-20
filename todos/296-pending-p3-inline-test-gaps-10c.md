---
status: pending
priority: p3
issue_id: "296"
tags: [code-review, inline, test]
dependencies: []
---

# Test gaps after 10c on the inline layout

## Problem Statement

1. SnapshotIntegrationTest's todo-220 check (key lookups lock their rows, not the table) is skipped on
   inline tables, so on MySQL and MariaDB it is untested there apart from the 1000-row FOR UPDATE.
   A probe row with committed metadata does not obviously help: on MySQL the in-flight range's next-key
   lock reaches to the first committed entry, and a smaller key with tx_state 3 falls in that gap too.
2. InterleavingIntegrationTest runs the inline table with imported=false, so the NULL-metadata
   scenario never runs on it; and the suite runs the driver at READ COMMITTED only (ISOLATIONS is
   ScalarDB's), so the stronger levels on inline rest on the two level tests.
3. MoreWritesIntegrationTest's locking scenarios on inline run at READ COMMITTED only, as the
   decoupled ones do.
Review of 10c (6bce052); no P1 or P2 found, the level tests verified to interleave on inline.

## Proposed Solutions

1. An inline variant of the NULL-metadata interleaving scenario (set the row's metadata columns NULL).
2. Decide whether the probe of item 1 can be kept on inline, or record why not.

## Recommended Action

## Acceptance Criteria

- [ ] Done

## Work Log

- 2026-10-20: found in the review of 10c (6bce052).
