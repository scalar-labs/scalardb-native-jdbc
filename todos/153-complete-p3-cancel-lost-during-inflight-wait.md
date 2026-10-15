---
status: complete
priority: p3
issue_id: "153"
tags: [code-review, correctness, jdbc, writes]
dependencies: []
---

# cancel() during the bounded wait is lost, and the write then commits

## Problem Statement

While the key read sleeps between polls, `runningWrite` still points at the closed key-read
statement; pgjdbc's `cancel()` on an idle statement does nothing, so the cancel is swallowed and the
write goes ahead once the row settles. The same holds for a locking read's wait.

## Findings

- Probe (write reviewer): `inflightWaitMs=5000`, id 100 held prepared, UPDATE on thread A,
  `st.cancel()` 300 ms later, held rolled back a second later: the UPDATE returned 1 and committed.
- Writes.java settledKeys and LockPlan.apply wait loops; Proxies.java cancel handling.

## Proposed Solutions

### Option 1
`cancel()` sets a volatile flag on the statement handler, cleared at each execute; the wait loops and
`Run.prepare` check it through the Context and throw 57014.

## Recommended Action


## Acceptance Criteria

- [x] A cancel during the wait ends the statement with 57014 and nothing is written.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the review of f56c8d1 (milestone 4c).

### 2026-10-15 - Fixed

**By:** Claude Code
- cancel() sets a flag the wait and every driver statement check; 57014. Test: a cancel during the wait, the row unchanged.
