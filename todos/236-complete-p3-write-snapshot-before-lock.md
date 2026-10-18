---
status: complete
priority: p3
issue_id: "236"
tags: [code-review, correctness, mysql, mariadb]
dependencies: []
---

# UPDATE/DELETE at SERIALIZABLE take the snapshot before the lock

## Problem Statement

Writes.java:1004 runs the plain afterImages read before lockedRows (1014), so that read creates the read
view; the comment at 1013 says the opposite. A ScalarDB commit in between aborts the write even when it is
the transaction's first statement (spurious abort, not unsafe). Also the locked step takes S locks the DML
then upgrades to X: two driver writers of the same rows deadlock where RR would wait. Code trace only.

## Proposed Solutions

### Option 1
Run lockedRows before afterImages (or drop afterImages when locked != null); consider FOR UPDATE for the write's lock.

## Acceptance Criteria

- [ ] The first-statement write doesn't abort on a commit made before it; comment matches

## Recommended Action

## Work Log

- 2026-10-18: filed by the 8b2 review (cb4844f).
- 2026-10-18: fixed: lockedRows now runs before the plain reads; the S-to-X deadlock note left as is (an ordinary retryable deadlock).
