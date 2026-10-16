---
status: complete
priority: p3
issue_id: "175"
tags: [code-review, jdbc, quality]
dependencies: []
---

# Small robustness gaps around the snapshot paths

## Problem Statement

- `Router.check` runs without the statement's query timeout (unlike `Writes.Run`).
- A locking read with NOWAIT at the snapshot levels fails with 55P03; design section 6 says 40001 there.
- The autocommit wrap switches autocommit off before `getFetchSize`/`setFetchSize(0)`, outside the try; and
  `endOwn` lets a rollback or setAutoCommit failure replace the read's own exception (use addSuppressed).
- `Router.nativeSql` returns a cached route without `current()`, so it can show the other level's text.
- `rs.unwrap` hands back the vendor result set, which skips the state check (as it skips anchoring).

## Proposed Solutions

### Option 1
Each in place: the timeout passed to check(); NOWAIT at snapshot as 40001 or the design updated; the switch
inside the try with suppressed exceptions; nativeSql through current(); unwrap documented as unchecked.

## Acceptance Criteria

- [ ] Each item fixed or documented.

## Recommended Action

## Work Log

### 2026-10-16 - Recorded

**By:** Claude Code
- From the end-of-milestone-7 review of d4949c0..a6336c0, verified against the code.

### 2026-10-17 - Fixed

**By:** Claude Code
- Router.check takes the statement's query timeout.
- A locking read's NOWAIT is dropped from the driver's in-flight handling at the snapshot levels: a row
  in flight aborts with 40001 (the statement's own NOWAIT still applies to its lock).
- Autocommit wrap: the fetch size is read before autocommit goes off and set inside the try; a failure
  ending the own transaction is added as suppressed to the read's.
- nativeSql routes a cached text through `current()`.
- A result set checked row by row refuses unwrap to the vendor's (0AN01).
- SnapshotIntegrationTest: NOWAIT, unwrap, nativeSQL across levels.
