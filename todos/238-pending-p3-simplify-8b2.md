---
status: pending
priority: p3
issue_id: "238"
tags: [code-review, simplicity]
dependencies: []
---

# Simplify the 8b2 code

## Problem Statement

- ranInTransaction is kept twice (Router.java:334 and Proxies.java:152) with identical updates; keep Router's.
- SERIALIZABLE->RR mapping spread over Proxies.java:253-268 and Router.java:1711-1716; move into Router
  (setIsolation, getTransactionIsolation returns router.isolation()).
- FIRST string marker (Router.java:1156,1164,1243) -> typed field on Route plus a passedFirst set.
- boolean locks next to boolean snapshot in Writes.plan (Writes.java:162,184,248): one enum or the level.
- Share syntax: Writes.java:1116 switches by engine where LOCK IN SHARE MODE works on both.
- Layout/hint guard copied three times (Router.java:693-702, 932-942, 1119-1127).
- Small: hoist singleSelect() (1110); redundant tableRefs().size()==1 (914); nested ternary (750-754);
  coarseRead parses twice (1131-1137, joinTokens already re-parses).

## Proposed Solutions

### Option 1
Apply what shrinks the code; skip the rest.

## Acceptance Criteria

- [ ] Tests still pass; less code

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
- 2026-10-18: partly done: Router's copy of ranInTransaction and the FIRST marker are gone with 233; the rest remains.
