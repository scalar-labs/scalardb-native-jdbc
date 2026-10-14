---
status: complete
priority: p2
issue_id: "136"
tags: [code-review, jdbc, writes]
dependencies: []
---

# setQueryTimeout and cancel() do not reach a write's own statements

## Problem Statement

Settings and `cancel()` go to the vendor statement, which never runs for a write; the driver's
statements carry no timeout and cannot be cancelled. A write blocked on a lock waits past
`@Transactional(timeout)`, JPA query timeouts and pool abandon logic.

## Findings

- Probe confirmed: `setQueryTimeout(1)` and `cancel()` at 1.5 s; the write finished after the lock
  released at 4 s.

## Proposed Solutions

### Option 1
Pass the statement's query timeout into `Writes.run` and apply the remaining budget to each driver
statement; keep the running driver statement in a volatile field that `cancel()` forwards to.

## Recommended Action

Option 1.

## Acceptance Criteria

- [x] The probe's write fails with a timeout or cancel error near 1 s.

## Work Log

### 2026-10-14 - Recorded

**By:** Claude Code
- From the review of cd135be (milestone 4a).

### 2026-10-14 - Fixed

**By:** Claude Code
- Writes.Run gives each driver statement the rest of the statement's query timeout and publishes the running statement, which cancel() reaches. Test "136" (timeout of 1 s against a 4 s lock fails within 3 s, autocommit restored).
