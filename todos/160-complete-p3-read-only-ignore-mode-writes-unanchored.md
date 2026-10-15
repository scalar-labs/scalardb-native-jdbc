---
status: complete
priority: p3
issue_id: "160"
tags: [code-review, correctness, jdbc, anchors]
dependencies: []
---

# A read-only transaction under readOnlyMode=ignore can write without anchors

## Problem Statement

`Router.anchoring()` (:592) and `Proxies` `recording` (:698) assume a read-only connection cannot write
(todo 146), and 933f8b6 also skips reading versions there. Under pgjdbc `readOnlyMode=ignore` the vendor
does not enforce read only, so a write after unanchored reads is a lost update.

## Findings

- Probe: `openDriver(readOnlyMode=ignore)`, autocommit off, `setReadOnly(true)`, read id 1; another
  connection updates id 1; the driver's `UPDATE ... WHERE id=1` returned 1 instead of 40001.

## Proposed Solutions

### Option 1
Refuse writes of ScalarDB tables while `isReadOnly()` (SQLState 25006), so the assumption holds on any
vendor setting.

## Acceptance Criteria

- [x] The probe gets 25006, never 1.

## Recommended Action

Option 1.

## Work Log

### 2026-10-15 - Recorded

**By:** Claude Code
- From the end-of-first-deliverable review of 14f48e2..e3ff66a.

### 2026-10-16 - Fixed

**By:** Claude Code
- `Writes.within` refuses a write of a ScalarDB table on a read-only connection with 25006, before
  anything runs; a locking read goes through it too, as PostgreSQL refuses FOR UPDATE there.
- Test: MoreWritesIntegrationTest.readOnlyWrite (readOnlyMode=ignore); without the fix the update
  succeeded.
