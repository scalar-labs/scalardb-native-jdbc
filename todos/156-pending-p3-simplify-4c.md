---
status: pending
priority: p3
issue_id: "156"
tags: [code-review, simplicity]
dependencies: []
---

# Simplify the 4c code

## Problem Statement

The simplicity review of d7be4f0, f56c8d1 and cdfc58a found about 85-95 lines to remove, mostly in
the plumbing that carries result sets, contexts and anchors between Writes and the statement proxy.

## Findings

- `Writes.run` is dead: runWrite and runBatch call `within` and `apply`.
- The `Proxies.Locked` holder: record anchors in the `locked` callback and reuse `rows[0]`; the
  IllegalStateException branch cannot run.
- `within` takes the Context only for `rollbackOnly`: pass a Runnable, drop `writeContext(null, null,
  null)` and the null checks.
- One helper for the two wait loops (`sleep(until, reason)`); a `bindAll` for the anyKey binding loops;
  one builder for the key-with-metadata SELECT (KeyedPlan.skipOrAbort, LockPlan).
- Drop `Plan.inserts()` for `instanceof InsertPlan`.
- The locking route's `.anchored(...)` is never read (runWrite returns first); drop it and the
  `prepared.write == null` guard in getMetaData.
- Merge `writeReturns` into `writeCount` for the update counts; drop its `volatile`.
- One helper wrapping a result set in ChildHandler; reuse `Router.exposed` in Writes.
- Locals in runWrite shadow handler fields (`prepared`, `recording`, `epoch`).

## Proposed Solutions

### Option 1
Apply the items above, behaviour unchanged; after 148-155, which touch the same code.

## Recommended Action


## Acceptance Criteria

- [ ] The items applied; check and the integration tests green.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the simplicity review of milestone 4c.
