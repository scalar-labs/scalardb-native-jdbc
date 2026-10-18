---
status: complete
priority: p2
issue_id: "231"
tags: [code-review, correctness, todo-219]
dependencies: []
---

# Batched ScalarDB writes don't count as a statement that ran

## Problem Statement

cb4844f added owner.ran(route) in a finally around runWrite (Proxies.java:909-911), but the batch loop
(Proxies.java:1072-1092) calls Writes.apply without it (only runPlain counts). After only batched writes,
setTransactionIsolation is allowed and a FIRST check passes although the writes' reads took the snapshot.
Verified by code reading.

## Proposed Solutions

### Option 1
Call owner.ran(route) in a finally after Writes.apply in the batch loop.

### Option 2
Count it in one place both write paths share.

## Acceptance Criteria

- [ ] A batch of ScalarDB writes followed by setTransactionIsolation fails with 25001
- [ ] A FIRST coarse read after such a batch aborts

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
- 2026-10-18: fixed: the batch loop calls owner.ran(route) after each write; test: a batch then setTransactionIsolation gives 25001.
